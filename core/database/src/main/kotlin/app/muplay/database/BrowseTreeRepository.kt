package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.SearchResults
import app.muplay.model.Song
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseSelection
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseTree
import app.muplay.model.browse.PlayFromSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Resolves a [BrowseId] into the data `BrowseTree` needs, and calls it.
 *
 * The only thing this class decides on its own is **scope**: music content comes from libraries the
 * user tagged `MUSIC`, and books from libraries tagged `AUDIOBOOKS`. Spec section 4 is why that
 * decision lives here rather than being trusted from upstream -- Navidrome hardcodes
 * `child.Type = "music"` for every file and there is no server-side signal at all, so the library
 * role is the *only* mechanism, and a browse tree that forgot it would put chapter 14 of a novel in
 * the Albums tab.
 *
 * **The scoping is structural, not a filter.** `BrowseRepository` has no "give me every album"
 * query -- every read it offers takes a `libraryId` (see `BrowseDao`'s own doc for why the absence
 * of an unscoped query is the point). So [musicAlbums] and [musicArtists] *ask only the Music
 * libraries*, and there is no line to delete that would widen them; widening them means changing
 * which libraries are asked. `BrowseTreeBrowserTest.theAlbumsTabHoldsNoAudiobookAlbum` is the
 * assertion that fails when someone does.
 *
 * Returns `null` from [children] for an id that names no browsable node -- a track, a shuffle row,
 * a one-file book, an album that is no longer in the mirror. The callback turns that into
 * `LibraryResult.ofError`, which is a different answer from an empty list and reads differently in
 * a car: "this is not a folder" rather than "this folder is empty".
 */
@Singleton
class BrowseTreeRepository @Inject constructor(
  private val libraryRepository: LibraryRepository,
  private val browseRepository: BrowseRepository,
  private val bookshelf: Bookshelf,
  private val shuffleRepository: ShuffleRepository,
) {

  suspend fun children(id: BrowseId, surface: BrowseSurface): List<BrowseNode>? = when (id) {
    BrowseId.Root -> rootChildren(surface)

    BrowseId.Continue -> BrowseTree.continueNodes(bookshelf.books(), surface.continueLimit)

    BrowseId.Books -> BrowseTree.bookNodes(bookshelf.books())

    BrowseId.Albums -> BrowseTree.albumsNodes(librariesWithRole(LibraryRole.MUSIC), musicAlbums())

    BrowseId.Artists -> BrowseTree.artistNodes(musicArtists())

    BrowseId.Libraries -> BrowseTree.libraryNodes(libraries())

    is BrowseId.Library -> libraries()
      .firstOrNull { it.id == id.libraryId }
      ?.let { library -> BrowseTree.libraryChildren(library, albumsIn(library.id)) }

    is BrowseId.Book -> bookshelf.book(id.bookId)
      // A one-file book is not browsable -- `BrowseTree.bookNodes` already says so, and this is the
      // same rule on the other side of the wire, for a controller that guessed the id.
      ?.takeIf { it.fileCount > 1 }
      ?.let { BrowseTree.bookChildren(bookshelf.files(id.bookId)) }

    is BrowseId.Album -> browseRepository.album(id.albumId)
      ?.let { BrowseTree.albumChildren(browseRepository.songs(id.albumId).first()) }

    // Scoped through the artist row this app already holds rather than through the caller's id:
    // Navidrome artist ids are global, so `albumsByArtist` needs a library of its own to mean
    // anything (see `ArtistEntity`). An artist in no Music library is not found, which is the
    // answer -- an unknown id must not silently widen to every library.
    is BrowseId.Artist -> musicArtists()
      .firstOrNull { it.id == id.artistId }
      ?.let { artist ->
        BrowseTree.artistChildren(browseRepository.albumsByArtist(artist.libraryId, artist.id).first())
      }

    // Playable leaves. Not an error and not empty: "not a folder".
    is BrowseId.Track, is BrowseId.Shuffle -> null
  }

  /**
   * One node, for `onGetItem`.
   *
   * Built by asking its **parent** for its children and picking it out, rather than by a second
   * construction path. Two paths that build the same node from the same data drift, and the drift
   * shows up as an item whose title in a list differs from its title on its own screen.
   */
  suspend fun node(id: BrowseId, surface: BrowseSurface): BrowseNode? = when (id) {
    // The root has its own dedicated call; see `MuPlayLibraryCallback.onGetItem`.
    BrowseId.Root -> null
    BrowseId.Continue, BrowseId.Books, BrowseId.Albums, BrowseId.Artists, BrowseId.Libraries ->
      rootChildren(surface).firstOrNull { it.id == id }
    is BrowseId.Library -> BrowseTree.libraryNodes(libraries()).firstOrNull { it.id == id }
    is BrowseId.Book -> bookshelf.book(id.bookId)?.let { BrowseTree.bookNodes(listOf(it)).single() }
    is BrowseId.Album ->
      browseRepository.album(id.albumId)?.let { BrowseTree.artistChildren(listOf(it)).single() }
    is BrowseId.Artist -> musicArtists().firstOrNull { it.id == id.artistId }
      ?.let { BrowseTree.artistNodes(listOf(it)).single() }
    // A shuffle row exists only where `libraryChildren` puts one, i.e. only for a Music library --
    // so an audiobook library's shuffle id is refused here for the same reason no such row is ever
    // offered. Spec section 1, expressed as the absence of an answer.
    is BrowseId.Shuffle -> libraries().firstOrNull { it.id == id.libraryId }
      ?.let { library -> BrowseTree.libraryChildren(library, emptyList()).firstOrNull { it.id == id } }
    is BrowseId.Track -> browseRepository.song(id.songId)?.let { BrowseTree.songNodes(listOf(it)).single() }
  }

  /**
   * The queue a playable browse id stands for, or `null` if the id is not playable.
   *
   * One rule: **a playable id becomes the queue it belongs to, positioned at itself.** A track
   * expands through its own album, which makes "play this track" mean "play this album from here"
   * for music and "play this book from this file" for a book -- the same code, because a book *is*
   * an album in a library the user tagged Audiobooks and spec section 4 says the server will never
   * distinguish them.
   *
   * **No position is computed here and none may be.** Spec section 3 puts the position behind
   * `MuPlayer`'s seam so that no code path can set a wrong one; this is a code path. The *index* is
   * this method's, because `ResumePolicy.resolve(mediaIds, requestedIndex)` cannot tell "play this
   * book" from "play chapter 1 from the top" -- the caller picks the index and the policy picks the
   * second.
   *
   * `null` and not [BrowseSelection.EMPTY] for a browsable-only id: `MuPlayLibraryCallback` turns
   * `null` into no items at all, which a car renders as "this is not something to play" rather than
   * as a queue it then fails to start.
   */
  suspend fun expand(id: BrowseId): BrowseSelection? = when (id) {
    is BrowseId.Album -> songsIn(id.albumId)?.let { BrowseSelection(it, startIndex = 0) }

    // Scoped through the shelf, the same way `children` is: a *music* album id spelled
    // `muplay/book/<id>` must not expand, or the one mechanism spec section 4 leaves this app --
    // the library role -- would be bypassed by an id a controller can type.
    is BrowseId.Book -> bookshelf.book(id.bookId)?.let {
      val files = bookshelf.files(id.bookId)
      files.takeIf { it.isNotEmpty() }?.let { songs ->
        // The caller picks the index; the policy picks the position. `resumeFileId` answers
        // "which file was I in", never "at what second".
        val resumeFileId = bookshelf.resumeFileId(id.bookId)
        BrowseSelection(songs, startIndex = resumeFileId?.let { startIndexOf(songs, it) } ?: 0)
      }
    }

    is BrowseId.Track -> {
      val song = browseRepository.song(id.songId)
      val albumId = song?.albumId
      when {
        song == null -> null
        // A loose track with no album is still playable; it is just a queue of one.
        albumId == null -> BrowseSelection(listOf(song), startIndex = 0)
        else -> songsIn(albumId)
          ?.let { siblings -> BrowseSelection(siblings, startIndexOf(siblings, song.id)) }
          // The album row is gone from the mirror but the song is not -- a sync that ran between
          // the browse and the tap. One track is a worse answer than the album and a better one
          // than silence.
          ?: BrowseSelection(listOf(song), startIndex = 0)
      }
    }

    is BrowseId.Shuffle -> shuffleSongs(id.libraryId)?.let { BrowseSelection(it, startIndex = 0) }

    // Browsable-only ids. The callback turns null into no items, which reads as "this is not
    // something to play" rather than as an empty queue.
    BrowseId.Root, BrowseId.Continue, BrowseId.Books, BrowseId.Albums, BrowseId.Artists,
    BrowseId.Libraries, is BrowseId.Library, is BrowseId.Artist,
    -> null
  }

  /** An album's songs in play order, or `null` when the mirror holds none. */
  private suspend fun songsIn(albumId: String): List<Song>? =
    browseRepository.songs(albumId).first().takeIf { it.isNotEmpty() }

  /**
   * The songs Plan 2's library-scoped shuffle produced, or `null` if it produced none.
   *
   * **`ShuffleResult` is a `data class`, not a sealed interface.** The plan asked for an exhaustive
   * `when` over its arms with no `else`, so that a new failure kind would fail to compile here
   * rather than be read as "no music"; there are no arms. What it carries instead is
   * `discardedOutOfScope`, a count of songs the server returned that this mirror does not place in
   * the requested library -- already dropped from `songs` by `ShuffleRepository` itself, which is
   * spec section 1's rule and the reason a shuffle row can be trusted in a car. Nothing here
   * re-filters it, and nothing here reads the count: a shuffle whose scope guard fired is still a
   * shuffle of in-scope music, and the count exists for a screen to explain a short list with.
   * If that type ever becomes sealed, this is the call site the plan meant.
   */
  private suspend fun shuffleSongs(libraryId: Int): List<Song>? =
    shuffleRepository.shuffle(libraryId, ShuffleRepository.DEFAULT_SHUFFLE_SIZE)
      .songs
      .takeIf { it.isNotEmpty() }

  /**
   * What a search box in a car should show: books first, then albums, then artists, then tracks.
   *
   * Plan 2's `search` answers against the **local mirror**, so this costs a Room query per library
   * and no network -- which is why nothing is cached between `onSearch` and `onGetSearchResult`.
   * A cache would be a map keyed by (controller, query) with an eviction policy, a staleness
   * question every time `SyncEngine` reconciles mid-drive, and a second code path that can disagree
   * with the first about how many results there are.
   *
   * **The books/music split is by library role, not by any property of the row.** Spec section 4 is
   * explicit that Navidrome never reports that something is an audiobook, so an album in a library
   * the user tagged Audiobooks *is* a book and one anywhere else is not. And it is **structural**
   * here, exactly as it is in [children]: `BrowseRepository.search` takes a `libraryId` and has no
   * unscoped form, so the audiobook libraries are asked for books and the music libraries are asked
   * for music. There is no filter to delete that would widen either.
   *
   * The matched book *albums* are turned back into [BookSummary] rows through the shelf rather than
   * rendered as albums, so a book found by search carries the completion pip it carries on the
   * Books tab. A row the shelf does not know is dropped, which is the same answer [children] gives.
   */
  suspend fun search(query: String): List<BrowseNode> {
    val bookAlbumIds = librariesWithRole(LibraryRole.AUDIOBOOKS)
      .flatMap { browseRepository.search(it.id, query, SEARCH_LIMIT).albums }
      .map(Album::id)
      .toSet()
    val books = bookshelf.books().filter { it.bookId in bookAlbumIds }

    val music = librariesWithRole(LibraryRole.MUSIC)
      .map { browseRepository.search(it.id, query, SEARCH_LIMIT) }

    return BrowseTree.searchNodes(
      books = books,
      albums = music.flatMap(SearchResults::albums),
      artists = music.flatMap(SearchResults::artists),
      songs = music.flatMap(SearchResults::songs),
    )
  }

  /**
   * The one thing a **spoken** query should start, already expanded into a queue.
   *
   * Two things happen here that [search] does not do, and both exist because a driver said this out
   * loud rather than typing it:
   *
   *  * **`PlayFromSearch` decides, not the mirror.** A `LIKE` match is a set; a spoken request is
   *    one answer, and "exact title" has to beat "contains the words" or *"play Tail Book"* starts
   *    whichever book the shelf happens to list first.
   *  * **What the matched rows cannot answer, the whole shelf does.** `LIKE '%Tail, Book!%'`
   *    matches nothing, and that punctuation is exactly what a speech recogniser hands over -- so
   *    without the second pass, `PlayFromSearch.normalise` would be unreachable in production and a
   *    request that missed by a comma would answer with silence. The same pass covers the other way
   *    a first answer fails: a book row whose files a sync has not reached yet is playable as a row
   *    and expands to nothing.
   *
   * Books and music albums, not songs, in the fallback: it is the "what could I play" set, and a
   * library's whole song list is not one.
   *
   * `null` only when there is nothing expandable in the library at all.
   */
  suspend fun searchSelection(query: String): BrowseSelection? =
    firstThatExpands(PlayFromSearch.rank(query, search(query)))
      ?: firstThatExpands(PlayFromSearch.rank(query, everythingPlayable()))

  /** The best-ranked candidate that really becomes a queue, or `null` if none of them does. */
  private suspend fun firstThatExpands(candidates: List<BrowseNode>): BrowseSelection? =
    candidates.firstNotNullOfOrNull { expand(it.id) }

  /** Every book and every music album, in the order a search result list would put them. */
  private suspend fun everythingPlayable(): List<BrowseNode> = BrowseTree.searchNodes(
    books = bookshelf.books(),
    albums = musicAlbums(),
    artists = emptyList(),
    songs = emptyList(),
  )

  /**
   * A `coverArt` id turned into a URL, or `null` when nothing is configured.
   *
   * `runCatching`, because `SubsonicSourceProvider.current()` throws `NotConfiguredException` when
   * no server has been set up -- and a browse tree that threw there would answer a car with an
   * error instead of with the rows it already has.
   */
  suspend fun artworkUri(artworkId: String?): String? =
    artworkId?.let { runCatching { browseRepository.coverArtUrl(it, ARTWORK_SIZE_PX) }.getOrNull() }

  private suspend fun rootChildren(surface: BrowseSurface): List<BrowseNode> {
    val libraries = libraries()
    return BrowseTree.root(
      surface = surface,
      hasAudiobooks = libraries.any { it.role == LibraryRole.AUDIOBOOKS },
      hasMusic = libraries.any { it.role == LibraryRole.MUSIC },
    )
  }

  private suspend fun libraries(): List<MusicLibrary> = libraryRepository.libraries.first()

  private suspend fun librariesWithRole(role: LibraryRole): List<MusicLibrary> =
    libraries().filter { it.role == role }

  private suspend fun musicAlbums(): List<Album> =
    librariesWithRole(LibraryRole.MUSIC).flatMap { albumsIn(it.id) }

  private suspend fun albumsIn(libraryId: Int): List<Album> =
    browseRepository.albums(libraryId).first()

  private suspend fun musicArtists(): List<Artist> =
    librariesWithRole(LibraryRole.MUSIC).flatMap { browseRepository.artists(it.id).first() }

  companion object {
    /**
     * Where in [songs] the item with [mediaId] sits, or `0` if it is not there at all.
     *
     * Never `-1`: `indexOfFirst` returns that for a miss, and `PlaybackQueue.of(songs, -1)` throws
     * inside a `ListenableFuture`, where the exception reaches a car as unexplained silence. A
     * missing id means the mirror moved under a stale browse row, and starting at the beginning is
     * the right answer to that.
     */
    fun startIndexOf(songs: List<Song>, mediaId: String): Int =
      songs.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)

    /**
     * The cover-art edge length a browse row asks for.
     *
     * The same number `QueueRepository.ARTWORK_SIZE_PX` asks for, so the browse tree and the
     * playing queue share one cover-art cache entry per album instead of two. It is a **copy**,
     * because that constant lives in `:core:media`, which depends on this module and not the other
     * way round -- and a copy that drifts is a doubled cache, so
     * `BrowseTreeBrowserTest.theBrowseArtworkSizeIsTheQueuesOwn` holds the two equal from the one
     * source set that can see both.
     */
    const val ARTWORK_SIZE_PX: Int = 512

    /**
     * How many rows of each kind, per library, one search asks the mirror for.
     *
     * A cap and not a page: `BrowsePaging` does the paging, and a car host asks for whatever page
     * size it wants. What this bounds is the Room read and the list `PlayFromSearch` walks -- a
     * one-letter query against a real library would otherwise materialise every row in it on a
     * background thread a driver is waiting on. The same number `LibraryViewModel.SEARCH_LIMIT`
     * uses for the phone's own search box; they are deliberately independent constants, because
     * that one bounds a list a thumb scrolls and this one bounds a list a car renders four rows of.
     */
    const val SEARCH_LIMIT: Int = 50
  }
}
