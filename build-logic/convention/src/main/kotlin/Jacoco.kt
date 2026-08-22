import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
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
 * Generated-code packages (Hilt's, primarily) are excluded up front — the same "generated code
 * excluded" requirement Task 7's floors depend on — so the module's own JaCoCo numbers were never
 * inflated or deflated by code nobody wrote.
 */
internal fun Project.configureAndroidJacocoReport() {
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

  tasks.register("jacocoTestReport", JacocoReport::class.java) {
    group = "verification"
    description = "Generates a JaCoCo coverage report for the debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
      xml.required.set(true)
      html.required.set(true)
    }

    classDirectories.setFrom(
      files(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
          exclude(generatedCodeExcludes)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
          exclude(generatedCodeExcludes)
        },
      ),
    )
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin", "$projectDir/src/main/java"))
    executionData.setFrom(
      fileTree(layout.buildDirectory) {
        include(
          "outputs/unit_test_code_coverage/debugUnitTest/*.exec",
          "jacoco/testDebugUnitTest.exec",
        )
      },
    )
  }
}
