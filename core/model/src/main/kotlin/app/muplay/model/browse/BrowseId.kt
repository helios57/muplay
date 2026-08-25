package app.muplay.model.browse

/**
 * The identity of one node in the browse tree Android Auto, Wear OS and the Assistant read.
 *
 * A `MediaItem`'s `mediaId` is the only handle any of those surfaces keeps, and it comes back to
 * this app out of context: `onGetChildren(parentId)`, `onGetItem(mediaId)` and `onAddMediaItems`
 * are all handed a bare string. Android Auto additionally *persists* browse ids and offers them
 * again days later, after a reinstall, so the encoded form below is a wire contract rather than an
 * implementation detail — `BrowseIdTest` asserts the literal strings for that reason, and changing
 * one is a deliberate act with a migration cost, not a refactor.
 *
 * **A playable track encodes to the bare server id, with no [PREFIX].** Android Auto marks the
 * currently-playing row by comparing its `mediaId` with the session's current item, and Plan 3's
 * `MediaItems.of` sets that to `Song.id`; a prefixed track id would silently disable that
 * highlight while leaving both halves individually correct. The cost is that a server id starting
 * with `muplay/` would be ambiguous, which [Track] refuses at construction rather than resolving
 * wrongly later.
 */
sealed interface BrowseId {

  /** The stable string that goes on the wire as a `MediaItem`'s `mediaId`. */
  fun encode(): String

  /** The tree's root. Returned by `onGetLibraryRoot`; never has children of its own kind. */
  data object Root : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ROOT"
  }

  /** Books with a stored position, most recently heard first. The car's first tab. */
  data object Continue : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_CONTINUE"
  }

  /** Every book in every library the user tagged Audiobooks. */
  data object Books : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_BOOKS"
  }

  /** Albums across every library the user tagged Music. */
  data object Albums : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ALBUMS"
  }

  /** Artists across every library the user tagged Music. */
  data object Artists : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_ARTISTS"
  }

  /** The library picker. Phone surface only: an unbounded list is what a driver must not get. */
  data object Libraries : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_LIBRARIES"
  }

  /** One Navidrome library, by its numeric id. See this file's note on why the type is `Int`. */
  data class Library(val libraryId: Int) : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_LIBRARY$SEPARATOR$libraryId"
  }

  /** Playable. Library-scoped shuffle, the headline feature, as one tap in a car. */
  data class Shuffle(val libraryId: Int) : BrowseId {
    override fun encode(): String = "$PREFIX$KIND_SHUFFLE$SEPARATOR$libraryId"
  }

  /** Playable **and** browsable: playing it resumes, opening it lists its files. */
  data class Book(val bookId: String) : BrowseId {
    init { require(bookId.isNotEmpty()) { "a book id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_BOOK$SEPARATOR$bookId"
  }

  /** Playable and browsable: playing it plays the album, opening it lists its tracks. */
  data class Album(val albumId: String) : BrowseId {
    init { require(albumId.isNotEmpty()) { "an album id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_ALBUM$SEPARATOR$albumId"
  }

  /** Browsable. Its children are that artist's albums. */
  data class Artist(val artistId: String) : BrowseId {
    init { require(artistId.isNotEmpty()) { "an artist id may not be empty" } }
    override fun encode(): String = "$PREFIX$KIND_ARTIST$SEPARATOR$artistId"
  }

  /**
   * Playable leaf. Encodes to the **bare** server id so that it equals the `mediaId` Plan 3's
   * `MediaItems.of` puts on the playing item — see this file's own documentation.
   */
  data class Track(val songId: String) : BrowseId {
    init {
      require(songId.isNotEmpty()) { "a track id may not be empty" }
      require(!songId.startsWith(PREFIX)) {
        "a server id starting with '$PREFIX' is indistinguishable from a browse node id; refusing " +
          "'$songId' here rather than resolving it to the wrong node later"
      }
    }

    override fun encode(): String = songId
  }

  companion object {
    /** Everything except a [Track] carries this. */
    const val PREFIX: String = "muplay/"

    /** Between the kind and its payload. A payload may itself contain this. */
    const val SEPARATOR: String = "/"

    const val KIND_ROOT: String = "root"
    const val KIND_CONTINUE: String = "continue"
    const val KIND_BOOKS: String = "books"
    const val KIND_ALBUMS: String = "albums"
    const val KIND_ARTISTS: String = "artists"
    const val KIND_LIBRARIES: String = "libraries"
    const val KIND_LIBRARY: String = "library"
    const val KIND_SHUFFLE: String = "shuffle"
    const val KIND_BOOK: String = "book"
    const val KIND_ALBUM: String = "album"
    const val KIND_ARTIST: String = "artist"

    /**
     * Parses [encoded] back into a node id, or `null` if it names no node this app serves.
     *
     * `null`, never a default and never an exception: [encoded] arrives from a car head unit, a
     * watch, the Assistant or the system's media-resumption store, and the correct answer to an id
     * this build does not recognise is `LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)` —
     * which Task 4 produces from this `null`. Returning [Root] for an unparseable id would send a
     * driver to the top of the tree with no explanation.
     */
    fun decode(encoded: String): BrowseId? {
      if (encoded.isEmpty()) return null
      // No prefix means a bare server id, i.e. a track. This is checked before anything else so
      // that a song id containing a slash is never mistaken for a kind.
      if (!encoded.startsWith(PREFIX)) return Track(encoded)

      val body = encoded.removePrefix(PREFIX)
      val kind = body.substringBefore(SEPARATOR)
      // `body.length > kind.length` distinguishes "muplay/root" from "muplay/root/", which are
      // different strings and must not be the same node -- a lenient parse here is how a trailing
      // slash in someone's persisted recents turns into a wrong answer.
      val hasPayload = body.length > kind.length
      val payload = if (hasPayload) body.substring(kind.length + SEPARATOR.length) else ""

      return when (kind) {
        KIND_ROOT -> Root.takeUnless { hasPayload }
        KIND_CONTINUE -> Continue.takeUnless { hasPayload }
        KIND_BOOKS -> Books.takeUnless { hasPayload }
        KIND_ALBUMS -> Albums.takeUnless { hasPayload }
        KIND_ARTISTS -> Artists.takeUnless { hasPayload }
        KIND_LIBRARIES -> Libraries.takeUnless { hasPayload }
        KIND_LIBRARY -> canonicalInt(payload)?.let(::Library)
        KIND_SHUFFLE -> canonicalInt(payload)?.let(::Shuffle)
        KIND_BOOK -> payload.takeIf { it.isNotEmpty() }?.let(::Book)
        KIND_ALBUM -> payload.takeIf { it.isNotEmpty() }?.let(::Album)
        KIND_ARTIST -> payload.takeIf { it.isNotEmpty() }?.let(::Artist)
        else -> null
      }
    }

    /**
     * [payload] as an `Int`, but only in its canonical decimal form.
     *
     * `toIntOrNull` alone accepts `"+1"` and `"01"`, neither of which this encoder ever produces,
     * so accepting them would make `decode(encode(x))` an identity while `encode(decode(s))` was
     * not — and, worse, would let two different strings name the same node in Auto's persisted
     * recents. Spec section 4 is the reason strictness is the right default here: a library id
     * that is *not* a number is silently ignored by Navidrome and widens the scope to every
     * library, with no runtime signal at all.
     */
    private fun canonicalInt(payload: String): Int? =
      payload.toIntOrNull()?.takeIf { it.toString() == payload }
  }
}
