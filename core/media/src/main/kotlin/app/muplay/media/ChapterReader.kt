package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.Chapter as Media3Chapter
import androidx.media3.inspector.MetadataRetriever
import app.muplay.model.Chapter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads an audio file's own chapter atoms over HTTP.
 *
 * Navidrome exposes no chapter API and OpenSubsonic has no chapter schema (spec section 5), so this
 * is the only source there is. `media3-inspector` supplies `MetadataRetriever`; `media3-exoplayer`
 * does not depend on it, and `androidx.media3.exoplayer.MetadataRetriever` does not exist in
 * 1.11.0 at all -- spike S3 proved that with a `javac` probe.
 *
 * **`setMediaSourceFactory` is not optional.** Without it, spike S3 measured -- twice -- that
 * QuickTime `chap` chapters are dropped entirely and every Nero `chpl` chapter comes back with
 * `endTimeMs == C.TIME_UNSET`. There is no exception, no log line and no other signal; a `chpl`
 * file returns plausible-looking data. `ChapterReaderTest` asserts the broken form is broken, so
 * deleting the line below reddens two tests rather than none.
 *
 * `retrieveTrackGroups()` returns a Guava `ListenableFuture`, and it is awaited with a blocking
 * `get` on [Dispatchers.IO] rather than by adding `kotlinx-coroutines-guava` -- a whole artifact
 * for one `await()` is exactly what the dependency-minimalism constraint rules out.
 *
 * `androidx.annotation.OptIn`, not `kotlin.OptIn`: Media3's `@UnstableApi` is an
 * `androidx.annotation.RequiresOptIn`, invisible to the Kotlin compiler and enforced by Lint at
 * `lintDebug` -- see [TrackIdCacheKeyFactory] for the same note.
 */
@Singleton
@OptIn(UnstableApi::class)
class ChapterReader @Inject constructor(
  @ApplicationContext private val context: Context,
  private val dataSourceFactory: MuPlayDataSourceFactory,
) {

  /**
   * The chapters of [mediaId]'s file at [uri], ordered, de-duplicated and with every end time
   * populated.
   *
   * [contentDurationMs] fills the **last** chapter's end when the container did not carry one; the
   * caller already knows it from `Song.durationSeconds`, so nothing here has to guess.
   *
   * **[mediaId] is a parameter because [TrackIdCacheKeyFactory] makes it one.** The plan's listing
   * for this class read `read(uri, contentDurationMs)` and built `MediaItem.fromUri(uri)`, which
   * cannot work in this module: every request through [MuPlayDataSourceFactory] reaches a
   * `CacheDataSource` whose key factory **throws** [MissingCacheKeyException] on a `DataSpec` with
   * no key rather than falling back to the URI. Measured on the emulator -- all six tests in
   * `ChapterReaderTest` died with *"A media request reached the cache with no custom cache key
   * (track Ra14Y8yMKT8YPrtrt6delD)"*. Supplying the key is the fix that honours the invariant
   * rather than routing around it, and it is worth having on its own: a chapter probe pulls the
   * file's `moov` atom, and the next thing that happens to an audiobook is that it is played.
   */
  suspend fun read(mediaId: String, uri: String, contentDurationMs: Long): List<Chapter> = withContext(Dispatchers.IO) {
    val item = MediaItem.Builder().setUri(uri).setCustomCacheKey(mediaId).build()
    MetadataRetriever.Builder(context, item)
      // Required. See the class documentation; deleting this line is a silent correctness bug.
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory.create()))
      .build()
      .use { retriever ->
        val groups = retriever.retrieveTrackGroups().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        ChapterAssembly.assemble(rawChaptersOf(groups), contentDurationMs)
      }
  }

  private fun rawChaptersOf(groups: TrackGroupArray): List<RawChapter> = buildList {
    for (groupIndex in 0 until groups.length) {
      val group = groups.get(groupIndex)
      for (formatIndex in 0 until group.length) {
        val metadata = group.getFormat(formatIndex).metadata ?: continue
        for (entryIndex in 0 until metadata.length()) {
          val entry = metadata.get(entryIndex) as? Media3Chapter ?: continue
          add(
            RawChapter(
              startMs = entry.startTimeMs,
              // The sentinel stops here. Nothing below this line knows `C.TIME_UNSET` exists.
              endMs = entry.endTimeMs.takeIf { it != C.TIME_UNSET },
              // `getTitle()` is an `androidx.media3.common.Label`, not a `String` -- a detail easy
              // to miss, and `.toString()` on it does not print the title text.
              title = entry.title?.value,
            ),
          )
        }
      }
    }
  }

  companion object {
    /**
     * Generous, because a non-faststart file costs an extra Range round trip into the tail and a
     * cold container can be slow. A retriever that hangs forever would block a book screen with no
     * way out, so the read is bounded and the bound throws.
     *
     * **What the throw reaches was, for a while, wrong here.** This comment used to say a
     * `TimeoutException` "surfaces as chapters unavailable", and there was no such state anywhere:
     * `BookViewModel` and `BookPlayerViewModel` both read the timeline inside a bare
     * `viewModelScope.launch` with no `catch`, so an `ExecutionException` or `TimeoutException`
     * out of this line reached the thread's default handler and **killed the process**. Opening a
     * book with the server asleep crashed MuPlay. The comment described a design nobody had built.
     *
     * It is built now, and this is the chain, so that the claim is checkable rather than
     * aspirational: this throws, [ChapterRepository] remembers the failure against the media id
     * and rethrows, `BookViewModel` catches it and publishes `BookUiState.Chapters.Unavailable`,
     * and `BookScreen` renders that as a sentence with a retry where the chapter list would be --
     * leaving the cover, the position, both settings and `Resume` on screen and working, because
     * none of them needs a chapter. The book player is the quieter half of the same fix: it falls
     * back to an empty timeline, which it already renders as a transport with no chapter marks.
     */
    const val TIMEOUT_MS = 30_000L
  }
}
