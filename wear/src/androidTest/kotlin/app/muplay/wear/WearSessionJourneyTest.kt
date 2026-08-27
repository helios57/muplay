package app.muplay.wear

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.CredentialStoreEntryPoint
import app.muplay.database.SyncState
import app.muplay.model.LibraryRole
import app.muplay.model.SubsonicCredentials
import app.muplay.model.browse.BrowseId
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A watch reaching the same service, browsing the same tree, playing the same audio.
 *
 * Nothing here is faked. The APK is `:wear`'s own debug build with its own `@HiltAndroidApp`
 * graph and its own Room database; the service is `MuPlaybackService` from `:core:media`, reached
 * across the binder exactly as it is from the phone app; the server is the pinned
 * `deluan/navidrome:0.63.2` from `ci/navidrome.compose.yml`, seeded by `ci/seed-fixtures.sh` and
 * configured by `ci/configure-libraries.sh`. `ci/prepare-wear-emulator.sh` establishes the two
 * preconditions this suite cannot establish for itself: a booted **watch** emulator and
 * `adb reverse tcp:4533 tcp:4533`.
 *
 * ### The first thing this file does is refuse a phone
 *
 * `:wear` installs and runs on a phone image without complaint -- `uses-feature` filters Play, not
 * `adb install` -- so a suite that ran on the wrong emulator would be green and prove nothing. It
 * would also reinstall `:app` underneath itself, because the two share the applicationId
 * `app.muplay`.
 *
 * [setUp] therefore asserts `PackageManager.FEATURE_WATCH` **before anything else**, so a wrong
 * device fails every test in the class rather than three out of four, and
 * [thisIsActuallyAWatch] states the same thing as a named test so that a report says which
 * property was violated. `ci/prepare-wear-emulator.sh` checks it a third time from outside the
 * APK: either of the two can be skipped, and neither knows about the other.
 *
 * ### What this suite is, and is not, evidence for
 *
 * It is the only thing in this build that can see `WearBrowser.connectAsync`'s connection hint.
 * The *hint mechanism* is separately and more cheaply tested by `:core:media`'s
 * `BrowseTreeBrowserTest`, which builds its own hints `Bundle` against a `MediaLibrarySession`
 * built in-process; what that cannot see is whether the watch app actually sends one. Delete the
 * `putString` in `WearBrowser` and everything still connects, browses and plays -- it just quietly
 * gets the phone's five root tabs.
 *
 * ### Names
 *
 * camelCase, and `Unit`-returning bodies. `minSdk 30` still compiles DEX 035, which forbids a space
 * in any `SimpleName` -- including the synthetic class Kotlin names after a backticked method's
 * lambda -- and JUnit 4 requires `void` test methods, which an expression body returning an
 * AssertJ assert object is not.
 */
@RunWith(AndroidJUnit4::class)
class WearSessionJourneyTest {

  private lateinit var context: Context
  private lateinit var graph: WearTestEntryPoint
  private lateinit var wearBrowser: WearBrowser

  /** Whether this test connected. See [tearDown] for why that is worth tracking. */
  private var connected = false

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // First, and deliberately a hard failure rather than an `assumeTrue`: a skipped suite reads as
    // a green one, which is the whole defect this check exists to prevent.
    check(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
      "this suite is only meaningful on a Wear OS image; see ci/prepare-wear-emulator.sh"
    }

    graph = EntryPointAccessors.fromApplication(context, WearTestEntryPoint::class.java)
    wearBrowser = graph.browser()

    runBlocking {
      // Exactly what Task 10's Data Layer transport will later write, written here directly. The
      // watch's database is its own -- this is not the phone's row being read. Written through the
      // **real** singleton store reached from the production graph, never a second `CredentialStore`
      // over the same DataStore file, which throws "there are multiple DataStores active for the
      // same file" in this very process.
      EntryPointAccessors.fromApplication(context, CredentialStoreEntryPoint::class.java)
        .credentialStore()
        .save(SubsonicCredentials(SERVER_URL, USERNAME, PASSWORD))

      val libraries = graph.libraryRepository()
      libraries.refreshFromServer()
      // Tagged by NAME, not by a hardcoded id. `ci/configure-libraries.sh` renames Navidrome's
      // pinned library 1 to "Music" and *creates* "Audiobooks", whose id the server assigns -- so
      // an id written down here would be a guess about the container's state, and the roles are
      // what every browse path scopes on.
      libraries.libraries.first().forEach { library ->
        libraries.setRole(
          library.id,
          when (library.name) {
            MUSIC_LIBRARY -> LibraryRole.MUSIC
            AUDIOBOOK_LIBRARY -> LibraryRole.AUDIOBOOKS
            else -> LibraryRole.UNASSIGNED
          },
        )
      }

      // The browse tree reads the mirror and never the server, so without a committed sync every
      // assertion below is an assertion about an empty tree. A failed sync is checked rather than
      // left to surface as "the albums tab has no albums", which names the wrong thing.
      val state = graph.syncEngine().syncIfStale()
      check(state !is SyncState.Failed) { "the watch could not sync from Navidrome: $state" }
    }
  }

  @After
  fun tearDown() {
    // Guarded: `setUp` fails before `wearBrowser` is assigned on a non-watch device or an
    // unreachable container, and an `@After` that throws on an uninitialised `lateinit` replaces
    // the real failure with its own -- which has cost this repository a whole debugging session.
    if (!::wearBrowser.isInitialized) return

    // Only if this test actually connected. Resolving a browser here purely to close it would
    // start a connection every test, including the one that asserts nothing about the session --
    // and `WearBrowser.release`'s "there is nothing to release" path would then never run.
    //
    // Resolved on THIS thread and used on the main one, never the other way round:
    // `runBlocking { wearBrowser.browser() }` inside `onMain { }` deadlocks, because it would
    // block the main thread waiting for work posted to the main thread.
    if (connected) {
      val browser = runBlocking { wearBrowser.browser() }
      onMain {
        browser.stop()
        browser.clearMediaItems()
      }
    }
    wearBrowser.release()
    connected = false
  }

  @Test
  fun thisIsActuallyAWatch() {
    assertThat(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
      .describedAs("this suite is only meaningful on a Wear OS image; see ci/prepare-wear-emulator.sh")
      .isTrue
  }

  @Test
  fun theWatchGetsTheWatchTreeAndNotThePhoneOne() {
    // Three tabs, exactly, in order. Without the connection hint this app's package is
    // indistinguishable from the phone's and the session would answer with the phone's five
    // (`muplay/artists` and `muplay/libraries` as well). `containsExactly`, never `contains`: the
    // order is what a crown scroll reads.
    assertThat(childIds(BrowseId.Root.encode())).containsExactly(
      BrowseId.Continue.encode(),
      BrowseId.Books.encode(),
      BrowseId.Albums.encode(),
    )
  }

  @Test
  fun theWatchCanBrowseDownToATrack() {
    val album = children(BrowseId.Albums.encode())
      .first { BrowseId.decode(it.mediaId) is BrowseId.Album }
    val tracks = children(album.mediaId)

    // Real rows off the seeded corpus, reached through the service by a browser in this APK.
    assertThat(tracks).describedAs("the tracks of ${album.mediaId}").isNotEmpty
    assertThat(tracks.map { it.mediaMetadata.title?.toString() }).contains(FIRST_TRACK_TITLE)
    // No count and no exact list: the fixture corpus is shared with every other lane and has grown
    // twice already. What is asserted instead is the structural property a browse tree that had
    // merely echoed its parent back could not satisfy -- every child decodes as a playable track,
    // which is the one `BrowseId` that encodes to a bare server id with no `muplay/` prefix.
    assertThat(tracks.map { BrowseId.decode(it.mediaId) }).allMatch { it is BrowseId.Track }
    assertThat(tracks.map { it.mediaMetadata.isPlayable }).allMatch { it == true }
  }

  @Test
  fun anIdTheTreeDoesNotServeIsAnEmptyListAndNotACrash() {
    // The session answers an unrecognised id with `LibraryResult.ofError(ERROR_BAD_VALUE)` -- a
    // result with an error code and **no value** -- and `WearBrowser.children` turns that into an
    // empty list. This is the only observation in the suite that reaches the null half of that
    // `orEmpty()`, and a watch that threw here would crash on a browse id persisted by an older
    // build. `muplay/` prefixed, so it decodes as a browse node rather than as a bare server id.
    assertThat(children("muplay/no-such-node")).isEmpty()
    // ...and the connection survived it, so the error path is not a disguised disconnect.
    assertThat(childIds(BrowseId.Root.encode())).isNotEmpty
  }

  @Test
  fun theWatchStreamsFromNavidromeAndThePositionAdvances() {
    val album = children(BrowseId.Albums.encode())
      .first { BrowseId.decode(it.mediaId) is BrowseId.Album }
    val track = children(album.mediaId).first()
    val browser = connectedBrowser()

    onMain {
      browser.setMediaItem(track)
      browser.prepare()
      browser.play()
    }

    // The same standard as every other playback assertion in this project: a position that moved,
    // not a player that was asked to play. A CI watch emulator has no audio device (`-no-audio`)
    // and ExoPlayer's clock advances anyway, which is why this works headless.
    val first = awaitPositionAtLeast(1_000L)
    Thread.sleep(1_200)
    assertThat(onMain { browser.currentPosition }).isGreaterThan(first)
    // ...and it is still the track that was asked for, so a queue that restarted from index 0 or
    // wandered into the next item cannot satisfy the assertion above on its own.
    assertThat(onMain { browser.currentMediaItem?.mediaId }).isEqualTo(track.mediaId)
  }

  /** The connected browser. Resolved off the main thread; see [tearDown] for why that matters. */
  private fun connectedBrowser() = runBlocking { wearBrowser.browser() }.also { connected = true }

  private fun children(parentId: String): List<MediaItem> {
    connected = true
    return runBlocking { wearBrowser.children(parentId) }
  }

  private fun childIds(parentId: String): List<String> = children(parentId).map(MediaItem::mediaId)

  private fun awaitPositionAtLeast(positionMs: Long): Long {
    val browser = connectedBrowser()
    val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      val position = onMain { browser.currentPosition }
      if (position >= positionMs) return position
      Thread.sleep(POLL_MILLIS)
    }
    throw AssertionError(
      "position never reached ${positionMs}ms; state=${onMain { browser.playbackState }} " +
        "isPlaying=${onMain { browser.isPlaying }} error=${onMain { browser.playerError }} " +
        "item=${onMain { browser.currentMediaItem?.mediaId }}",
    )
  }

  /**
   * Runs [block] on the main thread. A `MediaBrowser` throws *"Player is accessed on the wrong
   * thread"* from every access off the `Looper` it was built on.
   */
  private fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private companion object {
    /** `ci/navidrome.compose.yml`'s credentials, reached through `adb reverse tcp:4533 tcp:4533`. */
    const val SERVER_URL = "http://localhost:4533"
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    /** The two names `ci/configure-libraries.sh` gives the container's libraries. */
    const val MUSIC_LIBRARY = "Music"
    const val AUDIOBOOK_LIBRARY = "Audiobooks"

    /** One title `ci/seed-fixtures.sh` writes into the music library. Not a count over the corpus. */
    const val FIRST_TRACK_TITLE = "Track 1"

    const val TIMEOUT_MILLIS = 40_000L
    const val POLL_MILLIS = 50L
  }
}
