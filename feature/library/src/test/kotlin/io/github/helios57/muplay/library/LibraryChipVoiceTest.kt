package io.github.helios57.muplay.library

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **A library keeps the colour setup taught the user for it.**
 *
 * `Color.kt` reserves `primary` for music and `tertiary` for audiobooks, and `SetupScreen` teaches
 * that pairing as directly as a screen can: its two chips are labelled "Tag as Music"
 * (`primaryContainer`) and "Tag as Audiobooks" (`tertiaryContainer`). The library selector then
 * drew the same libraries as stock `FilterChip`s in neither colour, three rows above a `Books` card
 * that *is* `tertiaryContainer` -- so the continuity was closed for the door out of the music half
 * and left open for the thing the door leads to.
 *
 * A source scan, for the same reason [BookVoiceApplicationTest] in `:feature:book` is one: there is
 * no Compose matcher for "what colour is that chip", and a screenshot would only show it in one
 * theme and only for a library a fixture happens to have tagged. This module's own sources are
 * declared inputs of this module's own test task, so editing the screen re-runs this -- which is
 * the hole CLAUDE.md records for `ConventionTest`'s repo-wide rules.
 *
 * Comments are stripped before matching. The composable's own KDoc names both container roles in
 * prose, and a raw-text scan would be satisfied by the explanation instead of by the code -- the
 * self-matching failure this repository has now paid for five times, and the same fix
 * `VerifyMergedManifestTask`'s required half makes.
 *
 * Falsified: with the `colors =` argument deleted, both assertions below go red naming the role
 * that is missing.
 */
class LibraryChipVoiceTest {

  @Test
  fun `the library selector tints an audiobook library in the audiobook voice`() {
    val body = libraryChipsBody()

    assertThat(body)
      .describedAs("LibraryChips must decide its tint from the library's own role")
      .contains("LibraryRole.AUDIOBOOKS")
    assertThat(body)
      .describedAs("an audiobook library's selected chip is the container colour setup tagged it with")
      .contains("tertiaryContainer", "onTertiaryContainer")
    assertThat(body)
      .describedAs("a music library's selected chip stays in the music voice")
      .contains("primaryContainer", "onPrimaryContainer")
  }

  @Test
  fun `the scan is looking at the real composable`() {
    // Without this, a renamed composable makes the test above scan an empty string and report
    // nothing -- the failure mode where a check returns the same falsey value for "clean" and
    // "I could not look". Same guard `BookVoiceApplicationTest` keeps on its own file list.
    val body = libraryChipsBody()
    assertThat(body).isNotBlank()
    assertThat(body).contains("FilterChip(", "onLibrarySelected(library.id)")
  }

  /** The body of `LibraryChips`, from its signature to the next column-0 `}`, comments removed. */
  private fun libraryChipsBody(): String {
    // `LibraryChips.kt`, not `LibraryScreen.kt`: the composable moved out of the albums screen
    // when the folders and playlists tabs became callers of it, and this scan moved with it. That
    // is the whole reason for the `isNotBlank` guard in the test above -- a scan pointed at the
    // wrong file reports "clean" rather than "I could not look".
    val source = File("src/main/kotlin/io/github/helios57/muplay/library/LibraryChips.kt")
    assertThat(source).describedAs("LibraryChips.kt").exists()
    val lines = source.readLines()
    val start = lines.indexOfFirst { it.startsWith("internal fun LibraryChips(") }
    assertThat(start).describedAs("the LibraryChips declaration").isNotEqualTo(-1)
    val close = (start + 1 until lines.size).first { lines[it] == "}" }
    return lines.subList(start + 1, close).joinToString("\n")
      .replace(Regex("""/\*[\s\S]*?\*/"""), "")
      .replace(Regex("""//.*"""), "")
  }
}
