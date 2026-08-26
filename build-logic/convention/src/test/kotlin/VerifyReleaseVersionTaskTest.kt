import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Drives the real `verifyReleaseVersion` task over a real ledger file, for the reason
 * [VerifyMergedManifestTaskTest] states at length: a gate whose only exercise is "the build was
 * green" is a gate nobody has watched fail, and "the build was green" is exactly what a version
 * gate produces every single day it has nothing to complain about.
 *
 * The specific hazard here is that this task's happy path is the *normal* path. `check` runs it on
 * every commit and it passes on every one of them, so an accidental `return` at the top of
 * `verify()`, an inverted comparison, or a ledger parser that silently drops the line naming the
 * code you are about to reuse would all be invisible until the one build a year that matters.
 */
class VerifyReleaseVersionTaskTest {

  /**
   * A ledger with real content, because a rule about "already spent" is vacuous over an empty one.
   * That is not hypothetical for this project: `app/release-history.tsv` has exactly one line
   * today, and this suite would have been green against a task that did nothing at all if it had
   * used a ledger with none.
   */
  private val ledger = """
    # a comment, skipped
    1${'\t'}0.1.0${'\t'}2026-08-25${'\t'}pre-release development builds

    200${'\t'}0.2.0${'\t'}2026-08-26${'\t'}the first release-engineered build
  """.trimIndent()

  @Test
  fun `a legal next release passes`(@TempDir dir: File) {
    // The premise for every case below. A task that threw unconditionally would satisfy all of
    // them, and this suite would report a working gate that only ever says no.
    assertThatCode { verify(dir, versionCode = 201, versionName = "0.2.1", history = ledger) }
      .doesNotThrowAnyException()
  }

  @Test
  fun `a version code already in the ledger fails, and the failure names it`(@TempDir dir: File) {
    // The whole point of the file: Play refuses a repeated version code at upload, after the
    // artifact exists and after the tag has usually been pushed. This refuses it before
    // `bundleRelease` produces anything.
    assertThatThrownBy { verify(dir, versionCode = 200, versionName = "0.2.0", history = ledger) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("version code 200 has already been spent")
  }

  @Test
  fun `a version name already in the ledger fails even with a fresh code`(@TempDir dir: File) {
    // The subtler half. 0.2.0 at code 300 would upload happily and then make every bug report
    // ambiguous, because two different binaries would answer "0.2.0" when asked their version.
    assertThatThrownBy { verify(dir, versionCode = 300, versionName = "0.2.0", history = ledger) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("""version name "0.2.0" has already been spent""")
  }

  @Test
  fun `a code that disagrees with its name fails, and the failure shows the derivation`(@TempDir dir: File) {
    // The mistake that ships an "update" users' devices see as older than what they have.
    assertThatThrownBy { verify(dir, versionCode = 210, versionName = "0.2.1", history = ledger) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("does not match versionName")
      .hasMessageContaining("derives 201")
  }

  @Test
  fun `a code below the highest already spent fails`(@TempDir dir: File) {
    // Not covered by "already spent": 150 appears nowhere in the ledger, and 0.1.50 appears
    // nowhere either, so both of those rules pass it. Play would accept the upload and then refuse
    // to serve it as an update to anyone already on 200.
    assertThatThrownBy { verify(dir, versionCode = 150, versionName = "0.1.50", history = ledger) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("is not above the highest already spent (200)")

    // ...and the two rules it slips past really do slip past it, asserted rather than claimed.
    val problems = VerifyReleaseVersionTask.problemsWith(
      versionCode = 150,
      versionName = "0.1.50",
      spent = VerifyReleaseVersionTask.parseHistory(ledger.lines(), "test"),
    )
    assertThat(problems).hasSize(1)
  }

  @Test
  fun `a duplicated ledger entry fails rather than being deduplicated`(@TempDir dir: File) {
    // A ledger that silently tolerates a duplicate is a ledger whose "already spent" answer cannot
    // be trusted, which is worse than no ledger at all.
    val duplicated = ledger + "\n200\t0.2.0-again\t2026-08-26\tpasted twice"
    assertThatThrownBy { verify(dir, versionCode = 201, versionName = "0.2.1", history = duplicated) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("version code 200 is listed more than once")
  }

  @Test
  fun `a malformed ledger line fails rather than being skipped`(@TempDir dir: File) {
    // Skipping it is the dangerous behaviour: the skipped line is exactly the one that might have
    // named the code being reused, and the build would then be green *because* the ledger is
    // broken.
    val malformed = ledger + "\nnot-a-version-code\t0.9.0\t2026-08-26\ttypo"
    assertThatThrownBy { verify(dir, versionCode = 201, versionName = "0.2.1", history = malformed) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("first field is not a version code")

    val truncated = ledger + "\n900"
    assertThatThrownBy { verify(dir, versionCode = 201, versionName = "0.2.1", history = truncated) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("expected at least two tab-separated fields")
  }

  @Test
  fun `the scheme is monotonic in the version name, and rejects names it cannot encode`() {
    // Monotonic: a higher name is always a higher code, which is the property that makes the
    // "must move forward" rule mean the same thing to a human reading the name and to Play
    // reading the code.
    val names = listOf("0.0.1", "0.1.0", "0.2.0", "0.2.1", "0.99.99", "1.0.0", "1.12.3", "10.0.0")
    val codes = names.map { VerifyReleaseVersionTask.versionCodeFor(it) }
    assertThat(codes).containsExactly(1, 100, 200, 201, 9_999, 10_000, 11_203, 100_000)
    assertThat(codes).isSorted()

    // ...and it says so instead of silently encoding a name it cannot represent. `0.100.0` would
    // otherwise collide with `1.0.0` at 10_000, which is the one arithmetic accident this scheme
    // can have.
    assertThat(VerifyReleaseVersionTask.versionCodeFor("0.100.0")).isNull()
    assertThat(VerifyReleaseVersionTask.versionCodeFor("1.0.100")).isNull()
    assertThat(VerifyReleaseVersionTask.versionCodeFor("1.0")).isNull()
    assertThat(VerifyReleaseVersionTask.versionCodeFor("1.0.0-rc1")).isNull()
    assertThat(VerifyReleaseVersionTask.versionCodeFor("v1.0.0")).isNull()
  }

  /**
   * Runs the real task's real `@TaskAction` against a ledger written to a temp file.
   *
   * `ProjectBuilder`, not a hand-built instance, for the same reason [VerifyMergedManifestTaskTest]
   * gives: the task is abstract and its inputs are Gradle-generated properties, so letting Gradle
   * build it is both the only way to get one and the only way to be sure this is the class the
   * build actually runs.
   */
  private fun verify(dir: File, versionCode: Int, versionName: String, history: String) {
    val file = File(dir, "release-history.tsv").apply { parentFile.mkdirs(); writeText(history) }
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks.register("verifyTestVersion", VerifyReleaseVersionTask::class.java).get()
    task.versionCode.set(versionCode)
    task.versionName.set(versionName)
    task.history.set(file)
    task.verify()
  }
}
