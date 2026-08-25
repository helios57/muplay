package app.muplay.integrations

/**
 * Where one submitted request has got to.
 *
 * A sealed interface, per this project's "sealed interfaces for state and results" rule, and the
 * members are deliberately about **what the user can see next**, not about either service's
 * internal vocabulary. Lidarr and Bindery describe their pipelines differently; each client maps
 * its own vocabulary onto these five, and the mapping is tested against real captured payloads
 * rather than invented (see Tasks 6 and 7).
 *
 * [Imported] and [Arrived] are two different facts and collapsing them would be a bug of exactly
 * the kind spec section 4 warns about for capability negotiation. "The service says the files are
 * on disk" is not "Navidrome has scanned them and you can press play"; the gap between the two is
 * a whole scan cycle, and Task 8 is the code that closes it.
 *
 * Persisted as a `(name, detail)` pair of TEXT columns rather than as a Room `TypeConverter` over
 * a JSON blob: two columns are queryable, greppable in a bug report and readable in a database
 * dump, and this type has one data field per member at most.
 */
sealed interface RequestStatus {

  /** The service accepted the request and has not started fetching anything yet. */
  data object Requested : RequestStatus

  /** The download client is working. [percentComplete] is `null` when the service does not say. */
  data class Downloading(val percentComplete: Int?) : RequestStatus

  /** The service reports the files are in place. Navidrome has **not** necessarily seen them. */
  data object Imported : RequestStatus

  /** The mirror has it: [albumId] is a Navidrome album id and the UI can navigate to it. */
  data class Arrived(val albumId: String) : RequestStatus

  /** The service, or this client, could not complete the request. [reason] is shown to the user. */
  data class Failed(val reason: String) : RequestStatus

  companion object {

    /**
     * Reconstitutes a status from its two stored columns.
     *
     * Every unreadable case becomes a [Failed] that **names what it saw**, rather than a plausible
     * default. A corrupt row read as [Requested] tells the user their request is still in progress
     * forever and tells whoever reads the bug report nothing at all; a corrupt row read as
     * `Failed("unrecognised stored status \"X\"")` tells both.
     */
    fun fromStored(name: String, detail: String?): RequestStatus = when (name) {
      "REQUESTED" -> Requested
      "DOWNLOADING" -> Downloading(percentComplete = detail?.toIntOrNull())
      "IMPORTED" -> Imported
      "ARRIVED" ->
        if (detail.isNullOrEmpty()) Failed("stored ARRIVED row carried no album id") else Arrived(detail)
      "FAILED" -> Failed(detail ?: "the request failed")
      else -> Failed("unrecognised stored status \"$name\"")
    }
  }
}

/**
 * The value stored in the `status` column. Stable on disk; renaming a member does not change it.
 *
 * **An extension property rather than a member with a default getter, and that is a measurement
 * rather than a style choice.** A `val storedName: String get() = ...` declared inside the
 * interface compiles to a JVM default method *plus* a `RequestStatus$DefaultImpls` Java-compat
 * bridge which no Kotlin call site ever reaches: measured here at **LINE 0/4**, in a class no
 * coverage rule can honestly gate (0.90 fails it, and a 0.00 minimum is the unfireable floor this
 * project has already shipped once and banned). As an extension the same `when` cascade lands in
 * `RequestStatusKt`, reachable and gated, and every call site still reads `status.storedName`.
 */
val RequestStatus.storedName: String
  get() = when (this) {
    RequestStatus.Requested -> "REQUESTED"
    is RequestStatus.Downloading -> "DOWNLOADING"
    RequestStatus.Imported -> "IMPORTED"
    is RequestStatus.Arrived -> "ARRIVED"
    is RequestStatus.Failed -> "FAILED"
  }

/**
 * The value stored in the `status_detail` column, or `null` for members that carry no data.
 *
 * An extension for the same measured reason as [storedName] above.
 */
val RequestStatus.storedDetail: String?
  get() = when (this) {
    RequestStatus.Requested -> null
    is RequestStatus.Downloading -> percentComplete?.toString()
    RequestStatus.Imported -> null
    is RequestStatus.Arrived -> albumId
    is RequestStatus.Failed -> reason
  }
