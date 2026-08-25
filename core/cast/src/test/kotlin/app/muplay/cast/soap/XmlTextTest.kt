package app.muplay.cast.soap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XmlTextTest {

  @Test
  fun `each of the five characters is escaped`() {
    // One assertion per character, so an implementation missing one fails on that one rather than
    // on a compound string where the failure message names nothing useful.
    assertThat(XmlText.escape("&")).isEqualTo("&amp;")
    assertThat(XmlText.escape("<")).isEqualTo("&lt;")
    assertThat(XmlText.escape(">")).isEqualTo("&gt;")
    assertThat(XmlText.escape("\"")).isEqualTo("&quot;")
    assertThat(XmlText.escape("'")).isEqualTo("&apos;")
  }

  /**
   * The ordering bug, isolated. `replace("<", "&lt;").replace("&", "&amp;")` produces `&amp;lt;`
   * here and `&lt;` in every test that does not already contain an entity -- which is most of them.
   */
  @Test
  fun `the ampersand is replaced first, so an existing entity is escaped once and not twice`() {
    assertThat(XmlText.escape("&lt;")).isEqualTo("&amp;lt;")
    assertThat(XmlText.escape("&amp;")).isEqualTo("&amp;amp;")
    assertThat(XmlText.escape("a & b < c")).isEqualTo("a &amp; b &lt; c")
  }

  @Test
  fun `escaping is idempotent in the sense that matters, which is that it is not`() {
    // Stated as an assertion because "escape it again just in case" is the reflex that produces
    // `&amp;lt;DIDL-Lite`, and a reader needs to see that double-escaping is visible rather than
    // harmless.
    val once = XmlText.escape("<DIDL-Lite/>")
    assertThat(once).isEqualTo("&lt;DIDL-Lite/&gt;")
    assertThat(XmlText.escape(once)).isEqualTo("&amp;lt;DIDL-Lite/&amp;gt;")
  }

  @Test
  fun `text with nothing to escape comes back unchanged`() {
    assertThat(XmlText.escape("Track 1")).isEqualTo("Track 1")
    assertThat(XmlText.escape("")).isEmpty()
  }

  @Test
  fun `non-ascii text is left alone, because the document is utf-8`() {
    // Escaping these into numeric references would be legal and would also make every byte-exact
    // assertion in this plan wrong. The envelope declares utf-8 and means it.
    assertThat(XmlText.escape("Königin der Nacht")).isEqualTo("Königin der Nacht")
    assertThat(XmlText.escape("北国の春")).isEqualTo("北国の春")
  }

  @Test
  fun `a real track title with three of the five characters survives`() {
    assertThat(XmlText.escape("Rock & Roll <live> \"1971\""))
      .isEqualTo("Rock &amp; Roll &lt;live&gt; &quot;1971&quot;")
  }

  @Test
  fun `unescape reverses escape, including for the ampersand`() {
    // The round trip is what Task 4's DIDL test asserts across the whole envelope; this is the
    // unit of it. `unescape` must handle `&amp;` LAST for the mirror-image reason.
    listOf(
      "Rock & Roll <live> \"1971\"",
      "&lt;",
      "a & b",
      "Königin der Nacht",
      "",
    ).forEach { original ->
      assertThat(XmlText.unescape(XmlText.escape(original)))
        .describedAs("round trip of \"%s\"", original)
        .isEqualTo(original)
    }
  }

  /**
   * `unescape` on its own, not only as the inverse of `escape`.
   *
   * The round trip above is satisfied by an identity pair -- `escape = { it }` and
   * `unescape = { it }` pass every case in it. These are the observations that are not.
   */
  @Test
  fun `unescape decodes a document a device wrote, which escape never produced`() {
    assertThat(XmlText.unescape("&lt;DIDL-Lite/&gt;")).isEqualTo("<DIDL-Lite/>")
    assertThat(XmlText.unescape("&amp;lt;")).isEqualTo("&lt;")
    assertThat(XmlText.unescape("Rock &amp; Roll")).isEqualTo("Rock & Roll")
    assertThat(XmlText.unescape("&quot;1971&quot;")).isEqualTo("\"1971\"")
    assertThat(XmlText.unescape("&apos;71")).isEqualTo("'71")
    // Nothing to decode: unchanged, and specifically not stripped.
    assertThat(XmlText.unescape("plain & simple")).isEqualTo("plain & simple")
  }
}
