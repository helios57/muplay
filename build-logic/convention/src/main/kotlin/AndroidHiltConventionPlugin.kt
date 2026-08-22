import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `muplay.android.hilt`: Hilt via KSP, never kapt — KSP1 has been removed upstream and kapt is
 * dead for new projects. Applies the KSP plugin itself, so a module using this convention does
 * not also need `muplay.jvm.library`'s or `muplay.android.library`'s KSP wiring (they have none;
 * KSP is opt-in per capability, not part of the base Kotlin conventions).
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      with(pluginManager) {
        apply("com.google.devtools.ksp")
        apply("com.google.dagger.hilt.android")
      }

      dependencies {
        add("implementation", libs.findLibrary("hilt-android").get())
        add("ksp", libs.findLibrary("hilt-compiler").get())
      }
    }
  }
}
