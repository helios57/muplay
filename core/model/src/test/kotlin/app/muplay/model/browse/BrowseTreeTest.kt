package app.muplay.model.browse

import app.muplay.model.Album
import app.muplay.model.Artist
import app.muplay.model.BookSummary
import app.muplay.model.LibraryRole
import app.muplay.model.MusicLibrary
import app.muplay.model.Song
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The shape of the browse tree, one pure function at a time.
 *
 * Every assertion in this file maps a **field** and compares an **exact ordered list**. Two habits
 * this project has been bitten by six times are banned here outright: `isNotEmpty`, which is
 * satisfied by a tree that returns everything under everything, and `containsExactlyInAnyOrder`,
 * which asserts nothing about the one property a car list actually has -- the order a driver reads
 * it in.
 */
class BrowseTreeTest {

  // --- roots -----------------------------------------------------------------------------------

  @Test
  fun `the three surfaces produce three different roots`() {
    // The single most important assertion in this plan. If these three lists were equal, the
    // surface branch would be dead code carrying 100% branch coverage.
    val car = BrowseTree.root(BrowseSurface.CAR, hasAudiobooks = true, hasMusic = true)
    val watch = BrowseTree.root(BrowseSurface.WATCH, hasAudiobooks = true, hasMusic = true)
    val phone = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = true)

    assertThat(car.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists",
    )
    assertThat(watch.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums",
    )
    assertThat(phone.map { it.id.encode() }).containsExactly(
      "muplay/continue", "muplay/books", "muplay/albums", "muplay/artists", "muplay/libraries",
    )

    // Stated as its own assertion so a future edit that made two of them equal fails on a line
    // that says why, rather than on three separate list comparisons.
    assertThat(listOf(car.size, watch.size, phone.size)).containsExactly(4, 3, 5)
  }

  @Test
  fun `the car root never exceeds the four tabs Android Auto renders`() {
    // All four configurations, because the limit has to hold for the *largest* one and a single
    // sample cannot show that.
    val sizes = listOf(true to true, true to false, false to true, false to false)
      .map { (books, music) ->
        BrowseTree.root(BrowseSurface.CAR, hasAudiobooks = books, hasMusic = music).size
      }

    assertThat(sizes).containsExactly(4, 2, 2, 0)
    assertThat(sizes.max()).isLessThanOrEqualTo(BrowseSurface.MAX_CAR_ROOT_TABS)
    assertThat(BrowseSurface.MAX_CAR_ROOT_TABS).isEqualTo(4)
  }

  @Test
  fun `each configuration flag removes exactly the tabs it owns`() {
    // Rule 2, applied to a boolean: hold the surface constant, vary one flag, assert both
    // observations. A root that ignored `hasAudiobooks` would pass the first test above.
    val noBooks = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = false, hasMusic = true)
    val noMusic = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = false)
    val nothing = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = false, hasMusic = false)

    assertThat(noBooks.map { it.id.encode() })
      .containsExactly("muplay/albums", "muplay/artists", "muplay/libraries")
    assertThat(noMusic.map { it.id.encode() })
      .containsExactly("muplay/continue", "muplay/books")
    assertThat(nothing).isEmpty()
  }

  @Test
  fun `the browsable style follows the surface`() {
    val albumsTab = { surface: BrowseSurface ->
      BrowseTree.root(surface, hasAudiobooks = true, hasMusic = true)
        .single { it.id == BrowseId.Albums }
        .childStyle
    }

    assertThat(listOf(BrowseSurface.CAR, BrowseSurface.WATCH, BrowseSurface.PHONE).map(albumsTab))
      .containsExactly(BrowseStyle.GRID, BrowseStyle.LIST, BrowseStyle.GRID)

    // And the enum property the tab reads it from, at all three values, so that a `childStyle`
    // hardcoded to GRID and a `browsableStyle` hardcoded to GRID cannot cover for each other.
    assertThat(BrowseSurface.entries.map { it.browsableStyle })
      .containsExactly(BrowseStyle.GRID, BrowseStyle.LIST, BrowseStyle.GRID)
  }

  @Test
  fun `the artists and libraries tabs stay a list on every surface, including the grid ones`() {
    // These two are deliberately *not* `surface.browsableStyle`: an artist row and a library row
    // are a name and a count, with cover art that is at best incidental. Asserted on CAR and
    // PHONE, both of which are GRID surfaces, so a tab that had picked up `browsableStyle` by
    // copy-paste fails here rather than looking right on the one surface that agrees with it.
    val artistsTab = { surface: BrowseSurface ->
      BrowseTree.root(surface, hasAudiobooks = true, hasMusic = true)
        .single { it.id == BrowseId.Artists }
        .childStyle
    }

    assertThat(listOf(BrowseSurface.CAR, BrowseSurface.PHONE).map(artistsTab))
      .containsExactly(BrowseStyle.LIST, BrowseStyle.LIST)
    assertThat(
      BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = true)
        .single { it.id == BrowseId.Libraries }
        .childStyle,
    ).isEqualTo(BrowseStyle.LIST)
  }

  @Test
  fun `every root tab is browsable and none is playable`() {
    // Mapped and compared as exact lists, not `allMatch`: `allMatch` over an empty list is
    // vacuously true, and an empty root is a reachable state (see the test above).
    val root = BrowseTree.root(BrowseSurface.PHONE, hasAudiobooks = true, hasMusic = true)

    assertThat(root.map { it.isBrowsable }).containsExactly(true, true, true, true, true)
    assertThat(root.map { it.isPlayable }).containsExactly(false, false, false, false, false)
    assertThat(root.map { it.title })
      .containsExactly("Continue", "Books", "Albums", "Artists", "Libraries")
    // Three distinct media types across five tabs, in position: a root whose every tab was
    // FOLDER_MIXED would satisfy any per-tab spot check of the two that really are mixed.
    assertThat(root.map { it.mediaType }).containsExactly(
      BrowseMediaType.FOLDER_MIXED,
      BrowseMediaType.FOLDER_MIXED,
      BrowseMediaType.FOLDER_ALBUMS,
      BrowseMediaType.FOLDER_ARTISTS,
      BrowseMediaType.FOLDER_MIXED,
    )
    // A tab is a heading, not a thing: nothing on the root has art, a subtitle or a duration.
    assertThat(root.map { it.subtitle }).containsExactly(null, null, null, null, null)
    assertThat(root.map { it.artworkId }).containsExactly(null, null, null, null, null)
    assertThat(root.map { it.durationMs }).containsExactly(null, null, null, null, null)
    assertThat(root.map { it.title }).containsExactly(
      BrowseTree.CONTINUE_TITLE,
      BrowseTree.BOOKS_TITLE,
      BrowseTree.ALBUMS_TITLE,
      BrowseTree.ARTISTS_TITLE,
      BrowseTree.LIBRARIES_TITLE,
    )
  }

  // --- books -----------------------------------------------------------------------------------

  @Test
  fun `continue lists only started unfinished books, most recently heard first`() {
    val nodes = BrowseTree.continueNodes(SHELF, limit = 10)

    assertThat(nodes.map { it.id.encode() }).containsExactly(
      "muplay/book/b-tail",   // lastPlayedAt 900
      "muplay/book/b-multi",  // lastPlayedAt 500
      "muplay/book/b-second", // lastPlayedAt 100
      // b-test is unstarted and b-done is finished; neither belongs on a Continue shelf.
    )
  }

  @Test
  fun `continue is capped by the surface's own limit`() {
    assertThat(BrowseTree.continueNodes(SHELF, limit = BrowseSurface.WATCH.continueLimit).size)
      .isEqualTo(3)
    assertThat(BrowseTree.continueNodes(SHELF, limit = 2).map { it.id.encode() })
      .containsExactly("muplay/book/b-tail", "muplay/book/b-multi")
    assertThat(BrowseTree.continueNodes(SHELF, limit = 0)).isEmpty()

    // The three surfaces' limits, asserted as values rather than trusted: a limit that was the
    // same everywhere would make the two assertions above indistinguishable.
    assertThat(BrowseSurface.entries.map { it.continueLimit }).containsExactly(8, 5, 25)
  }

  @Test
  fun `two books last heard in the same millisecond come out in the same order either way round`() {
    // `thenBy { bookId }` is not decoration. A batch write during a device-to-device merge stamps
    // one millisecond onto several books, and an unstable sort would reorder a car list between
    // two identical requests -- a row moving under a driver's finger. Fed both permutations,
    // because a comparator with no tie-break returns its input order for ties and would pass a
    // single-permutation test half the time.
    val early = MULTI.copy(bookId = "b-aaa", title = "Aaa", lastPlayedAtEpochMs = 500)
    val late = MULTI.copy(bookId = "b-zzz", title = "Zzz", lastPlayedAtEpochMs = 500)

    assertThat(BrowseTree.continueNodes(listOf(late, early), limit = 10).map { it.id.encode() })
      .containsExactly("muplay/book/b-aaa", "muplay/book/b-zzz")
    assertThat(BrowseTree.continueNodes(listOf(early, late), limit = 10).map { it.id.encode() })
      .containsExactly("muplay/book/b-aaa", "muplay/book/b-zzz")
  }

  @Test
  fun `the book shelf is alphabetical, which is a different order from continue`() {
    // Same input, two functions, two different exact lists. This is what proves `bookNodes` sorts
    // at all: with a single order, "sorted" and "as supplied" are the same list.
    assertThat(BrowseTree.bookNodes(SHELF).map { it.title }).containsExactly(
      "A Wizard of Earthsea", // b-second
      "Multi Part Book",      // b-multi
      "Tail Book",            // b-tail
      "Test Book",            // b-test
      "Zero Hour",            // b-done
    )
    assertThat(BrowseTree.bookNodes(SHELF).map { it.title })
      .isNotEqualTo(BrowseTree.continueNodes(SHELF, limit = 25).map { it.title })
  }

  @Test
  fun `the book shelf sorts case-insensitively and breaks its own ties by id`() {
    // A natural `sortedBy(title)` puts every capital letter before every lower-case one, so
    // "Balloon" would precede "aardvark" and a shelf of mixed-case titles would read as two
    // alphabets stacked. Ties are broken by id for the same reason `continueNodes` breaks its own.
    val lower = TEST_BOOK.copy(bookId = "b-lower", title = "aardvark")
    val upper = TEST_BOOK.copy(bookId = "b-upper", title = "Balloon")

    assertThat(BrowseTree.bookNodes(listOf(upper, lower)).map { it.title })
      .containsExactly("aardvark", "Balloon")

    val second = TEST_BOOK.copy(bookId = "b-dup-2", title = "Same Title")
    val first = TEST_BOOK.copy(bookId = "b-dup-1", title = "same title")

    assertThat(BrowseTree.bookNodes(listOf(second, first)).map { it.id.encode() })
      .containsExactly("muplay/book/b-dup-1", "muplay/book/b-dup-2")
  }

  @Test
  fun `a book's completion is one of three distinct values`() {
    val byId = BrowseTree.bookNodes(SHELF).associateBy { it.id.encode() }

    assertThat(byId.getValue("muplay/book/b-test").completion)
      .isEqualTo(BrowseCompletion(BrowseCompletionStatus.NOT_PLAYED, 0.0))
    assertThat(byId.getValue("muplay/book/b-done").completion)
      .isEqualTo(BrowseCompletion(BrowseCompletionStatus.FULLY_PLAYED, 1.0))

    val partial = byId.getValue("muplay/book/b-multi").completion
    assertThat(partial?.status).isEqualTo(BrowseCompletionStatus.PARTIALLY_PLAYED)
    // 60_000 of 300_000 ms. Asserted as a number, not as "greater than zero": a fraction hardcoded
    // to 0.5 would pass every "partially played" assertion there is.
    assertThat(partial?.fraction).isEqualTo(0.2, within(1e-9))
  }

  @Test
  fun `a single-file book is playable but not browsable, and a multi-file one is both`() {
    val byId = BrowseTree.bookNodes(SHELF).associateBy { it.id.encode() }

    // Opening a one-file M4B would show a screen with one row that says what the row above it
    // already said. Both flags asserted for both books, so neither can be a constant.
    assertThat(byId.getValue("muplay/book/b-test").let { it.isPlayable to it.isBrowsable })
      .isEqualTo(true to false)
    assertThat(byId.getValue("muplay/book/b-multi").let { it.isPlayable to it.isBrowsable })
      .isEqualTo(true to true)
  }

  @Test
  fun `a book node carries every field of its summary`() {
    // The field-level rule. Two books that differ in every field, asserted field by field, so that
    // replacing any one assignment with a constant fails here.
    val nodes = BrowseTree.bookNodes(listOf(SECOND, MULTI))

    assertThat(nodes.map { it.title }).containsExactly("A Wizard of Earthsea", "Multi Part Book")
    assertThat(nodes.map { it.subtitle }).containsExactly(
      "Ursula K. Le Guin · 1 min left",
      "Terry Pratchett · 4 min left",
    )
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-second", "cov-multi")
    assertThat(nodes.map { it.durationMs }).containsExactly(80_000L, 300_000L)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.AUDIO_BOOK, BrowseMediaType.AUDIO_BOOK)
  }

  @Test
  fun `an unstarted book's subtitle is its author alone`() {
    assertThat(BrowseTree.bookNodes(listOf(TEST_BOOK)).single().subtitle)
      .isEqualTo("Anonymous")
  }

  @Test
  fun `a finished book's subtitle is its author alone, with no time left to offer`() {
    // The third arm of the same `when`: started *and* finished. Without it, `hasStarted` alone
    // would satisfy every subtitle assertion in this file and "0 min left" would ship on a book
    // that has nothing left to play.
    assertThat(BrowseTree.bookNodes(listOf(DONE)).single().subtitle).isEqualTo("Anonymous")
  }

  @Test
  fun `a book with no author reads as unknown rather than as an empty line`() {
    assertThat(BrowseTree.bookNodes(listOf(TEST_BOOK.copy(author = "  "))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
  }

  @Test
  fun `a book's children are its files, numbered and in the order supplied`() {
    val files = listOf(song("f-1", "Chapter One"), song("f-2", "Chapter Two"), song("f-3", "Chapter Three"))

    val children = BrowseTree.bookChildren(files)

    assertThat(children.map { it.id.encode() }).containsExactly("f-1", "f-2", "f-3")
    assertThat(children.map { it.title }).containsExactly("Chapter One", "Chapter Two", "Chapter Three")
    assertThat(children.map { it.subtitle })
      .containsExactly("Part 1 of 3", "Part 2 of 3", "Part 3 of 3")
    assertThat(children.map { it.isPlayable }).containsExactly(true, true, true)
    assertThat(children.map { it.isBrowsable }).containsExactly(false, false, false)
    assertThat(children.map { it.mediaType }).containsExactly(
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
      BrowseMediaType.AUDIO_BOOK_CHAPTER,
    )
  }

  @Test
  fun `a book child's part number counts the files it was given, not a fixed total`() {
    // `partLabel(index, files.size)`: with a two-file book the total has to read 2. A `total`
    // taken from anywhere but this list -- or an index that ignored its position -- survives the
    // three-file test above, where index and total happen to line up with the fixture everywhere
    // else in this file.
    val children = BrowseTree.bookChildren(listOf(song("f-9", "Only"), song("f-8", "Other")))

    assertThat(children.map { it.subtitle }).containsExactly("Part 1 of 2", "Part 2 of 2")
    assertThat(children.map { it.id.encode() }).containsExactly("f-9", "f-8")
    assertThat(children.map { it.durationMs }).containsExactly(300_000L, 300_000L)
    assertThat(BrowseTree.bookChildren(emptyList())).isEmpty()
  }

  // --- music -----------------------------------------------------------------------------------

  @Test
  fun `the albums node puts one shuffle per music library first, in library id order`() {
    // Supplied highest-id-first, and named so that **name order and id order disagree**: by name
    // this is Music(3) then Vinyl rips(1), by id it is Vinyl rips(1) then Music(3). An earlier
    // draft of this fixture had the names the other way round, where the two orders happened to
    // coincide -- and a `sortedBy(MusicLibrary::name)` mutation survived the whole suite.
    val libraries = listOf(
      MusicLibrary(id = 3, name = "Music", role = LibraryRole.MUSIC),
      MusicLibrary(id = 1, name = "Vinyl rips", role = LibraryRole.MUSIC),
    )

    val nodes = BrowseTree.albumsNodes(libraries, listOf(ALBUM_A, ALBUM_B))

    assertThat(nodes.map { it.id.encode() }).containsExactly(
      "muplay/shuffle/1", "muplay/shuffle/3", "muplay/album/al-a", "muplay/album/al-b",
    )
    assertThat(nodes.map { it.title })
      .containsExactly("Shuffle Vinyl rips", "Shuffle Music", "Abbey Road", "Blue Train")
    assertThat(nodes.map { it.isPlayable }).containsExactly(true, true, true, true)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(false, false, true, true)
    // A shuffle row is a verb, not an album: no art, no subtitle, no duration to render.
    assertThat(nodes.map { it.artworkId }).containsExactly(null, null, "cov-a", "cov-b")
    assertThat(nodes.map { it.mediaType }).containsExactly(
      BrowseMediaType.MIXED, BrowseMediaType.MIXED, BrowseMediaType.ALBUM, BrowseMediaType.ALBUM,
    )
  }

  @Test
  fun `an audiobook library gets no shuffle node and a music library does`() {
    // Spec section 1: shuffle must never pull chapter 14 of a novel into a music session. In a car
    // that is expressed as the absence of a row, because there is no UI to disable.
    val music = MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC)
    val books = MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.AUDIOBOOKS)
    val unassigned = MusicLibrary(id = 3, name = "New disk", role = LibraryRole.UNASSIGNED)

    assertThat(BrowseTree.libraryChildren(music, listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/shuffle/1", "muplay/album/al-a")
    assertThat(BrowseTree.libraryChildren(books, listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/album/al-a")
    // An untagged library is not "probably music": until the user says which it is, shuffling it
    // could pull a novel into a music session exactly as an audiobook library would.
    assertThat(BrowseTree.libraryChildren(unassigned, listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/album/al-a")
  }

  @Test
  fun `the albums tab offers a shuffle only for the libraries it was handed`() {
    // Rule 2 on the list argument: hold the albums constant and vary the libraries. A tab that
    // shuffled every library it could reach, or none, passes the id-order test above only because
    // that test happens to supply two music libraries.
    assertThat(BrowseTree.albumsNodes(emptyList(), listOf(ALBUM_A)).map { it.id.encode() })
      .containsExactly("muplay/album/al-a")
    assertThat(
      BrowseTree.albumsNodes(
        listOf(MusicLibrary(id = 7, name = "Only", role = LibraryRole.MUSIC)),
        emptyList(),
      ).map { it.id.encode() },
    ).containsExactly("muplay/shuffle/7")
  }

  @Test
  fun `an album node carries every field of its album`() {
    val nodes = BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A, ALBUM_B))

    assertThat(nodes.map { it.id.encode() }).containsExactly("muplay/album/al-a", "muplay/album/al-b")
    assertThat(nodes.map { it.title }).containsExactly("Abbey Road", "Blue Train")
    assertThat(nodes.map { it.subtitle }).containsExactly("The Beatles", "John Coltrane")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-a", "cov-b")
    assertThat(nodes.map { it.durationMs }).containsExactly(2_832_000L, 2_142_000L)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.ALBUM, BrowseMediaType.ALBUM)
  }

  @Test
  fun `an album with no artist reads as unknown`() {
    assertThat(BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A.copy(artistName = null))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
    // A name Navidrome sent as whitespace is the same absence with a different spelling, and it is
    // the arm a plain `?: UNKNOWN_ARTIST` -- with no `takeIf` -- gets wrong.
    assertThat(BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A.copy(artistName = "   "))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
  }

  @Test
  fun `an artist node carries its own fields and counts its albums`() {
    val artists = listOf(
      Artist(id = "ar-1", libraryId = 1, name = "The Beatles", coverArtId = "cov-ar1", albumCount = 13),
      Artist(id = "ar-2", libraryId = 1, name = "Nobody", coverArtId = null, albumCount = 1),
    )

    val nodes = BrowseTree.artistNodes(artists)

    assertThat(nodes.map { it.id.encode() }).containsExactly("muplay/artist/ar-1", "muplay/artist/ar-2")
    assertThat(nodes.map { it.title }).containsExactly("The Beatles", "Nobody")
    assertThat(nodes.map { it.subtitle }).containsExactly("13 albums", "1 album")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-ar1", null)
    assertThat(nodes.map { it.isPlayable }).containsExactly(false, false)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(true, true)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.ARTIST, BrowseMediaType.ARTIST)
  }

  @Test
  fun `each browsable node names the layout its own children want, and they are not all one value`() {
    // An artist opens onto covers; an album opens onto a numbered track list; a book opens onto
    // its chapters. Asserted together so that a `childStyle` collapsed to a single constant fails
    // here rather than passing every individual spot check that agrees with that constant.
    val artist = BrowseTree.artistNodes(
      listOf(Artist(id = "ar-1", libraryId = 1, name = "The Beatles", coverArtId = null, albumCount = 2)),
    ).single()
    val library = BrowseTree.libraryNodes(
      listOf(MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC)),
    ).single()

    assertThat(
      listOf(
        artist.childStyle,
        library.childStyle,
        BrowseTree.albumChildrenOfArtist(listOf(ALBUM_A)).single().childStyle,
        BrowseTree.bookNodes(listOf(MULTI)).single().childStyle,
      ),
    ).containsExactly(BrowseStyle.GRID, BrowseStyle.GRID, BrowseStyle.LIST, BrowseStyle.LIST)
  }

  @Test
  fun `a track node carries every field of its song`() {
    val songs = listOf(
      song("tr-1", "Come Together").copy(artistName = "The Beatles", coverArtId = "cov-a", durationSeconds = 259),
      song("tr-2", "Something").copy(artistName = "George", coverArtId = "cov-b", durationSeconds = 182),
    )

    val nodes = BrowseTree.songNodes(songs)

    assertThat(nodes.map { it.id.encode() }).containsExactly("tr-1", "tr-2")
    assertThat(nodes.map { it.title }).containsExactly("Come Together", "Something")
    assertThat(nodes.map { it.subtitle }).containsExactly("The Beatles", "George")
    assertThat(nodes.map { it.artworkId }).containsExactly("cov-a", "cov-b")
    assertThat(nodes.map { it.durationMs }).containsExactly(259_000L, 182_000L)
    assertThat(nodes.map { it.isPlayable }).containsExactly(true, true)
    assertThat(nodes.map { it.isBrowsable }).containsExactly(false, false)
    assertThat(nodes.map { it.mediaType })
      .containsExactly(BrowseMediaType.TRACK, BrowseMediaType.TRACK)
  }

  @Test
  fun `a track with no artist reads as unknown, absent or blank`() {
    assertThat(BrowseTree.songNodes(listOf(song("tr-1", "Come Together").copy(artistName = null))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
    assertThat(BrowseTree.songNodes(listOf(song("tr-1", "Come Together").copy(artistName = " "))).single().subtitle)
      .isEqualTo(BrowseText.UNKNOWN_ARTIST)
  }

  @Test
  fun `song nodes keep the order they were given`() {
    // The mirror's order is disc-then-track (Plan 2). A tree that sorted by title here would put
    // an album's tracks in the wrong order and nothing else in this file would notice.
    val songs = listOf(song("tr-3", "Zulu"), song("tr-1", "Alpha"), song("tr-2", "Mike"))

    assertThat(BrowseTree.songNodes(songs).map { it.id.encode() })
      .containsExactly("tr-3", "tr-1", "tr-2")
  }

  @Test
  fun `an album's children are its tracks, forwarded in the order they arrived`() {
    // `albumChildren` delegates to `songNodes`, and a delegating method is one of this project's
    // recorded defect classes: it is asserted here against an input whose order is *not* the
    // sorted one, so a delegate that dropped, reordered or ignored its argument fails.
    val songs = listOf(song("tr-3", "Zulu"), song("tr-1", "Alpha"))

    assertThat(BrowseTree.albumChildren(songs).map { it.id.encode() })
      .containsExactly("tr-3", "tr-1")
    assertThat(BrowseTree.albumChildren(songs)).isEqualTo(BrowseTree.songNodes(songs))
    assertThat(BrowseTree.albumChildren(emptyList())).isEmpty()
  }

  @Test
  fun `an artist's albums are the same nodes under both of its spellings, argument forwarded`() {
    // Same delegation rule for the pair `artistChildren`/`albumChildrenOfArtist`. The input is
    // deliberately in reverse-alphabetical order so a delegate that sorted, or that returned a
    // constant list, cannot pass.
    val albums = listOf(ALBUM_B, ALBUM_A)

    assertThat(BrowseTree.artistChildren(albums).map { it.id.encode() })
      .containsExactly("muplay/album/al-b", "muplay/album/al-a")
    assertThat(BrowseTree.albumChildrenOfArtist(albums)).isEqualTo(BrowseTree.artistChildren(albums))
    assertThat(BrowseTree.artistChildren(emptyList())).isEmpty()
  }

  @Test
  fun `a library node names its role in its subtitle`() {
    val libraries = listOf(
      MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC),
      MusicLibrary(id = 2, name = "Audiobooks", role = LibraryRole.AUDIOBOOKS),
      MusicLibrary(id = 9, name = "New disk", role = LibraryRole.UNASSIGNED),
    )

    val nodes = BrowseTree.libraryNodes(libraries)

    assertThat(nodes.map { it.id.encode() })
      .containsExactly("muplay/library/1", "muplay/library/2", "muplay/library/9")
    assertThat(nodes.map { it.title }).containsExactly("Music", "Audiobooks", "New disk")
    assertThat(nodes.map { it.subtitle }).containsExactly("Music", "Audiobooks", "Not assigned")
    assertThat(nodes.map { it.isBrowsable }).containsExactly(true, true, true)
    assertThat(nodes.map { it.isPlayable }).containsExactly(false, false, false)
  }

  @Test
  fun `library nodes are ordered by id, not by name and not by the order they arrived in`() {
    // Three orders, all different, so exactly one of them can pass: supplied is 9 then 1, by name
    // is "Alpha disk"(9) then "Zulu music"(1), by id is 1 then 9. Same lesson as the shuffle rows
    // above -- a fixture whose name order and id order agree cannot tell `sortedBy(id)` from
    // `sortedBy(name)`, and a `sortedBy(name)` mutation survived until this fixture was rewritten.
    val libraries = listOf(
      MusicLibrary(id = 9, name = "Alpha disk", role = LibraryRole.UNASSIGNED),
      MusicLibrary(id = 1, name = "Zulu music", role = LibraryRole.MUSIC),
    )

    assertThat(BrowseTree.libraryNodes(libraries).map { it.id.encode() })
      .containsExactly("muplay/library/1", "muplay/library/9")
    assertThat(BrowseTree.libraryNodes(libraries).map { it.title })
      .containsExactly("Zulu music", "Alpha disk")
  }

  // --- the credential rule ---------------------------------------------------------------------

  @Test
  fun `no node anywhere in the tree carries a url, and every artwork id is the id it was given`() {
    // A review of the playback queue measured `MediaMetadata.toBundle()` serialising
    // FIELD_ARTWORK_URI across the session IPC boundary, and MuPlay's artwork URLs carry `u`,
    // `s=<salt>` and `t=md5(password+salt)` -- a replayable, non-expiring credential. `artworkId`
    // is a Navidrome coverArt id for exactly that reason, and the resolution to a URL happens on
    // the device side, in the bitmap loader. This asserts that at the layer where it is decided.
    val libraries = listOf(MusicLibrary(id = 1, name = "Music", role = LibraryRole.MUSIC))
    val everyNode: List<BrowseNode> =
      BrowseSurface.entries.flatMap { BrowseTree.root(it, hasAudiobooks = true, hasMusic = true) } +
        BrowseTree.continueNodes(SHELF, limit = 25) +
        BrowseTree.bookNodes(SHELF) +
        BrowseTree.bookChildren(listOf(song("f-1", "Chapter One"))) +
        BrowseTree.albumsNodes(libraries, listOf(ALBUM_A, ALBUM_B)) +
        BrowseTree.artistNodes(
          listOf(Artist(id = "ar-1", libraryId = 1, name = "The Beatles", coverArtId = "cov-ar1", albumCount = 13)),
        ) +
        BrowseTree.songNodes(listOf(song("tr-1", "Come Together"))) +
        BrowseTree.libraryNodes(libraries) +
        BrowseTree.libraryChildren(libraries.single(), listOf(ALBUM_A))

    // Sized first: `allSatisfy` over an empty list is vacuously true, and this list is assembled
    // from nine builders that could each have returned nothing.
    assertThat(everyNode).hasSize(29)

    val rendered = everyNode.flatMap { listOfNotNull(it.title, it.subtitle, it.artworkId, it.id.encode()) }
    assertThat(rendered).allSatisfy { text ->
      assertThat(text).doesNotContain("://", "http", "?u=", "&s=", "&t=", "getCoverArt", "token")
    }

    // And the positive half: every art id that reached a node is verbatim one of the coverArt ids
    // the fixtures supplied. A node that resolved an id into anything at all fails here even if
    // whatever it built contained none of the substrings above.
    assertThat(everyNode.mapNotNull { it.artworkId }.toSet())
      .isSubsetOf(setOf("cov-a", "cov-b", "cov-ar1", "cov-test", "cov-second", "cov-multi", "cov-tail"))
  }

  // --- search ----------------------------------------------------------------------------------

  @Test
  fun `search results are books, then albums, then artists, then tracks`() {
    // Two entries per group, so a rule that interleaved them -- or that dropped one group's second
    // element -- fails here rather than looking like the right order with a shorter list.
    //
    // The fixture is deliberately **not** in alphabetical order end to end: sorted by title it
    // would read Abbey Road, Blue Train, Come Together, John Coltrane, Multi Part Book, Something,
    // Tail Book, The Beatles. So "books first" and "sorted" are distinguishable here, which is the
    // whole claim -- the ordering is a decision about what a driver reads first, not a tie-break.
    val nodes = BrowseTree.searchNodes(
      books = listOf(TAIL, MULTI),
      albums = listOf(ALBUM_A, ALBUM_B),
      artists = listOf(ARTIST_BEATLES, ARTIST_COLTRANE),
      songs = listOf(song("tr-1", "Come Together"), song("tr-2", "Something")),
    )

    assertThat(nodes.map(BrowseNode::title)).containsExactly(
      "Multi Part Book", "Tail Book",
      "Abbey Road", "Blue Train",
      "The Beatles", "John Coltrane",
      "Come Together", "Something",
    )
    // The kinds, in the same order -- so a run of eight rows built by the wrong builder, or four
    // groups collapsed into one, cannot satisfy the titles above by accident.
    assertThat(nodes.map(BrowseNode::mediaType)).containsExactly(
      BrowseMediaType.AUDIO_BOOK, BrowseMediaType.AUDIO_BOOK,
      BrowseMediaType.ALBUM, BrowseMediaType.ALBUM,
      BrowseMediaType.ARTIST, BrowseMediaType.ARTIST,
      BrowseMediaType.TRACK, BrowseMediaType.TRACK,
    )
    // ...and the ids, because a search row's whole purpose is to be expanded into a queue.
    assertThat(nodes.map { it.id.encode() }).containsExactly(
      "muplay/book/b-multi", "muplay/book/b-tail",
      "muplay/album/al-a", "muplay/album/al-b",
      "muplay/artist/ar-1", "muplay/artist/ar-2",
      // A track id is **bare** on the wire -- see `BrowseId.Track`'s own note on why the leaf is
      // the only id with no prefix.
      "tr-1", "tr-2",
    )
  }

  @Test
  fun `a book outranks music that sorts before it in every direction`() {
    // The sharpest form of "not a tie-break": the one book sorts **last** of the four rows by
    // title, and it is still first. Every ordering rule other than "books first" -- alphabetical,
    // reverse alphabetical (which would put the track first), input order, longest title -- gives
    // a different first row.
    val nodes = BrowseTree.searchNodes(
      books = listOf(ZULU_BOOK),
      albums = listOf(ALBUM_A),
      artists = listOf(ARTIST_BEATLES),
      songs = listOf(song("tr-1", "Come Together")),
    )

    assertThat(nodes.map(BrowseNode::title))
      .containsExactly("Zulu Book", "Abbey Road", "The Beatles", "Come Together")
  }

  @Test
  fun `an empty group contributes nothing and does not move the groups after it`() {
    // Vary one argument, hold the rest constant, assert both observations. Without the second of
    // these, an implementation that emitted the album group twice -- or that emitted the books
    // group in the albums slot -- would satisfy the first on its own.
    val withBook = BrowseTree.searchNodes(
      books = listOf(ZULU_BOOK),
      albums = listOf(ALBUM_A),
      artists = emptyList(),
      songs = emptyList(),
    )
    val withoutBook = BrowseTree.searchNodes(
      books = emptyList(),
      albums = listOf(ALBUM_A),
      artists = emptyList(),
      songs = emptyList(),
    )

    assertThat(withBook.map(BrowseNode::title)).containsExactly("Zulu Book", "Abbey Road")
    assertThat(withoutBook.map(BrowseNode::title)).containsExactly("Abbey Road")
    assertThat(BrowseTree.searchNodes(emptyList(), emptyList(), emptyList(), emptyList())).isEmpty()
  }

  @Test
  fun `a searched album is the album row and never a shuffle row`() {
    // `albumsNodes` -- the Albums *tab* -- puts one shuffle row per Music library above the albums.
    // A search result list must not: "Shuffle Music" is not a search result for anything the user
    // typed, and it would be the first row a driver takes. The two builders are one line apart in
    // this file, which is exactly how the wrong one gets called.
    val nodes = BrowseTree.searchNodes(
      books = emptyList(),
      albums = listOf(ALBUM_A, ALBUM_B),
      artists = emptyList(),
      songs = emptyList(),
    )

    assertThat(nodes.map { it.id.encode() }).containsExactly("muplay/album/al-a", "muplay/album/al-b")
    assertThat(nodes.map { it.id.encode() }).noneMatch { it.startsWith("muplay/shuffle/") }
  }

  private companion object {

    fun song(id: String, title: String) = Song(
      id = id,
      libraryId = 1,
      title = title,
      albumId = "al-a",
      albumName = "Abbey Road",
      artistId = "ar-1",
      artistName = "The Beatles",
      trackNumber = 1,
      discNumber = 1,
      durationSeconds = 300,
      suffix = "mp3",
      coverArtId = "cov-a",
    )

    val ALBUM_A = Album(
      id = "al-a",
      libraryId = 1,
      name = "Abbey Road",
      artistId = "ar-1",
      artistName = "The Beatles",
      coverArtId = "cov-a",
      songCount = 17,
      durationSeconds = 2_832,
    )

    val ALBUM_B = Album(
      id = "al-b",
      libraryId = 1,
      name = "Blue Train",
      artistId = "ar-2",
      artistName = "John Coltrane",
      coverArtId = "cov-b",
      songCount = 5,
      durationSeconds = 2_142,
    )

    fun book(
      id: String,
      title: String,
      author: String,
      cover: String?,
      fileCount: Int,
      durationMs: Long,
      positionMs: Long,
      isFinished: Boolean,
      lastPlayedAtEpochMs: Long,
    ) = BookSummary(
      bookId = id,
      libraryId = 2,
      title = title,
      author = author,
      coverArtId = cover,
      fileCount = fileCount,
      durationMs = durationMs,
      positionMs = positionMs,
      isFinished = isFinished,
      lastPlayedAtEpochMs = lastPlayedAtEpochMs,
    )

    /** One file, never started. */
    val TEST_BOOK = book("b-test", "Test Book", "Anonymous", "cov-test", 1, 15_000, 0, false, 0)

    /** One file, started, 20_000 of 80_000 ms in -- remaining exactly 60_000, the "1 min" boundary. */
    val SECOND = book("b-second", "A Wizard of Earthsea", "Ursula K. Le Guin", "cov-second", 1, 80_000, 20_000, false, 100)

    /** Three files, started at 60_000 of 300_000 ms -- exactly 0.2, and 4 min left. */
    val MULTI = book("b-multi", "Multi Part Book", "Terry Pratchett", "cov-multi", 3, 300_000, 60_000, false, 500)

    /** Two files, started, most recently heard. */
    val TAIL = book("b-tail", "Tail Book", "Anonymous", "cov-tail", 2, 10_000, 4_000, false, 900)

    /** Sorts **last** of every fixture title in the search tests, and is still the first row. */
    val ZULU_BOOK = book("b-zulu", "Zulu Book", "Anonymous", null, 1, 5_000, 0, false, 0)

    val ARTIST_BEATLES =
      Artist(id = "ar-1", libraryId = 1, name = "The Beatles", coverArtId = "cov-ar1", albumCount = 13)

    val ARTIST_COLTRANE =
      Artist(id = "ar-2", libraryId = 1, name = "John Coltrane", coverArtId = "cov-ar2", albumCount = 9)

    /** Finished. Belongs on the shelf, never on Continue. */
    val DONE = book("b-done", "Zero Hour", "Anonymous", null, 1, 5_000, 5_000, true, 1_000)

    /**
     * Deliberately **not** in either the alphabetical or the recency order, so that a function that
     * returned its input unchanged fails both order assertions rather than accidentally passing one.
     */
    val SHELF = listOf(MULTI, DONE, TEST_BOOK, TAIL, SECOND)
  }
}
