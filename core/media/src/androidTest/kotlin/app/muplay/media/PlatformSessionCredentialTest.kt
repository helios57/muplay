package app.muplay.media

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muplay.cast.net.CredentialQuery
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Nothing this app puts on a `MediaItem` may carry the user's Subsonic credentials.**
 *
 * ### The surface, and why [ControllerAccessPolicy] does not cover it
 *
 * That policy refuses every `MediaController` the platform does not judge
 * `isTrustedForMediaControl`, and it holds. Its own KDoc names what it cannot reach: Media3's
 * `MediaSessionLegacyStub` mirrors the current item's metadata onto the **platform**
 * `MediaSession`, which any app the user has granted notification-listener access reads through
 * `MediaSessionManager.getActiveSessions` -- without ever connecting to [MuPlaybackService], and
 * therefore without ever meeting that gate.
 *
 * A Subsonic `t` is `md5(password + salt)` and the `s` beside it is that salt, so the pair is a
 * **non-expiring password equivalent** granting the whole API as the user. It was on every item, in
 * `MediaMetadata.artworkUri`, and therefore on that session as `ART_URI`, `ALBUM_ART_URI` and
 * `DISPLAY_ICON_URI`.
 *
 * ### Which fields this scans, and why that list is what it is
 *
 * Read out of `media3-session-1.11.0.aar` rather than guessed.
 * `LegacyConversions.convertToMediaMetadataCompat` writes the string metadata fields, the artwork
 * URI under three keys, **and every key of `MediaMetadata.extras`** -- it iterates
 * `extras.keySet()`. `convertToMediaDescriptionCompat`, which builds each platform queue item,
 * copies the whole extras `Bundle` too. So "hide the URL in an extra" would have moved the same
 * string to the same surface under a different name, and this scan covers extras for that reason.
 *
 * The one field it does **not** have to cover is the stream URL, and that too is measured rather
 * than hoped: the stub passes `MediaItem.requestMetadata.mediaUri` as the `mediaUri` argument, not
 * `localConfiguration.uri`. `MediaItems.of` sets no `requestMetadata`, so `METADATA_KEY_MEDIA_URI`
 * is never written. `theStreamUrlIsCredentialBearingAndStaysOffTheSession` pins both halves of
 * that, because it is the assumption that would silently stop being true.
 *
 * ### The fixture is a real client, deliberately
 *
 * `QueueRepositoryTest`'s `RecordingSource` answers `https://host/rest/getCoverArt?id=..&size=..`
 * -- no auth parameters at all. That is precisely why nothing in this module could see the leak:
 * the only URLs any test ever looked at were credential-free. This class builds URLs through the
 * **real** `SubsonicClient`, whose `authParams()` is the thing that mints `u`, `t` and `s`, so the
 * scan has something real to find. The password below is not one, and `t` is the md5 of it, which
 * is a hash of nothing anybody uses.
 */
@RunWith(AndroidJUnit4::class)
class PlatformSessionCredentialTest {

  @Test
  fun noFieldOfAQueueItemThatReachesThePlatformSessionCarriesACredential(): Unit = runTest {
    val item = itemFor(SONG)

    assertThat(credentialFieldsOf(item)).isEmpty()
  }

  @Test
  fun theArtworkUriIsACoverArtIdRatherThanAnAuthenticatedUrl(): Unit = runTest {
    // The specific field the leak lived in, and the specific shape that replaced it. Asserted as
    // well as the scan above because "no credentials" is also satisfied by an item with no artwork
    // at all, and losing cover art everywhere would be a regression this class must not wave
    // through.
    val item = itemFor(SONG)

    assertThat(item.mediaMetadata.artworkUri.toString()).isEqualTo("${ArtworkUri.SCHEME}:art-1")
    assertThat(ArtworkUri.coverArtIdOf(item.mediaMetadata.artworkUri.toString())).isEqualTo("art-1")
  }

  @Test
  fun aSongWithNoCoverArtCarriesNoArtworkUriAtAll(): Unit = runTest {
    // The other arm, so the assertion above cannot be satisfied by a constant.
    val item = itemFor(SONG.copy(coverArtId = null))

    assertThat(item.mediaMetadata.artworkUri).isNull()
    assertThat(credentialFieldsOf(item)).isEmpty()
  }

  @Test
  fun theStreamUrlIsCredentialBearingAndStaysOffTheSession(): Unit = runTest {
    // Two facts in one place, because each is only meaningful beside the other.
    //
    // The first is the positive control: the URL this fixture builds really does carry `u`, `t` and
    // `s`. Without it every assertion in this class would pass against a fixture that never had a
    // credential in it -- which is exactly how the original leak survived a full suite.
    //
    // The second is the measured reason the stream URL needs no fix of its own:
    // `MediaSessionLegacyStub` publishes `requestMetadata.mediaUri`, and `MediaItems.of` sets none,
    // so `METADATA_KEY_MEDIA_URI` is never written. If a later change starts setting
    // `requestMetadata`, this goes red and the stream URL becomes the next thing to move.
    val item = itemFor(SONG)

    assertThat(CredentialQuery.parametersIn(item.localConfiguration!!.uri.toString()))
      .containsExactly("u", "t", "s")
    assertThat(item.requestMetadata.mediaUri).isNull()
  }

  @Test
  fun theScanWouldFailIfAnyScannedFieldEverCarriedOne(): Unit = runTest {
    // A guard nobody has seen go red is a guard nobody has seen work. This drives the scanner over
    // an item built the old way -- a credentialed cover URL on `artworkUri` -- and requires it to
    // report. It is the only place in this class that constructs such an item, and it hands it to
    // nothing.
    val leaky = itemFor(SONG).buildUpon()
      .setMediaMetadata(
        itemFor(SONG).mediaMetadata.buildUpon()
          .setArtworkUri(android.net.Uri.parse(client.coverArtUrl("art-1", 512)))
          .build(),
      )
      .build()

    assertThat(credentialFieldsOf(leaky)).containsExactly("artworkUri")

    // ...and an extra carries just as far, which is the half a reader would not expect: Media3
    // copies every extras key onto the platform metadata and onto every queue item's description.
    val hidden = itemFor(SONG).buildUpon()
      .setMediaMetadata(
        itemFor(SONG).mediaMetadata.buildUpon()
          .setExtras(
            android.os.Bundle().apply { putString("art", client.coverArtUrl("art-1", 512)) },
          )
          .build(),
      )
      .build()

    assertThat(credentialFieldsOf(hidden)).containsExactly("extras[art]")
  }

  // ---- harness -----------------------------------------------------------------------------------

  /**
   * The item, built the way `QueueRepository` builds one: real stream URL, real client, real
   * `MediaItems.of`.
   */
  private fun itemFor(song: Song): MediaItem = MediaItems.of(
    song = song,
    streamUri = client.streamUrl(song.id, StreamFormat.Raw),
    artworkId = song.coverArtId,
    isAudiobook = false,
    format = StreamFormat.Raw,
  )

  /**
   * The names of every field on [item] that would reach the platform session **and** carries a
   * credential.
   *
   * Names rather than values, and that is not tidiness: an assertion message is the one string in
   * this project that reliably reaches a bug report, and a leak detector that printed the token it
   * found would be a second leak.
   */
  private fun credentialFieldsOf(item: MediaItem): List<String> {
    val metadata = item.mediaMetadata
    val named = listOf<Pair<String, CharSequence?>>(
      "mediaId" to item.mediaId,
      "requestMetadata.mediaUri" to item.requestMetadata.mediaUri?.toString(),
      "title" to metadata.title,
      "displayTitle" to metadata.displayTitle,
      "subtitle" to metadata.subtitle,
      "description" to metadata.description,
      "artist" to metadata.artist,
      "albumTitle" to metadata.albumTitle,
      "albumArtist" to metadata.albumArtist,
      "writer" to metadata.writer,
      "composer" to metadata.composer,
      "artworkUri" to metadata.artworkUri?.toString(),
    )
    val extras = metadata.extras?.let { bundle ->
      bundle.keySet().map { key -> "extras[$key]" to bundle.get(key)?.toString() }
    }.orEmpty()
    return (named + extras)
      .filter { (_, value) -> CredentialQuery.carries(value?.toString()) }
      .map { (name, _) -> name }
  }

  private val client = SubsonicClient(
    SubsonicCredentials(
      baseUrl = "https://nav.example",
      username = "listener",
      password = "not-a-real-password",
    ),
  )

  private companion object {
    val SONG = Song(
      id = "track-1",
      libraryId = 1,
      title = "Track One",
      albumId = "album",
      albumName = "An Album",
      artistId = "artist",
      artistName = "An Artist",
      trackNumber = 1,
      discNumber = null,
      durationSeconds = 300,
      suffix = "mp3",
      coverArtId = "art-1",
    )
  }
}
