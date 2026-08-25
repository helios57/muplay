package app.muplay.model.browse

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.Song

/**
 * The browse tree, as pure functions from data to an **ordered** list of nodes.
 *
 * Nothing here touches Android, a repository, a coroutine or a clock. `BrowseTreeRepository`
 * (`:core:database`) decides *which* data a given [BrowseId] needs and fetches it;
 * `MuPlayLibraryCallback` (`:core:media`) turns the result into `MediaItem`s and answers Media3.
 * This object decides only what the tree *is*, which is the half a JVM test can hold to account.
 *
 * **Order is the property under test everywhere in this file.** A browse list in a car is read
 * top-down at speed; "the right items in some order" is not the same answer as "the right items".
 *
 * **No credential, token or stream URL is built here, and none may be.** Artwork travels as a
 * Navidrome `coverArt` id -- see [BrowseNode.artworkId] for the measured reason.
 */
object BrowseTree {

  const val CONTINUE_TITLE: String = "Continue"
  const val BOOKS_TITLE: String = "Books"
  const val ALBUMS_TITLE: String = "Albums"
  const val ARTISTS_TITLE: String = "Artists"
  const val LIBRARIES_TITLE: String = "Libraries"

  /**
   * The root's children.
   *
   * [hasAudiobooks] and [hasMusic] are what the user's library tagging produced, not a guess from a
   * library's name -- spec section 4 is explicit that a Navidrome server never reports that
   * something is an audiobook, and that inferring the role from the name ("Hörbücher" is not
   * "Audiobooks") silently poisons shuffle scope.
   *
   * An empty result is a legitimate answer, on every surface: it means nothing has been configured
   * yet. Task 4 returns it as an empty child list rather than as an error, because a car that says
   * "no media available" is telling the truth, whereas an error makes the app look broken.
   */
  fun root(surface: BrowseSurface, hasAudiobooks: Boolean, hasMusic: Boolean): List<BrowseNode> =
    buildList {
      if (hasAudiobooks) {
        add(folder(BrowseId.Continue, CONTINUE_TITLE, BrowseMediaType.FOLDER_MIXED, surface.browsableStyle))
        add(folder(BrowseId.Books, BOOKS_TITLE, BrowseMediaType.FOLDER_MIXED, surface.browsableStyle))
      }
      if (hasMusic) {
        add(folder(BrowseId.Albums, ALBUMS_TITLE, BrowseMediaType.FOLDER_ALBUMS, surface.browsableStyle))
        // A watch skips Artists: it is a level of indirection that costs two more crown scrolls to
        // reach exactly the album the Albums tab already lists.
        if (surface != BrowseSurface.WATCH) {
          add(folder(BrowseId.Artists, ARTISTS_TITLE, BrowseMediaType.FOLDER_ARTISTS, BrowseStyle.LIST))
        }
        // A library picker is unbounded and is one more level of depth, which is exactly what a
        // driver must not be handed -- and it would push the car root past its four tabs.
        if (surface == BrowseSurface.PHONE) {
          add(folder(BrowseId.Libraries, LIBRARIES_TITLE, BrowseMediaType.FOLDER_MIXED, BrowseStyle.LIST))
        }
      }
    }

  /**
   * Books with somewhere to carry on from, most recently heard first, capped at [limit].
   *
   * Finished books are excluded rather than sorted to the bottom: "Continue" is a promise that
   * every row has more to play.
   */
  fun continueNodes(books: List<BookSummary>, limit: Int): List<BrowseNode> =
    books.asSequence()
      .filter { it.hasStarted && !it.isFinished }
      // `thenBy { bookId }` is not decoration: two books last played in the same millisecond is
      // reachable (a device-to-device merge writes a batch), and an unstable sort would reorder a
      // car list between two identical requests.
      .sortedWith(compareByDescending<BookSummary> { it.lastPlayedAtEpochMs }.thenBy { it.bookId })
      .take(limit)
      .map(::bookNode)
      .toList()

  /** Every book, alphabetically -- a **different** order from [continueNodes], on purpose. */
  fun bookNodes(books: List<BookSummary>): List<BrowseNode> =
    books
      .sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, BookSummary::title).thenBy(BookSummary::bookId),
      )
      .map(::bookNode)

  /** One book's files, in the order given, numbered for a listener who cannot see a file name. */
  fun bookChildren(files: List<Song>): List<BrowseNode> =
    files.mapIndexed { index, song ->
      BrowseNode(
        id = BrowseId.Track(song.id),
        title = song.title,
        subtitle = BrowseText.partLabel(index, files.size),
        isBrowsable = false,
        isPlayable = true,
        mediaType = BrowseMediaType.AUDIO_BOOK_CHAPTER,
        artworkId = song.coverArtId,
        durationMs = song.durationSeconds * 1_000L,
      )
    }

  /**
   * The Albums tab: one shuffle per Music library first, in library id order, then the albums.
   *
   * Shuffle is first because it is the one thing a driver should be able to reach without reading,
   * and library id order because a driver who learns "the first row is Shuffle Music" must be right
   * every time rather than most of the time.
   */
  fun albumsNodes(musicLibraries: List<MusicLibrary>, albums: List<Album>): List<BrowseNode> =
    musicLibraries.sortedBy(MusicLibrary::id).map(::shuffleNode) + albums.map(::albumNode)

  /** Artists, in the order given (the mirror's, which Plan 2 defines as alphabetical). */
  fun artistNodes(artists: List<Artist>): List<BrowseNode> =
    artists.map { artist ->
      BrowseNode(
        id = BrowseId.Artist(artist.id),
        title = artist.name,
        subtitle = BrowseText.albumCountLabel(artist.albumCount),
        isBrowsable = true,
        isPlayable = false,
        mediaType = BrowseMediaType.ARTIST,
        artworkId = artist.coverArtId,
        // An artist's children are albums, so they get the cover-art grid regardless of surface --
        // there is no Artists tab on a watch, which is the only surface that would want a list.
        childStyle = BrowseStyle.GRID,
      )
    }

  /** One artist's albums. Same shape as [albumsNodes] without the shuffle rows. */
  fun artistChildren(albums: List<Album>): List<BrowseNode> = albums.map(::albumNode)

  /** Alias of [artistChildren], named for the call site that reads better: an album's siblings. */
  fun albumChildrenOfArtist(albums: List<Album>): List<BrowseNode> = artistChildren(albums)

  /** One album's tracks, in the order given (Plan 2's mirror orders by disc then track). */
  fun albumChildren(songs: List<Song>): List<BrowseNode> = songNodes(songs)

  /** Tracks, in the order given. Playable leaves; see [BrowseId.Track] for why the id is bare. */
  fun songNodes(songs: List<Song>): List<BrowseNode> =
    songs.map { song ->
      BrowseNode(
        id = BrowseId.Track(song.id),
        title = song.title,
        subtitle = song.artistName?.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST,
        isBrowsable = false,
        isPlayable = true,
        mediaType = BrowseMediaType.TRACK,
        artworkId = song.coverArtId,
        durationMs = song.durationSeconds * 1_000L,
      )
    }

  /** Every library, by id, with its role spelled out -- the phone-only Libraries tab. */
  fun libraryNodes(libraries: List<MusicLibrary>): List<BrowseNode> =
    libraries.sortedBy(MusicLibrary::id).map { library ->
      BrowseNode(
        id = BrowseId.Library(library.id),
        title = library.name,
        subtitle = when (library.role) {
          LibraryRole.MUSIC -> "Music"
          LibraryRole.AUDIOBOOKS -> "Audiobooks"
          LibraryRole.UNASSIGNED -> "Not assigned"
        },
        isBrowsable = true,
        isPlayable = false,
        mediaType = BrowseMediaType.FOLDER_MIXED,
        childStyle = BrowseStyle.GRID,
      )
    }

  /**
   * One library's contents: its albums, preceded by a shuffle row **only if it is a Music library**.
   *
   * Spec section 1: shuffle must not pull chapter 14 of a novel into a music session. On a surface
   * with no UI of its own, that rule is expressed as the absence of a row.
   */
  fun libraryChildren(library: MusicLibrary, albums: List<Album>): List<BrowseNode> =
    if (library.role == LibraryRole.MUSIC) {
      listOf(shuffleNode(library)) + albums.map(::albumNode)
    } else {
      albums.map(::albumNode)
    }

  private fun folder(
    id: BrowseId,
    title: String,
    mediaType: BrowseMediaType,
    childStyle: BrowseStyle,
  ) = BrowseNode(
    id = id,
    title = title,
    subtitle = null,
    isBrowsable = true,
    isPlayable = false,
    mediaType = mediaType,
    childStyle = childStyle,
  )

  private fun shuffleNode(library: MusicLibrary) = BrowseNode(
    id = BrowseId.Shuffle(library.id),
    title = "Shuffle ${library.name}",
    subtitle = null,
    isBrowsable = false,
    isPlayable = true,
    mediaType = BrowseMediaType.MIXED,
  )

  private fun albumNode(album: Album) = BrowseNode(
    id = BrowseId.Album(album.id),
    title = album.name,
    subtitle = album.artistName?.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST,
    isBrowsable = true,
    isPlayable = true,
    mediaType = BrowseMediaType.ALBUM,
    artworkId = album.coverArtId,
    childStyle = BrowseStyle.LIST,
    durationMs = album.durationSeconds * 1_000L,
  )

  private fun bookNode(book: BookSummary) = BrowseNode(
    id = BrowseId.Book(book.bookId),
    title = book.title,
    subtitle = bookSubtitle(book),
    // A one-file book has nothing worth a second screen: opening it would show one row repeating
    // the row above it. It stays playable, so tapping it still resumes.
    isBrowsable = book.fileCount > 1,
    isPlayable = true,
    mediaType = BrowseMediaType.AUDIO_BOOK,
    artworkId = book.coverArtId,
    childStyle = BrowseStyle.LIST,
    completion = completionOf(book),
    durationMs = book.durationMs,
  )

  private fun bookSubtitle(book: BookSummary): String {
    val author = book.author.takeIf(String::isNotBlank) ?: BrowseText.UNKNOWN_ARTIST
    return if (book.hasStarted && !book.isFinished) {
      "$author · ${BrowseText.remainingLabel(book.remainingMs)}"
    } else {
      author
    }
  }

  private fun completionOf(book: BookSummary): BrowseCompletion = when {
    book.isFinished -> BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0)
    book.hasStarted -> BrowseCompletion(
      BrowseCompletionStatus.PARTIALLY_PLAYED,
      book.progressFraction,
    )
    else -> BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0)
  }
}
