package app.muplay

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the build itself. Every rule here exists because it silently rotted in a previous
 * incarnation of this project: build files drifting apart, a scan matching zero files and passing
 * vacuously, or a banned dependency arriving transitively.
 */
class ConventionTest {

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    repeat(8) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile ?: return@repeat
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
  }

  private fun moduleBuildFiles(): List<File> =
    repoRoot().walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" }
      .filter { it.name == "build.gradle.kts" }
      .filter { it.parentFile.name != "convention" }
      .toList()

  /**
   * Every file under `build-logic` that can declare a dependency or apply a plugin: its own
   * `build.gradle.kts` (deliberately exempt from [moduleBuildFiles] above — that is exactly where
   * real `android {}`/`kotlin {}` configuration belongs) and every convention-plugin `.kt` source.
   * A banned dependency or `kapt` usage introduced through this layer reaches every module that
   * applies the affected convention plugin, which makes it the highest-leverage place a scan can
   * miss, not a place to exempt.
   */
  private fun buildLogicFiles(): List<File> =
    File(repoRoot(), "build-logic").walkTopDown()
      .onEnter { it.name != "build" }
      .filter { it.extension == "kts" || it.extension == "kt" }
      .toList()

  @Test
  fun `the scan finds build files at all`() {
    // A rule that silently scans nothing is the failure mode every rule here guards against.
    assertThat(moduleBuildFiles()).isNotEmpty()
  }

  @Test
  fun `no module configures android or kotlin blocks directly`() {
    // Module build files declare plugins and dependencies. Everything else belongs in a
    // convention plugin, or the ten modules drift apart one edit at a time. The one narrow
    // exception is `:app`'s own `android { }` block: `namespace`, and `applicationId`/
    // `versionCode`/`versionName` nested inside `defaultConfig { }`, none of which can live in a
    // shared convention plugin (versionCode/versionName are release identity, bumped every
    // release — putting them in build-logic would mean a build-logic code change per release;
    // namespace/applicationId are genuinely per-module, and a future second application module,
    // e.g. the roadmap's Wear OS app, needs its own). `androidBlockOffends` allow-lists exactly
    // those four properties and fails on anything else placed inside `android { }` — compileSdk,
    // minSdk, compileOptions, buildFeatures, ... all still belong in a convention plugin.
    val offenders = moduleBuildFiles()
      .filter { it.parentFile.name != "build-logic" }
      .filter { f ->
        val text = f.readText()
        val bannedBlock = Regex("""^\s*(compileOptions|kotlinOptions|compilerOptions)\s*\{""", RegexOption.MULTILINE)
          .containsMatchIn(text)
        bannedBlock || androidBlockOffends(text)
      }
    assertThat(offenders).describedAs("configure these in a convention plugin").isEmpty()
  }

  /**
   * True if [text] contains an `android { }` block whose content is anything other than the
   * allow-listed per-module identity values (see the test above for why exactly these four).
   */
  private fun androidBlockOffends(text: String): Boolean {
    val androidBody = bracedBlockBody(text, "android") ?: return false
    val defaultConfigBody = bracedBlockBody(androidBody, "defaultConfig")
    val outerBody = if (defaultConfigBody != null) withoutBracedBlock(androidBody, "defaultConfig") else androidBody

    val outerAllowed = Regex("""^namespace\s*=\s*"[^"]*"$""")
    val innerAllowed = Regex("""^(applicationId|versionName)\s*=\s*"[^"]*"$|^versionCode\s*=\s*\d+$""")

    val outerOffenders = significantLines(outerBody).filterNot(outerAllowed::matches)
    val innerOffenders = significantLines(defaultConfigBody ?: "").filterNot(innerAllowed::matches)
    return outerOffenders.isNotEmpty() || innerOffenders.isNotEmpty()
  }

  private fun significantLines(text: String): List<String> =
    text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }.toList()

  /** The interior of the first `name { ... }` block in [text] (braces excluded), or null if absent. */
  private fun bracedBlockBody(text: String, name: String): String? {
    val open = Regex("""(^|\s)$name\s*\{""").find(text) ?: return null
    val braceIndex = open.range.last
    var depth = 1
    var i = braceIndex + 1
    while (i < text.length && depth > 0) {
      when (text[i]) {
        '{' -> depth++
        '}' -> depth--
      }
      i++
    }
    return text.substring(braceIndex + 1, i - 1)
  }

  /** [text] with the first `name { ... }` block (braces included) removed. */
  private fun withoutBracedBlock(text: String, name: String): String {
    val open = Regex("""(^|\s)$name\s*\{""").find(text) ?: return text
    val declStart = open.range.first
    val braceIndex = open.range.last
    var depth = 1
    var i = braceIndex + 1
    while (i < text.length && depth > 0) {
      when (text[i]) {
        '{' -> depth++
        '}' -> depth--
      }
      i++
    }
    return text.removeRange(declStart, i)
  }

  @Test
  fun `no mock framework is declared in any build file or convention plugin`() {
    // A textual scan of *declared* dependencies — the catalogue, every module build file, and
    // every build-logic source — not a resolved-classpath check, so on its own it cannot catch a
    // mock framework arriving transitively through some other dependency. Verified empirically
    // instead, once, for this catalogue's actual resolved test classpaths:
    // `./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath` and
    // `:core:model:dependencies --configuration testRuntimeClasspath` both resolve to
    // junit-jupiter/junit-platform/opentest4j/kotlin-stdlib only. A real per-build
    // resolved-classpath guard (a Gradle task that resolves each module's test classpath and
    // inspects the resolved artifacts, wired into `check`) is a reasonable Task 7 addition once
    // there are enough modules and dependencies for the transitive risk to be more than
    // theoretical — building that now, against a four-module graph with no real transitive
    // candidates, would be exercising code paths nothing here can actually prove correct.
    val banned = listOf("mockito", "mockk", "easymock", "powermock")
    val catalogue = File(repoRoot(), "gradle/libs.versions.toml").readText().lowercase()
    banned.forEach { assertThat(catalogue).doesNotContain(it) }
    (moduleBuildFiles() + buildLogicFiles()).forEach { f ->
      val text = f.readText().lowercase()
      banned.forEach { assertThat(text).describedAs(f.path).doesNotContain(it) }
    }
  }

  @Test
  fun `no module or convention plugin uses kapt`() {
    // A word-boundary-aware pattern, not a bare substring: this convention plugins' own comments
    // legitimately explain *why* kapt is banned ("Hilt via KSP, never kapt") without using it, and
    // a bare `.doesNotContain("kapt")` would flag its own explanation the moment build-logic
    // sources were included below. Matches actual usage syntax instead: `kapt(...)`,
    // `id("kapt")`/`id("org.jetbrains.kotlin.kapt")`, `kotlin("kapt")`, `add("kapt", ...)` — kapt
    // immediately followed by `(` or `"`, which prose describing kapt does not do.
    val kaptUsage = Regex("""kapt\s*[("]""")
    (moduleBuildFiles() + buildLogicFiles()).forEach {
      assertThat(kaptUsage.containsMatchIn(it.readText())).describedAs(it.path).isFalse()
    }
  }
}
