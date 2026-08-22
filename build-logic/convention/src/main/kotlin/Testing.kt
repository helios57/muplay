import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

// Kept in sync with root `build.gradle.kts`'s identical constant by hand, not by import:
// `build-logic` is included only via `pluginManagement.includeBuild` (see settings.gradle.kts),
// which exposes its *plugins* (by id) to every project but not its Kotlin source the other way
// either, so a genuine shared `const val` isn't reachable across that boundary. Searching the repo
// for `LIVE_NAVIDROME_TEST_TASK_NAME` finds both declarations; before this constant existed, a
// bare string literal on each side could drift silently -- exactly the failure mode
// `configureJUnit5`'s own doc below records having already hit once. `app/src/test/kotlin/app/
// muplay/ConventionTest.kt`'s "the live-Navidrome test task name is not hand-synced into drift"
// is what actually enforces the sync now, rather than leaving it a convention nothing checks.
internal const val LIVE_NAVIDROME_TEST_TASK_NAME = "liveNavidromeTest"

/**
 * JUnit 5 for every module: `useJUnitPlatform()` on every `Test` task (this covers Android unit
 * test tasks too — `AndroidUnitTest` is itself a `Test` subtype), plus the jupiter engine, the
 * platform launcher JUnit 5 needs at runtime, and AssertJ. One place, so no module reaches for
 * JUnit 4 or a mock framework out of habit.
 *
 * Every ordinary `Test` task excludes tests tagged `"live"` (`core/network`'s
 * `LiveNavidromeTest`, so far): those need a real Navidrome container listening on
 * `localhost:4533`, which is not true for a plain `./gradlew test` in a developer's inner loop, in
 * the static-analysis job, or in the unit+integration job (`.github/workflows/pr.yml`). Only the
 * dedicated [LIVE_NAVIDROME_TEST_TASK_NAME] task (root `build.gradle.kts`, `:core:network`-only)
 * includes them — see that task's own comment for why a separate task, rather than a `-P` flag or
 * a profile, is what draws this line.
 *
 * The one well-known task name, [LIVE_NAVIDROME_TEST_TASK_NAME], is excluded from this rule
 * rather than having it applied and then re-included: JUnit Platform's Gradle integration does
 * not treat a later `includeTags("live")` as overriding an earlier `excludeTags("live")` — both
 * this project-wide `configureEach` and that task's own
 * `useJUnitPlatform { includeTags("live") }` apply to the same task
 * (`tasks.withType<Test>().configureEach` fires for every `Test` task, including one registered
 * after this runs), and JUnit Platform resolves "both included and excluded" by excluding —
 * confirmed empirically: without this carve-out, [LIVE_NAVIDROME_TEST_TASK_NAME] ran
 * "successfully" while silently executing zero tests, logging only "The tag 'live' is both
 * included and excluded. This will result in the tag being excluded" with no error to fail the
 * build on.
 */
internal fun Project.configureJUnit5() {
  tasks.withType<Test>().configureEach {
    useJUnitPlatform {
      if (name != LIVE_NAVIDROME_TEST_TASK_NAME) {
        excludeTags("live")
      }
    }
  }

  dependencies {
    add("testImplementation", libs.findLibrary("junit-jupiter").get())
    add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    add("testImplementation", libs.findLibrary("assertj").get())
  }
}
