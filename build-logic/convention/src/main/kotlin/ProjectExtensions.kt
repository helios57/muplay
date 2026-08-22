import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/**
 * The root `libs` version catalogue, as seen from inside a precompiled convention-plugin class.
 * (Kotlin DSL only generates the typed `libs.foo` accessors for build scripts themselves, not for
 * `Plugin<Project>` implementations, so plugin code looks names up by string through this.)
 */
internal val Project.libs: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
