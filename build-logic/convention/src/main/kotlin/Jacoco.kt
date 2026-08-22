import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.util.PatternSet
import org.gradle.kotlin.dsl.configure
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * JaCoCo, applied uniformly to every module now so it is configured once instead of drifting
 * across the ten modules still to come. This wires collection and reporting; it does **not** set
 * a coverage floor — `jacocoTestCoverageVerification`'s branch-coverage minimums are policy, set
 * once for every module from root `build.gradle.kts`'s `branchCoverageFloors` map (Task 7), not
 * duplicated here.
 *
 * Applying the `jacoco` plugin alone already instruments every `Test` task project-wide (Android
 * unit test tasks included, since they are `Test` tasks too), so execution data is captured
 * regardless of module kind. The `java`/`org.jetbrains.kotlin.jvm` plugin combination additionally
 * registers `jacocoTestReport` **and** `jacocoTestCoverageVerification` tasks automatically; this
 * only needs to turn the report's outputs on; the verification task's `violationRules` is filled
 * in from the root project, generically, for every module (JVM or Android) that has a floor.
 * AGP registers neither task for any variant, so [configureAndroidJacocoReport] and
 * [configureAndroidJacocoCoverageVerification] add them explicitly for Android modules.
 */
internal fun Project.configureJacoco() {
  pluginManager.apply("jacoco")

  extensions.configure<JacocoPluginExtension> {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
  }

  tasks.withType(JacocoReport::class.java).configureEach {
    reports {
      xml.required.set(true)
      html.required.set(true)
    }
  }
}

private val generatedCodeExcludes = listOf(
  "**/R.class",
  "**/R\$*.class",
  "**/BuildConfig.*",
  "**/Manifest*.*",
  "**/*Test*.*",
  "android/**/*.*",
  "**/Hilt_*.*",
  "**/*_Hilt*.*",
  "**/*Hilt*.*",
  "**/*_GeneratedInjector.*",
  "**/*_Factory.*",
  "**/*_MembersInjector.*",
  "**/*Module_*Factory.*",
  "**/*_ComponentTreeDeps.*",
  "**/Dagger*.*",
  "**/dagger/hilt/**",
  "**/hilt_aggregated_deps/**",
  "**/ComposableSingletons\$*.*",
)

/**
 * This module's own compiled debug classes, scoped to its own namespace package with generated
 * code excluded — the input [configureAndroidJacocoReport] and
 * [configureAndroidJacocoCoverageVerification] both need and must agree on (a report and a
 * verification task disagreeing about what counts as "this module's code" would make the report a
 * developer reads and the number that actually gates the build two different things).
 *
 * Scoped to this module's own package, not merely "everything except a blacklist": AGP's class
 * transform output also bundles a packed jar of passthrough library classes (under a `jars`
 * subdirectory) — every transitive AndroidX dependency's own merged R class, observed directly
 * (androidx/core/R, androidx/fragment/R, dagger/hilt/android/R, ~140 classes) — and JaCoCo reads
 * `.class` entries straight out of a jar without ever consulting Gradle's `PatternFilterable`
 * filtering (confirmed empirically: an `exclude(generatedCodeExcludes)`-only PatternSet did not
 * stop them, appending an `.filter { it.isDirectory }` step ahead of `asFileTree` also did not —
 * in both cases the classes from that bundled jar still ended up in the report). Scoping to an
 * `include(...)` of the module's own
 * package sidesteps the question of exactly which mechanism jacoco uses to read a bundled jar's
 * entries: nothing this module did not write lives under its own namespace, in a jar or otherwise,
 * so the include alone is sufficient and does not depend on understanding jacoco's internal
 * jar-handling.
 *
 * Read lazily (inside a task's own configuration block, never at the top level of a function that
 * runs during `apply()`): the convention plugin that calls this configures
 * `commonExtension.namespace` from *this same* synchronous call chain, before the consuming
 * module's own `android { namespace = ... }` line in its build.gradle.kts has run — reading it
 * eagerly would see null/blank. A lazy `Provider` (returned here, `map`ped from a task provider)
 * only actually runs once the whole project has finished configuring, by which point the real
 * namespace is set.
 */
private fun Project.debugClassesFileTree(commonExtension: CommonExtension): Provider<FileTree> {
  // AGP does not require a module's compiled class *package* path to match its declared
  // `namespace` (a library's `namespace` only determines its generated `R`/`BuildConfig` package,
  // not where its own Kotlin/Java sources must live) — verified against `:app`, `:feature:setup`
  // and `:core:designsystem`, where namespace and package do match, but the *first*
  // `muplay.android.library` consumer with source code living outside its own namespace package
  // should check this include pattern still finds it, rather than assuming this comment's
  // confidence carries over untested.
  val ownPackagePath = requireNotNull(commonExtension.namespace) {
    "muplay.android.application/library requires `android.namespace` to be set before " +
      "jacocoTestReport/jacocoTestCoverageVerification can scope its own-code include pattern to it"
  }.replace('.', '/')
  // Hilt-generated classes still land inside this same namespace (Dagger's generated components,
  // `Hilt_<Application>`, ...) — the same "generated code excluded" requirement Task 7's coverage
  // floors depend on, so they are excluded on top of the namespace include.
  val includeOwnPackageOnly = PatternSet()
    .include("$ownPackagePath/**")
    .exclude(generatedCodeExcludes)

  // Not always `transformDebugClassesWithAsm`: Hilt's Gradle plugin rewrites a Hilt-annotated
  // module's classes via a bytecode transform (e.g. `MuPlayApplication`'s superclass, `Application`
  // -> its generated `Hilt_MuPlayApplication`) that only runs, and only registers this task, when
  // the Hilt Gradle plugin is applied. `:app` applies `muplay.android.hilt`; `:feature:setup` and
  // `:core:designsystem` do not (see `AndroidHiltConventionPlugin` — only `:app` uses it today), so
  // that task does not exist in their projects at all — confirmed empirically: configuring
  // `jacocoTestReport` against a hardcoded `tasks.named("transformDebugClassesWithAsm")` failed
  // `:feature:setup`'s configuration outright with "Task with name 'transformDebugClassesWithAsm'
  // not found in project ':feature:setup'". `tasks.names` (a name lookup, not `tasks.named(...)`)
  // reports registered-but-not-yet-created lazy task names without realizing them, so branching on
  // it here stays safe to evaluate eagerly. Where it is absent, `compileDebugKotlin`'s own output
  // directory is exactly what testDebugUnitTest's classpath loads for that module — there is no
  // post-compile bytecode transform standing between them the way Hilt's ASM step interposes for
  // `:app` — verified: `:feature:setup`'s own namespace package
  // (`build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/app/muplay/setup/`)
  // contains exactly its own compiled classes, not a merged/repackaged jar.
  val classesTaskName = if (tasks.names.contains("transformDebugClassesWithAsm")) {
    "transformDebugClassesWithAsm"
  } else {
    "compileDebugKotlin"
  }
  return tasks.named(classesTaskName).map { it.outputs.files.asFileTree.matching(includeOwnPackageOnly) }
}

/**
 * This module's merged JVM + instrumented execution data. Today (Task 7) only the first of the two
 * globs below ever matches anything — Task 8 is what starts producing instrumented
 * (`connectedDebugAndroidTest`) coverage under the build's `outputs/code_coverage` directory;
 * wiring the merge in now, rather than only adding it once Task 8 exists, is what let Task 7 ship
 * the 90% branch floor as a floor that already accounts for both execution sources instead of
 * needing a second, disruptive rewrite of this file later. A `fileTree` glob over a directory that
 * does not exist yet — as `outputs/code_coverage` does not, before Task 8 — simply contributes no
 * files; Gradle's own
 * JaCoCo report/verification tasks already tolerate execution-data entries that do not exist at
 * task-execution time (this project has always relied on that: a module with zero tests still runs
 * `jacocoTestReport` successfully today, over an empty execution-data set).
 */
private fun Project.mergedExecutionData(debugUnitTest: Provider<Test>) = listOf(
  debugUnitTest.map { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile },
  layout.buildDirectory.dir("outputs/code_coverage").map { dir ->
    dir.asFileTree.matching(PatternSet().include("**/*.ec"))
  },
)

/**
 * Registers a `jacocoTestReport` task for the `debug` unit tests of an Android module — the one
 * AGP variant this project's modules care about measuring. Deliberately not wired into
 * `test`/`check`/`build`: a report a developer never asked for should not be able to fail an
 * otherwise-green build; `jacocoTestCoverageVerification` (below) is the task that actually gates.
 */
internal fun Project.configureAndroidJacocoReport(commonExtension: CommonExtension) {
  // `debugClassesFileTree`/`tasks.named("testDebugUnitTest", ...)` are called from *inside* this
  // registration block, not hoisted above it: `tasks.register(...) { }`'s own trailing lambda only
  // actually runs once `jacocoTestReport` itself is realized, which is always after AGP has
  // registered its variant tasks and after this module's own `android { namespace = ... }` line has
  // run — calling either eagerly at this function's top level threw "Task with name
  // 'testDebugUnitTest' not found" (mirroring the `transformDebugClassesWithAsm` failure
  // `debugClassesFileTree`'s own doc describes) the one time this was tried.
  tasks.register("jacocoTestReport", JacocoReport::class.java) {
    group = "verification"
    description = "Generates a JaCoCo coverage report for the debug unit tests."

    val debugUnitTest = tasks.named("testDebugUnitTest", Test::class.java)
    dependsOn(debugUnitTest)

    reports {
      xml.required.set(true)
      html.required.set(true)
    }

    classDirectories.setFrom(debugClassesFileTree(commonExtension))
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
    executionData.setFrom(mergedExecutionData(debugUnitTest))
  }
}

/**
 * Registers a `jacocoTestCoverageVerification` task for the `debug` unit tests of an Android
 * module, mirroring [configureAndroidJacocoReport]'s class/source/execution-data wiring exactly —
 * AGP registers neither task for any variant, unlike the `java`/`kotlin.jvm` + `jacoco` plugin
 * combination JVM modules get both from automatically.
 *
 * Registers no `violationRules` here: this is *mechanism* only (which classes, which execution
 * data). The *policy* — which modules get a floor at all, and what number — lives in root
 * `build.gradle.kts`'s `branchCoverageFloors` map, applied generically to every
 * `JacocoCoverageVerification` task project-wide (JVM-auto-registered or this one) so it is
 * expressed once instead of once per module, and so a module simply absent from that map is
 * visibly and deliberately un-gated rather than gated by an empty/invented rule.
 */
internal fun Project.configureAndroidJacocoCoverageVerification(commonExtension: CommonExtension) {
  // See the comment on the equivalent line in `configureAndroidJacocoReport`: everything here must
  // stay inside this lazy registration block for the same reason.
  tasks.register("jacocoTestCoverageVerification", JacocoCoverageVerification::class.java) {
    group = "verification"
    description = "Fails the build if this module's branch coverage drops below its floor " +
      "-- see root build.gradle.kts's branchCoverageFloors."

    val debugUnitTest = tasks.named("testDebugUnitTest", Test::class.java)
    dependsOn(debugUnitTest)

    classDirectories.setFrom(debugClassesFileTree(commonExtension))
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
    executionData.setFrom(mergedExecutionData(debugUnitTest))
  }
}
