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

  suspend fun shuffle(libraryId: Int, requestedSize: Int): ShuffleResult {
    // The size clamp lives in SubsonicClient: Navidrome caps `size` at 500 and truncates
    // silently, so the number on the wire and the number a caller reasons about are made the
    // same one at the point the request is built, not here.
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
