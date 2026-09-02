package app.muplay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.muplay.media.ArtworkUrls
import app.muplay.media.PlaybackEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * The **application's own** [ArtworkUrls], for the journeys that build a `PlaybackConnection` of
 * their own rather than using the singleton.
 *
 * A `MediaItem` carries `muplay-art:<coverArtId>` rather than an authenticated cover URL, because
 * everything on an item is mirrored onto the platform media session and a Subsonic cover URL is a
 * non-expiring password equivalent. `ArtworkUrls` is what turns the identifier back into a URL an
 * image loader can fetch, in this process, and a connection built without one publishes no
 * `PlaybackState.artworkUri` at all -- which would quietly delete the artwork leg of
 * `MuPlaybackServiceTest.everyPlaybackStateFieldReachesTheUiSideOfTheConnection`.
 *
 * Reached from the running graph rather than constructed here for the reason every other accessor
 * in `PlaybackEntryPoint` is: a second `CredentialStore` over the same DataStore file is refused by
 * the platform, per process, and it fails at *use* rather than at construction -- so a hand-built
 * one would look fine until an assertion ran and would then name DataStore rather than itself.
 */
fun appArtworkUrls(
  context: Context = ApplicationProvider.getApplicationContext(),
): ArtworkUrls =
  EntryPointAccessors.fromApplication(context, PlaybackEntryPoint::class.java).artworkUrls()
