package app.muplay.book

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **Every audiobook screen speaks the audiobook voice, and no screen is exempt by forgetting.**
 *
 * `BookVoice` remaps the roles Material reaches for when nothing tells it otherwise — see its own
 * KDoc in `:core:designsystem` for the three controls that were rendering in the *music* voice on
 * a book screen because their source names no colour at all. The wrapper closes that, but only for
 * screens that are inside one, so "is every screen inside one" is the invariant worth holding, and
 * it is not something any colour assertion can see.
 *
 * A source scan, deliberately, and this module's own sources are inputs to this module's own test
 * task — so editing a screen re-runs this, which is exactly the hole CLAUDE.md records for
 * `ConventionTest`'s repo-wide rules (they read files Gradle does not know are inputs, and are
 * skipped as UP-TO-DATE while being violated).
 *
 * Falsified rather than assumed: with `BookVoice {` removed from `BookContent` this fails naming
 * `BookScreen.kt`. A probe nobody has watched go red is a probe nobody has watched work.
 */
class BookVoiceApplicationTest {

  private val screens = listOf(
    "BookScreen.kt" to "internal fun BookContent(",
    "BookshelfScreen.kt" to "internal fun BookshelfContent(",
    "BookPlayerScreen.kt" to "internal fun BookPlayerContent(",
  )

  @Test
  fun `every audiobook screen wraps its content in the audiobook voice`() {
    val missing = screens.filterNot { (file, signature) ->
      bodyOf(file, signature).trimStart().startsWith("BookVoice {")
    }.map { it.first }

    assertThat(missing)
      .describedAs(
        "audiobook screens whose content composable is not wrapped in BookVoice, so every " +
          "Material default inside them (Button, OutlinedButton, TextButton, Switch, " +
          "LinearProgressIndicator, surfaceTint) renders in the music voice",
      )
      .isEmpty()
  }

  @Test
  fun `the scan is looking at real files with real content composables in them`() {
    // Without this the test above passes just as happily against a typo in a filename or a renamed
    // composable -- it would find nothing to check and report nothing missing. Same shape as the
    // non-vacuity guard `LibraryUiStateTest` keeps on its own fixtures.
    assertThat(screens).hasSize(3)
    screens.forEach { (file, signature) ->
      assertThat(bodyOf(file, signature))
        .describedAs("the body of %s in %s", signature, file)
        .isNotBlank()
    }
  }

  /** The text between [signature]'s opening `) {` and the first column-0 `}` after it. */
  private fun bodyOf(file: String, signature: String): String {
    val source = File("src/main/kotlin/app/muplay/book/$file")
    assertThat(source).describedAs("source file %s", file).exists()
    val lines = source.readLines()
    val start = lines.indexOfFirst { it.startsWith(signature) }
    assertThat(start).describedAs("%s in %s", signature, file).isNotEqualTo(-1)
    val open = (start until lines.size).first { lines[it] == ") {" }
    val close = (open + 1 until lines.size).first { lines[it] == "}" }
    return lines.subList(open + 1, close).joinToString("\n")
  }
}
