package app.muplay.cast.soap

import java.io.IOException

/** The `<UPnPError>` inside a SOAP fault. */
data class UpnpFault(val errorCode: Int, val errorDescription: String?)

/** A renderer refused an action, and said why. Not a transport failure -- see [SoapTransportException]. */
class UpnpErrorException(val action: String, val fault: UpnpFault) : IOException(
  "$action was refused: UPnP error ${fault.errorCode} " +
    "(${fault.errorDescription ?: UpnpError.describe(fault.errorCode)})",
)

/** The renderer could not be reached, or answered something this client cannot read. */
class SoapTransportException(val action: String, val statusCode: Int, cause: Throwable? = null) :
  IOException("$action failed at the transport: HTTP $statusCode", cause)

/**
 * The UPnP error codes this client branches on or reports, from the UPnP Device Architecture's
 * common set and the `AVTransport:1` service template.
 *
 * These strings reach a user, through the cast picker's failure line. That is why they are
 * constants with assertions rather than whatever a future `toString` produces.
 */
object UpnpError {

  /** Common errors, UPnP Device Architecture. */
  const val INVALID_ACTION: Int = 401
  const val INVALID_ARGS: Int = 402
  const val ACTION_FAILED: Int = 501

  /** `AVTransport:1` service-specific errors. */
  const val TRANSITION_NOT_AVAILABLE: Int = 701
  const val NO_CONTENTS: Int = 702
  const val READ_ERROR: Int = 703
  const val FORMAT_NOT_SUPPORTED: Int = 704
  const val TRANSPORT_IS_LOCKED: Int = 705
  const val SEEK_MODE_NOT_SUPPORTED: Int = 710
  const val ILLEGAL_SEEK_TARGET: Int = 711
  const val ILLEGAL_MIME_TYPE: Int = 714
  const val RESOURCE_NOT_FOUND: Int = 716
  const val PLAY_SPEED_NOT_SUPPORTED: Int = 717
  const val INVALID_INSTANCE_ID: Int = 718

  private val DESCRIPTIONS: Map<Int, String> = mapOf(
    INVALID_ACTION to "Invalid Action",
    INVALID_ARGS to "Invalid Args",
    ACTION_FAILED to "Action Failed",
    TRANSITION_NOT_AVAILABLE to "Transition not available",
    NO_CONTENTS to "No contents",
    READ_ERROR to "Read error",
    FORMAT_NOT_SUPPORTED to "Format not supported for playback",
    TRANSPORT_IS_LOCKED to "Transport is locked",
    SEEK_MODE_NOT_SUPPORTED to "Seek mode not supported",
    ILLEGAL_SEEK_TARGET to "Illegal seek target",
    ILLEGAL_MIME_TYPE to "Illegal MIME-type",
    RESOURCE_NOT_FOUND to "Resource not found",
    PLAY_SPEED_NOT_SUPPORTED to "Play speed not supported",
    INVALID_INSTANCE_ID to "Invalid InstanceID",
  )

  /** An unknown code is reported **as itself**, so a device's own number reaches the log. */
  fun describe(code: Int): String = DESCRIPTIONS[code] ?: "UPnP error $code"
}
