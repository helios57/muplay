import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * JaCoCo, applied uniformly to every module now so it is configured once instead of drifting
 * across the ten modules still to come. This wires collection and reporting; it does **not** set
 * a coverage floor — `jacocoTestCoverageVerification` and the branch-coverage minimums are Task 7.
 *
 * Applying the `jacoco` plugin alone already instruments every `Test` task project-wide (Android
 * unit test tasks included, since they are `Test` tasks too), so execution data is captured
 * regardless of module kind. The `java`/`org.jetbrains.kotlin.jvm` plugin combination additionally
 * registers a `jacocoTestReport` task automatically; this only needs to turn its reports on.
 * AGP registers no such task for any variant, so [configureAndroidJacocoReport] adds one
 * explicitly for Android modules.
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

/**
 * Registers a `jacocoTestReport` task for the `debug` unit tests of an Android module — the one
 * AGP variant this project's ten modules care about measuring. Deliberately not wired into
 * `test`/`check`/`build`: nothing here enforces a floor yet (Task 7), so a report a developer
 * never asked for should not be able to fail an otherwise-green build.
 *
 * Class directories are read from a real task's own declared outputs (`Task.outputs.files`), not
 * a hardcoded path — see the comments on `debugClasses`/`includeOwnPackageOnly` below for why, in
 * detail, and for the two wrong turns (a hardcoded `tmp/kotlin-classes/debug`, and later
 * `compileDebugKotlin`'s own output) that a real, non-zero coverage number on a real test is what
 * actually surfaced.
 */
internal fun Project.configureAndroidJacocoReport(commonExtension: CommonExtension) {
  val generatedCodeExcludes = listOf(
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

  // `tasks.named(...)` requires the target task to already be *registered* (even though its
  // configuration stays lazy) — and AGP does not register its variant tasks (compileDebugKotlin,
  // testDebugUnitTest, ...) until later in the configuration phase, well after this convention
  // plugin's `apply()` returns. Looking them up here, at the top level of this function, threw
  // "Task with name 'compileDebugKotlin' not found" every time. Deferring every `tasks.named(...)`
  // call to inside the `tasks.register("jacocoTestReport") { }` configuration block below fixes
  // it: that block only actually runs when `jacocoTestReport` itself is realized (i.e. someone
  // asks Gradle to run it), which is always after the whole project has finished configuring and
  // every AGP task genuinely exists.
  tasks.register("jacocoTestReport", JacocoReport::class.java) {
    group = "verification"
    description = "Generates a JaCoCo coverage report for the debug unit tests."

    // Scoped to this module's own package, not merely "everything except a blacklist": this
    // task's outputs also bundle a packed `jars/*.jar` of passthrough library classes — every
    // transitive AndroidX dependency's own merged R class, observed directly (androidx/core/R,
    // androidx/fragment/R, dagger/hilt/android/R, ~140 classes) — and JaCoCo reads `.class`
    // entries straight out of a jar without ever consulting Gradle's `PatternFilterable`
    // filtering (confirmed empirically: an `exclude(generatedCodeExcludes)`-only PatternSet did
    // not stop them, appending an `.filter { it.isDirectory }` step ahead of `asFileTree` also did
    // not — in both cases the classes from `jars/0.jar` still ended up in the report). Scoping to
    // an `include(...)` of the module's own package sidesteps the question of exactly which
    // mechanism jacoco uses to read a bundled jar's entries: nothing this module did not write
    // lives under its own namespace, in a jar or otherwise, so the include alone is sufficient and
    // does not depend on understanding jacoco's internal jar-handling.
    //
    // Read lazily (inside this registration block, not at the top of the function): the
    // convention plugin that calls this configures `commonExtension.namespace` from *this same*
    // synchronous call chain, before the consuming module's own `android { namespace = ... }` line
    // in its build.gradle.kts has run — reading it eagerly would see null/blank. Task
    // registration's own configuration block, by contrast, only actually runs once the whole
    // project has finished configuring, by which point the real namespace is set. This is what
    // makes the include pattern follow whichever module applies this convention (`:app` today,
    // future application modules — e.g. the roadmap's Wear OS target — tomorrow) instead of
    // silently mis-scoping to a namespace this convention plugin does not own.
    val ownPackagePath = requireNotNull(commonExtension.namespace) {
      "muplay.android.application/library requires `android.namespace` to be set before " +
        "jacocoTestReport can scope its own-code include pattern to it"
    }.replace('.', '/')
    // Hilt-generated classes still land inside this same namespace (Dagger's generated
    // components, `Hilt_<Application>`, ...) — the same "generated code excluded" requirement
    // Task 7's coverage floors depend on, so they are excluded on top of the namespace include.
    val includeOwnPackageOnly = org.gradle.api.tasks.util.PatternSet()
      .include("$ownPackagePath/**")
      .exclude(generatedCodeExcludes)
    // Not `compileDebugKotlin`'s own output: Hilt's Gradle plugin rewrites `MuPlayApplication`'s
    // superclass (`Application` -> its generated `Hilt_MuPlayApplication`) via a bytecode
    // transform that runs *after* compilation, in `transformDebugClassesWithAsm` — verified by
    // running this task against the raw `compileDebugKotlin` output first: JaCoCo logged
    // "Classes in bundle 'app' do not match with execution data... Execution data for class
    // app/muplay/MuPlayApplication does not match" and silently dropped that class from the
    // report. `transformDebugClassesWithAsm`'s own output is what testDebugUnitTest's classpath
    // actually loads (via bundleDebugClassesToRuntimeJar), so it is what the recorded execution
    // data actually matches.
    val debugClasses = tasks.named("transformDebugClassesWithAsm")
      .map { it.outputs.files.asFileTree.matching(includeOwnPackageOnly) }
    val debugUnitTest = tasks.named("testDebugUnitTest", Test::class.java)

    dependsOn(debugUnitTest)

    reports {
      xml.required.set(true)
      html.required.set(true)
    }

    classDirectories.setFrom(debugClasses)
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
    // The jacoco plugin's own default convention for a Test task's execution-data file (read
    // straight from the extension it attaches to `testDebugUnitTest`, rather than re-deriving or
    // guessing the path a second time here).
    executionData.setFrom(
      debugUnitTest.map { it.extensions.getByType(JacocoTaskExtension::class.java).destinationFile },
    )
  }
}
