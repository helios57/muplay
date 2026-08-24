import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.google.devtools.ksp.gradle.KspExtension
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider

/**
 * `muplay.android.room`: Room via **KSP**, never kapt -- KSP1 is removed upstream and kapt is dead
 * for new projects.
 *
 * The em-dash above is load-bearing, oddly enough. `ConventionTest`'s `no module or convention
 * plugin uses kapt` rule matches the banned tool's name immediately followed by one of `("{),`,
 * on the reasoning that prose explaining the ban never looks like a call site. This file's first
 * draft put a parenthesis straight after that name and duly failed the rule -- and so did the
 * first fix, which quoted the offending phrase while explaining it. The rule is right both times;
 * a comment simply has to keep punctuation away from the name. Applies the KSP plugin itself, so a module using this convention does not
 * also need `muplay.android.hilt`'s KSP wiring — applying both is harmless, since
 * `pluginManager.apply` is idempotent.
 *
 * Exports the schema to `<module>/schemas`. Without it the schema is invisible and every future
 * migration is unverifiable — and Room warns about it on every build, which is noise that
 * trains people to ignore warnings.
 *
 * Also registers `verifyReleaseNoDestructiveMigration` (release-variant only, see
 * [VerifyNoDestructiveMigrationTask]) and wires it into `check`. It fails on a
 * `fallbackToDestructiveMigration` call unless `<module>/DESTRUCTIVE_MIGRATION_EXEMPTION.md`
 * exists, in which case it passes but prints a loud warning naming that file on every run -- see
 * [VerifyNoDestructiveMigrationTask]'s own doc for why a silent pass was rejected on review.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("com.google.devtools.ksp")

      extensions.configure<KspExtension> {
        // A CommandLineArgumentProvider, not a plain `arg("room.schemaLocation", path)`: a bare
        // string is an untracked absolute path, which makes every KSP task cache-miss on a
        // different checkout directory and silently non-relocatable. Declaring it as an
        // @InputDirectory-bearing provider is what Now in Android does, for the same reason.
        arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
        // Kotlin codegen rather than Java. Room 2.8 can emit either; this project has no Java.
        arg("room.generateKotlin", "true")
      }

      dependencies {
        add("implementation", libs.findLibrary("room-runtime").get())
        add("ksp", libs.findLibrary("room-compiler").get())
        add("androidTestImplementation", libs.findLibrary("room-testing").get())
      }

      configureNoDestructiveMigrationVerification()
    }
  }

  /**
   * `getByType`, not `findByType`: every module this convention plugin applies to today is an
   * Android **library** (`:core:database`, via `muplay.android.library`, applied before this
   * plugin in that module's `plugins {}` block) -- there is no `com.android.application` consumer
   * of Room yet. If one ever exists, `LibraryAndroidComponentsExtension` genuinely will not be
   * the right extension type for it, and this project's own standing preference is a loud
   * Gradle-configuration failure over a check that quietly never registers.
   */
  private fun Project.configureNoDestructiveMigrationVerification() {
    val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
      val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}NoDestructiveMigration"
      val verifyTask = tasks.register<VerifyNoDestructiveMigrationTask>(taskName) {
        group = "verification"
        description = "Fails if a main Kotlin source in this module calls " +
          "fallbackToDestructiveMigration() with no DESTRUCTIVE_MIGRATION_EXEMPTION.md present " +
          "-- see VerifyNoDestructiveMigrationTask for why."
        kotlinSources.from(fileTree("src/main/kotlin") { include("**/*.kt") })
        // A `ConfigurableFileCollection`, not a hardcoded existence check here: whether this
        // resolves to zero or one file is exactly the question `verify()` answers, and Gradle
        // tracks the file's presence *and* content as a real input either way -- adding, removing
        // or editing the marker invalidates this task's up-to-date state.
        exemptionMarker.from(fileTree(projectDir) { include("DESTRUCTIVE_MIGRATION_EXEMPTION.md") })
        exemptionMarkerPath.set(File(projectDir, "DESTRUCTIVE_MIGRATION_EXEMPTION.md").path)
      }
      tasks.named("check").configure { dependsOn(verifyTask) }
    }
  }

  /**
   * Declares the schema directory as a tracked input so KSP tasks stay relocatable and
   * up-to-date checks stay honest.
   */
  class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File,
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDir.path}")
  }
}
