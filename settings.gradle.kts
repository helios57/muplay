pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "MuPlay"

include(":app")
include(":core:model")
include(":core:network")
include(":core:testing")
include(":core:database")
include(":core:media")
include(":core:designsystem")
include(":core:cast")
include(":feature:setup")
include(":feature:library")

// Plan 7's own top-level source directory. Kept out of `core/` deliberately: `ConventionTest`'s
// `nothing outside integrations depends on an integration` makes "Plan 7 can be dropped" a checked
// fact rather than a promise, and that rule is written as a path prefix -- which only means
// anything while these modules live somewhere of their own.
include(":integrations:core")
