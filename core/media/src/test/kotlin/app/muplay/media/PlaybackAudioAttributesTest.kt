package app.muplay.media

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A JVM test, because [PlaybackAudioAttributes.contentTypeFor] takes an `Int` and returns an `Int`.
 *
 * That signature is the whole reason this type exists separately from the `AudioAttributes` builder
 * beside it: the decision is gated by the fast tier, the object construction is not. Same split as
 * `StreamRetryPolicy` and, one layer down, as `KeystoreCipher` taking a `SecretKey`.
 */
class PlaybackAudioAttributesTest {

  @Test
  fun `an audiobook chapter is speech`() {
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }

  @Test
  fun `an audiobook is speech`() {
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }

  @Test
  fun `music is music`() {
    // The other observation. Without it, `contentTypeFor` returning SPEECH unconditionally passes
    // both tests above -- and a music player that declares everything to be speech ducks under a
    // navigation prompt in a way nobody would notice for months.
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_MUSIC))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
  }

  @Test
  fun `anything this app has no opinion about is music`() {
    // MEDIA_TYPE_MIXED is what an unassigned library's items carry. Music is the safe default: it
    // is what the user is most likely playing, and speech attributes on music is the more
    // audible mistake of the two.
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_MIXED))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
    assertThat(PlaybackAudioAttributes.contentTypeFor(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE))
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
  }

  @Test
  fun `the usage is always media`() {
    // USAGE_MEDIA is what puts this app on the media volume stream rather than the notification or
    // assistant stream. It does not vary with content type and it must not.
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC).usage)
      .isEqualTo(C.USAGE_MEDIA)
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).usage)
      .isEqualTo(C.USAGE_MEDIA)
  }

  @Test
  fun `the built attributes carry the content type the switch chose`() {
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_MUSIC).contentType)
      .isEqualTo(C.AUDIO_CONTENT_TYPE_MUSIC)
    assertThat(PlaybackAudioAttributes.of(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).contentType)
      .isEqualTo(C.AUDIO_CONTENT_TYPE_SPEECH)
  }
}
