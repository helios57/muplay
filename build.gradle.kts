import java.io.File
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.w3c.dom.Element

// Every module applies its plugins through a build-logic convention plugin (`muplay.*`, defined
// in build-logic/convention), which applies the underlying AGP/Kotlin/KSP/Hilt plugins itself
// with an explicit, catalogue-pinned version — so nothing needs declaring here beyond the two
// pieces of cross-module *policy* below (as opposed to *mechanism*, which lives in build-logic):
// the coverage floor table, and the one project-specific test task `:core:network` needs for a
// real Navidrome container.

// Kept in sync with `Testing.kt`'s identical constant by hand, not by import: `build-logic` is
// included only via `pluginManagement.includeBuild` (see settings.gradle.kts), which exposes its
// *plugins* (by id) to every project but not its Kotlin source, so this root script genuinely
// cannot `import` a constant declared inside it. Searching the repo for
// `LIVE_NAVIDROME_TEST_TASK_NAME` finds both declarations; searching for the bare string
// `"liveNavidromeTest"` would not have found `Testing.kt`'s `if (name != "liveNavidromeTest")`
// carve-out before this constant existed, which is exactly the drift risk a rename on either side
// used to carry silently. Not left as a hand-sync convention alone: `ConventionTest`'s
// `the live-Navidrome test task name is not hand-synced into drift` reads both declarations and
// fails the build the moment they diverge, the same way this project checks everything else that
// cannot be enforced by the type system directly.
val LIVE_NAVIDROME_TEST_TASK_NAME = "liveNavidromeTest"

/**
 * One coverage floor: [counter] ("BRANCH" or "LINE") must reach [minimum] as a `COVEREDRATIO`, at
 * the granularity [element] names, restricted to [includes]/[excludes] class-name patterns when
 * given non-empty — see [coverageFloors]'s own doc for why a single module can carry more than one
 * of these, and the three JaCoCo/Gradle gotchas this class's own properties exist to route around:
 *
 * 1. **`includes`/`excludes` on an [element] `"BUNDLE"` rule silently do nothing.** Measured
 *    empirically (see task-7-report.md for the exact commands): a `"BUNDLE"` rule with
 *    `excludes = listOf("app.muplay.setup.SetupScreenKt")` still evaluated the module's *entire*,
 *    unfiltered class set — it reported the same ratio, and passed at the same floors, as an
 *    identical rule with no `excludes` at all, all the way up to a minimum of `1.00`. Every rule
 *    below that needs to scope to specific classes uses `element = "CLASS"` instead, confirmed to
 *    filter correctly — the trade-off is that a `"CLASS"`-element rule evaluates [minimum]
 *    separately against *each* matched class (every one must individually clear it), not as one
 *    blended aggregate across all of them.
 * 2. **An `includes`/`excludes` property that is assigned an empty list is not the same as one
 *    left unset.** Explicitly assigning `emptyList()` — which is what a naive
 *    `includes = floor.includes` does when `floor.includes` is itself empty — is read as "match
 *    zero classes" (a vacuous, always-passing rule), not "no restriction". The registration code
 *    below only assigns `includes`/`excludes` when the corresponding list is non-empty, leaving
 *    the property at its own real default otherwise.
 * 3. **A literal `$` in a class-name pattern never matches anything** (e.g.
 *    `"app.muplay.setup.SetupViewModel$1"`, the exact binary name of a compiled nested/lambda
 *    class) — every rule below that needs one uses a `*` wildcard across that position instead
 *    (`"app.muplay.setup.SetupViewModel*1"`), which does match. Not a regex-anchoring problem —
 *    JaCoCo's `WildcardMatcher` quotes every literal character before matching, `$` included, so
 *    it is never treated as a regex metacharacter. The real cause is upstream of matching
 *    entirely: `JavaNames.getQualifiedClassName` converts the binary name to its "qualified" form
 *    by replacing *both* `/` and `$` with `.` before a pattern ever sees it, so the class is
 *    presented to the matcher as `SetupViewModel.1` — a string that no longer contains a `$` at
 *    all, which a pattern containing one can therefore never match. The `*` wildcard works because
 *    the `.*` it becomes absorbs that literal `.` in the transformed name; [warnUngatedClasses]'s
 *    own `wildcardToRegex`/qualified-name conversion below replicates both of these exactly.
 */
data class CoverageFloor(
  val counter: String,
  val minimum: BigDecimal,
  val element: String = "BUNDLE",
  val includes: List<String> = emptyList(),
  val excludes: List<String> = emptyList(),
)

/**
 * Coverage floors, keyed by project path, enforced by every module's own
 * `jacocoTestCoverageVerification` task (Tier 1's coverage-gate step — see the "Coverage gate"
 * step in `.github/workflows/pr.yml`, and `Jacoco.kt` in build-logic for the mechanism this table
 * supplies the numbers for: which classes/execution-data a module's task reads is decided there;
 * whether a given module has a floor at all, and what it is, is decided here, once, rather than
 * once per module).
 *
 * Every number below is **measured** from a real `jacocoTestReport` run
 * (`./gradlew jacocoTestReport` per module — see task-7-report.md for the exact transcript and
 * the round of code review that produced the BRANCH/LINE split below), rounded down a little from
 * the exact figure so a routine, no-behaviour-change refactor does not flip the gate red on
 * noise — never an invented round number, and never a number the check mathematically cannot fail
 * at. (This project has shipped exactly that second defect once already: a Java-era coverage
 * table carried an entry of `0.00` — `ratio < floor` can never be true when `floor` is `0.00`,
 * since a ratio can never be negative, so that "floor" could never fail regardless of how
 * coverage actually moved.)
 *
 * **Why some modules get more than one entry, at different counters.** BRANCH coverage measures
 * the wrong thing for `@Composable` code: the Compose compiler inserts real, JaCoCo-visible
 * branches into every composable's own body (`$changed`/`$dirty` recomposition-skip checks,
 * `getDefaultsInvalid()` guards, per-call-site skip logic) that only execute when something
 * actually *composes* the function — a real UI, not a plain JVM unit test, can do that. A round
 * of code review on this task decompiled the two Compose-bearing modules directly rather than
 * reasoning about them: `:core:designsystem`'s `MuPlayTheme` composable body contains **zero**
 * author-written conditionals (every one of its missing branches is Compose codegen); of
 * `:feature:setup`'s `SetupScreenKt` branches, roughly 5-6 correspond to author-written
 * conditionals (`isConnecting`, the `when (uiState)` cascade) and the rest are the same synthetic
 * codegen. `generatedCodeExcludes` (`Jacoco.kt`) cannot fix this: it filters at class/file
 * granularity, and these synthetic branches are woven *inside the same method body* as the
 * developer's own `if`/`when` — JaCoCo has no Compose-aware filter to separate them.
 *
 * So: **non-UI code keeps the original BRANCH rule** (that is where logic lives, and it is
 * genuinely JVM-testable), and **files containing `@Composable` declarations get a LINE rule
 * instead** — "did this UI code actually run" is a question a real composition (an emulator
 * journey, Task 8) can actually answer; branch coverage there would just be measuring the Compose
 * compiler, not this project's own work. Where a module has *both* kinds of code, it gets more
 * than one entry — never one blended rule that would hide a UI-only regression behind a
 * healthy-looking ViewModel average, or vice versa.
 *
 * - **`:core:model`, `:core:network`, `:core:testing`** — no `@Composable` code at all (none of
 *   the three modules even applies the Compose convention plugin). Unchanged: a single `"BUNDLE"`-
 *   element BRANCH rule (an aggregate across the whole module — correct here, since there is
 *   nothing to separate it from), measuring 100% today (10/10, 30/30, 6/6 real branches) against
 *   the full 0.90 target. Every gap Task 7 found in them was closable from the JVM alone, so it
 *   was closed rather than excused: see `ServerCapabilitiesTest`, `SubsonicClientTest`'s new
 *   non-compliant-response and no-trailing-slash-baseUrl tests, and
 *   `OpenApiFixtureValidatorTest`'s new `readSpec`/blank-path tests.
 *
 * - **`:feature:setup`** — three `"CLASS"`-element BRANCH rules, one per non-`@Composable` class
 *   this module has (`SetupScreenKt`, the one Composable file, has no rule of its own — see next
 *   paragraph). Not one aggregate rule across all three: their measured ratios are different
 *   enough (100%, 60%, 87.5%) that a single blended floor would either sit so low it protects
 *   none of them individually, or so high the weakest one could never have passed it honestly.
 *   `SetupViewModel` (2/2, floor 0.90): `connect`'s own branches (the `InvalidUrl` check, the
 *   catch-clause dispatch), fully covered. `SetupViewModel$1` (3/5, floor 0.55): the compiled
 *   *default* `ping` constructor parameter's lambda class — two new `SetupViewModelTest` cases
 *   (a real refused connection, then a real `MockWebServer` success) closed 3 of its originally-5
 *   missing branches; the 2 still missing (`SetupViewModel.kt` lines 38-39, inside this same
 *   compiled class) are, on the evidence, the Kotlin-compiler-generated "invalid continuation
 *   state" safety branches every suspend lambda's `invokeSuspend` carries — structurally
 *   unreachable from any legitimate call site, the same *kind* of compiler-owned gap BRANCH
 *   coverage has for Compose, just from the coroutines compiler plugin instead. `SetupFailureReasonKt`
 *   (7/8, floor 0.85): `toMessage`'s `when`-cascade, `SetupFailureReasonTest` covers all three
 *   members plus both sides of `Rejected`'s `detail` null/non-null branch; the one branch still
 *   missing is an artifact of how a `when` with no `else` over a sealed interface compiles, not an
 *   uncovered case. No LINE entry for this module: `SetupScreenKt` itself measures a real
 *   0/54 = 0.00% (no JVM test touches an uninvoked composable's own body at all, unlike
 *   `:core:designsystem` below), and `0.00` is exactly the unfireable floor this project must not
 *   ship again — Task 8 raises this to a real, floorable LINE number once its emulator journey
 *   actually composes `SetupScreen`.
 *
 * - **`:core:designsystem`** — one `"CLASS"`-element LINE rule, scoped to `ThemeKt` (0.652,
 *   floor 0.63) — the file holding both `colorSchemeFor` and the `MuPlayTheme` composable (Kotlin
 *   compiles every top-level function in one `.kt` file into one class, so JaCoCo cannot separate
 *   the two at class granularity; isolating `colorSchemeFor`'s own already-100%-covered 2 branches
 *   would need JaCoCo's METHOD-element scoping, materially riskier machinery — untested by this
 *   task — for a slice that is already fully covered and immaterial to the gate, so not pursued).
 *   `colorSchemeFor` and `MuPlayTheme`'s own body are not what supplies most of that 0.652 —
 *   `LightColorScheme`/`DarkColorScheme`'s multi-line `lightColorScheme(...)`/`darkColorScheme(...)`
 *   initializers run as a side effect of the `ThemeKt` class simply being *loaded* (by
 *   `ThemeTest`), independent of `MuPlayTheme` itself ever being composed. No BRANCH entry: the
 *   only branches in this module are `colorSchemeFor`'s 2 (already 100% covered) and there is no
 *   way to gate them in isolation, per the paragraph above; `ColorKt`/`TypeKt` (this module's
 *   other two files) contain no `@Composable` declarations and no branches of their own to gate
 *   either way.
 *
 * **`:app` has no entry at all here**, deliberately, and that absence is not the same thing as a
 * `0.00` floor. Every one of its 18 measured branches, and effectively all of its measured lines
 * (1/21 — the sole covered line is `MuPlayApplication`'s own trivial body, from
 * `MuPlayApplicationTest`), is Compose/DI wiring (`MainActivity`, `MuPlayApp`, `MuPlayApplication`,
 * `SetupRoute` contain no `if`/`when`/`?:`/`&&`/`||` of their own at all). A `0.00` floor at
 * either counter would be exactly the unfireable gate this project has already shipped once
 * before; any floor above `0.00` would fail immediately against a module with nothing actually
 * wrong with it. Neither is honest, so `:app` simply has no entry until Task 8 gives it real,
 * non-Compose-only execution data to measure one against — see the loud, per-module
 * `logger.warn` below for why an absence like this is never silent.
 */
val coverageFloors: Map<String, List<CoverageFloor>> = mapOf(
  ":core:model" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
  ":core:network" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
  ":core:testing" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
  // See coverageFloors's own doc above for why three CLASS-element rules, not one BUNDLE rule.
  ":feature:setup" to listOf(
    // 2/2 -- SetupViewModel.connect's own branches (InvalidUrl check, catch-clause dispatch) are
    // fully covered by SetupViewModelTest. SetupUiState/SetupUiState$* ride along in the same
    // rule (0 branches of their own, so they can never move this ratio) purely so
    // warnUngatedClasses never has to flag their own fully-covered lines as an ungated class --
    // a real class with real state to protect, just not a branch-shaped one.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.setup.SetupViewModel",
        "app.muplay.setup.SetupUiState",
        "app.muplay.setup.SetupUiState*",
      ),
    ),
    // 3/5 -- the compiled default `ping` constructor parameter's lambda class. Two rounds of new
    // SetupViewModelTest cases (a real refused connection, then a real MockWebServer success)
    // closed 3 of its originally-5 missing branches; the 2 still missing are, on the evidence
    // (SetupViewModel.kt lines 38-39, inside this same compiled class), the Kotlin-compiler
    // -generated "invalid continuation state" safety branches every suspend lambda's
    // `invokeSuspend` carries -- structurally unreachable from any legitimate call site, the same
    // *kind* of compiler-owned gap BRANCH coverage has for Compose, just from the coroutines
    // compiler plugin instead of the Compose one.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.55"),
      includes = listOf("app.muplay.setup.SetupViewModel*1"),
    ),
    // 7/8 -- SetupFailureReason.toMessage's when-cascade; SetupFailureReasonTest covers all three
    // members plus both sides of Rejected's detail null/non-null branch. The one branch still
    // missing is an artifact of how a `when` over a sealed interface with no `else` compiles, not
    // a reachable path SetupFailureReasonTest is missing a case for. SetupFailureReason/
    // SetupFailureReason$* (the sealed interface and its members) ride along for the same reason
    // SetupUiState does above -- 0 branches of their own, included only so their own fully-covered
    // lines never show up as an ungated class.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.85"),
      includes = listOf(
        "app.muplay.setup.SetupFailureReasonKt",
        "app.muplay.setup.SetupFailureReason",
        "app.muplay.setup.SetupFailureReason*",
      ),
    ),
  ),
  // See coverageFloors's own doc above for the exact measurement and why CLASS-element.
  ":core:designsystem" to listOf(
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.63"),
      includes = listOf("app.muplay.designsystem.theme.ThemeKt"),
    ),
  ),
)

/**
 * A JaCoCo `includes`/`excludes` glob pattern (`*` = any sequence, everything else literal) as a
 * [Regex]. Reimplements enough of `org.jacoco.core.analysis.WildcardMatcher` to answer "would this
 * pattern match that class" from plain Kotlin, for [warnUngatedClasses]'s own use — not to
 * duplicate JaCoCo's actual rule evaluation (that stays entirely inside the real `violationRules`
 * block below; this is a read-only, best-effort check to decide whether to print a warning, never
 * something a build's pass/fail depends on).
 */
private fun wildcardToRegex(pattern: String): Regex =
  Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })

/**
 * Whether [floor] covers [qualifiedClassName] — mirroring JaCoCo's own two rules, both learned the
 * hard way while making [coverageFloors] itself work (see that map's own doc and
 * task-7-report.md): an `"BUNDLE"`-element rule covers every class in the module regardless of
 * `includes`/`excludes` (those properties have no effect at that element), and a `"CLASS"`-element
 * rule covers a class only if it matches at least one `includes` pattern (or `includes` is empty)
 * and no `excludes` pattern. [qualifiedClassName] must already be in JaCoCo's own "qualified" form
 * (binary name with both `/` and `$` replaced by `.` — see [warnUngatedClasses]), the same form
 * `includes`/`excludes` patterns are matched against.
 */
private fun matchesFloor(qualifiedClassName: String, floor: CoverageFloor): Boolean {
  if (floor.element == "BUNDLE") return true
  val included = floor.includes.isEmpty() || floor.includes.any { wildcardToRegex(it).matches(qualifiedClassName) }
  val excluded = floor.excludes.any { wildcardToRegex(it).matches(qualifiedClassName) }
  return included && !excluded
}

/**
 * Finding 2 recurring one level down: `coverageFloors` gates modules, and a module simply absent
 * from it now warns loudly (see the `logger.warn` below) — but a module *present* in the table can
 * still contain a class none of its own rules actually cover (`:feature:setup` is in the table,
 * `SetupScreenKt` — 94 branches, 54 lines, 0% on both — is covered by none of its three `"CLASS"`
 * -element rules, and the only signal that fact carried before this function existed was prose in
 * `coverageFloors`'s own doc comment). Reads [xmlFile] (the `jacocoTestReport` task's own XML
 * output for this module — always present and fresh, since `jacocoTestCoverageVerification`
 * depends on it below) and warns, by name and measured coverage, for every class [floors] does not
 * cover. Never fails the build: a genuinely-uncovered class can be the right call today (`.00%` is
 * unfireable for the same reason a `0.00` floor is — see `coverageFloors`'s own doc), the same way
 * an absent module is; the point is that it is never silent about it.
 *
 * Classes with zero measured branches *and* zero measured lines (`SetupUiState.Idle` and similar
 * plain `data object`s) are skipped: there is nothing in them for any rule to gate, so flagging
 * them would only be noise on every run.
 */
private fun warnUngatedClasses(modulePath: String, xmlFile: File, floors: List<CoverageFloor>, logger: Logger) {
  if (!xmlFile.isFile) return

  val factory = DocumentBuilderFactory.newInstance()
  // JaCoCo's own XML report declares a DOCTYPE pointing at its DTD on jacoco.org; parsing must
  // never depend on fetching that over the network (offline dev machines, and CI runners with no
  // reason to trust this parse with network access at all) -- the class/counter data below does
  // not depend on DTD validation succeeding.
  factory.isValidating = false
  factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
  val document = factory.newDocumentBuilder().parse(xmlFile)

  val classNodes = document.getElementsByTagName("class")
  for (i in 0 until classNodes.length) {
    val classElement = classNodes.item(i) as Element
    val binaryName = classElement.getAttribute("name") // e.g. app/muplay/setup/SetupScreenKt
    val qualifiedName = binaryName.replace('/', '.').replace('$', '.')
    if (floors.any { matchesFloor(qualifiedName, it) }) continue

    var branchMissed = 0
    var branchCovered = 0
    var lineMissed = 0
    var lineCovered = 0
    val counterNodes = classElement.getElementsByTagName("counter")
    for (j in 0 until counterNodes.length) {
      val counterElement = counterNodes.item(j) as Element
      val missed = counterElement.getAttribute("missed").toInt()
      val covered = counterElement.getAttribute("covered").toInt()
      when (counterElement.getAttribute("type")) {
        "BRANCH" -> { branchMissed = missed; branchCovered = covered }
        "LINE" -> { lineMissed = missed; lineCovered = covered }
      }
    }
    val branchTotal = branchMissed + branchCovered
    val lineTotal = lineMissed + lineCovered
    if (branchTotal == 0 && lineTotal == 0) continue

    val branchSummary = if (branchTotal > 0) "branch $branchCovered/$branchTotal" else "branch n/a"
    val lineSummary = if (lineTotal > 0) "line $lineCovered/$lineTotal" else "line n/a"
    logger.warn(
      "COVERAGE: $modulePath's $binaryName is not covered by any rule in `coverageFloors` " +
        "(root build.gradle.kts) -- measured $branchSummary, $lineSummary. If that is " +
        "deliberate (a genuinely 0%-covered Composable file, say), no action needed; if a rule " +
        "should cover this class and does not, its includes/excludes pattern is probably wrong.",
    )
  }
}

subprojects {
  // `tasks.withType(...).configureEach { }` applies whenever a task of that type is registered —
  // before or after this line runs — so this is safe regardless of whether a given module's task
  // was auto-registered eagerly (the `java`/`kotlin.jvm` + `jacoco` plugin combination JVM modules
  // get) or lazily (`configureAndroidJacocoReport`/`configureAndroidJacocoCoverageVerification` in
  // build-logic, for Android modules); no `afterEvaluate` is needed to sequence this correctly.
  //
  // Load-bearing, not defensive boilerplate: proving `:core:model`'s floor could fail (see
  // task-7-report.md) surfaced that the JVM-auto-registered `jacocoTestCoverageVerification` task
  // does *not* itself depend on `test` in this Gradle/plugin combination — running it in isolation
  // silently reused whatever stale (or entirely absent) `.exec` data happened to already sit on
  // disk instead of forcing a fresh test run, so a coverage gate run right after a source change
  // could read pre-change data and pass regardless of what the change did. `jacocoTestReport` has
  // the identical gap for the identical reason. The Android path already avoids this
  // (`configureAndroidJacocoReport`/`configureAndroidJacocoCoverageVerification` explicitly depend
  // on `testDebugUnitTest`); this makes every module's report *and* verification task depend on
  // its own `test` lifecycle task the same way, generically, rather than trusting invocation order
  // (CI step ordering, or a developer's own command) to always happen to run tests first. Verified
  // across all six modules, not just the one (`:core:model`) the bug first surfaced in: every
  // module's `jacocoTestCoverageVerification` task graph includes its own `test` task.
  tasks.withType<JacocoReport>().configureEach { dependsOn("test") }
  tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn("test")

    val floors = coverageFloors[project.path]
    if (floors.isNullOrEmpty()) {
      // Loud, not silent: a module simply absent from `coverageFloors` used to fail this
      // `return`/no-op with no signal at all — `:app` was fine because it is documented above,
      // but nothing stopped (and nothing announced) a *future* module landing in
      // `settings.gradle.kts` with no floor and no comment explaining why. This does not fail the
      // build — an ungated module is a real, sometimes-correct state (see `:app`'s own case) — it
      // just makes sure nobody has to go looking for the gap.
      logger.warn(
        "COVERAGE: ${project.path} has no entry in `coverageFloors` (root build.gradle.kts) " +
          "-- its branch/line coverage is completely unenforced in Tier 1. If that is " +
          "deliberate, document why there (see :app's own entry there for the precedent); if " +
          "not, add a measured floor.",
      )
      return@configureEach
    }

    // Ensures a fresh XML report always exists for `warnUngatedClasses` below to read, whatever
    // order tasks were invoked in (the CI workflow already runs "Coverage report" before
    // "Coverage gate" as separate steps, but this makes that guaranteed rather than assumed).
    dependsOn("jacocoTestReport")
    // Captured now, at configuration time, rather than read from inside `doLast` below:
    // `Task.project` is deprecated at *execution* time (incompatible with the configuration
    // cache) — confirmed by this exact line originally living inside `doLast` and Gradle logging
    // "Invocation of Task.project at execution time has been deprecated" on every run. A
    // `TaskProvider` captured here stays perfectly lazy (nothing about `jacocoTestReport` is
    // actually realized or read until `doLast` calls `.get()` on it), so this loses no laziness,
    // only the deprecated access pattern.
    val modulePath = project.path
    val reportTaskProvider = project.tasks.named("jacocoTestReport", JacocoReport::class.java)

    violationRules {
      isFailOnViolation = true
      floors.forEach { floor ->
        rule {
          element = floor.element
          // Only assigned when non-empty: JaCoCo/Gradle treats an *explicitly assigned* empty
          // `includes`/`excludes` list as "match zero classes" (a vacuous, always-passing rule),
          // not as "no restriction" -- confirmed empirically while proving this rule can fail (see
          // task-7-report.md): an unconditional `includes = floor.includes` with
          // `floor.includes == emptyList()` silently evaluated over zero classes and passed at
          // *any* minimum, including 0.99 against a module measuring 0.11. Leaving the property
          // untouched (its own unset default) is what makes "no restriction" actually mean that.
          if (floor.includes.isNotEmpty()) includes = floor.includes
          if (floor.excludes.isNotEmpty()) excludes = floor.excludes
          limit {
            counter = floor.counter
            value = "COVEREDRATIO"
            minimum = floor.minimum
          }
        }
      }
    }

    doLast {
      val reportXml = reportTaskProvider.get().reports.xml.outputLocation.get().asFile
      warnUngatedClasses(modulePath, reportXml, floors, logger)
    }
  }
}

// `:core:network`'s `LiveNavidromeTest` needs a real Navidrome container listening on
// localhost:4533 (see ci/navidrome.compose.yml, ci/configure-libraries.sh) — not true for a plain
// `./gradlew test` in a developer's inner loop, nor for this repo's static-analysis or
// unit+integration CI jobs. `Testing.kt`'s `configureJUnit5` already excludes anything tagged
// `"live"` from every ordinary `Test` task project-wide; this is the one task that does the
// opposite — includes *only* `"live"`-tagged tests — and it exists solely in `:core:network`,
// since that is the only module with any such test today. Registered here, not in
// `core/network/build.gradle.kts`: every module build file contains only `plugins {}` and
// `dependencies {}` (`ConventionTest` enforces it), so a one-off task like this belongs at the
// root, next to the coverage floor table it is policy alongside, not mechanism inside
// build-logic (nothing here is reusable machinery a second module would ever need).
//
// `LIVE_NAVIDROME_TEST_TASK_NAME` (`Testing.kt`) is the shared constant that keeps this task's own
// name and `configureJUnit5`'s carve-out for it from drifting apart — see that constant's own doc
// for the exact failure a plain string literal on each side already caused once.
project(":core:network") {
  // `afterEvaluate`, not a bare `project(...) { }` body: this root script's own evaluation runs
  // before `:core:network`'s build.gradle.kts applies `muplay.jvm.library` (the plugin that
  // creates the `SourceSetContainer` extension `the<SourceSetContainer>()` below reads) —
  // confirmed empirically: without `afterEvaluate`, this failed project configuration outright
  // with "Extension of type 'SourceSetContainer' does not exist", because `project(path) { }`
  // configures its target eagerly, as part of *this* script's own evaluation, not deferred until
  // the target project's own build script has run.
  afterEvaluate {
    // Captured *here*, at the `afterEvaluate` level, not inside `tasks.register<Test>(...) { }`
    // below: `the<T>()` resolves against its receiver's own extensions, and a `Task` is itself
    // `ExtensionAware` (that is how `JacocoTaskExtension` gets attached to it) — calling
    // `the<SourceSetContainer>()` *inside* the task's configuration lambda resolves it against
    // that lambda's implicit receiver, the task, not this project, and fails the same way (also
    // confirmed empirically: the task's own registered extensions at that point were exactly
    // `[ExtraPropertiesExtension, JacocoTaskExtension]` — never `SourceSetContainer` — because a
    // `Test` task has no such extension of its own; only the project does).
    val testSourceSet = the<SourceSetContainer>()["test"]

    tasks.register<Test>(LIVE_NAVIDROME_TEST_TASK_NAME) {
      group = "verification"
      description = "Runs LiveNavidromeTest (the \"live\"-tagged tests only) against a real " +
        "Navidrome container on localhost:4533 -- see ci/navidrome.compose.yml. Run by the " +
        "live-navidrome job in .github/workflows/pr.yml, after that job starts the container " +
        "and runs ci/configure-libraries.sh."

      testClassesDirs = testSourceSet.output.classesDirs
      classpath = testSourceSet.runtimeClasspath

      useJUnitPlatform {
        includeTags("live")
      }
    }
  }
}
