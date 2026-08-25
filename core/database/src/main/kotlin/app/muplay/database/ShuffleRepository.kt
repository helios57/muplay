package app.muplay.database

import app.muplay.database.dao.BrowseDao
import app.muplay.model.ShuffleResult
import javax.inject.Inject
import javax.inject.Singleton

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
 */
@Singleton
class ShuffleRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  /**
   * [requestedSize] is forwarded to [SubsonicSourceProvider.current]'s `getRandomSongs`
   * unchanged: it is neither validated nor re-clamped here. `SubsonicClient`'s own `size` clamp
   * (Task 3, `MAX_RANDOM_SONGS` = 500) is what makes "the number on the wire" and "the number a
   * caller reasons about" the same one -- and it is made there, at the point the request is
   * built, deliberately not duplicated here: a second clamp at this layer would make those two
   * numbers different from each other instead. A [requestedSize] above 500 is therefore silently
   * truncated one layer down from this method, not by it -- see
   * `aRequestedSizeAbove500ReachesTheSourceUnclampedByThisRepository` for the passthrough this
   * documents, and `BrowseEndpointsTest`'s wire-level clamp tests for where the truncation
   * itself is proved.
   */
  suspend fun shuffle(libraryId: Int, requestedSize: Int): ShuffleResult {
    val returned = sourceProvider.current().getRandomSongs(libraryId, requestedSize)
    if (returned.isEmpty()) return ShuffleResult(emptyList(), discardedOutOfScope = 0)

    val confirmed = browseDao.songIdsInLibrary(libraryId, returned.map { it.id }).toSet()
    val kept = returned.filter { it.id in confirmed }
    return ShuffleResult(songs = kept, discardedOutOfScope = returned.size - kept.size)
  }

  companion object {
    /**
     * The size the browse UI asks for. Well under the protocol's 500 cap, and large enough that a
     * shuffle session does not run dry mid-listen.
     */
    const val DEFAULT_SHUFFLE_SIZE = 100
  }
}
