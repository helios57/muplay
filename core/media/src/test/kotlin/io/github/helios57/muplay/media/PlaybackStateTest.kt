package io.github.helios57.muplay.media

import androidx.media3.common.MediaMetadata
import io.github.helios57.muplay.cast.net.CredentialQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A plain JVM test, because [PlaybackState] is a plain Kotlin value with no Android or Media3 type
 * in it -- the same split `StreamRetryPolicy` gets, and for the same reason: the part of the media
 * layer that can be gated by the fast tier should be.
 *
 * What is worth asserting about a `data class` is only the part a person wrote, and here that is
 * [PlaybackState.NOTHING_PLAYING]. It is not decoration: four other tasks render from this value
 * before anything is loaded, and each field is a separate claim about what the UI should show then.
 * A `hasNext = true` renders an enabled "next" button with no queue behind it; a non-null `title`
 * names a track that is not loaded; a non-zero `durationMs` gives a seek bar a length it invented.
 *
 * The mapping *into* a non-empty state lives in `PlaybackConnection.publish`, takes a
 * `androidx.media3.common.Player`, and cannot be reached from here at all -- this project has no
 * Robolectric. It is gated on the device by
 * `MuPlaybackServiceTest.everyPlaybackStateFieldReachesTheUiSideOfTheConnection`, which observes
 * every field of it at a value no other field could have supplied.
 */
class PlaybackStateTest {

  @Test
  fun `nothing playing is neither playing nor buffering`() {
    assertThat(PlaybackState.NOTHING_PLAYING.isPlaying).isFalse()
    // Not the same claim: "buffering" is what a UI renders as a spinner, and a state that is
    // neither playing nor buffering is the only one that renders as idle.
    assertThat(PlaybackState.NOTHING_PLAYING.isBuffering).isFalse()
  }

  @Test
  fun `nothing playing names no track`() {
    assertThat(
      listOf(
        PlaybackState.NOTHING_PLAYING.mediaId,
        PlaybackState.NOTHING_PLAYING.title,
        PlaybackState.NOTHING_PLAYING.artist,
        PlaybackState.NOTHING_PLAYING.albumTitle,
        PlaybackState.NOTHING_PLAYING.artworkUri,
      ),
      // `containsOnlyNulls` on the list rather than five separate `isNull()` calls, so a failure
      // names which of the five is populated instead of stopping at the first.
    ).containsOnlyNulls()
  }

  @Test
  fun `nothing playing is at zero of zero`() {
    assertThat(PlaybackState.NOTHING_PLAYING.positionMs).isZero()
    assertThat(PlaybackState.NOTHING_PLAYING.durationMs).isZero()
  }

  @Test
  fun `nothing playing can step neither forward nor back`() {
    assertThat(PlaybackState.NOTHING_PLAYING.hasNext).isFalse()
    assertThat(PlaybackState.NOTHING_PLAYING.hasPrevious).isFalse()
  }

  @Test
  fun `nothing playing claims to be neither a book nor a song, and plays at normal speed`() {
    // `MEDIA_TYPE_MIXED` rather than `MEDIA_TYPE_MUSIC`: nothing is loaded, so "this is a song" is
    // a claim, and it is the claim that would make `isAudiobook` false for the wrong reason.
    assertThat(PlaybackState.NOTHING_PLAYING.mediaType)
      .isEqualTo(MediaMetadata.MEDIA_TYPE_MIXED)
    assertThat(PlaybackState.NOTHING_PLAYING.isAudiobook).isFalse()
    // 1.0, not 0.0: a screen rendered before anything played would otherwise read "0.0x".
    assertThat(PlaybackState.NOTHING_PLAYING.speed).isEqualTo(1.0f)
  }

  @Test
  fun `both audiobook media types are recognised as a book`() {
    // Two values, because `MediaItems.of` stamps `MEDIA_TYPE_AUDIO_BOOK_CHAPTER` on a file inside a
    // book while a whole-book browse item carries `MEDIA_TYPE_AUDIO_BOOK`. A getter written with
    // only the first arm renders a whole-book item with music controls, and is green against any
    // test that only ever plays chapters.
    assertThat(state(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).isAudiobook).isTrue()
    assertThat(state(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK).isAudiobook).isTrue()
  }

  @Test
  fun `music and the unknown type are not books`() {
    // The false arms. Without them `isAudiobook` could be `true` unconditionally -- every line
    // covered, every floor green, and every listener sent to the wrong player screen.
    assertThat(state(MediaMetadata.MEDIA_TYPE_MUSIC).isAudiobook).isFalse()
    assertThat(state(MediaMetadata.MEDIA_TYPE_MIXED).isAudiobook).isFalse()
    // A neighbouring type, so "anything but music" cannot satisfy the rule either. A podcast
    // episode is spoken word and is still not a book.
    assertThat(state(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).isAudiobook).isFalse()
  }

  /** [PlaybackState.NOTHING_PLAYING] with one field moved -- the field under test and nothing else. */
  private fun state(mediaType: Int): PlaybackState =
    PlaybackState.NOTHING_PLAYING.copy(mediaType = mediaType)

  @Test
  fun `the player's own duration wins, because it measured what is playing`() {
    // Both sources present and disagreeing is the only arrangement in which "which one wins" is a
    // question at all. 3000 and 5000, not 3000 and 3000.
    assertThat(PlaybackState.durationMsOf(playerDurationMs = 3_000L, metadataDurationMs = 5_000L))
      .isEqualTo(3_000L)
  }

  @Test
  fun `the metadata's duration is used when the extractor had none`() {
    // The Opus case: the server transcodes on the fly, there is no Content-Length, and the player
    // reports `C.TIME_UNSET` for the whole track -- which reaches here as null. Without this, a
    // whole format renders as an unknown-length track on the lock screen and collapses Plan 3's
    // seek bar.
    assertThat(PlaybackState.durationMsOf(playerDurationMs = null, metadataDurationMs = 5_000L))
      .isEqualTo(5_000L)
  }

  @Test
  fun `an unknown duration is zero, never a negative sentinel`() {
    assertThat(PlaybackState.durationMsOf(playerDurationMs = null, metadataDurationMs = null))
      .isZero()
    // A negative from either source is a sentinel that leaked, not a length. A UI that renders it
    // shows a seek bar running backwards; `coerceAtLeast` is what stops that, and this is the case
    // that fails when it is removed.
    assertThat(PlaybackState.durationMsOf(playerDurationMs = -1L, metadataDurationMs = 5_000L))
      .isZero()
  }

  /**
   * **The one field here that carries the user's password equivalent, and what happens when
   * something prints it.**
   *
   * This class's own KDoc states the rule -- a cover-art URL carries the same `t` and `s` a stream
   * URL does, and it "must not be logged, printed, or asserted whole" -- and a `data class` gets a
   * compiler-generated `toString` that prints every property, so the type documented the promise
   * and then broke it. `PublishedMedia` and `PublishedArtwork` in `:core:cast` were fixed for
   * exactly this; `PlaybackState` was missed, because the convention rule that polices it matches
   * property *names* (`apiKey`, `password`, `token`, ...) and `artworkUri` is not one.
   *
   * Nothing prints a `PlaybackState` today, so this is a latent leak rather than an active one.
   * The path that makes it real is the cheapest one imaginable: a failing AssertJ assertion on a
   * `PlaybackState` renders the whole value into a test report, and this repository's own device
   * tier asserts on these.
   *
   * Written against [CredentialQuery], not against the literal "u", "t" and "s", for the reason
   * `PublishedRedactionTest` gives: a test that spelled the parameters itself would keep passing
   * if the auth scheme gained a fourth.
   */
  @Test
  fun `a printed playback state does not carry the credentials on its artwork url`() {
    val printed = PlaybackState.NOTHING_PLAYING.copy(
      title = "Track 1",
      artworkUri =
        "https://music.example.com/rest/getCoverArt?id=al-1&u=listener" +
          "&t=0123456789abcdef0123456789abcdef&s=abcd1234",
    ).toString()

    assertThat(CredentialQuery.carries(printed))
      .describedAs("a printed PlaybackState still carries a Subsonic auth parameter: %s", printed)
      .isFalse()
    // Positive control: redaction must not be achieved by printing nothing useful, or the
    // assertion above would pass over an empty string.
    assertThat(printed).contains("PlaybackState", "Track 1")
  }

  /**
   * The other side of the one branch that `toString` has, and it is a real distinction rather than
   * a coverage errand.
   *
   * Redacting `artworkUri` unconditionally would print `<redacted>` for a state that has no cover
   * at all, and "there is no artwork" is the answer a reader of this line usually wants -- it is
   * the difference between a track the server gave no `coverArt` id and one whose URL is simply
   * not being shown. Absence is not a secret, so `null` stays `null`.
   */
  @Test
  fun `a printed playback state with no artwork says so rather than redacting one it does not have`() {
    val printed = PlaybackState.NOTHING_PLAYING.copy(title = "Track 1", artworkUri = null).toString()

    assertThat(printed).contains("artworkUri=null")
    assertThat(printed).doesNotContain("<redacted>")
  }

}
