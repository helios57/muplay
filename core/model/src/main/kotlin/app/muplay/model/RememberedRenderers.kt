package app.muplay.model

/** A device seen before, kept so it can be found again when multicast cannot reach it. */
data class RememberedRenderer(
  val udn: String,
  val friendlyName: String,
  /** The `LOCATION` this device last announced. A `String`, so the store needs no URI converter. */
  val descriptionUrl: String,
)

/**
 * Persistence for [RememberedRenderer], implemented outside this module because this is a record
 * with no behaviour and the store that holds it is an Android one.
 *
 * **Backed by DataStore, not by Room** -- see `RendererStore` in `:core:database`. This is a
 * bounded list of at most [MAX_REMEMBERED] flat records with no query, no join and no ordering
 * requirement. A Room table for it would cost a schema version bump and a migration, and buy
 * nothing; and this plan's standing rule is that a migration in Plan 6 means something has been
 * added that belongs to another plan.
 *
 * [remember] takes [RememberedRenderer], **not `CastDevice`**. That is what lets this interface
 * live here at all: `CastDevice` is a `:core:cast` type, and naming it would drag the whole cast
 * module into every consumer of `:core:database` -- the lowest module in the tree, which
 * everything else depends on. The mapping lives in `:core:cast`, next to the type that needs
 * mapping -- see `CastDevice.remembered()`.
 */
interface RememberedRenderers {
  suspend fun load(): List<RememberedRenderer>
  suspend fun remember(renderers: List<RememberedRenderer>)
  suspend fun forget(udn: String)

  companion object {
    /** More speakers than any household this app is designed for, and a hard bound on the store. */
    const val MAX_REMEMBERED: Int = 16
  }
}
