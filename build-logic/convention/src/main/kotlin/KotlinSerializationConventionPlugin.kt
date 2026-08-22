import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `muplay.kotlin.serialization`: applies the kotlinx.serialization Kotlin compiler plugin
 * (`org.jetbrains.kotlin.plugin.serialization`) by bare id, resolved through build-logic's own
 * `implementation(libs.kotlin.gradlePlugin)` classpath — exactly how [JvmLibraryConventionPlugin]
 * applies `org.jetbrains.kotlin.jvm` itself — so it is implicitly pinned to this project's single
 * Kotlin version with nothing to restate here.
 *
 * A separate, opt-in convention plugin rather than folded into `muplay.jvm.library`
 * unconditionally: not every plain-JVM module needs `@Serializable` types (`:core:model` and
 * `:core:testing` do not, today; `:core:network`'s Subsonic response DTOs do). Applying the plugin
 * via a *versioned* catalogue alias (`alias(libs.plugins.kotlin.serialization)`) directly in a
 * module's own `plugins {}` block — the module-level option the plan also allows — was tried
 * first and works, but makes Gradle load the Kotlin Gradle plugin's classes twice: once through
 * build-logic's classpath (via [JvmLibraryConventionPlugin]), once through the module's own
 * `pluginManagement`-resolved classpath, from two different classloaders. Gradle warns loudly the
 * moment that happens ("The Kotlin Gradle plugin was loaded multiple times in different
 * subprojects, which is not supported and may break the build") even though the build still
 * succeeds. Applying by bare id through build-logic, the same way every other `muplay.*` plugin
 * already does, avoids the double load — and the warning — entirely; verified by removing this
 * plugin and reverting `:core:network`'s `build.gradle.kts` to the versioned-alias form, seeing
 * the warning reappear, then restoring this plugin and seeing it gone again.
 */
class KotlinSerializationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
  }
}
