import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The one case that can be arranged without a key: an **unsigned** bundle must be refused.
 *
 * That is not the trivial half. It is the exact artifact this build produces today when no signing
 * material is configured -- measured on this branch, `bundleRelease` succeeded and the resulting
 * 6,948,199-byte `.aab` carried no `META-INF` entry at all, with nothing in the build saying so --
 * and it is what a release job whose repository secrets never arrived would upload.
 *
 * The *positive* half cannot be built here: producing a signed jar needs a private key and a
 * self-signed certificate, and the only ways to make one inside a unit test are to add a crypto
 * library this project does not want or to commit key material this project forbids. It is
 * verified instead by running the real task against a real signed bundle, with a throwaway keystore
 * generated outside the repository -- see Task 7/9's report for that transcript and for the
 * certificate fingerprint the task printed.
 */
class VerifyReleaseSignedTaskTest {

  @Test
  fun `a bundle with no signature at all is refused, and the failure says which secrets are missing`(
    @TempDir dir: File,
  ) {
    assertThatThrownBy { verify(dir, signatureEntries = emptyList()) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("it is UNSIGNED")
      .hasMessageContaining("MUPLAY_KEYSTORE_PASSWORD")
  }

  @Test
  fun `META-INF entries that are not a signature do not count as one`(@TempDir dir: File) {
    // The defeat this check has to survive: `META-INF/` is an ordinary directory in a zip, and a
    // rule that only asks whether one exists passes for an artifact carrying a stray
    // `MANIFEST.MF` with no signature block behind it. `JarFile(file, verify = true)` is what makes
    // the difference -- the entry's `codeSigners` stays null, so the code is not covered.
    assertThatThrownBy {
      verify(dir, signatureEntries = listOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n\n"))
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("does not cover")
      .hasMessageContaining("base/dex/classes.dex")
  }

  @Test
  fun `a bundle missing the entry the signature is checked over is refused`(@TempDir dir: File) {
    // Vacuity: `getJarEntry` returning null must not be read as "no signer", which would make the
    // check pass for an artifact whose code is not there at all.
    assertThatThrownBy {
      verify(dir, signatureEntries = emptyList(), code = null)
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("it is UNSIGNED")
  }

  private fun verify(
    dir: File,
    signatureEntries: List<Pair<String, String>>,
    code: String? = "not really a dex",
  ) {
    val aab = File(dir, "app-release.aab")
    ZipOutputStream(aab.outputStream()).use { zip ->
      fun entry(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
      signatureEntries.forEach { (name, text) -> entry(name, text) }
      if (code != null) entry("base/dex/classes.dex", code)
      entry(VerifyReleaseArtifactTask.BUNDLE_MANIFEST_ENTRY, "manifest")
    }
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks.register("verifyTestSigning", VerifyReleaseSignedTask::class.java).get()
    task.bundle.set(aab)
    task.signedEntries.set(listOf("base/dex/classes.dex", VerifyReleaseArtifactTask.BUNDLE_MANIFEST_ENTRY))
    task.report.set(File(dir, "report.txt"))
    task.verify()
  }
}
