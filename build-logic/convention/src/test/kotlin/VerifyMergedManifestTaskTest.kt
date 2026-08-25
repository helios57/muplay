import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The first test in `build-logic`, and it exists because this task's *behaviour* was verified
 * nowhere.
 *
 * `ConventionTest`'s two manifest rules read `AndroidApplicationConventionPlugin`'s declared
 * `forbiddenAttributes`/`requiredDeclarations` lists -- what the task is *asked* to check. Nothing
 * read what it *does*.
 *
 * **Measured, not reasoned about.** With the entire `missing` block deleted from
 * [VerifyMergedManifestTask.verify] *and*
 * `android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"` deleted from
 * `core/media/src/main/AndroidManifest.xml` -- so there was a real, shipped defect to catch --
 * `./gradlew :app:verifyDebugManifest :app:verifyReleaseManifest :app:testDebugUnitTest
 * --rerun-tasks --tests '*ConventionTest*'` was **BUILD SUCCESSFUL**, all seventeen `ConventionTest`
 * rules included. A task that never throws is a task that succeeded. The forbidden half has exactly
 * the same hole. This file is the only thing in the repository that goes red for either.
 *
 * That is this project's recorded "decision verified at a different layer than applied" defect,
 * aimed at the gate whose whole job is to prove a claim about the shipped manifest -- so the fix is
 * a test on the layer that applies it, not a third source scan on the layer that declares it.
 *
 * ### Why `ProjectBuilder` and a real task instance
 *
 * The alternative was a `ConventionTest` assertion that `verify()`'s source text still contains its
 * `filterNot { text.contains(it) }` and its `throw`. That is cheaper and it is the wrong shape: it
 * passes for a `throw` that is unreachable, for a `filterNot` whose result is discarded, and for
 * any rewrite that keeps the words -- and it fails for a correct refactor that changes them. This
 * builds the task Gradle builds, sets the same three inputs the convention plugin sets, and calls
 * the same `@TaskAction`, so what is asserted is that a bad manifest fails the build.
 *
 * Every case below writes a manifest to a temp file rather than reading a real merged one: the
 * point here is the *predicate*, and the complementary half -- that the predicate is pointed at
 * AGP's own merged artifact rather than at a source manifest -- is carried by
 * `SingleArtifact.MERGED_MANIFEST` in the plugin and by the manual red-then-green run recorded in
 * `.superpowers/sdd/2026-08-24-muplay-k02-library-browse/manifest-gate-report.md`.
 */
class VerifyMergedManifestTaskTest {

  /**
   * A merged manifest with everything `AndroidApplicationConventionPlugin` requires and nothing it
   * forbids. Every case below is this text, minus or plus one thing.
   */
  private val compliantManifest = """
    <?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.muplay">
      <uses-permission android:name="android.permission.INTERNET" />
      <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
      <application>
        <service
            android:name="app.muplay.media.MuPlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback" />
      </application>
    </manifest>
  """.trimIndent()

  /** Exactly the list `AndroidApplicationConventionPlugin` passes in, wrappers included. */
  private val required = listOf(
    """android:name="android.permission.INTERNET"""",
    """android:name="android.permission.POST_NOTIFICATIONS"""",
    """android:name="android.permission.FOREGROUND_SERVICE"""",
    """android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"""",
    """android:name="app.muplay.media.MuPlaybackService"""",
    """android:foregroundServiceType="mediaPlayback"""",
  )

  private val forbidden = listOf("usesCleartextTraffic", "networkSecurityConfig")

  @Test
  fun `a compliant manifest passes`(@TempDir dir: File) {
    // The premise for every case below. Without it, a task that threw unconditionally would satisfy
    // all of them, and this suite would be reporting that the gate works when it only ever says no.
    assertThatCode { verify(dir, compliantManifest, required, forbidden) }.doesNotThrowAnyException()
  }

  @Test
  fun `a missing declaration fails the build, and the failure names it`() {
    // The half a deletion leaves green everywhere else. One line removed from the manifest is
    // exactly what "the permission is declared in :core:media" stops being true by.
    required.forEach { declaration ->
      val dir = createTempDir()
      val without = compliantManifest.lines()
        .filterNot { it.contains(declaration) }
        .joinToString("\n")
      // The premise, per declaration: if removing it changed nothing, the assertion below would be
      // about a manifest that still contains it.
      assertThat(without).describedAs(declaration).doesNotContain(declaration)

      assertThatThrownBy { verify(dir, without, required, forbidden) }
        .describedAs("removing %s must fail the build", declaration)
        .isInstanceOf(GradleException::class.java)
        // Named, not merely thrown: a failure that does not say which declaration went missing
        // sends the reader to the wrong file.
        .hasMessageContaining(declaration)
    }
  }

  @Test
  fun `a forbidden attribute fails the build, and the failure names it`() {
    forbidden.forEach { attribute ->
      val dir = createTempDir()
      val polluted = compliantManifest.replace(
        "<application>",
        """<application android:$attribute="true">""",
      )
      assertThat(polluted).describedAs(attribute).contains(attribute)

      assertThatThrownBy { verify(dir, polluted, required, forbidden) }
        .describedAs("%s must never reach a release merged manifest", attribute)
        .isInstanceOf(GradleException::class.java)
        .hasMessageContaining(attribute)
    }
  }

  @Test
  fun `a required declaration is matched with its wrapper, not by bare name`(@TempDir dir: File) {
    // The measurement `VerifyMergedManifestTask.requiredDeclarations` records, made executable:
    // `android.permission.FOREGROUND_SERVICE` is a *prefix* of
    // `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so a bare-name list reports the
    // shorter permission present in a manifest that declares only the longer one. With the
    // wrappers, the closing quote is what makes the prefix stop matching.
    val onlyTheTypedPermission = compliantManifest.lines()
      .filterNot { it.contains("""android:name="android.permission.FOREGROUND_SERVICE"""") }
      .joinToString("\n")
    assertThat(onlyTheTypedPermission).contains("FOREGROUND_SERVICE_MEDIA_PLAYBACK")

    assertThatThrownBy { verify(dir, onlyTheTypedPermission, required, forbidden) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("""android:name="android.permission.FOREGROUND_SERVICE"""")

    // ...and the bare-name spelling really is the weaker one, asserted rather than asserted about:
    // the same manifest passes a list written without the wrappers.
    assertThatCode {
      verify(dir, onlyTheTypedPermission, listOf("android.permission.FOREGROUND_SERVICE"), forbidden)
    }.doesNotThrowAnyException()
  }

  @Test
  fun `the debug variant's empty forbidden list forbids nothing`(@TempDir dir: File) {
    // The plugin passes `emptyList()` for debug, because `app/src/debug/AndroidManifest.xml`
    // legitimately carries `usesCleartextTraffic` for the Tier 2 journey's plain-HTTP container. An
    // empty list has to mean "nothing is forbidden" and not "everything is" -- and a `filter` over
    // an empty list can only ever be empty, which is the shape that makes an assertion vacuous
    // rather than permissive. Pinned so the release arm cannot be made to share it.
    val cleartext = compliantManifest.replace(
      "<application>",
      """<application android:usesCleartextTraffic="true">""",
    )
    assertThatCode { verify(dir, cleartext, required, forbiddenAttributes = emptyList()) }
      .doesNotThrowAnyException()
  }

  private var tempCounter = 0

  private fun createTempDir(): File =
    File(System.getProperty("java.io.tmpdir"), "verify-merged-manifest-${tempCounter++}-${System.nanoTime()}")
      .also { it.mkdirs(); it.deleteOnExit() }

  /**
   * Runs the real task's real `@TaskAction` over [manifest].
   *
   * `ProjectBuilder` rather than a hand-built instance: `VerifyMergedManifestTask` is abstract and
   * its `RegularFileProperty`/`ListProperty` inputs are Gradle-generated, so the only way to get an
   * instance is to let Gradle make one -- which is also the only way to be sure this is the class
   * the build runs.
   */
  private fun verify(
    dir: File,
    manifest: String,
    requiredDeclarations: List<String>,
    forbiddenAttributes: List<String>,
  ) {
    val file = File(dir, "AndroidManifest.xml").apply { parentFile.mkdirs(); writeText(manifest) }
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks.register("verifyTestManifest", VerifyMergedManifestTask::class.java).get()
    task.mergedManifest.set(file)
    task.requiredDeclarations.set(requiredDeclarations)
    task.forbiddenAttributes.set(forbiddenAttributes)
    task.verify()
  }
}
