import java.math.BigDecimal
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

// Every module applies its plugins through a build-logic convention plugin (`muplay.*`, defined
// in build-logic/convention), which applies the underlying AGP/Kotlin/KSP/Hilt plugins itself
// with an explicit, catalogue-pinned version — so nothing needs declaring here beyond the two
// pieces of cross-module *policy* below (as opposed to *mechanism*, which lives in build-logic):
// the coverage floor table, and the one project-specific test task `:core:network` needs for a
// real Navidrome container.

/**
 * Branch-coverage floors, keyed by project path, enforced by every module's own
 * `jacocoTestCoverageVerification` task (Tier 1's coverage-gate step — see the "Coverage gate"
 * step in `.github/workflows/pr.yml`, and `Jacoco.kt` in build-logic for the mechanism this table
 * supplies the numbers for: which classes/execution-data a module's task reads is decided there;
 * whether a given module has a floor at all, and what it is, is decided here, once, rather than
 * once per module).
 *
 * Every number below is **measured** from a real `jacocoTestReport` run
 * (`./gradlew jacocoTestReport` per module — see task-7-report.md for the exact transcript),
 * rounded down a little from the exact figure so a routine, no-behaviour-change refactor does not
 * flip the gate red on noise — never an invented round number, and never a number the check
 * mathematically cannot fail at. (This project has shipped exactly that second defect once
 * already: a Java-era coverage table carried an entry of `0.00` — `ratio < floor` can never be
 * true when `floor` is `0.00`, since a ratio can never be negative, so that "floor" could never
 * fail regardless of how coverage actually moved.)
 *
 * `:core:model`, `:core:network` and `:core:testing` measure **100%** today — 10/10, 30/30 and
 * 6/6 real branches respectively — and get the full 90% target immediately. Every gap Task 7 found
 * in them was closable from the JVM alone, so it was closed rather than excused: see
 * `ServerCapabilitiesTest` (both `supports` overloads were previously untested inside
 * `:core:model` itself — `:core:network`'s `CapabilityNegotiatorTest` exercises them too, but
 * coverage is measured per module, and execution from a *different* module's test task never
 * counts here), `SubsonicClientTest`'s new non-compliant-response and no-trailing-slash-baseUrl
 * tests, and `OpenApiFixtureValidatorTest`'s new `readSpec`/blank-path tests.
 *
 * `:feature:setup` (9/109 real branches, ~8.26%) and `:core:designsystem` (2/30, ~6.67%) measure
 * far below 90% — not because logic that could be JVM-tested was left untested (their own
 * ViewModel / failure-to-message / colour-scheme-selection logic already is, at 100%: see
 * `SetupViewModelTest`, `SetupFailureReasonTest`, `ThemeTest`), but because the rest of both
 * modules is `@Composable` function bodies. The Compose compiler inserts real, measurable branches
 * into every composable (`$changed`/`$dirty` recomposition-skip checks) that only execute when
 * something actually composes the function — a real UI, not a plain JVM unit test, can do that.
 * The two floors below are the real measured numbers, not zero and not invented; **Task 8 raises
 * both to 0.90** once instrumented (emulator) coverage — which this project's
 * `mergedExecutionData` (`Jacoco.kt`) already merges in the moment it exists — starts actually
 * exercising these bodies.
 *
 * `:app` has **no entry at all** here, deliberately, and that absence is not the same thing as a
 * `0.00` floor: every one of its 18 measured branches is Compose codegen too (`MainActivity`,
 * `MuPlayApp`, `MuPlayApplication` and `SetupRoute` contain no `if`/`when`/`?:`/`&&`/`||` of their
 * own at all — confirmed by inspection and by the measured number), so its *entire* measured
 * branch coverage is 0/18 today, from JVM tests alone, with nothing left to extract the way
 * `colorSchemeFor`/`toMessage` were. A `0.00` floor here would be exactly the unfireable gate this
 * project has already shipped once before; any floor above `0.00` would fail immediately against a
 * module with nothing actually wrong with it. Neither is honest, so `:app` simply has no floor
 * until Task 8 gives it real, non-Compose-only execution data to measure one against.
 */
val branchCoverageFloors = mapOf(
  ":core:model" to BigDecimal("0.90"),
  ":core:network" to BigDecimal("0.90"),
  ":core:testing" to BigDecimal("0.90"),
  ":feature:setup" to BigDecimal("0.08"),
  ":core:designsystem" to BigDecimal("0.06"),
)

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
  // (CI step ordering, or a developer's own command) to always happen to run tests first.
  tasks.withType<JacocoReport>().configureEach { dependsOn("test") }
  tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn("test")

    val floor = branchCoverageFloors[project.path] ?: return@configureEach
    violationRules {
      isFailOnViolation = true
      rule {
        // `element` defaults to "BUNDLE" — the whole module's aggregate ratio, which is what a
        // per-module floor means here, not a per-class one.
        limit {
          counter = "BRANCH"
          value = "COVEREDRATIO"
          minimum = floor
        }
      }
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

    tasks.register<Test>("liveNavidromeTest") {
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
