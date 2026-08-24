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
    // ...and `buildLogicFiles()` was not covered by that, though two rules below scan it: a
    // relocated or renamed `build-logic` would silently reduce the mock-framework and kapt rules
    // to scanning nothing and both would go on passing.
    assertThat(buildLogicFiles()).describedAs("build-logic sources").isNotEmpty()
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
      .filter { f -> configurationOffences(f.readText()).isNotEmpty() }
    assertThat(offenders).describedAs("configure these in a convention plugin").isEmpty()
  }

  /**
   * Every way this file has been shown to be evadable, closed. Each entry below was verified by
   * appending the offending line to a real module build file and running this suite: before these
   * patterns existed all three passed.
   *
   * 1. **The property-access form.** `androidBlockOffends` only looks inside a braced
   *    `android { … }` block, so `android.buildFeatures.buildConfig = true` — real Android
   *    configuration, in a module build file, which is the entire thing this rule bans — was
   *    invisible. So are `androidComponents { }` and
   *    `extensions.configure<ApplicationExtension> { }`, which reach the same DSL by other routes.
   * 2. **The rule's own name.** It is called "no module configures android or *kotlin* blocks
   *    directly" while banning three identifiers, so `kotlin { jvmToolchain(21) }`,
   *    `kotlin { explicitApi() }` and `kotlin { sourceSets { … } }` all passed. A name that
   *    promises more than the code delivers is the same defect class this project fixed once
   *    already in Task 1's "on any classpath" wording.
   * 3. **The line anchor.** `^\s*compilerOptions\s*\{` misses the inlined one-line form
   *    `kotlin { compilerOptions { … } }`.
   *
   * Returns the offending snippets rather than a boolean so a failure names what it found.
   */
  private fun configurationOffences(text: String): List<String> {
    val patterns = listOf(
      // Not line-anchored: the inlined `kotlin { compilerOptions { ... } }` form evaded that.
      Regex("""(^|\s)(compileOptions|kotlinOptions|compilerOptions)\s*\{"""),
      // The `kotlin { }` half the rule's own name has always promised.
      Regex("""(^|\s)kotlin\s*\{"""),
      // Property-access and alternative-entry-point forms of the same configuration.
      Regex("""(^|\s)android\s*\."""),
      Regex("""(^|\s)androidComponents\s*[{.]"""),
      Regex("""extensions\s*\.\s*(configure|getByType|findByType)"""),
    )
    val bodyOffences = patterns.flatMap { it.findAll(text).map { match -> match.value.trim() } }
    return bodyOffences + if (androidBlockOffends(text)) listOf("android { }") else emptyList()
  }

  /**
   * True if [text] contains an `android { }` block whose content is anything other than the
   * allow-listed per-module identity values (see the test above for why exactly these four).
   *
   * **Every** `android { }` block in the file, not the first one. Kotlin DSL happily accepts more
   * than one, and every one of them takes effect, so a rule that stopped at the first had a
   * reachable blind spot: appending a second `android { buildFeatures { buildConfig = true } }` to
   * a module that already had `android { namespace = ... }` left this suite green, while putting
   * the identical property inside the existing block failed it. Same for `defaultConfig { }`
   * nested inside one `android { }` block -- [bracedBlockBodies] returns all of them and
   * [withoutBracedBlocks] removes all of them, so neither can hide behind a sibling.
   */
  private fun androidBlockOffends(text: String): Boolean =
    bracedBlockBodies(text, "android").any(::androidBodyOffends)

  /** True if one `android { }` block's interior contains anything outside the allow-list. */
  private fun androidBodyOffends(androidBody: String): Boolean {
    val defaultConfigBodies = bracedBlockBodies(androidBody, "defaultConfig")
    val outerBody = withoutBracedBlocks(androidBody, "defaultConfig")

    val outerAllowed = Regex("""^namespace\s*=\s*"[^"]*"$""")
    val innerAllowed = Regex("""^(applicationId|versionName)\s*=\s*"[^"]*"$|^versionCode\s*=\s*\d+$""")

    val outerOffenders = significantLines(outerBody).filterNot(outerAllowed::matches)
    val innerOffenders = defaultConfigBodies.flatMap(::significantLines).filterNot(innerAllowed::matches)
    return outerOffenders.isNotEmpty() || innerOffenders.isNotEmpty()
  }

  private fun significantLines(text: String): List<String> =
    text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("//") }.toList()

  /**
   * The character ranges of every `name { ... }` block in [text], braces included, outermost only
   * (the brace counter walks past any nested block of the same name, so a `defaultConfig` inside a
   * `defaultConfig` is part of its parent's range rather than a second entry).
   */
  private fun bracedBlockRanges(text: String, name: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var searchFrom = 0
    while (searchFrom < text.length) {
      val open = Regex("""(^|\s)$name\s*\{""").find(text, searchFrom) ?: break
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
      ranges += open.range.first until i
      searchFrom = i
    }
    return ranges
  }

  /** The interior of every `name { ... }` block in [text] (braces excluded). */
  private fun bracedBlockBodies(text: String, name: String): List<String> =
    bracedBlockRanges(text, name).map { range ->
      val braceIndex = text.indexOf('{', range.first)
      text.substring(braceIndex + 1, range.last)
    }

  /** [text] with every `name { ... }` block (braces included) removed. */
  private fun withoutBracedBlocks(text: String, name: String): String =
    bracedBlockRanges(text, name).reversed().fold(text) { acc, range ->
      acc.removeRange(range.first, range.last + 1)
    }

  @Test
  fun `no mock framework is declared in any build file or convention plugin`() {
    // A textual scan of *declared* dependencies — the catalogue, every module build file, and
    // every build-logic source — not a resolved-classpath check, so on its own it cannot catch a
    // mock framework arriving transitively through some other dependency.
    //
    // An earlier version of this comment claimed those classpaths "both resolve to
    // junit-jupiter/junit-platform/opentest4j/kotlin-stdlib only", and used that to argue a
    // resolved-classpath guard was not worth building. That was false, and it was the stated
    // reason for not building the stronger check. `:core:model`'s `testRuntimeClasspath` also
    // carries `org.assertj:assertj-core` and, transitively under it, `net.bytebuddy:byte-buddy`
    // — the bytecode engine Mockito is built on — and `:app`'s `debugUnitTestRuntimeClasspath`
    // resolves 141 artifacts, not four.
    //
    // The *conclusion* survives: a whole-graph resolution across every `testRuntimeClasspath`,
    // `testDebugRuntimeClasspath` and `androidTestDebugRuntimeClasspath` in the build finds no
    // Mockito, MockK, EasyMock, PowerMock, JMockit or Objenesis; Byte Buddy's only parent is
    // AssertJ. But the graph is no longer four modules with no transitive candidates, so a real
    // per-build resolved-classpath guard wired into `check` is now worth writing rather than
    // argued away — carried forward, not done here.
    //
    // The list below is *declared* names. Mokkery and Mockative are the two Kotlin mocking
    // libraries a contributor is most likely to reach for now; neither was covered.
    val banned = listOf("mockito", "mockk", "easymock", "powermock", "mokkery", "mockative", "jmockit")
    val catalogue = File(repoRoot(), "gradle/libs.versions.toml").readText().lowercase()
    banned.forEach { assertThat(catalogue).doesNotContain(it) }
    (moduleBuildFiles() + buildLogicFiles()).forEach { f ->
      val text = f.readText().lowercase()
      banned.forEach { assertThat(text).describedAs(f.path).doesNotContain(it) }
    }
  }

  @Test
  fun `the live-Navidrome test task name is not hand-synced into drift`() {
    // Testing.kt's `configureJUnit5` and root build.gradle.kts's `liveNavidromeTest` task
    // registration each declare their own `LIVE_NAVIDROME_TEST_TASK_NAME` constant -- a true
    // shared declaration is not reachable across that boundary (`build-logic` is included only
    // via `pluginManagement.includeBuild`, which exposes its plugins by id, not its Kotlin source,
    // to the root build the other way) -- so nothing but this test stops the two from drifting
    // apart. That drift is not hypothetical: this project hit exactly it once already, as a task
    // that ran "successfully" while silently executing zero tests (see Testing.kt's own doc on
    // `configureJUnit5` for the full story) -- a mismatch here reintroduces it with no build
    // failure to catch it, only this test.
    val testingKt = File(repoRoot(), "build-logic/convention/src/main/kotlin/Testing.kt")
    val rootBuildGradleKts = File(repoRoot(), "build.gradle.kts")
    val pattern = Regex("""LIVE_NAVIDROME_TEST_TASK_NAME\s*=\s*"([^"]+)"""")

    val testingKtValue = pattern.find(testingKt.readText())?.groupValues?.get(1)
    val rootBuildValue = pattern.find(rootBuildGradleKts.readText())?.groupValues?.get(1)

    // A pattern that stops matching either declaration (both renamed the constant, say) must fail
    // loudly here too, not silently pass two nulls as "equal" -- the same "a scan that finds
    // nothing is the failure mode every rule here guards against" principle as the very first test
    // in this class.
    assertThat(testingKtValue).describedAs(testingKt.path).isNotNull()
    assertThat(rootBuildValue).describedAs(rootBuildGradleKts.path).isNotNull()
    assertThat(testingKtValue)
      .describedAs("${testingKt.path} vs ${rootBuildGradleKts.path}")
      .isEqualTo(rootBuildValue)
  }

  @Test
  fun `the emulator coordinates in e2e yml and prepare-emulator sh cannot drift apart`() {
    // Tier 2's AVD is named by three strings that must agree in two files: the job `env:` block in
    // .github/workflows/e2e.yml (which the `reactivecircus/android-emulator-runner` step reads)
    // and `ci/prepare-emulator.sh`'s own `readonly` declarations (which it checks the *running*
    // device against). Nothing but this test stops them drifting -- and the drift is not
    // hypothetical: this gate shipped once with `api-level: 37` while the script's own header
    // already said `system-images;android-37.0;...`. There is no `platforms;android-37` or
    // `system-images;android-37;google_apis;x86_64` package (API 36 has a bare alias, API 37 does
    // not), so the job could not create an AVD at all -- a required gate that had never run.
    //
    // Same shape as the LIVE_NAVIDROME_TEST_TASK_NAME assertion above, for the same reason: a
    // comment asking two files to be kept in sync is not a mechanism.
    val workflow = File(repoRoot(), ".github/workflows/e2e.yml").readText()
    val script = File(repoRoot(), "ci/prepare-emulator.sh").readText()

    listOf("EMULATOR_API_LEVEL", "EMULATOR_TARGET", "EMULATOR_ARCH").forEach { name ->
      val fromWorkflow = Regex("""^\s*$name:\s*"?([^"\s#]+)"?\s*$""", RegexOption.MULTILINE)
        .find(workflow)?.groupValues?.get(1)
      val fromScript = Regex("""^readonly $name=([^\s#]+)\s*$""", RegexOption.MULTILINE)
        .find(script)?.groupValues?.get(1)

      // A pattern that stops matching either declaration must fail here too, not silently compare
      // two nulls as equal -- the same principle as the very first test in this class.
      assertThat(fromWorkflow).describedAs("$name in .github/workflows/e2e.yml").isNotNull()
      assertThat(fromScript).describedAs("$name in ci/prepare-emulator.sh").isNotNull()
      assertThat(fromWorkflow).describedAs("$name: e2e.yml vs ci/prepare-emulator.sh")
        .isEqualTo(fromScript)
    }

    // ...and the action's own inputs must actually read those variables. Without this the `env:`
    // block above could sit there agreeing with the script perfectly while the step it is supposed
    // to feed passes a hardcoded literal, which is precisely the defect this test exists to catch.
    //
    // Whole-line matches, not `contains`: `system-image-api-level: ${{ env.EMULATOR_API_LEVEL }}`
    // *contains* the string `api-level: ${{ env.EMULATOR_API_LEVEL }}`, so a substring assertion
    // passes a workflow whose `api-level:` was replaced by a literal. Confirmed by injection --
    // the substring form did exactly that.
    mapOf(
      "api-level" to "EMULATOR_API_LEVEL",
      "system-image-api-level" to "EMULATOR_API_LEVEL",
      "target" to "EMULATOR_TARGET",
      "arch" to "EMULATOR_ARCH",
      // No counterpart in prepare-emulator.sh -- an emulator build id is not something a booted
      // device reports -- so this one is only held to reading the job `env:`, which is what keeps
      // the workflow's preflight check and the action's own download pointed at the same value.
      "emulator-build" to "EMULATOR_BUILD",
    ).forEach { (input, variable) ->
      val line = Regex(
        """^\s*${Regex.escape(input)}:\s*\$\{\{\s*env\.$variable\s*\}\}\s*$""",
        RegexOption.MULTILINE,
      )
      assertThat(line.containsMatchIn(workflow))
        .describedAs("e2e.yml must pass `$input:` as \${'$'}{{ env.$variable }}, not a literal")
        .isTrue()
    }
  }

  /**
   * `verifyReleaseNoDestructiveMigration` is a real `GradleException`-throwing gate
   * (`VerifyNoDestructiveMigrationTask`), but a gate nothing in CI ever invokes is exactly the
   * "reports the absence of a problem" failure this project has already spent multiple rounds
   * eliminating -- see that task's own doc. Nothing but this test stops the ten lines that wire it
   * into `pr.yml` from being deleted (or silently reverted to the module-qualified form
   * `:core:database:verifyReleaseNoDestructiveMigration`, which would stop covering any second
   * Room module) with every other test still green. Same shape as the emulator-coordinates test
   * above: a workflow file is a build artifact this class already checks, not a place a scan
   * conveniently stops.
   */
  @Test
  fun `pr yml still runs the destructive-migration gate, unqualified`() {
    val workflow = File(repoRoot(), ".github/workflows/pr.yml").readText()

    // The bare task name, not module-qualified: AndroidRoomConventionPlugin registers this task
    // per module, and only the unqualified form (`./gradlew verifyReleaseNoDestructiveMigration`)
    // resolves it against *every* module that applies `muplay.android.room`, present or future.
    // Whole-line match, not `contains`, for the same reason the emulator test above insists on
    // whole-line matches: a step that runs `./gradlew :core:database:verifyReleaseNoDestructiveMigration`
    // still *contains* the unqualified task name as a substring.
    val line = Regex(
      """^\s*run:\s*\./gradlew verifyReleaseNoDestructiveMigration\s*$""",
      RegexOption.MULTILINE,
    )
    assertThat(line.containsMatchIn(workflow))
      .describedAs(
        "${File(repoRoot(), ".github/workflows/pr.yml").path} must run " +
          "`./gradlew verifyReleaseNoDestructiveMigration` (unqualified) as an explicit step",
      )
      .isTrue()
  }

  @Test
  fun `no module or convention plugin uses kapt`() {
    // A word-boundary-aware pattern, not a bare substring: this convention plugins' own comments
    // legitimately explain *why* kapt is banned ("Hilt via KSP, never kapt") without using it, and
    // a bare `.doesNotContain("kapt")` would flag its own explanation the moment build-logic
    // sources were included below. Matches actual usage syntax instead: `kapt(...)`,
    // `id("kapt")`/`id("org.jetbrains.kotlin.kapt")`, `kotlin("kapt")`, `add("kapt", ...)`,
    // `kapt { correctErrorTypes = true }` — kapt immediately followed by `(`, `"`, or `{`, which
    // prose describing kapt does not do (an earlier version of this regex, `kapt\s*[("]`, missed
    // the bare-block form entirely — verified missing, then fixed, by injection: see the report).
    //
    // Two gaps closed after the whole-branch review: the scan never included
    // `gradle/libs.versions.toml`, and the pattern could not match `alias(libs.plugins.kapt)` —
    // so the catalogue-alias route into kapt was open on both counts. The character class now
    // also admits `)` and `,` (`libs.plugins.kapt)` , `useVersion("...", kapt)`), and the
    // catalogue is scanned alongside the build files.
    val kaptUsage = Regex("""kapt\s*[("{),]""")
    val scanned = moduleBuildFiles() + buildLogicFiles() + File(repoRoot(), "gradle/libs.versions.toml")
    scanned.forEach {
      assertThat(kaptUsage.containsMatchIn(it.readText())).describedAs(it.path).isFalse()
    }
  }
  @Test
  fun `every Gradle project has a coverage floor`() {
    // A module absent from `coverageFloors` is un-gated, and the build's own warning for it has
    // been shown to vanish under `--configuration-cache` reuse (Plan 1's sixth silent-gate
    // instance). A test that reads both files is invocation-mode-independent, so it cannot.
    val settings = File(repoRoot(), "settings.gradle.kts").readText()
    val rootBuild = File(repoRoot(), "build.gradle.kts").readText()

    val includedProjects = Regex("""^include\("(:[^"]+)"\)""", RegexOption.MULTILINE)
      .findAll(settings).map { it.groupValues[1] }.toList()

    // A scan that finds nothing is the failure mode every rule in this class guards against.
    assertThat(includedProjects).describedAs("projects included by settings.gradle.kts").isNotEmpty()

    val floored = Regex("""^\s*"(:[^"]+)" to listOf\(""", RegexOption.MULTILINE)
      .findAll(rootBuild).map { it.groupValues[1] }.toList()
    assertThat(floored).describedAs("entries in coverageFloors").isNotEmpty()

    assertThat(includedProjects)
      .describedAs("every module needs a measured floor in `coverageFloors` (build.gradle.kts)")
      .allMatch { it in floored }
  }

}
