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

  /** The exact declaration `verifyNoMockFrameworks`'s ban list is written as, named once. */
  private val BANNED_MOCK_GROUPS_DECLARATION = "val BANNED_MOCK_GROUPS = listOf("

  /** The markers delimiting the App-access instruction block in `docs/REVIEWER-ACCESS.md`. */
  private val REVIEWER_TAPS_START = "<!-- reviewer-taps:start -->"
  private val REVIEWER_TAPS_END = "<!-- reviewer-taps:end -->"

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
      // `.claude/` is harness state, not project source: it holds git worktrees, so walking into
      // it finds a SECOND copy of every module's `build.gradle.kts` — including the root's, whose
      // canonical path then defeats the carve-out below and reports the root file as a module.
      // That turned `:app:testDebugUnitTest` red on master purely because another agent had a
      // worktree open. It is git-ignored and never contains source this project owns.
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
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
   * [text] with every `/* ... */` and `<!-- ... -->` comment removed.
   *
   * Not cosmetic, and not hypothetical. `app/src/debug/AndroidManifest.xml`'s own comment explains
   * the attribute by quoting it in full, and each `CleartextPolicyModule`'s KDoc discusses the
   * member the *other* variant provides -- so a `contains`/`doesNotContain` over raw file text is
   * answered by prose rather than by code. Measured, not assumed: with
   * `android:usesCleartextTraffic="true"` deleted from the debug manifest and only the comment
   * about it left behind, `the cleartext policy and the cleartext manifest cannot disagree` stayed
   * green. That is this project's recorded "assertion that runs but cannot fail" defect class,
   * found inside the rule written to stop the policy and the manifest disagreeing.
   */
  private fun withoutBlockComments(text: String): String = text
    .replace(Regex("""(?s)/\*.*?\*/"""), "")
    .replace(Regex("""(?s)<!--.*?-->"""), "")

  /**
   * [text] with every **trailing** `//` comment removed, quote-aware.
   *
   * [significantLines] drops a line that *starts* with `//` and nothing else, which left every
   * `contains`/`doesNotContain` rule below still answerable by prose — the exact defect
   * [withoutBlockComments] exists for, one comment style down. Measured shape of the hole:
   *
   * ```
   * import app.muplay.integrations.CleartextPolicy as CP
   * fun provideCleartextPolicy(): CP = CP.Allowed  // not CleartextPolicy.Forbidden
   * ```
   *
   * satisfies `contains("CleartextPolicy.Forbidden")` from the comment and dodges
   * `doesNotContain("CleartextPolicy.Allowed")` through the import alias. The alias half is not
   * fixable by text scanning and is left to the compile-and-run gates; the comment half is, and
   * this is it.
   *
   * A line containing `"""` is returned untouched: a raw string can carry a `//` that is code, and
   * truncating one would hide a real occurrence from a `doesNotContain` — a hole, which is the
   * wrong direction for a security rule to fail in. Within an ordinary line, a `//` inside a
   * double-quoted string (`"https://host"`, every URL literal in this repository) is not a comment
   * and is skipped by tracking quote state.
   */
  private fun withoutTrailingLineComments(text: String): String =
    text.lineSequence().joinToString("\n") { line ->
      if (line.contains("\"\"\"")) return@joinToString line
      var inString = false
      var escaped = false
      var index = 0
      while (index < line.length) {
        val c = line[index]
        when {
          escaped -> escaped = false
          inString && c == '\\' -> escaped = true
          c == '"' -> inString = !inString
          !inString && c == '/' && index + 1 < line.length && line[index + 1] == '/' ->
            return@joinToString line.substring(0, index)
        }
        index++
      }
      line
    }

  /** [text] reduced to the lines a Kotlin compiler would act on: no comments of any kind, no blanks. */
  private fun kotlinCode(text: String): String =
    significantLines(withoutTrailingLineComments(withoutBlockComments(text))).joinToString("\n")

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
    // The *conclusion* survived: a whole-graph resolution across every `testRuntimeClasspath`,
    // `testDebugRuntimeClasspath` and `androidTestDebugRuntimeClasspath` in the build found no
    // Mockito, MockK, EasyMock, PowerMock, JMockit or Objenesis; Byte Buddy's only parent is
    // AssertJ. But the graph is no longer four modules with no transitive candidates, so the real
    // per-build resolved-classpath guard was worth writing — and Plan 2 Task 10 wrote it:
    // `verifyNoMockFrameworks` (root build.gradle.kts), registered in every subproject that has a
    // test configuration and wired into `check`. Two guards, deliberately: this one is textual and
    // runs in seconds with no resolution; that one resolves and is the real answer.
    //
    // The list below is *declared* names. Mokkery and Mockative are the two Kotlin mocking
    // libraries a contributor is most likely to reach for now; neither was covered.
    val banned = listOf("mockito", "mockk", "easymock", "powermock", "mokkery", "mockative", "jmockit")
    val catalogue = File(repoRoot(), "gradle/libs.versions.toml").readText().lowercase()
    banned.forEach { assertThat(catalogue).doesNotContain(it) }
    (moduleBuildFiles() + buildLogicFiles()).forEach { f ->
      val text = scannableText(f).lowercase()
      banned.forEach { assertThat(text).describedAs(f.path).doesNotContain(it) }
    }
  }

  /**
   * The one region of the repository where a banned framework's name is *supposed* to appear:
   * `verifyNoMockFrameworks`'s own `BANNED_MOCK_GROUPS` list in the root build script.
   *
   * A guard that bans a word cannot also be the file that names the word, and the honest fix is a
   * carve-out that is itself checked rather than a file-level exemption. So this is deliberately
   * narrow — one named `val`, in one file — and
   * [`the mock-framework ban list is the only place the root build script names one`] asserts both
   * halves that keep it from becoming a hole: that the carve-out matches something, and that every
   * name the textual rule bans is actually covered by a group inside it.
   */
  private fun bannedMockGroupsDeclaration(): String {
    val rootBuild = File(repoRoot(), "build.gradle.kts").readText()
    val start = rootBuild.indexOf(BANNED_MOCK_GROUPS_DECLARATION)
    if (start < 0) return ""
    val end = rootBuild.indexOf("\n)", start)
    return if (end < 0) "" else rootBuild.substring(start, end + 2)
  }

  /** [file]'s text with [bannedMockGroupsDeclaration] removed, when [file] is the root build script. */
  private fun scannableText(file: File): String {
    val text = file.readText()
    if (file.canonicalFile != File(repoRoot(), "build.gradle.kts").canonicalFile) return text
    val declaration = bannedMockGroupsDeclaration()
    return if (declaration.isEmpty()) text else text.replace(declaration, "")
  }

  @Test
  fun `the mock-framework ban list is the only place the root build script names one`() {
    // Two assertions, and the first is the one that matters: a carve-out that stops matching is
    // indistinguishable, from the outside, from a carve-out that is not needed — the rule above
    // would go on passing while the root build script became free to declare Mockito.
    val declaration = bannedMockGroupsDeclaration()
    assertThat(declaration)
      .describedAs("`val $BANNED_MOCK_GROUPS_DECLARATION` in the root build script — the one region " +
        "`no mock framework is declared in any build file or convention plugin` skips")
      .isNotEmpty()

    // ...and the carve-out may not be wider than the guard it exists for. Every name the textual
    // rule bans has to be covered by a Maven group inside this list, or the two guards disagree
    // about what a mock framework is and the resolved-classpath one is the weaker of the two.
    val groups = declaration.lowercase()
    listOf("mockito", "mockk", "easymock", "powermock", "mokkery", "mockative", "jmockit").forEach {
      assertThat(groups)
        .describedAs("verifyNoMockFrameworks's BANNED_MOCK_GROUPS must cover `$it`")
        .contains(it)
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
   *
   * Pinning the step's *text* is not the same claim as pinning its *power to fail the job* -- a
   * re-review found that `continue-on-error: true` added to this exact step leaves the text
   * assertion above green while the gate can no longer redden a PR (GitHub Actions marks the step
   * outcome failed but the job conclusion success). The same escape exists for an `if:` that
   * evaluates false. Both are checked below, scoped to *this step's own block* -- the text between
   * its `- name:` line and the next step's `- name:` line -- specifically because the very next
   * step in this file (`Upload reports`) legitimately has an `if: failure()` of its own, and a
   * whole-file `doesNotContain` would flag that unrelated, correct line.
   */
  /**
   * **Every module that has instrumented tests is named in the one job that runs them.**
   *
   * A module can grow a `src/androidTest` source set, fill it with tests, wire coverage floors to
   * the data they produce, and have not one of them ever run in CI -- because the emulator job's
   * `script:` block is a hand-written list of Gradle task paths and nothing checks it against the
   * repository. That is not hypothetical: `:feature:player` gained 24 instrumented tests in Plan 3
   * Task 9 (the positional assertions that are the only thing between a title/artist swap and a
   * green build, plus two that dump the whole semantics tree and require it to carry neither an
   * auth token nor a salt) and Task 10 found that no CI job had ever run any of them.
   *
   * The *coverage* gate does not close this, and that was measured rather than assumed: with
   * `:feature:player`'s own execution data deleted, `:app`'s journey covers all four of that
   * module's `requiresInstrumentedData` floors on its own, so the gate stayed green over 24 tests
   * that never ran. A test suite and the coverage it happens to produce are different things.
   *
   * Whole-word matching on `:<path>:connectedDebugAndroidTest`, and the discovered module list is
   * asserted non-empty first, so a scan that found nothing cannot report success -- the failure
   * mode this project has now recorded five times.
   */
  @Test
  fun `every module with instrumented tests is run by the emulator job`() {
    val workflowFile = File(repoRoot(), ".github/workflows/e2e.yml")
    val workflow = workflowFile.readText()

    val modulesWithDeviceTests = moduleBuildFiles()
      .map { it.parentFile }
      .filter { File(it, "src/androidTest").isDirectory }
      .map { ":" + it.relativeTo(repoRoot()).path.replace(File.separatorChar, ':') }
      .sorted()

    assertThat(modulesWithDeviceTests)
      .describedAs("the scan for modules with a src/androidTest source set")
      .isNotEmpty()
    // Named, not merely counted: a scan that silently stopped finding `:app` would otherwise pass
    // this test while the whole journey tier went unrun.
    assertThat(modulesWithDeviceTests).contains(":app", ":core:media")

    // **Every** such line, not `singleOrNull`. It was `singleOrNull` while this job had one
    // emulator step; Plan 5 Task 8 added a second (a Wear OS image, with its own
    // `./gradlew :wear:connectedDebugAndroidTest`), and `singleOrNull` over two matches returns
    // null -- so the rule would have failed with "must have exactly one line" while the thing it
    // guards was in fact correct. Comment lines are dropped for the same reason `gradleTaskTokens`
    // drops them: the prose around these steps names the task.
    //
    // The vacuity guard is unchanged in force, only in wording: a scan that found no invoking line
    // at all must fail here rather than report every module missing (or, worse, none).
    val scriptLines = workflow.lines()
      .filterNot { it.trimStart().startsWith("#") }
      .filter { it.contains("connectedDebugAndroidTest") }
    assertThat(scriptLines)
      .describedAs(
        "${workflowFile.path} must have at least one line invoking connectedDebugAndroidTest; " +
          "this test cannot check a list it cannot find",
      )
      .isNotEmpty()

    val missing = modulesWithDeviceTests.filterNot { module ->
      scriptLines.any { it.contains("$module:connectedDebugAndroidTest") }
    }
    assertThat(missing)
      .describedAs(
        "${workflowFile.path}'s emulator script does not run these modules' instrumented tests, " +
          "so they exist and never execute in CI",
      )
      .isEmpty()
  }

  /**
   * The wear AVD is declared twice - in `.github/workflows/e2e.yml`'s job `env:` and in
   * `ci/prepare-wear-emulator.sh` - for the same reason the phone AVD is: the workflow launches it
   * and the script validates the device it is handed, and neither can import from the other. If
   * they disagree, the script's own "wrong system image" check fires on a correct emulator, or
   * worse, passes on a wrong one.
   *
   * Four values rather than the phone's three, because a Wear AVD also needs a device `profile`
   * (`wearos_small_round`); `android-emulator-runner` defaults that to a phone profile, and a Wear
   * system image on a phone profile does not boot to a usable watch.
   *
   * Falsified by hand while it was written, in both halves: changing `WEAR_API_LEVEL` in the
   * workflow alone fails with
   * `WEAR_API_LEVEL: e2e.yml vs ci/prepare-wear-emulator.sh expected: "36" but was: "35"`, and
   * replacing `api-level: ${'$'}{{ env.WEAR_API_LEVEL }}` with the literal `36` fails with
   * `e2e.yml must pass `api-level:` as ${'$'}{{ env.WEAR_API_LEVEL }}, not a literal`.
   */
  @Test
  fun `the wear emulator coordinates in e2e yml and prepare-wear-emulator sh cannot drift apart`() {
    val workflow = File(repoRoot(), ".github/workflows/e2e.yml").readText()
    val scriptFile = File(repoRoot(), "ci/prepare-wear-emulator.sh")
    assertThat(scriptFile).exists()
    val script = scriptFile.readText()

    listOf("WEAR_API_LEVEL", "WEAR_TARGET", "WEAR_ARCH", "WEAR_PROFILE").forEach { name ->
      val fromWorkflow = Regex("""^\s*$name:\s*"?([^"\s#]+)"?\s*$""", RegexOption.MULTILINE)
        .find(workflow)?.groupValues?.get(1)
      val fromScript = Regex("""^readonly $name=([^\s#]+)\s*$""", RegexOption.MULTILINE)
        .find(script)?.groupValues?.get(1)

      // A pattern that stops matching either declaration must fail here too, not silently compare
      // two nulls as equal -- the same principle as the very first test in this class.
      assertThat(fromWorkflow).describedAs("$name in .github/workflows/e2e.yml").isNotNull()
      assertThat(fromScript).describedAs("$name in ci/prepare-wear-emulator.sh").isNotNull()
      assertThat(fromWorkflow).describedAs("$name: e2e.yml vs ci/prepare-wear-emulator.sh")
        .isEqualTo(fromScript)
    }

    // ...and the action's own inputs must actually read those variables, or the `env:` block could
    // agree with the script perfectly while the step it feeds passed a hardcoded literal. Whole-line
    // matches, not `contains`: `system-image-api-level: ...` *contains*
    // `api-level: ...` as a substring, which is how the phone version of this
    // assertion was once satisfied by a workflow whose `api-level:` had been replaced.
    mapOf(
      "api-level" to "WEAR_API_LEVEL",
      "system-image-api-level" to "WEAR_API_LEVEL",
      "target" to "WEAR_TARGET",
      "arch" to "WEAR_ARCH",
      "profile" to "WEAR_PROFILE",
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
   * **A watch module's instrumented suite runs on the watch emulator, and nothing else does.**
   *
   * The rule above proves every module with a `src/androidTest` is *named somewhere* in the
   * emulator job. That was enough while the job had one emulator. It is not enough now: `:wear`
   * named on the **phone** step's command line would satisfy it completely, and that run would be
   * the exact defect `ci/prepare-wear-emulator.sh` exists to prevent - a wear suite on a phone
   * image is green and proves nothing, and because `:wear` and `:app` share the applicationId
   * `app.muplay` it would also reinstall the phone app underneath itself mid-job.
   *
   * The other direction matters too and is cheaper to get wrong: a phone module added to the wear
   * step would run its suite on a 45 mm round screen with a different API level, which is a slower,
   * flakier, less meaningful copy of a run that already happened.
   *
   * **Which modules are watch modules is derived from the tree, never listed here.** A module is
   * one iff its own `src/main/AndroidManifest.xml` declares `android.hardware.type.watch` - the
   * declaration Play routes APKs by, so it is the module's own statement of what it is rather than
   * a second opinion about it. That is this repository's standing answer to the hand-written list
   * that drifts, which has now cost it four gates.
   *
   * Falsified by hand: moving `:wear:connectedDebugAndroidTest` onto the phone step's line fails
   * with `these WATCH modules run on a step that is not the wear emulator's: [:wear]`.
   */
  @Test
  fun `a watch module's instrumented suite runs on the watch emulator and only there`() {
    val root = repoRoot()
    val workflowFile = File(root, ".github/workflows/e2e.yml")
    val workflow = workflowFile.readText()

    val watchModules = moduleBuildFiles()
      .map { it.parentFile }
      .filter { module ->
        File(module, "src/main/AndroidManifest.xml")
          .takeIf { it.isFile }
          ?.readText()
          ?.contains("android.hardware.type.watch") == true
      }
      .map { ":" + it.relativeTo(root).path.replace(File.separatorChar, ':') }
      .sorted()

    // Vacuity. A scan that found no watch module would satisfy every assertion below by having
    // nothing to contradict them -- the failure mode every rule in this class guards against.
    assertThat(watchModules)
      .describedAs("modules whose own manifest declares android.hardware.type.watch")
      .isNotEmpty()

    // The workflow's steps, split on their own `- name:` lines. A step is "the wear one" if its
    // script runs the wear preflight, which is the thing that makes the device a watch as far as
    // this job is concerned -- not its name, which anyone may edit.
    val steps = workflow.split(Regex("""(?=^ {6}- name:)""", RegexOption.MULTILINE))
    val taskPattern = Regex("""((?::[A-Za-z0-9_.-]+)+):connectedDebugAndroidTest""")

    val onWear = mutableListOf<String>()
    val onPhone = mutableListOf<String>()
    steps.forEach { step ->
      val body = step.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
      if (!body.contains("connectedDebugAndroidTest")) return@forEach
      val modules = taskPattern.findAll(body).map { it.groupValues[1] }.toList()
      if (body.contains("prepare-wear-emulator.sh")) onWear += modules else onPhone += modules
    }

    // Both lists non-empty: with one of them empty the two assertions below are each satisfied by
    // an absence, and a job that had lost its wear step entirely would read as compliant.
    assertThat(onWear).describedAs("modules run by ${workflowFile.path}'s wear emulator step").isNotEmpty()
    assertThat(onPhone).describedAs("modules run by ${workflowFile.path}'s phone emulator step").isNotEmpty()

    assertThat(onPhone.filter { it in watchModules })
      .describedAs(
        "these WATCH modules run on a step that is not the wear emulator's: a wear suite on a " +
          "phone image is green and proves nothing, and :wear shares :app's applicationId so it " +
          "would replace the phone app mid-job -- see ci/prepare-wear-emulator.sh",
      )
      .isEmpty()
    assertThat(onWear.filterNot { it in watchModules })
      .describedAs(
        "these non-watch modules run on the wear emulator step: their suites already ran on the " +
          "phone image, and running them again on a watch is slower and proves nothing new",
      )
      .isEmpty()
    assertThat(watchModules.filterNot { it in onWear })
      .describedAs(
        "these watch modules are never run by the wear emulator step, so their instrumented " +
          "tests exist and execute nowhere",
      )
      .isEmpty()
  }


  /**
   * **Every module with instrumented tests has those sources compiled by the fast tier.**
   *
   * The sibling test above proves the emulator job *runs* them. This one proves the ten-minute job
   * *compiles* them, which is a different and cheaper guarantee: `check` compiles neither
   * `src/androidTest` nor the release variant, so a shared test helper can change shape under a
   * module and the break stays invisible until somebody pays for an emulator. That is not
   * hypothetical either -- master once carried a `:core:media:compileDebugAndroidTestKotlin`
   * failure through every gate because two lanes changed `RealTrackBytes.client()` and its caller
   * independently.
   *
   * The step's list was hand-written and named **two** of the seven modules that have a
   * `src/androidTest`. `:core:database`, `:feature:player`, `:integrations:core`,
   * `:integrations:lidarr` and `:integrations:bindery` could all have stopped compiling their
   * device sources with `pr.yml` green. It is the fourth list in this repository written by hand to
   * describe something discoverable from the tree, and the fourth to drift.
   *
   * Derived from the same scan as the emulator-job test, and asserted non-empty first for the same
   * reason: a scan that found nothing must not be able to report success.
   */
  @Test
  fun `every module with instrumented tests is compiled by the fast tier`() {
    val workflowFile = File(repoRoot(), ".github/workflows/pr.yml")
    val workflow = workflowFile.readText()

    val modulesWithDeviceTests = moduleBuildFiles()
      .map { it.parentFile }
      .filter { File(it, "src/androidTest").isDirectory }
      .map { ":" + it.relativeTo(repoRoot()).path.replace(File.separatorChar, ':') }
      .sorted()

    assertThat(modulesWithDeviceTests)
      .describedAs("the scan for modules with a src/androidTest source set")
      .isNotEmpty()
    assertThat(modulesWithDeviceTests).contains(":app", ":core:media")

    val step = workflow.lines().singleOrNull { it.contains("compileDebugAndroidTestKotlin") }
    assertThat(step)
      .describedAs(
        "${workflowFile.path} must have exactly one line invoking compileDebugAndroidTestKotlin; " +
          "this test cannot check a list it cannot find",
      )
      .isNotNull()

    val missing = modulesWithDeviceTests.filterNot {
      checkNotNull(step).contains("$it:compileDebugAndroidTestKotlin")
    }
    assertThat(missing)
      .describedAs(
        "${workflowFile.path}'s `Compile the gates check does not` step never compiles these " +
          "modules' instrumented sources, so they can break and only the emulator job will say so",
      )
      .isEmpty()
  }

  @Test
  fun `pr yml still runs the destructive-migration gate, unqualified`() {
    val workflowFile = File(repoRoot(), ".github/workflows/pr.yml")
    val workflow = workflowFile.readText()

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
        "${workflowFile.path} must run `./gradlew verifyReleaseNoDestructiveMigration` " +
          "(unqualified) as an explicit step",
      )
      .isTrue()

    // The step's own block: from its `- name:` line up to (but not including) the next step's
    // `- name:` line, or end of file if it were the last step. A non-greedy `(.*?)` bounded by
    // that lookahead, not a bare `doesNotContain` over the whole file.
    val stepBlock = Regex(
      """- name: No destructive migration without a named exemption\n(.*?)(?=\n\s*- name:|\z)""",
      setOf(RegexOption.DOT_MATCHES_ALL),
    ).find(workflow)?.groupValues?.get(1)

    assertThat(stepBlock).describedAs("the step's own block in ${workflowFile.path}").isNotNull()

    assertThat(stepBlock).describedAs(
      "${workflowFile.path}: the destructive-migration step must not carry " +
        "`continue-on-error: true` -- that leaves this very test green while the step can no " +
        "longer fail the job",
    ).doesNotContain("continue-on-error")

    assertThat(stepBlock).describedAs(
      "${workflowFile.path}: the destructive-migration step must not carry its own `if:` -- an " +
        "always-false condition would skip the step (green) with the gate never evaluated",
    ).doesNotContain("if:")
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
  fun `verifyReleaseManifest forbids networkSecurityConfig as well as usesCleartextTraffic`() {
    // Static companion to the manual red-then-green run recorded in
    // .superpowers/sdd/2026-08-24-muplay-k02-library-browse/manifest-gate-report.md: that run
    // proves the *task* fails on a real merged manifest; this proves the *declaration* that feeds
    // it cannot silently lose an entry on some future edit (e.g. a refactor of
    // configureReleaseManifestVerification that rewrites the listOf(...) call) without this suite
    // catching it, the same way the LIVE_NAVIDROME_TEST_TASK_NAME and emulator-coordinates tests
    // above guard their own hand-synced values.
    //
    // networkSecurityConfig is the other way a manifest can permit cleartext HTTP in release
    // besides usesCleartextTraffic="true": a <network-security-config> XML resource can set
    // cleartextTrafficPermitted="true" at the base level or inside any <domain-config>. Nothing in
    // this repo references it today, but Plan 6 (casting) adds an on-device HTTP proxy and LAN
    // renderers that speak plain HTTP -- exactly the feature whose author reaches for it.
    val pluginFile = File(
      repoRoot(),
      "build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt",
    )
    // Scoped to the *release* arm, since Plan 3 Task 5: the task runs for every variant now, and
    // `forbiddenAttributes` is variant-dependent because debug legitimately carries
    // `usesCleartextTraffic` for the Tier 2 journey's plain-HTTP container. A pattern that matched
    // the whole `set(...)` call would therefore also match the `emptyList()` the debug arm passes,
    // and would go on passing if the release arm lost an entry.
    val forbiddenAttributesArgs =
      Regex("""buildType == "release"\)\s*\{\s*listOf\(([^)]*)\)""")
        .find(pluginFile.readText())
        ?.groupValues
        ?.get(1)

    // A pattern that stops matching the declaration must fail here too, not silently treat a
    // missing match as "nothing to check" -- the same principle as the very first test in this
    // class. It has already earned that: this regex went stale the moment the release-only
    // selector moved out of `onVariants` and into this value, and the missing-match branch is what
    // reported it.
    assertThat(forbiddenAttributesArgs).describedAs(pluginFile.path).isNotNull()
    assertThat(forbiddenAttributesArgs).contains("\"usesCleartextTraffic\"")
    assertThat(forbiddenAttributesArgs).contains("\"networkSecurityConfig\"")
  }

  /**
   * The mirror of the test above, for the half of the same task that checks *presence*.
   *
   * It can be lost the same silent way, and worse: deleting an entry from `requiredDeclarations`
   * leaves `verifyDebugManifest` and `verifyReleaseManifest` both green, and the defect it stops
   * checking for -- a media playback service that is not declared, or that lacks
   * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` -- fails no build, no install and no foreground test. It
   * throws `SecurityException` from `startForeground` the first time the app is backgrounded with
   * audio playing.
   *
   * Each entry is asserted **with its `android:name="..."` wrapper**, which is the part that is
   * easy to "tidy" away and is load-bearing: `android.permission.FOREGROUND_SERVICE` is a prefix of
   * `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so a bare-name list reports the shorter
   * permission present in a manifest that declares only the longer one. Measured before the wrapper
   * existed: deleting the `FOREGROUND_SERVICE` line from `core/media/src/main/AndroidManifest.xml`
   * left `verifyDebugManifest` green.
   */
  @Test
  fun `the merged-manifest gate still requires the playback service and its permissions`() {
    val pluginFile = File(
      repoRoot(),
      "build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt",
    )
    // Reads the named constant, not the `set(...)` call: Plan 5 Task 7 split the list in two
    // (`BASE_DECLARATIONS` plus `AUTOMOTIVE_DECLARATIONS`, added through a `Provider`), and a
    // pattern anchored on `requiredDeclarations.set(listOf(` stopped matching the moment it did.
    // The missing-match branch below is what reported that, which is the second time this
    // regex has gone stale and been caught by its own vacuity guard rather than by a human.
    val requiredDeclarations = declarationList(pluginFile, "BASE_DECLARATIONS")

    assertThat(requiredDeclarations).describedAs(pluginFile.path).isNotNull()
    assertThat(
      listOf(
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
        "app.muplay.media.MuPlaybackService",
        "androidx.media3.session.MediaSessionService",
      ).map { """android:name="$it"""" }
        .plus("""android:foregroundServiceType="mediaPlayback"""")
        .filterNot { checkNotNull(requiredDeclarations).contains(it) },
      // The list of what is missing, not `allMatch`: a failure has to name which declaration was
      // dropped, and an `allMatch` over an empty required list would be vacuously true.
    ).describedAs("declarations dropped from requiredDeclarations").isEmpty()
  }

  /**
   * The body of one named `private val <NAME> = listOf( ... )` declaration in [file], or `null`.
   *
   * Named constants rather than an inline `listOf(...)` inside the `set(...)` call, since Plan 5
   * Task 7: `requiredDeclarations` is now two lists joined by a `Provider`, and the rules below
   * have to be able to read each half separately to say which half lost an entry.
   */
  private fun declarationList(file: File, name: String): String? =
    Regex("""private val $name = listOf\(([\s\S]*?)\n\)""")
      .find(file.readText())
      ?.groupValues
      ?.get(1)

  /** This file, read as text by the lint-replacement rule below. */
  private val CONVENTION_TEST_PATH = "app/src/test/kotlin/app/muplay/ConventionTest.kt"

  /** The convention plugin that owns both merged-manifest declaration lists. */
  private fun applicationPlugin(): File = File(
    repoRoot(),
    "build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt",
  )

  /**
   * Every `AndroidManifest.xml` this repository owns, comments stripped.
   *
   * Comments stripped is the whole point, and it is not hypothetical here: AGP's *merged* manifest
   * preserves the source manifests' XML comments verbatim (measured -- `core/media`'s twenty-line
   * comment about the browse actions is in `app/build/intermediates/merged_manifest/debug/`), and
   * `VerifyMergedManifestTask` is a plain `contains`. A comment that quotes the declaration it
   * explains -- which is exactly what a good comment about `android:name="..."` looks like --
   * would answer the check on behalf of a declaration nobody wrote. This repository has already
   * paid twice for a `contains` that read prose (`verifyReleaseNoDestructiveMigration`, and the
   * cleartext rule below).
   */
  private fun sourceManifests(): List<File> =
    repoRoot().walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.name == "AndroidManifest.xml" }
      .toList()

  /**
   * The Android Auto gate is only as real as the opt-in that turns it on.
   *
   * `muplayApplication { androidAuto = true }` is four words in one build file, and deleting them
   * removes `verifyAutomotiveDescriptor` and four entries from the merged-manifest gate **without
   * failing anything**: the app still builds, still installs, still passes every instrumented
   * test, and quietly stops appearing in a car. That is precisely the shape of "a gate that reports
   * the absence of a problem must be provably incapable of staying quiet when it did not run".
   *
   * The second half is the one assumption [VerifyAutomotiveDescriptorTask] makes about reading a
   * *source* resource rather than a merged one -- that exactly one module in the build declares a
   * descriptor, so a library dependency cannot introduce a second one behind the check's back the
   * way it can with a manifest attribute. Derived from the tree, not written down: the opted-in
   * module list is scanned out of every module build file, so a second module opting in fails here
   * rather than silently making the descriptor check ambiguous.
   */
  @Test
  fun `the app module opts in to Android Auto and ships the descriptor it promises`() {
    val optIn = Regex("""androidAuto\s*=\s*true""")
    val optedIn = moduleBuildFiles()
      .filter { optIn.containsMatchIn(kotlinCode(it.readText())) }
      .map { it.parentFile.name }
      .sorted()

    assertThat(optedIn)
      .describedAs(
        "modules declaring `muplayApplication { androidAuto = true }`. `:app` must, or both " +
          "Android Auto gates silently stop running; nothing else may, or " +
          "verifyAutomotiveDescriptor's single-descriptor assumption is no longer true.",
      )
      .containsExactly("app")

    // `kotlinCode` strips comments, so the assertion above cannot be satisfied by the paragraph in
    // app/build.gradle.kts that explains the opt-in -- which does quote it. Asserted here rather
    // than assumed, because a comment answering a `contains` is this repository's recorded defect.
    assertThat(optIn.containsMatchIn(File(repoRoot(), "app/build.gradle.kts").readText()))
      .describedAs("the raw text of app/build.gradle.kts mentions the opt-in")
      .isTrue()

    val descriptor = File(repoRoot(), "app/src/main/res/xml/automotive_app_desc.xml")
    assertThat(descriptor).describedAs("the descriptor the opt-in promises").exists()
    val descriptorBody = withoutBlockComments(descriptor.readText())
    assertThat(descriptorBody).contains("<automotiveApp")
    assertThat(descriptorBody).containsPattern("""<uses\s+name="media"""")
  }

  /**
   * The mirror of the two rules above, for the entries Android Auto needs and nothing else does.
   *
   * Two halves, and they fail for different reasons. The first reads
   * `AndroidApplicationConventionPlugin`'s `AUTOMOTIVE_DECLARATIONS` and names every entry that has
   * to be in it: deleting one leaves `verifyDebugManifest` and `verifyReleaseManifest` green while
   * the app quietly disappears from a car, with no error, no log line and no crash.
   *
   * The second holds that list against the tree it describes, which is the half this repository
   * keeps learning it needs: every value the gate requires must actually be written in a manifest
   * this repository owns. That makes deleting `android.media.browse.MediaBrowserService` from
   * `core/media`'s service filter red in the **fast** tier as well as in the AGP gate, and -- since
   * the manifests are read with their comments stripped -- it cannot be satisfied by the comment
   * next to the declaration explaining what the declaration is for.
   */
  @Test
  fun `the merged-manifest gate still requires every declaration Android Auto discovers this app by`() {
    val plugin = applicationPlugin()
    val automotive = declarationList(plugin, "AUTOMOTIVE_DECLARATIONS")

    // A pattern that stops matching the declaration must fail here, not silently treat a missing
    // match as "nothing to check" -- the same principle as the very first test in this class.
    assertThat(automotive).describedAs("AUTOMOTIVE_DECLARATIONS in ${plugin.path}").isNotNull()

    val required = listOf(
      // Media3 MediaBrowsers ask for the library half of the session by this action.
      """android:name="androidx.media3.session.MediaLibraryService"""",
      // ANDROID AUTO enumerates media apps by this legacy action and by no other. An app that
      // declares only Media3's own actions installs, runs, passes every test here, and is simply
      // absent from the car's list.
      """android:name="android.media.browse.MediaBrowserService"""",
      // Auto's entry point into the app's declaration, and the resource it names. Two entries for
      // one <meta-data> element, because the element and the resource it points at are separately
      // losable in a manifest edit.
      """android:name="com.google.android.gms.car.application"""",
      """android:resource="@xml/automotive_app_desc"""",
    )

    assertThat(required.filterNot { checkNotNull(automotive).contains(it) })
      // The list of what is missing, not `allMatch`: a failure has to name which declaration was
      // dropped, and an `allMatch` over an empty required list would be vacuously true.
      .describedAs("declarations dropped from AUTOMOTIVE_DECLARATIONS in ${plugin.path}")
      .isEmpty()

    val manifests = sourceManifests()
    assertThat(manifests).describedAs("AndroidManifest.xml files in this repository").isNotEmpty()
    val manifestText = manifests.joinToString("\n") { withoutBlockComments(it.readText()) }

    // Derived from the list itself, not from the four spelled out above: **every** entry the gate
    // requires has to be written in a manifest this repository owns, including one added later by
    // a task nobody has written yet. The named list above is the other direction -- that these
    // four cannot be dropped -- and neither direction implies the other.
    // Line-wise rather than one regex: an entry is a raw string whose own content ends in `"`, so
    // the delimiters and the last character of the value run together as four quotes, and a
    // non-greedy `\"\"\"(.*?)\"\"\"` silently eats the value's closing quote. Measured, not reasoned
    // about -- it did, and `containsAll` below is what said so.
    val quote = "\"\"\""
    val declared = checkNotNull(automotive).lines()
      .map(String::trim)
      .filter { it.startsWith(quote) }
      .map { it.removePrefix(quote).substringBeforeLast(quote) }
    assertThat(declared)
      .describedAs("entries parsed out of AUTOMOTIVE_DECLARATIONS")
      .containsAll(required)

    assertThat(declared.filterNot(manifestText::contains))
      .describedAs(
        "the merged-manifest gate requires these and no manifest in this repository declares " +
          "them, so the only way `check` passes is a dependency supplying them -- or it does not " +
          "pass at all. Comments are stripped before this check: a comment quoting the " +
          "declaration it explains is not the declaration.",
      )
      .isEmpty()
  }

  /**
   * A disabled lint check has to say what took over from it, and the replacement has to be there.
   *
   * `app/lint.xml` is the only lint configuration in this repository, and it turns off both of
   * Android Lint's Android Auto checks. That file argues, at length and from measurements, that
   * neither can evaluate this project's layout at all -- `AndroidAutoDetector` reads the
   * application module's own manifest sources, and MuPlay's playback service is deliberately
   * declared in `:core:media`'s. Measured: adding the play-from-search action to that service left
   * `MissingIntentFilterForMediaSearch` reported, unchanged.
   *
   * That argument is exactly the kind that is true when it is written and quietly false a year
   * later, and "we replaced it with something better" is exactly the kind of claim that outlives
   * the replacement. So each disabled id is paired here with the thing that took over, and the
   * pairing is checked: `MissingMediaBrowserServiceIntentFilter` is replaced by a *declaration*
   * that `AUTOMOTIVE_DECLARATIONS` must still require, and `MissingIntentFilterForMediaSearch` by a
   * *rule in this very file* that must still exist. An id disabled with no entry here fails, and so
   * does an entry whose replacement has gone.
   */
  @Test
  fun `no Android Auto lint check is disabled without a named replacement`() {
    val lintConfig = File(repoRoot(), "app/lint.xml")
    assertThat(lintConfig).describedAs("the only lint configuration in this repository").exists()

    // What took over from each disabled check. Two maps, not one, because the evidence lives in two
    // different files and one combined haystack is answerable by the wrong half -- measured: with a
    // single blob of both sources, deleting the declaration from AUTOMOTIVE_DECLARATIONS left this
    // rule green, because *this test file* quotes the same declaration in the rule above. Each
    // replacement is now checked only against the file that is supposed to hold it.
    val replacedByDeclaration = mapOf(
      // The merged-manifest gate proves on every `check`, for both variants, what lint could not
      // see: the action really is in the artifact that ships.
      "MissingMediaBrowserServiceIntentFilter" to
        """android:name="android.media.browse.MediaBrowserService"""",
    )
    val replacedByRule = mapOf(
      // Not an equivalent, and the lint.xml comment says so. This rule holds the filter, its
      // handler and its gate entry to one answer; it does not require any of them to exist.
      "MissingIntentFilterForMediaSearch" to
        "a declared play-from-search filter must have a handler and a gate entry",
    )

    val disabled = Regex("""<issue\s+id="([^"]*)"\s+severity="ignore"""")
      .findAll(withoutBlockComments(lintConfig.readText()))
      .map { it.groupValues[1] }
      .toList()

    // Vacuity, and the direction that matters most: a lint.xml this pattern stopped matching would
    // otherwise satisfy every assertion below by appearing to disable nothing.
    assertThat(disabled)
      .describedAs("ids disabled in ${lintConfig.path}")
      .containsExactlyInAnyOrderElementsOf(replacedByDeclaration.keys + replacedByRule.keys)

    val declarations = declarationList(applicationPlugin(), "AUTOMOTIVE_DECLARATIONS")
    assertThat(declarations).describedAs("AUTOMOTIVE_DECLARATIONS").isNotNull()
    // `kotlinCode`, so a rule named only in a comment -- including the paragraphs above, which name
    // it -- does not stand in for the rule itself.
    val rules = kotlinCode(File(repoRoot(), CONVENTION_TEST_PATH).readText())

    // The **declaration** of the named test, not the bare name. Measured: matching the bare name
    // made this assertion incapable of failing, because the name is a string literal in the map
    // three lines above -- the rule was reading itself. Renaming the test away left it green.
    val orphaned =
      replacedByDeclaration.filterValues { !checkNotNull(declarations).contains(it) }.keys +
        replacedByRule.filterValues { !rules.contains("fun `$it`(") }.keys

    assertThat(orphaned)
      .describedAs(
        "${lintConfig.path} disables these lint checks and names a replacement that is no longer " +
          "there, so the check is off and nothing took its place. Either restore the replacement " +
          "or stop disabling the check.",
      )
      .isEmpty()
  }

  /**
   * The Assistant's cold-start filter, its handler and its gate entry must land together.
   *
   * Three separately-editable places describe one feature: the `<intent-filter>` in `:core:media`'s
   * manifest, `MuPlaybackService.onStartCommand`'s branch on
   * `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`, and the entry in `AUTOMOTIVE_DECLARATIONS`
   * that keeps the filter from being deleted. Every pairing of two-without-the-third is a real
   * defect that fails nothing else:
   *
   *  * filter without handler -- the app claims to answer "play X on MuPlay" and does nothing;
   *  * handler without filter -- the code is unreachable from a cold start, which is the only case
   *    the filter exists for, and it is dead weight with a passing test beside it;
   *  * filter and handler without the gate entry -- the filter can be deleted in a later manifest
   *    edit and nothing goes red.
   *
   * **All three are absent as this rule is written, and that is a state it reports rather than
   * hides.** Plan 5 Task 6 owns the filter and the handler; Task 7 wrote this and deliberately did
   * not add a filter whose handler does not exist yet, because a manifest that claims to answer an
   * intent nothing answers is a wrong claim in a shipped manifest. So this rule is green today by
   * agreement at *false*, not by having nothing to check -- it goes red the moment any one of the
   * three arrives alone, which is exactly when it is needed. Falsified in all three directions.
   */
  @Test
  fun `a declared play-from-search filter must have a handler and a gate entry`() {
    val action = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

    val manifest = File(repoRoot(), "core/media/src/main/AndroidManifest.xml")
    assertThat(manifest).describedAs("the service's own manifest").exists()
    val declared = withoutBlockComments(manifest.readText()).contains("""android:name="$action"""")

    val service = File(
      repoRoot(),
      "core/media/src/main/kotlin/app/muplay/media/MuPlaybackService.kt",
    )
    assertThat(service).describedAs("the service that would handle the intent").exists()
    // The platform constant, not the literal: `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` is
    // how a handler spells this, and `kotlinCode` strips the comments that would otherwise answer
    // for it -- including a KDoc explaining the branch, which is what a handler's comment says.
    val handled = kotlinCode(service.readText()).contains("INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH")

    val automotive = declarationList(applicationPlugin(), "AUTOMOTIVE_DECLARATIONS")
    assertThat(automotive).describedAs("AUTOMOTIVE_DECLARATIONS").isNotNull()
    val gated = checkNotNull(automotive).contains(action)

    // `.distinct()` reduced to one value is "all three agree", in either direction, and a failure
    // prints both values it found alongside the describedAs naming which place holds which.
    assertThat(listOf(declared, handled, gated).distinct())
      .describedAs(
        "play-from-search is declared=$declared in ${manifest.path}, handled=$handled in " +
          "${service.path}, gated=$gated in AUTOMOTIVE_DECLARATIONS. All three or none: a filter " +
          "with no handler answers the Assistant with silence, a handler with no filter is " +
          "unreachable from a cold start, and a filter with no gate entry can be deleted without " +
          "anything going red.",
      )
      .hasSize(1)
  }

  /**
   * `:core:cast` speaks to renderers through a hand-written `java.net.Socket` client, and to
   * Navidrome through OkHttp. That split **is** the module's security claim: `LocalNetworkOnly`
   * can only hold a connection to the local network if the connection goes through the socket
   * client, because OkHttp consults `NetworkSecurityPolicy` and knows nothing about this rule.
   *
   * Until this test existed the claim was enforced by a comment in `core/cast/build.gradle.kts`
   * ("if you find an `okhttp3` import under these packages, that is the bug"). A future task that
   * reached for OkHttp inside the cast protocol code would bypass `LocalNetworkOnly` entirely --
   * and, because the debug manifest sets `usesCleartextTraffic="true"`, it would work on the bench
   * and ship the bypass with every test green. This repository already enforces this shape of rule
   * mechanically (`BANNED_MOCK_GROUPS`, `forbiddenAttributes`); this is the same move.
   *
   * The exempt package is read from the build file rather than duplicated here, so the two cannot
   * drift -- and a build file that stops declaring one fails this test rather than quietly
   * exempting nothing (or, worse, everything).
   */
  @Test
  fun `only the cast module's proxy package may reach for OkHttp`() {
    val buildFile = File(repoRoot(), "core/cast/build.gradle.kts")
    val exemptPackage = Regex("""OKHTTP EXEMPT PACKAGE: ([\w.]+)""")
      .find(buildFile.readText())?.groupValues?.get(1)

    // A scan that finds nothing is the failure mode every rule in this class guards against, and
    // that applies to the carve-out as much as to the sources.
    assertThat(exemptPackage)
      .describedAs("${buildFile.path} must declare the one package that may use OkHttp")
      .isNotNull()

    val sourceRoot = File(repoRoot(), "core/cast/src/main/kotlin")
    val sources = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
    assertThat(sources).describedAs("Kotlin sources under ${sourceRoot.path}").isNotEmpty()

    val exemptDir = File(sourceRoot, exemptPackage!!.replace('.', '/')).canonicalPath
    // `okhttp3.` catches the import, the fully-qualified use and the star import alike. Prose that
    // merely mentions OkHttp (`CastHttpClient`'s KDoc says "not OkHttp", and it should) does not
    // write the package name with a dot after it.
    val usage = Regex("""okhttp3\s*\.""")
    val offenders = sources
      .filterNot { it.canonicalPath.startsWith(exemptDir + File.separator) }
      .filter { usage.containsMatchIn(it.readText()) }
      .map { it.path }

    assertThat(offenders)
      .describedAs(
        "renderer-facing cast code must use CastHttpClient, not OkHttp: OkHttp consults " +
          "NetworkSecurityPolicy and bypasses LocalNetworkOnly entirely. Only `$exemptPackage` " +
          "(the proxy's upstream fetch of Navidrome) is exempt.",
      )
      .isEmpty()
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

  /**
   * The severability contract, as a check rather than a promise.
   *
   * The roadmap says Plans 5-7 "are independent of each other and can be reordered or dropped".
   * For Plan 7 that is only true while nothing else in the tree can compile against it, and the
   * cheapest honest way to know is that a dependency has to be **declared** to be used. A build
   * file naming `project(":integrations:...")` is greppable; a stray import is not.
   *
   * `:app` and `:feature:requests` are the two permitted consumers, and that is the whole
   * severability surface: deleting this plan is `git rm -r integrations feature/requests`, plus
   * these two edges, plus the `settings.gradle.kts` includes and the `coverageFloors` entries.
   */
  @Test
  fun `nothing outside integrations depends on an integration`() {
    val root = repoRoot()
    val permitted = setOf("app", "feature/requests")
    val scanned = moduleBuildFiles()
      .filter { file ->
        val path = file.parentFile.relativeTo(root).invariantSeparatorsPath
        !path.startsWith("integrations/") && path !in permitted
      }
    // A regex, not `contains("project(\":integrations:")`: Gradle accepts the named form
    // `project(path = ":integrations:core")` just as happily, and the literal missed it entirely.
    // Named once so the rule and its own control assertion below cannot grep for different
    // things and agree anyway.
    val dependencyOnAnIntegration = Regex("""project\(\s*(path\s*=\s*)?":integrations:""")
    val offenders = scanned
      .filter { dependencyOnAnIntegration.containsMatchIn(it.readText()) }
      .map { it.relativeTo(root).invariantSeparatorsPath }

    // Vacuity, from both ends. A permit-list that happened to exclude every build file would leave
    // `offenders` empty forever...
    assertThat(scanned).describedAs("build files outside integrations/ and the permit-list").isNotEmpty()
    // ...and so would a rule grepping for a literal no build file in this repo actually writes.
    // The one permitted edge that exists today is the proof that this literal is the right one.
    assertThat(dependencyOnAnIntegration.containsMatchIn(File(root, "app/build.gradle.kts").readText()))
      .describedAs("the :app -> :integrations:core edge this rule greps for")
      .isTrue()

    assertThat(offenders)
      .describedAs(
        "Plan 7's integrations must stay severable: only :app and :feature:requests may depend " +
          "on an :integrations:* project. Anything else makes 'this plan can be dropped' false.",
      )
      .isEmpty()
  }

  /**
   * The cleartext policy and the cleartext manifest are written in two different files and must
   * say the same thing. Nothing in the type system connects them.
   *
   * `verifyReleaseManifest` already proves `usesCleartextTraffic` never reaches the merged release
   * manifest. This proves the *policy* side: the release source set provides `Forbidden`, the
   * debug source set provides `Allowed`, and both files exist. A missing file would not fail the
   * build loudly -- Hilt would fail to find a binding, which reads as an unrelated DI error -- and
   * a release module that provided `Allowed` would compile, pass every test, and ship an app that
   * accepts a cleartext URL it can then never connect to.
   */
  @Test
  fun `the cleartext policy and the cleartext manifest cannot disagree`() {
    val root = repoRoot()
    val debugModule = File(root, "app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt")
    val releaseModule = File(root, "app/src/release/kotlin/app/muplay/di/CleartextPolicyModule.kt")
    val debugManifest = File(root, "app/src/debug/AndroidManifest.xml")

    assertThat(debugModule).exists()
    assertThat(releaseModule).exists()

    // Every assertion below reads code with the comments stripped -- see `withoutBlockComments`
    // for the measured reason, which is that three of them were satisfied by prose without it.
    assertThat(kotlinCode(releaseModule.readText()))
      .describedAs("the release variant must provide CleartextPolicy.Forbidden and nothing else")
      .contains("CleartextPolicy.Forbidden")
      .doesNotContain("CleartextPolicy.Allowed")

    assertThat(kotlinCode(debugModule.readText()))
      .describedAs("the debug variant provides Allowed, and only because the debug manifest does")
      .contains("CleartextPolicy.Allowed")
      .doesNotContain("CleartextPolicy.Forbidden")

    assertThat(withoutBlockComments(debugManifest.readText()))
      .describedAs(
        "CleartextPolicy.Allowed is only correct while the debug manifest also permits cleartext; " +
          "if this attribute is removed, the debug policy must become Forbidden in the same commit",
      )
      .contains("android:usesCleartextTraffic=\"true\"")

    // The release side of the same agreement, checked here rather than left entirely to
    // `verifyReleaseManifest`: that task reads AGP's *merged* release manifest and only runs on a
    // release build, so `usesCleartextTraffic` added to the **main** manifest -- which every
    // variant inherits, release included -- would sit in the tree unnoticed by any JVM-tier run
    // until someone assembled a release. The two checks are complementary, not redundant: this one
    // is fast and unconditional, that one is exact and sees the merge.
    assertThat(withoutBlockComments(File(root, "app/src/main/AndroidManifest.xml").readText()))
      .describedAs("cleartext must be declared in no manifest but the debug overlay")
      .doesNotContain("usesCleartextTraffic")
  }

  /**
   * A Hilt `@EntryPoint` is a way to pull a binding out of the graph without injecting it, and all
   * four of the ones in this repository exist for `:app`'s instrumented tests. In a `src/main/`
   * source set each is *public API of its module*, so any consumer can reach the binding rather
   * than declare a dependency on it -- which is the pattern constructor injection exists to remove.
   *
   * The reason all four sat in `src/main/` is a true premise with a false conclusion, recorded here
   * because it is a natural mistake to repeat: Hilt aggregates `@InstallIn` from a variant's **main
   * compilation**, so declaring one in `:app`'s `androidTest` source set genuinely does not work
   * (it throws `ClassCastException: Cannot cast ...SingletonCImpl to ...EntryPoint`). But a
   * build-type source set *is* part of that compilation -- `app/src/debug/kotlin/.../
   * CleartextPolicyModule.kt` and its `src/release` twin are this repo's own proof -- and the
   * instrumented tests run the debug variant. `src/debug/` costs nothing and keeps all four out of
   * both the release graph and every module's published API.
   *
   * Written as "none in `src/main/`" rather than "all in `src/debug/`" on purpose. A production
   * `@EntryPoint` is a legitimate thing to need one day (a framework component Hilt cannot inject);
   * this rule makes that a decision someone has to come here and record, rather than a file that
   * lands in `src/main/` because that is where the neighbouring one was.
   */
  @Test
  fun `every Hilt entry point is declared in a debug source set`() {
    val root = repoRoot()
    val marker = "@EntryPoint"
    val declarations = repoRoot().walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.extension == "kt" }
      .filter { kotlinCode(it.readText()).contains(marker) }
      .map { it.relativeTo(root).invariantSeparatorsPath }
      .toList()

    // The premise. A scan that matched nothing -- a renamed annotation, a moved repo root -- would
    // satisfy the assertion below forever, which is the vacuity failure every rule here guards
    // against. Read from the code with comments stripped, so a KDoc mentioning `@EntryPoint`
    // (several of them do, including this rule's own subjects) is not counted as a declaration.
    assertThat(declarations).describedAs("files declaring $marker").isNotEmpty()

    assertThat(declarations.filter { it.contains("/src/main/") })
      .describedAs(
        "a Hilt @EntryPoint in src/main/ is public API of its module: any consumer can pull the " +
          "binding out of the graph instead of injecting it. All of this repository's entry " +
          "points exist for :app's instrumented tests, and belong in src/debug/ -- which is part " +
          "of the debug variant's main compilation, so Hilt still aggregates them.",
      )
      .isEmpty()
  }

  /**
   * Nothing in `integrations/` may write to a log.
   *
   * This is the one rule in this class that guards a *value* rather than a build setting, and it
   * exists because of what that value is: a Lidarr or Bindery API key is **instance-wide and
   * carries admin authority** over the user's download client or library, with no scoped or
   * read-only form to fall back on. `IntegrationCredentials.Lidarr.toString()` redacts it, and
   * `IntegrationCredentialsTest` plus `ci/mutation-probes.sh`'s `integrations/credentials-redaction`
   * keep that redaction honest — but a redacted `toString()` protects nothing against
   * `Log.d(TAG, "key=$apiKey")`, which names the field directly.
   *
   * "No integration code logs" was true when this plan's second task shipped and was checked by
   * *reading*, which is exactly the kind of claim this project has repeatedly watched decay. A
   * scan is what makes it stay true through Tasks 3-11, when clients, interceptors and error
   * handling arrive and logging is the obvious thing to reach for while debugging one against a
   * real instance.
   *
   * Test sources are excluded: an assertion is allowed to print, and none of them holds a real
   * user's key. `android.util.Log` and `System.out`/`err` are matched by name; bare `println(` is
   * matched too, since that is what actually gets typed at 1am and it goes to logcat on Android.
   */
  @Test
  fun `nothing in integrations writes to a log`() {
    val root = repoRoot()
    val sources = File(root, "integrations").walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.extension == "kt" }
      .filter { it.invariantSeparatorsPath.contains("/src/main/") }
      .toList()

    // A scan that finds nothing is the failure mode every rule in this class guards against.
    assertThat(sources).describedAs("Kotlin sources under integrations/*/src/main").isNotEmpty()

    // `android.util.Log` catches the import and the fully-qualified use; a bare `Log.` catches the
    // imported form. Comments are stripped first, so this file's own prose about logging -- and
    // every KDoc in `integrations/` that explains why a secret must not be logged -- does not
    // trip it. That is not hypothetical here: `IntegrationCredentialStore`'s class doc contains
    // the words "never logged", and `IntegrationCredentials` explains what a log line would leak.
    val loggers = listOf("android.util.Log", "Log.d(", "Log.e(", "Log.i(", "Log.v(", "Log.w(",
                         "Timber.", "println(", "System.out", "System.err")
    val offenders = sources
      .mapNotNull { file ->
        val code = kotlinCode(file.readText())
        val found = loggers.filter { code.contains(it) }
        if (found.isEmpty()) null else "${file.relativeTo(root).invariantSeparatorsPath} -> $found"
      }

    assertThat(offenders)
      .describedAs(
        "An integration API key is instance-wide and carries admin authority; there is no scoped " +
          "form of it. Redacting toString() protects nothing against a log line that names the " +
          "field. If a diagnostic is genuinely needed here, surface it as a typed result the UI " +
          "can render -- never as a log line that a bug report attaches wholesale.",
      )
      .isEmpty()
  }

  /**
   * The sibling of the rule above, for the one logger it cannot see.
   *
   * `nothing in integrations writes to a log` matches call sites — `Log.d(`, `println(`,
   * `System.out`. An OkHttp **logging interceptor** is neither: it is a line of builder
   * configuration, `addInterceptor(HttpLoggingInterceptor())`, and at `Level.HEADERS` or `BODY` it
   * writes **every request header of every call** to logcat. For this module that means the
   * `X-Api-Key` header on every Lidarr request — the exact value Plan 7 spends a Keystore alias, a
   * redacting `toString()`, a convention rule and two mutation probes keeping out of reach.
   *
   * Flagged by Plan 7 Task 4, which proved by test and probe that no URL, no exception message and
   * no fixture carries the key, and then observed that **the only thing preventing a logging
   * interceptor was that nobody had added the dependency yet.** That is a property held by
   * accident, which is this repository's standing definition of a gate that has not been written.
   *
   * The catalogue is scanned as well as the sources, because the dependency arriving is the moment
   * the hazard becomes one line away rather than an import away.
   */
  @Test
  fun `no okhttp logging interceptor may reach an integration`() {
    val root = repoRoot()
    val sources = File(root, "integrations").walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.extension == "kt" || it.name == "build.gradle.kts" }
      .filter { !it.invariantSeparatorsPath.contains("/src/test/") }
      .filter { !it.invariantSeparatorsPath.contains("/src/androidTest/") }
      .toList()

    // A scan that finds nothing is the failure mode every rule in this class guards against.
    assertThat(sources).describedAs("integration sources and build files").isNotEmpty()

    val banned = listOf("HttpLoggingInterceptor", "logging-interceptor", "okhttp3.logging")
    val offenders = sources.mapNotNull { file ->
      val text = if (file.extension == "kt") kotlinCode(file.readText()) else file.readText()
      val found = banned.filter { text.contains(it) }
      if (found.isEmpty()) null else "${file.relativeTo(root).invariantSeparatorsPath} -> $found"
    }

    assertThat(offenders)
      .describedAs(
        "An OkHttp logging interceptor writes every request header to logcat, and an integration's " +
          "requests carry an instance-wide admin API key in one. Debug a request by asserting on " +
          "it in a test against mockwebserver3, which is what the rest of this module already does.",
      )
      .isEmpty()
  }

  /**
   * `:core:media` builds every `MediaItem` this app plays, and each one's URI is an authenticated
   * Subsonic stream URL carrying `u`, `s=salt` and `t=md5(password+salt)` -- a credential that does
   * not expire.
   *
   * Today that URL reaches the platform `MediaSession` through nothing at all:
   * `LegacyConversions` writes `METADATA_KEY_MEDIA_URI` from `MediaItem.requestMetadata.mediaUri`,
   * and `MediaItems.of` never sets it. That is one line away from being false. "So the session
   * knows the URI" is a plausible thing for a future task to write, it compiles, it changes no
   * test's outcome, and it puts a replayable credential on the platform session -- readable by
   * every app the user has granted notification-listener access.
   *
   * The artwork URI is a separate matter and is deliberately *not* banned here: it carries the same
   * credential, an image loader genuinely needs it, and removing it means carrying a coverArt id
   * instead (Plan 5's `BrowseNode` already does). This rule is about the one value that has no
   * reason to cross at all.
   */
  @Test
  fun `nothing in the media module puts a stream URI on the platform session`() {
    val sourceRoot = File(repoRoot(), "core/media/src/main/kotlin")
    val sources = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
    assertThat(sources).describedAs("Kotlin sources under ${sourceRoot.path}").isNotEmpty()

    // Both spellings, because either one alone is defeatable: `setRequestMetadata` is the builder
    // call, `setMediaUri` is the only thing worth putting inside one. Matched against code with
    // comments stripped, so this rule's own subject can be named in a KDoc -- `MediaItems.of`'s
    // documentation should be free to say why it does not do this.
    val banned = listOf("setRequestMetadata", "setMediaUri")
    val offenders = sources
      .flatMap { file -> banned.filter { kotlinCode(file.readText()).contains(it) }.map { file.name to it } }

    assertThat(offenders)
      .describedAs(
        "a MediaItem's requestMetadata.mediaUri becomes METADATA_KEY_MEDIA_URI on the platform " +
          "session, where every notification listener reads it -- and this app's stream URLs are " +
          "non-expiring credentials. If a future task genuinely needs a request URI, it must be " +
          "one that carries no authentication, and that is a decision to record here.",
      )
      .isEmpty()
  }

  /**
   * `MuPlaybackService`'s KDoc makes "nothing here logs" an explicit invariant, and a media service
   * is the easiest place in an app to break it: every `MediaItem` it holds carries a stream URL
   * complete with an auth token and salt, and the reflex when a track will not play is to log the
   * item.
   *
   * Same move as `only the cast module's proxy package may reach for OkHttp`, for the same reason:
   * this repository has an invariant stated in prose, and prose is not a check.
   *
   * **What this does not claim.** It is a rule about *this project's* code, not about the process.
   * Media3's own `MediaSessionLegacyStub` logs a warning naming the artwork `Uri` when the session
   * bitmap loader fails, and `DefaultHttpDataSource` embeds the request URL in the message of the
   * exception it throws on a cross-protocol redirect. Neither is reachable from here, and the
   * service's KDoc says so rather than implying this rule closes them.
   */
  @Test
  fun `nothing in the media module logs`() {
    val sourceRoot = File(repoRoot(), "core/media/src/main/kotlin")
    val sources = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()
    assertThat(sources).describedAs("Kotlin sources under ${sourceRoot.path}").isNotEmpty()

    // `android.util.Log` catches the import and the fully-qualified call; `Log.` catches a use
    // through a plain import. `println(` catches the other reflex. Over comment-stripped code, so
    // the service's own KDoc can keep explaining what this rule is for.
    val loggers = listOf(Regex("""android\.util\.Log"""), Regex("""(^|[^\w.])Log\s*\."""), Regex("""(^|[^\w.])println\s*\("""))
    val offenders = sources
      .filter { file -> loggers.any { it.containsMatchIn(kotlinCode(file.readText())) } }
      .map { it.name }

    assertThat(offenders)
      .describedAs(
        "no source under core/media/src/main may log: every MediaItem in this module carries a " +
          "stream URL with a non-expiring auth token, and the artwork URI carries the same one.",
      )
      .isEmpty()
  }

  /**
   * Every Kotlin source a release build compiles, except the two files that are allowed to name it.
   *
   * `CleartextPolicy`'s own KDoc used to claim that *"no code compiled into the release variant
   * names [Allowed]"*. That was false when it was written: `IntegrationBaseUrl.permitsCleartext`
   * has always contained `CleartextPolicy.Allowed -> true`, in a module `:app` depends on with
   * `implementation` (not `debugImplementation`), so it is compiled into release. The rule above
   * could not see it — it opens three hardcoded paths and walks nothing — so a screen that passed
   * `CleartextPolicy.Allowed` from `app/src/main` would have left every gate in this build green.
   *
   * The property was holding by luck. This is the scan that makes it hold by construction, and it
   * is written as a walk rather than as more hardcoded paths for exactly the reason the old rule
   * failed: a rule that names the files it checks cannot see a file that did not exist when it was
   * written.
   *
   * **Two carve-outs, both matched by canonical path and both checked rather than trusted.**
   * `app/src/debug/.../CleartextPolicyModule.kt` is the variant source set that provides `Allowed`,
   * and no release build compiles it. `IntegrationBaseUrl.kt` is the one place the value is
   * *consumed* — `permitsCleartext`'s exhaustive `when`, which is what makes the policy
   * unbypassable in the first place and which cannot be written without naming both members. That
   * carve-out is narrowed to a single occurrence on a single expected line, so widening it to a
   * second use inside the same file fails this test.
   *
   * Test sources are excluded: they are compiled into no release build, and both tiers legitimately
   * pass `Allowed` to `parse` to exercise the arm a release build must never reach.
   */
  @Test
  fun `nothing a release build compiles names CleartextPolicy Allowed`() {
    val root = repoRoot()
    val debugPolicy =
      File(root, "app/src/debug/kotlin/app/muplay/di/CleartextPolicyModule.kt").canonicalFile
    val urlParser =
      File(root, "integrations/core/src/main/kotlin/app/muplay/integrations/IntegrationBaseUrl.kt")
        .canonicalFile
    val literal = "CleartextPolicy.Allowed"

    val scanned = root.walkTopDown()
      // The same three skips `moduleBuildFiles` makes, and `.claude` for the same measured reason:
      // it holds git worktrees, so walking into it finds a second copy of every source file in the
      // repository and this rule would fail on a file no agent in this session wrote.
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.extension == "kt" }
      .filterNot { it.invariantSeparatorsPath.contains("/src/test/") }
      .filterNot { it.invariantSeparatorsPath.contains("/src/androidTest/") }
      .toList()

    // Vacuity, from both ends. A walk that found nothing, or a literal no file in this repository
    // actually writes, would leave this rule green forever.
    assertThat(scanned).describedAs("release-compiled Kotlin sources").isNotEmpty()
    assertThat(scanned.map { it.canonicalFile })
      .describedAs("the release variant's own policy module must be inside the scanned set")
      .contains(File(root, "app/src/release/kotlin/app/muplay/di/CleartextPolicyModule.kt").canonicalFile)
    assertThat(scanned.filter { kotlinCode(it.readText()).contains(literal) }.map { it.canonicalFile })
      .describedAs("the carve-outs must actually match, or this rule greps for nothing")
      .containsExactlyInAnyOrder(debugPolicy, urlParser)

    val offenders = scanned
      .filterNot { it.canonicalFile == debugPolicy || it.canonicalFile == urlParser }
      .filter { kotlinCode(it.readText()).contains(literal) }
      .map { it.relativeTo(root).invariantSeparatorsPath }

    assertThat(offenders)
      .describedAs(
        "CleartextPolicy.Allowed permits an http:// integration URL, and a release build's " +
          "manifest forbids cleartext traffic outright -- so naming it outside the debug variant " +
          "source set produces an app that accepts a URL it can then never connect to. Provide " +
          "the policy through Hilt from a variant source set instead.",
      )
      .isEmpty()

    // The consuming carve-out, narrowed: one occurrence, on the line that makes the `when`
    // exhaustive. A second use anywhere in this file -- a default argument, a shortcut in a
    // companion -- is a release-compiled `Allowed` this rule would otherwise wave through.
    val parserCode = kotlinCode(urlParser.readText())
    assertThat(parserCode.split(literal).size - 1)
      .describedAs("${urlParser.name} may name $literal exactly once, in permitsCleartext's `when`")
      .isEqualTo(1)
    assertThat(parserCode.lines().map { it.trim() })
      .describedAs("the one permitted use is permitsCleartext's own `when` arm")
      .contains("$literal -> true")
  }

  // ------------------------------------------------------------------------------------------
  // Plan 8 Task 3: the upload key. One contiguous block, appended at the end, so it cannot tangle
  // with the edits other lanes are making to the rules above.
  // ------------------------------------------------------------------------------------------

  /** Paths that would be key material if they were tracked. Named once, for rule and control alike. */
  private val KEY_MATERIAL =
    Regex("""(^|/)(keystore\.properties|[^/]+\.(jks|keystore|p12|pfx|bks|pepk))$""")

  /**
   * Runs `git` with [command] in the repository root; returns its exit status and combined output.
   *
   * Fails loudly rather than returning a falsey value when it cannot run at all. That is why this
   * is a helper and not two inline `ProcessBuilder` calls: this repository has now recorded four
   * checks that could not distinguish "no" from "I cannot tell" (a `pgrep` matching its own
   * command line, a PID file holding `PID=12345`, a subagent `.output` mtime, and a coverage
   * warning suppressed by configuration-cache reuse), and every one of them was read as "no".
   */
  private fun git(vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("git", *command))
      .directory(repoRoot())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
      "git ${command.joinToString(" ")} did not finish within two minutes"
    }
    return process.exitValue() to output
  }

  /**
   * The upload key must never be in this repository, in any form.
   *
   * Asked of **git's index**, not of `.gitignore`'s text, and that is the entire point. A rule that
   * reads the ignore file proves someone wrote a pattern down; it says nothing about a file added
   * with `git add -f`, about a file committed before the pattern existed, or about a shape of key
   * material the pattern's author did not think of. `git ls-files` answers the question actually at
   * stake — what would a `git push` publish — and answers it about this checkout rather than about
   * a policy document.
   *
   * Getting this wrong is not recoverable by rotating a secret. Anyone holding the upload key can
   * sign an artifact Play accepts as an update to this application, and installed users receive it
   * as MuPlay. Which is why Plan 8 states the constraint as "not encrypted, not base64-encoded in
   * a workflow file": an encoded key is still the key.
   */
  @Test
  fun `no keystore material is tracked by git`() {
    val (status, output) = git("ls-files", "-z")
    assertThat(status).describedAs("git ls-files exit status (output: $output)").isZero()
    // `-z` above, so the separator is NUL: a path containing a newline (git permits one)
    // would otherwise arrive as two paths, neither of which matches the pattern.
    val tracked = output.split('\u0000').filter { it.isNotEmpty() }

    // Vacuity, from both ends. A `git ls-files` that returned nothing — wrong directory, git
    // missing, a checkout with no index — would leave this rule green forever...
    assertThat(tracked).describedAs("files in git's index").isNotEmpty()
    // ...and so would a pattern matching no filename anybody would actually create. These three are
    // the exact names this task's own tooling, .gitignore and documentation use.
    assertThat(
      listOf("keystore.properties", "upload-keystore.jks", "ci/release.p12")
        .filter { KEY_MATERIAL.containsMatchIn(it) },
    )
      .describedAs("the pattern must match the shapes key material actually takes")
      .hasSize(3)

    assertThat(tracked.filter { KEY_MATERIAL.containsMatchIn(it) })
      .describedAs(
        "the upload key must never be tracked, in any form. Remove it from the index " +
          "(git rm --cached), and then treat the key as compromised: anything that reached a " +
          "commit reached the reflog, and anything pushed reached everyone with read access.",
      )
      .isEmpty()
  }

  /**
   * ...and the ignore list has to actually work, so the *next* keystore someone generates is
   * untracked by default rather than by that person's discipline.
   *
   * `git check-ignore`, not a `contains` over `.gitignore`: the question is whether git ignores the
   * path, and git's own answer accounts for pattern syntax, ordering, negation and every other
   * `.gitignore` in the tree. The rule above is the guarantee; this one is what stops the guarantee
   * depending on somebody remembering.
   */
  @Test
  fun `git ignores every shape key material takes`() {
    fun ignored(path: String): Boolean {
      val (status, output) = git("check-ignore", "-q", "--no-index", path)
      // 0 = ignored, 1 = not ignored, anything else = git could not answer, which must not be read
      // as either of the two real answers.
      check(status == 0 || status == 1) { "git check-ignore could not answer for $path: $output" }
      return status == 0
    }

    val candidates = listOf(
      "keystore.properties",
      "upload-keystore.jks",
      "app/muplay-upload.keystore",
      "ci/release.p12",
      "secrets/upload.pfx",
      "upload.bks",
      "app/muplay.pepk",
    )
    assertThat(candidates.filterNot(::ignored))
      .describedAs("add a pattern for these to .gitignore — see its upload-key section")
      .isEmpty()

    // The control. Without it, a `check-ignore` that answered "ignored" for everything — a stray
    // `*` in some .gitignore, or a helper that mistook an error for a hit — would satisfy the
    // assertion above while proving nothing at all.
    assertThat(ignored("app/build.gradle.kts"))
      .describedAs("a tracked project file must NOT be ignored, or this helper proves nothing")
      .isFalse()
  }

  // ------------------------------------------------------------------------------------------
  // Plan 8 Tasks 7 and 9: the release gates, and the pipeline that runs them. One contiguous
  // block, appended after Task 3's, so it cannot tangle with the rules above.
  // ------------------------------------------------------------------------------------------

  /** The workflow a tag push runs. Named once, for both rules below. */
  private fun releaseWorkflow(): File = File(repoRoot(), ".github/workflows/release.yml")

  /** The exact declaration `RELEASE_CHECK_EXCLUSIONS` is written as, named once. */
  private val RELEASE_CHECK_EXCLUSIONS_DECLARATION = "internal val RELEASE_CHECK_EXCLUSIONS = listOf("

  /**
   * Every `./gradlew ...` command line in [workflow], comments removed and line continuations
   * joined, split into tokens.
   *
   * Comments removed *first*, and that is the whole reason this is a function rather than a
   * `contains` over the file. `.github/workflows/release.yml` explains what each gate does, by
   * name, in a comment beside the step that runs them — so a `contains("verifyReleaseSigned")`
   * over the raw text passes just as happily when the task has been deleted from the command line
   * and only the comment describing it remains. That is the same trap `kotlinCode` exists for on
   * the Kotlin side, and it has produced a false pass in this repository before.
   */
  private fun gradleTaskTokens(workflow: String): List<String> {
    val lines = workflow.lines().filterNot { it.trimStart().startsWith("#") }
    val commands = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
      if (lines[index].contains("gradlew")) {
        val command = StringBuilder()
        while (index < lines.size) {
          val line = lines[index]
          command.append(line.trim().removeSuffix("\\")).append(' ')
          if (!line.trimEnd().endsWith("\\")) break
          index++
        }
        commands += command.toString()
      }
      index++
    }
    return commands.flatMap { it.trim().split(Regex("""\s+""")) }.filter { it.isNotEmpty() }
  }

  /**
   * A release gate that `releaseCheck` does not run must be run by the release workflow, or by
   * nothing at all.
   *
   * `releaseCheck` collects its gates by matching `verifyRelease*` (see `ReleaseGates.kt`), so a
   * new one is picked up the moment it is registered and there is no list to keep in sync — which
   * is this repository's standing answer to "a list written by hand in one file, describing
   * something discoverable from the tree, drifts and nothing notices". Two gates are deliberately
   * *excluded*, because each needs something a developer's checkout has not got:
   * `verifyReleaseSigned` needs the upload key and `verifyReleaseTag` needs the tag being released.
   *
   * Both are hard errors when what they need is absent rather than quiet skips, so excluding one is
   * the same act as promising the pipeline runs it. This is that promise, checked. Adding a name to
   * `RELEASE_CHECK_EXCLUSIONS` and forgetting the workflow line leaves a gate that runs nowhere —
   * indistinguishable, from every green build, from a gate that passes.
   *
   * The gate list is derived from `tasks.register` calls in `build-logic`, so it covers every
   * literally-named one. It does **not** cover `verifyReleaseManifest`, whose name is built from
   * the variant (`verify${'$'}{variant}Manifest`) and so appears in no string literal; that task is
   * inside `releaseCheck`'s prefix match, is wired into `check` besides, and has its own step in
   * `pr.yml`. Stated rather than papered over: a derivation with a known blind spot is worth having
   * as long as nobody reads it as complete.
   */
  @Test
  fun `every release gate is run by releaseCheck or by the release workflow`() {
    val root = repoRoot()
    val workflowFile = releaseWorkflow()
    assertThat(workflowFile).exists()
    val tokens = gradleTaskTokens(workflowFile.readText())

    // Vacuity, first: a workflow whose Gradle lines this test could not find would satisfy every
    // assertion below by containing nothing to contradict them.
    assertThat(tokens).describedAs("tokens of ${workflowFile.path}'s gradlew command lines").isNotEmpty()

    val registration = Regex("""tasks\.register(?:<\w+>)?\("(verifyRelease\w*)"\)""")
    val gates = buildLogicFiles()
      .flatMap { file -> registration.findAll(kotlinCode(file.readText())).map { it.groupValues[1] } }
      .toSortedSet()
    // Named, not merely counted, for the same reason the emulator-job rule names two modules: a
    // regex that silently stopped matching would leave every check below trivially true.
    assertThat(gates)
      .describedAs("verifyRelease* tasks registered in build-logic")
      .contains("verifyReleaseVersion", "verifyReleaseArtifact", "verifyReleaseSigned", "verifyReleaseTag")

    val exclusionsSource = File(root, "build-logic/convention/src/main/kotlin/ReleaseGates.kt")
    val exclusions = Regex(""""([^"]+)"""")
      .findAll(
        kotlinCode(exclusionsSource.readText())
          .substringAfter(RELEASE_CHECK_EXCLUSIONS_DECLARATION, "")
          .substringBefore(")"),
      )
      .map { it.groupValues[1] }
      .toList()
    assertThat(exclusions)
      .describedAs("$RELEASE_CHECK_EXCLUSIONS_DECLARATION in ${exclusionsSource.name}")
      .isNotEmpty()
    assertThat(gates)
      .describedAs("an exclusion naming no registered task leaves a dead line in the workflow")
      .containsAll(exclusions)

    fun invoked(task: String) = tokens.any { it == task || it.endsWith(":$task") }

    assertThat(exclusions.filterNot(::invoked))
      .describedAs(
        "these gates are excluded from releaseCheck, so ${workflowFile.path} is the only thing " +
          "that can run them -- and it does not. Either run them there or stop excluding them.",
      )
      .isEmpty()
    // And the aggregate itself, without which the excluded gates would be the only ones running.
    assertThat(listOf("releaseCheck", "bundleRelease").filterNot(::invoked))
      .describedAs("${workflowFile.path} must build the bundle and run releaseCheck over it")
      .isEmpty()
  }

  /**
   * The upload key reaches CI from repository secrets, and from nothing else.
   *
   * Plan 8's first global constraint, and the one that is not recoverable by rotating anything:
   * *"not encrypted, not base64-encoded in a workflow file"*. Anyone holding this key can sign an
   * artifact Play accepts as an update to MuPlay, and installed users receive it as MuPlay.
   *
   * `no keystore material is tracked by git` above asks git's index what would be published. This
   * asks the workflow files what would be *written* — which is the other half, because a workflow
   * that materialises the keystore inside `github.workspace` puts key material one careless
   * `git add -A` (or one `upload-artifact` glob) away from the index that rule guards.
   *
   * Four things, and the first is derived rather than listed: the environment variable names come
   * out of `ReleaseBuild.kt`'s own constants, so a rename there fails this test instead of quietly
   * leaving the workflow setting variables nothing reads.
   */
  @Test
  fun `the release workflow reads key material only from secrets`() {
    val root = repoRoot()
    val workflowFile = releaseWorkflow()
    assertThat(workflowFile).exists()
    // Comments stripped, as everywhere else here: this workflow's own prose names the variables.
    val body = workflowFile.readText().lines().filterNot { it.trimStart().startsWith("#") }

    val releaseBuild = File(root, "build-logic/convention/src/main/kotlin/ReleaseBuild.kt")
    val signingEnvironment = Regex("""const val \w+ = "(MUPLAY_\w+)"""")
      .findAll(kotlinCode(releaseBuild.readText()))
      .map { it.groupValues[1] }
      .toList()
    assertThat(signingEnvironment)
      .describedAs("the signing environment variables declared in ${releaseBuild.name}")
      .containsExactlyInAnyOrder(
        "MUPLAY_KEYSTORE_PATH",
        "MUPLAY_KEYSTORE_PASSWORD",
        "MUPLAY_KEY_ALIAS",
        "MUPLAY_KEY_PASSWORD",
      )

    val assignment = Regex("""^\s*(MUPLAY_\w+):\s*(\S.*?)\s*$""")
    val assignments = body.mapNotNull { assignment.find(it) }
      .associate { it.groupValues[1] to it.groupValues[2] }
    assertThat(assignments.keys)
      .describedAs(
        "${workflowFile.path} must set every variable releaseSigningConfig reads; a release job " +
          "that sets three of the four is the half-configured case build-logic turns into a hard " +
          "error, and a job that sets none of them silently produces an UNSIGNED bundle",
      )
      .containsAll(signingEnvironment)

    // `MUPLAY_KEYSTORE_PATH` is the one that is not material: it names a file. Every other one is
    // the material itself and may only ever come from a secret.
    assertThat(
      signingEnvironment
        .filter { it != "MUPLAY_KEYSTORE_PATH" }
        .filterNot { assignments.getValue(it).startsWith("\${{ secrets.") },
    )
      .describedAs("a password or alias written as a literal in a workflow file is a leaked key")
      .isEmpty()

    // ...and the file it names is outside the checkout, so no `git add` and no artifact glob can
    // ever reach it. Every line naming a keystore file has to say so.
    val keystoreFile = Regex("""[\w.-]+\.(?:jks|keystore|p12|pfx|bks|pepk)\b""")
    val keystoreLines = body.filter { keystoreFile.containsMatchIn(it) }
    assertThat(keystoreLines)
      .describedAs("${workflowFile.path} must name the keystore file somewhere, or this reads nothing")
      .isNotEmpty()
    assertThat(keystoreLines.filterNot { it.contains("RUNNER_TEMP") || it.contains("runner.temp") })
      .describedAs(
        "the upload key must exist only outside github.workspace (RUNNER_TEMP). Inside it, a " +
          "`git add -A`, an `upload-artifact` glob or a `git status` in a later step all reach it.",
      )
      .isEmpty()

    // No workflow may carry key material inline, in any file. A PEM block is what that looks like.
    val workflows = File(root, ".github/workflows").listFiles().orEmpty().filter { it.extension == "yml" }
    assertThat(workflows).describedAs("workflow files").isNotEmpty()
    assertThat(workflows.filter { it.readText().contains("-----BEGIN") }.map { it.name })
      .describedAs("a PEM block in a workflow file is key material committed to this repository")
      .isEmpty()

    // The obvious way a secret reaches a log, and only the obvious way: `echo "$KEY_PASSWORD"`.
    // Shell being shell, this cannot be exhaustive -- an indirect expansion or a `set -x` defeats
    // it -- and it is here because the obvious way is the one that actually gets typed.
    val secretVariables = signingEnvironment.map { it.removePrefix("MUPLAY_") } + signingEnvironment
    assertThat(
      body.filter { line ->
        line.contains("echo") && secretVariables.any { line.contains("\$$it") || line.contains("\${$it") }
      },
    )
      .describedAs("nothing may echo a signing secret; GitHub's log masking is not a second chance")
      .isEmpty()
  }

  /**
   * `build-logic`'s own tests are run by a CI workflow.
   *
   * They were not, and nobody had noticed. `build-logic` is an **included build** (see
   * `settings.gradle.kts`'s `pluginManagement { includeBuild("build-logic") }`), so `./gradlew check`
   * at the repository root does not reach it: it builds and runs the eleven *project* modules and
   * stops. Measured while writing this rule -- no workflow file mentioned `build-logic` in any
   * `run:` line, so **thirteen tests had never executed in CI** -- and those two suites were the
   * only red-capable evidence for two gates:
   *
   *  * `VerifyMergedManifestTaskTest` is the only thing in this repository that fails when the
   *    merged-manifest gate stops checking. Its own header records the measurement: with the
   *    `missing` block deleted from `verify()` *and* a real permission deleted from
   *    `core/media/src/main/AndroidManifest.xml`, every other gate here -- including all of
   *    `ConventionTest` -- stayed green.
   *  * `VerifyReleaseVersionTaskTest` is the only thing that watches the version gate say no, and
   *    that gate's happy path is the path every single build takes.
   *
   * This is the fourth time this repository has found a working test suite that no gate runs, after
   * `:feature:player`'s 24 instrumented tests, `:integrations:core`'s 17 and `:integrations:lidarr`.
   * The pattern is identical every time and so is the fix: derive the list from the tree rather than
   * write it down. Any directory under `build-logic` with a `src/test` source set has to be invoked
   * by name in a workflow's Gradle command line.
   */
  @Test
  fun `build-logic's own tests are run by CI`() {
    val root = repoRoot()
    val buildLogic = File(root, "build-logic")
    val moduleDirectories = buildLogic.listFiles().orEmpty()
      .filter { it.isDirectory && File(it, "src/test").isDirectory }
      .map { it.name }
      .sorted()

    // Vacuity: a `build-logic` that had been renamed or restructured would otherwise satisfy the
    // assertion below by having nothing to check.
    assertThat(moduleDirectories)
      .describedAs("build-logic modules with a src/test source set")
      .isNotEmpty()

    val workflows = File(root, ".github/workflows").listFiles().orEmpty().filter { it.extension == "yml" }
    assertThat(workflows).describedAs("workflow files").isNotEmpty()
    // Comments stripped by `gradleTaskTokens`, and load-bearing here too: the step added for this
    // rule explains at length what lives in `build-logic`'s test source set, naming it.
    val tokens = workflows.flatMap { gradleTaskTokens(it.readText()) }
    assertThat(tokens).describedAs("tokens of every workflow's gradlew command lines").isNotEmpty()

    assertThat(moduleDirectories.filterNot { module -> tokens.any { it == ":build-logic:$module:test" } })
      .describedAs(
        "no workflow runs these build-logic modules' tests, so they exist and never execute in " +
          "CI -- which is exactly what was true of all of build-logic until this rule was written",
      )
      .isEmpty()
  }

  /**
   * Every control `docs/REVIEWER-ACCESS.md` tells a Play reviewer to tap is a label the app really
   * renders, and no instruction in that document sends a reviewer to a cleartext remote host.
   *
   * The App-access instructions are the one piece of this repository's prose that is **filed with
   * Google** and then read by a stranger, months later, against whatever binary is current. A
   * button renamed in `SetupScreen` or `LibraryScreen` does not break a test, does not break a
   * build, and silently turns the review instructions into a walkthrough of a screen that no
   * longer exists -- which is a rejection, arrived at with every gate green. This repository has
   * already been bitten three times by *a list written by hand in one file describing something
   * discoverable from the tree*; this is the same shape with a slower and more expensive failure.
   *
   * The list is not written twice. The rule reads the instruction block itself, between the two
   * `reviewer-taps` markers, and takes every bolded run in it as a control the reviewer is told to
   * type into or tap. HTML comments are stripped first, so an explanation *about* the block cannot
   * satisfy it -- the same discipline `verifyDebugManifest`'s required half learned the hard way,
   * where a comment quoting a declaration stood in for the declaration.
   *
   * The second half is the cleartext one, and it is not theoretical either. Measured on the
   * minified, release-signed APK (Plan 8 Task 2, recorded in `CLAUDE.md`): `http://10.0.2.2:4533`
   * answers "Could not reach the server." while `http://localhost:4533` connects and plays, same
   * install, minutes apart -- Android's default network security config for `targetSdk >= 28`
   * forbids cleartext to a remote host and carves out `localhost`, and no manifest opts out of
   * either half. So an `http://` address for anything but `localhost` in these instructions is an
   * instruction to watch the app fail.
   */
  @Test
  fun `every control the reviewer instructions name is a label the app renders`() {
    val root = repoRoot()
    val document = File(root, "docs/REVIEWER-ACCESS.md")
    assertThat(document).exists()
    val text = document.readText()

    // Every marked region, not just the first: the recommended route and the fallback route each
    // carry their own instruction block, and a rule that read only one of them would leave the
    // other free to name a control that no longer exists.
    val regions = Regex(
      Regex.escape(REVIEWER_TAPS_START) + "(.*?)" + Regex.escape(REVIEWER_TAPS_END),
      RegexOption.DOT_MATCHES_ALL,
    ).findAll(text).map { withoutBlockComments(it.groupValues[1]) }.toList()

    // Vacuity, and the loudest failure this rule can produce. A document that had lost its markers
    // -- renamed, reformatted, rewritten by someone who did not know they were load-bearing --
    // would otherwise satisfy every assertion below by having nothing to check, which is this
    // project's most-recorded defect shape and the one it least wants in a gate over prose.
    assertThat(regions)
      .describedAs(
        "no $REVIEWER_TAPS_START / $REVIEWER_TAPS_END block in " +
          document.relativeTo(root).invariantSeparatorsPath + " -- those markers delimit the text " +
          "that is filed in Play Console, and this rule cannot check anything without them",
      )
      .isNotEmpty()

    val controls = regions
      .flatMap { region -> Regex("""\*\*([^*]+)\*\*""").findAll(region).map { it.groupValues[1] } }
      .toSortedSet()
    assertThat(controls)
      .describedAs("controls named in bold inside the reviewer-taps blocks")
      .isNotEmpty()

    val shippedUi = File(root, "feature").walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.extension == "kt" }
      .filter { it.invariantSeparatorsPath.contains("/src/main/kotlin/") }
      .toList()
    assertThat(shippedUi).describedAs("shipped feature sources").isNotEmpty()

    // Comments stripped: a label that survives only in a comment explaining the label that used to
    // be there is exactly the false pass this project has already measured once, on the merged
    // manifest, where prose quoting a declaration satisfied the check on its behalf.
    val rendered = shippedUi.joinToString("\n") { kotlinCode(it.readText()) }

    assertThat(controls.filterNot { rendered.contains("\"" + it + "\"") })
      .describedAs(
        "these controls are named in ${document.relativeTo(root).invariantSeparatorsPath}, which is " +
          "the text filed in Play Console's App access section, but no shipped feature source " +
          "renders a label by that name -- so the instructions Google's reviewer follows describe " +
          "a screen this app no longer has",
      )
      .isEmpty()

    // The instruction blocks only, deliberately. The prose around them *quotes* a cleartext URL --
    // the emulator's `10.0.2.2` alias, the address that was measured failing on a release build --
    // and that measurement is the reason this half of the rule exists. Scanning the whole document
    // would make the evidence for a rule fail the rule.
    val cleartext = regions
      .flatMap { region -> Regex("""http://[^\s`'"<>)\]]+""").findAll(region).map { it.value } }
      .filterNot { it.removePrefix("http://").substringBefore("/").substringBefore(":") == "localhost" }
    assertThat(cleartext)
      .describedAs(
        "a release build cannot reach a cleartext remote host -- Android's default network " +
          "security config forbids it and permits only localhost -- so a reviewer given one of " +
          "these addresses would see \"Could not reach the server.\" and call the app broken",
      )
      .isEmpty()
  }

}
