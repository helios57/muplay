package io.github.helios57.muplay.database

import io.github.helios57.muplay.database.dao.BrowseDao
import io.github.helios57.muplay.model.Playlist
import io.github.helios57.muplay.model.PlaylistWithSongs
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The user's server-side playlists, read live.
 *
 * **Not mirrored, unlike everything `BrowseRepository` serves.** A playlist is small, changes for
 * reasons no `lastScan` watermark reports (editing one is not a library rescan), and is the one
 * browse surface where a stale answer is actively wrong -- a listener who just added a track and
 * does not see it will conclude the app is broken. So this reads the server every time and has no
 * table. The cost is that playlists need a connection; the mirror still serves albums, folders and
 * books offline.
 *
 * The mirror is still consulted for one thing: which **library** each entry belongs to.
 */
@Singleton
class PlaylistRepository @Inject constructor(
  private val browseDao: BrowseDao,
  private val sourceProvider: SubsonicSourceProvider,
) {

  suspend fun playlists(): List<Playlist> = sourceProvider.current().getPlaylists()

  /**
   * Which libraries each of [playlists] draws from, keyed by playlist id.
   *
   * ### The server cannot be asked, so this is derived
   *
   * `getPlaylists` accepts `musicFolderId` and ignores it -- measured against the CI Navidrome with
   * one playlist holding a music track and another holding an audiobook chapter, both of which came
   * back for both libraries -- and no `getPlaylist` entry names a library either. So the only route
   * to "the playlists in this library" is to read every playlist's entries and look their ids up in
   * the mirror, which is what this does.
   *
   * ### An empty set means "unknown", and the caller shows those everywhere
   *
   * A playlist gets an empty set when it has no entries, when the mirror has never seen any of
   * them, or when the server refused the read. All three are the same thing to a filter -- nothing
   * was learned -- and the honest answer to that is to show the playlist under every library rather
   * than to hide it. Hiding a playlist the server says exists is the failure a user reports as
   * "my playlists are gone"; showing one that may not belong to this library costs a row.
   *
   * This is the same choice [playlist] makes for an entry the mirror does not recognise, and it is
   * deliberately *not* the same as stamping the unknown with the library being browsed: that would
   * put every playlist in every library and the filter would silently do nothing.
   *
   * ### The cache, and the one answer it refuses to keep
   *
   * One `getPlaylist` per playlist is the cost, so the answer is remembered under the playlist's
   * own [Playlist.changed] stamp -- an edit in any client changes it, and nothing else does.
   *
   * An empty answer for a playlist that **has** entries is not cached. That emptiness is a
   * statement about the *mirror*, not about the playlist, and the next sync fixes it without
   * touching `changed`; caching it would leave the filter needing an app restart to heal. An empty
   * answer for a playlist with no entries is cached, because that one really is final.
   *
   * A server that sends no `changed` at all degrades to no cache rather than to a stale one: the
   * hit requires a non-null stamp on both sides, so every visit re-derives. That is the safe
   * direction -- the alternative is a filter that never notices an edit -- and it costs nothing
   * against Navidrome, which sends the field on every playlist.
   */
  suspend fun librariesOf(playlists: List<Playlist>): Map<String, Set<Int>> = coroutineScope {
    // Concurrent, and bounded by OkHttp's own five-requests-per-host dispatcher rather than by a
    // semaphore here: a user with fifty playlists pays ten round trips on the first visit to the
    // tab and none on any later one.
    playlists
      .map { playlist -> async { playlist.id to librariesOfOne(playlist) } }
      .awaitAll()
      .toMap()
  }

  private suspend fun librariesOfOne(playlist: Playlist): Set<Int> {
    val stamp = playlist.changed
    cached[playlist.id]?.let { if (stamp != null && it.stamp == stamp) return it.libraries }

    // The stamp argument is irrelevant here and that is worth saying out loud: it is what
    // `getPlaylist` writes onto entries it cannot place, and every id is looked up in the mirror
    // below regardless. Passing the library being browsed would make every entry look local.
    val entries = runCatching {
      sourceProvider.current().getPlaylist(playlist.id, UNSTAMPED).songs
    }.getOrElse {
      // One playlist deleted in another client between the listing and this read is ordinary on a
      // live server, and it must not cost the other playlists their answer.
      return emptySet()
    }

    val libraries = if (entries.isEmpty()) {
      emptySet()
    } else {
      browseDao.songsByIds(entries.map { it.id }).map { it.libraryId }.toSet()
    }
    if (libraries.isNotEmpty() || entries.isEmpty()) {
      cached[playlist.id] = Scope(stamp, libraries)
    }
    return libraries
  }

  /**
   * Derived library sets, by playlist id.
   *
   * A plain concurrent map rather than anything guarded: the writes are idempotent -- two lanes
   * deriving the same playlist at once agree -- so the worst a race costs is one duplicated
   * request, which is cheaper than serialising the whole fan-out to avoid it.
   */
  private val cached = ConcurrentHashMap<String, Scope>()

  private data class Scope(val stamp: String?, val libraries: Set<Int>)

  /**
   * One playlist and its entries, with each entry's library corrected from the mirror.
   *
   * `getPlaylist` takes a `musicFolderId` only as a *stamp*: the protocol has no per-entry library
   * and a playlist may legitimately mix a music track with an audiobook chapter. That stamp is not
   * cosmetic -- `libraryId` matched against the user's `LibraryRole` assignment is the **only**
   * thing that tells this application a track is an audiobook (Navidrome hardcodes `Type = "music"`
   * for every media file), so it decides which player screen opens and whether a position is
   * remembered per book. Stamping a whole playlist with one library would therefore open the music
   * player on a book chapter.
   *
   * So every entry the mirror recognises is re-stamped with the library the mirror has it in.
   * Entries the mirror has never seen keep [fallbackLibraryId] -- the library the user is browsing
   * -- which is a guess, and the honest one: the alternative is dropping a track the server says is
   * in the playlist, and a playlist that silently loses rows is worse than one whose unknown row
   * opens the wrong player.
   */
  suspend fun playlist(playlistId: String, fallbackLibraryId: Int): PlaylistWithSongs {
    val fetched = sourceProvider.current().getPlaylist(playlistId, fallbackLibraryId)
    if (fetched.songs.isEmpty()) return fetched

    val known = browseDao.songsByIds(fetched.songs.map { it.id }).associate { it.id to it.libraryId }
    return fetched.copy(
      songs = fetched.songs.map { song ->
        val libraryId = known[song.id]
        if (libraryId == null || libraryId == song.libraryId) song else song.copy(libraryId = libraryId)
      },
    )
  }

  private companion object {
    /**
     * The `musicFolderId` [librariesOfOne] sends, which nothing reads.
     *
     * `getPlaylist` takes the parameter only as a stamp for entries it cannot place, and every
     * entry that call returns is looked up in the mirror by id straight afterwards. Named rather
     * than written as a bare `0` so that it cannot be mistaken for a library.
     */
    const val UNSTAMPED = 0
  }
}
