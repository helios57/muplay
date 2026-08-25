package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The wire format of every browse node id.
 *
 * Three separate questions, asked separately on purpose:
 *
 * - **What exactly does each id encode to?** Asserted against literal strings, because Android Auto
 *   persists these across reinstalls and reboots — the format is a contract with a piece of
 *   software this project does not own, and a round-trip test cannot see a format change at all.
 * - **Can two different nodes ever encode to the same string?** Asserted as injectivity over the
 *   *whole* hierarchy at once, because `decode(encode(x)) == x` holds member by member even when
 *   two members collide — and a collision here is a car playing the wrong thing, silently.
 * - **What does a hostile payload do?** Asserted with ids containing the separator, a colon,
 *   spaces, unicode and the scheme's own prefix, because by the time an id comes back from a head
 *   unit it is untrusted input.
 */
class BrowseIdTest {

  /**
   * One representative of every member of the sealed hierarchy, each parameterised member present
   * **twice** with different payloads.
   *
   * The second instance is what makes this a test of `encode` rather than of a lookup table: an
   * `encode` that ignored its payload would still round-trip and would still produce twelve
   * distinct strings from twelve distinct kinds, but it cannot produce two distinct strings from
   * two `Book`s.
   *
   * [memberName] below is an exhaustive `when` over `BrowseId`, so a member added to the hierarchy
   * without being added to this list is a **compile error** in this file rather than an id nobody
   * ever tested.
   */
  private val everyMember: List<BrowseId> = listOf(
    BrowseId.Root,
    BrowseId.Continue,
    BrowseId.Books,
    BrowseId.Albums,
    BrowseId.Artists,
    BrowseId.Libraries,
    BrowseId.Library(1),
    BrowseId.Library(2),
    BrowseId.Shuffle(1),
    BrowseId.Shuffle(2),
    BrowseId.Book("al-7c3f"),
    BrowseId.Book("al-9911"),
    BrowseId.Album("al-7c3f"),
    BrowseId.Album("al-9911"),
    BrowseId.Artist("al-7c3f"),
    BrowseId.Artist("al-9911"),
    BrowseId.Track("al-7c3f"),
    BrowseId.Track("al-9911"),
  )

  /**
   * The member [id] belongs to, as a name.
   *
   * Exists only for its `when`, which is exhaustive over the sealed interface and therefore stops
   * compiling the day a thirteenth member is added — the one mechanism in this file that can
   * notice a member nobody wrote a test for.
   */
  private fun memberName(id: BrowseId): String = when (id) {
    BrowseId.Root -> "Root"
    BrowseId.Continue -> "Continue"
    BrowseId.Books -> "Books"
    BrowseId.Albums -> "Albums"
    BrowseId.Artists -> "Artists"
    BrowseId.Libraries -> "Libraries"
    is BrowseId.Library -> "Library"
    is BrowseId.Shuffle -> "Shuffle"
    is BrowseId.Book -> "Book"
    is BrowseId.Album -> "Album"
    is BrowseId.Artist -> "Artist"
    is BrowseId.Track -> "Track"
  }

  @Test
  fun `every id encodes to its exact documented string`() {
    // The whole table in one assertion, mapped to the field under test and compared as an exact
    // ordered list. A per-id loop of `assertThat(id.encode()).isNotEmpty()` would pass against a
    // single constant; this cannot.
    val ids: List<BrowseId> = listOf(
      BrowseId.Root,
      BrowseId.Continue,
      BrowseId.Books,
      BrowseId.Albums,
      BrowseId.Artists,
      BrowseId.Libraries,
      BrowseId.Library(2),
      BrowseId.Shuffle(1),
      BrowseId.Book("al-7c3f"),
      BrowseId.Album("al-9911"),
      BrowseId.Artist("ar-0042"),
      BrowseId.Track("tr-abcdef"),
    )

    assertThat(ids.map(BrowseId::encode)).containsExactly(
      "muplay/root",
      "muplay/continue",
      "muplay/books",
      "muplay/albums",
      "muplay/artists",
      "muplay/libraries",
      "muplay/library/2",
      "muplay/shuffle/1",
      "muplay/book/al-7c3f",
      "muplay/album/al-9911",
      "muplay/artist/ar-0042",
      // Bare, with no prefix at all -- see BrowseId's own documentation. This is the one line in
      // this file whose *absence of* a prefix is the assertion.
      "tr-abcdef",
    )
  }

  @Test
  fun `every documented string decodes to its exact id`() {
    val encoded = listOf(
      "muplay/root",
      "muplay/continue",
      "muplay/books",
      "muplay/albums",
      "muplay/artists",
      "muplay/libraries",
      "muplay/library/2",
      "muplay/shuffle/1",
      "muplay/book/al-7c3f",
      "muplay/album/al-9911",
      "muplay/artist/ar-0042",
      "tr-abcdef",
    )

    assertThat(encoded.map { BrowseId.decode(it) }).containsExactly(
      BrowseId.Root,
      BrowseId.Continue,
      BrowseId.Books,
      BrowseId.Albums,
      BrowseId.Artists,
      BrowseId.Libraries,
      BrowseId.Library(2),
      BrowseId.Shuffle(1),
      BrowseId.Book("al-7c3f"),
      BrowseId.Album("al-9911"),
      BrowseId.Artist("ar-0042"),
      BrowseId.Track("tr-abcdef"),
    )
  }

  @Test
  fun `no two nodes in the whole hierarchy encode to the same string`() {
    // Injectivity, which round-tripping does not imply. `Book("x")` and `Album("x")` differing is
    // the case a shared `"$PREFIX$kind$SEPARATOR$payload"` helper gets right and a copy-pasted
    // `KIND_BOOK` in `Album.encode()` gets wrong -- and that defect round-trips perfectly for
    // `Book`, so only a whole-hierarchy comparison can see it.
    val encoded = everyMember.map(BrowseId::encode)

    assertThat(encoded).hasSize(everyMember.size).doesNotHaveDuplicates()
  }

  @Test
  fun `every node in the whole hierarchy decodes back to itself`() {
    // Ordered, element by element, against the source list: `containsExactlyElementsOf` would let
    // a decode that returned the right *set* in the wrong order pass, which is one of the recorded
    // ways an assertion here can run without discriminating.
    assertThat(everyMember.map { BrowseId.decode(it.encode()) })
      .containsExactlyElementsOf(everyMember)
  }

  @Test
  fun `the sample table covers every member of the sealed hierarchy`() {
    // The assertion is the roster; the mechanism is `memberName`'s exhaustive `when`, which stops
    // compiling when a member is added. Both halves are needed: the `when` notices the addition,
    // this list notices that the addition was never given a sample to test.
    assertThat(everyMember.map(::memberName).distinct()).containsExactly(
      "Root",
      "Continue",
      "Books",
      "Albums",
      "Artists",
      "Libraries",
      "Library",
      "Shuffle",
      "Book",
      "Album",
      "Artist",
      "Track",
    )
  }

  @Test
  fun `the payload survives every character a server id could contain`() {
    // Each of these has broken a hand-rolled `split(":")` somewhere. The separator one is the
    // important case: `split` without a limit turns "a/b" into two fragments and silently drops
    // the second.
    val payloads = listOf(
      "a/b/c",
      "with:colon",
      "with space",
      "Hörbücher",
      "muplay",            // the prefix's first component, but not the prefix
      "%2F",
      "-",
    )

    assertThat(payloads.map { BrowseId.decode(BrowseId.Book(it).encode()) })
      .containsExactly(*payloads.map { BrowseId.Book(it) }.toTypedArray())
  }

  @Test
  fun `a bare id decodes to a track, including one containing a slash`() {
    assertThat(listOf(BrowseId.decode("tr-1"), BrowseId.decode("tr/1"), BrowseId.decode("muplayer")))
      .containsExactly(BrowseId.Track("tr-1"), BrowseId.Track("tr/1"), BrowseId.Track("muplayer"))
  }

  @Test
  fun `a library id that is not canonically numeric is rejected rather than widened`() {
    // Spec section 4: a non-numeric musicFolderId returns `status: ok` and EVERY library's
    // content. There is no runtime signal when it is wrong, so this parse is the only place the
    // type can be re-established, and it has to be strict rather than lenient.
    val hostile = listOf(
      "muplay/library/abc",
      "muplay/library/",
      "muplay/library/1abc",
      "muplay/library/ 1",
      "muplay/library/+1",
      "muplay/library/01",
      // Two more of the same class, both of which `toIntOrNull` alone accepts or silently wraps:
      // "-0" parses to 0 and would give a second spelling of the same node, and 2147483648 is
      // Int.MAX_VALUE + 1, which must be rejected rather than wrapped to a negative library.
      "muplay/library/-0",
      "muplay/library/2147483648",
      "muplay/shuffle/abc",
      "muplay/shuffle/",
      "muplay/shuffle/01",
      "muplay/shuffle/2147483648",
    )

    assertThat(hostile.map { BrowseId.decode(it) }).containsExactly(
      null, null, null, null, null, null, null, null, null, null, null, null,
    )
  }

  @Test
  fun `a negative library id is a node, because the server rejects it loudly`() {
    // The one payload `canonicalInt` accepts that no real library carries. It is deliberately not
    // rejected here: "-1" is the canonical decimal form of an Int, so it names exactly one node
    // and cannot be a second spelling of another -- and spec section 4 measured that an unknown
    // *numeric* musicFolderId fails with error 70, which is the loud path. The silent widening
    // this scheme exists to prevent is the non-numeric one above.
    assertThat(BrowseId.decode("muplay/library/-1")).isEqualTo(BrowseId.Library(-1))
    assertThat(BrowseId.Library(-1).encode()).isEqualTo("muplay/library/-1")
  }

  @Test
  fun `a malformed browse id decodes to null rather than to something plausible`() {
    val malformed = listOf(
      "",                       // Media3's default mediaId. Must never be a valid node.
      "muplay/",
      "muplay/nosuchkind",
      "muplay/nosuchkind/1",
      "muplay/root/1",          // a kind that takes no payload, given one
      "muplay/root/",           // and the same kind given an empty one -- a different string again
      "muplay/continue/x",
      "muplay/books/1",
      "muplay/albums/1",
      "muplay/artists/1",
      "muplay/libraries/1",
      "muplay/book",            // a kind that requires a payload, given none
      "muplay/book/",
      "muplay/album/",
      "muplay/artist/",
      "muplay/shuffle",
      "muplay/library",
      "MUPLAY/root",            // the prefix is not case-insensitive: this is a bare track id
    )

    assertThat(malformed.map { BrowseId.decode(it) }).containsExactly(
      null, null, null, null, null, null, null, null, null,
      null, null, null, null, null, null, null, null,
      // ...except the last: "MUPLAY/root" does not carry the prefix, so it is a server id, and
      // treating it as one is the same rule that makes "muplayer" a track. Asserted here, beside
      // the rejections, because the boundary between "malformed node" and "bare track" is exactly
      // what a reader of this scheme needs to see.
      BrowseId.Track("MUPLAY/root"),
    )
  }

  @Test
  fun `a track id that would collide with the scheme is refused loudly`() {
    // The one ambiguity the bare-leaf decision buys, and it fails at construction rather than
    // resolving to the wrong node three screens later.
    assertThatThrownBy { BrowseId.Track("muplay/books") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("muplay/")

    assertThatThrownBy { BrowseId.Track("") }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `no payload-carrying id can be constructed empty`() {
    // Every one of these, not just `Track`'s: an empty payload encodes to a string `decode` maps
    // to `null` (`"muplay/book/"`), so allowing one would make `encode` produce an id this scheme
    // cannot read back -- the round trip broken in the direction no round-trip test looks.
    assertThatThrownBy { BrowseId.Book("") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("book")
    assertThatThrownBy { BrowseId.Album("") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("album")
    assertThatThrownBy { BrowseId.Artist("") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("artist")
    assertThatThrownBy { BrowseId.Track("") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("track")
  }

  @Test
  fun `the payload is what distinguishes two ids of the same kind`() {
    // Rule 2: a value observed at exactly one value is not tested. Two books, two encodings, two
    // decodings, and the two are asserted to differ -- so an `encode` that ignored its payload
    // fails here even though every single-value assertion above would still pass.
    val first = BrowseId.Book("al-1")
    val second = BrowseId.Book("al-2")

    assertThat(listOf(first.encode(), second.encode()))
      .containsExactly("muplay/book/al-1", "muplay/book/al-2")
    assertThat(first.encode()).isNotEqualTo(second.encode())
    assertThat(BrowseId.decode(first.encode())).isNotEqualTo(BrowseId.decode(second.encode()))
  }

  @Test
  fun `the wire constants are the strings the encoded forms are built from`() {
    // The constants are public API: Task 4's browse tree and the Wear surface both build ids from
    // them, so a rename that changed one silently would change the wire format for every id above.
    // Pinned here as literals, next to the table that spells the same strings out.
    assertThat(BrowseId.PREFIX).isEqualTo("muplay/")
    assertThat(BrowseId.SEPARATOR).isEqualTo("/")
    assertThat(
      listOf(
        BrowseId.KIND_ROOT,
        BrowseId.KIND_CONTINUE,
        BrowseId.KIND_BOOKS,
        BrowseId.KIND_ALBUMS,
        BrowseId.KIND_ARTISTS,
        BrowseId.KIND_LIBRARIES,
        BrowseId.KIND_LIBRARY,
        BrowseId.KIND_SHUFFLE,
        BrowseId.KIND_BOOK,
        BrowseId.KIND_ALBUM,
        BrowseId.KIND_ARTIST,
      )
    ).containsExactly(
      "root", "continue", "books", "albums", "artists", "libraries",
      "library", "shuffle", "book", "album", "artist",
    )
  }
}
