import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `muplay.jvm.library`: a plain Kotlin/JVM module — no Android framework dependency, no manifest,
 * no `compileSdk`/`minSdk`. For modules like `:core:model` that are only data classes and sealed
 * interfaces, and `:core:network`/`:core:testing`, whose Retrofit/OkHttp/kotlinx.serialization and
 * OpenAPI-validator code never touch `android.*` either. See [configureKotlinJvm].
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.jetbrains.kotlin.jvm")
      configureKotlinJvm()
    }
  }
}
