import java.io.File
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `verifyReleaseTag`'s three outcomes, all of which are invisible from a normal build.
 *
 * This task never runs on a developer's machine and never runs in `check` -- it runs once per
 * release, inside a pipeline, on the one day when being wrong is expensive. So the only thing that
 * can watch it fail is a test, and the case worth having a test for is the *third* one: that an
 * unset environment variable is a failure rather than a quiet pass. That is the shape this
 * repository has recorded four times as a check that cannot tell "no" from "I cannot tell", and it
 * is the one an environment-driven gate falls into by default.
 */
class VerifyReleaseTagTaskTest {

  @Test
  fun `a tag that names the versionName passes`(@TempDir dir: File) {
    // The premise: a task that threw unconditionally would satisfy both cases below.
    assertThatCode { verify(dir, tag = "v0.2.0", versionName = "0.2.0") }.doesNotThrowAnyException()
  }

  @Test
  fun `a tag that names a different version fails, and the failure names both`(@TempDir dir: File) {
    // `v0.3.0` pushed at a commit that still says 0.2.0 builds a perfectly valid bundle labelled
    // wrongly in the GitHub release, in every bug report, and in the Play Console's release notes.
    assertThatThrownBy { verify(dir, tag = "v0.3.0", versionName = "0.2.0") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("the tag being released is 'v0.3.0'")
      .hasMessageContaining("versionName '0.2.0'")
  }

  @Test
  fun `an unset tag is a failure, not a skip`(@TempDir dir: File) {
    assertThatThrownBy { verify(dir, tag = "", versionName = "0.2.0") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("MUPLAY_RELEASE_TAG is not set")
  }

  private fun verify(dir: File, tag: String, versionName: String) {
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks.register("verifyTestTag", VerifyReleaseTagTask::class.java).get()
    task.tag.set(tag)
    task.versionName.set(versionName)
    task.verify()
  }
}
