import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails unless the `.aab` carries a JAR signature that actually covers the code inside it.
 *
 * This is the gate the release *pipeline* needs and `releaseCheck` deliberately does not run. Task
 * 3 built signing so that a **half**-configured environment is a hard error — `MUPLAY_KEYSTORE_PATH`
 * set with any of the other three missing throws immediately — and left the fully-*unset* case
 * producing an unsigned artifact on purpose, so that a machine holding no key can still use
 * `assembleRelease`/`bundleRelease` as a compile gate. Both halves of that are right, and together
 * they leave one hole: a release job whose secret never arrived takes the second path, builds
 * happily, and uploads nothing usable. Measured on this branch, with no key configured at all:
 * `bundleRelease` succeeded, `signReleaseBundle` ran, and the resulting 6,948,199-byte bundle
 * contained **no `META-INF` entry whatsoever**. Nothing in the build said so.
 *
 * ### What is proven, and what is not
 *
 * `JarFile(file, verify = true)` does the real thing: it parses the `.SF` under `META-INF`, checks the
 * signature block over it, and as each entry is read it verifies that entry's digest against the
 * manifest. So reading an entry to the end and finding a non-null `codeSigners` is a cryptographic
 * statement about *that entry*, not a statement that some files exist under `META-INF/`. The
 * entries checked are named by [signedEntries] rather than being "any entry", because the
 * dangerous artifact is one where the signature covers metadata and not the code.
 *
 * What this cannot prove is that the signature is by the *right* key — that answer lives in the
 * Play Console, not in the repository, and pinning a certificate hash here would either duplicate a
 * secret or invite somebody to commit one. What it does instead is print the SHA-256 of the signing
 * certificate, which is public information and is exactly the string Play shows for an app's upload
 * key, so a human comparing them once is a cheap and complete check.
 */
abstract class VerifyReleaseSignedTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val bundle: RegularFileProperty

  /** Entries whose bytes must be covered by the signature. Code first. */
  @get:Input
  abstract val signedEntries: ListProperty<String>

  @get:OutputFile
  abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    val aab: File = bundle.get().asFile
    val lines = mutableListOf<String>()
    JarFile(aab, true).use { jar ->
      val names = jar.entries().asSequence().map { it.name }.toList()
      val signatureFiles = names.filter { it.startsWith("META-INF/") }
      if (signatureFiles.isEmpty()) {
        throw GradleException(unsignedMessage(aab, names.size))
      }

      // Every entry has to be read to the end before `codeSigners` is populated; JarFile verifies
      // the digest as it goes, so a tampered entry throws SecurityException from here rather than
      // returning a wrong answer.
      jar.entries().asSequence().forEach { entry -> jar.getInputStream(entry).use { it.readBytes() } }

      val required = signedEntries.get()
      val unsigned = required.filter { name ->
        val entry = jar.getJarEntry(name)
          ?: throw GradleException("$aab has no entry '$name' to check the signature over.")
        entry.codeSigners.isNullOrEmpty()
      }
      if (unsigned.isNotEmpty()) {
        throw GradleException(
          "$aab carries ${signatureFiles.size} META-INF entry/entries but its signature does not " +
            "cover ${unsigned.joinToString()}. A bundle whose code is outside its own signature " +
            "is not a signed bundle.",
        )
      }

      val certificates = required.flatMap { name ->
        jar.getJarEntry(name).codeSigners.orEmpty()
          .flatMap { it.signerCertPath.certificates }
          .filterIsInstance<X509Certificate>()
      }.distinct()
      lines += "SIGNED: ${required.size} entry/entries covered, ${signatureFiles.size} META-INF entries"
      certificates.forEach {
        lines += "  certificate: ${it.subjectX500Principal.name}"
        lines += "  SHA-256: ${sha256(it.encoded)}"
        lines += "  valid: ${it.notBefore} .. ${it.notAfter}"
      }
    }
    report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
    lines.forEach { logger.lifecycle("RELEASE SIGNING: $it") }
  }

  private fun unsignedMessage(aab: File, entryCount: Int) =
    "$aab has no META-INF entry, so it is UNSIGNED and Play will refuse it ($entryCount entries " +
      "in total). Signing material reaches this build through four environment variables or a " +
      "git-ignored keystore.properties -- see releaseSigningConfig in build-logic. If this is CI, " +
      "the repository secrets behind $KEYSTORE_PATH_ENV, $KEYSTORE_PASSWORD_ENV, $KEY_ALIAS_ENV " +
      "and $KEY_PASSWORD_ENV are what did not arrive; note that a *partly* set environment is " +
      "already a hard error, so reaching this message means none of them was set at all."

  private fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}
