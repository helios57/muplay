import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The behaviour of the Android Auto descriptor gate, on the layer that applies it.
 *
 * Same argument as [VerifyMergedManifestTaskTest]'s header, and the same defect class. `:app`'s
 * `ConventionTest` reads what this task is *asked* to check — that `AUTOMOTIVE_DECLARATIONS` still
 * names the car entries, that `:app` still opts in, that the descriptor file still exists and says
 * `media`. Nothing there reads what the task *does*, so deleting the body of [verify] would leave
 * every rule in that class green, `verifyAutomotiveDescriptor` UP-TO-DATE, and `check` successful,
 * while a descriptor that says nothing shipped and the app quietly stopped appearing in a car.
 *
 * `ProjectBuilder` and a real task instance, for the reason recorded in the other file: an
 * assertion that the source of `verify()` still contains its `throw` passes for a `throw` that is
 * unreachable and fails for a correct refactor that renames things.
 *
 * What is *not* here, deliberately: any claim that a passing descriptor makes the app appear in a
 * car. That is a runtime property of a host this repository cannot start. This suite proves the
 * predicate; `docs/superpowers/manual-checks/` is where the DHU checklist belongs.
 */
class VerifyAutomotiveDescriptorTaskTest {

  /** Exactly the file `:app` ships, and the list the convention plugin passes in. */
  private val compliantDescriptor = """
    <?xml version="1.0" encoding="utf-8"?>
    <automotiveApp>
      <uses name="media" />
    </automotiveApp>
  """.trimIndent()

  private val required = listOf("media")

  @Test
  fun `a compliant descriptor passes`(@TempDir dir: File) {
    // The premise for every case below. Without it, a task that threw unconditionally would satisfy
    // all of them, and this suite would report that the gate works when it only ever says no.
    assertThatCode { verify(dir, compliantDescriptor, required) }.doesNotThrowAnyException()
  }

  @Test
  fun `a descriptor with no uses element fails, and the failure names what is missing`(
    @TempDir dir: File,
  ) {
    // The self-closing empty descriptor: a file that exists, parses, is referenced by the merged
    // manifest, and tells Android Auto nothing. `verifyDebugManifest` cannot see this at all --
    // the manifest carries `android:resource="@xml/automotive_app_desc"`, a reference -- which is
    // the entire reason this task exists as a second gate rather than a fourth property on that one.
    assertThatThrownBy { verify(dir, "<automotiveApp />", required) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("""<uses name="media"/>""")
  }

  @Test
  fun `a descriptor with the wrong root element fails, and the failure names the right one`(
    @TempDir dir: File,
  ) {
    assertThatThrownBy { verify(dir, "<app>\n  <uses name=\"media\" />\n</app>", required) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("<automotiveApp>")
  }

  @Test
  fun `a required use is matched with its closing quote, not by prefix`(@TempDir dir: File) {
    // The same measurement `VerifyMergedManifestTask.requiredDeclarations` records, one file over:
    // a presence check that over-matches passes wrongly. `media` is a prefix of `mediaTemplate`, so
    // without the closing quote in the pattern a descriptor declaring only the longer value would
    // report the shorter one present.
    val longerValue = compliantDescriptor.replace("""name="media"""", """name="mediaTemplate"""")
    assertThat(longerValue).contains("mediaTemplate").doesNotContain("""name="media"""")

    assertThatThrownBy { verify(dir, longerValue, required) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("media")
  }

  @Test
  fun `an element that merely starts with uses is not a uses element`(@TempDir dir: File) {
    // `<uses\s+` and not `<uses`: the pattern has to bind to the element name, or a future
    // `<usesFeature name="media" />` -- or, more likely, prose in a comment -- answers for a
    // declaration nobody wrote. This repository has twice paid for a `contains` that read prose.
    val notAUsesElement = """
      <automotiveApp>
        <!-- <uses name="media" /> is what this file needs and does not have. -->
        <usesFeature name="media" />
      </automotiveApp>
    """.trimIndent()

    assertThatThrownBy { verify(dir, notAUsesElement, required) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("media")
  }

  /**
   * Runs the real task's real `@TaskAction` over [descriptorText].
   *
   * `ProjectBuilder` rather than a hand-built instance: the task is abstract and its
   * `RegularFileProperty`/`ListProperty` inputs are Gradle-generated, so the only way to get an
   * instance is to let Gradle make one — which is also the only way to be sure this is the class
   * the build runs.
   */
  private fun verify(dir: File, descriptorText: String, requiredUses: List<String>) {
    val file = File(dir, "automotive_app_desc.xml")
      .apply { parentFile.mkdirs(); writeText(descriptorText) }
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks
      .register("verifyTestDescriptor", VerifyAutomotiveDescriptorTask::class.java)
      .get()
    task.descriptor.set(file)
    task.requiredUses.set(requiredUses)
    task.verify()
  }
}
