plugins {
  `kotlin-dsl`
}

group = "app.muplay.buildlogic"

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
  }
}

dependencies {
  // `implementation`, not `compileOnly`: these convention-plugin classes reference AGP/Kotlin/
  // Hilt DSL types directly (e.g. `extensions.configure<ApplicationExtension>`), and Gradle's
  // plugin decoration inspects every referenced type when the class loads — with `compileOnly`,
  // that class is absent from the classloader that loads AndroidApplicationConventionPlugin at
  // runtime and the build fails with "Could not generate a decorated class". Bundling them here
  // is also what lets `pluginManager.apply("com.android.application")` (a plain string, no
  // version) resolve at all from inside a convention-plugin class: it resolves via this jar's own
  // classpath, not via a consuming module's `plugins {}` block.
  implementation(libs.android.gradlePlugin)
  implementation(libs.kotlin.gradlePlugin)
  implementation(libs.kotlin.serialization.gradlePlugin)
  implementation(libs.compose.compiler.gradlePlugin)
  implementation(libs.ksp.gradlePlugin)
  implementation(libs.hilt.gradlePlugin)

  // `build-logic` had no test source set at all until Plan 3 Task 5's review round, and the gap it
  // left was not theoretical: `VerifyMergedManifestTask.verify()` is a security gate, and deleting
  // the half of it that checks for *missing* declarations left `ConventionTest` green (it reads the
  // plugin's declared list, not the task's behaviour), left both `verifyDebugManifest` and
  // `verifyReleaseManifest` green (they only fail when the task throws), and left CI green. That is
  // this project's recorded "decision verified at a different layer than applied" defect, aimed at
  // the gate that verifies the shipped manifest.
  //
  // Same stack as every other module -- JUnit 5 and AssertJ, no mock framework -- but declared by
  // hand rather than through `configureJUnit5()`: that function is a `Project` extension inside
  // *this* jar, and this build file is what produces the jar. `libs` is the same catalogue, loaded
  // from the same file (see build-logic/settings.gradle.kts).
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.assertj)
  testRuntimeOnly(libs.junit.platform.launcher)
  // `ProjectBuilder`, which instantiates a real `VerifyMergedManifestTask` so the test drives the
  // task Gradle would drive rather than a copy of its logic.
  testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

gradlePlugin {
  plugins {
    register("androidApplication") {
      id = "muplay.android.application"
      implementationClass = "AndroidApplicationConventionPlugin"
    }
    register("androidLibrary") {
      id = "muplay.android.library"
      implementationClass = "AndroidLibraryConventionPlugin"
    }
    register("androidCompose") {
      id = "muplay.android.compose"
      implementationClass = "AndroidComposeConventionPlugin"
    }
    register("jvmLibrary") {
      id = "muplay.jvm.library"
      implementationClass = "JvmLibraryConventionPlugin"
    }
    register("kotlinSerialization") {
      id = "muplay.kotlin.serialization"
      implementationClass = "KotlinSerializationConventionPlugin"
    }
    register("androidHilt") {
      id = "muplay.android.hilt"
      implementationClass = "AndroidHiltConventionPlugin"
    }
    register("androidRoom") {
      id = "muplay.android.room"
      implementationClass = "AndroidRoomConventionPlugin"
    }
  }
}
