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

  // Setting `toolVersion` is necessary and *not sufficient*, which is the whole point of
  // everything after it. Before this build declared the dependency outright, the analyzer that
  // actually ran was sometimes 0.8.14 while this property read back 0.8.12 -- see the measurements
  // below, and `task-8-report.md` for what that cost.
  //
  // It is still assigned because it is what `JacocoTaskExtension` and AGP's own
  // `getUnitTestJacocoVersion` read, and because leaving it wrong would be a second wrong thing
  // for the next reader to trip over. It is *not* what the enforcement rests on.
  extensions.configure<JacocoPluginExtension> {
    toolVersion = pinnedJacocoVersion
  }

  // ---------------------------------------------------------------------------------------------
  // What is verified, what is measured, and what is not understood. Read this before touching the
  // declaration, force and assertion blocks below, and especially before "simplifying" any of them.
  //
  // VERIFIED, by reading bytecode. **Four** different things write
  // `JacocoPluginExtension.toolVersion` in an Android module of this build -- the fourth is
  // Gradle's own `JacocoPlugin.apply`, which seeds it with `DEFAULT_JACOCO_VERSION` before any of
  // the three below run, and which matters because that default is the *same string* AGP
  // hardcodes (see the note under this list):
  //
  //   1. `configureJacoco` -- this function, the line above, with the catalogue's pin.
  //   2. AGP's `DependencyConfigurator.configureJacocoTransforms`
  //      (`com.android.tools.build:gradle:9.3.1`), unconditionally apart from a null check and
  //      without consulting `android.testCoverage.jacocoVersion`:
  //          findByType(JacocoPluginExtension::class.java)?.setToolVersion("0.8.14")
  //      (`ldc_w "0.8.14"` -> `invokevirtual JacocoPluginExtension.setToolVersion`).
  //   3. AGP's `AndroidUnitTest$CreationAction.configure`, at task realization, setting it back to
  //      `getUnitTestJacocoVersion(...)` -- i.e. to `android.testCoverage.jacocoVersion`, which
  //      `KotlinAndroid.kt` pins to the same catalogue value
  //      (`findByType(JacocoPluginExtension)` -> `setToolVersion(this.jacocoVersion)`).
  //
  // Also verified, and worth knowing because it defeats the obvious inference: Gradle 9.7.1's own
  // `JacocoPlugin.DEFAULT_JACOCO_VERSION` is *also* the string `"0.8.14"`. Seeing 0.8.14 therefore
  // does not by itself identify AGP as the writer.
  //
  // MEASURED, at this commit, with an init script probing every observation point:
  //
  //     projectsEvaluated   :app 0.8.14  :core:designsystem 0.8.14  :feature:setup 0.8.14
  //                         :core:model / :core:network / :core:testing  0.8.12
  //     taskGraph.whenReady all six modules 0.8.12
  //     every task doFirst  all six modules 0.8.12
  //
  // and, before the dependency below was declared, `org.jacoco.ant` actually resolved to:
  //
  //     ./gradlew jacocoJvmCoverageVerification        Android modules 0.8.14, JVM modules 0.8.12
  //     ./gradlew jacocoTestCoverageVerification       every module 0.8.12
  //     ./gradlew jacocoJvmCoverageVerification jacocoTestReport jacocoTestCoverageVerification
  //                                                    Android modules 0.8.14 for *every* jacoco
  //                                                    task, the full gate included
  //
  // while `jacocoAgent` resolved `org.jacoco.agent-0.8.12` in every module in every shape, both
  // before and after the fix.
  //
  // NOT UNDERSTOOD, and deliberately not guessed at. `JacocoPlugin` gives `jacocoAnt`/`jacocoAgent`
  // a `defaultDependencies` block that reads `toolVersion` when the configuration's dependency set
  // is first observed, so *some* interleaving of those three writes with those observations
  // produced the table above -- but no rule stated here predicts it. A previous version of this
  // comment claimed the agent escaped because it is "observed during plugin apply, before AGP's
  // overwrite"; that is false, because at plugin-apply time `toolVersion` is still Gradle's own
  // default of 0.8.14 and the agent would have baked *that*. A simple before/after-the-overwrite
  // rule is equally not predictive: a standalone `jacocoTestCoverageVerification` observes the
  // configuration well after AGP's write and still gets 0.8.12. Why `jacocoAgent` never drifted
  // is unexplained.
  //
  // WHY THAT IS ACCEPTABLE, and the reason not to tidy this away: none of the enforcement depends
  // on the ordering being understood. The declared dependency removes `defaultDependencies` from
  // the picture entirely (it contributes only when the configuration has none), the
  // `eachDependency` force covers `org.jacoco.core`/`org.jacoco.report` -- where the filters that
  // changed the numbers actually live, and which nothing here names -- and the two assertions
  // check the *resolved artifacts*, not any property. That is exactly why they hold: they make no
  // claim about when anything is observed. Anyone tempted to drop a block because "toolVersion is
  // 0.8.12 everywhere anyway" should note that it reads 0.8.12 at every probe point above, and was
  // still 0.8.14 in the jar that did the analysis.
  // ---------------------------------------------------------------------------------------------
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
        throw GradleException(wrongJacocoVersionMessage(taskPath, wrong, pinnedJacocoVersion))
      }
    }
  }

  // The other half of the pin, asserted the same way and for the same reason. `jacocoClasspath`
  // above is the `jacocoAnt` side -- the analyzer. The agent that instruments the JVM under test,
  // and therefore writes the `.exec` this build's floors are computed from, comes from the
  // separate `jacocoAgent` configuration, which until now was bound by the declaration and the
  // force but never checked against what resolved. "Happens to be correct, unasserted" is the
  // shape of every defect this task has found.
  //
  // Resolved through `incoming.artifacts.resolvedArtifacts` rather than by parsing file names:
  // the extracted agent jar is called `jacocoagent.jar` and carries no version at all, so a
  // name-based check would be checking the wrong thing. This reads the module version Gradle
  // actually resolved. A `Provider`, captured at configuration time and read in the task action,
  // so the configuration cache serializes it rather than a live `Configuration`.
  val resolvedJacocoAgent =
    configurations.named("jacocoAgent").flatMap { it.incoming.artifacts.resolvedArtifacts }
  tasks.withType(Test::class.java).configureEach {
    val taskPath = "$path"
    doFirst {
      val wrong = resolvedJacocoAgent.get()
        .map { it.id.componentIdentifier.displayName }
        .filter { it.startsWith("org.jacoco:") }
        .filterNot { it.endsWith(":$pinnedJacocoVersion") }
      if (wrong.isNotEmpty()) {
        throw GradleException(wrongJacocoVersionMessage(taskPath, wrong, pinnedJacocoVersion))
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

/**
 * The one message both JaCoCo pin assertions raise — the analyzer one on every `JacocoBase` task
 * and the agent one on every `Test` task. Shared so the two cannot drift into saying different
 * things about the same rule.
 */
private fun wrongJacocoVersionMessage(taskPath: String, wrong: List<String>, pinned: String): String =
  "$taskPath resolved $wrong, but every org.jacoco artifact must be $pinned " +
    "(gradle/libs.versions.toml). Every floor in `coverageFloors` (root build.gradle.kts) was " +
    "measured with JaCoCo $pinned, and a different JaCoCo measures different numbers -- see " +
    "`configureJacoco` in build-logic for the three things that write `toolVersion` here and what " +
    "that cost last time. Fix the pin; do not re-measure the floors against whatever resolved."

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
  // Room's KSP output. `MuPlayDatabase_Impl` and every `<Dao>_Impl` land inside this module's
  // own namespace package, so `debugClassesFileTree`'s namespace-scoped include picks them up
  // and no existing pattern removes them. They are generated code by exactly the same argument
  // the Hilt patterns above rest on: gating them would be gating Room's code generator, and
  // their branch count (nullable-column reads, cursor index lookups) would swamp this module's
  // own logic in every ratio.
  "**/*_Impl*.*",
  "**/ComposableSingletons\$*.*",
  // `**/*Module_*Factory.*` (above) excludes the generated Factory class itself -- Dagger names it
  // `DataModule_ProvideSubsonicSourceFactoryFactory`, which contains no `_Factory.` substring, so
  // `**/*_Factory.*` is not the pattern doing that work; `**/*Module_*Factory.*` is (confirmed by
  // removing each independently and rebuilding the report: dropping `*Module_*Factory.*` brings
  // every `DataModule_Provide*Factory` class back, dropping `*_Factory.*` changes nothing here).
  // That pattern is suffix-anchored on "Factory.", though, so it does not match the same class's
  // own nested `$InstanceHolder` -- the static holder Dagger emits for a no-arg, unscoped
  // `@Provides` method to memoize its one Factory instance. `:core:database`'s Task 4 added the
  // first such provider in this project (`DataModule.provideSubsonicSourceFactory`), and its
  // generated `DataModule_ProvideSubsonicSourceFactoryFactory$InstanceHolder` was the first time
  // this nested shape appeared in a report at all. Anchored the same way `*Module_*Factory.*`
  // already is (`Module_`...`Factory`), not a bare `*Factory$InstanceHolder.*`: the wider form
  // would also match an unrelated, author-written `class ArtworkFactory { object InstanceHolder }`
  // anywhere in the project, silently dropping it from every floor's denominator -- exactly the
  // "silent gate" shape this list is otherwise careful about. Same generated-code argument as
  // every pattern above it, just anchored to the one shape it is actually justified by.
  "**/*Module_*Factory\$InstanceHolder.*",
  // The same nested holder, one binding shape further out. Dagger emits an `$InstanceHolder`
  // for a *scoped, no-argument* `@Inject` constructor too, not only for a `@Provides` method:
  // `:core:media`'s `@Singleton class NavidromeLoadErrorHandlingPolicy @Inject constructor()`
  // generates `NavidromeLoadErrorHandlingPolicy_Factory$InstanceHolder`, which appeared in a
  // report for the first time when that module landed. `**/*_Factory.*` above does not reach it
  // for exactly the reason recorded for the `Module_` case: that pattern is anchored on the
  // literal `_Factory.`, and this class's name reads `_Factory$InstanceHolder.` -- there is no
  // `_Factory.` substring in it at all. Anchored on the same `_Factory` the outer class is,
  // which is no wider a claim than `**/*_Factory.*` already makes: an author-written class whose
  // name ends in `_Factory` is not idiomatic Kotlin, whereas the bare `*Factory$InstanceHolder`
  // form this deliberately avoids would also swallow a hand-written
  // `class ArtworkFactory { object InstanceHolder }`.
  "**/*_Factory\$InstanceHolder.*",
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
  // the Hilt Gradle plugin is applied. Three modules apply `muplay.android.hilt`: `:app`,
  // `:core:database` (already true at Task 8's own merge base, not something this task changed),
  // and `:feature:setup` as of Plan 2 Task 8 itself (its `SetupViewModel` moved onto Hilt
  // constructor injection). Of the four Android modules this function (`configureAndroidJacocoReport`)
  // runs for, `:core:designsystem` is the only one that does not. This comment used to name
  // `:feature:setup` as *the* without-Hilt example, and even after
  // a first correction (review round 1) still listed only two of the three Hilt-applying modules
  // — both checked on review and found incomplete, corrected here. Where the plugin is absent,
  // that task does not exist in the project at all — confirmed empirically at the time against
  // `:feature:setup`, when it was still a Hilt-less module: configuring `jacocoTestReport` against
  // a hardcoded `tasks.named("transformDebugClassesWithAsm")` failed its configuration outright
  // with "Task with name 'transformDebugClassesWithAsm' not found in project ':feature:setup'".
  // That mechanism has not changed, only which module is named as the still-Hilt-less example
  // (`:core:designsystem` today) — this correction updates *which modules are named*, not a
  // re-run of the experiment, so it is not claimed as a fresh empirical result. `tasks.names` (a
  // name lookup, not `tasks.named(...)`) reports registered-but-not-yet-created lazy task names
  // without realizing them, so branching on it here stays safe to evaluate eagerly. Where the
  // plugin is absent, `compileDebugKotlin`'s own output directory is exactly what
  // testDebugUnitTest's classpath loads for that module — there is no post-compile bytecode
  // transform standing between them the way Hilt's ASM step interposes for `:app`,
  // `:core:database` and `:feature:setup`. This branch is why those three modules' coverage
  // floors measure Hilt-transformed classes (their own compiled classes, post-ASM) rather than
  // the raw `compileDebugKotlin` output a Hilt-less module like `:core:designsystem` measures — a
  // real difference in which bytes JaCoCo instruments, not just an implementation detail.
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
