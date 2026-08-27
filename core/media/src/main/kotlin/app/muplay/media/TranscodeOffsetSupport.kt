package app.muplay.media

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import app.muplay.database.SubsonicSourceProvider
import app.muplay.model.ServerCapabilities
import app.muplay.network.SubsonicSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether this server can start a transcode part-way through, and -- when it can -- how to ask.
 *
 * ### The gate
 *
 * **Defaults to "no" and stays there until a negotiation succeeds.** That is the conservative
 * direction: "no" withdraws the seek command on a transcode, which shows the user a disabled seek
 * bar for the second or two after the service starts. "Yes" by default would offer a seek that
 * silently does nothing, which is the failure this whole task exists to remove.
 *
 * This is the **first caller of `ServerCapabilities.supports` anywhere in the project**. Plan 1
 * built the three-tier negotiation and stored the versions list rather than a boolean, and nothing
 * had ever asked it a question; `transcodeOffset` is the one capability gate spec section 4 names,
 * so it is the right first one.
 *
 * ### Why it is also the [TranscodeSeekSupport]
 *
 * Because re-issuing needs the same two things the gate already holds -- the answer, and the
 * `SubsonicSource` that answered -- and `MuPlayer.seekTo` runs on the player's application thread
 * and **cannot suspend**. `SubsonicSourceProvider.current()` is `suspend` (it reads DataStore and
 * unseals a password), so the source has to be captured somewhere ahead of the seek. Capturing it
 * here, beside the answer it was negotiated with, keeps one object holding one session's worth of
 * "what this server can do", rather than a second class holding a second copy of the credentials.
 *
 * The session-scoped assumption is stated rather than hidden: [refresh] runs once per
 * `MuPlaybackService` lifetime, and a user who signs into a **different server** without the
 * service being recreated keeps the previous server's answer and the previous server's source until
 * it is. That is the same window [isSupported] has always had; nothing here narrows or widens it.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn` -- see `MuPlayerFactory` for the full argument.
// `MediaItem.Builder.setCustomCacheKey` is the `@UnstableApi` member used below, and the Kotlin
// compiler cannot see that annotation at all; `check` fails at `lintDebug` instead, long after this
// file compiled clean. It is the same member, for the same reason, `MediaItems` opts into.
@OptIn(UnstableApi::class)
@Singleton
class TranscodeOffsetSupport @Inject constructor(
  private val sourceProvider: SubsonicSourceProvider,
) : TranscodeSeekSupport {

  @Volatile private var negotiated: Negotiated? = null

  /** Whether the server this session is signed into advertises `transcodeOffset`. */
  val isSupported: Boolean get() = negotiated?.supportsOffset == true

  /**
   * Negotiates capabilities once, if that has not already succeeded.
   *
   * Called by `PlaybackLauncher` on every play, which costs one round trip per session and none
   * afterwards. **Not** from `MuPlaybackService.onCreate`, which is where it started and is the
   * wrong place: the service is created the first time anything binds a `MediaController`, which
   * on a first run is *before* the user has finished signing in. The negotiation then failed, and
   * because it ran once per service lifetime the gate answered "not supported" for the rest of the
   * session -- withdrawing the seek bar on every transcode, silently and for the wrong reason.
   */
  override suspend fun refreshIfUnknown() {
    if (negotiated == null) refresh()
  }

  /**
   * Negotiates capabilities, unconditionally.
   *
   * A failure -- no network, wrong credentials, a server that does not implement the command --
   * leaves the conservative answer standing rather than propagating: this is a *capability* query,
   * and spec section 4's rule for one is to degrade, not to fail the thing that asked. It also
   * leaves [refreshIfUnknown] willing to try again, which is what stops one bad moment deciding a
   * whole session.
   *
   * On `Dispatchers.IO` because the caller is `MuPlaybackService`'s main-thread scope and
   * [SubsonicSourceProvider.current] unseals the stored password with an AndroidKeystore key --
   * cheap, but not free, and not something to do on the thread that draws.
   */
  suspend fun refresh() {
    negotiated = withContext(Dispatchers.IO) {
      runCatching {
        val source = sourceProvider.current()
        Negotiated(source, source.capabilities().supports(EXTENSION))
      }.getOrNull()
    }
  }

  override fun methodFor(mediaItem: MediaItem, targetPositionMs: Long): SeekMethod =
    TranscodeSeek.methodFor(
      formatWireValue = MediaItems.streamFormatOf(mediaItem)?.wireValue.orEmpty(),
      serverSupportsTranscodeOffset = isSupported,
      targetPositionMs = targetPositionMs,
    )

  /**
   * [mediaItem] with its URI rebuilt at [timeOffsetSeconds], its cache key moved with it, and the
   * offset recorded on the item so `MuPlayer` can report real-track time.
   *
   * Returns [mediaItem] unchanged when the item carries no format extra or when nothing has been
   * negotiated yet. Both are states [methodFor] cannot have answered `ReissueWithOffset` from, so
   * neither is reachable from `MuPlayer`; they are here because a public method that would
   * otherwise have to throw is a worse answer than one that changes nothing.
   *
   * `buildUpon()` and not a fresh `MediaItem.Builder`: the mediaId, the MIME type, the artwork and
   * every metadata field are the *same track's*, and the only things that change are the three
   * below. A rebuilt-from-scratch item is how a seek quietly loses a title.
   */
  override fun reissue(mediaItem: MediaItem, timeOffsetSeconds: Int): MediaItem {
    val format = MediaItems.streamFormatOf(mediaItem) ?: return mediaItem
    val source = negotiated?.source ?: return mediaItem
    val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle())
    extras.putLong(MediaItems.KEY_TIME_OFFSET_MS, timeOffsetSeconds * MILLIS_PER_SECOND)
    return mediaItem.buildUpon()
      .setUri(source.streamUrl(mediaItem.mediaId, format, timeOffsetSeconds).toUri())
      // Not the bare track id -- see `TranscodeSeek.cacheKeyFor`. An offset stream filed under the
      // track's own key is written into the middle of the full track's cache entry.
      .setCustomCacheKey(TranscodeSeek.cacheKeyFor(mediaItem.mediaId, timeOffsetSeconds))
      .setMediaMetadata(mediaItem.mediaMetadata.buildUpon().setExtras(extras).build())
      .build()
  }

  /** One session's negotiated answer and the source it was negotiated with, replaced atomically. */
  private class Negotiated(val source: SubsonicSource, val supportsOffset: Boolean)

  companion object {
    /**
     * The one capability gate spec section 4 names by name.
     *
     * Aliased from `:core:model` rather than spelled here, so the live test that watches the pinned
     * container advertise it and this gate that reads it are the same string -- see
     * [ServerCapabilities.TRANSCODE_OFFSET_EXTENSION] for why the constant cannot live in this
     * module.
     */
    const val EXTENSION: String = ServerCapabilities.TRANSCODE_OFFSET_EXTENSION

    private const val MILLIS_PER_SECOND = 1000L
  }
}
