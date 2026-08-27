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
// Plan 6 Task 12. The settings **slot**: a screen that renders whatever `SettingsSection`
// implementations the Hilt graph contains and names none of them. It depends on no other feature
// and on no `:core:` module, which is what makes `git rm -r core/cast feature/castpicker` a
// complete removal of casting -- see `SettingsSection`'s own documentation and `ConventionTest`'s
// `the settings slot never learns what is in it`.
include(":feature:settings")
// Plan 6 Task 12. Casting's own UI, and today that is exactly one thing: the renderer-direct
// switch, contributed into the slot above. The arrow runs this way and never back.
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
