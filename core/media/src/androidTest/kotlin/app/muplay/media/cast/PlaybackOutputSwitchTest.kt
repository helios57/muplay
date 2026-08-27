package app.muplay.media.cast

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.media.NoOpPlayer
import app.muplay.media.PlaybackOutputSwitch
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam itself, at the values a handover never produces.
 *
 * `HandoverTest` drives this class through a real cast, which is where it matters; these are the
 * arms that a working handover cannot reach -- a switch nothing has been installed into, and a
 * local player that refuses to pause.
 *
 * On the device tier because a `Player` is a Media3 type built on a `Looper`. `NoOpPlayer` is this
 * module's existing hand-written inert player; no mock framework is involved anywhere.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackOutputSwitchTest {

  @Test
  fun aSwitchNothingHasBeenInstalledIntoDrivesNothingAndDoesNotThrow() {
    // `MuPlaybackService` populates this in `onCreate`, so the window is short -- but a
    // `MediaController` connecting during it must find "nothing yet", and `returnToLocal` with no
    // local must not install a null over whatever is playing.
    val switch = PlaybackOutputSwitch()

    switch.returnToLocal()

    assertThat(switch.current()).isNull()
    assertThat(switch.localPlayer()).isNull()
    assertThat(switch.activePlayer.value).isNull()
  }

  @Test
  fun theLocalPlayerIsRememberedSeparatelyFromTheActiveOne() {
    // The distinction the service's `onDestroy` depends on: while casting, the session's player is
    // the speaker and the phone's own player is still there, paused. Releasing only what the
    // session held would leak a real `ExoPlayer` every time the app is swiped away mid-cast.
    val local = onMain { NoOpPlayer() }
    val remote = onMain { NoOpPlayer() }
    val switch = PlaybackOutputSwitch()
    onMain { switch.installLocal(local) }

    onMain { switch.installRemote(remote) }

    assertThat(switch.current()).isSameAs(remote)
    assertThat(switch.localPlayer()).isSameAs(local)
    onMain { switch.returnToLocal() }
    assertThat(switch.current()).isSameAs(local)
    onMain { local.release(); remote.release() }
  }

  @Test
  fun aRemoteInstalledBeforeThereIsALocalPlayerIsStillInstalled() {
    // The window between the service starting and `installLocal` running is short, and a cast
    // cannot legitimately begin inside it -- but "there is nothing to pause" must be a no-op rather
    // than the reason the switch is left half-done, with a position armed and no player consulting
    // it. This is the arm a working handover can never reach.
    val remote = onMain { NoOpPlayer() }
    val switch = PlaybackOutputSwitch()

    onMain { switch.installRemote(remote) }

    assertThat(switch.current()).isSameAs(remote)
    assertThat(switch.localPlayer()).isNull()
    onMain { remote.release() }
  }

  @Test
  fun aLocalPlayerThatRefusesToPauseDoesNotStopTheHandover() {
    // Pausing the outgoing player is a courtesy; the handover has already written the listener's
    // position down by the time it happens. A throw here would abandon the switch half-done, with
    // the position armed and no player consulting it.
    val local = onMain { RefusesToPause() }
    val remote = onMain { NoOpPlayer() }
    val switch = PlaybackOutputSwitch()
    onMain { switch.installLocal(local) }

    onMain { switch.installRemote(remote) }

    assertThat(local.pauseAttempts).isEqualTo(1)
    assertThat(switch.current()).isSameAs(remote)
    onMain { remote.release() }
  }

  /** A player whose `pause` throws. Hand-written, not a mock: this project bans mock frameworks. */
  @OptIn(UnstableApi::class)
  private class RefusesToPause : ForwardingPlayer(NoOpPlayer()) {
    var pauseAttempts = 0

    override fun pause() {
      pauseAttempts += 1
      throw IllegalStateException("this player will not pause")
    }
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }
}
