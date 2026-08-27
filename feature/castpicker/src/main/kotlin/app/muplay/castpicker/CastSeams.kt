package app.muplay.castpicker

import app.muplay.cast.discovery.CastDevice
import app.muplay.cast.discovery.DiscoveryResult
import app.muplay.cast.session.CastSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * The two things the picker needs from the cast layer, as interfaces this module owns.
 *
 * Not a stylistic preference and not a mock-framework substitute. `RendererDirectory` is
 * constructed from four collaborators (a UDP transport, a destination list, an HTTP fetcher and a
 * persistent store) and `CastSessionManager` reaches for `Looper.getMainLooper()` in its very first
 * statement — so a JVM test of [CastViewModel] can build neither. This project bans mock frameworks
 * (`ConventionTest` rule 3 fails the build on the mere word), which leaves exactly one honest
 * option: name what the picker actually uses, and let the object graph supply the real thing.
 *
 * They are narrow on purpose. [CastControl] exposes four members out of `CastSessionManager`'s
 * surface, and in particular exposes **no `Player`**: a feature module holding the remote player
 * could seek it, stop it, or replace its queue behind the media session's back. What it can do is
 * set the speaker's volume, which is the one thing the picker is for.
 */
interface CastDiscovery {

  /**
   * One discovery pass: three multicast datagrams and a listen window, plus the remembered-device
   * fallback. Suspending and slow — seconds, not milliseconds — which is why the picker starts one
   * when it opens rather than keeping one running.
   */
  suspend fun discover(): DiscoveryResult
}

/** Starting, ending and adjusting a cast session. */
interface CastControl {

  val state: StateFlow<CastSessionState>

  /** Move playback to [device]. */
  suspend fun castTo(device: CastDevice)

  /** Back to the phone. */
  suspend fun stopCasting()

  /**
   * The speaker's own volume as a percentage, or `null` when nothing is being cast.
   *
   * Read rather than observed, because the only observable the media layer offers is the session
   * state and a volume that a *user* is dragging does not need a round trip to be believed. The
   * slider is seeded from this and drives [setDeviceVolumePercent] from there.
   */
  fun deviceVolumePercent(): Int?

  /**
   * Set the speaker's volume, 0–100.
   *
   * A percentage and not a fraction, because that is what `AVTransport`'s `RenderingControl` takes
   * and what `Player.setDeviceVolume` takes at both ends of this; the one place the slider's
   * `Float` becomes an `Int` is [CastViewModel.setVolume], where a JVM test can watch it.
   *
   * Call on the player's application thread — the main looper — like every other `Player` call.
   */
  fun setDeviceVolumePercent(percent: Int)
}
