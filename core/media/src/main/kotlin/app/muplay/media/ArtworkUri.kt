package app.muplay.media

/**
 * **The cover-art URI this app puts on a `MediaItem`: an identity, not a credential.**
 *
 * ### What went wrong without it
 *
 * `MediaItems.of` used to carry the artwork URL `SubsonicClient.coverArtUrl` builds, which appends
 * `authParams()`: `u`, `s=<salt>` and `t=md5(password + salt)`. That triple is a **non-expiring
 * password equivalent** granting the whole Subsonic API as the user, and it cannot be revoked
 * without changing the password.
 *
 * [ControllerAccessPolicy] closes the Media3 controller surface -- `MuPlayLibraryCallback.onConnect`
 * refuses any caller the platform does not judge `isTrustedForMediaControl` -- and its own KDoc
 * already named what that gate does **not** close, and named this as the fix:
 *
 * > The platform `MediaSession` is a separate surface. `MediaSessionLegacyStub` mirrors the same
 * > metadata onto it (`METADATA_KEY_ART_URI`, `METADATA_KEY_DISPLAY_ICON_URI`), and any app the
 * > user has granted notification-listener access reads that directly, without ever calling
 * > [MuPlaybackService].
 *
 * Verified in the bytecode of `media3-session-1.11.0.aar` rather than assumed:
 * `LegacyConversions.convertToMediaMetadataCompat` writes `ART_URI`, `ALBUM_ART_URI` and
 * `DISPLAY_ICON_URI` from `MediaMetadata.artworkUri`, and it **also copies every key of
 * `MediaMetadata.extras`** onto the platform metadata, as does `convertToMediaDescriptionCompat`
 * for each queue item. So hiding the credentialed URL in an extra would not have been a fix at
 * all -- it would have moved the same string to the same surface under a different key.
 *
 * ### The shape, and why the picture survives
 *
 * The item carries `muplay-art:<coverArtId>`. A cover-art id is a library identifier, not a
 * secret; it grants nothing to anyone who cannot already authenticate. Nothing outside this process
 * can fetch it, and that costs the surfaces nothing, because none of them fetch a URI in the first
 * place: `MediaSessionLegacyStub` calls `getBitmapLoader().loadBitmapFromMetadata(...)` and mirrors
 * the resulting **Bitmap** as `METADATA_KEY_ALBUM_ART`, and `DefaultMediaNotificationProvider` does
 * the same for the notification. [MuPlayBitmapLoader] is the one component that resolves the scheme,
 * in this process, with credentials that never leave it.
 *
 * ### Not `Uri` -- `String`
 *
 * `android.net.Uri` is an unimplemented stub on a bare JVM, and this decision is a string
 * transformation with two inverse halves, which is exactly what the fast tier should hold. Callers
 * that need a `Uri` parse the result; `ArtworkUriTest` gates the pair here.
 */
object ArtworkUri {

  /**
   * The scheme, which is deliberately not `http`.
   *
   * A scheme no general-purpose loader recognises is the point: an app reading the platform session
   * gets a string it cannot dereference, and a mistake that put this URI somewhere it will be
   * fetched fails visibly rather than fetching something unintended.
   */
  const val SCHEME: String = "muplay-art"

  /** `muplay-art:<coverArtId>`, or `null` for a song the server has no art for. */
  fun of(coverArtId: String?): String? =
    coverArtId?.takeIf(String::isNotBlank)?.let { "$SCHEME:$it" }

  /**
   * The cover-art id inside [uri], or `null` when [uri] is not one of ours.
   *
   * `null` for an `http` URL as well as for rubbish, and that matters: it is what lets
   * [MuPlayBitmapLoader] hand anything else to its delegate untouched rather than guessing, and
   * what stops a browse item's own URL being mistaken for one of these.
   */
  fun coverArtIdOf(uri: String?): String? =
    uri?.takeIf { it.startsWith("$SCHEME:") }
      ?.removePrefix("$SCHEME:")
      ?.takeIf(String::isNotBlank)
}
