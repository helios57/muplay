package app.muplay.integrations.requests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TitleMatchingTest {

  @Test
  fun `normalisation is case-insensitive and trims`() {
    assertThat(TitleMatching.normalise("  Kind Of Blue  ")).isEqualTo("kind of blue")
    assertThat(TitleMatching.normalise("BITCHES BREW")).isEqualTo("bitches brew")
  }

  @Test
  fun `diacritics are stripped, which is what makes a german library searchable`() {
    // Spec section 4's own example is "Hörbücher". A user typing either spelling must find the
    // same album, and a request whose title came from Lidarr's metadata may carry either.
    assertThat(TitleMatching.normalise("Hörbücher")).isEqualTo(TitleMatching.normalise("Horbucher"))
    assertThat(TitleMatching.normalise("Café Blue")).isEqualTo("cafe blue")
    // Precomposed (U+00F6) and decomposed (o + U+0308) forms of the same word are different
    // strings and the same word -- and the raw strings really are unequal, which is what makes the
    // second assertion mean something. Without the NFD pass one survives as a letter carrying an
    // accent and the other as a letter followed by a mark, and they never become equal.
    assertThat("Bj\u00f6rk").isNotEqualTo("Bjo\u0308rk")
    assertThat(TitleMatching.normalise("Bj\u00f6rk")).isEqualTo(TitleMatching.normalise("Bjo\u0308rk"))
  }

  @Test
  fun `punctuation collapses to a single space rather than vanishing`() {
    // Vanishing would make "Sgt Peppers" equal to "SgtPeppers", which no server ever sends, and
    // would merge "Vol.1" and "Vol 1" -- the second of which is a real difference between albums.
    assertThat(TitleMatching.normalise("Sgt. Pepper's Lonely Hearts Club Band"))
      .isEqualTo("sgt pepper s lonely hearts club band")
    assertThat(TitleMatching.normalise("A  --  B")).isEqualTo("a b")
  }

  @Test
  fun `two different titles do not normalise to the same string`() {
    // The assertion that makes every one above mean something. Without it a `normalise` returning
    // a constant passes them all.
    assertThat(TitleMatching.normalise("Kind of Blue"))
      .isNotEqualTo(TitleMatching.normalise("Kind of Blue (Remastered)"))
    assertThat(TitleMatching.normalise("Dune")).isNotEqualTo(TitleMatching.normalise("Dune Messiah"))
  }

  @Test
  fun `a blank input normalises to an empty string`() {
    assertThat(TitleMatching.normalise("   ")).isEmpty()
    assertThat(TitleMatching.normalise("!!!")).isEmpty()
  }

  /**
   * **The plan's `\p{Alnum}` would fail this, and it is not a curiosity.** That class is Java's
   * POSIX one and matches ASCII only, so under it every title in a non-Latin script normalises to
   * the empty string — and two unrelated Japanese albums then normalise *equal*, which is the one
   * way this matcher could hand back a confident wrong answer instead of no answer.
   */
  @Test
  fun `a non-latin title survives normalisation and still differs from another`() {
    assertThat(TitleMatching.normalise("東京事変")).isEqualTo("東京事変")
    assertThat(TitleMatching.normalise("Кино")).isEqualTo("кино")
    assertThat(TitleMatching.normalise("東京事変")).isNotEqualTo(TitleMatching.normalise("椎名林檎"))
    // And it is genuinely doing the rest of the work on those scripts too, not passing them through.
    assertThat(TitleMatching.normalise("東京 - 事変")).isEqualTo("東京 事変")
  }

  @Test
  fun `digits are kept, because a volume number is the whole difference between two books`() {
    assertThat(TitleMatching.normalise("Dune Vol. 1")).isEqualTo("dune vol 1")
    assertThat(TitleMatching.normalise("Dune Vol. 1")).isNotEqualTo(TitleMatching.normalise("Dune Vol. 2"))
  }
}
