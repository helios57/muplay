pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
  versionCatalogs {
    // build-logic is a separate Gradle build (included via `includeBuild("build-logic")` in the
    // root settings.gradle.kts); it does not automatically see the root project's `libs` catalog,
    // so it is recreated here from the same file. This is the only place besides the root
    // gradle/libs.versions.toml that names this file — nothing here pins its own versions.
    create("libs") {
      from(files("../gradle/libs.versions.toml"))
    }
  }
}

rootProject.name = "build-logic"

include(":convention")
