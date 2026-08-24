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
import org.gradle.process.CommandLineArgumentProvider

/**
 * `muplay.android.room`: Room via **KSP**, never kapt (KSP1 is removed upstream and kapt is dead
 * for new projects). Applies the KSP plugin itself, so a module using this convention does not
 * also need `muplay.android.hilt`'s KSP wiring — applying both is harmless, since
 * `pluginManager.apply` is idempotent.
 *
 * Exports the schema to `<module>/schemas`. Without it the schema is invisible and every future
 * migration is unverifiable — and Room warns about it on every build, which is noise that
 * trains people to ignore warnings.
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
