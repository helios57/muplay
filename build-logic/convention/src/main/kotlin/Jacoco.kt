import com.android.build.api.dsl.CommonExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.util.PatternSet
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoBase
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * JaCoCo, applied uniformly to every module now so it is configured once instead of drifting
 * across the ten modules still to come. This wires collection and reporting; it does **not** set
 * a coverage floor — `jacocoTestCoverageVerification`'s BRANCH/LINE minimums are policy, set
 * once for every module from root `build.gradle.kts`'s `coverageFloors` map (Task 7 -- extended
 * to a per-counter, per-module split during that same task's review), not duplicated here.
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

  val pinnedJacocoVersion = libs.findVersion("jacoco").get().requiredVersion

  // Setting `toolVersion` is necessary and *not sufficient*, which is the whole point of the two
  // blocks after it. AGP overwrites this value: `DependencyConfigurator.configureJacocoTransforms`
  // does, unconditionally and without consulting `android.testCoverage.jacocoVersion`,
  //
  //     project.extensions.findByType(JacocoPluginExtension::class.java)?.setToolVersion("0.8.14")
  //
  // -- read straight out of `com.android.tools.build:gradle:9.3.1`'s bytecode (`ldc_w "0.8.14"`
  // followed by `invokevirtual JacocoPluginExtension.setToolVersion`), not inferred. Measured
  // effect before this was fixed: after evaluation `toolVersion` was `0.8.14` in `:app`,
  // `:core:designsystem` and `:feature:setup` and `0.8.12` in the three JVM modules, while
  // `android.testCoverage.jacocoVersion` stayed `0.8.12` in all three Android ones.
  //
  // It is still set here because it is what `JacocoTaskExtension`/the agent and AGP's own
  // `getUnitTestJacocoVersion` read, and because leaving it wrong would be a second lie to
  // whoever reads it.
  extensions.configure<JacocoPluginExtension> {
    toolVersion = pinnedJacocoVersion
  }

  // What actually binds the version. `JacocoPlugin` gives `jacocoAnt`/`jacocoAgent` a
  // `defaultDependencies` block that reads `toolVersion` *at the moment the configuration's
  // dependency set is first observed* -- so which of the two values above got baked in depended on
  // whether that observation happened before or after AGP's overwrite, i.e. on the task graph.
  // Measured, before this fix, `org.jacoco.ant` resolved to:
  //
  //     ./gradlew jacocoJvmCoverageVerification        Android modules 0.8.14, JVM modules 0.8.12
  //     ./gradlew jacocoTestCoverageVerification       every module 0.8.12
  //     ./gradlew jacocoJvmCoverageVerification jacocoTestReport jacocoTestCoverageVerification
  //                                                    Android modules 0.8.14 for *every* jacoco
  //                                                    task, including the full gate
  //
  // A `defaultDependencies` block only contributes when the configuration has no declared
  // dependency, so declaring one here retires that whole ordering question: there is nothing left
  // for the timing to decide. The `eachDependency` rule is the belt to that braces -- it also
  // covers `org.jacoco.core`/`org.jacoco.report`, which arrive transitively and which nothing else
  // here names.
  //
  // `jacocoAgent` gets the same treatment even though it was measured as *not* affected: probing
  // both configurations before and after this fix, the agent resolved `org.jacoco.agent-0.8.12`
  // in every module either way, because the agent configuration is first observed while the
  // `jacoco` plugin wires `JacocoTaskExtension` onto each `Test` task -- during plugin apply,
  // before AGP's overwrite -- whereas `jacocoAnt` is not observed until a Jacoco task actually
  // runs, by which time the overwrite has happened. That timing difference is the whole bug, and
  // it is not something to leave depending on.
  dependencies {
    add("jacocoAnt", "org.jacoco:org.jacoco.ant:$pinnedJacocoVersion")
    add("jacocoAgent", "org.jacoco:org.jacoco.agent:$pinnedJacocoVersion")
  }
  listOf("jacocoAnt", "jacocoAgent").forEach { configurationName ->
    configurations.named(configurationName).configure {
      resolutionStrategy.eachDependency {
        if (requested.group == "org.jacoco") useVersion(pinnedJacocoVersion)
      }
    }
  }

  // And the pin is asserted from the *resolved artifact*, because the property lied: `toolVersion`
  // read back as the pinned version at the moment this function assigns it, while the jar that
  // actually did the analysis was a different one. A pin asserted from a configured property
  // rather than from what was resolved is not a pin.
  //
  // This is not the "warn, never fail" posture the coverage notices use, and the difference is
  // deliberate: an absent `.exec` is a legitimate state, whereas an analyzer that is not the one
  // every floor in `coverageFloors` was measured against makes every number in that table a
  // different measurement. 0.8.14's Kotlin-coroutine and Compose filters, for one measured
  // example, take `:feature:setup`'s `SetupViewModel$1` from 5 branches to 0 -- which turned that
  // class's floor into one that could pass at a minimum of 0.99.
  //
  // On `doFirst` rather than a notice task: unlike the coverage notices, this asserts a
  // precondition of work the task is about to do. A task Gradle skips analyses nothing, so there
  // is no analyzer for it to be wrong about, and nothing to report.
  tasks.withType(JacocoBase::class.java).configureEach {
    val taskPath = "$path"
    doFirst {
      // Every `org.jacoco.*` jar on the analysis classpath, not just `org.jacoco.ant`: `core` and
      // `report` are where the filters that changed the numbers actually live, and they arrive
      // transitively, so pinning only the one artifact this build names by hand would leave the
      // ones that matter to whatever the graph resolved.
      val wrong = jacocoClasspath.files
        .map { it.name }
        .filter { it.startsWith("org.jacoco.") }
        .filterNot { it.endsWith("-$pinnedJacocoVersion.jar") }
      if (wrong.isNotEmpty()) {
        throw GradleException(
          "$taskPath resolved $wrong, but every org.jacoco artifact must be " +
            "$pinnedJacocoVersion (gradle/libs.versions.toml). Every floor in `coverageFloors` " +
            "(root build.gradle.kts) was measured with JaCoCo $pinnedJacocoVersion, and a " +
            "different analyzer measures different numbers -- see `configureJacoco` in " +
            "build-logic for how AGP overwrites the pin and what that cost last time. Fix the " +
            "pin; do not re-measure the floors against whatever resolved.",
        )
      }
    }
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
 * This module's merged JVM + instrumented execution data: its own `testDebugUnitTest` `.exec`
 * file, plus **every** `.ec` file the build's instrumented (`connectedDebugAndroidTest`) run
 * produced, from any module.
 *
 * "From any module" is not defensive over-reach, it is the only correct answer. Instrumented
 * execution data is written by whichever module owns the `androidTest` source set — `:app`, and
 * only `:app`, today — into *that* module's `build/outputs/code_coverage/`. But the process it
 * records is the whole debug APK, which contains `:feature:setup`'s and `:core:designsystem`'s
 * classes too. A module that only globbed its own build directory would therefore see nothing of
 * a journey that exercised its own code: exactly the case for `SetupScreenKt`, composed for real
 * by `FirstRunJourneyTest`, whose execution data lands under `:app`. JaCoCo matches execution
 * data to classes by class id, not by which module's directory the data sat in, so handing every
 * module the same `.ec` files is safe — a module's report still only counts the classes its own
 * `classDirectories` names (see [debugClassesFileTree]).
 *
 * Read through `rootProject.allprojects` rather than by globbing paths under `rootDir`: it asks
 * Gradle where each project's build directory actually is instead of assuming `<project>/build`.
 * Safe to evaluate here because every caller invokes this from inside a `tasks.register(...) { }`
 * configuration block, which runs at task-realization time — long after `settings.gradle.kts` has
 * created every project.
 *
 * A `fileTree` glob over a directory that does not exist contributes no files, and Gradle's own
 * JaCoCo report/verification tasks already tolerate execution-data entries that are absent at
 * task-execution time (this project has always relied on that: a module with zero tests still
 * runs `jacocoTestReport` successfully, over an empty execution-data set). So a plain
 * `./gradlew jacocoTestReport` with no emulator run behind it still works — it just measures the
 * JVM half. That is also the line the coverage *gate* is split along: `jacocoTestCoverageVerification`
 * evaluates the whole floor table and therefore runs in the one CI job that has both halves
 * (`.github/workflows/e2e.yml`), while `jacocoJvmCoverageVerification` evaluates the subset that
 * needs no device — over JVM execution data only, never this merged set — and runs in
 * `.github/workflows/pr.yml` and in `check`. Both tasks are configured from root
 * `build.gradle.kts`; see `coverageFloors` there.
 */
private fun Project.mergedExecutionData(debugUnitTest: Provider<Test>): List<Provider<out Any>> {
  val instrumented = rootProject.allprojects.map { anyProject ->
    anyProject.layout.buildDirectory.dir("outputs/code_coverage").map { dir ->
      dir.asFileTree.matching(PatternSet().include("**/*.ec"))
    }
  }
  return listOf(
    debugUnitTest.map { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile },
  ) + instrumented
}

/** The AGP task that runs a module's instrumented tests on a device and pulls their `.ec` back. */
private const val CONNECTED_TEST_TASK_NAME = "connectedDebugAndroidTest"

/**
 * Every `connectedDebugAndroidTest` task in the build, as an *ordering* constraint only.
 *
 * `outputs/code_coverage` is that task's own declared `@OutputDirectory`, and
 * [mergedExecutionData] reads it. Gradle notices, and asking for both in one invocation
 * (`./gradlew :app:connectedDebugAndroidTest jacocoTestReport`) fails outright with "Task
 * ':app:jacocoTestReport' uses this output of task ':app:connectedDebugAndroidTest' without
 * declaring an explicit or implicit dependency" — reproduced directly, which is how this function
 * came to exist. Splitting the two into separate invocations dodges it, and
 * `.github/workflows/e2e.yml` does run them as separate steps, but a gate that only works when
 * nobody combines the commands is a trap, not a design.
 *
 * `mustRunAfter`, which Gradle's own message lists as a valid resolution, is the right one of the
 * three it offers: `dependsOn` would make every `jacocoTestReport` require a connected device, and
 * declaring the task as an *input* would do the same. `mustRunAfter` constrains order only when
 * both tasks are already in the graph, so a plain `./gradlew jacocoTestReport` still runs with no
 * emulator anywhere in sight — it simply measures the JVM half.
 *
 * One residual local footgun this does not close, deliberately: run *only*
 * `jacocoTestCoverageVerification` after deleting a test, and the previous emulator run's `.ec`
 * still credits the coverage that test used to provide. It is bounded — JaCoCo matches execution
 * data to classes by class id, so a *changed* class loses its old data and the floor fires — and
 * it cannot happen in CI, where `gradle/actions/setup-gradle` caches `~/.gradle` and never the
 * project build directory, so every run starts with no `.ec` at all. Closing it would mean making
 * the report depend on a device.
 *
 * Every project's, not just this one's, for the same reason [mergedExecutionData] reads every
 * project's `.ec`: the module that owns the `androidTest` source set is not the module whose code
 * the run covers. `tasks.names` is a name lookup that does not realize tasks, so projects without
 * an instrumented test task are skipped without being forced to create one.
 */
private fun Project.connectedTestTasks(): List<Any> =
  rootProject.allprojects
    .filter { it.tasks.names.contains(CONNECTED_TEST_TASK_NAME) }
    .map { it.tasks.named(CONNECTED_TEST_TASK_NAME) }

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
    mustRunAfter(connectedTestTasks())
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
 * `build.gradle.kts`'s `coverageFloors` map, applied generically to every
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
      "-- see root build.gradle.kts's coverageFloors."

    val debugUnitTest = tasks.named("testDebugUnitTest", Test::class.java)
    dependsOn(debugUnitTest)

    classDirectories.setFrom(debugClassesFileTree(commonExtension))
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
    executionData.setFrom(mergedExecutionData(debugUnitTest))
    mustRunAfter(connectedTestTasks())
  }
}
