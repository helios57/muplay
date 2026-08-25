package app.muplay.media

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * There is exactly one place in this module an `ExoPlayer` is built, and it is [MuPlayerFactory].
 *
 * ### Why a source scan, and why it is not cargo cult
 *
 * The retry policy this module exists for attaches to the `MediaSource.Factory`, and
 * `ExoPlayer.Builder` has no setter for it at all -- so a second construction site does not fail to
 * compile, does not fail a test of the policy, and does not fail at runtime. It produces a player
 * that quietly keeps Media3's `DefaultLoadErrorHandlingPolicy` (three retries inside five seconds)
 * while every other test in this project stays green. That is a *structural* property -- "no second
 * one exists" -- and no runtime test can express it: a test can only observe the players it was
 * handed.
 *
 * The device tier proves the complementary half, that the one construction site really does attach
 * the policy: `MuPlayDataSourceFactoryTest.aRefusalBudgetThatRunsOutSurfacesAsAPlayerError` builds
 * its player through [MuPlayerFactory] and counts `StreamRetryPolicy.MAX_RETRIES + 1` = 6 requests
 * on the wire, where Media3's own default budget would produce 4. Neither half is sufficient alone.
 *
 * ### Why it scans the test sources too
 *
 * That is the half a review asked for, in these words: *"have the instrumented test call that
 * rather than hand-building an `ExoPlayer` -- then the test's wiring is the production wiring."* A
 * test that builds its own player is testing a copy of the production arrangement, and the copy is
 * exactly the thing that drifts. This scan is what stops one coming back.
 *
 * ### Vacuity
 *
 * Every assertion here is paired with one that the scan found anything at all. A rule that silently
 * matches zero files is the failure mode this project has already paid for more than once -- see
 * `ConventionTest.the scan finds build files at all`, which exists for the same reason.
 */
class PlayerConstructionTest {

  /** The text that builds an `ExoPlayer`. Both the Kotlin and the Java call spellings start here. */
  private val construction = "ExoPlayer.Builder("

  /**
   * This file's own name. A prose mention of the needle in any *other* file -- a comment that
   * spells the call with its opening parenthesis -- fails this rule, and that is the safe
   * direction: a false positive is one comment to reword, a false negative is a second player.
   */
  private val THIS_FILE = "PlayerConstructionTest.kt"

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    repeat(8) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile ?: return@repeat
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
  }

  private fun sources(): List<File> {
    val src = File(repoRoot(), "core/media/src")
    check(src.isDirectory) { "${src.absolutePath} is not a directory; this scan would match nothing" }
    return src.walkTopDown().filter { it.extension == "kt" }.toList()
  }

  /**
   * Every source but this one.
   *
   * This file holds the needle as a literal, so it matches its own scan. Excluding it by name
   * rather than by hiding the literal behind a concatenation: a rename makes the `check` below
   * fail loudly, whereas a cleverly-spelled needle would just silently stop matching one day.
   */
  private fun sourcesUnderTest(): List<File> {
    val all = sources()
    val (scanner, rest) = all.partition { it.name == THIS_FILE }
    check(scanner.size == 1) {
      "expected exactly one $THIS_FILE under core/media/src, found ${scanner.size} -- if this file " +
        "was renamed, rename $THIS_FILE with it, or this scan starts matching itself"
    }
    return rest
  }

  @Test
  fun `the scan reads this module's own sources`() {
    val scanned = sources()
    assertThat(scanned).describedAs("kotlin sources under core/media/src").isNotEmpty()
    // Named files, not merely "not empty": a scan that found only `src/main` would pass the check
    // above and would never look at the test sources this rule exists to cover.
    assertThat(scanned.map { it.name })
      .contains("MuPlayerFactory.kt", "MuPlayDataSourceFactoryTest.kt", THIS_FILE)
  }

  @Test
  fun `an ExoPlayer is constructed in exactly one place`() {
    val builders = sourcesUnderTest().filter { it.readText().contains(construction) }

    // The premise: if nothing matched, the assertion below would pass on an empty set and this
    // whole rule would be decoration.
    assertThat(builders).describedAs("files containing `%s`", construction).isNotEmpty()
    assertThat(builders.map { it.name }).containsExactly("MuPlayerFactory.kt")
  }
}
