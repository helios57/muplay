package app.muplay.database

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.browse.BrowseId
import app.muplay.model.browse.BrowseNode
import app.muplay.model.browse.BrowseSurface
import app.muplay.model.browse.BrowseTree
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
  }
}
