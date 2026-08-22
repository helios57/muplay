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
  }
}
