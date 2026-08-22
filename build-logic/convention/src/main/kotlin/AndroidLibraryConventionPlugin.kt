import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `muplay.android.library`: an Android library module with the JDK 21 toolchain, `compileSdk 37`,
 * `minSdk 26`, Kotlin `jvmTarget` 21, JUnit 5, and JaCoCo — see [configureKotlinAndroid]. No
 * `targetSdk`: that is an application-only concept (see `AndroidApplicationConventionPlugin`).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      // No `org.jetbrains.kotlin.android`: AGP 9's built-in Kotlin support compiles Kotlin
      // sources itself and rejects that plugin outright ("no longer required... not compatible
      // with the new DSL" — see https://kotl.in/gradle/agp-built-in-kotlin).
      pluginManager.apply("com.android.library")

      extensions.configure<LibraryExtension> {
        configureKotlinAndroid(this)
      }
    }
  }
}
