package app.muplay.media.browse

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.media.NoOpPlayer
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * *"Carry on"*, driven through the **real** callback a session is built with.
 *
 * The plan for this task said the resumption callback could only be gated by a later task's
 * end-to-end journey. It cannot be gated by a real reboot, which is true -- but the callback itself
 * is an ordinary method on an ordinary object, and this module already builds that object out of a
 * real Room mirror for the browse suites. So the decision, the snapshot refresh and the
 * `C.TIME_UNSET` are all observable here, at the layer that ships, rather than deferred.
 *
 * What is *not* observable here is the system deciding to call it. Media3 declares two overloads
 * and the three-argument one's default body delegates to the two-argument one (verified in the
 * 1.11.0 bytecode); this drives the two-argument one, which is the one that is overridden.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackResumptionTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private lateinit var graph: BrowseGraph
  private lateinit var callback: MuPlayLibraryCallback
  private lateinit var session: MediaSession

  @After
  fun tearDown() {
    if (::session.isInitialized) {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        session.player.release()
        session.release()
      }
    }
    if (::callback.isInitialized) callback.release()
    if (::graph.isInitialized) graph.close()
  }

  /**
   * Builds the graph, the real callback and an inert session.
   *
   * `NoOpPlayer` rather than an `ExoPlayer`: `PlayerConstructionTest` forbids a second
   * `ExoPlayer.Builder` anywhere in this module, test sources included, and this suite's subject is
   * what the callback *answers* -- Media3, not this test, is what would apply it to a player.
   */
  private fun build(withProgress: Boolean = true) {
    graph = BrowseGraph.create(context, withProgress = withProgress)
    callback = graph.callback(context)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      session = MediaSession.Builder(context, NoOpPlayer())
        .setId("playback-resumption-test-${System.nanoTime()}")
        .build()
    }
  }

  private fun resume(): MediaSession.MediaItemsWithStartPosition =
    callback.onPlaybackResumption(session, controller()).get(TIMEOUT_S, TimeUnit.SECONDS)

  @Test
  fun theSystemIsHandedTheMostRecentlyHeardUnfinishedBookAtTheFileItWasLeftIn() {
    build()
    runBlocking {
      graph.database.mediaProgressDao()
        .upsert(MediaProgressEntity("bk-test-p2", 20_000L, false, 9_000L, 1f, false, 0f))
    }

    val answer = resume()

    assertThat(answer.mediaItems.map { it.mediaId })
      .containsExactly("bk-test-p1", "bk-test-p2", "bk-test-p3")
    assertThat(answer.startIndex).isEqualTo(1)
    // Playable, not merely named: a browse row carries no `localConfiguration` and Media3 refuses a
    // `MediaItem` with no URI outright. This is the half that says the callback went through
    // `QueueRepository` rather than handing back bare ids.
    assertThat(answer.mediaItems.map { it.localConfiguration }).doesNotContainNull()
  }

  @Test
  fun thePositionIsLeftToTheResumePolicy() {
    // Spec section 3's guarantee at the coldest path in the application. `C.TIME_UNSET` is what
    // `MuPlayer` discards before asking `AudiobookResumePolicy`; a real number here would be a
    // second opinion about where a book resumes, and the two would eventually disagree.
    build()

    assertThat(resume().startPositionMs).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun theSnapshotIsWarmedBeforeTheItemsAreAnswered() {
    // **The cold-start race, at the one place it actually happens.** This callback runs at process
    // start, so the snapshot's collector has typically not emitted; without the refresh the policy
    // answers `null` for every id and the book the system just resumed starts at zero. Nothing
    // throws, nothing logs, and it reproduces once a month on a slow device.
    build()
    // The premise: nobody has started or refreshed this snapshot, so it knows nothing yet.
    assertThat(graph.audiobookSnapshot.isLoaded)
      .describedAs("the snapshot must be cold before the callback runs, or this proves nothing")
      .isFalse

    resume()

    assertThat(graph.audiobookSnapshot.isLoaded).isTrue
    // ...and it holds a real answer, not merely a latch that flipped: the item the queue starts on
    // has the position the seed wrote for it.
    assertThat(graph.audiobookSnapshot.itemFor("bk-second-p1")?.positionMs).isEqualTo(50_000L)
  }

  @Test
  fun nothingToCarryOnWithFailsTheFutureRatherThanStartingSilence() {
    // Failing the future is the API's way of saying "leave the resumption control alone". An empty
    // queue would put a control in the shade that plays nothing when pressed.
    build(withProgress = false)

    assertThatThrownBy { resume() }
      .isInstanceOf(ExecutionException::class.java)
      .hasCauseInstanceOf(UnsupportedOperationException::class.java)
      .hasMessageContaining(MuPlayLibraryCallback.NOTHING_TO_RESUME)
  }

  /**
   * The system's own resumption request, as `MediaSessionStub` would build it.
   *
   * Trusted, because the caller is SystemUI: `ControllerAccessPolicy` would refuse an untrusted
   * one at `onConnect`, and this callback is only ever reached for a controller that got past it.
   */
  private fun controller(): MediaSession.ControllerInfo =
    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
      /* packageName = */ "com.android.systemui",
      /* pid = */ Process.myPid(),
      /* uid = */ Process.myUid(),
      /* libraryVersion = */ 1,
      /* interfaceVersion = */ 1,
      /* isTrusted = */ true,
      /* connectionHints = */ Bundle.EMPTY,
      /* isPackageNameVerified = */ true,
    )

  private companion object {
    const val TIMEOUT_S = 15L
  }
}
