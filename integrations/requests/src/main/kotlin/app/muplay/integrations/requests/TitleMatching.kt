package app.muplay.integrations.requests

import java.text.Normalizer

/**
 * The one normalisation both halves of an arrival match go through.
 *
 * Deliberately shallow and fully specified: Unicode NFD with combining marks stripped, lower-cased,
 * every run of non-letter/non-digit characters collapsed to one space, trimmed. It makes
 * `"Hörbücher"` match `"Horbucher"` and `"Sgt. Pepper's"` match `"Sgt Peppers"`, and it makes
 * **no** attempt to be clever about editions, subtitles or featured artists — because every
 * additional cleverness is another way to match the wrong album, and a wrong match is worse than
 * no match here.
 *
 * Punctuation collapses to a space rather than vanishing: vanishing would equate `"Vol.1"` and
 * `"Vol 1"`, which is fine, but also `"Sgt Peppers"` and `"SgtPeppers"`, which no server sends,
 * and it would merge titles that differ only in spacing.
 *
 * **The character class is `\p{L}\p{N}`, not the plan's `\p{Alnum}`, and that is a correctness fix
 * rather than a style one.** `\p{Alnum}` is Java's POSIX class and is **ASCII-only**: under it
 * every Cyrillic, Greek, Han, Kana or Hangul title normalises to the empty string, so two
 * unrelated Japanese albums would normalise equal and a request could be answered with the wrong
 * one. `\p{L}`/`\p{N}` are Unicode general categories and keep those scripts intact.
 * [RequestArrivalDetector] additionally refuses to match on an empty normalised title at all, so
 * the residue of that class of input — `"!!!"`, `"   "` — is "no answer" rather than "any answer".
 */
object TitleMatching {

  /** Anything that is not a Unicode letter or digit, in runs. Unicode-aware; see the class doc. */
  private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

  /** Every Unicode mark NFD can produce — the accents that make `"Hörbücher"` and `"Horbucher"` differ. */
  private val COMBINING_MARKS = Regex("\\p{M}+")

  fun normalise(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(COMBINING_MARKS, "")
      .lowercase()
      .replace(NON_ALPHANUMERIC, " ")
      .trim()
}
