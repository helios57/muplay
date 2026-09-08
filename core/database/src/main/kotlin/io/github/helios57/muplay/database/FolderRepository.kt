package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.model.FolderListing
import io.github.helios57.muplay.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Browsing and shuffling the library by folder, read entirely from the local mirror.
 *
 * The server has a folder API (`getIndexes` / `getMusicDirectory`), and this deliberately does not
 * use it: that would be one request per folder the user opens, would not work offline, and would
 * make "shuffle this folder and everything under it" a recursive walk over the network. Every
 * mirrored song carries its own path (schema version 8), so the whole feature is one indexed
 * prefix query and some string arithmetic.
 *
 * **One query answers both halves of a listing.** [listing] reads the songs beneath a prefix once
 * and lets [FolderPaths] split them into subfolders and the tracks lying directly in the folder,
 * so the count shown beside a folder and the queue that shuffling it builds come from the same
 * rows and cannot disagree.
 */
@Singleton
class FolderRepository @Inject constructor(
  private val browseDao: BrowseDao,
) {

  /**
   * What the folder screen at [path] shows. [path] is `""` for the library root.
   *
   * The read is bounded by the subtree, so the root listing reads every pathed song in the
   * library — the same shape as `BrowseDao.songsInLibraries`, which the bookshelf already does.
   */
  fun listing(libraryId: Int, path: String): Flow<FolderListing> =
    browseDao.observeSongsUnder(libraryId, FolderPaths.likePatternFor(path)).map { rows ->
      val songs = rows.map(MirrorMapper::song)
      FolderListing(
        path = path,
        folders = FolderPaths.children(path, songs.mapNotNull { it.path }),
        tracks = songs.filter { song -> song.path?.let { FolderPaths.isDirectlyIn(path, it) } == true },
      )
    }

  /**
   * Every song beneath [path] at any depth, in path order — what "shuffle this folder" draws from.
   *
   * Recursive by construction rather than by a walk: the prefix pattern matches the whole subtree
   * in one query, so a folder holding only subfolders still shuffles everything under it, which is
   * the case a per-directory implementation gets wrong.
   *
   * Ordered, not shuffled. The caller decides whether to shuffle, because "play this folder in
   * order" is the other thing a listener asks of it and there is no reason to make that a second
   * query.
   */
  suspend fun songsUnder(libraryId: Int, path: String): List<Song> =
    browseDao.songsUnder(libraryId, FolderPaths.likePatternFor(path)).map(MirrorMapper::song)

  /**
   * How many songs in this library know their path.
   *
   * Zero on a mirror written before schema version 8 and not yet reconciled. The folder screen
   * needs it to tell that apart from a library that genuinely has no folders: both render as an
   * empty list, and only one of them is worth explaining to the user.
   * [io.github.helios57.muplay.database.MIGRATION_7_8] is what makes the first case short-lived.
   */
  fun pathedSongCount(libraryId: Int): Flow<Int> = browseDao.observePathedSongCount(libraryId)
}
