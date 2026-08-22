import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `muplay.android.compose`: turns on Compose (`buildFeatures.compose`) and pins every Compose
 * artifact this module resolves to the catalogue's BOM — applied here, once, rather than in every
 * module that touches Compose. Must be applied *after* `muplay.android.application` or
 * `muplay.android.library` in a module's `plugins {}` block, since it configures the `android`
 * extension those create; applying it alone fails loudly with "no extension of type
 * CommonExtension found", which is the point — there is nothing sensible to configure otherwise.
 *
 * Individual Compose artifacts (`compose-ui`, `compose-material3`, ...) are *not* added here:
 * only the module that actually uses them declares that dependency, in its own
 * `dependencies {}` block, version-less because the BOM platform constraint below resolves it.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

      extensions.configure<CommonExtension> {
        buildFeatures.compose = true
      }

      val bom = libs.findLibrary("compose-bom").get()
      dependencies {
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
      }
    }
  }
}
