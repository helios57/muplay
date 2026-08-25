import java.io.File
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.w3c.dom.Element

// The root project has no `check` task of its own without this, and the `build-logic` tests wired
// in at the bottom of this file need one to hang from -- every other `check` in this build belongs
// to a subproject, and `build-logic` is not one. `base` and nothing more: no module applies its
// plugins here (see below), and this adds `check`/`assemble`/`build`/`clean` lifecycle tasks only.
plugins {
  base
}

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
 *   100% today (**56/56** and **28/28** real branches) against the full 0.90 target.
 *
 *   `:core:testing` was 6/6 until Plan 3 Task 7 added `PcmAnalysis`, the pure-JVM analyser the
 *   gapless measurement is read through, which is 22 of those 28. A BUNDLE aggregate is the shape
 *   `:core:model`'s entry below warns about, so the question was asked here rather than assumed,
 *   and answered by deletion in both directions: with `PcmAnalysisTest` deleted this floor fails
 *   at **6/28 = 0.21**, and with `OpenApiFixtureValidatorTest` deleted it fails at
 *   **22/28 = 0.78**. Neither class can hide behind the other's coverage, which is the only
 *   property that made the aggregate honest here in the first place; a third class arriving is
 *   what would change that answer. (Raising this floor above its measured 1.00 is not a way to
 *   watch it fire, incidentally: JaCoCo rejects a minimum outside 0.0..1.0 as a configuration
 *   error — *"given minimum ratio is 1.01, but must be between 0.0 and 1.0"* — which fails the
 *   build without ever reading a ratio. Deleting the tests is what actually exercises the gate.)
 *
 *   `:core:network`'s
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
 * - **`:feature:setup`** — three `"CLASS"`-element rules: two BRANCH, one LINE over
 *   `SetupScreenKt`, this module's one Composable file (described at the end of this entry). Task
 *   8 replaced `SetupViewModel`'s defaulted-lambda constructor seam (`@JvmOverloads`, no DI graph
 *   to inject from) with ordinary Hilt constructor injection — a secondary `@Inject` constructor
 *   builds two anonymous `SetupCredentialSink`/`SetupLibrarySink` objects from the now-injected
 *   `CredentialStore`/`LibraryRepository` — and gained real logic along with it: the tagging
 *   predicate, the continue guard, the widened catch cascade. The two prior *floors* this replaced
 *   — `SetupViewModel*1`/`SetupViewModel*2` at 0.55, originally matching the compiled *default*-
 *   lambda classes — were deleted. **Correction, found on re-review:** the deletion is still
 *   right, but an earlier version of this comment justified it as "those patterns now match no
 *   compiled class, a `0/0` → `NaN` → 'no violation' rule" — checked and found false. Both
 *   patterns still match real classes: `SetupViewModel*1` matches the new `.connect.1`/
 *   `.setRole.1`/`.continueToLibrary.1`/`.tagging.1` coroutine-body classes, and `*2` still
 *   matches `.2` (the `SetupCredentialSink` anonymous object). The actual reason the deletion is
 *   correct: every class either old pattern matches is *also* matched by the new
 *   `SetupViewModel*` wildcard folded into the 0.90 rule directly below — a strict superset (it
 *   additionally matches `.3`, the `SetupLibrarySink` anonymous object, which neither old pattern
 *   did) — at a *stricter* minimum (0.90 vs 0.55). Keeping the old 0.55 floor alongside the new
 *   0.90 one would have added no protection at all, only a lower, confusing ceiling.
 *
 *   `SetupViewModel` measures **12/12** BRANCH today: `connect`'s `InvalidUrl` check and its
 *   catch cascade (`SubsonicErrorException` / `SubsonicHttpException` /
 *   `CancellationException`-rethrow / generic `Exception`), `tagging`'s `isNotEmpty() &&
 *   none { UNASSIGNED }` conjunction, `setRole`'s `serverInfo?.let` null guard, and
 *   `continueToLibrary`'s own `isNotEmpty() && none { UNASSIGNED }` guard (added in review round
 *   1 -- N-2 found `continueToLibrary` relying on `none` alone, vacuously true over an empty
 *   list, closed by `continuing with no libraries at all does nothing`). Two of those branches
 *   measured **0%** the moment their own
 *   tests did not exist — found by reading the XML report directly, not by inspection —
 *   and were closed by name: `a cancelled connection is never reported as a failure` (the
 *   `CancellationException` rethrow; without it, a broad `catch (e: Exception)` below would
 *   swallow cancellation as `Unreachable`) and `setting a role before any connection has
 *   succeeded stores it but touches no screen state` (`serverInfo`'s null path; a `serverInfo!!`
 *   mutant crashes this test). `SetupViewModel*` rides along in the same rule, matching six
 *   compiled nested classes the new seam and each `viewModelScope.launch` body produce — the two
 *   `@Inject`-constructor anonymous sink objects (`SetupViewModel$2`/`$3`, 2/2 and 4/4 LINE, no
 *   branches of their own) and four per-method coroutine bodies (`$connect$1` 17/17 LINE;
 *   `$setRole$1` 2/2 BRANCH; `$continueToLibrary$1` 8/8 BRANCH; `$tagging$1`, no counters at all)
 *   — the same zero-branch-rider pattern `SetupUiState*` already uses below: harmless (`NaN` → no
 *   violation for the branch-less ones) and it is what keeps `warnUngatedClasses` quiet about
 *   them. `SetupFailureReasonKt` (7/8, floor 0.85, untouched by Task 8): `toMessage`'s
 *   `when`-cascade, `SetupFailureReasonTest` covers all three members plus both sides of
 *   `Rejected`'s `detail` null/non-null branch; the one branch still missing is an artifact of how
 *   a `when` with no `else` over a sealed interface compiles, not an uncovered case.
 *
 *   And a rule of a different counter, added by Task 7 and re-measured here: `SetupScreenKt` LINE
 *   (**81/83 = 0.9759**, floor 0.90). From the JVM alone this class measures 0/54 — `0.00` is
 *   exactly the unfireable floor this project must not ship again — and only
 *   `FirstRunJourneyTest`, composing the real screen on an emulator down every terminal state
 *   (a successful connect, a rejection, every library tagged and `Continue` reaching `Ready`),
 *   makes a real floor possible. The `Ready` branch is why that last state matters: JaCoCo had
 *   reported its `Text("Setup complete")` line as "covered" from the first three journeys alone,
 *   because Kotlin compiles an exhaustive sealed `when` as an `instanceof` chain — the `Ready`
 *   check's own dispatch instructions run on *every* composition regardless of the actual state,
 *   so the line lit up green while the branch behind it, and the text it renders, had never once
 *   executed. `completingEveryTagReachesReadyAndShowsSetupComplete` closes that specifically. The
 *   2 lines still missing both belong to the `private` `SetupScreen` overload: its declaration
 *   line and the closing brace of its `when (uiState)` block — the same two Task 7 could not
 *   attribute further than "not an uninvoked `SetupScreen$default` bridge; this class carries none
 *   at all". `SetupScreenKt*` rides along, matching the `LaunchedEffect(uiState) { ... }` body
 *   Task 8 added (`SetupScreenKt$SetupScreen$1$1`, 3/3 LINE). Both classes' BRANCH counters (81/49
 *   and 3/1 respectively) are deliberately left ungated, per the ruling above.
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
 * - **`:feature:library`** (Plan 2 Task 9, floors 2 and 3 added in its review round 1) — three
 *   `"CLASS"`-element BRANCH rules:
 *
 *   1. `1.00` over `LibraryUiStateKt` (`libraryContent`, measured **18/18**), with the zero-branch
 *      `LibraryUiState` sealed interface riding along, per the same reasoning `SetupUiState` rides
 *      along in `:feature:setup`'s own rule above.
 *   2. `1.00` over `LibraryViewModel` and `AlbumViewModel` **by exact name** (measured 4/4 and
 *      2/2), with `AlbumViewModel`'s zero-branch nested `Fetch` types riding along.
 *   3. `0.75` over `CoverArtCacheKeyKt` (measured **3/4** — see below for the missing fourth).
 *
 *   Rules 2 and 3 were deferred to Task 10 when this module shipped, on two arguments the review
 *   (N-7) showed were choices rather than constraints, and the corrections are worth keeping:
 *
 *   - *"`coverArtCacheKey` and `CoverArtImage` compile into one file-class, so a CLASS rule cannot
 *     separate them."* True, and irrelevant: **which declarations share a file is the author's
 *     choice**, and splitting pure state out of a Compose file is already this codebase's own
 *     convention (`LibraryUiState.kt` vs `LibraryScreen.kt`, `SetupUiState.kt` vs
 *     `SetupScreen.kt`). `coverArtCacheKey` moved to `CoverArtCacheKey.kt` and now measures
 *     `CoverArtCacheKeyKt` 3/4 BRANCH, 1/1 LINE on its own. The fourth branch is Kotlin's
 *     unreachable non-null path of `sizePx?.toString() ?: "full"`, so `0.75` — not `0.90` — is the
 *     honest ceiling, the same shape and the same reason as `SetupFailureReasonKt`'s `0.85` above.
 *     Proved able to fail, not merely to pass: raising this entry to `1.00` produces
 *     `Rule violated for class app.muplay.library.CoverArtCacheKeyKt: branches covered ratio is
 *     0.75, but expected minimum is 1.00 -> BUILD FAILED`.
 *   - *"`LibraryViewModel` cannot be gated: `LibraryViewModel$shuffle$1` measures 6/12."* True of a
 *     **wildcard**, which is what was tried; it is not true of an exact-name include, and this
 *     table already uses exact names beside wildcards (`"app.muplay.setup.SetupViewModel"` *and*
 *     `"app.muplay.setup.SetupViewModel*"`). `LibraryViewModel` itself is 4/4 from this module's
 *     own JVM tests, and an exact-name rule gates the outer class while leaving the under-covered
 *     nested ones to keep warning. `AlbumViewModel` was genuinely 1/2 when the review ran — its
 *     double-load guard was executed by no test (N-3a) — and is 2/2 now that it is.
 *
 *   **Still deferred to Task 10, and now genuinely so:** `CoverArtKt` (the `@Composable
 *   CoverArtImage` alone, 0/52 BRANCH, 0/23 LINE), `LibraryScreenKt` (0/158, 0/62) and
 *   `AlbumScreenKt` (0/58, 0/24) — none can be composed from a JVM test — plus both ViewModels'
 *   nested lambda classes, which no floor a wildcard could hold reaches today
 *   (`LibraryViewModel$shuffle$1` 6/12, and the `@Inject` constructor's real-repository
 *   `LibrarySource`/`AlbumSource` adapters, `LibraryViewModel$1` and `AlbumViewModel$1`, reachable
 *   only through Hilt's DI graph at 0/8 and 0/4 LINE). All of them are named, by measured ratio,
 *   in `warnUngatedClasses`'s output on every run.
 *
 * - **`:app`** — one `"BUNDLE"`-element LINE rule at `0.90` (measured **61/63 = 0.9683**, up from
 *   20/21 = 0.9524 before Task 10 added the Tier 2 journeys), the one
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
    //   `StreamFormat`         Plan 3 Task 1. Two hand-written branches, both of them decisions
    //                         this project argues about in prose: `forSuffix`'s membership test
    //                         (the "never Opus" rule, which `format=raw` makes impossible to
    //                         enforce by omission) and `Mp3`'s `require` on the bitrate range.
    //                         Listed here as a gated class, not as a ride-along, and matched by
    //                         both an exact name and a `*` pattern because the branches live in
    //                         the nested `Companion` and `Mp3` classes rather than in the
    //                         interface itself.
    //
    //   `BrowseId`            Plan 5 Task 1, and the same shape as `StreamFormat` for the same
    //                         reason: the interface itself compiles to **no counters at all**
    //                         (measured -- neither BRANCH nor LINE appears for
    //                         `app/muplay/model/browse/BrowseId` in the report), and every branch
    //                         lives in nested classes the `*` pattern is what reaches. Measured
    //                         today: `BrowseId$Companion` **60/60** (all of `decode` -- the empty
    //                         check, the prefix check, `hasPayload`, the eleven-arm `when` over
    //                         the kind, and `canonicalInt`'s two), `BrowseId$Track` **8/8** (its
    //                         two `require`s -- empty, and the `muplay/` collision the bare-leaf
    //                         encoding buys) and `BrowseId$Book`/`$Album`/`$Artist` **4/4** each
    //                         (one `require` apiece). Those last three are the reason
    //                         `BrowseIdTest` asserts an empty id is refused for *every*
    //                         payload-carrying member rather than for `Track` alone: without
    //                         those three assertions each of those classes measures 2/4 = 0.50
    //                         and this floor fails, which is a CLASS-element rule doing exactly
    //                         what a BUNDLE aggregate over this module would have hidden.
    //
    //   `BrowseTree`            Plan 5 Task 2, and the first class in this module with a real
    //                         amount of hand-written logic in it. Measured **49/49** BRANCH,
    //                         130/130 LINE: the surface `when` in `root` (the four-tab limit, the
    //                         watch's missing Artists tab, the phone-only Libraries tab),
    //                         `continueNodes`'s `hasStarted && !isFinished` filter, `bookNode`'s
    //                         `fileCount > 1`, `completionOf`'s three-arm cascade,
    //                         `bookSubtitle`'s `hasStarted && !isFinished`, the three
    //                         `?.takeIf(String::isNotBlank) ?: UNKNOWN_ARTIST` chains and
    //                         `libraryChildren`'s `role == MUSIC` guard -- spec section 1's rule,
    //                         expressed as the absence of a shuffle row. `BrowseTree*` is not
    //                         decoration: Kotlin compiles each `compareBy`/`thenBy`/`sortedBy`
    //                         into its own synthetic class, and two of them
    //                         (`$continueNodes$$inlined$thenBy$1`, `$bookNodes$$inlined$thenBy$1`)
    //                         carry **2/2 BRANCH of their own** -- the tie-breaks that keep a car
    //                         list from reordering itself between two identical requests.
    //
    //   `BrowseText`           Plan 5 Task 2. **9/9**: `remainingLabel`'s four bands and
    //                         `albumCountLabel`'s three arms, each asserted at both sides of its
    //                         boundary. Its opening `max(0L, remainingMs)` was deleted rather
    //                         than covered -- measured unfalsifiable, no input including
    //                         `Long.MIN_VALUE` changed its answer, because the first band's test
    //                         is `<`.
    //
    //   `BookSummary`          Plan 5 Task 2 (see its own KDoc: Plan 4 Task 4 owns the type and
    //                         had not landed anywhere in the repository). **4/4**:
    //                         `progressFraction`'s `durationMs <= 0` divide-by-zero guard and its
    //                         `coerceIn`. Gated by exact name rather than as a ride-along because
    //                         it is the one `data class` in this module with arithmetic in it.
    //
    // Everything else in the list rides along, the same way `SetupUiState` rides along in
    // `:feature:setup`'s rule: they carry zero branches, so they cannot move any ratio (a
    // CLASS-element rule over a zero-counter class yields NaN, and JaCoCo reports no violation
    // for NaN), and including them is what keeps `warnUngatedClasses` from flagging them on every
    // run. That is honest for these specifically because they contain **no author-written
    // executable code at all** -- read them: `ServerInfo`, `MusicLibrary`, `Album`,
    // `AlbumWithSongs`, `Artist`, `Song`, `ScanStatus` and `ShuffleResult` are `data class`
    // declarations with no body, and `LibraryRole`/`AlbumListType` are `enum class` declarations
    // whose only members are constructor properties, so every line JaCoCo counts in them is
    // compiler-generated `equals`/`hashCode`/`toString`/`copy`/`values` plumbing. Gating that
    // would be gating the Kotlin compiler, the same argument this table already makes about
    // Compose's synthetic branches. If any of them grows a body, it needs a rule of its own --
    // which is exactly what happened to `SearchResults`, and why it is listed above rather than
    // here.
    //
    // Plan 5 Task 2 adds five more of exactly that shape, all measured with **no BRANCH counter
    // at all** and every line of them a declaration: `BrowseNode` and `BrowseCompletion` (`data
    // class`es with no body, 12/12 and 3/3 LINE), and `BrowseMediaType`, `BrowseStyle`,
    // `BrowseCompletionStatus` (`enum class`es whose only members are their constants, 9/9, 1/1,
    // 1/1). `BrowseSurface` joins them despite carrying two constructor properties and a
    // companion `const`: it is `LibraryRole`'s shape exactly (6/6 LINE, no branches), its three
    // `continueLimit`/`browsableStyle` values are asserted as values by `BrowseTreeTest` where
    // that assertion can actually fail, and a LINE floor over an enum's own declaration lines
    // cannot fail while any test in the module touches the enum at all. `BrowseSurface*` rides
    // along for `BrowseSurface$Companion`, which carries no counter of either kind.
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
        "app.muplay.model.ShuffleResult",
        "app.muplay.model.StreamFormat",
        "app.muplay.model.StreamFormat*",
        "app.muplay.model.browse.BrowseId",
        "app.muplay.model.browse.BrowseId*",
        // Plan 6 Task 2. `RememberedRenderer` is a three-field record and `RememberedRenderers` is
        // an interface whose only member with a body is a `const val`; between them they carry
        // zero branch counters, so they ride along here exactly the way `Album` and `Song` do
        // above -- included so `warnUngatedClasses` has nothing to say, never gating anything.
        //
        // Their LINE counters are deliberately left ungated, and that needs saying rather than
        // hiding: `RememberedRenderer` measures 0/4 LINE from this module's own tests, because
        // the only code that constructs one lives in `:core:cast` and `:core:database`, whose
        // execution data is not this module's. That is the exact trap `SubsonicCredentials` fell
        // into below -- but the answer there was a real test for a real security control, and the
        // answer here is that those 4 lines are compiler-generated `equals`/`hashCode`/`copy`
        // plumbing on a class with no body. Gating them would be gating the Kotlin compiler, and
        // writing a `:core:model` test that constructs one just to light them up would be gating
        // it dishonestly. If this record ever grows a member, it needs a rule of its own.
        "app.muplay.model.RememberedRenderer",
        "app.muplay.model.RememberedRenderers",
        "app.muplay.model.RememberedRenderers*",
        "app.muplay.model.BookSummary",
        "app.muplay.model.browse.BrowseTree",
        "app.muplay.model.browse.BrowseTree*",
        "app.muplay.model.browse.BrowseText",
        "app.muplay.model.browse.BrowseSurface",
        "app.muplay.model.browse.BrowseSurface*",
        // Plan 5 Task 3. Named in full even though the `BrowseSurface*` rider one line up already
        // matches it (`wildcardToRegex` turns that into `\QBrowseSurface\E.*`, and `BrowseSurfaces`
        // matches) -- because that rider was added in Task 2 to catch `BrowseSurface$Companion`,
        // a class carrying no counters at all, and a floor whose only reason for reaching a class
        // is a glob aimed at something else is one narrowing away from silently letting go. This
        // is the first entry in this list that the rider was gating by accident, and it is the
        // only class in the browse package whose branches are a *decision* rather than a guard.
        //
        // Measured today: `BrowseSurfaces` BRANCH **12/12**, LINE 19/19, and all 12 of those
        // branches are `of`'s (measured per method: `of` BRANCH 12/12 LINE 10/10; the other 9
        // lines are `<clinit>` and the two set getters). Verified fireable by withholding tests
        // rather than by raising the minimum, and the near-misses are the point: withholding `the
        // media3 predicate wins...` alone leaves 12/12 = 1.0000 (`each of the four arguments...`
        // still varies `isCarController` on its own), withholding both leaves 11/12 = 0.9167 and
        // still passes, and it takes a third -- `a hint is honoured from our own package and
        // refused from any other`, the only test that reaches the `HINT_CAR` arm from our own
        // package -- to reach 10/12 = 0.8333 and fail. Raising the minimum instead would have been
        // vacuous: JaCoCo validates it before comparing, so anything above 1.0 fails with "given
        // minimum ratio is 1.01, but must be between 0.0 and 1.0" against any code at all.
        //
        // Deliberately NOT given a LINE floor of its own, unlike `BrowseTree` and `BrowseText`
        // below: every one of `of`'s 10 lines sits under a branch this rule already gates, and the
        // remaining 9 are the two `setOf(...)` initialisers in `<clinit>`, which execute the moment
        // any test in the module touches the object at all. That is the unfireable-declaration
        // case the ride-along paragraph above describes, not the `BrowseTree` case.
        "app.muplay.model.browse.BrowseSurfaces",
        "app.muplay.model.browse.BrowseNode",
        "app.muplay.model.browse.BrowseCompletion",
        "app.muplay.model.browse.BrowseCompletionStatus",
        "app.muplay.model.browse.BrowseMediaType",
        "app.muplay.model.browse.BrowseStyle",
        //   `BrowsePaging`         Plan 5 Task 4. 6/6, from `BrowsePagingTest`. Four lines, and
        //                          every branch in them is a value Android Auto really sends: a
        //                          negative page, a non-positive size, a page past the end, and the
        //                          `Int.MAX_VALUE` page size whose Int product goes negative at
        //                          page 1. The upper clamp is the one that keeps the app alive --
        //                          `MediaLibrarySessionImpl.verifyResultItems` throws on the
        //                          session's own handler for a result longer than the page asked
        //                          for, which is a process death, measured on the emulator.
        //   `BrowseExtras`         Plan 5 Task 4. 11/11, from `BrowseExtrasTest`. The extras a car
        //                          head unit reads: a style hint only on a browsable node, and a
        //                          completion percentage only on a partially-played one. A `Map`
        //                          and not a `Bundle` precisely so this stays on the fast tier;
        //                          `BrowseItems.bundleOf` does the one Android-shaped step and is
        //                          gated in `:core:media`.
        "app.muplay.model.browse.BrowsePaging",
        "app.muplay.model.browse.BrowseExtras",
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
    // A LINE rule over the same classes the BRANCH rule above already lists, and it is not
    // redundant with it: eight of `BrowseId`'s twelve members -- the six `data object`s plus
    // `Library` and `Shuffle` -- carry **no BRANCH counters at all** (measured: BRANCH absent from
    // the report for every one of them), so the BRANCH rule above rides over them at NaN and
    // gates nothing about them at any minimum. What it cannot see is their `encode()` bodies,
    // which are the wire format itself -- the string Android Auto persists across a reinstall.
    // LINE can see exactly that, and this is the `SubsonicCredentials` argument one floor up
    // repeated for a different reason: a hand-written member with no branch in it still needs a
    // gate, and LINE is the only counter that has one.
    //
    // Measured today, all at 1.0000: `BrowseId$Companion` 20/20, `BrowseId$Track` 7/7,
    // `BrowseId$Book`/`$Album`/`$Artist` 3/3, `BrowseId$Library`/`$Shuffle` 2/2, and each of
    // `Root`/`Continue`/`Books`/`Albums`/`Artists`/`Libraries` 1/1. Verified fireable rather than
    // assumed so: with the three tests that call `encode()` on a `data object` moved aside, this
    // floor fails at `BrowseId.Root` 0/1 = 0.00 while the BRANCH rule above stays green -- the
    // whole reason it is here.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.model.browse.BrowseId",
        "app.muplay.model.browse.BrowseId*",
      ),
    ),
    // Plan 5 Task 2, and the `BrowseId` argument directly above applied to a bigger class for the
    // same structural reason: a CLASS-element BRANCH rule can only see the code that branches.
    // `BrowseTree` is 130 lines of which only a minority sit under a branch -- `bookChildren`,
    // `artistNodes`, `shuffleNode`, `folder`, `albumChildren`, `artistChildren` and
    // `albumChildrenOfArtist` contain no `if`/`when`/`?:` at all -- so its BRANCH ratio stays at
    // 1.0000 while whole builders go untested. That is not hypothetical here: `albumChildren` is
    // pure delegation to `songNodes`, and delegation-with-the-argument-dropped is one of this
    // project's own recorded defect classes.
    //
    // Measured today, all at 1.0000: `BrowseTree` 130/130, its five synthetic comparator classes
    // 1/1 or 2/2 each, `BrowseText` 13/13 and `BookSummary` 14/14.
    //
    // Verified fireable rather than assumed so, and verified to catch what the BRANCH rule above
    // cannot: with the two `bookChildren` tests, the `artistNodes` test, the childStyle test and
    // the tree-wide credential test moved aside -- five tests, none of which uniquely covers any
    // branch -- this floor fails at `BrowseTree` 106/130 = 0.8154 **while the BRANCH rule above
    // stays green at 49/49**. Raising the minimum instead would have been vacuous: JaCoCo
    // validates the minimum before comparing, so `1.01` fails with "given minimum ratio is 1.01,
    // but must be between 0.0 and 1.0" exactly as it does against zero-coverage code.
    //
    // The enums and the bodiless `data class`es are deliberately **not** here -- see the
    // ride-along paragraph above the BRANCH rule for why a LINE floor over a declaration is a
    // floor that cannot fail.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.model.browse.BrowseTree",
        "app.muplay.model.browse.BrowseTree*",
        "app.muplay.model.browse.BrowseText",
        "app.muplay.model.BookSummary",
        // Plan 5 Task 4: `BrowsePaging` 5/5 and `BrowseExtras` 24/24, both from JVM data alone.
        // They carry the BRANCH rule above as well, and ride here for the reason every other
        // pure-data class in this module does: `BrowseExtras` is mostly `put`s, and a deleted key
        // moves no branch.
        "app.muplay.model.browse.BrowsePaging",
        "app.muplay.model.browse.BrowseExtras",
      ),
    ),
  ),
  ":core:network" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
  ":core:testing" to listOf(CoverageFloor(counter = "BRANCH", minimum = BigDecimal("0.90"))),
  // See coverageFloors's own doc above for why three CLASS-element rules, not one BUNDLE rule.
  ":feature:setup" to listOf(
    // 12/12 -- SetupViewModel's own branches: connect's InvalidUrl check and its widened catch
    // cascade (SubsonicErrorException / SubsonicHttpException / CancellationException-rethrow /
    // generic Exception), tagging's isNotEmpty()-&&-none{UNASSIGNED} conjunction, setRole's
    // serverInfo?.let null guard, and continueToLibrary's own isNotEmpty()-&&-none guard --
    // all fully covered by
    // SetupViewModelTest, including two that measured 0% before their own tests existed (see
    // coverageFloors's own doc above for which tests and why).
    //
    // SetupViewModel* rides along, matching six compiled nested classes the Task 8 Hilt
    // constructor-injection seam and each viewModelScope.launch body produce: the two @Inject
    // secondary constructor's anonymous SetupCredentialSink/SetupLibrarySink objects
    // (SetupViewModel$2 2/2 LINE, SetupViewModel$3 4/4 LINE, no branches of their own) and four
    // per-method coroutine bodies (SetupViewModel$connect$1 17/17 LINE, no branches at this level;
    // $setRole$1 2/2 BRANCH; $continueToLibrary$1 8/8 BRANCH; $tagging$1, no counters at all).
    // None of these existed under the defaulted-lambda seam this task removed -- see this file's
    // own note (coverageFloors's own doc above) on why the SetupViewModel*1/*2 floor (0.55) this
    // replaces was deleted: not because its patterns stopped matching anything (they did not --
    // that was this comment's own original, incorrect claim, corrected on re-review), but because
    // every class either pattern still matches is also matched by this wider rule at a stricter
    // minimum, making the old floor pure redundancy.
    //
    // SetupUiState/SetupUiState* ride along in the same rule (0 branches of their own, so they can
    // never move this ratio) purely so warnUngatedClasses never has to flag their own
    // fully-covered lines as an ungated class -- real classes with real state to protect, just not
    // branch-shaped ones.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.setup.SetupViewModel",
        "app.muplay.setup.SetupViewModel*",
        "app.muplay.setup.SetupUiState",
        "app.muplay.setup.SetupUiState*",
      ),
    ),
    // 7/8 -- SetupFailureReason.toMessage's when-cascade; SetupFailureReasonTest covers all three
    // members plus both sides of Rejected's detail null/non-null branch. The one branch still
    // missing is an artifact of how a `when` over a sealed interface with no `else` compiles, not
    // a reachable path SetupFailureReasonTest is missing a case for. SetupFailureReason/
    // SetupFailureReason$* (the sealed interface and its members) ride along for the same reason
    // SetupUiState does above -- 0 branches of their own, included only so their own fully-covered
    // lines never show up as an ungated class. Untouched by Task 8.
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
    // 81/83 = 0.9759 LINE -- the one Compose-bearing file in this module, gated on LINE rather
    // than BRANCH per the ruling in this table's own doc. Reachable only because FirstRunJourneyTest
    // composes SetupScreen for real on an emulator, down every terminal state -- a successful
    // connect, a rejection, and (added this task) every library tagged through to Continue
    // reaching Ready. That last journey is why the ratio moved: without it, the `is
    // SetupUiState.Ready -> Text("Setup complete")` line still read as JaCoCo-"covered", because
    // Kotlin compiles an exhaustive sealed `when` as an `instanceof` chain and the Ready check's
    // own dispatch instructions run on every composition regardless of which state is current --
    // the line was lighting up green with the branch behind it, and the text it renders, never
    // once executed. completingEveryTagReachesReadyAndShowsSetupComplete closes that specifically.
    // The 2 lines still missing are the private SetupScreen overload's declaration line and its
    // `when` block's closing brace -- see coverageFloors's own doc above for what is and is not
    // known about them.
    //
    // SetupScreenKt* rides along, matching the LaunchedEffect(uiState) { ... } body Task 8 added
    // (SetupScreenKt$SetupScreen$1$1, 3/3 LINE). Both classes' BRANCH counters -- 81/130 and 3/4
    // respectively, covered/total like every other ratio in this file (an earlier version of this
    // comment wrote them covered/missed, 81/49 and 3/1, which reads as a ratio above 1 and was
    // corrected on re-review) -- are deliberately left ungated: Compose codegen, not author logic.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.setup.SetupScreenKt", "app.muplay.setup.SetupScreenKt*"),
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
    // `SyncDecision` is Task 6's own JVM-measurable class: a sealed interface with a pure
    // `companion object` rule and no collaborators, so `SyncDecisionTest` (a plain JVM test)
    // reaches its branches with no emulator -- 6/6, confirmed with the instrumented `.ec` file
    // physically absent (same check `MirrorMapper*` above and task-5-report.md used). `*` because
    // the `when` lives in `SyncDecision$Companion`, not the bare interface name, which carries no
    // branches of its own and would leave `SyncDecision.Companion` unmatched by a literal include.
    // The sealed subtypes (`UpToDate`, `ScanInProgress`, `Reconcile`) ride along at zero branches
    // each (JaCoCo's isNaN pass), the same way `MirrorMapper`'s own nested lambda class does.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.SyncDecision*"),
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
    // CredentialStore's own author-written branches. 16/16 after Plan 2 Task 2 added the
    // partial-write, missing-key and unopenable-blob recovery paths; those five branches were
    // genuinely untested rather than codegen -- the class measured 11/16 before them -- which is
    // why this is a BRANCH rule and not an excuse for one.
    //
    // **12/12 as of Plan 7 Task 2**, and the drop is the whole point of that task rather than a
    // regression: `clear`'s `containsAlias` guard and `secretKey`'s create-or-fetch `if` moved
    // into `KeystoreKeys` (gated by its own rule below) and `read`'s inline `containsAlias` check
    // became a `?: return null` on `KeystoreKeys.find`, which is the same two branches in a
    // different shape. Nothing stopped being covered -- 8 of the 12 are `read`'s four `?:`
    // guards, the other 4 are `runCatching { }.map { }.getOrNull()` inlining `Result`'s own
    // `isSuccess`/`isFailure` checks into this class's bytecode. `CredentialStoreTest` was not
    // edited for any of it.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.CredentialStore"),
      requiresInstrumentedData = true,
    ),
    // Plan 7 Task 2. `KeystoreKeys`, the Android Keystore plumbing extracted out from under
    // `CredentialStore` so a second store reuses the mechanism rather than copying it. Measured
    // **6/6 BRANCH**: `find`'s `containsAlias` guard, `getOrCreate`'s `find(...) ?: generate(...)`
    // and `delete`'s `containsAlias` guard, each exercised both ways.
    //
    // `requiresInstrumentedData` because there is no JVM tier for it at all: `AndroidKeyStore` is
    // a device-only provider, which is precisely why `KeystoreCipher` was built to take a
    // `SecretKey` rather than fetch one (its own floor, at the top of this list, is the fast tier's
    // half of the same split).
    //
    // Honest about what this floor does and does not add, because it was checked rather than
    // assumed: `CredentialStoreTest` alone already reaches all six branches through `save`/`read`/
    // `clear`, so withholding the whole of `KeystoreKeysTest` does **not** make this floor fail --
    // measured, not reasoned. What `KeystoreKeysTest` adds is the property no ratio can express:
    // that the `alias` argument is actually used, proved by two aliases at once. That is a
    // mutation-shaped defect, not a coverage-shaped one, and it is recorded in task-2-report.md.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.KeystoreKeys"),
      requiresInstrumentedData = true,
    ),
    // LibraryRepository's author-written branches. Originally just `hasUnassignedLibraries`'s
    // `.isNotEmpty()` boolean check (2/2, `unassignedLibrariesAreReported...` exercises it both
    // true and false); Task 6's fix round 1 (task-6-review.md F-1) added a second,
    // `refreshFromServer`'s `folders.isEmpty() && libraryDao.allIds().isNotEmpty()` guard against
    // an empty `getMusicFolders` response deleting every already-tagged library --
    // `refreshingWithNoLibrariesReportedRefusesToDeleteTheOnesAlreadyTagged` and
    // `refreshingWithNoLibrariesOnAFreshMirrorSucceeds` cover both operands both ways. Every other
    // method here is either a straight-line suspend delegation to `LibraryDao` or the `Flow.map`
    // in `libraries`, whose own lambda classes are covered by the LINE catch-all below, not this
    // rule. Measured 8/8 (JaCoCo counts each `&&` operand as its own branch pair).
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
    // `SyncEngine`'s own author-written conditionals: the `SyncDecision` `when`, the null check on
    // an absent watermark, `fetchAllAlbums`'s paging loop (the short-page early return and the
    // `MAX_PAGES` bound), and -- as of Task 6's fix round 1 (task-6-review.md F-3) -- the
    // constructor's `require(albumPageSize in 1..SubsonicClient.MAX_ALBUM_LIST_PAGE)` guard.
    // Room and SQLite make this instrumented-only. Originally 12/12 --
    // `aServerWithNoLastScanReconcilesButStoresNoWatermark` (the null-watermark branch, previously
    // uncovered: `watermark?.let { watermarkDao.store(it) }` collapsed to
    // `watermarkDao.store(watermark!!)` passed every other test in this file) and
    // `aServerThatNeverSendsAShortPageFailsAtTheBoundRatherThanHanging` (the `MAX_PAGES` exit,
    // genuinely reached with `SyncEngine.MAX_PAGES` fake in-memory calls, not asserted on the
    // constant) were what closed the original two gaps -- now 18/18, `constructingWithAPageSize
    // OutsideTheClientsClampFailsLoudly` exercising the new `require`'s two ends. `SyncEngine*`
    // (not the bare name): the four suspend-body continuation classes it compiles to carry zero
    // counters of any kind here (not the partial 0.50-0.67 the `Flow.map`/suspend families below
    // measure), so they ride along for free rather than needing their own lower-floor rule.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.SyncEngine*"),
      requiresInstrumentedData = true,
    ),
    // `ShuffleRepository`'s one author-written branch: `shuffle`'s early `if (returned.isEmpty())`
    // return, which `anEmptyServerResponseIsAnEmptyResultRatherThanAnError` exercises true and
    // every other `ShuffleRepositoryTest` case exercises false. The scope guard itself
    // (`returned.filter { it.id in confirmed }`) is a lambda with no branch of its own in this
    // class's bytecode -- its predicate's `Set.contains` compiles to a method call, not a branch
    // instruction here, which is exactly why `aSongFromAnotherLibraryIsDroppedAndCounted` and
    // `aSongTheMirrorHasNeverSeenIsDropped` are proved by mutation (task-7-report.md) rather than
    // by this floor: a BRANCH rule cannot tell `returned.filter { it.id in confirmed }` apart from
    // `returned` unfiltered, since both compile to the same 2 branches in this method. Measured
    // 2/2 from a real `jacocoTestReport` run (instrumented-only, like every other repository
    // here).
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.ShuffleRepository"),
      requiresInstrumentedData = true,
    ),
    // Everything whose value is "did this line run at all": the Room database class, the Hilt
    // providers, the entities that need an emulator to be reached at all (LibraryEntity,
    // MediaProgressEntity -- unlike the three mirror entities above, nothing JVM-side ever
    // constructs these), `LibraryDao`, the Task 4/5 classes with no branch of their own
    // (LibraryEntity, NotConfiguredException, MirrorReplacement), and every class above that also
    // has its own BRANCH rule (including `BrowseDao`, whose own `require` branches are gated
    // above; it stays listed here too for its LINE ratio, the same dual-listing `LibraryRepository`
    // and `SubsonicSourceProvider` already have -- `ShuffleRepository` now too, 10/10 LINE).
    // No separate BRANCH entry for
    // LibraryDao/LibraryEntity/MediaProgressEntity/MirrorReplacement -- they contain no
    // author-written conditional, so a BRANCH rule would match only zero-total counters and pass
    // silently at every minimum through JaCoCo's isNaN branch.
    //
    // Task 6 adds five more, all instrumented-only for the same reason (Room/SQLite): `SyncEngine`
    // itself (dual-listed for LINE alongside its own BRANCH rule above -- originally 42/44, the
    // two misses both inside `catch (e: CancellationException) { throw e }`, never exercised
    // because nothing in the suite cancelled the engine's own coroutine; fix round 1's F-6 closed
    // that with `cancellationPropagatesRatherThanBecomingAFailure`, now 48/48);
    // `SyncWatermarkEntity` (5/5, a plain `data class` like the three mirror entities, but reached
    // only through `SyncWatermarkDao.store` -- nothing JVM-side constructs it, confirmed with the
    // instrumented `.ec` physically absent); `SyncWatermarkDao` (2/2, its own `store` is the only
    // author-written line, `read`/`clear`/`upsert` are Room-generated); `SyncState*` (the
    // wildcard for `Synced`/`Failed`, both 1/1 -- `UpToDate`/`ScanInProgress` are zero-counter
    // `data object`s that ride along the same way `SyncDecision`'s own sealed subtypes do above);
    // and, new in fix round 1, `EmptyLibraryListException` (2/2 -- the same shape as
    // `NotConfiguredException` alongside it, a typed exception with no branches of its own,
    // constructed by `LibraryRepository.refreshFromServer`'s F-1 guard and read back by both
    // `LibraryRepositoryTest` and `SyncEngineTest`).
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.MuPlayDatabase",
        "app.muplay.database.CredentialStore",
        "app.muplay.database.CredentialStore*Companion",
        // Plan 7 Task 2, 19/19 LINE. Listed here rather than given a rule of its own for the
        // reason this rule exists: its value is "did this line run at all", and its branches are
        // gated separately above.
        "app.muplay.database.KeystoreKeys",
        "app.muplay.database.di.DataModule",
        "app.muplay.database.entity.MediaProgressEntity",
        "app.muplay.database.entity.LibraryEntity",
        "app.muplay.database.entity.SyncWatermarkEntity",
        "app.muplay.database.dao.LibraryDao",
        "app.muplay.database.dao.BrowseDao",
        "app.muplay.database.dao.MirrorReplacement",
        "app.muplay.database.dao.SyncWatermarkDao",
        "app.muplay.database.LibraryRepository",
        "app.muplay.database.SubsonicSourceProvider",
        "app.muplay.database.BrowseRepository",
        "app.muplay.database.ShuffleRepository",
        "app.muplay.database.NotConfiguredException",
        "app.muplay.database.EmptyLibraryListException",
        "app.muplay.database.SyncEngine*",
        "app.muplay.database.SyncState*",
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
    // `ShuffleRepository*` rides along too, for the same reason: `shuffle`'s own suspend
    // continuation (`ShuffleRepository$shuffle$1`) and `ShuffleRepository$Companion` (a bare
    // `const val`, so no getter and no counters at all -- unlike a plain `val` companion member)
    // both measure 0/0 LINE, JaCoCo's isNaN branch, not "excluded".
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
        "app.muplay.database.ShuffleRepository*",
      ),
      excludes = listOf(
        "app.muplay.database.CredentialStore",
        "app.muplay.database.CredentialStore*Companion",
        "app.muplay.database.LibraryRepository",
        "app.muplay.database.SubsonicSourceProvider",
        "app.muplay.database.BrowseRepository",
        "app.muplay.database.ShuffleRepository",
      ),
      requiresInstrumentedData = true,
    ),
    // Plan 6 Task 2. `RendererStore`, the remembered-speaker store, measured **8/8 BRANCH** and
    // **15/15 LINE** against a real DataStore file on the emulator. Four of those branches decide
    // whether a record on disk is usable at all -- `decode`'s `parts.size != 3` and `forget`'s
    // `decode(it)?.udn` safe call, plus `load`/`forget`'s `orEmpty()` on an absent key -- and
    // every one was closed by a test that writes the raw preference set by hand
    // (`aRecordThatIsNotAWholeTripleIsSkippedAndItsNeighboursAreKept`,
    // `forgettingWorksAlongsideARecordThatCannotBeDecoded`,
    // `forgettingFromAStoreThatWasNeverWrittenIsHarmless`).
    //
    // The other four arrived in Task 2's fix round: `encode` now refuses a record whose UDN or
    // description URL contains the separator, instead of assuming neither can. Both operands of
    // that `||` are observed both ways by one test with three records --
    // `aUdnOrUrlContainingTheSeparatorIsRefusedRatherThanShiftingTheFieldsAfterIt` -- because a
    // guard that only ever refuses is a store that remembers nothing. Was 4/4 and 14/14.
    //
    // `requiresInstrumentedData` because DataStore is an Android type: there is no JVM tier for
    // this class at all, and every number above came from `connectedDebugAndroidTest`.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.database.RendererStore"),
      requiresInstrumentedData = true,
    ),
    // The same class's LINE, and its coroutine artefacts, at the full 0.90 rather than at the
    // 0.50 the rule above this one carries for `CredentialStore`'s and `BrowseRepository`'s --
    // because these measure 1.0000 and there is no reason to gate them lower than they are.
    // `RendererStore$remember$2` (1/1) and `RendererStore$forget$2` (5/5) are the `dataStore.edit`
    // lambda bodies; `$load$1`/`$remember$1`/`$forget$1` are suspend continuations carrying no
    // LINE counter at all (0/0, JaCoCo's isNaN pass, which is not the same as excluded).
    //
    // Their BRANCH is deliberately not gated: `remember$2` measures 1/2 and `forget$2` 5/6, and
    // in both cases the missing branch is the coroutine state machine's own `label` check, whose
    // other arm throws `IllegalStateException("call to 'resume' before 'invoke' with coroutine")`
    // and is unreachable by construction. That is the same "gating the compiler" argument this
    // table already makes about Compose codegen.
    //
    // `CastPreferences` rides along: a `@Qualifier` annotation class with no counters of any kind,
    // included so `warnUngatedClasses` never has to mention it.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.RendererStore*",
        "app.muplay.database.CastPreferences",
      ),
      requiresInstrumentedData = true,
    ),
    // ---- Plan 5 Task 4: the browse tree's data layer ------------------------------------------
    // `BrowseTreeRepository` 82/82, `MirrorBookshelf` 10/10 and `BookProgress` 14/14 = 1.0000
    // BRANCH, instrumented.
    //
    // BRANCH and not LINE, and this is the module's most branch-shaped code: `BrowseTreeRepository`
    // is two exhaustive `when`s over a sealed `BrowseId` plus a `?.let` per lookup, and **every one
    // of those null arms is a different answer a car renders differently** -- "this is not a folder"
    // rather than "this folder is empty". A LINE floor over it is satisfied by a tree that answers
    // every unknown id with an empty list. Eighty-two branches took twelve assertions to reach, and
    // the last seven were found by reading this report rather than by reasoning: an unknown library,
    // an unknown album, an unknown artist, an audiobook-only install, a cover-art URL that throws,
    // and the root's own `node` arm. All are now driven by `BrowseTreeBrowserTest`.
    //
    // `requiresInstrumentedData` because Room needs a device, and because the execution data comes
    // from `:core:media`'s connected suite rather than this module's own -- `Jacoco.kt`'s
    // `mergedExecutionData` globs every project's `.ec`, which is the mechanism that lets a browse
    // stack assembled in `:core:media`'s androidTest gate `:core:database`'s classes.
    //
    // **`MirrorBookshelf` and `BookProgress` are Plan 4 Task 4's to delete or reconcile** -- see
    // `Bookshelf`'s own provenance note. Whoever does that owns re-measuring this entry rather than
    // carrying the numbers forward.
    //
    // Falsified by moving `:core:media`'s connected `.ec` aside: all three fired on BRANCH at
    // **0.00**, BUILD FAILED. On LINE the residuals are **not** zero and are recorded as measured --
    // `BrowseTreeRepository` 0.09 and `MirrorBookshelf` 0.15 -- because `:app`'s device journey
    // starts the real service, which builds these objects through Hilt even though it never browses.
    // Constructor lines are not coverage of a browse decision, and the LINE rule below fires on both
    // of them anyway; the point of writing the numbers down is that "0.00" would have been false.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.BrowseTreeRepository",
        "app.muplay.database.MirrorBookshelf",
        "app.muplay.database.BookProgress",
      ),
      requiresInstrumentedData = true,
    ),
    // The same three classes' LINE -- 55/55, 32/32 and 16/16 -- plus `BookPosition` 4/4, which
    // carries **zero BRANCH counters** (JaCoCo's Kotlin data-class filter removes the generated
    // `equals`/`hashCode`/`copy` entirely) and so needs a rule on the counter it actually has. It is
    // listed by name rather than folded in as `Book*`, because a BRANCH rule over a set containing
    // a branchless class gates the branchless one at nothing.
    //
    // The `$children$1`/`$node$1`/`$books$1` suspend continuations these patterns do not name carry
    // zero counters of either kind, so `warnUngatedClasses` skips them and no rule could gate them
    // -- the standing exception this table records for every `$Companion`.
    //
    // Falsified with the same `.ec` moved aside -- all four fired, BUILD FAILED, at
    // `BrowseTreeRepository` 0.09, `MirrorBookshelf` 0.15, `BookProgress` 0.00 and `BookPosition`
    // 0.00. See the BRANCH rule above for why two of those are not zero.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.database.BrowseTreeRepository",
        "app.muplay.database.MirrorBookshelf",
        "app.muplay.database.BookProgress",
        "app.muplay.database.BookPosition",
      ),
      requiresInstrumentedData = true,
    ),
  ),
  // `:core:media`. Every rule here is `"CLASS"`-element, and the split across the two tiers is the
  // point: the *decision* about a 429 is a plain object the fast tier can hold to a floor, and
  // everything Media3 or OkHttp touches is only reachable on a device. Re-measure in Task 10, once
  // the service, the queue and the progress writer are in. (The count of rules is deliberately not
  // written down. It said "Four" from Task 2 until Task 3's fix round, through three tasks that
  // each added rules -- the same staleness `ci/mutation-probes.sh`'s header describes about its
  // own probe count, and the same remedy: do not carry a number nothing derives.)
  //
  // Every class this module compiles that carries a counter at all is matched by exactly one rule
  // below; the only classes left over are `MuPlayDataSourceFactory$Companion`,
  // `NavidromeLoadErrorHandlingPolicy$Companion`, `QueueRepository$Companion`,
  // `QueueRepository$mediaItems$1`, the `ResumePolicy` interface and the `@Qualifier` annotation
  // class `app.muplay.media.di.MediaHttpClient`, every one of which measures zero branches *and*
  // zero lines, so [UngatedClassChecker.warnUngatedClasses] skips them and no rule can gate them
  // anyway. (`MediaHttpClient` was named only inside one rule's own comment before this round,
  // which is the wrong place for it: this paragraph is the list a reader checks.)
  // Dagger's `NavidromeLoadErrorHandlingPolicy_Factory$InstanceHolder` -- the holder it emits for
  // a *scoped, no-argument* `@Inject` constructor -- was in this report until
  // `generatedCodeExcludes` (`Jacoco.kt`) grew the pattern for that shape; see its own note.
  ":core:media" to listOf(
    // 15/16 = 0.9375 BRANCH from **JVM data alone** -- `StreamRetryPolicyTest`, ten tests, no
    // emulator. Confirmed by deleting the instrumented `.ec` and running
    // `jacocoJvmCoverageVerification`, which is what `requiresInstrumentedData = false` claims
    // here. That is the whole reason `StreamRetryPolicy` exists as a separate type from the Media3
    // adapter: the branch that decides whether a 429 kills playback is gated by the fast tier.
    //
    // The one missed branch is unreachable and stays in the denominator honestly: the `?.` the
    // compiler emits after `String.trim()` in
    // `retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { .. }` can never take its null path,
    // because `trim()` returns a non-null `String`. 0.90 leaves that single dead branch of room
    // and no more -- one genuinely-uncovered branch takes this to 15/17 = 0.88 and fails. Watched
    // failing at a minimum of 0.95: "branches covered ratio is 0.93, but expected minimum is
    // 0.95", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.StreamRetryPolicy"),
    ),
    // 4/4 = 1.0000 LINE, also JVM-only (`MediaModuleTest`). LINE and not BRANCH because
    // `MediaModule` has **no branches at all** -- a BRANCH rule over it would match a class with
    // zero counters of its own kind, which JaCoCo scores `NaN` and reports as "no violation" at
    // every minimum. That is the vacuous-floor shape this table's own doc describes, and
    // `warnVacuousFloors` would have said so on every run.
    //
    // Gating it matters more than four lines suggests: those four lines are the media layer's
    // timeout policy, and the decision they encode is *not* setting a `callTimeout` -- a limit
    // that would cut off any track longer than the cap. Until `MediaModuleTest` existed that
    // decision was protected by a comment. Watched failing with that test moved aside: 0/4.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.di.MediaModule"),
    ),
    // 6/6 = 1.0000 BRANCH, instrumented. `NavidromeLoadErrorHandlingPolicy` is the adapter between
    // Media3's `LoadErrorInfo` and the decision above, and its branches are the passthrough:
    // is-an-`InvalidResponseCodeException`, and did the policy have an opinion. Both are driven by
    // `NavidromeLoadErrorHandlingPolicyTest`, on a device, because every input type in that
    // signature is a Media3 or Android type (`DataSpec` holds an `android.net.Uri`) and this
    // project has no Robolectric.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.NavidromeLoadErrorHandlingPolicy"),
      requiresInstrumentedData = true,
    ),
    // 11/11, 14/14, 16/16 and 2/2 = 1.0000 LINE, instrumented. `MuPlayDataSourceFactory` carries
    // no branches, so LINE is the only counter that can gate it at all -- same argument as
    // `MediaModule` above, reached from the other tier. `NavidromeLoadErrorHandlingPolicy` rides
    // here as well as carrying its own BRANCH rule: its `getRetryDelayMsFor` body is the module's
    // one place where a line can be added that adds no branch.
    //
    // `RequestedUriDataSource*` joined in Task 3's fix round -- the wrapper that stops a redirect
    // target reaching `exo_redir`, and its `Factory`. The pattern covers both classes and both are
    // branchless by construction: `getUri()` returns the requested URI unconditionally rather than
    // falling back to the delegate's, which is one fewer branch *and* an unconditional guarantee
    // instead of a stateful one. Ten forwarding methods is ten chances to forget one, and Media3
    // ships no `ForwardingDataSource` to inherit from, so
    // `MediaCacheTest.theUpstreamWrapperAnswersTheRequestedUriAndForwardsEverythingElse` drives
    // every one of them through a hand-written recording delegate -- which is what takes it to
    // 16/16 rather than the 6-of-10 an end-to-end playback alone reaches.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.MuPlayDataSourceFactory",
        "app.muplay.media.NavidromeLoadErrorHandlingPolicy",
        "app.muplay.media.RequestedUriDataSource*",
      ),
      requiresInstrumentedData = true,
    ),
    // ---- Plan 3 Task 4: the queue, and the two classes that turn it into MediaItems -----------
    // 10/10 = 1.0000 BRANCH from **JVM data alone** -- `PlaybackQueueTest`, six tests, no
    // emulator. Measured by deleting the connected run's `.ec` and re-reporting, which is what
    // `requiresInstrumentedData = false` claims here; the ten branches are the two `require`s in
    // `init` (`isNotEmpty`, and `startIndex in songs.indices`, which the compiler emits as a range
    // check with several arms), and every one of them is driven by a test that asserts the
    // resulting *message*, not merely that something was thrown.
    //
    // Falsified the other way round, because a floor measuring exactly 1.0000 cannot be falsified
    // by raising its minimum: JaCoCo validates `minimum` *before* comparing anything, so 1.01
    // fails with "Rule violated for class app.muplay.media.PlaybackQueue: given minimum ratio is
    // 1.01, but must be between 0.0 and 1.0" -- measured here, and the identical message
    // zero-coverage code would produce, which proves nothing about any test. Watched failing with
    // `PlaybackQueueTest` moved aside instead: "Rule violated for class
    // app.muplay.media.PlaybackQueue: branches covered ratio is 0.00, but expected minimum is
    // 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackQueue"),
    ),
    // `PlaybackQueue$Companion` -- the `of` factory -- measures 1/1 LINE and **no branches at
    // all**, so it needs a rule of its own on the counter it actually carries: a BRANCH rule over
    // it would be the vacuous, NaN-scored shape this table's own doc describes, and
    // `warnVacuousFloors` would say so on every run. It is not folded into the rule above as
    // `PlaybackQueue*` for exactly that reason -- that pattern matches both classes, and a BRANCH
    // rule over a set containing a branchless class still gates the branchless one at nothing.
    //
    // `*Companion`, not `$Companion`: a literal `$` in a pattern never matches (see this table's
    // own doc, gotcha 3). JVM-measurable, same run as above. Watched failing with
    // `PlaybackQueueTest` moved aside: "Rule violated for class
    // app.muplay.media.PlaybackQueue.Companion: lines covered ratio is 0.00, but expected minimum
    // is 0.90".
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackQueue*Companion"),
    ),
    // `MediaItems` 2/2 and `QueueRepository` 2/2 = 1.0000 BRANCH, instrumented -- both measure
    // 0/2 from JVM data alone, because `MediaItem` is built on `android.net.Uri` and there is no
    // Robolectric here. Two branches each, and both are the same decision seen at two layers: is
    // there cover art (`song.coverArtId?.let` in the repository, `artworkUri?.toUri()` in the
    // mapping). Real branches, so this floor is not vacuous -- but two of them over a
    // seventeen-line field-by-field mapping is a thin gate on its own, which is what the LINE rule
    // below is for.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.MediaItems", "app.muplay.media.QueueRepository"),
      requiresInstrumentedData = true,
    ),
    // 18/18 and 10/10 = 1.0000 LINE, instrumented. Both classes ride here *as well as* carrying
    // the BRANCH rule above -- the same shape `NavidromeLoadErrorHandlingPolicy` already has in
    // this table.
    //
    // **What this rule does NOT do**, corrected from the claim that stood here through one review:
    // it does not notice a deleted mapped field. The old text said `MediaItems.of` is one builder
    // chain, so a whole field can be deleted without moving its BRANCH counter -- true -- and then
    // that "LINE is the counter that notices" -- false. `COVEREDRATIO` is
    // `covered/(missed+covered)` (this file's own `warnVacuousFloors` says so, and JaCoCo's
    // `Limit.check` computes it), so deleting `.setTitle(song.title)` takes the *numerator and the
    // denominator down together*: 18/18 becomes 17/17, still exactly 1.0000, and nothing goes red.
    // A LINE floor can only ever be moved by an **added, untested** line, never by a deleted
    // covered one. Even that is coarse at this minimum -- one untested line among these eighteen
    // scores 18/19 = 0.9474 and passes; it takes three (18/21 = 0.8571) to breach 0.90 -- so what
    // this rule really gates is a class drifting into a substantially untested block, which is a
    // real thing to gate and worth keeping. `MediaItems.setDurationMs` was added under exactly
    // this rule and had to arrive with the test that covers it.
    //
    // The thing that actually catches a deleted field is `MediaItemsTest`: one assertion per
    // mapped field, each observing a pair of dissimilar songs. There is no coverage counter that
    // substitutes for it, and a reader who believed the old sentence would have thought there was.
    //
    // `QueueRepository$Companion` (`ARTWORK_SIZE_PX`) and `QueueRepository$mediaItems$1` (the
    // suspend continuation) carry zero branches *and* zero lines, so `warnUngatedClasses` skips
    // them and no rule can gate them -- the same standing exception this module already records
    // for two other `$Companion`s above.
    //
    // Watched failing with the connected run's `.ec` -- the execution data these two classes'
    // only tests produce -- moved aside: "Rule violated for class app.muplay.media.MediaItems:
    // lines covered ratio is 0.00, but expected minimum is 0.90", BUILD FAILED, alongside the same
    // for `QueueRepository` and for both classes' BRANCH rule.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.MediaItems", "app.muplay.media.QueueRepository"),
      requiresInstrumentedData = true,
    ),
    // Plan 3 Task 8a: the resume policy. 1/1 = 1.0000 LINE each for `NeverResume` and
    // `ResumeTarget`, from **JVM data alone** (`ResumePolicyTest`, nine tests, no emulator) --
    // which is the whole reason `ResumePolicy` takes media ids and an index rather than
    // `MediaItem`s: the one thing in this application allowed to choose a playback position is
    // gated by the fast tier.
    //
    // LINE and not BRANCH, and that is a measurement rather than a preference: both classes have
    // **zero BRANCH counters** (`NeverResume.resolve` is one unconditional expression, and JaCoCo's
    // Kotlin data-class filter removes `ResumeTarget`'s generated equals/hashCode/copy entirely, so
    // it reports a single line and no branches at all). A BRANCH rule over them was written and
    // run before this one: `warnVacuousFloors` reported it as enforcing nothing -- "all 2 classes
    // it matches have zero BRANCH counters ... JaCoCo reports no violation for it and never will,
    // at this or any other minimum" -- which is exactly the vacuous shape this table's own doc
    // describes. Same argument as `MediaModule` above, reached from a different direction.
    //
    // Watched failing, with `ResumePolicyTest` moved aside: both classes drop to 0/1 and the build
    // fails naming the ratio -- "Rule violated for class app.muplay.media.NeverResume: lines
    // covered ratio is 0.00, but expected minimum is 0.90", BUILD FAILED, once per class. That is
    // the only way to watch a floor already measuring 1.0000 fail, and it is worth writing down
    // why: raising `minimum` above the measured ratio, which is how every fractional floor in this
    // table was falsified, is not available here. JaCoCo validates the minimum before it compares
    // anything, so `BigDecimal("1.01")` fails with "given minimum ratio is 1.01, but must be
    // between 0.0 and 1.0" -- a configuration error that would have gone red against *any* code,
    // including code with no coverage at all, and therefore proves nothing about this floor.
    //
    // `ResumePolicy` itself is deliberately not listed: an interface with a single abstract method
    // compiles to zero counters of either kind, so `warnUngatedClasses` skips it and no rule could
    // gate it anyway -- the same reason the two `$Companion` classes noted above are absent.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.NeverResume", "app.muplay.media.ResumeTarget"),
    ),
    // ---- Plan 3 Task 8b: the seam that applies the decision, and the writer -------------------
    // `MuPlayer` 11/11 = 1.0000 LINE, instrumented. LINE and not BRANCH because the class has
    // **zero BRANCH counters**: six overrides and one funnel, not a conditional among them, which
    // is the point of it -- a seam with a branch in it is a seam with a way past it. A BRANCH rule
    // here would be the vacuous shape this table's own doc describes and `warnVacuousFloors` would
    // say so on every run, exactly as it did for `NeverResume` one entry above.
    //
    // Eleven lines is small, and every one of them is load-bearing in the way this project keeps
    // finding: an overload that is not overridden does not fail to compile, does not fail at
    // runtime, and does not fail any test of the policy -- it just lets a `MediaController` in a
    // car set whatever position it likes. What actually gates that is `MuPlayerTest`'s
    // `everyOverloadIndividuallyLandsThePolicysAnswerAndNotTheCallers`, and all six deletions were
    // applied by hand against a device run rather than trusted; see task-8b-report.md for the
    // transcript. This floor is the backstop that notices an untested line being *added*.
    //
    // Falsified by moving the connected run's `.ec` aside -- the only execution data this class has
    // -- exactly as the `ContentTypeSwitcher` entry below was: "Rule violated for class
    // app.muplay.media.MuPlayer: lines covered ratio is 0.00, but expected minimum is 0.90",
    // BUILD FAILED.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.MuPlayer"),
      requiresInstrumentedData = true,
    ),
    // `ProgressWriter` 16/16 and its `write` body 12/12 = 1.0000 BRANCH, instrumented.
    //
    // BRANCH is the counter that matters for this class and LINE could not replace it: the whole
    // read-modify-write is three elvis operators and one `||`, and covering either side of any of
    // them satisfies a LINE floor. `existing?.speed ?: DEFAULT_SPEED` with only the `existing ==
    // null` arm driven is a writer that has never been shown a row it must not clobber, which is
    // the data-loss defect the class documentation is about; `isFinished = finished ||
    // (existing?.isFinished ?: false)` with only one arm driven is a writer that un-finishes a
    // book. Both are lines that run.
    //
    // The 16 are `ProgressWriter`'s own -- the silence-skip reason check, the two `?: return`s, the
    // `!isPlaying` guard, `STATE_IDLE || STATE_ENDED`, the `finished =` expression and
    // `ticker?.cancel()` -- and the 12 are `write$2`, the `withContext(Dispatchers.IO)` body where
    // the elvis defaults compile to. `*`, not a literal `$`: this table's own doc, gotcha 3.
    //
    // It measured 17/22 before three uncoverable arms were **deleted rather than excused**, the
    // same treatment `ContentTypeSwitcher` and `PlaybackConnection`'s ticker got: `x?.mediaId ?:
    // return` emits a second null check on `mediaId`, which is non-null on every `MediaItem` and so
    // can never take its other arm. Rewritten as one null check on the item, the two arms that were
    // left are both real and both driven (`aPlayerWithNothingLoadedWritesNothingAndDoesNotThrow`
    // and `aDiscontinuityOutOfNothingWritesNothing`).
    //
    // Falsified by moving the connected run's `.ec` aside -- the only execution data these classes
    // have: "Rule violated for class app.muplay.media.ProgressWriter: branches covered ratio is
    // 0.00, but expected minimum is 0.90", BUILD FAILED, and the same for
    // `app.muplay.media.ProgressWriter.write.2`.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.ProgressWriter*"),
      requiresInstrumentedData = true,
    ),
    // The same family's LINE, instrumented, and it gates what the BRANCH rule above cannot reach:
    // `ProgressWriter` 34/35 = 0.9714, `ProgressWriter$write$2` 14/14, `ProgressWriter$start$1` 3/3
    // (the ticker body) and `$captureCurrent$1` / `$flushBlocking$1` / `$onPositionDiscontinuity$1`
    // 1/1 each -- the three launched writes, which carry no branch of their own and are exactly the
    // lines a persistence point loses when its `scope.launch` is deleted.
    //
    // The one uncovered line in `ProgressWriter` stays in the denominator honestly: it is the
    // closing brace of `write`, i.e. the implicit-return bookkeeping the Kotlin compiler emits for
    // a `suspend fun` whose body always suspends into `withContext`. It cannot be reached and it is
    // not excused away. 0.90 leaves that line and two more of room; the real gate on this class is
    // the by-hand mutation set in task-8b-report.md, which reddens a named test for every one of
    // the seven persistence points individually.
    //
    // `ProgressWriter$Companion` is matched and carries **zero counters of either kind** (three
    // `const val`s, which the compiler inlines), so JaCoCo evaluates nothing for it and
    // `warnUngatedClasses` skips it -- the same standing exception this module records for its
    // other `$Companion`s. It is inside the pattern rather than excluded so that a reader does not
    // have to wonder whether it was forgotten.
    //
    // Falsified with the same `.ec` moved aside, and watched rather than predicted: "lines covered
    // ratio is 0.00, but expected minimum is 0.90" for `ProgressWriter`, `ProgressWriter.write.2`,
    // `ProgressWriter.start.1`, `ProgressWriter.captureCurrent.1`, `ProgressWriter.flushBlocking.1`
    // and `ProgressWriter.onPositionDiscontinuity.1` -- six classes, one line each, BUILD FAILED.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.ProgressWriter*"),
      requiresInstrumentedData = true,
    ),
    // ---- Plan 3 Task 3: the media cache ------------------------------------------------------
    // 11/12 = 0.9167 BRANCH, instrumented. BRANCH and not LINE because the part of this class that
    // matters is *all* branch: `dataSpec.key ?: throw MissingCacheKeyException(..)`, whose two
    // sides are the whole task, and `trackIdIn`, which is a chain of safe calls. A LINE floor over
    // it would be satisfied by covering either side of that elvis, which is the difference between
    // "the cache key comes from the track id" and "the missing-key fallback Tempo ships is gone".
    // Counter picked by reading the merged report rather than from the non-UI default -- this
    // class is one of the few in the module that genuinely carries both.
    //
    // 2/2 until Task 3's fix round, when `trackIdIn` arrived: the pure String -> String reduction
    // of a stream URL to the track id, so that the exception message names a track and not a
    // replayable credential. Its branches come from `TrackIdCacheKeyFactoryTest` on the **JVM**
    // tier -- deliberately, that is why it takes a `String` -- while the elvis above it still
    // needs a `DataSpec` and a device, which is why `requiresInstrumentedData` stays on: with the
    // instrumented `.ec` deleted this class measures 9/12 = 0.75 and the rule would fire on a
    // JVM-only invocation that was never owed it.
    //
    // The one missed branch is unreachable and stays in the denominator honestly, the same way
    // `StreamRetryPolicy`'s does: the safe-call chain emits a null check per `?.`, and once
    // `firstOrNull` has returned null control jumps straight to the `?:`, so the second check's
    // null arm cannot be selected. 0.90 leaves that single dead branch of room and no more -- one
    // genuinely-uncovered branch takes this to 10/12 = 0.8333 and fails.
    //
    // Falsified by moving `MediaCacheTest` aside, not by raising the minimum, for exactly the
    // reason the `ResumePolicy` entry above spells out: at a measured 1.0000 a minimum above the
    // ratio is a configuration error JaCoCo rejects before it compares anything. Watched failing
    // at its real minimum, with the connected run re-taken under
    // `-Pandroid.testInstrumentationRunnerArguments.notClass=app.muplay.media.MediaCacheTest`:
    // "Rule violated for class app.muplay.media.TrackIdCacheKeyFactory: branches covered ratio is
    // 0.50, but expected minimum is 0.90", BUILD FAILED.
    //
    // 0.50 and not 0.00, which is the more interesting number: `MuPlayDataSourceFactoryTest`'s own
    // playbacks drive the key-present side of the elvis all by themselves. The side that only
    // `MediaCacheTest` reaches is the *throw* -- the removed fallback, i.e. the entire point of
    // the class -- so this floor's real job is holding that second branch, and a rule that
    // settled for one of the two would be gating the half that was never in doubt.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.TrackIdCacheKeyFactory"),
      requiresInstrumentedData = true,
    ),
    // 8/8, 2/2 and 1/1 = 1.0000 LINE, instrumented. None of these three carries a single BRANCH
    // counter -- read off the merged report, not assumed -- so LINE is the only counter that can
    // gate them at all; a BRANCH rule here would score NaN and pass at every minimum, the vacuous
    // shape this table's own doc describes and `warnVacuousFloors` reports.
    //
    // `MediaCache` was 6/6 and is 8/8 since Task 3's fix round: `create` grew a `maxBytes`
    // parameter beside `directory`, which is what turns "there is an evictor and it is bounded"
    // from a comment into something a test can fill. Neither `SimpleCache` nor
    // `LeastRecentlyUsedCacheEvictor` exposes the bound (checked in 1.11.0), so before that
    // parameter existed a `NoOpCacheEvictor` left every test in this module green.
    //
    // Instrumented-only, and two of the three reduce to "needs a real device": `MediaCache` builds
    // a `SimpleCache` over `context.cacheDir` and a `StandaloneDatabaseProvider` (real SQLite),
    // and `MediaCacheModule` calls it. `MissingCacheKeyException` is the exception: it takes a
    // `String` and `TrackIdCacheKeyFactoryTest` builds one on the JVM tier, so its two lines are
    // covered from both tiers now. It stays in this rule rather than moving to a JVM one because
    // the throw *site* is still device-only, and a rule that gated the message on the fast tier
    // while the only real caller lived on the slow one would be gating the half that was never in
    // doubt -- the same argument the BRANCH rule above makes about its own elvis.
    //
    // Watched failing the same way, with `MediaCacheTest` moved aside: BUILD FAILED naming all
    // three -- "app.muplay.media.di.MediaCacheModule: lines covered ratio is 0.00",
    // "app.muplay.media.MissingCacheKeyException: lines covered ratio is 0.00" and
    // "app.muplay.media.MediaCache: lines covered ratio is 0.83", each against a minimum of 0.90.
    // `MediaCache`'s 0.83 is 5/6: `MuPlayDataSourceFactoryTest` builds its own cache through the
    // two-argument overload, so the one line left uncovered is the production default argument --
    // which is precisely the line `MediaCacheModule` exists to exercise and the one a
    // `filesDir`-shaped regression would live on.
    //
    // And the flag itself is a measurement, not a judgement: with the instrumented `.ec` deleted,
    // `jacocoJvmCoverageVerification` is green with `requiresInstrumentedData = true` on both new
    // rules (5 of 11 floors evaluated) and BUILD FAILED with it unset, all four classes at 0.00.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.MediaCache",
        "app.muplay.media.MissingCacheKeyException",
        "app.muplay.media.di.MediaCacheModule",
      ),
      requiresInstrumentedData = true,
    ),
    // ---- Plan 3 Task 5: the service, the player factory and the connection --------------------
    // 6/6 = 1.0000 BRANCH from **JVM data alone** (`TaskRemovalPolicyTest`, four tests, no
    // emulator), and the reason `TaskRemovalPolicy` is a separate object at all.
    // `Service.onTaskRemoved` is invoked by the system and by nothing else, so the *override* is
    // unreachable from every test this project can run -- but the rule it applies does not have to
    // live inside it. Same split as `StreamRetryPolicy` behind the Media3 adapter, reached from a
    // different direction: there, the decision was hoisted out of an `@UnstableApi` signature;
    // here, out of an Android lifecycle callback.
    //
    // The six branches are the rule: `playWhenReady != true` (which folds in "there is no player at
    // all"), and `(mediaItemCount ?: 0) == 0`. Both halves fail in opposite directions and both are
    // driven -- stopping unconditionally kills music a user is listening to, never stopping leaves
    // a notification they cannot dismiss.
    //
    // Falsified with `TaskRemovalPolicyTest` moved aside, because a floor measuring exactly 1.0000
    // cannot be falsified by raising its minimum (see the Task 8a note above for the exact JaCoCo
    // message that produces and why it proves nothing): "Rule violated for class
    // app.muplay.media.TaskRemovalPolicy: branches covered ratio is 0.00, but expected minimum is
    // 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.TaskRemovalPolicy"),
    ),
    // `PlaybackState$Companion` 4/4 = 1.0000 BRANCH, JVM-only (`PlaybackStateTest`). The four
    // branches are `durationMsOf`'s two elvis arms and its `coerceAtLeast`, which is the whole of
    // this task's answer to a format the server transcodes on the fly: `player.duration` is
    // `C.TIME_UNSET` for the entire track, and without the metadata fallback every Opus track shows
    // as unknown length on the lock screen, in Auto, in Wear and in Plan 3's seek bar.
    //
    // `*Companion`, not `$Companion`: a literal `$` in a pattern never matches (this table's own
    // doc, gotcha 3). Falsified with `PlaybackStateTest` moved aside: 0/4, BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackState*Companion"),
    ),
    // `PlaybackState` 24/24 and `TaskRemovalPolicy` 1/1 = 1.0000 LINE, JVM-only. Both ride here as
    // well as (for `TaskRemovalPolicy` and the companion) carrying a BRANCH rule, because the
    // branchless half of each is real: `PlaybackState` itself has **no branches at all**, and its
    // twenty-four lines are `NOTHING_PLAYING` -- the value four downstream tasks render before
    // anything is loaded. A field of it silently changing meaning (`hasNext = true` on an empty
    // queue) moves no BRANCH counter anywhere.
    //
    // `PlaybackState$Companion`'s own 2/2 LINE rides on the BRANCH rule above, which is what
    // `warnUngatedClasses` needs -- a class matched by any rule is gated.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackState", "app.muplay.media.TaskRemovalPolicy"),
    ),
    // 22/22 = 1.0000 BRANCH, instrumented -- `PlaybackConnection`, driven by `MuPlaybackServiceTest`
    // in `:app` (see that suite's own doc for why it cannot live in this module, and `Jacoco.kt`'s
    // `mergedExecutionData` for why its `.ec` still lands here).
    //
    // It was 23/24 = 0.9583 until Task 5's fix round, and the two branches that went are the same
    // kind that went before them: `suspendCoroutine`'s `COROUTINE_SUSPENDED` check, whose other arm
    // is a synchronous resume a real IPC connection never takes, moved into
    // `PlaybackConnection$connect$1` -- a continuation class carrying no counters at all -- when the
    // install-after-connect code moved inside `connect()`. Nothing was excused; the arithmetic
    // changed because the code did.
    //
    // Three branches that used to sit here are gone rather than excused, and that is worth
    // recording because the fix improved the code: `release()`'s null-safe calls stayed uncovered
    // until a test released a connection that had never connected (which is what a UI does when the
    // user leaves before the service binds), `controller()`'s cached arm until a test asked for the
    // controller twice, and the ticker's `isActive` condition and `controller?.let` were deleted --
    // a loop that exits only by cancellation cannot take its condition's false arm, and a field
    // that is cleared only after the coroutine is cancelled cannot be null inside it. Two branches
    // that can never take their other arm are not safety; they are two uncoverable branches.
    //
    // **`PlaybackConnection$controller$2` is deliberately NOT here any more, and that is the honest
    // reading rather than a retreat.** The lambda measures 5/6 = 0.8333 BRANCH since the fix round,
    // and the sixth is the false arm of `connection === attempt` inside `invokeOnCompletion`. That
    // check is load-bearing -- without it, an attempt cancelled by `release()` completing *after* a
    // caller has already started its replacement would null out the replacement, and the next
    // `controller()` would build a third controller while the second stayed bound, which is exactly
    // the leak this round fixed. But its false arm needs the cancelled attempt to complete after the
    // replacement was assigned, and cancellation resumes through the main dispatcher: `release()`
    // posts the resume before any later caller can post its own `withContext` body, so on this
    // project's dispatcher the queue is always [resume, replacement]. No test this project can write
    // forces the other order. The lambda keeps its LINE rule below (7/7), so
    // `warnUngatedClasses` is satisfied -- a class matched by any rule is gated -- and this
    // paragraph is where the missing branch is recorded instead of being hidden under a 0.80 floor
    // that would permit a real one.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackConnection"),
      requiresInstrumentedData = true,
    ),
    // Plan 3 Task 5's fix round: the connection gate. 4/4 = 1.0000 BRANCH from **JVM data alone**
    // (`ControllerAccessPolicyTest`, five tests, no emulator), and the reason
    // `ControllerAccessPolicy` is a separate object rather than an `if` inside
    // `MediaLibrarySession.Callback.onConnect`: the decision about who may read this app's session
    // metadata -- which carries a non-expiring Subsonic credential -- is gated by the fast tier.
    //
    // The four branches are the rule: `isTrustedForMediaControl`, and the exact-name comparison
    // that lets the platform's own unattributable legacy caller through. Both arms of both are
    // driven, and both fail in opposite, visible directions -- accepting an untrusted controller
    // hands any local app a replayable credential, refusing the legacy caller kills hardware media
    // buttons on API 26 and 27.
    //
    // Falsified by withholding the covering test rather than by raising the minimum, for the reason
    // the Task 8a entry above spells out (JaCoCo rejects a minimum over 1.0 before it compares
    // anything, which proves nothing): with `ControllerAccessPolicyTest` moved aside, "Rule
    // violated for class app.muplay.media.ControllerAccessPolicy: branches covered ratio is 0.00,
    // but expected minimum is 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.ControllerAccessPolicy"),
    ),
    // The adapter half of the same decision: 2/2 = 1.0000 BRANCH, instrumented, driven by
    // `ControllerAccessGateTest` -- which calls the real `onConnect` with a real `ControllerInfo`
    // for a package that is not this one, because `ControllerInfo` is Android-backed and there is
    // no Robolectric here.
    //
    // **The class moved in Plan 5 Task 4** and this include moved with it. Media3 takes exactly one
    // `MediaLibrarySession.Callback`, so `MuPlaybackService$LibraryCallback` -- whose entire body
    // was `onConnect` -- became `app.muplay.media.browse.MuPlayLibraryCallback`, which serves the
    // browse tree as well. `warnUngatedClasses` is what caught the stale pattern, by name: *"this
    // floor currently enforces nothing: it matches no class in this module at all"*. A JaCoCo
    // include that stops matching does not fail; it silently gates nothing.
    //
    // It rides on the LINE rule further down as well (22/22), and it needs this one too rather than
    // only that one: `onConnect` is one `if`, so its two branches ARE the gate, and a LINE floor
    // over the whole class is satisfied by an `onConnect` that accepts everything.
    //
    // Falsified twice. By deleting the `if` -- `onConnect` reduced to `super.onConnect(session,
    // controller)` -- and running the device suite: `ControllerAccessGateTest`'s
    // `anAppThePlatformDoesNotTrustWithMediaControlIsRefused` and
    // `theSameConnectionIsRefusedOrAcceptedOnTheTrustFlagAlone` both went red (task-4-report.md).
    // And by moving `:core:media`'s connected `.ec` aside: "branches covered ratio is 0.50, but
    // expected minimum is 0.90". **0.50, not 0.00**, because `:app`'s device journey connects a
    // real controller and takes the accepting arm; it is the *refusing* arm that only this module's
    // suite reaches, which is exactly the arm a security gate is for.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.browse.MuPlayLibraryCallback"),
      requiresInstrumentedData = true,
    ),
    // 1.0000 LINE on everything this task adds that a device can reach: `PlaybackConnection` 51/51
    // and its compiled lambdas (`controller$2` 7/7, `controller$2$1` 1/1, `listener$1` 2/2,
    // `connect$connected$1$1` 2/2, `startTicker$1` 3/3), `MuPlayerFactory` 11/11, and the service's
    // `Companion` 1/1 (`sessionToken`). Its sibling `LibraryCallback` rode here at 6/6 until Plan 5
    // Task 4 replaced it; see the note at the include list below.
    //
    // **Two of those lambda names changed in Task 5's fix round, and the patterns had to move with
    // them.** `connect$2$1` became `connect$connected$1$1` when the `suspendCoroutine` result was
    // bound to a local so the install-after-connect code could follow it, and `controller$2$1` is
    // new -- the `async { connect() }` body. A JaCoCo `includes` pattern that stops matching does
    // not fail; it silently gates nothing, which is why `warnUngatedClasses` names every class no
    // rule matches on every run. It is what caught both of these, by name, with their measured
    // ratios. Read its output after any change that moves a lambda.
    //
    // `MuPlayerFactory` has **zero BRANCH counters**, so LINE is the only counter that can gate it
    // at all -- the vacuous-floor shape this table's own doc describes, checked rather than assumed.
    // That matters more than eleven lines suggest: those eleven lines are the only place in this
    // project an `ExoPlayer` is built, and the one of them that attaches the 429 retry policy is
    // silent when it is missing. LINE is what notices a deleted builder call;
    // `PlayerConstructionTest` (JVM) is what notices a second builder appearing somewhere else.
    //
    // 11/11 and not 10/10 since Plan 3 Task 7b, and the eleventh line is worth naming because it
    // looks like it should have brought a branch with it and did not. `create` grew a
    // `renderersFactory` parameter with a production default (see its own note: Media3 offers no
    // way to reach the audio processor chain after construction, so `GaplessTest` needs the seam
    // there rather than a player of its own), and Kotlin compiles that into a synthetic
    // `create$default` holding the default-value expression. JaCoCo's `KotlinDefaultArgumentsFilter`
    // removes the argument-mask branches from that method, so the class still reports **zero**
    // BRANCH counters -- read off the merged report, which is why the sentence above is still true.
    // The line itself is covered because production calls `create()` and the instrumented gapless
    // suite calls `create(renderersFactory)`; note that this floor would NOT notice the first of
    // those disappearing (10/11 = 0.9091 still clears 0.90), so it is `MuPlayDataSourceFactoryTest`
    // and `MuPlaybackServiceTest` calling the no-argument form, not this rule, that keeps the
    // production shape exercised.
    //
    // Re-falsified after the change, not assumed to still hold: with `:core:media`'s own connected
    // `.ec` moved aside, "Rule violated for class app.muplay.media.MuPlayerFactory: lines covered
    // ratio is 0.00, but expected minimum is 0.90", BUILD FAILED, and green again once restored.
    //
    // `MuPlaybackService*Companion` is listed by name rather than as `MuPlaybackService*`,
    // deliberately: that pattern also matches `MuPlaybackService` itself, which has its own rule
    // below at a lower minimum.
    //
    // Falsified by moving the connected run's `.ec` aside -- the only execution data these classes
    // have: "Rule violated for class app.muplay.media.MuPlayerFactory: lines covered ratio is 0.00,
    // but expected minimum is 0.90", BUILD FAILED, once per class.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.PlaybackConnection",
        "app.muplay.media.PlaybackConnection*controller*2",
        "app.muplay.media.PlaybackConnection*controller*2*1",
        "app.muplay.media.PlaybackConnection*listener*1",
        // Deliberately `*connect*` and not `*connect*connected*1*1`: the suspend function's own
        // continuation class (`PlaybackConnection$connect$1`) carries zero counters of either kind,
        // so it can never move this ratio, and a pattern pinned to the lambda's exact spelling is
        // the thing that just went stale once. This one survives the body being rearranged again.
        "app.muplay.media.PlaybackConnection*connect*",
        "app.muplay.media.PlaybackConnection*startTicker*1",
        "app.muplay.media.MuPlayerFactory",
        "app.muplay.media.MuPlaybackService*Companion",
        // `MuPlaybackService$LibraryCallback` used to ride here at 6/6. Plan 5 Task 4 replaced it
        // with `app.muplay.media.browse.MuPlayLibraryCallback`, which has its own LINE rule below;
        // the pattern is deleted rather than left behind, because a pattern that matches nothing
        // gates nothing and reads exactly like one that does.
      ),
      requiresInstrumentedData = true,
    ),
    // `MuPlaybackService` itself: **39/43 = 0.9070 LINE**, instrumented. The number is a
    // measurement and so is the exception, so both are spelled out rather than rounded to a
    // comfortable figure.
    //
    // **Re-measured by Plan 5 Task 4, which changed this class**, per CLAUDE.md's rule that a
    // recorded floor measurement is a measurement with a timestamp: it was 27/31 = 0.8710 when this
    // entry was written. Task 4 added `@Inject lateinit var libraryCallback` and one
    // `libraryCallback.release()` in `onDestroy`, and removed the nested `LibraryCallback` (a
    // separate class, which never counted here). The four uncoverable lines below are unchanged;
    // what grew is the covered count.
    //
    // Four lines cannot be covered by any test this project can run, and they are all of the miss:
    //
    //   L96-L98  `onTaskRemoved`, called by the system when a user swipes the app out of recents
    //            and by nothing else. There is no instrumentation API that delivers it. The *rule*
    //            it applies was hoisted into `TaskRemovalPolicy` for exactly this reason and is
    //            gated at 1.0000 by the fast tier above; what is left here is the adapter that
    //            reads two properties off a nullable player.
    //   L76      the lazy message of `checkNotNull(packageManager.getLaunchIntentForPackage(..))`.
    //            An installed application always has a launcher activity, so the failure arm is
    //            unreachable -- but the check is not decoration: without it the session activity is
    //            null and the notification silently does nothing when tapped, which is a defect a
    //            user reports and a developer cannot reproduce from a log.
    //
    // **What 0.85 now catches, corrected.** This entry used to say "one genuinely-uncovered line
    // takes this to 26/31 = 0.8387 and fails". At 43 lines that is no longer true, and it is exactly
    // the stale-falsification shape CLAUDE.md describes: one added uncovered line is now 39/44 =
    // 0.8864 and *passes*, two are 39/45 = 0.8667 and pass, and it takes **three** (39/46 = 0.8478)
    // to fire. The minimum is left where its author put it rather than tightened by a passing lane,
    // but the claim about what it catches is corrected to what was measured.
    //
    // Watched failing at a minimum of 0.92 -- "Rule violated for class
    // app.muplay.media.MuPlaybackService: lines covered ratio is 0.90, but expected minimum is
    // 0.92", BUILD FAILED -- which is the falsification a fractional floor admits, and then
    // restored.
    //
    // No BRANCH rule, and that is the honest reading rather than an omission: the class measures
    // 4/16 = 0.2500 (was 2/12 before Task 4's two `lateinit` reads, each of which the compiler
    // emits an unreachable uninitialized-property arm for), because most of its branches are the
    // null-safe reads inside `onTaskRemoved` and the unreachable arm of the `checkNotNull` above. A
    // BRANCH floor at 0.25 would permit anything and is the vacuous shape this table exists to
    // refuse; `warnUngatedClasses` is satisfied by the LINE rule, because a class matched by any
    // rule is gated.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.85"),
      includes = listOf("app.muplay.media.MuPlaybackService"),
      requiresInstrumentedData = true,
    ),
    // Plan 3 Task 9: `PlaybackLauncherKt` -- `launchQueue`, the one decision `PlaybackLauncher`
    // makes before it touches a `MediaController`. 2/2 = 1.0000 BRANCH and 1/1 LINE from **JVM data
    // alone** (`PlaybackLauncherTest`, six tests, no emulator), which is the entire reason it is a
    // top-level function rather than a private method: everything else in `PlaybackLauncher.play`
    // is a controller handshake that needs a bound media session, but *which item the caller asked
    // to start from* is what a user notices immediately when it is wrong -- tapping track 7 and
    // hearing track 1 -- and it should not cost a device run to notice. Same argument
    // `StreamRetryPolicy` and `ResumePolicy` above already make in this module.
    //
    // Falsified by withholding the covering test rather than by raising the minimum (at a measured
    // 1.0000 JaCoCo rejects a minimum over 1.0 before it compares anything, which proves nothing):
    // with `PlaybackLauncherTest` moved aside, "Rule violated for class
    // app.muplay.media.PlaybackLauncherKt: branches covered ratio is 0.00, but expected minimum is
    // 0.90", BUILD FAILED.
    //
    // **`PlaybackLauncher` itself is deliberately not listed, and it is not an oversight.** The
    // class measures 0/2 BRANCH and 0/12 LINE here, because every line of it needs a real
    // `MediaController` bound to a real `MuPlaybackService` -- which only an `@HiltAndroidApp`
    // application can start, i.e. `:app`'s instrumented tier, i.e. Task 10's journey. It is named,
    // with those measured ratios, in `warnUngatedClasses`'s output on every run, which is where a
    // genuinely-deferred class belongs; the same shape `:feature:library` used for `CoverArtKt`
    // between its Task 9 and its Task 10.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackLauncherKt"),
    ),
    // ---- Plan 3 Task 6: audio focus, becoming-noisy, wake mode, and the content-type switch ----
    // `PlaybackAudioAttributes` 2/2 = 1.0000 BRANCH from **JVM data alone**
    // (`PlaybackAudioAttributesTest`, six tests, no emulator). That split is the entire reason this
    // object exists apart from the builder call it feeds: `contentTypeFor` takes an `Int` and
    // returns an `Int`, so spec section 5's one-line switch is gated by the fast tier while the
    // `AudioAttributes` construction around it is not. Same argument as `StreamRetryPolicy` behind
    // the Media3 error adapter and `ResumePolicy` behind the player.
    //
    // Two branches, and they are the whole decision: is this media type one of the two audiobook
    // types, or anything else. A wrong answer is **invisible at runtime** -- focus still works, the
    // app still pauses for a call -- and shows up only where nobody looks: a navigation prompt
    // ducking music but interrupting speech, and a car mixing a notification differently over each.
    //
    // Confirmed JVM-measurable by deleting both connected runs' `.ec` and running
    // `jacocoJvmCoverageVerification` alone: green, and this rule among the ones it evaluated.
    // Falsified by withholding the covering test rather than by raising the minimum (at a measured
    // 1.0000 JaCoCo rejects a minimum over 1.0 before it compares anything, which proves nothing --
    // see the Task 8a entry above): with `PlaybackAudioAttributesTest` moved aside, "Rule violated
    // for class app.muplay.media.PlaybackAudioAttributes: branches covered ratio is 0.00, but
    // expected minimum is 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackAudioAttributes"),
    ),
    // The same class's 8/8 = 1.0000 LINE, JVM, and it gates something the BRANCH rule above cannot:
    // `of` is a branchless builder chain (`setUsage`, `setContentType`), and `USAGE_MEDIA` is what
    // puts this app on the *media* volume stream rather than the notification or assistant stream.
    // Per this table's own doc a LINE floor can only be moved by an added untested line, never by a
    // deleted covered one -- so what this really gates is the class drifting into an untested
    // block; the deleted-call case is `PlaybackAudioAttributesTest.the usage is always media`.
    // Falsified the same way, same run: lines covered ratio 0.00 against a minimum of 0.90.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.media.PlaybackAudioAttributes"),
    ),
    // `ContentTypeSwitcher` 4/4 and `PlaybackLauncher` 2/2 = 1.0000 BRANCH, instrumented.
    //
    // `ContentTypeSwitcher`'s four are two real decisions with both arms driven: the transition to
    // a null `mediaItem` (a cleared queue) and a null `MediaMetadata.mediaType` (any item built
    // without metadata -- `MediaItem.fromUri`, which is most of this module's own device fixtures).
    // It measured 5/6 as `mediaItem?.mediaMetadata?.mediaType ?: return`, where the middle safe call
    // on a *platform* type emitted a null check nothing can reach; that arm was deleted rather than
    // excused, which is the same treatment `PlaybackConnection`'s ticker got in Task 5.
    //
    // `PlaybackLauncher`'s two are `launchQueue(..) ?: return` -- Task 9 measured them 1/2 and
    // recorded the class as deliberately ungated, because every line of it needs a `MediaController`
    // bound to a real `MuPlaybackService`. That is still true, and it is exactly what
    // `MuPlaybackServiceTest` now does: this task pointed its `setQueueAndPlay` helper at the
    // production launcher instead of a hand-rolled `setMediaItems(items, 0, 0L)` (which is how
    // `startIndex` came to be applied nowhere), and added the empty-queue case, so both arms are
    // driven where they are applied.
    //
    // Falsified by moving both connected runs' `.ec` aside -- the only execution data either class
    // has: "Rule violated for class app.muplay.media.ContentTypeSwitcher: branches covered ratio is
    // 0.00, but expected minimum is 0.90", BUILD FAILED, and the same for `PlaybackLauncher`.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.ContentTypeSwitcher",
        "app.muplay.media.PlaybackLauncher",
      ),
      requiresInstrumentedData = true,
    ),
    // 6/6, 12/12 and 6/6 = 1.0000 LINE, instrumented: the same two classes plus
    // `PlaybackLauncher$play$2`, the compiled body of the `withContext(mainDispatcher)` block, which
    // is where the three controller calls actually live (`setMediaItems`, `prepare`, `play`) and
    // carries no BRANCH counter of its own. `*play*2`, not `$play$2`: a literal `$` in a pattern
    // never matches (this table's own doc, gotcha 3). `PlaybackLauncher$play$1` -- the suspend
    // continuation -- measures zero counters of either kind, so `warnUngatedClasses` skips it and no
    // rule could gate it, the same standing exception this module records for its `$Companion`s.
    //
    // Falsified with the same `.ec` files moved aside: lines covered ratio 0.00 for all three.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.ContentTypeSwitcher",
        "app.muplay.media.PlaybackLauncher",
        "app.muplay.media.PlaybackLauncher*play*2",
      ),
      requiresInstrumentedData = true,
    ),
    // ---- Plan 5 Task 4: the browse tree on the wire -------------------------------------------
    // `BrowseItems` 23/23, `MuPlayLibraryCallback$onGetChildren$1` 4/4 and
    // `MuPlayLibraryCallback$onGetItem$1` 6/6 = 1.0000 BRANCH, instrumented. (`MuPlayLibraryCallback`
    // itself carries the connection gate's 2/2 and is gated by its own rule further up, where the
    // argument for gating that decision on BRANCH belongs.)
    //
    // Instrumented, and not by preference: `androidx.media3.common.MediaItem` reaches
    // `android.net.Uri` and `android.os.Bundle`, which are unimplemented stubs in the JVM's
    // `android.jar` -- a plain unit test of this mapping throws *"not mocked"* and the only escape
    // is Robolectric, which spec sections 2 and 10 ban. Everything *decidable* off a device was
    // pushed above this boundary on purpose (`BrowseTree`, `BrowseExtras`, `BrowsePaging` are all
    // `:core:model`, all Tier 1), which is why what is left here is 23 branches of translation.
    //
    // The two lambda classes are where the interesting branches actually are: each is a decode that
    // can fail and a lookup that can miss, i.e. the difference between "this is not a folder" and
    // "this folder is empty" -- an answer a car renders differently and no other tier can observe.
    // Both arms of both are driven by `BrowseTreeBrowserTest`, over a real `MediaBrowser`.
    //
    // `*onGetChildren*1`, not `$onGetChildren$1`: a literal `$` in a pattern never matches (this
    // table's own doc, gotcha 3).
    //
    // Falsified by moving `:core:media`'s connected `.ec` aside -- the only execution data these
    // classes have: "Rule violated for class app.muplay.media.browse.BrowseItems: branches covered
    // ratio is 0.00, but expected minimum is 0.90", BUILD FAILED, once per class.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.browse.BrowseItems",
        "app.muplay.media.browse.MuPlayLibraryCallback*onGetChildren*1",
        "app.muplay.media.browse.MuPlayLibraryCallback*onGetItem*1",
      ),
      requiresInstrumentedData = true,
    ),
    // `MuPlayLibraryCallback$future$1` alone, at **0.85**, and this is the one floor Task 4 sets
    // below 0.90. It measures 6/7 = 0.8571 BRANCH, and the seventh branch cannot be reached by any
    // test: the class is the compiled body of `scope.launch { .. }`, and Kotlin's state machine
    // emits `when (label) { 0 -> ..; 1 -> ..; else -> throw IllegalStateException("call to
    // 'resume' before 'invoke'") }`. Only the coroutine machinery can produce that third arm, and
    // only by being broken. `:core:database`'s `RendererStore` entry above records the same
    // artefact and answers it by not gating BRANCH at all; this entry gates it at what it measures
    // instead, because the other six branches here are worth gating.
    //
    // Those six are real and both driven: the suspension either side of `block()`, and
    // `runCatching { .. }.getOrElse { .. }` in **both** directions -- the failure arm by
    // `aRepositoryFailureBecomesAnErrorResultRatherThanASilentEmptyScreen`, which closes the
    // database under the repository and asserts the browser receives an error result rather than a
    // blank list. That arm is the whole reason this helper exists.
    //
    // 0.85 and not lower: one more genuinely uncovered branch takes it to 6/8 = 0.7500 and fails.
    // Watched failing at 0.90 -- "Rule violated for class
    // app.muplay.media.browse.MuPlayLibraryCallback.future.1: branches covered ratio is 0.85, but
    // expected minimum is 0.90", BUILD FAILED -- and restored. It also fires with this module's
    // connected `.ec` withheld, at 0.00.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.85"),
      includes = listOf("app.muplay.media.browse.MuPlayLibraryCallback*future*1"),
      requiresInstrumentedData = true,
    ),
    // 1.0000 LINE, instrumented, on everything this task added to this module: `BrowseItems` 45/45,
    // `MuPlayLibraryCallback` 22/22 and its three compiled lambdas (`onGetChildren$1` 7/7,
    // `onGetItem$1` 7/7, `future$1` 5/5), and `DefaultSurfaceResolver` 8/8.
    //
    // **`DefaultSurfaceResolver` had no floor at all until now**, and printed an ungated-class
    // warning on every run since Task 3 landed it. It is gated here rather than there because Task
    // 4 is its first consumer -- the `@Binds` that puts it in the graph is in this task's
    // `MediaModule` -- and a floor is owed by whoever makes a class reachable. LINE and not BRANCH
    // because it has **zero BRANCH counters**: it is one expression that reads three values off a
    // `ControllerInfo` and hands them to `BrowseSurfaces.of`, where every branch lives and where
    // `:core:model`'s own BRANCH floor already gates them. A BRANCH rule over it would be the
    // vacuous, NaN-scored shape this table's doc describes.
    //
    // `MuPlayLibraryCallback*` matches the outer class and all three lambdas; the suspend
    // continuation classes it also matches carry zero counters of either kind, so they can never
    // move this ratio -- the standing exception this module already records for its `$Companion`s.
    //
    // Falsified by moving `:core:media`'s connected `.ec` aside -- all six classes fired, BUILD
    // FAILED. The **residual ratios are recorded rather than rounded to zero**, because two of them
    // are not zero and a comment that said they were would be the stale-falsification shape
    // CLAUDE.md describes: `BrowseItems` 0.00, `MuPlayLibraryCallback` **0.45**,
    // `.future.1` 0.00, `.onGetChildren.1` 0.00, `.onGetItem.1` 0.00,
    // `DefaultSurfaceResolver` **0.25**. The non-zero two come from `:app`'s own device journey,
    // which starts the real service: that builds the callback and resolves a surface for the app's
    // own controller, and `Jacoco.kt` globs every project's `.ec`. So this floor is enforced by two
    // suites, and withholding either one alone is not enough to fire the whole rule.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.browse.BrowseItems",
        "app.muplay.media.browse.MuPlayLibraryCallback*",
        "app.muplay.media.browse.DefaultSurfaceResolver",
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
  // Plan 2 Task 9 (:feature:library), floors 2 and 3 added in its review round 1 (N-7). Every
  // number below is measured from `./gradlew :feature:library:test :feature:library:jacocoTestReport`
  // at commit-time; see this table's own doc above for the two arguments the review overturned and
  // for what is still, genuinely, Task 10's.
  ":feature:library" to listOf(
    // 18/18 -- libraryContent's own branches: the empty-libraries early return, the
    // firstOrNull-elvis-firstOrNull selection repair, and the searching ? searchAlbums : albums
    // branch -- all covered by LibraryUiStateTest's own cases.
    //
    // LibraryUiState* rides along for the same reason SetupUiState did in :feature:setup's own
    // rule above: Content/Loading/NoLibraries carry 0 branches of their own (a sealed interface
    // and data classes with no body), so they can never move this ratio, and including them is
    // what keeps warnUngatedClasses from flagging their own fully-covered lines as an ungated
    // class.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("1.00"),
      includes = listOf(
        "app.muplay.library.LibraryUiStateKt",
        "app.muplay.library.LibraryUiState",
        "app.muplay.library.LibraryUiState*",
        // AlbumUiState rides along on exactly the same reasoning -- Loading/NotFound/Content carry
        // no BRANCH counter at all (measured: branch n/a, line 1/1 for Content) -- rather than
        // being left to warn forever about three types with nothing a floor could gate. It has no
        // Kt file-class of its own to name here: `AlbumUiState.kt` declares no top-level function.
        "app.muplay.library.AlbumUiState",
        "app.muplay.library.AlbumUiState*",
      ),
    ),
    // Both ViewModels' own bodies. **Exact names, not `"LibraryViewModel*"`** -- that wildcard is
    // what cannot hold a floor here (LibraryViewModel$shuffle$1 is 6/12, LibraryViewModel$1 is the
    // Hilt-only LibrarySource adapter at 0/8 LINE), and it was mistaken for the class itself being
    // ungateable when this module shipped. The exact form gates the outer class and leaves the
    // nested ones to go on warning, which is what warnUngatedClasses is for. Precedent for exact
    // names beside wildcards in this same table: "app.muplay.setup.SetupViewModel".
    //
    // Measured: LibraryViewModel 4/4 BRANCH (36/39 LINE, hence no LINE rule -- the three are the
    // @Inject secondary constructor's own body, reachable only through Hilt), AlbumViewModel 2/2
    // BRANCH (the double-load guard, both ways, since review round 1 covered it; it was 1/2 and
    // therefore genuinely ungateable before that).
    //
    // AlbumViewModel*Fetch* rides along, same reasoning as LibraryUiState* above: `Fetch`,
    // `Fetch.Pending` and `Fetch.Done` carry no BRANCH counters at all (an `object` and a `class`
    // with no generated equals, on purpose -- see AlbumViewModel), so they cannot move this ratio,
    // and including them keeps warnUngatedClasses quiet about three types with nothing to gate.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("1.00"),
      includes = listOf(
        "app.muplay.library.LibraryViewModel",
        "app.muplay.library.AlbumViewModel",
        "app.muplay.library.AlbumViewModel*Fetch*",
      ),
    ),
    // 3/4 = 0.75, and 0.75 is the honest ceiling rather than a rounded-down 0.90: the fourth
    // branch is the non-null path of `sizePx?.toString() ?: "full"` on a value Kotlin has already
    // proven non-null, which no test can reach. Same shape and same reason as SetupFailureReasonKt
    // at 0.85 above. LINE is 1/1 and carries no rule of its own -- one expression body, nothing a
    // second counter would add.
    //
    // Live, and proved able to fail rather than merely to pass: at minimum = 1.00 this entry
    // reports `Rule violated for class app.muplay.library.CoverArtCacheKeyKt: branches covered
    // ratio is 0.75, but expected minimum is 1.00` and fails the build.
    //
    // This floor exists because `coverArtCacheKey` was moved out of `CoverArt.kt` into its own
    // file -- see `CoverArtCacheKey.kt`'s own header. Folding it back in would silently delete
    // this gate (the merged CoverArtKt measured 3/56 BRANCH, well under any floor worth setting).
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.75"),
      includes = listOf("app.muplay.library.CoverArtCacheKeyKt"),
    ),
    // ---- Plan 2 Task 10: the three Composable file-classes Task 9 deferred, now that
    // BrowseJourneyTest and ScopedShuffleJourneyTest compose and exercise them for real. ----
    //
    // LINE, not BRANCH, per this table's own doc: `LibraryScreenKt` measures 91/158 = 0.5759
    // BRANCH and `AlbumScreenKt` 28/58 = 0.4828, and essentially all of that shortfall is Compose
    // codegen -- every `@Composable` call site compiles to `$changed`-bitmask branches and skipping
    // checks that no user-reachable behaviour corresponds to. Gating those would be gating the
    // Compose compiler. LINE is the counter that answers the question worth asking of a screen:
    // did this row, this message, this control actually render on a device.
    //
    // Measured from a merged JVM + instrumented report after the journeys landed:
    //   LibraryScreenKt   56/62 = 0.9032 LINE
    //   AlbumScreenKt     23/24 = 0.9583 LINE
    //
    // **`LibraryScreenKt` clears 0.90 by one line, and that is worth knowing before somebody is
    // surprised by it.** The six lines it misses are: the private `LibraryScreen` overload's own
    // declaration line and its closing brace (the same two-line artifact `SetupScreenKt` carries,
    // documented on that floor above); the `NoLibraries` message, which no journey can reach
    // because reaching the library screen at all requires tagged libraries; and the three lines of
    // the `discardedOutOfScope > 0` warning, which is unreachable precisely *because* the scoping
    // works -- `ScopedShuffleJourneyTest` exists to prove nothing is ever discarded. A seventh
    // missed line drops this to 0.8871 and fails, which is the intended behaviour: somebody should
    // look.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.library.LibraryScreenKt", "app.muplay.library.AlbumScreenKt"),
      requiresInstrumentedData = true,
    ),
    // `CoverArtKt` -- what is left in `CoverArt.kt` once `coverArtCacheKey` moved out: the
    // `CoverArtImage` Composable alone. 18/23 = 0.7826 LINE, and **0.90 is not reachable, which is
    // a statement about the fixtures rather than about the tests**.
    //
    // The five missed lines are the whole `coverArtId == null` placeholder branch (the `Box`, its
    // three modifier lines, and the early `return`). Navidrome synthesises a `coverArt` id for
    // every album it serves, including ones whose files carry no embedded artwork -- both seeded
    // albums do -- so no journey against this container can put a null through that parameter, and
    // a JVM test cannot compose at all (no Robolectric, by constraint). Raising this to 0.90 would
    // therefore not buy a test; it would buy a permanently red gate, which is how a gate gets
    // switched off.
    //
    // 0.75, not 0.7826: one line of headroom, deliberately, for the same reason the floor above
    // has one. What it still catches is the thing worth catching -- if `AsyncImage` and the
    // `produceState` lookup stopped executing, this class drops to about 10/23 = 0.43.
    //
    // **This floor is permanent, not provisional.** An earlier draft said it returns to 0.90 "the
    // day a fixture album with genuinely no cover art exists" -- but this task's own live check
    // against `ci-navidrome-1` found Navidrome **synthesises a `coverArt` id for every album**,
    // including both artwork-free seeded ones. So that day cannot arrive from a fixture, and the
    // route back to 0.90 is a placeholder branch reached only by a server that does not behave
    // like Navidrome. Raising it would need a hand-built response, not a corpus change. The
    // five lines above come with it. Recorded here so that is a decision somebody can act on
    // rather than a number nobody can explain.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.75"),
      includes = listOf("app.muplay.library.CoverArtKt"),
      requiresInstrumentedData = true,
    ),
    // The Compose compiler's own nested classes for these three files: `LazyColumn`'s four
    // `$$inlined$items$default$N` adapters, the `FilterChip` label lambda, `AlbumScreen`'s
    // `LaunchedEffect` body, and `CoverArtImage`'s `produceState` body. Gated rather than excluded,
    // and gated low rather than not at all -- the same ruling `:core:database`'s 0.50 rule for
    // suspend/`Flow.map` artefacts already makes, and for the same reason: a pattern broad enough
    // to catch every Compose artefact would also catch author-written nested classes, and this
    // project would rather carry an honest low floor than a silent hole.
    //
    // Measured LINE, all of them: `$$inlined$items$default$4` 2/3 = 0.6667 (the lowest, and the
    // only one under 1.00 -- it is `items`'s key/contentType adapter, whose `contentType` arm is
    // never taken because `LibraryScreen` passes `key =` but not `contentType =`);
    // `$$inlined$items$default$1`/`$2`/`$3` 1/1; `LibraryScreen$7$5$1$2$2$1` 1/1;
    // `AlbumScreen$1$1` 1/1; `CoverArtImage$url$2$1` 3/3. Floored at 0.65 -- a real number this run
    // produced, one line of headroom under the lowest of them, not a round one.
    //
    // `excludes` names the three file-classes explicitly: a `"...Kt*"` include matches the bare
    // `"...Kt"` too (JaCoCo's `*` matches the empty string), and folding them in here would drop
    // all three from their own floors above to this one.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.65"),
      includes = listOf(
        "app.muplay.library.LibraryScreenKt*",
        "app.muplay.library.AlbumScreenKt*",
        "app.muplay.library.CoverArtKt*",
      ),
      excludes = listOf(
        "app.muplay.library.LibraryScreenKt",
        "app.muplay.library.AlbumScreenKt",
        "app.muplay.library.CoverArtKt",
      ),
      requiresInstrumentedData = true,
    ),
    // The two ViewModels' own coroutine and `Flow` codegen -- `$uiState$1`/`$2`, `$search$1`,
    // `$refresh$1`, `$shuffle$1`, `$albums$1`, `$load$1`, the `$$inlined$flatMapLatest$1` pair, and
    // the two anonymous `LibrarySource`/`AlbumSource` adapters the `@Inject` secondary constructors
    // wire to the real repositories. Task 9's own floor comment explains why the *outer* classes
    // need exact-name includes: a `"LibraryViewModel*"` wildcard cannot hold a BRANCH floor here,
    // because these nested classes measure 0.50-0.90 BRANCH and would drag it under any minimum
    // worth setting. This is the other half of that ruling -- the nested classes get their own
    // rule, on the counter that suits them.
    //
    // LINE, for the same reason `:core:database`'s equivalent rule is LINE: what is worth knowing
    // about compiler-generated continuation machinery is whether it ran, not which of its
    // state-machine arms the compiler emitted. Measured: `LibraryViewModel$1` 7/8 = 0.8750 (the
    // Hilt-only `LibrarySource` adapter -- its eighth line is `allIds`, which the journeys never
    // reach because a library is always selected by then); every other class here 1/1 to 10/10 at
    // 1.0000, and `$currentLibraryId$1`/`$Companion` at 0/0, JaCoCo's isNaN pass. Floored at 0.85.
    //
    // `AlbumViewModel*Fetch*` is excluded rather than left to ride along: Task 9's BRANCH rule
    // above already names it, and a class matched by two floors is a class whose failure message
    // names the wrong rule.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.85"),
      includes = listOf("app.muplay.library.LibraryViewModel*", "app.muplay.library.AlbumViewModel*"),
      excludes = listOf(
        "app.muplay.library.LibraryViewModel",
        "app.muplay.library.AlbumViewModel",
        "app.muplay.library.AlbumViewModel*Fetch*",
      ),
      requiresInstrumentedData = true,
    ),
  ),
  // Plan 3 Task 9 (`:feature:player`). Every number below is MEASURED from
  // `./gradlew :feature:player:jacocoTestReport` (JVM) and from this module's own
  // `connectedDebugAndroidTest` run (instrumented) at commit time.
  //
  // The BRANCH/LINE split is this table's standing ruling, applied to a module that is mostly
  // Compose: the author-written logic (the pure state mapping, the formatter, the ViewModel) gets
  // BRANCH, and the `@Composable` file-classes get LINE, because the Compose compiler weaves
  // `$changed`/`$dirty` skip branches into the same method bodies as the author's own `if`s and no
  // class-granular exclusion can separate them.
  ":feature:player" to listOf(
    // `PlayerUiStateKt` -- `playerUiState`, `displayPosition` and `formatDuration` -- 10/10 =
    // 1.0000 BRANCH, 14/14 LINE, from **JVM data alone** (`PlayerUiStateTest`, fifteen tests, no
    // emulator). That is the whole
    // reason this mapping is a top-level function in its own file rather than a `ViewModel` method
    // or a line inside `PlayerScreen.kt`: `:feature:library`'s `CoverArtCacheKey.kt` header records
    // what happens otherwise -- a pure function sharing a file-class with a Composable measures its
    // own branches drowned in the Composable's synthetic ones, and no floor can reach it.
    //
    // `PlayerUiState`/`PlayerUiState*` ride along, carrying zero BRANCH counters of their own (a
    // sealed interface, a `data object` and a `data class` with no body), so they cannot move this
    // ratio; they are listed only so `warnUngatedClasses` has nothing to say about them on every
    // run. The floor is not thereby vacuous: all 8 branches come from `PlayerUiStateKt`.
    //
    // Falsified by withholding the covering test, not by raising the minimum: at a measured 1.0000
    // a higher minimum is rejected by JaCoCo before it compares anything ("given minimum ratio is
    // 1.01, but must be between 0.0 and 1.0"), which is the same configuration error zero-coverage
    // code would produce. With `PlayerUiStateTest` moved aside this floor reports "Rule violated
    // for class app.muplay.player.PlayerUiStateKt: branches covered ratio is 0.00, but expected
    // minimum is 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.player.PlayerUiStateKt",
        "app.muplay.player.PlayerUiState",
        "app.muplay.player.PlayerUiState*",
      ),
    ),
    // `PlayerViewModel`'s own decisions, both measuring 1.0000 BRANCH from **JVM data alone**
    // (`PlayerViewModelTest`, thirteen tests, no emulator) -- which is the whole reason the class
    // is constructed over a `PlaybackControls` seam rather than over `PlaybackConnection` directly.
    //
    //   `PlayerViewModel`                 2/2 -- `commitScrub`'s `?: return` (the tap that moved
    //                                     nothing must not seek) and `scrubTo`'s coerce-at-zero.
    //   `PlayerViewModel$playPause$1`     2/2 -- pause-if-playing / play-if-paused, the one
    //                                     decision in the class a user meets on every tap.
    //
    // **Exact names beside a narrow wildcard, not `"PlayerViewModel*"`** -- the same ruling
    // `:feature:library` records for its own two view models. That wildcard cannot hold a BRANCH
    // floor here: it matches `PlayerViewModel$1`, the Hilt-only `PlaybackControls` adapter, which
    // no JVM test can reach. `*playPause*` is narrow enough to name the one nested class that
    // carries branches, and it also matches `PlayerViewModel$1$play$1`/`$pause$1` (zero counters of
    // either kind, JaCoCo's NaN pass) which is harmless.
    //
    // Falsified by withholding the covering test rather than by raising the minimum -- at a
    // measured 1.0000 JaCoCo rejects a minimum above 1.0 before it compares anything, which is the
    // same configuration error zero-coverage code produces and proves nothing. With
    // `PlayerViewModelTest` moved aside: "Rule violated for class app.muplay.player.PlayerViewModel:
    // branches covered ratio is 0.00, but expected minimum is 0.90", BUILD FAILED.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.player.PlayerViewModel",
        "app.muplay.player.PlayerViewModel*playPause*",
      ),
    ),
    // The view model's coroutine and `Flow` codegen -- `$2` (the `init` block's connect launch),
    // `$next$1`, `$previous$1`, `$playPause$1`, `$commitScrub$1`. All 1.0000 LINE from JVM data
    // alone (1/1, 1/1, 1/1, 2/2, 3/3), floored at 0.90.
    //
    // LINE, for the reason `:core:database`'s and `:feature:library`'s equivalent rules are LINE:
    // what is worth knowing about compiler-generated continuation machinery is whether it ran, not
    // which state-machine arms the compiler emitted.
    //
    // Two exclusions, and both are load-bearing:
    //
    //  * `PlayerViewModel` itself, because a `"...*"` include matches the bare name too (JaCoCo's
    //    `*` matches the empty string) and it would arrive here at 23/26 = 0.8846, dragging a floor
    //    that every class it is meant to gate clears at 1.0000. Its three missing lines are the
    //    `@Inject` secondary constructor's own body, reachable only through Hilt -- exactly the
    //    shape, and the ratio, `:feature:library` records for `LibraryViewModel` at 36/39.
    //  * `PlayerViewModel$1` -- written `PlayerViewModel.1`, because a literal `$` in a pattern
    //    never matches (see this table's own doc, gotcha 3: JaCoCo presents the class as
    //    `PlayerViewModel.1`). It is the anonymous `PlaybackControls` adapter the `@Inject`
    //    constructor builds, 0/10 LINE, reachable **only** through Hilt's DI graph: this module's
    //    own instrumented suite composes the screens over a hand-built view model, which is what
    //    covers everything else here and is deliberately not a Hilt graph. Task 10's end-to-end
    //    journey is what reaches it, and until then it is named, at 0.0000, in
    //    `warnUngatedClasses`'s output on every run -- the same shape `:feature:library` used for
    //    `CoverArtKt` between its own Task 9 and Task 10.
    //
    // Falsified with `PlayerViewModelTest` withheld: BUILD FAILED naming each of the five at 0.00.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.player.PlayerViewModel*"),
      excludes = listOf(
        "app.muplay.player.PlayerViewModel",
        "app.muplay.player.PlayerViewModel.1",
      ),
    ),
    // The three `@Composable` file-classes, LINE, instrumented. LINE and not BRANCH per this
    // table's standing ruling, and the numbers say why plainly: these same three measure 0.6364,
    // 0.5865 and 0.4706 BRANCH, and essentially every missing branch is Compose codegen --
    // `$changed`/`$dirty` skip bitmasks the compiler weaves into the author's own method bodies,
    // which no class-granular exclusion can separate and no user-reachable behaviour corresponds
    // to. Gating those would be gating the Compose compiler.
    //
    // MEASURED from a merged JVM + instrumented report, `:feature:player:connectedDebugAndroidTest`
    // 24/24 green:
    //
    //   PlayerScreenKt  58/61 = 0.9508     MiniPlayerKt  47/49 = 0.9592
    //   ArtworkKt       20/21 = 0.9524
    //
    // This module carries its own instrumented suite rather than waiting for Task 10's journey,
    // and that is what makes 0.90 an honest number here instead of the 0.7377/0.7551 these two
    // measured before it existed. Both stateless overloads are `internal` for exactly that reason,
    // and both Hilt-bound entry points are composed over a hand-built `PlayerViewModel`, which is
    // what covers the `collectAsStateWithLifecycle` hop the JVM tier cannot see.
    //
    // The lines still missing are the `hiltViewModel()` default-argument expressions and their
    // `$default` bridges -- the one thing in these files that genuinely needs a Hilt graph, i.e.
    // Task 10. A fourth missed line takes `MiniPlayerKt` to 46/49 = 0.9388 and a fifth to 0.9184,
    // so the headroom here is real but thin: somebody should look before lowering it.
    //
    // Watched failing at its real minimum with the instrumented `.ec` deleted, which is what
    // `requiresInstrumentedData = true` claims: all three drop to 0.00 and
    // `jacocoTestCoverageVerification` names each one.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.player.PlayerScreenKt",
        "app.muplay.player.MiniPlayerKt",
        "app.muplay.player.ArtworkKt",
      ),
      requiresInstrumentedData = true,
    ),
  ),
  // 61/63 = 0.9683 LINE across the whole module (20/21 = 0.9524 before Task 10's journeys). The
  // one BUNDLE-element rule in this table -- see
  // coverageFloors's own doc above for why an aggregate is the right shape here specifically, and
  // why there is no BRANCH entry.
  // `requiresInstrumentedData`: every line here is Compose/DI wiring the emulator journey runs;
  // from the JVM alone this module measures 1/21 = 0.04.
  ":app" to listOf(
    CoverageFloor(counter = "LINE", minimum = BigDecimal("0.90"), requiresInstrumentedData = true),
  ),
  // `:core:cast` (Plan 6 Task 1, raised by its security review). A pure-JVM module with no Compose
  // and no Android, so BOTH floors here are BRANCH and both are enforceable in Tier 1 --
  // `requiresInstrumentedData` appears nowhere in this entry, deliberately. The include list grows
  // one task at a time and is completed in Task 11.
  //
  // MEASURED, per class, from `core/cast/build/reports/jacoco/test/jacocoTestReport.xml` at this
  // commit -- per class and not as a module blend, because a BRANCH floor over a class with no
  // BRANCH counters enforces nothing at any minimum (JaCoCo's NaN path; see `warnVacuousFloors`
  // below) and a blended number hides which of these is which:
  //
  //   HttpHeaders       BRANCH  10/10  = 1.0000    HttpWire         BRANCH 138/138 = 1.0000
  //   CastHttpClient    BRANCH  28/28  = 1.0000    CastHttpResponse BRANCH   8/8   = 1.0000
  //   LocalNetworkOnly  BRANCH  31/32  = 0.9688    LocalAddress     BRANCH   4/4   = 1.0000
  //
  // LocalNetworkOnly's one missing branch is unreachable rather than untested: `isLocal`'s `when`
  // has an `else -> false` arm that no `InetAddress` can select, because `Inet4Address` and
  // `Inet6Address` are the only two subclasses the platform has.
  //
  // RIDE-ALONGS, carrying zero BRANCH counters and therefore unable to move any ratio (a
  // CLASS-element rule over a zero-counter class yields NaN, and JaCoCo reports no violation for
  // NaN): `HttpHeaders$Companion` and `CastHttpClient$Companion` via the `*` patterns, and the
  // four declaration-only types `HttpRequestHead`, `HttpResponseHead`, `MalformedHttpException`
  // and `NonLocalAddressException`. They are listed only so `warnUngatedClasses` has nothing to
  // say about them on every run. The floor is not thereby vacuous, and the arithmetic is worth
  // stating exactly because the previous version of this comment got it wrong in both halves: the
  // first floor carries 216 BRANCH counters, ALL 216 of them from the five real classes above (the
  // ride-alongs carry none at all, which is why they cannot move a ratio), and 215 of the 216 are
  // covered. The one that is not is `LocalNetworkOnly`'s unreachable `else`.
  ":core:cast" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.http.HttpHeaders",
        "app.muplay.cast.http.HttpHeaders*",
        "app.muplay.cast.http.HttpWire",
        "app.muplay.cast.http.CastHttpClient",
        "app.muplay.cast.http.CastHttpClient*",
        "app.muplay.cast.http.CastHttpResponse",
        "app.muplay.cast.http.HttpRequestHead",
        "app.muplay.cast.http.HttpResponseHead",
        "app.muplay.cast.http.MalformedHttpException",
        "app.muplay.cast.net.LocalNetworkOnly",
        "app.muplay.cast.net.NonLocalAddressException",
      ),
    ),
    // `LocalAddress` was gated on LINE rather than BRANCH, and the comment here said its two
    // remaining branches -- the kernel answering the route probe with the wildcard, and the kernel
    // refusing the probe outright -- could not be forced from a hermetic JVM test. That premise
    // was measured FALSE on JDK 21: `DatagramSocket.connect` and `getLocalAddress` are both
    // overridable, so a hand-written subclass (no mock framework; `verifyNoMockFrameworks` stays
    // green) forces every arm with no network at all. `towards` now takes the socket factory as a
    // defaulted parameter, `LocalAddressTest` drives all four branches, and the floor is BRANCH
    // 4/4 like every other class in this module.
    //
    // The seam closed a second hole, which is the one that made it worth doing rather than merely
    // possible: the only assertion that could tell `towards` from `{ getLoopbackAddress() }` sat
    // behind an `assumeTrue` on the host having a non-loopback interface, so on a loopback-only
    // container both of this class's guards degraded to nothing at once and this floor stayed
    // green on a LINE measurement that a constant would also have satisfied.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.cast.net.LocalAddress"),
    ),
    // Plan 6 Task 2, `app.muplay.cast.discovery`. Every class below with an author-written branch
    // measures **1.0000** today, and each number is from
    // `core/cast/build/reports/jacoco/test/jacocoTestReport.xml` after a plain `:core:cast:test`
    // -- no emulator anywhere, which is the whole point of this module being pure JVM:
    //
    //   `SsdpSearch`            36/36 -- the M-SEARCH renderer's `MX` branch and every one of
    //                           `parseResponse`'s eight rejections. Two of those eight arrived in
    //                           this task's fix round (the review's HIGH 2): `LOCATION`'s host
    //                           must be an IP **literal**, so no resolver is ever on the read
    //                           loop's path, and must equal the address the datagram came from.
    //                           Was 28/28; the eight new counters are `isIpLiteral`'s two regex
    //                           alternatives and the two guards that consult it. The
    //                           `UnknownHostException` arm of `InetAddress.getByName` is still
    //                           reachable, and still by the same hermetic example -- a link-local
    //                           IPv6 literal with a scope id the host does not have, which is a
    //                           literal, so it passes the new guard and reaches `getByName` and
    //                           fails there in single-digit milliseconds with no query.
    //   `DeviceDescription`     58/58 -- `URLBase` present/absent/unparseable, every `orEmpty()`
    //                           arm on a device that names no type, no UDN and no serviceList,
    //                           both `URI.resolve` failures, the DOCTYPE refusal in both cases,
    //                           and (fix round, MEDIUM 2) the `deviceList` depth bound refusing
    //                           and permitting. Was 47/58 when first measured, then 56/56.
    //   `CastDevice*`           26/26 on the `Companion` (both `isSonos` signals independently,
    //                           the no-AVTransport refusal, the empty-friendlyName fallback, and
    //                           the fix round's refusal of a device with no `<UDN>` at all);
    //                           `CastDevice`, `CastDeviceKt` and the record types below carry no
    //                           branches and ride along. Was 22/22.
    //   `DescriptionFetcher`    10/10 -- 200 against 404, a non-local URL, an `https` URL and a
    //                           URL with no host, each of which must be `null` rather than an
    //                           exception escaping a whole discovery pass.
    //   `RendererDirectory`     30/30 -- deduplication, the three fallback layers, the UDN check
    //                           that stops a recycled DHCP lease being mistaken for a speaker,
    //                           and the local-network guard on the unicast search. Was 23/30.
    //
    // `SsdpResponse`, `DiscoveryResult`, `UpnpDevice`, `UpnpService` and
    // `MalformedDescriptionException` ride along at zero branches each, the same way `:core:model`
    // and `:feature:setup` carry theirs -- included so `warnUngatedClasses` stays quiet, gating
    // nothing (a CLASS rule over a zero-counter class yields NaN, which JaCoCo reports as no
    // violation). The floor is not vacuous regardless: 160 of its BRANCH counters come from the
    // five classes above.
    //
    // Falsified by withholding tests, never by raising a minimum above a measured 1.0000 (JaCoCo
    // validates the minimum is inside 0.0..1.0 before it reads a ratio, so that can only ever
    // throw). One withheld test is not enough here, and the two intermediate measurements are
    // recorded because they are the interesting part: withholding `SsdpSearchTest`'s `a reply
    // whose location names a host rather than an address is discarded without resolving it` alone
    // leaves `SsdpSearch` at **34/36 = 0.9444** and this floor still passes; adding `a reply that
    // announces an address it did not come from is discarded` leaves it at **33/36 = 0.9167**,
    // still passing. What does fire it is those two together with `a reply whose location is not
    // a local address is discarded` -- **32/36 = 0.88** -- *"Rule violated for class
    // app.muplay.cast.discovery.SsdpSearch: branches covered ratio is 0.88, but expected minimum
    // is 0.90"*.
    //
    // `DatagramSsdpTransport` is **not** here, and that is the brief's own ruling rather than a
    // convenience: its branches are socket timeouts and network-interface enumeration, so a floor
    // over it would be a number nothing in CI could move. It is gated on LINE below instead.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.discovery.SsdpSearch",
        "app.muplay.cast.discovery.SsdpResponse",
        "app.muplay.cast.discovery.DeviceDescription",
        "app.muplay.cast.discovery.MalformedDescriptionException",
        "app.muplay.cast.discovery.UpnpDevice",
        "app.muplay.cast.discovery.UpnpService",
        "app.muplay.cast.discovery.CastDevice*",
        "app.muplay.cast.discovery.DescriptionFetcher",
        "app.muplay.cast.discovery.RendererDirectory",
        "app.muplay.cast.discovery.DiscoveryResult",
      ),
    ),
    // The transport, and the coroutine artefacts of the two suspend classes, on LINE.
    //
    // `DatagramSsdpTransport` measures 3/3, its `Companion` 4/4 and its `search$2` body 25/25
    // LINE -- every line runs, against a real `DatagramSocket` and a real responder on loopback.
    // Its BRANCH (6/8 on the companion, 11/14 in the read loop) is the interface filter
    // (`isUp && !isLoopback && supportsMulticast`, whose arms depend on the host's own hardware)
    // and the socket-timeout poll, and lowering a floor to fit those is what this table refuses to
    // do -- so LINE gates what can honestly be gated, exactly as it does for `LocalAddress` above.
    //
    // The read loop's third missing arm arrived in this task's fix round and is worth naming,
    // because it is a *deliberately* unreachable one rather than a hardware-dependent one: the
    // `LocalNetworkOnly.isLocal(packet.address)` guard added there is redundant by construction
    // (`SsdpSearch.parseResponse` already requires the announced `LOCATION` to be an IP literal
    // equal to that same address), so nothing can make it change an outcome and no probe exists
    // for it. Its *line* is covered, which is what this rule gates; see the guard's own comment in
    // `SsdpTransport.kt` for why a provably redundant check is kept at all.
    //
    // `RendererDirectory*` catches `describe$xml$1` (1/1 LINE; its BRANCH is 3/4, the missing one
    // being the coroutine `label` check whose other arm is unreachable by construction) and four
    // suspend continuations carrying no counters at all. `SsdpTransport`, the interface, has no
    // counters either and is here so nothing in this package is left unmatched.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.discovery.DatagramSsdpTransport*",
        "app.muplay.cast.discovery.RendererDirectory*",
        "app.muplay.cast.discovery.SsdpTransport",
      ),
    ),
    // Plan 6 Task 3, `app.muplay.cast.soap`. Measured from
    // `core/cast/build/reports/jacoco/test/jacocoTestReport.xml` after a plain `:core:cast:test`,
    // no emulator anywhere. Every class named here carries BRANCH counters -- checked first,
    // because a CLASS rule over a class with none is a `0/0` COVEREDRATIO, which is `NaN`, which
    // JaCoCo reports as no violation at every minimum:
    //
    //   `SoapEnvelope`   34/34 -- `render`'s three validations, `parseResponse`'s "no Body", "no
    //                    response element" and "a response for a different action" arms (all
    //                    three now answering `null` rather than an empty map -- Task 3's fix
    //                    round, the review's MEDIUM: "the device answered nothing" and "the device
    //                    answered no out arguments" are different facts, and `SoapClient.invoke`
    //                    turns only the first into a `SoapTransportException`),
    //                    `parseFault`'s four (not a fault / no `UPnPError` detail / an
    //                    `errorCode` that is not a number / no `errorCode` at all), the DOCTYPE
    //                    refusal, the unparseable-XML arm, and (Task 2's fix round) `descendant`'s
    //                    depth bound refusing and permitting -- the same StackOverflowError this
    //                    module's `DeviceDescription.parseDevice` carried, in the walker
    //                    `SoapClient.invoke` reaches on **every** response, outside its
    //                    `try`/`catch`. Was 32/32. `render`'s escaping of argument values (the
    //                    same fix round, the review's HIGH 1) adds no branch: it is a call.
    //   `SoapNames`      20/20 -- each of the four `require`s refusing and accepting, the two
    //                    control-URL arms, and `quoteSafely`'s printable/non-printable split.
    //   `UpnpTime`       16/16 -- `parseClock`'s empty, `NOT_IMPLEMENTED` and no-match arms and
    //                    all four fraction lengths; `formatClock`/`formatDuration`'s clamps.
    //   `UpnpError`       2/2  -- `describe`'s known and unknown code.
    //   `UpnpErrorException` 2/2 -- the message's fallback to `UpnpError.describe` when the device
    //                    sent a code but no description.
    //
    // `UpnpFault`, `SoapArgument`, `SoapTransportException` and `MalformedSoapRequestException`
    // ride along at zero branches each, the same way this module's discovery record types do --
    // included so `warnUngatedClasses` stays quiet, gating nothing. The floor is not vacuous
    // regardless: 72 of its BRANCH counters come from the five classes above.
    //
    // Falsified rather than assumed, and RE-MEASURED in Task 3's fix round -- which is the part
    // worth reading, because the previously recorded falsification had gone stale and would have
    // been believed. It said that withholding `SoapEnvelopeTest`'s `a fault this client cannot
    // read a code out of is still a fault`, `a body that is not xml at all, or has no Body
    // element, ...` and `a fault carrying a DOCTYPE is refused rather than parsed` together drops
    // `SoapEnvelope` to 27/32 = 0.84 and fires this floor. Withholding exactly those three today
    // leaves it at **32/34 = 0.9412 and this floor GREEN**: the fix round gave three of those arms
    // second drivers (`SoapClientTest`'s `a 200 with no response element is a transport failure`
    // reaches the "no Body" arm, and `FakeRendererStrictnessTest`'s `a body carrying a DOCTYPE is
    // refused rather than parsed` reaches `declaresDoctype`, which the fake now shares rather than
    // copies). Withholding those three **plus** `a doctype hidden behind a five kilobyte comment
    // is still seen by the guard`, `a response for a different action is not accepted as this one`
    // and the fake's own DOCTYPE test drops it to **30/34 = 0.8824** and this floor fails --
    // *"Rule violated for class app.muplay.cast.soap.SoapEnvelope: branches covered ratio is 0.88,
    // but expected minimum is 0.90"*.
    //
    // The other recorded near-miss still stands: withholding `UpnpTimeTest`'s `NOT_IMPLEMENTED and
    // the other unusable values are null, not zero` leaves `UpnpTime` at **15/16 = 0.9375** and
    // this floor still passes, the `NOT_IMPLEMENTED` arm being also driven by
    // `FakeRendererStrictnessTest`'s seek-target rejection.
    //
    // The measurement is 1.0000 and the floor is 0.90 on purpose -- raising a minimum above a
    // measured 1.0000 is not a way to watch a gate fire, because JaCoCo validates the minimum is
    // within 0.0..1.0 before it ever reads a ratio.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.soap.SoapEnvelope",
        "app.muplay.cast.soap.SoapNames",
        "app.muplay.cast.soap.SoapArgument",
        "app.muplay.cast.soap.UpnpError",
        "app.muplay.cast.soap.UpnpErrorException",
        "app.muplay.cast.soap.UpnpFault",
        "app.muplay.cast.soap.SoapTransportException",
        "app.muplay.cast.soap.MalformedSoapRequestException",
        "app.muplay.cast.soap.UpnpTime",
      ),
    ),
    // The two soap classes a BRANCH rule would measure nothing on, on LINE instead -- and which
    // one goes where is a measurement, not a preference:
    //
    //   `XmlText`         **no BRANCH counter at all**. `escape` and `unescape` are five chained
    //                     `replace` calls each, with no conditional anywhere in them, so a BRANCH
    //                     rule over this class would be the silent `NaN` pass described above --
    //                     over the one class in this package whose ordering defect this task
    //                     exists to prevent. LINE 12/12.
    //   `SoapClient*`     `SoapClient` itself is LINE 3/3 with no branches; the real body is the
    //                     `invoke$2` continuation, LINE 19/19 and BRANCH **7/8** -- the missing
    //                     arm being the coroutine `label` check whose other arm is unreachable by
    //                     construction, exactly as recorded for `RendererDirectory$describe$xml$1`
    //                     above. Lowering a BRANCH floor to fit that is what this table refuses to
    //                     do, so LINE gates what can honestly be gated. Was LINE 18/18 and BRANCH
    //                     5/6; Task 3's fix round added the elvis that turns an unreadable 200
    //                     into a `SoapTransportException`, which is one line and two branches.
    //
    // Falsified, and RE-MEASURED in that fix round because the note here had gone stale in the way
    // it predicted itself: it said withholding `XmlTextTest`'s two `unescape` tests drops `XmlText`
    // to LINE 6/12 = 0.50, and added *"Task 4's DIDL round trip will be the second caller"*. Task 4
    // landed. Withholding those two alone now leaves `XmlText` at **12/12 = 1.0000 and this floor
    // GREEN**. What fires it is those two together with `DidlLiteTest`'s two decoding tests --
    // `didl survives being embedded in a soap envelope and read back out` and `the metadata
    // argument carries the document escaped exactly once` -- which drops `XmlText` to
    // **6/12 = 0.50**: *"Rule violated for class app.muplay.cast.soap.XmlText: lines covered ratio
    // is 0.50, but expected minimum is 0.90"*.
    //
    // The `SoapClient*` half of this floor is the honest weak one, and the measurement is recorded
    // rather than hidden: withholding all four of `SoapClientTest`'s failure-path tests (`a
    // renderer that has gone away`, `a refused action throws with the device's own error code`, `a
    // status this client cannot read`, `a 200 with no response element is a transport failure`)
    // leaves `invoke$2` at LINE **18/19 = 0.9474** -- still green -- while its BRANCH falls to
    // 5/8 = 0.6250. That is what a LINE floor over a class whose every line has several callers
    // buys, and it is why the four mutation probes on `SoapClient` in `ci/mutation-probes.sh`, not
    // this floor, are what actually holds that class.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.soap.XmlText",
        "app.muplay.cast.soap.SoapClient*",
      ),
    ),
    // Plan 6 Task 4, `app.muplay.cast.didl`. Measured from
    // `core/cast/build/reports/jacoco/test/jacocoTestReport.xml` after a plain `:core:cast:test`,
    // no emulator anywhere. Which class goes on which rule is a measurement and not a preference,
    // because a CLASS-element rule over a class carrying no counter of that kind is a `0/0`
    // COVEREDRATIO, which is `NaN`, which JaCoCo reports as no violation at every minimum:
    //
    //   `MimeAgreement`          BRANCH 48/48 -- the missing `<res>`, the DOCTYPE refusal and the
    //                            unparseable document; a `protocolInfo` with too few fields and
    //                            one with an empty MIME field; a URL with no extension, one whose
    //                            extension this client does not serve, one `URI` will not parse
    //                            and an opaque `mailto:`; an absent and a blank `Content-Type`;
    //                            and the disagreement check itself, at one distinct value and at
    //                            more than one.
    //   `DidlLite`               BRANCH 6/6 -- each of the three optional fields present and
    //                            absent. All six are author-written; there is no codegen here.
    //   `ServedMedia$Companion`  BRANCH 8/8 -- `of`'s `Mp3` and `Raw` arms, the `RAW_TYPES` hit
    //                            and miss, the null suffix, and `forExtension`'s hit and miss.
    //
    // `ServedMedia` itself is on the LINE rule below rather than here, for exactly the reason
    // `XmlText` is: it carries **no BRANCH counter at all**. `protocolInfo` and `fileName` are
    // string building with no conditional anywhere in them, so a BRANCH rule over this class would
    // be the silent `NaN` pass -- over the type this whole task exists to make single-valued.
    // LINE 16/16.
    //
    // Falsified per class, and the first attempt is recorded because it FAILED to fire, which is
    // the interesting half:
    //
    //   * withholding `DidlLiteTest`'s `an absent optional field is omitted rather than rendered
    //     empty` **alone leaves `DidlLite` at 6/6 = 1.0000 and this floor green**. The three
    //     optional fields' absent arms are also driven by `MimeAgreementTest`'s
    //     `every format this client serves agrees with itself on all three legs`, which renders an
    //     item with all three null. Withholding that sweep as well drops `DidlLite` to
    //     **3/6 = 0.50** and the rule fires -- *"Rule violated for class
    //     app.muplay.cast.didl.DidlLite: branches covered ratio is 0.50, but expected minimum is
    //     0.90"*. One withheld test is not always enough, and a near-miss is worth recording
    //     rather than re-deriving.
    //   * `MimeAgreement`: withholding `a document with no res element, or an unreadable one, is
    //     reported` alone drops it to **43/48 = 0.8958** and the rule fires. Note how thin that
    //     is -- five branches of forty-eight is the whole margin -- which is the honest state of a
    //     class this size at a 0.90 floor, not a reason to raise the minimum.
    //   * `ServedMedia$Companion`: withholding `ServedMediaTest`'s `a transcode is served as mp3,
    //     whatever the source file was` together with `opus never reaches a renderer, by
    //     construction` leaves `of`'s `is StreamFormat.Mp3 ->` arm unexecuted, at
    //     **7/8 = 0.8750**, and the rule fires naming `ServedMedia.Companion`. That arm is the one
    //     the whole task turns on: it is what stops an Opus source being announced as `audio/ogg`
    //     while MP3 bytes are served.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.didl.DidlLite",
        "app.muplay.cast.didl.MimeAgreement",
        "app.muplay.cast.didl.ServedMedia*Companion",
      ),
    ),
    // The one class in this package with real code and no branches, and the two declaration-only
    // types beside it -- LINE, the same shape and the same argument as `XmlText` above.
    //
    //   `ServedMedia`                 LINE 16/16. `protocolInfo`, `fileName`, and the data class.
    //   `CastItem`                    LINE 10/10, declaration only (JaCoCo's Kotlin filters remove
    //                                 a data class's generated members, so what is left is the
    //                                 constructor and the `copy` the tests use).
    //   `MimeDisagreementException`   LINE 1/1, declaration only.
    //
    // `MimeDisagreementException` is NOT a ride-along here, unlike the zero-counter passengers on
    // the rules above: its one line is the exception's construction, reached only when a refusal
    // actually happens. Falsified as such -- withholding `MimeAgreementTest`'s `require refuses a
    // disagreement as an IOException naming every leg` **alone** takes it to **0/1 = 0.0000** and
    // this floor fires: *"Rule violated for class app.muplay.cast.didl.MimeDisagreementException:
    // lines covered ratio is 0.00, but expected minimum is 0.90"*. So the one class in this
    // package that exists to say no is gated on whether anything ever makes it say no.
    //
    // `ServedMedia` itself takes considerably more to move, and the number is recorded so nobody
    // repeats the search: its two behavioural lines are `protocolInfo` and `fileName`, and both
    // have several callers, so withholding `ServedMediaTest`'s three `protocolInfo`/`opus` tests,
    // `MimeAgreementTest`'s three tests that mint a URL through `fileName`, and the seven
    // `DidlLiteTest` tests that render -- thirteen in all -- is what leaves it at
    // **14/16 = 0.8750** and fires the rule. That is the shape of a value with many readers: no
    // single test is load-bearing for it, which is the reason it is worth gating at all.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.cast.didl.ServedMedia",
        "app.muplay.cast.didl.CastItem",
        "app.muplay.cast.didl.MimeDisagreementException",
      ),
    ),
  ),
  // `:integrations:core`. `IntegrationBaseUrl`'s parse cascade is pure Kotlin over OkHttp's URL
  // parser with no Android dependency at all -- which is why it is a Tier-1-enforceable BRANCH
  // floor and why it lives in this module rather than inside either client. Measured in Plan 7
  // Task 1 Step 8; re-measured in Task 10 once the credential store and the request store are in.
  //
  // Two rules because the module carries two kinds of class, and one measured fact decides which
  // rule each gets: **only three classes here carry a BRANCH counter at all**. JaCoCo's Kotlin
  // filters remove the generated `equals`/`hashCode`/`toString`/`copy` of a `data class` and the
  // whole of a `data object` from the report, and they remove an enum's `values`/`valueOf` -- so
  // `BaseUrlResult`'s five members and `CleartextPolicy`'s two contribute no branches for any rule
  // to gate, and `IntegrationService` contributes none either. Measured:
  //
  //   IntegrationBaseUrl$Companion   BRANCH 16/16   LINE 19/19
  //   IntegrationBaseUrlKt           BRANCH  8/8    LINE 11/11
  //   IntegrationBaseUrl             BRANCH  6/6    LINE  5/5
  //   IntegrationService             BRANCH  n/a    LINE  3/3
  //   BaseUrlResult$Valid            BRANCH  n/a    LINE  1/1
  //   BaseUrlResult$CleartextForbidden BRANCH n/a   LINE  1/1
  //   BaseUrlResult, BaseUrlResult$Blank/$MissingScheme/$Malformed, CleartextPolicy and both of
  //   its members: no BRANCH and no LINE counter at all, so no rule can gate them and
  //   `warnUngatedClasses` skips them.
  //
  // 30/30 BRANCH against the 0.90 target, not written as 1.00: this table's floors are set at the
  // project target and rounded down from the measurement, and a floor at the measured 1.00 would
  // go red on a refactor that changed nothing. Falsified rather than assumed, twice, both recorded
  // in task-1-report.md: withholding `a url equals only itself and another url with the same
  // value` alone drops `IntegrationBaseUrl` to 4/6 = 0.6667 and this floor fails at its real
  // minimum; withholding the three tests that name an `IntegrationService` drops
  // `IntegrationBaseUrlKt` to 0/8 and `IntegrationService` to LINE 0/3, failing one rule each.
  //
  // The LINE rule names `IntegrationService` and `BaseUrlResult*` explicitly rather than widening
  // the BRANCH rule's pattern to `app.muplay.integrations.*`: those two are the classes this task
  // measured and decided about, and a wildcard would also swallow every class Tasks 2-11 add to
  // this module into a rule that cannot fail on them, which is the silent hole
  // `warnUngatedClasses` exists to report. A genuinely new class here should show up as ungated.
  ":integrations:core" to listOf(
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.integrations.IntegrationBaseUrl*"),
    ),
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.IntegrationService",
        "app.muplay.integrations.BaseUrlResult*",
      ),
    ),
    // Task 2, and this one is a **security control's** floor, which is why it is a LINE rule in
    // the *fast* tier rather than an instrumented one. `IntegrationCredentials$Lidarr` measures
    // **5/5 LINE and no BRANCH counter at all** -- exactly `SubsonicCredentials`' shape in
    // `:core:model`, and gated for exactly the same reason: its one piece of author-written
    // behaviour is a hand-written `toString()` whose only job is keeping an API key out of logs
    // and crash reports, and a `data class`'s generated `toString()` would print it. A BRANCH rule
    // cannot gate a class with no branches (JaCoCo's NaN pass), so LINE is the only counter that
    // can hold this at all.
    //
    // JVM-measurable, and that is a measurement rather than a hope: `IntegrationCredentialsTest`
    // is a plain JUnit 5 test -- `toString` is string interpolation and `IntegrationBaseUrl.parse`
    // names no Android type -- so this floor is enforced by `jacocoJvmCoverageVerification` on
    // every pull request, not only by the 45-minute tier. `:core:model`'s own note records what
    // happens otherwise: a redaction asserted only from another module's (or another tier's) tests
    // leaves the gate green while the control is silently untested.
    //
    // Falsified by withholding, since the measurement is 1.0000 and JaCoCo rejects a minimum above
    // 1.0 outright: see task-2-report.md for the ratios each withheld test produces.
    // `IntegrationCredentials` (the sealed interface) carries no counters of either kind and rides
    // along so `warnUngatedClasses` has nothing to say about it.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.IntegrationCredentials",
        "app.muplay.integrations.IntegrationCredentials*",
      ),
    ),
    // Task 2. The companion's own `when` over `IntegrationService` -- **2/2 BRANCH, 7/7 LINE** --
    // and, like the rule above it, in the *fast* tier by measurement: `keyAlias` is a `when` over
    // an enum with no Android type anywhere near it, so `IntegrationCredentialsTest` reaches it
    // from a plain JVM run.
    //
    // Its own rule rather than a ride-along, because the per-service alias is the independence
    // property the whole plan rests on: a shared alias makes `clear(LIDARR)` either leave a key
    // that still opens Bindery's blob or destroy it, and neither failure is visible to a test that
    // configures one service. Note what a ratio still cannot see -- a `keyAlias` that returned one
    // constant for both services has the same 2/2 branch coverage -- which is why
    // `ci/mutation-probes.sh` carries `integrations/keyAlias-service` as well.
    //
    // `*Companion`, not `*`: the plain wildcard also matches `save$2`/`clear$2` (1/2 each, the
    // coroutine state machine's own `label` check whose other arm is unreachable by construction),
    // which would make a 0.90 minimum fail on the Kotlin compiler's output.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.integrations.IntegrationCredentialStore*Companion"),
    ),
    // Task 2. `IntegrationCredentialStore`'s own author-written branches: **17/18**, instrumented
    // only -- DataStore and AndroidKeyStore are both device-only, so there is no JVM tier for this
    // class.
    //
    // Sixteen of the eighteen are `read`'s five `?: return null` guards (absent base URL, absent
    // sealed secret, absent Keystore key, a blob that will not open, a stored URL the cleartext
    // policy now refuses) and the `when` over `service`. Each was closed by a test that plants the
    // state on disk by hand rather than by one that hopes to reach it:
    // `aPartiallyWrittenServiceReadsAsNotConfigured`,
    // `aCredentialWhoseKeyWasDestroyedOutFromUnderItReadsAsNotConfigured`,
    // `aTamperedCiphertextReadsAsNotConfiguredRatherThanThrowing`,
    // `aStoredCleartextUrlIsDroppedRatherThanUsed` and
    // `aStoredBinderyEntryReadsAsNotConfiguredRatherThanAsLidarrs`. That last one is why this
    // number is 17/18 rather than 16/18: the `BINDERY -> null` arm was reachable by no test in the
    // suite, and the answer was to write the test that plants a complete, openable Bindery entry
    // -- which also proves `read` does not hand back Lidarr's credential for it -- rather than to
    // lower this floor to accommodate an arm nothing exercised.
    //
    // The eighteenth is Kotlin's unreachable non-null path of
    // `(parse(...) as? Valid)?.url ?: return null`: when the `as?` succeeds, `url` cannot be null.
    // Same shape and same reason as `:feature:library`'s `CoverArtCacheKeyKt` at 0.75 and
    // `:feature:setup`'s `SetupFailureReasonKt` at 0.85 -- an artifact of how the null-safe chain
    // compiles, not an uncovered case -- so **0.90 against a measured 0.9444 is the honest
    // ceiling here**, and raising it to 1.00 would fail the build on the Kotlin compiler.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.integrations.IntegrationCredentialStore"),
      requiresInstrumentedData = true,
    ),
    // Task 2. The same class's LINE (**27/27**), the companion's (7/7), and the Hilt provider that
    // decides *where* a user's API key is written (`IntegrationsDataModule`, **3/3**).
    //
    // The provider is here rather than left ungated for the reason `:core:database`'s
    // `di.DataModule` is: it is the code that opens the shipped file, `IntegrationCredentialStoreTest`
    // builds its DataStore over a file of its own and never runs a line of it, and "obviously fine,
    // exercised by nothing" is a shape this project has found repeatedly.
    // `IntegrationsDataModuleTest` is what covers it, and its
    // `signingOutOfNavidromeDoesNotForgetAConfiguredIntegration` is the assertion that would fail
    // if this provider ever named `credentials.preferences_pb` -- which `CredentialStore.clear()`
    // empties whole.
    //
    // `IntegrationPreferences`, the `@Qualifier` annotation class, rides along carrying no counters
    // of either kind, exactly as `CastPreferences` does in `:core:database`'s own rule.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.IntegrationCredentialStore",
        "app.muplay.integrations.IntegrationCredentialStore*Companion",
        "app.muplay.integrations.IntegrationPreferences",
        "app.muplay.integrations.di.IntegrationsDataModule",
      ),
      requiresInstrumentedData = true,
    ),
    // Task 2. The Kotlin compiler's own output for this module's first `suspend` bodies and its
    // first `Flow.map`, gated low rather than not at all -- the identical trade `:core:database`
    // already makes for `CredentialStore*`'s family, and made here for the identical reason:
    // excluding them would need a pattern broad enough to also catch author-written nested classes,
    // and leaving them ungated would print `warnUngatedClasses` lines on every run forever, which
    // is how a warning mechanism dies.
    //
    // Measured: `save$2` and `clear$2` 4/4 LINE each (the `dataStore.edit` lambda bodies),
    // `special$$inlined$map$1` 2/3 = 0.6667 and `special$$inlined$map$1$2` 1/2 = 0.5000 (the
    // `configured` Flow's `map`), and `load$1`/`save$1`/`clear$1`/`map$1$1`/`map$1$2$1` with no
    // LINE counter at all (0/0, JaCoCo's isNaN pass, which is not the same thing as excluded).
    // 0.50 is a real number this run produced, not a round one.
    //
    // Their BRANCH is deliberately not gated: `save$2` and `clear$2` measure 1/2, and in both cases
    // the missing branch is the coroutine state machine's own `label` check, whose other arm throws
    // `IllegalStateException("call to 'resume' before 'invoke' with coroutine")` and is unreachable
    // by construction. The two classes with real branches -- the store and its companion -- have
    // their own BRANCH rules above, which is what the `excludes` here keeps true.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.50"),
      includes = listOf("app.muplay.integrations.IntegrationCredentialStore*"),
      excludes = listOf(
        "app.muplay.integrations.IntegrationCredentialStore",
        "app.muplay.integrations.IntegrationCredentialStore*Companion",
      ),
      requiresInstrumentedData = true,
    ),
    // Task 3, the request store. Four rules, and which tier each lands in is a measurement rather
    // than a preference: the status type is pure Kotlin and reachable from the JVM, and everything
    // that touches Room is not reachable without a device at all.
    //
    // The **fast tier**, enforced by `jacocoJvmCoverageVerification` on every pull request:
    // `RequestStatusKt` (the two `when` cascades that decide what goes in the `status` and
    // `status_detail` columns) at **18/18 BRANCH, 14/14 LINE**, and `RequestStatus$Companion`
    // (`fromStored`, the read side of the same two columns) at **16/16 BRANCH, 8/8 LINE**.
    // `RequestStatusTest` is a plain JUnit 5 test -- a `when` over a sealed interface and a
    // `toIntOrNull` name no Android type -- so this is genuinely a Tier 1 floor and not one that
    // only the 45-minute tier can see.
    //
    // `storedName`/`storedDetail` are **extension properties in `RequestStatus.kt`, not members of
    // the interface**, and that is what makes `RequestStatusKt` exist to be gated. As interface
    // members with default getters they compiled to a JVM default method plus a
    // `RequestStatus$DefaultImpls` Java-compat bridge that no Kotlin call site reaches: measured
    // at **LINE 0/4** here, in a class 0.90 fails outright and a 0.00 minimum could never fail --
    // the unfireable floor this project has shipped once and does not intend to ship again. See
    // the KDoc on those two properties.
    //
    // Falsified, not assumed, and the withholding is recorded because a record like this goes
    // stale the moment a second caller appears (see CLAUDE.md's note on exactly that): withholding
    // `every member round-trips to an equal value` **and** `the detail column carries the member's
    // data and nothing else` from `RequestStatusTest` drops `RequestStatusKt` to **8/18 = 0.4444**
    // BRANCH and `RequestStatus$Companion` to **10/16 = 0.6250**, and
    // `jacocoJvmCoverageVerification` fails on both. One of the two alone is not enough --
    // `roundTrip` in the first reads `storedDetail` for every member, which is what the second
    // asserts about.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.RequestStatusKt",
        "app.muplay.integrations.RequestStatus*Companion",
      ),
    ),
    // The same two classes' LINE, plus the three data-carrying members' 1/1 constructors
    // (`RequestStatus$Downloading`, `$Arrived`, `$Failed`) and `MediaRequest$Companion`'s `idFor`
    // (1/1) -- every one of them measured 1.0000 and every one of them reachable from the JVM
    // tier, since `RequestStatusTest` constructs each member at two values and calls `idFor` at
    // three. `RequestStatus` itself and its two `data object` members carry no counter of either
    // kind and ride along, exactly as `IntegrationCredentials` does in the rule above.
    //
    // Falsified by withholding `the request id is derived from the service and the external id`
    // alone: `MediaRequest$Companion` drops to **LINE 0/1 = 0.0000** and this rule fails. (The two
    // withheld tests recorded against the BRANCH rule above also take `RequestStatusKt` to LINE
    // 7/14 = 0.5000 and the companion to 6/8 = 0.7500, so this rule has two independent
    // falsifications rather than one.)
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.RequestStatusKt",
        "app.muplay.integrations.RequestStatus",
        "app.muplay.integrations.RequestStatus*",
        "app.muplay.integrations.MediaRequest*Companion",
      ),
      excludes = listOf("app.muplay.integrations.MediaRequestRepository*"),
    ),
    // `MediaRequestRepository`'s own author-written branches: **11/12**, instrumented only. Real
    // Room and real SQL need a device, and a fake DAO would not prove the two properties this
    // class exists to have -- that the newest-first order comes out of the query rather than out
    // of insertion luck, and that `requests(service)` passes its argument through.
    //
    // The twelfth branch is Kotlin's unreachable null path of `existing?.status ?: ...`: JaCoCo
    // counts four branches on that line and the `existing.status == null` arm cannot happen,
    // because the column is a non-null `String`. Same artifact, same reason, as
    // `IntegrationCredentialStore`'s eighteenth branch four rules up and `CoverArtCacheKeyKt`'s
    // fourth in `:feature:library` -- so **0.90 against a measured 0.9167 is the honest ceiling**
    // and a 1.00 here would fail the build on the Kotlin compiler rather than on this project's
    // code.
    //
    // LINE for the row's storage shapes in the same rule set below rather than here: this class
    // measures 43/43 LINE, and the entity, the database and the model have no branches at all.
    //
    // Falsified by withholding `reRequestingTheSameThingUpdatesTheRowRatherThanDuplicatingIt` and
    // `reRecordingKeepsTheStatusTheRowAlreadyReached` from `MediaRequestRepositoryTest`: those two
    // are the only tests that re-record an existing row, so all three `existing?.x ?: y` arms go
    // uncovered and this class drops to **5/12 = 0.4167**, failing at its real minimum.
    CoverageFloor(
      counter = "BRANCH",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf("app.muplay.integrations.MediaRequestRepository"),
      requiresInstrumentedData = true,
    ),
    // The instrumented LINE side: `MediaRequestRepository` 43/43, `MediaRequest` 10/10 (its
    // constructor and nine property getters, which only a row read back out of SQLite reaches),
    // `db.MediaRequestEntity` 12/12 and `db.IntegrationRequestsDatabase` 1/1. All four measured
    // 1.0000, gated at the project target rather than at the measurement for the reason the
    // `IntegrationBaseUrl` entry above gives: a floor pinned to 1.00 goes red on a refactor that
    // changed nothing.
    //
    // `db.MediaRequestDao` and `IntegrationRequestsDatabase$Companion` carry no counters and ride
    // along. Room's generated `MediaRequestDao_Impl`/`IntegrationRequestsDatabase_Impl` are not in
    // the report at all -- generated code is excluded before it gets here -- which is what keeps
    // this rule's `db.*` wildcard from gating a code generator's output.
    //
    // Falsified, and the **first attempt failed to falsify it**, which is worth recording rather
    // than quietly fixing: withholding the four `setStatus*` tests and `forgetRemovesOnlyTheRowIt
    // Names` left this class at **42/43 = 0.9767 and green**, because
    // `reRecordingKeepsTheStatusTheRowAlreadyReached` is a *second caller* of `setStatus` and kept
    // its body covered -- exactly the stale-falsification-record shape CLAUDE.md describes.
    // Withholding that sixth test as well drops it to **36/43 = 0.8372** and the rule fires. If a
    // seventh caller of `setStatus` ever arrives, this record needs re-measuring.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.integrations.MediaRequestRepository",
        "app.muplay.integrations.MediaRequest",
        "app.muplay.integrations.db.*",
      ),
      requiresInstrumentedData = true,
    ),
    // The Kotlin compiler's own output for `requests()`'s two `Flow.map`s, gated low rather than
    // not at all -- the identical trade the `IntegrationCredentialStore*` rule above makes, for
    // the identical reason. Measured: `requests$$inlined$map$1` and `$2` at 2/3 = 0.6667 each, and
    // `$1$2`/`$2$2` at 1/2 = 0.5000 each; `$1$1`, `$2$1`, `$1$2$1`, `$2$2$1` and `record$1` carry
    // no LINE counter at all (JaCoCo's isNaN pass, which is not the same thing as excluded). 0.50
    // is a number this run produced.
    //
    // `excludes` keeps `MediaRequestRepository` itself out, so its real 0.90 rules above stay the
    // ones that gate it.
    //
    // Falsified by withholding the four tests that collect `requests(service)` --
    // `nothingIsStoredBeforeAnythingIsRecorded`, `requestsForOneServiceComeBackNewestFirstToo`,
    // `requestsFilteredByServiceReturnsThatServicesRowsAndOnlyThose` and
    // `aServiceWithNoRowsOfItsOwnReadsAsEmptyEvenWhenTheTableIsNot`: `requests$$inlined$map$2`
    // goes to **0/3** and `$2$2` to **0/2**, and the rule fails on both.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.50"),
      includes = listOf("app.muplay.integrations.MediaRequestRepository*"),
      excludes = listOf("app.muplay.integrations.MediaRequestRepository"),
      requiresInstrumentedData = true,
    ),
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

// ------------------------------------------------------------------------------------------------
// The resolved-classpath mock guard.
//
// `plan-2-inherited.md` item 3, and `ConventionTest`'s own note asking for it. That test bans mock
// frameworks by scanning *declared* names in the catalogue, module build files and build-logic
// sources -- which cannot catch one arriving transitively, and this plan adds Room, DataStore,
// Coil, Hilt-navigation and their transitive graphs, exactly when "declared" and "resolved" stop
// being the same set. Two guards, deliberately: the textual one runs in seconds with no
// resolution, this one is the real answer.
// ------------------------------------------------------------------------------------------------

/**
 * Every mock framework this project bans, by Maven group. Groups rather than artifact names
 * because a framework's artifact set changes between versions while its group does not, and
 * because a group match cannot be defeated by a rename.
 *
 * `org.objenesis` is on the list although it is not itself a mock framework: it is the
 * instantiation engine every JVM mocking library depends on, so its presence on a test runtime
 * classpath means one of them arrived, whatever it is called.
 *
 * **`ConventionTest`'s `no mock framework is declared in any build file or convention plugin`
 * scans this file too, and would fail on the literals below.** It carves out this one declaration
 * by name and, in the same rule, asserts that every name *it* bans is covered by a group here --
 * so the carve-out cannot quietly become a hole, and the two guards cannot drift apart. See that
 * test.
 */
val BANNED_MOCK_GROUPS = listOf(
  "org.mockito",
  "io.mockk",
  "org.easymock",
  "org.powermock",
  "dev.mokkery",
  "io.mockative",
  "org.jmockit",
  "org.objenesis",
)

/**
 * Which resolvable configurations actually reach a test JVM or a test APK, **matched rather than
 * listed** -- and that is a correction this task measured, not a preference.
 *
 * The brief named three literals: `testRuntimeClasspath`, `testDebugRuntimeClasspath` and
 * `androidTestDebugRuntimeClasspath`. Only the first exists in this build. Every Android module
 * calls them `debugUnitTestRuntimeClasspath` and `debugAndroidTestRuntimeClasspath` -- printed
 * from the build itself, per project:
 *
 *     :app, :core:database, :core:designsystem, :feature:setup, :feature:library
 *         -> [debugAndroidTestRuntimeClasspath, debugRuntimeClasspath,
 *             debugUnitTestRuntimeClasspath, releaseRuntimeClasspath]
 *     :core:model, :core:network, :core:testing
 *         -> [runtimeClasspath, testRuntimeClasspath]
 *
 * So the literal list covered **three of eight modules and none of the Android ones** -- including
 * `:app`, whose `debugUnitTestRuntimeClasspath` `ConventionTest`'s own comment already records as
 * resolving 141 artifacts. Combined with the brief's `if (resolvedByConfiguration.isEmpty())
 * return@afterEvaluate`, the guard would simply not have been registered for those five projects:
 * `./gradlew verifyNoMockFrameworks` would have reported success having inspected the three
 * modules least likely to acquire a transitive mock, which is a guard reporting safety it did not
 * measure.
 *
 * A pattern rather than a longer list because the failure above was a *name* failure: any
 * configuration that both mentions a test and is a runtime classpath is in scope, whatever a
 * future AGP calls it, and a pattern that stops matching in a project still fails loudly through
 * [MockFrameworkChecker]'s empty-input branch rather than silently shrinking the guard.
 * `debugRuntimeClasspath`/`releaseRuntimeClasspath`/`runtimeClasspath` are deliberately excluded:
 * a mock framework on a *production* classpath is a different (worse) problem and one
 * `ConventionTest`'s declared-name scan already covers.
 */
val MOCK_GUARD_CONFIGURATION_PATTERN = Regex("^.*[Tt]est.*RuntimeClasspath$")

/** The name of the guard task, so the workflow step and `ConventionTest` can name the same string. */
val MOCK_GUARD_TASK_NAME = "verifyNoMockFrameworks"

object MockFrameworkChecker {
  /**
   * Fails when any [banned] group appears in [resolved], and **also** fails when [resolved] is
   * empty. The second half is the point: a check that cannot report its own subject's absence is
   * not a check, and a guard that silently inspects zero classpaths reads exactly like a guard
   * that found nothing wrong.
   */
  fun check(projectPath: String, resolved: Map<String, List<String>>, banned: List<String>) {
    if (resolved.isEmpty()) {
      throw GradleException(
        "$projectPath: verifyNoMockFrameworks resolved no classpaths at all. Either this project " +
          "has no test configuration (in which case it should not have this task) or the names " +
          "MOCK_GUARD_CONFIGURATION_PATTERN matches have drifted. A guard that inspects " +
          "nothing passes for the wrong reason.",
      )
    }
    val offenders = resolved.flatMap { (configuration, artifacts) ->
      artifacts.filter { artifact -> banned.any { artifact.startsWith("$it:") } }
        .map { "$configuration -> $it" }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException(
        "$projectPath: a mock framework reached a test classpath: $offenders. This project uses " +
          "hand-written fakes only (see the spec's testing section); a test satisfied by a mock " +
          "returning what it was told returns no information.",
      )
    }
  }
}

subprojects {
  // `afterEvaluate`, because a project's configurations do not exist until its own build script
  // and its plugins have run -- the same reason the `liveNavidromeTest` registration above uses it.
  afterEvaluate {
    // `:core` and `:feature` are container projects: `settings.gradle.kts` includes `:core:model`
    // and friends, which brings their parents into the build with no plugins, no configurations
    // and no `check` task. That -- not "a module with no tests" -- is the only legitimate reason
    // to skip, and it is tested by asking for the thing this guard actually attaches to rather
    // than by name-matching a path. Confirmed by running it the other way first: with no condition
    // at all the build died with "Task with name 'check' not found in project ':core'".
    if (tasks.findByName("check") == null) return@afterEvaluate

    val modulePath = path
    val banned = BANNED_MOCK_GROUPS

    val guard = tasks.register(MOCK_GUARD_TASK_NAME) {
      group = "verification"
      description = "Fails if any mock framework is on a resolved test runtime classpath."

      // **Read here, inside the task's own configuration block, not in `afterEvaluate` above.**
      // `tasks.register` is lazy, so this runs when the task is realized -- after AGP's variant
      // callbacks have created `debugUnitTestRuntimeClasspath` and
      // `debugAndroidTestRuntimeClasspath`. Read one level out, in `afterEvaluate`, and every
      // Android module reports an entirely empty container: measured, printed per project,
      // `:app -> []`, `:core:database -> []`, `:core:designsystem -> []`, `:feature:library -> []`,
      // `:feature:setup -> []`, against `[runtimeClasspath, testRuntimeClasspath]` for the three
      // JVM modules. With the brief's early return on an empty result that is a guard which
      // quietly stops existing for five of eight projects while `./gradlew verifyNoMockFrameworks`
      // still says BUILD SUCCESSFUL.
      val inputs = configurations
        .filter { it.isCanBeResolved && MOCK_GUARD_CONFIGURATION_PATTERN.matches(it.name) }
        .sortedBy { it.name }
        .map { configuration ->
          // A lazy Provider captured at configuration time, never a live Configuration read inside
          // the task action: this is the pattern `Jacoco.kt`'s agent assertion already uses, and it
          // is what keeps the configuration cache able to serialize this task.
          //
          // The **resolution result**, not `incoming.artifacts.resolvedArtifacts`, which the brief
          // used. Artifacts are files, so asking for them makes this guard build every project
          // dependency first -- and asking for them from a task action fails outright:
          // `:core:network:verifyNoMockFrameworks` died with "Querying the mapped value of
          // provider(java.util.Set) before task ':core:model:jar' has completed is not supported".
          // A guard on *which components are on the graph* needs the graph, not the jars.
          configuration.name to configuration.incoming.resolutionResult.rootComponent.map { root ->
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque<ResolvedComponentResult>()
            queue.add(root)
            while (queue.isNotEmpty()) {
              val component = queue.removeFirst()
              if (!seen.add(component.id.displayName)) continue
              component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                .forEach { queue.add(it.selected) }
            }
            seen.toList()
          }
        }

      doLast {
        MockFrameworkChecker.check(modulePath, inputs.associate { it.first to it.second.get() }, banned)
      }
    }
    tasks.named("check") { dependsOn(guard) }
  }
}

/**
 * `build-logic`'s own JVM tests, wired into the root build's `check`.
 *
 * `build-logic` is a **separate Gradle build** (`pluginManagement { includeBuild("build-logic") }`
 * in settings.gradle.kts), so its tasks are invisible to `./gradlew check` and to every CI job:
 * nothing in `.github/workflows/` names them, and nothing would. That is not a detail -- until
 * Plan 3 Task 5's review round, `build-logic` had no test source set at all, and the two security
 * gates it contains (`VerifyMergedManifestTask`, `VerifyNoDestructiveMigrationTask`) had their
 * *behaviour* verified nowhere. Adding a test that nothing runs would have been the same defect
 * with a longer changelog.
 *
 * `gradle.includedBuild(..).task(..)` is the supported way to depend across that boundary. Named
 * from the root's own `check` rather than added to a CI step, for the reason every other gate in
 * this file is: a check that has to be remembered is a check that stops running.
 */
tasks.named("check") {
  dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}
