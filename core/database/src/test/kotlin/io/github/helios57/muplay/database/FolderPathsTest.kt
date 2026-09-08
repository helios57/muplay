package io.github.helios57.muplay.database

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The path arithmetic behind folder browsing, on the fast tier.
 *
 * Every folder question this app asks reduces to "which paths sit under this prefix, and what is
 * the next segment of each" — so this is where the whole feature is decided, and it is a plain
 * function over strings rather than a query, so that the decision is held here and the database
 * only has to filter.
 */
class FolderPathsTest {

  private val library = listOf(
    "Fourth Author/Multi Part Book/01 - Part One.mp3",
    "Fourth Author/Multi Part Book/02 - Part Two.mp3",
    "Fourth Author/Single/01 - Alone.mp3",
    "Test Artist/Test Album/04 - Second Track.flac",
    "Loose Track.mp3",
  )

  @Test
  fun `the root lists top-level folders and the tracks lying beside them`() {
    val children = FolderPaths.children(prefix = "", paths = library)

    assertThat(children.map { it.name }).containsExactly("Fourth Author", "Test Artist")
    assertThat(children.map { it.path })
      .containsExactly("Fourth Author", "Test Artist")
    assertThat(FolderPaths.tracksDirectlyIn(prefix = "", paths = library))
      .containsExactly("Loose Track.mp3")
  }

  @Test
  fun `a folder's track count is everything beneath it, not just its own files`() {
    // The number shown beside a folder is the number "shuffle this folder" will draw from, so it
    // has to be the recursive count. "Fourth Author" holds no files of its own at all.
    val children = FolderPaths.children(prefix = "", paths = library)

    assertThat(children.single { it.name == "Fourth Author" }.trackCount).isEqualTo(3)
    assertThat(children.single { it.name == "Test Artist" }.trackCount).isEqualTo(1)
  }

  @Test
  fun `descending a folder shows only its own children`() {
    val children = FolderPaths.children(prefix = "Fourth Author", paths = library)

    assertThat(children.map { it.name }).containsExactly("Multi Part Book", "Single")
    assertThat(children.map { it.path })
      .containsExactly("Fourth Author/Multi Part Book", "Fourth Author/Single")
    assertThat(FolderPaths.tracksDirectlyIn(prefix = "Fourth Author", paths = library)).isEmpty()
  }

  @Test
  fun `a leaf folder has no children and holds its tracks`() {
    assertThat(FolderPaths.children(prefix = "Fourth Author/Multi Part Book", paths = library))
      .isEmpty()
    assertThat(FolderPaths.tracksDirectlyIn("Fourth Author/Multi Part Book", library))
      .containsExactly(
        "Fourth Author/Multi Part Book/01 - Part One.mp3",
        "Fourth Author/Multi Part Book/02 - Part Two.mp3",
      )
  }

  @Test
  fun `a prefix matches whole path segments, never half a folder name`() {
    // The defect this exists for. A `LIKE 'Fourth%'` -- or any prefix test that forgets the
    // separator -- matches "Fourth Author/..." and would show one folder's contents under
    // another folder's name. Shuffling it would then play tracks the user did not choose.
    val paths = listOf("Fourth/a.mp3", "Fourth Author/b.mp3", "Fourthly/c.mp3")

    assertThat(FolderPaths.tracksDirectlyIn(prefix = "Fourth", paths = paths))
      .containsExactly("Fourth/a.mp3")
    assertThat(FolderPaths.isUnder(prefix = "Fourth", path = "Fourth Author/b.mp3")).isFalse
    assertThat(FolderPaths.isUnder(prefix = "Fourth", path = "Fourth/a.mp3")).isTrue
    // A folder is not "under itself" as a track, but everything below it is.
    assertThat(FolderPaths.isUnder(prefix = "Fourth", path = "Fourth/sub/d.mp3")).isTrue
  }

  @Test
  fun `every track is under the root, which is what makes the root shuffle the whole library`() {
    assertThat(library).allMatch { FolderPaths.isUnder(prefix = "", path = it) }
  }

  @Test
  fun `folders are listed once however many tracks they hold, and in name order`() {
    val paths = listOf("b/2.mp3", "a/1.mp3", "b/1.mp3", "a/2.mp3", "a/nested/3.mp3")

    val children = FolderPaths.children(prefix = "", paths = paths)

    assertThat(children.map { it.name }).containsExactly("a", "b")
    assertThat(children.single { it.name == "a" }.trackCount).isEqualTo(3)
  }

  @Test
  fun `a trailing separator on the prefix means the same folder`() {
    // Callers build prefixes by concatenation and one of them will end up with a trailing slash.
    // Answering differently for "a" and "a/" would be a bug that only shows up on one screen.
    assertThat(FolderPaths.children(prefix = "Fourth Author/", paths = library))
      .isEqualTo(FolderPaths.children(prefix = "Fourth Author", paths = library))
    assertThat(FolderPaths.tracksDirectlyIn("Fourth Author/Multi Part Book/", library))
      .isEqualTo(FolderPaths.tracksDirectlyIn("Fourth Author/Multi Part Book", library))
  }

  @Test
  fun `a path that is not under the prefix contributes nothing`() {
    assertThat(FolderPaths.children(prefix = "Nowhere", paths = library)).isEmpty()
    assertThat(FolderPaths.tracksDirectlyIn(prefix = "Nowhere", paths = library)).isEmpty()
  }

  @Test
  fun `the parent of a folder is the folder above it, and the root's parent is nothing`() {
    // What the Up control on the folder screen needs, and the one place an off-by-one separator
    // strands a user in a folder they cannot leave.
    assertThat(FolderPaths.parentOf("Fourth Author/Multi Part Book")).isEqualTo("Fourth Author")
    assertThat(FolderPaths.parentOf("Fourth Author")).isEqualTo("")
    assertThat(FolderPaths.parentOf("")).isNull()
  }

  @Test
  fun `folder order ignores case, because a file browser that sorts Zoo before apple reads as broken`() {
    val paths = listOf("Zoo/1.mp3", "apple/1.mp3", "Banana/1.mp3")

    assertThat(FolderPaths.children(prefix = "", paths = paths).map { it.name })
      .containsExactly("apple", "Banana", "Zoo")
  }

  @Test
  fun `every song under a folder is reachable from it, which is what shuffle draws from`() {
    assertThat(library.filter { FolderPaths.isUnder("Fourth Author", it) })
      .containsExactlyInAnyOrder(
        "Fourth Author/Multi Part Book/01 - Part One.mp3",
        "Fourth Author/Multi Part Book/02 - Part Two.mp3",
        "Fourth Author/Single/01 - Alone.mp3",
      )
  }

  @Test
  fun `the root's pattern matches every path, which is what makes it the whole library`() {
    assertThat(FolderPaths.likePatternFor("")).isEqualTo("%")
  }

  @Test
  fun `a folder's pattern ends at the separator, so it cannot reach a sibling`() {
    assertThat(FolderPaths.likePatternFor("Fourth")).isEqualTo("Fourth/%")
    assertThat(FolderPaths.likePatternFor("Fourth/")).isEqualTo("Fourth/%")
    assertThat(FolderPaths.likePatternFor("a/b")).isEqualTo("a/b/%")
  }

  @Test
  fun `a folder named with a wildcard character matches itself and nothing else`() {
    // A real folder name. Unescaped, "50% Off/%" is a LIKE pattern matching "50 Off/x.mp3",
    // "509 Off/x.mp3" and, worse, "50anything/%" -- so browsing one folder would list another's
    // tracks and shuffling it would play them. The DAO pairs this with ESCAPE '\\'.
    assertThat(FolderPaths.likePatternFor("50% Off")).isEqualTo("50\\% Off/%")
    assertThat(FolderPaths.likePatternFor("a_b")).isEqualTo("a\\_b/%")
    // The escape character itself, or it stops escaping the moment a path contains a backslash.
    assertThat(FolderPaths.likePatternFor("a\\b")).isEqualTo("a\\\\b/%")
  }

  @Test
  fun `a track is directly in a folder only when no folder sits between them`() {
    // The predicate behind the tracks half of a folder listing. It has to be a predicate on one
    // path rather than a filter over strings, because the screen shows whole songs and the list
    // it filters is a list of rows, not of paths.
    assertThat(FolderPaths.isDirectlyIn("Fourth Author", "Fourth Author/Single/01 - Alone.mp3"))
      .describedAs("a track two levels down was claimed by the level above")
      .isFalse
    assertThat(FolderPaths.isDirectlyIn("Fourth Author/Single", "Fourth Author/Single/01 - Alone.mp3"))
      .isTrue
    assertThat(FolderPaths.isDirectlyIn("", "Loose Track.mp3")).isTrue
    assertThat(FolderPaths.isDirectlyIn("", "Fourth Author/Single/01 - Alone.mp3")).isFalse
    // ...and the whole-segment rule holds here too.
    assertThat(FolderPaths.isDirectlyIn("Fourth", "Fourth Author/b.mp3")).isFalse
  }
}
