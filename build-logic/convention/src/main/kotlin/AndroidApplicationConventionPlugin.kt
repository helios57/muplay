import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `muplay.android.application`: everything `muplay.android.library` sets up
 * (see [configureKotlinAndroid]) plus `targetSdk 36` — a real application module needs one,
 * a library module does not.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      // No `org.jetbrains.kotlin.android`: AGP 9's built-in Kotlin support compiles Kotlin
      // sources itself and rejects that plugin outright (see AndroidLibraryConventionPlugin).
      pluginManager.apply("com.android.application")

      extensions.configure<ApplicationExtension> {
        configureKotlinAndroid(this)
        defaultConfig.targetSdk = 36
      }
    }
  }
}
