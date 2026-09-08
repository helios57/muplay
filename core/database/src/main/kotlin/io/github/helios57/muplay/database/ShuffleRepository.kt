package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.model.ShufflePlan
import io.github.helios57.muplay.model.ShuffleResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Random playback restricted to one library — the feature this application exists for.
 *
 * Music and audiobooks live in separate Navidrome libraries, and Navidrome hardcodes
 * `child.Type = "music"` for every media file, so nothing in a response can distinguish them. The
 * library id is the only mechanism, and `getRandomSongs` honours `musicFolderId`.
 *
 * This class adds the third defence on top of the type (`Int`, so an unparseable id is
 * unrepresentable) and the request assertion (`BrowseEndpointsTest`): **every returned id is
 * checked against the mirror**, and a song the mirror does not place in this library is dropped.
 * The failure being defended against is silent — an audiobook chapter simply starts playing —
 * so a defence that only works when something else already worked is not enough.
 *
 * On top of the scoping it applies the listener's own thumbs, through [ShufflePlan]: a demoted song
 * is dropped and a promoted one is weighted. That happens *after* the library guard, so a demotion
 * is never counted as a scoping fault.
 */
@Singleton
class ShuffleRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  /**
   * `Random.Default`, not an injected [Random].
   *
   * Every decision this source makes is [ShufflePlan]'s, and `ShufflePlanTest` drives that with a
   * seeded [Random] on the JVM tier -- so a binding here would buy a Hilt provider and a test for
   * the provider without making one product decision observable that is not already observable.
   * `ShuffleRepositoryTest` asserts the property that survives real randomness (more than one order
   * over many runs) rather than one seed's output.
   */
  private val random: Random = Random.Default

  /**
   * A queue of at most [requestedSize] songs from [libraryId], scoped, rated and shuffled.
   *
   * ### Why the number asked for is not the number requested
   *
   * [ShufflePlan] selects **without replacement**, so a pool no larger than the queue returns the
   * whole pool whatever anything is rated -- a thumb up would do nothing at all. So this asks the
   * server for [ShufflePlan.poolSizeFor] candidates, which is a multiple of the queue length capped
   * at what `getRandomSongs` will return, and the plan chooses the queue out of them.
   *
   * **This used to be a pure passthrough**, and its KDoc said so at length: the 500 clamp lives in
   * `SubsonicClient`, and clamping at two layers would make "the number on the wire" and "the number
   * this repository forwarded" two different numbers to reason about. That is still true of the
   * clamp -- this method does not clamp -- but the size on the wire is now a *derived* figure rather
   * than the caller's own, and `aRequestedSizeAbove500AsksForTheProtocolCapRatherThanAMultipleOfIt`
   * carries the rewritten record.
   *
   * ### Order of operations
   *
   * Scope first, then rate. The library guard is the defence this class exists for and it must see
   * every song the server returned, including the demoted ones -- `discardedOutOfScope` counts songs
   * the *mirror* disowns, and a demoted song filtered out beforehand would quietly stop being
   * counted if the server ever leaked one.
   */
  suspend fun shuffle(libraryId: Int, requestedSize: Int): ShuffleResult {
    val poolSize = ShufflePlan.poolSizeFor(requestedSize)
    val returned = sourceProvider.current().getRandomSongs(libraryId, poolSize)
    if (returned.isEmpty()) return ShuffleResult(emptyList(), discardedOutOfScope = 0)

    val confirmed = browseDao.songIdsInLibrary(libraryId, returned.map { it.id }).toSet()
    val kept = returned.filter { it.id in confirmed }
    return ShuffleResult(
      songs = ShufflePlan.queue(kept, requestedSize, random),
      discardedOutOfScope = returned.size - kept.size,
    )
  }

  companion object {
    /**
     * The size the browse UI asks for. Well under the protocol's 500 cap, and large enough that a
     * shuffle session does not run dry mid-listen.
     */
    const val DEFAULT_SHUFFLE_SIZE = 100
  }
}
