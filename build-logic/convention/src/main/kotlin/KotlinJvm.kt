import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Settings for a pure Kotlin/JVM module (no Android framework dependency): JDK 21 toolchain,
 * Kotlin `jvmTarget` 21, JUnit 5, and JaCoCo. Used by modules like `:core:model` that are plain
 * data classes and have no reason to depend on `android.*`.
 */
internal fun Project.configureKotlinJvm() {
  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(21))
    }
  }

  extensions.configure<KotlinJvmProjectExtension> {
    jvmToolchain(21)
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_21)
    }
  }

  configureJUnit5()
  configureJacoco()
}
