package app.muplay.media

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **A book has one speed, and `media_progress.speed` is not it.**
 *
 * Spec section 3 puts `speed` and `skipSilence` on the per-*item* progress row. For a book that is
 * thirty MP3s that is the wrong grain: the speed a listener chose in chapter 3 would not survive
 * the transition to chapter 4. `BookSettings` and the `book_settings` table are Plan 4's correction,
 * and `ProgressWriter` holds up its half -- it preserves those two columns and never writes them.
 *
 * This is the other half, and it is **structural rather than behavioural** for a reason worth
 * stating. A test that stored two different speeds and asserted the player used the book's one
 * could be satisfied by an `AudiobookItem` that happened to carry the right number; what has to be
 * true is that this class cannot *reach* the item-grain column at all. That is a property of the
 * source text -- "no second reader exists" -- and no runtime test can express it, which is the same
 * argument `PlayerConstructionTest` makes about a second `ExoPlayer.Builder`.
 *
 * The failure it prevents is silent: a book with two speeds, one of them winning depending on which
 * file of it is playing.
 *
 * ### Vacuity
 *
 * Every assertion is paired with one that the scan found the file at all. A rule that silently
 * matches zero files is the failure mode this project has already paid for more than once.
 */
class BookSpeedAuthorityTest {

  /**
   * The item-grain names. Reaching `media_progress` from Kotlin means naming one of them: the DAO,
   * the entity, or the table itself in a `@Query`.
   *
   * Held as a list rather than one needle so that a failure says *which* door was opened.
   */
  private val itemGrainNames = listOf("MediaProgressDao", "MediaProgressEntity", "media_progress")

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    repeat(8) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile ?: return@repeat
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
  }

  private fun controllerSource(): File =
    File(repoRoot(), "core/media/src/main/kotlin/app/muplay/media/BookSpeedController.kt")

  @Test
  fun `the scan reads the controller's own source`() {
    // The premise. Without it the assertion below passes on a file that was renamed out from under
    // it, which is a gate reporting the absence of a problem it never looked for.
    val source = controllerSource()
    assertThat(source).describedAs("the file this rule is about").exists()
    // Named content, not merely "not empty": a truncated or unrelated file would still exist.
    assertThat(source.readText())
      .contains("class BookSpeedController", "fun applyFor", "BookPlaybackSettings")
  }

  @Test
  fun `the speed applied to the player never comes from the item-grain progress row`() {
    val text = controllerSource().readText()

    assertThat(itemGrainNames.filter { text.contains(it) })
      .describedAs(
        "item-grain names in BookSpeedController.kt -- a book's speed is the book's " +
          "(app.muplay.model.BookSettings), and a second reader is a book with two speeds",
      )
      .isEmpty()
  }

  @Test
  fun `the needles this rule looks for are the ones that really reach that table`() {
    // The other half of the vacuity pair, and the one that is easy to leave out: a rule whose
    // needles match nothing anywhere is green forever. Each name is checked against the file that
    // genuinely does reach `media_progress`, so a rename over there fails *here* rather than
    // quietly disarming the rule above.
    val writer = File(repoRoot(), "core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt")
    assertThat(writer).exists()

    val text = writer.readText()
    assertThat(itemGrainNames.filter { text.contains(it) })
      .describedAs("the needles, checked against the class that really does write that table")
      .containsExactlyElementsOf(itemGrainNames)
  }
}
