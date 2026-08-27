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
include(":feature:player")
// Plan 6 Task 10. The cast picker: the device list, the cast button and the volume slider.
// Depends on `:core:cast` and `:core:media` and on no other feature -- see this module's own
// build file, and `ConventionTest`'s severability reasoning for `integrations`: dropping casting
// stays `git rm -r core/cast feature/castpicker` plus these two include lines.
include(":feature:castpicker")

// Plan 7's own top-level source directory. Kept out of `core/` deliberately: `ConventionTest`'s
// `nothing outside integrations depends on an integration` makes "Plan 7 can be dropped" a checked
// fact rather than a promise, and that rule is written as a path prefix -- which only means
// anything while these modules live somewhere of their own.
include(":integrations:core")
// Plan 7 Task 4. Depends on `:integrations:core` and on nothing else in the build.
include(":integrations:lidarr")
// Plan 7 Task 8. The second service, and the module that turns "severable" from a claim into a
// demonstrated property: it depends on `:integrations:core` and on nothing else in the build --
// in particular not on `:integrations:lidarr`, which is the severability contract's third clause
// (neither service is reachable from the other's code path) stated as a build edge.
include(":integrations:bindery")
