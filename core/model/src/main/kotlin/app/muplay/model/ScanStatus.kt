package app.muplay.model

/**
 * The server's scan state, from `getScanStatus`.
 *
 * [lastScan] is Navidrome's own extension to the Subsonic `ScanStatus` element and is **not** in
 * the vendored OpenSubsonic spec (see `NavidromeSpecDeviationTest`). It is treated as an
 * **opaque token**, never parsed as a timestamp: the only question the sync engine asks is
 * "is this the same string as the one I last committed?". A server that changed its format, or
 * one that does not send the field at all, degrades to "cannot tell" rather than to a parse
 * error — which is why the type is `String?` and not an `Instant`.
 */
data class ScanStatus(
  val isScanning: Boolean,
  val scannedCount: Int?,
  val lastScan: String?,
)
