import java.io.File
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.PathSensitivity
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
 *    the `.*` it becomes absorbs that literal `.` in the transformed name; [UngatedClassChecker]'s
 *    own `wildcardToRegex`/qualified-name conversion below replicates both of these exactly.
 */
data class CoverageFloor(
  val counter: String,
  val minimum: BigDecimal,
  val element: String = "BUNDLE",
  val includes: List<String> = emptyList(),
  val excludes: List<String> = emptyList(),
  /**
   * Whether this floor can only be measured with instrumented execution data, i.e. whether it
   * belongs to Tier 2 alone rather than to both tiers — see [isEnforceableWithoutAnEmulator].
   */
  val requiresInstrumentedData: Boolean = false,
)

/**
 * The task that enforces only the floors a plain JVM test run can measure — Tier 1's coverage
 * gate (`.github/workflows/pr.yml`), and part of `check`. Registered below, alongside the
 * `jacocoTestCoverageVerification` task it is a strict subset of.
 */
val JVM_COVERAGE_VERIFICATION_TASK_NAME = "jacocoJvmCoverageVerification"

/**
 * The task that enforces the *whole* table, against merged JVM + instrumented execution data —
 * Tier 2's coverage gate (`.github/workflows/e2e.yml`). Registered by the `java`/`kotlin.jvm` +
 * `jacoco` plugin combination for JVM modules and by `Jacoco.kt` for Android ones; named here
 * only so the two tasks' relationship is expressed rather than spelled out twice.
 */
val FULL_COVERAGE_VERIFICATION_TASK_NAME = "jacocoTestCoverageVerification"

/** The report the full gate's notice reads, registered by the same plugins as the gate itself. */
val FULL_COVERAGE_REPORT_TASK_NAME = "jacocoTestReport"

/**
 * The two reporting tasks, one per gate. Everything either gate says about itself is said from
 * here rather than from the gate's own `doLast`, which Gradle's `onlyIf` can and did suppress —
 * see [CoverageGateNotice].
 */
val FULL_COVERAGE_NOTICE_TASK_NAME = "${FULL_COVERAGE_VERIFICATION_TASK_NAME}Notice"
val JVM_COVERAGE_NOTICE_TASK_NAME = "${JVM_COVERAGE_VERIFICATION_TASK_NAME}Notice"

/**
 * [executionData] with the instrumented half removed — the input that makes Tier 1's report and
 * gate provably independent of any emulator run.
 *
 * `.ec` is what AGP's on-device agent writes and what `Jacoco.kt`'s glob collects; the JVM agent
 * writes `.exec` (verified on disk: `build/jacoco/testDebugUnitTest.exec` and
 * `build/outputs/code_coverage/.../coverage.ec`). Filtering on the format's own extension rather
 * than on a directory name means this and `Jacoco.kt` share no literal that could drift apart.
 */
fun jvmOnly(executionData: FileCollection): FileCollection =
  executionData.filter { file -> !file.name.endsWith(".ec") }

/**
 * Whether [floor] can be enforced without an emulator, i.e. whether it belongs to Tier 1's
 * [JVM_COVERAGE_VERIFICATION_TASK_NAME] as well as to the full gate.
 *
 * **Declared per entry, not derived from the counter.** It used to read
 * `floor.counter == "BRANCH"`, on the reasoning that a LINE floor existed in this table only over
 * `@Composable` code and `@Composable` code only executes inside a real composition. That was true
 * of every entry at the time and stopped being true the moment `:core:model` got a LINE floor over
 * `SubsonicCredentials` — a plain JVM class with no Compose anywhere near it, whose floor the fast
 * tier can and should enforce. The proxy would have quietly moved a security control's floor into
 * the 45-minute tier and told the reader it "needs an emulator", which is simply false. A property
 * of the entry belongs on the entry.
 *
 * Every `true` below is a measurement, not a judgement: deleting the instrumented `.ec` and
 * running the whole build fails exactly those floors and passes every other entry in the table.
 *
 * Getting a value wrong still fails in the safe direction. A floor left at `false` that turns out
 * to need instrumented data fails Tier 1 loudly rather than passing quietly; one set to `true`
 * that did not need it is still enforced, just by Tier 2 alone.
 */
fun isEnforceableWithoutAnEmulator(floor: CoverageFloor): Boolean = !floor.requiresInstrumentedData

/**
 * Coverage floors, keyed by project path. Enforced by two tasks, one a strict subset of the other:
 * `jacocoTestCoverageVerification` evaluates **every** entry against merged JVM + instrumented
 * execution data (Tier 2 — the "Coverage gate" step in `.github/workflows/e2e.yml`), and
 * [JVM_COVERAGE_VERIFICATION_TASK_NAME] evaluates only the entries
 * [isEnforceableWithoutAnEmulator] selects, against JVM execution data alone (Tier 1 — the
 * "Coverage gate (JVM floors)" step in `.github/workflows/pr.yml`, and `check`). See `Jacoco.kt`
 * in build-logic for the mechanism this table supplies the numbers for: which
 * classes/execution-data a module's task reads is decided there; whether a given module has a
 * floor at all, and what it is, is decided here, once, rather than once per module.
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
 * That same family of defect has one further shape, which choosing the *number* cannot prevent
 * because it is a property of what a floor matches rather than of its minimum: a floor whose
 * entire matched class set carries zero counters of that floor's own kind passes at every minimum,
 * silently, because the resulting `0/0` COVEREDRATIO is `NaN` and JaCoCo returns "no violation"
 * for `NaN` outright. Nothing in this table can rule that out in advance, so it is detected after
 * the fact instead, on every verification run, by [UngatedClassChecker.warnVacuousFloors] below —
 * see that function's own doc for the decompiled evidence. It is also why the deliberate
 * zero-branch riders on two of the `:feature:setup` entries below stay safe: they ride *alongside*
 * classes that do have branches, so the floors carrying them still gate something real.
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
 * - **`:core:network`, `:core:testing`** — no `@Composable` code at all (neither module even
 *   applies the Compose convention plugin), and nothing within them that needs separating: a
 *   single `"BUNDLE"`-element BRANCH rule each (an aggregate across the whole module), measuring
 *   100% today (**56/56** and 6/6 real branches) against the full 0.90 target. `:core:network`'s
 *   branch population went 30 → 56 in Plan 2 Task 3, and the floor is not decorative there: with
 *   the six browse commands added but before the three `OK_WITH_NO_PAYLOAD` tests that reach
 *   their absent-container branches, the same module measured **46/56 = 0.8214** and this floor
 *   failed. That failure is what produced those three tests, which are also the only coverage of
 *   the asymmetric mapping rule (`albumList2`/`searchResult3`/`randomSongs` absent → empty,
 *   `album`/`scanStatus` absent → `SubsonicMalformedResponseException`).
 *
 * - **`:core:model`** — no `@Composable` code either, but **no longer one `"BUNDLE"` rule**. The
 *   final whole-branch review showed why that shape was wrong here: a BUNDLE aggregate over this
 *   module measured exactly one of its five classes, because all 10 branches live in
 *   `ServerCapabilities` and the other four classes contributed nothing to the ratio — deleting
 *   `SubsonicCredentials`'s password-redaction test left both gates green. It is now `"CLASS"`-
 *   element rules, so each gated class must clear its floor individually and a new class shows up
 *   as ungated instead of being silently absorbed. Both properties were verified by deletion and
 *   by adding a class.
 *
 *   Across all three modules, every gap Task 7 found was closable from the JVM alone, so it was
 *   closed rather than excused: see `ServerCapabilitiesTest`, `SubsonicClientTest`'s
 *   non-compliant-response and no-trailing-slash-baseUrl tests, and
 *   `OpenApiFixtureValidatorTest`'s `readSpec`/blank-path tests.
 *
 * - **`:feature:setup`** — four `"CLASS"`-element rules: three BRANCH, one per non-`@Composable`
 *   class here that has branches of its own, and one LINE over `SetupScreenKt`, this module's one
 *   Composable file (described at the end of this entry). The three BRANCH rules are not one
 *   aggregate rule across all three: their measured ratios are different enough (100%, 60%,
 *   87.5%) that a single blended floor would either sit so low it protects
 *   none of them individually, or so high the weakest one could never have passed it honestly.
 *   `SetupViewModel` (2/2, floor 0.90): `connect`'s own branches (the `InvalidUrl` check, the
 *   catch-clause dispatch), fully covered. `SetupViewModel$1` and `SetupViewModel$2` (3/5 each,
 *   floor 0.55): the compiled *default* `ping` and `fetchLibraries` constructor parameters'
 *   lambda classes — `SetupViewModelTest`'s real-refused-connection and real-`MockWebServer`
 *   cases close 3 of each one's 5 branches; the 2 still missing in each are, on the evidence, the
 *   Kotlin-compiler-generated "invalid continuation state" safety branches every suspend lambda's
 *   `invokeSuspend` carries — structurally unreachable from any legitimate call site, the same
 *   *kind* of compiler-owned gap BRANCH coverage has for Compose, just from the coroutines
 *   compiler plugin instead. `SetupFailureReasonKt` (7/8, floor 0.85): `toMessage`'s
 *   `when`-cascade, `SetupFailureReasonTest` covers all three members plus both sides of
 *   `Rejected`'s `detail` null/non-null branch; the one branch still missing is an artifact of how
 *   a `when` with no `else` over a sealed interface compiles, not an uncovered case.
 *
 *   And, from Task 8, a fourth rule of a different counter: `SetupScreenKt` LINE (55/57 =
 *   **0.9649**, floor 0.90). Task 7 could not gate this class at all — from the JVM alone it
 *   measured a real 0/54, and `0.00` is exactly the unfireable floor this project must not ship
 *   again. `FirstRunJourneyTest` composes the screen for real on an emulator, down both terminal
 *   states (a successful connect, and a rejection from the real server), which is what makes a
 *   real floor possible. The 2 lines still missing both belong to the `private` `SetupScreen`
 *   overload (2 missed / 49 covered on its own method counter): its declaration line and the
 *   closing brace of its `when (uiState)` block. What sits on those two lines was not decompiled,
 *   so nothing is claimed about it beyond one thing the report does rule out — this class carries
 *   no `SetupScreen$default` method at all, so it is not an uninvoked defaulted-parameter bridge.
 *   The BRANCH counter for the same class stands at 60/94 and is deliberately not gated, per the
 *   ruling above.
 *
 * - **`:core:designsystem`** — one `"CLASS"`-element LINE rule at the full `0.90`, covering all
 *   three of this module's classes, each measuring **1.0000** (`ThemeKt` 23/23, `ColorKt` 12/12,
 *   `TypeKt` 13/13). Task 7 could only gate `ThemeKt`, at `0.63` against a measured 0.652, because
 *   from the JVM alone nothing ever *composed* `MuPlayTheme` — the covered lines were only
 *   `LightColorScheme`/`DarkColorScheme`'s initializers running as a side effect of `ThemeTest`
 *   loading the class — and `TypeKt`/`ColorKt` sat at 0/13 and 12/12 with no rule at all. Task 8's
 *   emulator journey composes `MuPlayTheme` for real (`MainActivity` wraps the whole app in it),
 *   which is what closes all three. Still no BRANCH entry: the module's 30 measured branches are
 *   30 - 2 = 28 Compose codegen inside `MuPlayTheme`'s own body plus `colorSchemeFor`'s own 2
 *   (100% covered from `ThemeTest`), and there is no class-granular way to gate the latter alone
 *   — that would need JaCoCo's METHOD-element scoping, materially riskier machinery for a slice
 *   already fully covered.
 *
 * - **`:app`** — one `"BUNDLE"`-element LINE rule at `0.90` (measured **20/21 = 0.9524**), the one
 *   aggregate rule in this table, and the one place where that is the right shape rather than a
 *   compromise — with one cost, stated here rather than only in a report: `matchesFloor` below
 *   returns `true` unconditionally for a `"BUNDLE"`-element floor, so **no class in `:app` can
 *   ever be reported by `warnUngatedClasses`**. That is acceptable only while every class here is
 *   the same kind of wiring; the moment `:app` grows code with logic of its own, this entry has to
 *   become `"CLASS"`-element rules (and take on the synthetic-class exclusion that motivated
 *   `"BUNDLE"` in the first place). Task 7 deliberately left this module with no entry at all:
 *   from the JVM alone it
 *   measured 1/21 lines (only `MuPlayApplication`'s own body, via `MuPlayApplicationTest`), and
 *   both available numbers were dishonest — `0.00` is the unfireable floor this project has
 *   already shipped once, and anything above it would have failed a module with nothing wrong with
 *   it. Task 8's journey supplies the real data: `MainActivity` 5/5, `MuPlayAppKt` 10/10,
 *   `SetupRoute` 2/2, `MuPlayApplication` 1/1.
 *
 *   `"BUNDLE"`, not `"CLASS"`, for one specific reason. Every class in this module is the same
 *   *kind* of code — Compose/DI wiring, LINE-measured — so an aggregate here cannot hide a
 *   regression of a different kind behind a healthy average, which is the only thing the
 *   CLASS-element rules elsewhere in this table exist to prevent. What a `"CLASS"` rule at 0.90
 *   *would* do is fail on `MuPlayAppKt$MuPlayApp$$inlined$entryProvider$default$1` — a
 *   compiler-inlined synthetic holding one line, measuring 0/1, that no test can reach and no
 *   author-written code corresponds to. Excluding it by pattern is possible but leaves a
 *   permanent, unfixable ungated-class warning on every run (see [UngatedClassChecker]'s own doc
 *   on why a warning nobody can act on is worse than none). The BUNDLE rule covers it, still
 *   fails on anything real: dropping `MainActivity` alone takes the module to 15/21 = 0.714.
 *
 *   No BRANCH entry for `:app`, and that absence is the ruling above, not an oversight: its 18
 *   measured branches are entirely `MuPlayAppKt`'s Compose codegen (`MainActivity`,
 *   `MuPlayApplication` and `SetupRoute` contain no `if`/`when`/`?:`/`&&`/`||` of their own at
 *   all), so a BRANCH floor here would measure the Compose compiler.
 */
val coverageFloors: Map<String, List<CoverageFloor>> = mapOf(
  // Two `"CLASS"`-element rules, not one `"BUNDLE"` rule, and the difference is the whole point --
  // see this table's own doc above for what the BUNDLE form was hiding here.
  ":core:model" to listOf(
    // Two classes with branches of their own, each individually held to the floor (a
    // `"CLASS"`-element rule evaluates `minimum` per matched class, never as a blend):
    //
    //   `ServerCapabilities`  10/10 -- `supports`'s two null-safe chains, covered by
    //                         `ServerCapabilitiesTest`.
    //   `SearchResults`        6/6 -- `isEmpty`'s three-way `&&`, added by Plan 2 Task 3 and
    //                         covered by `SearchResultsTest`, which exercises each of the three
    //                         lists as the sole non-empty one. Measured 0/6 the moment the class
    //                         landed: `:core:network`'s `BrowseEndpointsTest` does assert
    //                         `isEmpty` on a real all-empty `search3` response, but that is a
    //                         different module's execution data and contributes nothing here --
    //                         the same trap `SubsonicCredentials` fell into below.
    //
    // Everything else in the list rides along, the same way `SetupUiState` rides along in
    // `:feature:setup`'s rule: they carry zero branches, so they cannot move any ratio (a
    // CLASS-element rule over a zero-counter class yields NaN, and JaCoCo reports no violation
    // for NaN), and including them is what keeps `warnUngatedClasses` from flagging them on every
    // run. That is honest for these specifically because they contain **no author-written
    // executable code at all** -- read them: `ServerInfo`, `MusicLibrary`, `Album`,
    // `AlbumWithSongs`, `Artist`, `Song` and `ScanStatus` are `data class` declarations with no
    // body, and `LibraryRole`/`AlbumListType` are `enum class` declarations whose only members are
    // constructor properties, so every line JaCoCo counts in them is compiler-generated
    // `equals`/`hashCode`/`toString`/`copy`/`values` plumbing. Gating that would be gating the
    // Kotlin compiler, the same argument this table already makes about Compose's synthetic
    // branches. If any of them grows a body, it needs a rule of its own -- which is exactly what
    // happened to `SearchResults`, and why it is listed above rather than here.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.model.ServerCapabilities",
        "app.muplay.model.SearchResults",
        "app.muplay.model.ServerInfo",
        "app.muplay.model.MusicLibrary",
        "app.muplay.model.LibraryRole",
        "app.muplay.model.Album",
        "app.muplay.model.AlbumWithSongs",
        "app.muplay.model.AlbumListType",
        "app.muplay.model.Artist",
        "app.muplay.model.ScanStatus",
        "app.muplay.model.Song",
      ),
    ),
    // 5/5 LINE -- `SubsonicCredentials`, the one class in this module with a hand-written member:
    // a `toString()` override whose only job is keeping a plaintext password out of logs. It has
    // no branches, so a BRANCH rule cannot gate it; LINE can, and must, because it is a security
    // control. Until `SubsonicCredentialsTest` was added it measured 0/5 here -- its only
    // assertion lived in `:core:network`, which is a different module's execution data -- and
    // deleting that assertion left both tiers green.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.model.SubsonicCredentials"),
    ),
  ),
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
    // 3/5 each -- the compiled default `ping` (SetupViewModel$1) and `fetchLibraries`
    // (SetupViewModel$2) constructor parameters' lambda classes. SetupViewModelTest's
    // real-refused-connection and real-MockWebServer cases close 3 of each one's 5 branches; the
    // 2 still missing in each are, on the evidence, the Kotlin-compiler-generated "invalid
    // continuation state" safety branches every suspend lambda's `invokeSuspend` carries --
    // structurally unreachable from any legitimate call site, the same *kind* of compiler-owned
    // gap BRANCH coverage has for Compose, just from the coroutines compiler plugin instead of
    // the Compose one. A CLASS-element rule holds each matched class to the minimum separately,
    // so listing both here gates both individually rather than blending them.
    //
    // `SetupViewModel*1` also matches `SetupViewModel$connect$1` (the compiled `connect` coroutine
    // body, `SetupViewModel.connect.1` once qualified), which carries no branches of its own --
    // harmless, since a CLASS-element rule over a zero-counter class yields NaN and JaCoCo reports
    // no violation, and it keeps that class from showing up as ungated.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.55"),
      includes = listOf(
        "app.muplay.setup.SetupViewModel*1",
        "app.muplay.setup.SetupViewModel*2",
      ),
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
    // 55/57 = 0.9649 LINE -- the one Compose-bearing file in this module, gated on LINE rather
    // than BRANCH per the ruling in this table's own doc. Reachable only because Task 8's
    // FirstRunJourneyTest composes SetupScreen for real on an emulator, down both of its terminal
    // states; from the JVM alone this same class measures 0/54. The 2 lines still missing are the
    // private SetupScreen overload's declaration line and its `when` block's closing brace -- see
    // coverageFloors's own doc above for what is and is not known about them. Its 60/94 BRANCH is
    // deliberately left ungated: Compose codegen, not author logic.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.setup.SetupScreenKt"),
      // Composed only by FirstRunJourneyTest, on a device. 0/54 from the JVM alone.
      requiresInstrumentedData = true,
    ),
  ),
  // `:core:database` (Plan 2 Tasks 1-5). Nine rules, because the module now holds three different
  // kinds of code and one blended floor would hide a regression in any of them behind the others.
  //
  // Measured, all of them, from a merged JVM + instrumented report (see task-2's transcript for
  // Tasks 1-2's numbers; task-4's own transcript for Task 4's; task-5's report and fix round 1 for
  // what follows): KeystoreCipher BRANCH 4/4 and LINE 15/15; CredentialStore BRANCH 16/16 and
  // LINE 37/37; MuPlayDatabase 1/1, DataModule 10/10, MediaProgressEntity 9/9, LibraryDao 5/5,
  // LibraryEntity 5/5, NotConfiguredException 2/2 LINE; LibraryRepository BRANCH 2/2 and LINE
  // 14/15; SubsonicSourceProvider BRANCH 2/2 and LINE 5/5; the coroutine/`Flow.map` codegen
  // classes at 0.50-0.67 LINE; MirrorMapper BRANCH 16/16 and LINE 75/75 (grew from 12/12 and
  // 72/72 in fix round 1's `searchPattern`); ArtistEntity/AlbumEntity/SongEntity LINE 8/8, 11/11,
  // 15/15; BrowseDao BRANCH 12/12 (new in fix round 1 -- the three `require` preconditions on
  // `replaceLibraryContents`) and LINE 23/23; MirrorReplacement LINE 7/7; BrowseRepository BRANCH
  // 4/4 (shrank from 6/6 once the LIKE-pattern logic moved into `MirrorMapper`) and LINE 17/17.
  ":core:database" to listOf(
    // The only floor in this module Tier 1 could enforce through Task 4, and one of two as of
    // Task 5. KeystoreCipher takes a `SecretKey` rather than fetching one from AndroidKeyStore
    // precisely so its cryptographic contract is testable off-device, and KeystoreCipherTest
    // reaches 4/4 branches from a plain JVM run. Gating it in the fast tier is the whole payoff
    // of that design.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.KeystoreCipher"),
    ),
    // `MirrorMapper` is the second: a plain `object` with no injected collaborators and no
    // Android/SQLite dependency, so its own JVM `MirrorMapperTest` reaches its branches with no
    // emulator -- 12/12 as originally submitted, 16/16 after fix round 1 added `searchPattern`
    // (moved here from `BrowseRepository.search` so its trim/blank/escape logic is JVM-testable).
    // `MirrorMapper*` (not the bare name) because `artistEntities`'s `sortedBy` call compiles to a
    // nested lambda class (`MirrorMapper$artistEntities$lambda$2$$inlined$sortedBy$1`) that a bare
    // `"app.muplay.database.MirrorMapper"` include would not match at all and `warnUngatedClasses`
    // would then flag on every run; it carries 0 branches of its own (JaCoCo's isNaN pass) so
    // widening the pattern costs nothing. `MirrorMapper.album(entity)` and `.artist(entity)` --
    // the reverse direction only `BrowseRepository` originally called -- measured 0/17 LINE from
    // `MirrorMapperTest` alone until this task added direct tests for both, specifically so this
    // class could stay JVM-measurable rather than moving to the `requiresInstrumentedData` rule
    // below (confirmed by physically removing the instrumented `.ec` file and re-running
    // `jacocoTestReport`; see task-5-report.md). Fix round 1 repeated that same physical-`.ec`-
    // removal check for `searchPattern` and for the second, disjoint-fixture forward/reverse
    // tests it added -- still 0 lines/branches lost with the emulator's data absent.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.MirrorMapper*"),
    ),
    // The three mirror row entities -- unlike `LibraryEntity`/`MediaProgressEntity` below, these
    // have no branches of their own (plain `data class`es) but *are* JVM-measurable: `MirrorMapper`
    // constructs all three directly (`albumEntity`, `songEntity`, `artistEntities`), and
    // `MirrorMapperTest` also constructs an `ArtistEntity` by hand to test `MirrorMapper.artist`
    // field by field. Measured 8/8, 11/11, 15/15 LINE with the instrumented `.ec` file physically
    // absent -- not inferred from the merged report, which cannot distinguish "reachable from the
    // JVM" from "reachable only from the emulator".
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.entity.ArtistEntity",
        "app.muplay.database.entity.AlbumEntity",
        "app.muplay.database.entity.SongEntity",
      ),
    ),
    // CredentialStore's own author-written branches: 16/16 after Task 2 added the partial-write,
    // missing-key and unopenable-blob recovery paths. Those five branches were genuinely
    // untested rather than codegen -- the class measured 11/16 before them -- which is why this
    // is a BRANCH rule and not an excuse for one.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.CredentialStore"),
      requiresInstrumentedData = true,
    ),
    // LibraryRepository's only author-written branch, confirmed at the method level (not
    // assumed): both of the 2 branches this measures are JaCoCo's own instrumentation of
    // `hasUnassignedLibraries`'s `.isNotEmpty()` boolean check (`unassignedLibrariesAreReported...`
    // exercises it both true and false). Every other method here is either a straight-line
    // suspend delegation to `LibraryDao` or the `Flow.map` in `libraries`, whose own lambda
    // classes are covered by the LINE catch-all below, not this rule. Measured 2/2.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.LibraryRepository"),
      requiresInstrumentedData = true,
    ),
    // `SubsonicSourceProvider.current`'s `credentialStore.load() ?: throw NotConfiguredException()`
    // -- exactly the two branches `refreshingWithNoStoredCredentialsFailsLoudly` (the throw) and
    // every other `LibraryRepositoryTest` (the pass-through) exist to cover. Measured 2/2.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.SubsonicSourceProvider"),
      requiresInstrumentedData = true,
    ),
    // `BrowseRepository`'s branches, post fix-round-1: `search` delegates its LIKE-pattern
    // construction to `MirrorMapper.searchPattern` now (the blank-query short circuit moved with
    // it), so what remains here is the `?:` on that call's `null` result. Every method is a
    // straight-line delegation to `BrowseDao`/`SubsonicSourceProvider` through `MirrorMapper` --
    // proven to actually forward its arguments rather than hardcode them by 13 mutations in the
    // original submission plus further mutations across fix round 1's rewritten search tests,
    // none of which this BRANCH rule alone could tell apart from a hardcoded constant (a mutated
    // `observeArtists(1)` still compiles to the same branch count as `observeArtists(libraryId)`).
    // See task-5-report.md for the per-mutation proof. Measured 6/6 originally, 4/4 after the
    // pattern logic moved out.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.BrowseRepository"),
      requiresInstrumentedData = true,
    ),
    // `BrowseDao`'s only author-written conditionals, new in fix round 1: the three `require`
    // preconditions in `replaceLibraryContents` that reject a batch carrying a row scoped to a
    // different library than the one being reconciled (N-5 -- demonstrated live as a defect that
    // silently wrote rows into the wrong library and reported that it had written none).
    // `replaceLibraryContentsRejectsARowScopedToADifferentLibrary`,
    // `...RejectsAMismatchOnAnyOneOfTheThreeLists` and `...AcceptsAWhollyEmptyBatch` exercise both
    // sides of all three (and the vacuous `all {}`-on-empty case that must NOT throw). Measured
    // 12/12; Room-generated DAO code, instrumented-only like every other DAO here.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.dao.BrowseDao"),
      requiresInstrumentedData = true,
    ),
    // Everything whose value is "did this line run at all": the Room database class, the Hilt
    // providers, the entities that need an emulator to be reached at all (LibraryEntity,
    // MediaProgressEntity -- unlike the three mirror entities above, nothing JVM-side ever
    // constructs these), `LibraryDao`, the Task 4/5 classes with no branch of their own
    // (LibraryEntity, NotConfiguredException, MirrorReplacement), and every class above that also
    // has its own BRANCH rule (including `BrowseDao`, whose own `require` branches are gated
    // above; it stays listed here too for its LINE ratio, the same dual-listing `LibraryRepository`
    // and `SubsonicSourceProvider` already have). No separate BRANCH entry for
    // LibraryDao/LibraryEntity/MediaProgressEntity/MirrorReplacement -- they contain no
    // author-written conditional, so a BRANCH rule would match only zero-total counters and pass
    // silently at every minimum through JaCoCo's isNaN branch.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.MuPlayDatabase",
        "app.muplay.database.CredentialStore",
        "app.muplay.database.CredentialStore*Companion",
        "app.muplay.database.di.DataModule",
        "app.muplay.database.entity.MediaProgressEntity",
        "app.muplay.database.entity.LibraryEntity",
        "app.muplay.database.dao.LibraryDao",
        "app.muplay.database.dao.BrowseDao",
        "app.muplay.database.dao.MirrorReplacement",
        "app.muplay.database.LibraryRepository",
        "app.muplay.database.SubsonicSourceProvider",
        "app.muplay.database.BrowseRepository",
        "app.muplay.database.NotConfiguredException",
      ),
      requiresInstrumentedData = true,
    ),
    // The Kotlin compiler's own output for `suspend` bodies and for `Flow.map`: the
    // `save$2`/`clear$2`/`CredentialStore$special$$inlined$map$1*` family, `LibraryRepository`'s
    // own `Flow.map` lambda (`LibraryRepository$special$$inlined$map$1*`) and its
    // `refreshFromServer`/`hasUnassignedLibraries` suspend continuations, and now
    // `BrowseRepository`'s four `Flow.map` lambdas (`artists`/`albums`/`albumsByArtist`/`songs`,
    // each compiling to its own `$$inlined$map$1`/`$1$2` pair) plus its `album`/`search`/
    // `coverArtUrl` suspend continuations. Measured 0.50-0.67 LINE across all three families
    // alike, floored at 0.50 -- a real number this run produced, not a round one.
    // `SubsonicSourceProvider*` rides along in the same rule: its own `current$1` continuation
    // carries no LINE counter of its own (0/0, JaCoCo's isNaN branch, see `warnVacuousFloors`'s
    // own doc for why that is not the same thing as "excluded"), so it costs nothing to include
    // and keeps this rule's reasoning -- "one rule for every suspend/Flow.map artefact in this
    // module" -- true without a second near-duplicate rule.
    //
    // Gated rather than excluded, and gated low rather than not at all. Excluding them the way
    // Room's `_Impl` and Hilt's generated types are excluded would be defensible, but those have
    // dedicated, stable name shapes; a pattern broad enough to catch every coroutine artefact
    // would also catch author-written nested classes, and this project would rather carry an
    // honest low floor than a silent hole. Leaving them ungated instead would make
    // `warnUngatedCoverage` print lines on every run forever, which is how a warning mechanism
    // dies.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.50"),
      includes = listOf(
        "app.muplay.database.CredentialStore*",
        "app.muplay.database.LibraryRepository*",
        "app.muplay.database.SubsonicSourceProvider*",
        "app.muplay.database.BrowseRepository*",
      ),
      excludes = listOf(
        "app.muplay.database.CredentialStore",
        "app.muplay.database.CredentialStore*Companion",
        "app.muplay.database.LibraryRepository",
        "app.muplay.database.SubsonicSourceProvider",
        "app.muplay.database.BrowseRepository",
      ),
      requiresInstrumentedData = true,
    ),
  ),
  // See coverageFloors's own doc above for the exact measurements and why CLASS-element.
  // ThemeKt 23/23, ColorKt 12/12, TypeKt 13/13 -- all 1.0000 LINE once the emulator journey
  // composes MuPlayTheme (MainActivity wraps the whole app in it). Task 7 could only gate ThemeKt,
  // at 0.63, and left the other two with no rule at all.
  ":core:designsystem" to listOf(
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.designsystem.theme.ThemeKt",
        "app.muplay.designsystem.theme.ColorKt",
        "app.muplay.designsystem.theme.TypeKt",
      ),
      // MuPlayTheme is composed only by the emulator journey; from the JVM alone ThemeKt measures
      // 0.65 and TypeKt 0.00.
      requiresInstrumentedData = true,
    ),
  ),
  // 20/21 = 0.9524 LINE across the whole module. The one BUNDLE-element rule in this table -- see
  // coverageFloors's own doc above for why an aggregate is the right shape here specifically, and
  // why there is no BRANCH entry.
  // `requiresInstrumentedData`: every line here is Compose/DI wiring the emulator journey runs;
  // from the JVM alone this module measures 1/21 = 0.04.
  ":app" to listOf(
    CoverageFloor(counter = "LINE", minimum = BigDecimal("0.90"), requiresInstrumentedData = true),
  ),
)

/**
 * Every function below is deliberately self-contained (no reference to any other top-level
 * `val`/`fun` in this script) inside a genuine Kotlin `object`, not left as plain script-level
 * `private fun`s: a `.gradle.kts` script's own top-level declarations are actually *members* of
 * an implicit script class (confirmed earlier by `const val` being rejected outside "top level,
 * named objects, companion objects" -- a real top-level Kotlin file allows it, a script body
 * does not), so calling one script-level `private fun` from inside a task's `doLast { }` closure
 * implicitly captures a reference to *the whole script object* to make that call. The
 * configuration cache refuses to serialize that ("cannot serialize Gradle script object
 * references") -- confirmed empirically: this was still failing under `--configuration-cache`
 * even after every `Task`/`Provider` reference elsewhere in this function had already been
 * replaced with a plain `File` (see the comment on `reportXmlFile` below, and task-7-report.md
 * for the full sequence). A genuine Kotlin `object` is its own independent, stateless class --
 * calling `UngatedClassChecker.warnUngatedCoverage(...)` from `doLast { }` references only that
 * object's own singleton, never this script, which the configuration cache serializes fine.
 */
/**
 * Every `COVERAGE:` warning this build raises, emitted so it is visible in the one place that
 * decides merges.
 *
 * `logger.warn` alone puts these in a thousand-line CI log where nobody reads them — which is
 * exactly how a module could land with no coverage floor and a green build. GitHub Actions'
 * workflow-command form (`::warning::…`) surfaces the same text as an annotation on the run and
 * the pull request. Both are emitted: the plain line for a local build and for any other CI, the
 * annotation only when `GITHUB_ACTIONS` is set, so a developer's terminal does not fill with
 * `::warning::` noise.
 *
 * Annotations must be one line — `%0A` is the only way to encode a newline in one — and every
 * message this build produces already is, being built by string concatenation.
 */
object CoverageWarning {
  /**
   * [annotate] is `true` for a condition someone should act on and `false` for one this build
   * expects on every run. Both still get a `logger.warn`; only the first becomes an annotation.
   * The distinction matters because an annotation that appears on every pull request stops being
   * read, which is the same failure as the log line it was meant to escape.
   */
  fun emit(logger: Logger, annotate: Boolean, message: String) {
    logger.warn(message)
    if (annotate) logger.warn("::warning::$message")
  }
}

object UngatedClassChecker {
  /**
   * How many matched class names a vacuous-floor warning spells out before it summarises the rest.
   * A `"BUNDLE"`-element floor matches every class in its module, so an uncapped list would bury
   * the sentence that matters under a wall of names whenever such a floor goes vacuous.
   */
  private const val MAX_NAMED_CLASSES_PER_WARNING = 6

  /**
   * A JaCoCo `includes`/`excludes` glob pattern (`*` = any sequence, everything else literal) as a
   * [Regex]. Reimplements enough of `org.jacoco.core.analysis.WildcardMatcher` to answer "would this
   * pattern match that class" from plain Kotlin, for [matchesFloor]'s own use — not to
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
   * (binary name with both `/` and `$` replaced by `.` — see [parseClassCoverage]), the same form
   * `includes`/`excludes` patterns are matched against.
   */
  private fun matchesFloor(qualifiedClassName: String, floor: CoverageFloor): Boolean {
    if (floor.element == "BUNDLE") return true
    val included = floor.includes.isEmpty() || floor.includes.any { wildcardToRegex(it).matches(qualifiedClassName) }
    val excluded = floor.excludes.any { wildcardToRegex(it).matches(qualifiedClassName) }
    return included && !excluded
  }

  /**
   * One `<class>` element of a `jacocoTestReport` XML report, reduced to what the two checks below
   * need from it: its name in both forms JaCoCo uses, and its own `missed`/`covered` figures keyed
   * by counter type (`"BRANCH"`, `"LINE"`, `"INSTRUCTION"`, ...). A counter type the class carries
   * no `<counter>` element for reads back as `0` — which is the honest answer, and the exact case
   * [warnVacuousFloors] exists to notice.
   */
  private class ClassCoverage(
    /** JaCoCo's `name` attribute verbatim, e.g. `app/muplay/setup/SetupViewModel$1`. */
    val binaryName: String,
    /** [binaryName] in JaCoCo's "qualified" form — both `/` and `$` replaced by `.`. */
    val qualifiedName: String,
    private val missedByCounter: Map<String, Int>,
    private val coveredByCounter: Map<String, Int>,
  ) {
    /** This class's own covered count for [counter]. */
    fun covered(counter: String): Int = coveredByCounter[counter] ?: 0

    /** This class's own `missed + covered` total for [counter] — the denominator of COVEREDRATIO. */
    fun total(counter: String): Int = (missedByCounter[counter] ?: 0) + covered(counter)
  }

  /**
   * Every `<class>` element in [xmlFile] (a module's own `jacocoTestReport` XML output), parsed
   * once so both checks below read the same figures instead of walking the document twice.
   *
   * Assigns per counter type rather than accumulating, deliberately: `getElementsByTagName` returns
   * every *descendant* of a `<class>`, which includes each of its `<method>` elements' own
   * counters, so accumulating would double-count. JaCoCo's report DTD declares
   * `<!ELEMENT class (method*, counter*)>` (read out of `org/jacoco/report/xml/report.dtd` in
   * `org.jacoco.report-0.8.12.jar`), so a class's own totals are always the *last* counters in its
   * own subtree and last-assignment-wins leaves exactly them in place — confirmed against this
   * project's real reports (`:feature:setup`'s `SetupViewModel` element: four `<method>` children,
   * then six `<counter>` children). Nor can a method-level counter leak in for a type the class
   * itself does not report, since a class node's counters are the aggregate of its own methods' —
   * also checked across every report this project produces: no `<method>` carries a counter type
   * absent from its enclosing `<class>`.
   */
  private fun parseClassCoverage(xmlFile: File): List<ClassCoverage> {
    val factory = DocumentBuilderFactory.newInstance()
    // JaCoCo's own XML report declares a DOCTYPE pointing at its DTD on jacoco.org; parsing must
    // never depend on fetching that over the network (offline dev machines, and CI runners with no
    // reason to trust this parse with network access at all) -- the class/counter data below does
    // not depend on DTD validation succeeding.
    factory.isValidating = false
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val document = factory.newDocumentBuilder().parse(xmlFile)

    val classNodes = document.getElementsByTagName("class")
    val classes = mutableListOf<ClassCoverage>()
    for (i in 0 until classNodes.length) {
      val classElement = classNodes.item(i) as Element
      val binaryName = classElement.getAttribute("name") // e.g. app/muplay/setup/SetupScreenKt
      val missedByCounter = mutableMapOf<String, Int>()
      val coveredByCounter = mutableMapOf<String, Int>()
      val counterNodes = classElement.getElementsByTagName("counter")
      for (j in 0 until counterNodes.length) {
        val counterElement = counterNodes.item(j) as Element
        val counterType = counterElement.getAttribute("type")
        missedByCounter[counterType] = counterElement.getAttribute("missed").toInt()
        coveredByCounter[counterType] = counterElement.getAttribute("covered").toInt()
      }
      classes += ClassCoverage(
        binaryName = binaryName,
        qualifiedName = binaryName.replace('/', '.').replace('$', '.'),
        missedByCounter = missedByCounter,
        coveredByCounter = coveredByCounter,
      )
    }
    return classes
  }

  /**
   * Finding 2 recurring one level down: `coverageFloors` gates modules, and a module simply absent
   * from it now warns loudly (see the `logger.warn` below) — but a module *present* in the table can
   * still contain a class none of its own rules actually cover (`:feature:setup` is in the table,
   * `SetupScreenKt` — 94 branches, 54 lines, 0% on both — is covered by none of its three `"CLASS"`
   * -element rules, and the only signal that fact carried before this function existed was prose in
   * `coverageFloors`'s own doc comment). Warns, by name and measured coverage, for every class
   * [floors] does not cover. Never *fails* the build: a genuinely-uncovered class can be the right
   * call today (`.00%` is unfireable for the same reason a `0.00` floor is — see `coverageFloors`'s
   * own doc), the same way an absent module is; the point is that none of this is ever silent.
   *
   * Classes with zero measured branches *and* zero measured lines (`SetupUiState.Idle` and similar
   * plain `data object`s) are skipped: there is nothing in them for any rule to gate, so flagging
   * them would only be noise on every run.
   */
  private fun warnUngatedClasses(
    modulePath: String,
    classes: List<ClassCoverage>,
    floors: List<CoverageFloor>,
    onGitHubActions: Boolean,
    logger: Logger,
  ) {
    for (classCoverage in classes) {
      if (floors.any { matchesFloor(classCoverage.qualifiedName, it) }) continue

      val branchTotal = classCoverage.total("BRANCH")
      val lineTotal = classCoverage.total("LINE")
      if (branchTotal == 0 && lineTotal == 0) continue

      val branchSummary =
        if (branchTotal > 0) "branch ${classCoverage.covered("BRANCH")}/$branchTotal" else "branch n/a"
      val lineSummary =
        if (lineTotal > 0) "line ${classCoverage.covered("LINE")}/$lineTotal" else "line n/a"
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath's ${classCoverage.binaryName} is not covered by any rule in " +
          "`coverageFloors` (root build.gradle.kts) -- measured $branchSummary, $lineSummary. If " +
          "that is deliberate (a genuinely 0%-covered Composable file, say), no action needed; if " +
          "a rule should cover this class and does not, its includes/excludes pattern is probably " +
          "wrong.",
      )
    }
  }

  /**
   * [floor] rendered for a warning message — every field that identifies which entry of
   * `coverageFloors` is being talked about. `excludes` is printed only when it is non-empty, since
   * an empty one is both the overwhelmingly common case and a no-op.
   */
  private fun describeFloor(floor: CoverageFloor): String = buildString {
    append("(counter=${floor.counter}, minimum=${floor.minimum}, element=${floor.element}")
    append(", includes=${floor.includes}")
    if (floor.excludes.isNotEmpty()) append(", excludes=${floor.excludes}")
    append(")")
  }

  /**
   * The same finding one level *inside* a gated class set: a floor whose matched classes have no
   * counters of the floor's own kind gates nothing at all, and JaCoCo will never say so.
   *
   * `org.jacoco.report.check.Limit.check` reads its rule's `COVEREDRATIO` and, when that value is
   * `NaN`, returns `null` — JaCoCo's own encoding of "no violation" — *before* it ever compares
   * against `minimum`. `CounterImpl.getCoveredRatio` computes that ratio as
   * `covered / (missed + covered)`, so a matched set carrying zero counters of the rule's kind
   * yields `0.0 / 0.0` = `NaN`. Such a rule therefore passes at *every* minimum, silently, under
   * every invocation mode — categorically unlike untested code, whose ratio is a real `0.0` and
   * fails loudly. Both facts were read straight out of the bytecode (`javap -c` on
   * `org.jacoco.report`/`org.jacoco.core`, byte-identical in 0.8.12 — the version
   * `libs.versions.toml` pins and `configureJacoco` sets as `toolVersion` — and in 0.8.14), not
   * inferred from JaCoCo's documentation.
   *
   * That NaN path is *deliberately* load-bearing in `coverageFloors` today: the `SetupUiState` /
   * `SetupFailureReason` riders carry no branches of their own and pass through it on every single
   * run. Which is exactly why this checks per **floor** and never per zero-total class — a
   * per-class warning would fire on those riders on every run and be trained away as noise, and a
   * warning nobody reads is a warning that is not there. A floor is doing its job as long as *at
   * least one* class it matches has a nonzero total for **that floor's own `counter`**: not the
   * other counter (a BRANCH floor over branchless-but-line-rich classes still gates nothing), and
   * not "either of them".
   *
   * Warns, never fails, for the same reason nothing else in this object fails: a floor with nothing
   * to gate can be the right call today. What must never happen is that a floor which *used* to
   * gate something stops, and reads exactly like a passing one while it does.
   */
  private fun warnVacuousFloors(
    modulePath: String,
    classes: List<ClassCoverage>,
    floors: List<CoverageFloor>,
    onGitHubActions: Boolean,
    logger: Logger,
  ) {
    for (floor in floors) {
      // A "BUNDLE"-element floor matches every class in the module unconditionally (see
      // `matchesFloor`), so this aggregates over the whole module for those, which is exactly what
      // JaCoCo's own BUNDLE rule measures.
      val matched = classes.filter { matchesFloor(it.qualifiedName, floor) }
      if (matched.sumOf { it.total(floor.counter) } > 0) continue

      val matchedNames = matched.map { it.binaryName }.sorted()
      val shownNames = matchedNames.take(MAX_NAMED_CLASSES_PER_WARNING)
      val elided = matchedNames.size - shownNames.size
      val shownSummary = shownNames.joinToString(", ") + (if (elided > 0) ", +$elided more" else "")
      val matchedSummary = when (matchedNames.size) {
        0 -> "it matches no class in this module at all"
        1 -> "the one class it matches ($shownSummary) has zero ${floor.counter} counters"
        else -> "all ${matchedNames.size} classes it matches ($shownSummary) have zero " +
          "${floor.counter} counters"
      }
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath's floor ${describeFloor(floor)} in `coverageFloors` (root " +
          "build.gradle.kts) currently enforces nothing: $matchedSummary, so the floor's own " +
          "${floor.counter} total across everything it matches is 0. JaCoCo reports no violation " +
          "for it and never will, at this or any other minimum -- its COVEREDRATIO is " +
          "covered/(missed+covered), which is NaN when the total is 0, and `Limit.check` returns " +
          "\"no violation\" outright for NaN (see `warnVacuousFloors` in this file for the " +
          "decompiled evidence). Not a build failure -- a floor with nothing to gate can be " +
          "deliberate -- but a floor that has *lost* what it used to gate is indistinguishable " +
          "from one that never had any, so: if this is meant to gate real ${floor.counter} " +
          "coverage, that coverage is gone; if it is not, say so where the floor is declared.",
      )
    }
  }

  /**
   * Warns about both shapes of silently-ungated coverage a module in `coverageFloors` can carry: a
   * class no floor covers ([warnUngatedClasses]) and a floor that covers only classes with nothing
   * of its own counter to measure ([warnVacuousFloors]). Reads [xmlFile] — the `jacocoTestReport`
   * task's own XML output for this module; `dependsOn("jacocoTestReport")` below means it is
   * present for any ordinary invocation, but `./gradlew ... -x jacocoTestReport` against a module
   * that has never built one can still hand this a path that does not exist.
   *
   * Neither check ever fails the build, and the missing-report case is not treated as "nothing to
   * warn about" either — see each function's own doc.
   */
  fun warnUngatedCoverage(
    modulePath: String,
    reportTaskName: String,
    xmlFile: File,
    allFloors: List<CoverageFloor>,
    evaluatedFloors: List<CoverageFloor>,
    onGitHubActions: Boolean,
    logger: Logger,
  ) {
    if (!xmlFile.isFile) {
      // Loud, not a quiet `return`: an absent report reads exactly like "nothing to warn about" to
      // anyone who has not read this function's own source, which is the same silent-gate shape
      // this entire mechanism was built to close. `-x`-style invocations that skip the report task
      // are the one way the caller's own `dependsOn` cannot guarantee this file exists.
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath's $reportTaskName XML ($xmlFile) does not exist -- neither the " +
          "ungated-class check nor the vacuous-floor check could run at all. If $reportTaskName " +
          "was deliberately skipped (e.g. -x $reportTaskName), this module's per-class coverage " +
          "is unverified this run; run $reportTaskName first to restore it.",
      )
      return
    }

    val classes = parseClassCoverage(xmlFile)
    // Two different floor lists, because the two checks answer two different questions and each
    // tier may legitimately evaluate only part of the table.
    //
    // "Is this class gated by anything at all?" is a question about the *whole* table -- a class
    // covered only by a LINE floor is properly gated even in the tier that evaluates BRANCH floors
    // alone, and flagging it there would be false. So [warnUngatedClasses] gets [allFloors].
    //
    // "Did the floor I just evaluated actually gate something?" is a question about *this* tier's
    // own work, and it is the one that keeps a tier from claiming more than it checked: a floor
    // matching no class passes at every minimum (see [warnVacuousFloors]'s own doc), so a gate
    // reporting "enforced all N of its floors" without this check is asserting something it never
    // verified. So [warnVacuousFloors] gets [evaluatedFloors].
    warnUngatedClasses(modulePath, classes, allFloors, onGitHubActions, logger)
    warnVacuousFloors(modulePath, classes, evaluatedFloors, onGitHubActions, logger)
  }
}


/**
 * Everything either coverage gate *reports* — the tier split, the un-floored module warning, and
 * the two [UngatedClassChecker] checks. All of it, deliberately, in one place that is not a task
 * action of the verification task itself.
 *
 * **Why not `doLast` on the verification task, which is where all of this used to live.** Gradle's
 * own `JacocoReportBase` constructor calls
 * `onlyIf("Any of the execution data files exists", spec)` where the spec is
 * `Iterables.any(getExecutionData(), File::exists)` — read out of `gradle-jacoco-9.7.1.jar`'s
 * bytecode, not assumed. `JacocoReport` and `JacocoCoverageVerification` both extend that class,
 * so both carry it. An `onlyIf` short-circuits a task *before* its actions run, and
 * `outputs.upToDateWhen { false }` does not defeat it — that guard answers a different question
 * (is this task up to date) than `onlyIf` does (should this task run at all). The consequence was
 * measured, not theorised: with `core/model/src/test` moved aside, an ordinary whole-project
 * `./gradlew jacocoJvmCoverageVerification` printed
 * `:core:model:jacocoJvmCoverageVerification SKIPPED`, evaluated no rule, printed no `COVERAGE:`
 * line at all, and exited 0. Both tiers had that hole.
 *
 * So the reporting lives in its own plain task, which has no `onlyIf` of its own and is wired as a
 * **finalizer** of the verification task. A finalizer runs even when the task it finalizes is
 * skipped by `onlyIf` — verified directly with a throwaway probe build before this was written,
 * not inferred from the documentation — which is what makes this announcement structurally
 * incapable of going quiet. It is also why the notice can speak in the past tense: it runs after
 * the gate, whether the gate ran, passed, failed or was skipped.
 *
 * A genuine Kotlin `object`, for the reason [UngatedClassChecker]'s own doc gives: a script-level
 * `private fun` called from inside `doLast { }` captures the whole script object, which the
 * configuration cache refuses to serialize.
 */
object CoverageGateNotice {
  /**
   * Says what [verificationTaskName] did and did not evaluate for [modulePath], and then runs the
   * per-class checks that decide whether saying "enforced" was true.
   *
   * [executionData] is resolved by the caller at execution time and tested with `File.exists()`,
   * mirroring Gradle's own `onlyIf` spec exactly (see this object's doc) rather than approximating
   * it: this function's whole job is to describe a decision Gradle already made, so it must ask
   * the same question Gradle asked.
   *
   * Warns rather than failing, in every state, because that is the posture of every other signal
   * in this build and because an absent `.exec` is a legitimate state — a module whose tests are
   * all `@Disabled`, or one that has none yet. What must never happen is that it is quiet.
   */
  fun report(
    modulePath: String,
    hasJvmTestSource: Boolean,
    verificationTaskName: String,
    reportTaskName: String?,
    reportXmlFile: File?,
    allFloors: List<CoverageFloor>,
    evaluatedFloors: List<CoverageFloor>,
    executionData: Collection<File>,
    deferredTo: String?,
    perClassChecksRunBy: String?,
    onGitHubActions: Boolean,
    logger: Logger,
  ) {
    if (allFloors.isEmpty()) {
      // Loud, not silent: a module simply absent from `coverageFloors` produced no signal at all
      // before this existed -- `:app` was the documented case when that was written, and nothing
      // stopped (or announced) a *future* module landing in `settings.gradle.kts` with no floor
      // and no comment explaining why. Every module has a measured floor today, `:app` included,
      // so this fires for nothing right now; it exists for the next module to arrive. Not a build
      // failure -- an ungated module is a real, sometimes-correct state.
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath has no entry in `coverageFloors` (root build.gradle.kts) -- its " +
          "branch and line coverage is unenforced in both tiers. If that is deliberate, say so " +
          "at that table -- every entry there documents the choices behind its own numbers, " +
          "including the counters it deliberately does not gate; if not, add a measured floor.",
      )
      return
    }

    val presentExecutionData = executionData.filter { it.exists() }
    if (presentExecutionData.isEmpty() && evaluatedFloors.isEmpty() && deferredTo != null &&
      !hasJvmTestSource
    ) {
      // Skipped, but this gate was owed nothing: every one of this module's floors needs
      // instrumented data, so the tier below has no rule of its own to evaluate whether execution
      // data exists or not. `:core:database` is the module that motivated this branch -- Room
      // needs the Android framework's SQLite and Robolectric is banned project-wide, so its Room
      // and DAO coverage can never come from a JVM test. [Corrected on a Task 4 re-review: this
      // comment used to claim the module "has no JVM tests at all and never will", which was true
      // when it was written and stopped being true the moment Task 2 added `KeystoreCipherTest`
      // (its cryptographic contract needs no Android framework, only Room and the DAO layer do) --
      // the module carries real JVM tests today for the code that needs no framework, and nobody
      // revisited this prose. The same false premise had propagated into `ci/mutation-probes.sh`'s
      // own header; both are fixed together.]
      //
      // Split out from the general no-data branch below deliberately. That branch says "usually
      // this means the module's tests did not run or no longer exist", which for a module in this
      // shape is both wrong and permanent: it would fire on every Tier 1 build forever. This
      // project has already ruled, when deciding how to warn about vacuous floors, that a warning
      // which fires constantly becomes noise and takes the mechanism down with it. The state is
      // still announced -- silence is never the answer here -- but it is announced as the
      // unremarkable fact it is, and it still names the gate that does the work.
      //
      // `!hasJvmTestSource` is load-bearing and was missing from the first version of this branch.
      // Without it the condition tested for *absent execution data* while the message asserted
      // *absent test sources*, so a module that HAD JVM tests and then lost them -- deleted,
      // renamed, all `@Disabled` -- got this reassuring message instead of the loud one. A review
      // reproduced exactly that by deleting `:core:designsystem`'s `ThemeTest`: it printed "the
      // expected steady state ... not a missing-test warning" and exited 0. That made the fix for
      // the twelfth instance of this project's silent-gate family into the thirteenth, which is
      // the fourth time in this codebase that a fix for one instance created the next. The test
      // and the claim must be the same question.
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath -- $verificationTaskName had nothing to evaluate: all " +
          "${allFloors.size} of its coverage floors need instrumented execution data, and this " +
          "module has no JVM tests to produce any. They are enforced by $deferredTo instead. " +
          "This is the expected steady state for a module tested only on a device, not a " +
          "missing-test warning; if this module ever gains a JVM test, its floors should be " +
          "re-measured and any that no longer need an emulator moved into this tier.",
      )
      return
    }
    if (presentExecutionData.isEmpty()) {
      // The state this whole task exists for. Nothing below can be said honestly: no rule was
      // evaluated and no report was produced, so the module's floors are simply unchecked this
      // run -- and, without this line, indistinguishable from all of them passing.
      CoverageWarning.emit(
        logger,
        onGitHubActions,
        "COVERAGE: $modulePath -- $verificationTaskName did NOT run, so none of its " +
          "${allFloors.size} coverage floors were evaluated. Gradle skipped it through the " +
          "`onlyIf(\"Any of the execution data files exists\")` its own JacocoReportBase " +
          "attaches, because every execution data file this task reads is absent. It looked for " +
          "${executionData.joinToString { it.name }.ifEmpty { "no named file" }}, and for any " +
          "`**/*.ec` under every module's build/outputs/code_coverage -- that glob contributes " +
          "no files at all when those directories are absent, so it never appears by name here. " +
          "Usually this means the module's tests did not run or no longer exist. This build " +
          "says nothing about $modulePath's coverage.",
      )
      return
    }

    // "Evaluated", never "enforced". The review found the fast gate reporting that it had
    // "enforced all 1 of its coverage floors" for a floor that matched no class and therefore
    // enforced nothing at all -- a claim stronger than the work done. What this task can honestly
    // say is that JaCoCo evaluated the rules it was given; whether each of those rules matches a
    // class carrying counters of its own kind is a separate question, answered below only where
    // [reportXmlFile] is available, and named explicitly as unanswered where it is not.
    val deferred = allFloors.size - evaluatedFloors.size
    val caveat = perClassChecksRunBy?.let {
      " Whether each of those floors actually matches a class with counters of its kind is not" +
        " checked here -- $it does that."
    }.orEmpty()
    when {
      evaluatedFloors.isEmpty() ->
        // `annotate = false`: this is the designed steady state for `:app` and
        // `:core:designsystem`, whose every floor is a Compose LINE floor only the emulator tier
        // can measure, so it would appear on every single pull request. Still `warn` in the log --
        // the point stands that this task passing says nothing about those modules -- but an
        // annotation nobody can act on trains people to ignore the ones they can.
        CoverageWarning.emit(
          logger,
          false,
          "COVERAGE: $modulePath -- $verificationTaskName evaluated 0 of its ${allFloors.size} " +
            "coverage floors: every one of them needs an emulator. They are enforced instead by " +
            "${deferredTo.orEmpty()}. This task passing says nothing about $modulePath's coverage.",
        )
      deferred > 0 ->
        logger.lifecycle(
          "COVERAGE: $modulePath -- $verificationTaskName evaluated ${evaluatedFloors.size} of " +
            "its ${allFloors.size} coverage floors; the rest are left to ${deferredTo.orEmpty()}." +
            caveat,
        )
      else ->
        logger.lifecycle(
          "COVERAGE: $modulePath -- $verificationTaskName evaluated all ${allFloors.size} of its " +
            "coverage floors.$caveat",
        )
    }

    if (reportXmlFile == null || reportTaskName == null) return
    UngatedClassChecker.warnUngatedCoverage(
      modulePath = modulePath,
      reportTaskName = reportTaskName,
      xmlFile = reportXmlFile,
      allFloors = allFloors,
      evaluatedFloors = evaluatedFloors,
      onGitHubActions = onGitHubActions,
      logger = logger,
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

  // The full gate: every floor, merged JVM + instrumented execution data. Rules only — everything
  // this task used to *report* now lives in its notice task below, because an `onlyIf` can skip a
  // task's actions and did (see [CoverageGateNotice]'s own doc for the measured failure).
  tasks.withType<JacocoCoverageVerification>().configureEach {
    // The JVM-only gate is a `JacocoCoverageVerification` too, so it would otherwise be handed the
    // whole table and the merged execution data by this very block. It is configured on its own
    // terms further down, from the task this one produces.
    if (name == JVM_COVERAGE_VERIFICATION_TASK_NAME) return@configureEach

    dependsOn("test")

    val floors = coverageFloors[project.path].orEmpty()
    if (floors.isEmpty()) return@configureEach

    // Ensures a fresh XML report always exists for this task's notice to read, whatever order
    // tasks were invoked in (the CI workflow already runs "Coverage report" before "Coverage gate"
    // as separate steps, but this makes that guaranteed rather than assumed).
    dependsOn(FULL_COVERAGE_REPORT_TASK_NAME)
    // Two identical plain-command runs in a row previously reported UP-TO-DATE the second time
    // (byte-identical XML, byte-identical execution data, nothing at all had changed) and skipped
    // this task outright. The JaCoCo Ant check it runs is fast (this project's modules are small
    // today), so re-running it unconditionally costs real but negligible time in exchange for a
    // gate that cannot silently go stale between runs. Note what this does *not* buy: it has no
    // effect on the `onlyIf` Gradle's own `JacocoReportBase` attaches — see [CoverageGateNotice].
    outputs.upToDateWhen { false }

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
  }

  // Tier 1's half of the gate, plus the notice task each gate needs. Registered only where the
  // full task exists (every module applies `jacoco` through a convention plugin, but
  // `plugins.withId` keeps that a fact rather than an assumption), and derived from the full task
  // rather than restating any of it: same classes, same source dirs, the same execution data minus
  // the instrumented half, and the subset of the same floor list that
  // `isEnforceableWithoutAnEmulator` selects.
  //
  // Why a second task rather than a flag on the first: a `-P`-switched gate is one typo away from
  // silently evaluating the wrong half, and the two genuinely differ in their *inputs*, not only
  // in which rules they apply — this one must not be able to pass on execution data a developer's
  // last emulator run happened to leave on disk.
  plugins.withId("jacoco") {
    val floors = coverageFloors[project.path].orEmpty()
    val jvmFloors = floors.filter(::isEnforceableWithoutAnEmulator)
    val modulePath = project.path

    val jvmVerification = tasks.register<JacocoCoverageVerification>(JVM_COVERAGE_VERIFICATION_TASK_NAME) {
      group = "verification"
      description = "Fails if a coverage floor that needs no emulator drops below its minimum " +
        "-- the Tier 1 subset of $FULL_COVERAGE_VERIFICATION_TASK_NAME. See root build.gradle.kts."

      dependsOn("test")
      dependsOn(FULL_COVERAGE_REPORT_TASK_NAME)

      // Read here, inside the registration block, so it runs at realization time: `.get()`
      // realizes the full task, which applies the `configureEach` above to it first, so what is
      // copied below is the fully configured value rather than an empty default.
      val fullVerification =
        tasks.named(FULL_COVERAGE_VERIFICATION_TASK_NAME, JacocoCoverageVerification::class.java).get()
      classDirectories.setFrom(fullVerification.classDirectories)
      sourceDirectories.setFrom(fullVerification.sourceDirectories)
      executionData.setFrom(jvmOnly(fullVerification.executionData))

      outputs.upToDateWhen { false }

      violationRules {
        isFailOnViolation = true
        jvmFloors.forEach { floor ->
          rule {
            element = floor.element
            // See the identical lines on the full task above: an explicitly-assigned empty
            // includes/excludes means "match zero classes", not "no restriction".
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
    }

    // One notice per gate. `finalizedBy`, not `doLast` on the gate itself: see
    // [CoverageGateNotice]'s doc for the `onlyIf` that made every `doLast` here suppressible, and
    // for the probe build that confirmed a finalizer still runs when its finalized task is
    // skipped that way.
    //
    // Both notices read the *same* report, `jacocoTestReport`, even though the two gates enforce
    // over different execution data. That is sound, not a shortcut, and the reason is narrow: the
    // only things [UngatedClassChecker] reads out of that XML are `missed + covered` totals per
    // counter, and a total is a property of the analysed *bytecode*, not of the execution data --
    // exec data moves coverage between "missed" and "covered" without changing their sum. So "does
    // this floor match any class carrying counters of its kind" and "does this class carry any
    // counters at all" have the same answer whichever tier's data produced the report, while the
    // Tier 1 *gate* keeps enforcing over `jvmOnly` data and stays independent of any emulator run.
    //
    // A dedicated JVM-only report was built first and then removed, and the reason recorded here
    // at the time was wrong, so it is worth stating what actually happened. The symptom was real:
    // in some invocation shapes a report came out with `SetupViewModel$1` at 18 instructions on
    // one line with no branches, against a class file that really has 42 instructions, two lines
    // and five branches. The cause was **not** that anything analysed the wrong bytecode -- the
    // class inputs were byte-identical across the two runs, same files, same md5, same
    // `<sessioninfo>`. It was that the two runs used different JaCoCo versions, 0.8.14 against the
    // pinned 0.8.12, and 0.8.14's Kotlin-coroutine and Compose filters simply report different
    // numbers. That is fixed at its root in `configureJacoco` (build-logic), which is why both
    // notices can now read one report; the second report task bought nothing once the analyzer
    // stopped varying. See task-8-report.md.
    fun registerNotice(
      noticeTaskName: String,
      verificationTaskName: String,
      reportTaskName: String?,
      evaluatedFloors: List<CoverageFloor>,
      deferredTo: String?,
      perClassChecksRunBy: String?,
    ) {
      val notice = tasks.register(noticeTaskName) {
        group = "verification"
        description = "Reports what $verificationTaskName evaluated, including when Gradle " +
          "skipped it for want of execution data. Never fails the build."

        // No outputs and never up-to-date: this task must execute on every invocation that asks
        // for its gate, including a configuration-cache reuse run (task execution still happens on
        // a cache hit -- the cache skips reconfiguring, not rerunning).
        outputs.upToDateWhen { false }

        // Read through `providers.environmentVariable` rather than `System.getenv` in the task
        // action: this makes the value a declared configuration-cache input, so an entry stored
        // locally is not silently reused in CI with annotations switched off. GitHub Actions sets
        // `GITHUB_ACTIONS=true` on every runner.
        val onGitHubActions = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"
        if (reportTaskName != null) dependsOn(reportTaskName)

        val verification =
          tasks.named(verificationTaskName, JacocoCoverageVerification::class.java).get()
        // Captured as the live FileCollection, resolved inside `doLast`: the whole point is to ask
        // the same question at the same moment Gradle's own `onlyIf` asked it.
        val gatedExecutionData = verification.executionData
        // A plain `File`, resolved eagerly here. `JacocoReport.reports.xml.outputLocation` is a
        // `Report` property, not a task output property Gradle's dependency/serialization
        // machinery can associate back to its producer: a `TaskProvider` `.get()`-resolved inside
        // `doLast` hit the deprecated "Task.project at execution time" warning, and a
        // `.flatMap { it.reports.xml.outputLocation }` `Provider` failed under
        // `--configuration-cache` with "error writing value of type 'FlatMapProvider'". Resolving
        // it now yields something trivially serializable.
        val reportXmlFile: File? = reportTaskName?.let {
          tasks.named(it, JacocoReport::class.java).get().reports.xml.outputLocation.get().asFile
        }

        // Resolved eagerly at configuration time, like `reportXmlFile` above and for the same
        // serialization reason. This is what separates "this module never had JVM tests" from
        // "this module's JVM tests just vanished" -- see the quiet branch in
        // `CoverageGateNotice.report`, which a review found could not tell those apart.
        val hasJvmTestSource = projectDir.resolve("src/test").isDirectory

        doLast {
          CoverageGateNotice.report(
            modulePath = modulePath,
            hasJvmTestSource = hasJvmTestSource,
            verificationTaskName = verificationTaskName,
            reportTaskName = reportTaskName,
            reportXmlFile = reportXmlFile,
            allFloors = floors,
            evaluatedFloors = evaluatedFloors,
            executionData = gatedExecutionData.files,
            deferredTo = deferredTo,
            perClassChecksRunBy = perClassChecksRunBy,
            onGitHubActions = onGitHubActions,
            logger = logger,
          )
        }
      }
      // `tasks.matching { }.configureEach`, not `tasks.named(verificationTaskName)`: this whole
      // block runs from `plugins.withId("jacoco")`, i.e. while the jacoco plugin is being applied,
      // and for an Android module `configureAndroidJacocoCoverageVerification` has not registered
      // the full gate yet at that point -- `tasks.named` threw "Task with name
      // 'jacocoTestCoverageVerification' not found in project ':app'" outright. A `matching`
      // collection applies to a task registered before *or* after this line, the same reason
      // `Jacoco.kt` and the `configureEach` above use the lazy form.
      tasks.matching { it.name == verificationTaskName }.configureEach { finalizedBy(notice) }
    }

    registerNotice(
      noticeTaskName = FULL_COVERAGE_NOTICE_TASK_NAME,
      verificationTaskName = FULL_COVERAGE_VERIFICATION_TASK_NAME,
      reportTaskName = FULL_COVERAGE_REPORT_TASK_NAME,
      // The full gate evaluates the whole table, so nothing is deferred and `deferredTo` is
      // unreachable rather than merely unused.
      evaluatedFloors = floors,
      deferredTo = null,
      // This is the tier that runs the per-class checks, so it has no caveat to add.
      perClassChecksRunBy = null,
    )
    registerNotice(
      noticeTaskName = JVM_COVERAGE_NOTICE_TASK_NAME,
      verificationTaskName = JVM_COVERAGE_VERIFICATION_TASK_NAME,
      // Reads the same report the full gate's notice does, and runs the same two per-class
      // checks. Both tiers, deliberately: `warnVacuousFloors` is exactly the check that detects a
      // floor which cannot fail at any minimum, and the one time this build had such a floor it
      // was in the *Tier 1* subset -- `:feature:setup`'s `SetupViewModel*1`/`*2` BRANCH floor,
      // which passed Tier 1 at a minimum of 0.99 while Tier 2 failed it at 0.60, because Tier 1
      // was silently analysing with an unpinned JaCoCo (see `configureJacoco` in build-logic).
      // The check was switched off on the only tier that had the defect.
      //
      // A previous round wired this to `null` on the grounds that pulling `jacocoTestReport` into
      // a Tier 1 task graph made it "analyse bytecode that does not match the class files it was
      // handed". That was a misdiagnosis: the bytecode was byte-identical (same 16 files, same
      // md5, same `<sessioninfo>`) and the difference was the analyzer version, 0.8.14 versus the
      // pinned 0.8.12, resolved differently depending on the task graph. With the pin actually
      // binding, this report is now byte-for-byte the same numbers in either invocation shape --
      // re-tested, `SetupViewModel$1` 37/42 INSTRUCTION, 3/5 BRANCH, 2/2 LINE via both
      // `jacocoJvmCoverageVerification` and `jacocoTestReport`.
      //
      // Sound despite the two tiers gating on different execution data, for a reason that is
      // narrow and worth stating: the only figures either check reads are `missed + covered`
      // totals, and a total is a property of the analysed bytecode, not of the execution data --
      // exec data moves coverage between "missed" and "covered" without changing their sum. The
      // Tier 1 *gate* still enforces over `jvmOnly` data and stays independent of any emulator run.
      reportTaskName = FULL_COVERAGE_REPORT_TASK_NAME,
      evaluatedFloors = jvmFloors,
      deferredTo = "`$FULL_COVERAGE_VERIFICATION_TASK_NAME`, which needs the instrumented " +
        "execution data only the emulator journey produces (.github/workflows/e2e.yml)",
      // Runs the per-class checks itself now, so it has no caveat to add either.
      perClassChecksRunBy = null,
    )

    // Into `check`, unlike the full task, which cannot run without a device. This is what makes
    // the fast floors enforced by a plain `./gradlew build` rather than only by a CI step -- the
    // gap the review found when `check --dry-run` listed no jacoco task at all.
    tasks.named("check").configure { dependsOn(jvmVerification) }
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

      // The eleventh silent gate, and the one guarding the claim the spec singles out ("anything
      // whose subject is Navidrome's behaviour is tested against a pinned Navidrome container").
      // Without this the gate passes with **no Navidrome at all**. Measured, container stopped and
      // port dead:
      //
      //     ./gradlew :core:network:liveNavidromeTest                 -> UP-TO-DATE, BUILD SUCCESSFUL
      //     (delete this task's outputs) ... --build-cache            -> FROM-CACHE, BUILD SUCCESSFUL
      //
      // The second is the CI-reachable one: `gradle.properties` sets `org.gradle.caching=true`,
      // `gradle/actions/setup-gradle@v6` restores the Gradle user home (which holds
      // `caches/build-cache-1`) between runs, and `Test` is a `@CacheableTask` -- so any later run
      // whose `:core:network` inputs are unchanged (a docs-only PR, a workflow-only PR, a re-run of
      // a flaked job) restores this task and never opens a socket, while `Start Navidrome` and
      // `Configure libraries` above it become decoration.
      //
      // A task whose *whole subject* is a live server has no legitimate up-to-date or cached
      // answer: the inputs Gradle hashes say nothing about whether the container is running or what
      // it contains. So this task always executes. Verified after adding this line, container still
      // stopped: `3 tests completed, 3 failed`, BUILD FAILED, under both invocations above.
      //
      // Not the same case as the `onlyIf` caveat on the JaCoCo tasks elsewhere in this file: this
      // is a plain `Test` task with no `onlyIf`, so nothing short-circuits its actions.
      //
      // `cacheIf { false }` as well as `upToDateWhen { false }`, rather than trusting one to imply
      // the other -- they are separate decisions in Gradle (is this task up to date; may its result
      // be stored/loaded) and the failure this closes came from the second one.
      outputs.upToDateWhen { false }
      outputs.cacheIf { false }
    }
  }
}

// `:app`'s `ConventionTest` guards the build by *reading files* — every module build file, every
// build-logic source, the version catalogue, `.github/workflows/e2e.yml` and
// `ci/prepare-emulator.sh`. None of those are inputs of the task that runs it, so Gradle had no
// reason to re-run it when one of them changed: editing a workflow and running
// `./gradlew :app:testDebugUnitTest --tests '*ConventionTest*'` reported UP-TO-DATE and passed,
// with the injected violation still in the file. Confirmed by injection, twice, before this block
// existed — which makes it the same shape of defect as a coverage floor that cannot fail, and the
// reason a convention rule that scans a file must declare that file.
//
// Registered here, not in `app/build.gradle.kts`: every module build file contains only
// `plugins {}` and `dependencies {}` (`ConventionTest` itself enforces that), so a one-off like
// this belongs at the root alongside the `liveNavidromeTest` registration above.
//
// The patterns deliberately mirror what `ConventionTest` actually walks. If a future rule there
// starts reading something outside this set, it will silently stop being re-run — so extend this
// list in the same commit.
//
// `tasks.withType<Test>().configureEach` with a name check, not `afterEvaluate { tasks.named(...) }`:
// AGP registers `testDebugUnitTest` from its own variant callbacks, which run *after* this
// project's `afterEvaluate` — that spelling failed outright with "Task with name
// 'testDebugUnitTest' not found in project ':app'". `configureEach` applies to a task registered
// before or after this line, the same reason `Jacoco.kt` and the `subprojects` block above use it.
project(":app") {
  tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
      val scannedByConventionTest = rootProject.fileTree(rootProject.projectDir) {
        include("**/build.gradle.kts")
        // Read by ConventionTest's `every Gradle project has a coverage floor` rule, which
        // compares settings.gradle.kts's includes against coverageFloors's keys. Nothing else in
        // this fileTree matches it -- `**/build.gradle.kts` does not.
        include("settings.gradle.kts")
        include("build-logic/**/*.kt")
        include("build-logic/**/*.kts")
        include("gradle/libs.versions.toml")
        include(".github/workflows/*.yml")
        include("ci/*.sh")
        exclude("**/build/**")
        exclude("**/.git/**")
        exclude("**/.gradle/**")
      }
      inputs.files(scannedByConventionTest)
        .withPropertyName("filesScannedByConventionTest")
        // RELATIVE, not ABSOLUTE: the content and repository-relative path are what the rules
        // assert on, so a checkout at a different absolute path must still hit the build cache.
        .withPathSensitivity(PathSensitivity.RELATIVE)
    }
  }
}
