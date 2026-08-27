package app.muplay.cast.session

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.ServedMedia

/**
 * One queued track, as this module needs it: **already extracted from whatever the caller's queue
 * is made of**.
 *
 * `androidx.media3.common.MediaItem` is built on `android.net.Uri` and `android.os.Bundle`, both of
 * which are unimplemented stubs on a bare JVM -- so a type holding one could not be constructed in
 * this module's tests at all, and the mapping below could only ever be gated on a device. Taking
 * the nine values instead puts the whole field mapping in Tier 1, which is the same split Plan 3
 * made for `StreamRetryPolicy`. Task 9's `MediaItem` -> `CastSource` extraction is the only part
 * that stays on the device, and it is nine assignments with nothing to decide.
 *
 * [upstreamUrl] is a Navidrome stream URL and therefore **carries the user's credentials** -- the
 * Subsonic `u`, `t` and `s` parameters. [toString] is overridden for that reason: a `data class`
 * prints every field, and this type is held in a list inside a session, which is exactly the shape
 * that reaches a log line or an assertion failure message by accident. [CastPlayback] does not
 * carry it at all.
 */
data class CastSource(
  val mediaId: String,
  val title: String,
  val artist: String?,
  val albumTitle: String?,
  val artworkUri: String?,
  /** `0` when unknown. A negative sentinel is normalised away by [CastItems.of], not here. */
  val durationMs: Long,
  val isAudiobook: Boolean,
  /** The Navidrome URL the phone fetches from. **Credential-bearing.** */
  val upstreamUrl: String,
  val served: ServedMedia,
) {
  override fun toString(): String =
    "CastSource(mediaId=$mediaId, title=$title, artist=$artist, albumTitle=$albumTitle, " +
      "artworkUri=$artworkUri, durationMs=$durationMs, isAudiobook=$isAudiobook, " +
      "upstreamUrl=$REDACTED_UPSTREAM, served=$served)"

  companion object {
    /** What [toString] prints instead of a credential-bearing URL. */
    const val REDACTED_UPSTREAM: String = "<redacted>"
  }
}

/**
 * The [CastSource] -> [CastItem] mapping, which is a field copy and one decision.
 *
 * Field mappings are where this project has shipped the same defect repeatedly -- a value
 * hardcoded, or copied from the neighbouring field -- so this is a pure function with its own test
 * rather than a block inside the session's load path. The resource URL arrives separately because
 * it is not the source's: it is whatever [app.muplay.cast.route.CastRouter] decided, which for the
 * ordinary case is a proxy URL on this phone and not [CastSource.upstreamUrl] at all.
 */
object CastItems {

  /**
   * @param resourceUrl what the **renderer** will fetch. Never [CastSource.upstreamUrl] unless the
   *   route really is renderer-direct.
   */
  fun of(source: CastSource, resourceUrl: String): CastItem = CastItem(
    mediaId = source.mediaId,
    title = source.title,
    artist = source.artist,
    albumTitle = source.albumTitle,
    artworkUri = source.artworkUri,
    // A duration this app does not know arrives as Media3's `C.TIME_UNSET`, which is
    // `Long.MIN_VALUE + 1`. Rendered into `res@duration` as a negative clock time it makes several
    // renderers refuse the item outright, so "unknown" is 0 -- which UpnpTime renders as 0:00:00
    // and every renderer reads as "length unknown, play it anyway".
    durationMs = source.durationMs.coerceAtLeast(0L),
    // The one field that is a decision rather than a copy. A renderer's display and its own
    // library grouping read it, and Plan 4's books are the reason it exists.
    upnpClass = if (source.isAudiobook) DidlLite.CLASS_AUDIO_BOOK else DidlLite.CLASS_MUSIC_TRACK,
    resourceUrl = resourceUrl,
    served = source.served,
  )
}
