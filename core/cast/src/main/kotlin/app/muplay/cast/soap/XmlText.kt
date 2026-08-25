package app.muplay.cast.soap

/**
 * XML text escaping, in the one order that is correct.
 *
 * `&` **must** be replaced first. The obvious `replace("<", "&lt;").replace("&", "&amp;")` rewrites
 * the ampersand the first replacement just introduced and produces `&amp;lt;` -- and it produces
 * the right answer for every input that did not already contain an entity, which is most inputs,
 * which is why it survives review.
 *
 * All five characters are escaped, not the three that text content strictly requires: a DIDL-Lite
 * document is embedded as the text content of an element inside an attribute-bearing envelope, and
 * track titles really do contain apostrophes and quotation marks. One rule with no cases.
 *
 * Non-ASCII characters are **not** escaped. The envelope declares `utf-8` and the transport sends
 * `utf-8`; turning "Königin" into numeric references would be legal, would double the size of a
 * German library's metadata, and would make every byte-exact assertion in this plan wrong.
 */
object XmlText {

  fun escape(raw: String): String = raw
    .replace("&", "&amp;") // first, always
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

  /** The mirror image: `&amp;` **last**, for exactly the same reason. */
  fun unescape(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&") // last, always
}
