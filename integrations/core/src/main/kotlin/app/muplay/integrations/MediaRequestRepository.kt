package app.muplay.integrations

import app.muplay.integrations.db.MediaRequestDao
import app.muplay.integrations.db.MediaRequestEntity
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The only entry point to stored requests, per this project's repository rule.
 *
 * **Every method here that takes an argument is a place this project has previously shipped a
 * defect**: a delegating method that forwards to a DAO can discard its argument and hardcode a
 * value with the whole suite green at 100% branch coverage. `MediaRequestRepositoryTest` proves
 * passthrough by observing two disjoint results from the same method, never by observing that the
 * DAO was called.
 */
@Singleton
class MediaRequestRepository @Inject constructor(
  private val dao: MediaRequestDao,
  @IntegrationsClock private val clock: Clock,
) {

  fun requests(): Flow<List<MediaRequest>> = dao.observeAll().map { rows -> rows.mapNotNull(::toModel) }

  fun requests(service: IntegrationService): Flow<List<MediaRequest>> =
    dao.observeByService(service.name).map { rows -> rows.mapNotNull(::toModel) }

  /**
   * Records a request that a service has **already accepted**, and returns the stored row.
   *
   * Called after the submit succeeds, never before: a row that exists for a submit that failed is
   * a request the user believes they made and nobody is fulfilling.
   *
   * Re-recording the same `(service, externalId)` updates the existing row and **keeps its
   * original `requestedAtEpochMs`**, so a status poll that refreshes a row does not make the
   * user's "requested on" date jump forward. It keeps the row's existing status too, for the same
   * reason in the other direction: a refresh must not walk a finished download back to "queued".
   */
  suspend fun record(
    service: IntegrationService,
    externalId: String,
    title: String,
    subtitle: String,
    remoteId: String?,
  ): MediaRequest {
    val id = MediaRequest.idFor(service, externalId)
    val now = clock.millis()
    val existing = dao.find(id)
    val entity = MediaRequestEntity(
      id = id,
      service = service.name,
      externalId = externalId,
      title = title,
      subtitle = subtitle,
      remoteId = remoteId,
      status = existing?.status ?: RequestStatus.Requested.storedName,
      statusDetail = existing?.statusDetail ?: RequestStatus.Requested.storedDetail,
      requestedAtEpochMs = existing?.requestedAtEpochMs ?: now,
      updatedAtEpochMs = now,
    )
    dao.upsert(entity)
    // The service is already in hand here, so this is the one call that cannot fail to resolve it
    // and is the one path with a non-null return. It goes through the same single field-mapping
    // site as `requests()` does, deliberately: two construction sites for one model is how a
    // returned row and a stored row come to disagree about a field.
    return toModel(entity, service)
  }

  suspend fun setStatus(id: String, status: RequestStatus) {
    dao.updateStatus(
      id = id,
      status = status.storedName,
      statusDetail = status.storedDetail,
      updatedAtEpochMs = clock.millis(),
    )
  }

  suspend fun forget(id: String) = dao.delete(id)

  /**
   * `firstOrNull`, not `first`: `valueOf`/`first` would throw on a row written by a future version
   * of this app that added a third service, taking the whole list down with it. A row this build
   * cannot interpret is dropped by `mapNotNull` above — fail closed, toward showing less, never
   * toward crashing the screen the user is on.
   */
  private fun toModel(entity: MediaRequestEntity): MediaRequest? =
    IntegrationService.entries.firstOrNull { it.name == entity.service }
      ?.let { service -> toModel(entity, service) }

  private fun toModel(entity: MediaRequestEntity, service: IntegrationService) = MediaRequest(
    id = entity.id,
    service = service,
    externalId = entity.externalId,
    title = entity.title,
    subtitle = entity.subtitle,
    remoteId = entity.remoteId,
    status = RequestStatus.fromStored(entity.status, entity.statusDetail),
    requestedAtEpochMs = entity.requestedAtEpochMs,
    updatedAtEpochMs = entity.updatedAtEpochMs,
  )
}
