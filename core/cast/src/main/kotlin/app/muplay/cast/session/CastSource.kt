package app.muplay.cast.session

import app.muplay.cast.didl.CastItem
import app.muplay.cast.didl.DidlLite
import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.net.CredentialQuery

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
 * **Two** of these fields carry the user's credentials -- the Subsonic `u`, `t` and `s` parameters
 * -- and [toString] is overridden for that reason: a `data class` prints every field, and this type
 * is held in a list inside a session, which is exactly the shape that reaches a log line or an
 * assertion failure message by accident. [CastPlayback] does not carry either of them at all.
 *
 * It used to redact only [upstreamUrl]. [artworkUri] is built by the same `authParams()` call --
 * `SubsonicClient.coverArtUrl` and `SubsonicClient.streamUrl` differ in their path and in nothing
 * else that matters here -- so a redaction that named one and printed the other was a blind spot
 * rather than a policy, and it printed the credential in full. Both are redacted now, and
 * `CastSourceTest`'s `toString prints no credential-bearing url, whichever field carries one`
 * observes it through [CredentialQuery] rather than by matching a string, so a third such field
 * added later is caught by the same assertion.
 */
data class CastSource(
  val mediaId: String,
  val title: String,
  val artist: String?,
  val albumTitle: String?,
  /**
   * The cover image, as a **credential-bearing** Navidrome `getCoverArt` URL.
   *
   * Never handed to a renderer as it stands: [CastItems.of] takes the URL a renderer should fetch
   * as a separate argument, which for a proxied route is a capability token on this phone. See
   * [app.muplay.cast.route.CastRoute.Proxied.artwork].
   */
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
      "artworkUri=${redacted(artworkUri)}, durationMs=$durationMs, isAudiobook=$isAudiobook, " +
      "upstreamUrl=$REDACTED_UPSTREAM, served=$served)"

  companion object {
    /** What [toString] prints instead of a credential-bearing URL. */
    const val REDACTED_UPSTREAM: String = "<redacted>"

    /**
     * [url], or [REDACTED_UPSTREAM] when printing it would print a credential.
     *
     * Conditional, unlike [upstreamUrl]'s unconditional redaction, and the difference is honest:
     * a stream URL is always authenticated, whereas an artwork URI is `null` for most items and is
     * occasionally something harmless. Printing "<redacted>" for a `null` would make every
     * artwork-less item look like it was hiding something, which is how a redaction stops carrying
     * information.
     */
    internal fun redacted(url: String?): String? =
      if (CredentialQuery.carries(url)) REDACTED_UPSTREAM else url
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
   * @param artworkUrl what the **renderer** will fetch for the cover image -- a capability token on
   *   this phone, from [app.muplay.cast.route.CastRoute.Proxied.artwork]. `null` for an item with
   *   no artwork and for every non-proxied route. Never [CastSource.artworkUri]: that is
   *   Navidrome's own URL and it carries the user's password equivalent.
   */
  fun of(source: CastSource, resourceUrl: String, artworkUrl: String? = null): CastItem = CastItem(
    mediaId = source.mediaId,
    title = source.title,
    artist = source.artist,
    albumTitle = source.albumTitle,
    // **Never `source.artworkUri`**, which is Navidrome's own credential-bearing URL. What the
    // renderer is told to fetch is what the router published on this phone, and the guard below is
    // the backstop rather than the mechanism: a caller that passes a credential-bearing URL here
    // sends no cover at all, because a missing picture is a smaller harm than a leaked password
    // and because failing closed is the only direction a guard may fail in.
    artworkUri = artworkUrl?.takeUnless(CredentialQuery::carries),
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
