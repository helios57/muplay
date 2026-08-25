package app.muplay.media

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * There is exactly one place in this module's **production** code that an `ExoPlayer` is built,
 * and it is [MuPlayerFactory]. Test sources are scanned too, against an enumerated list -- see
 * `only the named test suites build a player of their own` for what is on it and why.
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

  /**
   * **The production half, and it is absolute.** Nothing under `src/main` may build a player except
   * [MuPlayerFactory]. No carve-out belongs here, ever: a second production construction site is
   * the exact defect this file exists for -- a player that silently keeps Media3's default retry
   * budget while every test of the policy stays green.
   */
  @Test
  fun `production code constructs an ExoPlayer in exactly one place`() {
    val builders = sourcesUnderTest()
      .filter { it.path.contains("/src/main/") }
      .filter { it.readText().contains(construction) }

    // The premise: if nothing matched, the assertion below would pass on an empty set and this
    // whole rule would be decoration.
    assertThat(builders).describedAs("src/main files containing `%s`", construction).isNotEmpty()
    assertThat(builders.map { it.name }).containsExactly("MuPlayerFactory.kt")
  }

  /**
   * The test half: an **enumerated** list, not a ban and not a free pass.
   *
   * The rule a review asked for is *"have the instrumented test call [MuPlayerFactory] rather than
   * hand-building an `ExoPlayer` -- then the test's wiring is the production wiring"*, and that is
   * right for every suite whose subject is the player. `MuPlayDataSourceFactoryTest` is exactly
   * that suite and it goes through the factory; `MuPlayerFactoryTest` tests the factory itself.
   *
   * There is no longer any exception, and that is a change worth recording rather than a tidy-up.
   * `MediaCacheTest` was the one carve-out: its subject is the cache key, and it hand-built a
   * player so that a missing custom cache key surfaced immediately instead of waiting out
   * `StreamRetryPolicy.MAX_RETRIES` = 5 escalating retries against that harness's 30s ceiling.
   * Task 3's own follow-up routed it through the factory, and `MuPlayerFactoryTest` was superseded
   * by this file. So the list emptied out on its own, and the honest rule is now the absolute one.
   *
   * The premise moved with it. "Some test file matched" was the anti-vacuity check while a
   * carve-out existed; now that nothing may match, the premise has to be that the **scan** still
   * reads test sources -- otherwise a broken scan would report an empty set and pass.
   */
  @Test
  fun `no test suite builds a player of its own`() {
    val testSources = sourcesUnderTest().filter { !it.path.contains("/src/main/") }

    // The premise, and it has to be about the SCAN rather than about the matches: the assertion
    // below passes on an empty set, so without this it would be decoration the day the scan broke.
    // It cannot be "some file matched" any more -- see the KDoc: nothing matches now, deliberately.
    assertThat(testSources).describedAs("kotlin test sources under core/media/src").isNotEmpty()
    assertThat(testSources.map { it.name }).contains("MuPlayDataSourceFactoryTest.kt")

    val builders = testSources.filter { it.readText().contains(construction) }
    assertThat(builders.map { it.name })
      .describedAs("test files containing `%s`", construction)
      .isEmpty()
  }
}
