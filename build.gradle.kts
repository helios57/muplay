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
  ),
  // `:core:media`. Four `"CLASS"`-element rules, and the split across the two tiers is the point:
  // the *decision* about a 429 is a plain object the fast tier can hold to a floor, and everything
  // Media3 or OkHttp touches is only reachable on a device. Re-measure in Task 10, once the
  // service, the queue and the progress writer are in.
  //
  // Every class this module compiles that carries a counter at all is matched by exactly one rule
  // below; the only classes left over are `MuPlayDataSourceFactory$Companion` and
  // `NavidromeLoadErrorHandlingPolicy$Companion`, which measure zero branches *and* zero lines, so
  // [UngatedClassChecker.warnUngatedClasses] skips them and no rule can gate them anyway.
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
    // 3/3 and 12/12 = 1.0000 LINE, instrumented. `MuPlayDataSourceFactory` carries no branches, so
    // LINE is the only counter that can gate it at all -- same argument as `MediaModule` above,
    // reached from the other tier. `NavidromeLoadErrorHandlingPolicy` rides here as well as
    // carrying its own BRANCH rule: its `getRetryDelayMsFor` body is the module's one place where
    // a line can be added that adds no branch.
    CoverageFloor(
      counter = "LINE",
      element = "CLASS",
      minimum = BigDecimal("0.90"),
      includes = listOf(
        "app.muplay.media.MuPlayDataSourceFactory",
        "app.muplay.media.NavidromeLoadErrorHandlingPolicy",
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
    // 17/17 and 10/10 = 1.0000 LINE, instrumented. Both classes ride here *as well as* carrying
    // the BRANCH rule above -- the same shape `NavidromeLoadErrorHandlingPolicy` already has in
    // this table, and for a sharper reason: `MediaItems.of` is one builder chain, so a whole
    // mapped field can be deleted without moving its BRANCH counter by one. LINE is the counter
    // that notices.
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
    // ---- Plan 3 Task 3: the media cache ------------------------------------------------------
    // 2/2 = 1.0000 BRANCH, instrumented. BRANCH and not LINE because this class is *all* branch:
    // its single line is `dataSpec.key ?: throw MissingCacheKeyException(...)`, and the two sides
    // of that elvis are the whole task. A LINE floor over it would be satisfied by covering
    // either one, which is the difference between "the cache key comes from the track id" and
    // "the missing-key fallback Tempo ships is gone". Counter picked by reading the merged report
    // rather than from the non-UI default -- this class is one of the few in the module that
    // genuinely carries both.
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
    // 6/6, 2/2 and 1/1 = 1.0000 LINE, instrumented. None of these three carries a single BRANCH
    // counter -- read off the merged report, not assumed -- so LINE is the only counter that can
    // gate them at all; a BRANCH rule here would score NaN and pass at every minimum, the vacuous
    // shape this table's own doc describes and `warnVacuousFloors` reports.
    //
    // Instrumented-only, and all three reduce to "needs a real device": `MediaCache` builds a
    // `SimpleCache` over `context.cacheDir` and a `StandaloneDatabaseProvider` (real SQLite);
    // `MediaCacheModule` calls it; and `MissingCacheKeyException`'s message is only ever
    // constructed from a `DataSpec`, which holds an `android.net.Uri`. No Robolectric here, so
    // there is no JVM path to any of them.
    //
    // `app.muplay.media.di.MediaHttpClient` is deliberately absent: a `@Qualifier` annotation
    // class carries no counter of either kind, so `warnUngatedClasses` skips it and no rule could
    // gate it -- the same standing exception this module already records for its `$Companion`s.
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
  // 61/63 = 0.9683 LINE across the whole module (20/21 = 0.9524 before Task 10's journeys). The
  // one BUNDLE-element rule in this table -- see
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
