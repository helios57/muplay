package app.muplay.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata

/**
 * Spec section 5's one-line switch: books are speech, everything else is music.
 *
 * The switch is genuinely one line. It gets a type of its own — and a JVM test — because a wrong
 * `contentType` is **invisible**: focus still works, the app still pauses for a call, and the
 * difference only shows up where nobody looks. A navigation prompt should duck music and interrupt
 * speech, and a car mixes a notification differently over each.
 *
 * The input is `MediaMetadata.mediaType`, which [MediaItems] sets from the user's own `LibraryRole`
 * assignment. It is never inferred from a file suffix or from any server field: Navidrome hardcodes
 * `child.Type = "music"` for every media file, so the protocol cannot answer this question at all.
 *
 * Split from the builder call beside it on purpose: [contentTypeFor] takes an `Int` and returns an
 * `Int`, so the *decision* is gated by the fast tier while the object construction — which reaches
 * Media3 types — is not. Same split as `StreamRetryPolicy` behind the Media3 error adapter.
 */
object PlaybackAudioAttributes {

  /** The Media3 `C.AUDIO_CONTENT_TYPE_*` for a `MediaMetadata.MEDIA_TYPE_*`. */
  fun contentTypeFor(mediaType: Int): Int = when (mediaType) {
    MediaMetadata.MEDIA_TYPE_AUDIO_BOOK,
    MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
    -> C.AUDIO_CONTENT_TYPE_SPEECH
    // Music by default, including for the media types this app never sets. Speech attributes on
    // music is the more audible of the two mistakes, so the default is the quieter one.
    else -> C.AUDIO_CONTENT_TYPE_MUSIC
  }

  /**
   * The full attributes for a media type. `USAGE_MEDIA` always — it is what puts this app on the
   * media volume stream rather than the notification or assistant stream, and it does not vary
   * with content.
   */
  fun of(mediaType: Int): AudioAttributes =
    AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(contentTypeFor(mediaType))
      .build()
}
