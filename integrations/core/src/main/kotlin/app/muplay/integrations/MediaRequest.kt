package app.muplay.integrations

/**
 * One thing the user asked an integration for.
 *
 * [id] is `"<SERVICE>:<externalId>"`, which makes re-requesting the same album an *update* rather
 * than a duplicate row, and keeps two services that happen to use the same identifier space from
 * colliding onto one row. [externalId] is whatever the service identifies the work by — for Lidarr
 * a MusicBrainz id; see `:integrations:bindery` for Bindery's.
 *
 * [remoteId] is the id the *service* assigned after accepting the request, and is `null` until it
 * does. It is what status polling looks the request up by, and it is separate from [externalId]
 * for a reason: one is the identity of the work in the world, the other is the identity of a row
 * in someone's database.
 */
data class MediaRequest(
  val id: String,
  val service: IntegrationService,
  val externalId: String,
  val title: String,
  val subtitle: String,
  val remoteId: String?,
  val status: RequestStatus,
  val requestedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  companion object {
    fun idFor(service: IntegrationService, externalId: String): String = "${service.name}:$externalId"
  }
}
