import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * Settings shared by every Android module, application and library alike: JDK 21 toolchain,
 * `compileSdk 37`, `minSdk 26`, Kotlin `jvmTarget` 21, JUnit 5, and JaCoCo. Kept in one place so
 * the ten modules coming after this one cannot configure any of it differently.
 *
 * `CommonExtension` (AGP 9's non-generic DSL base shared by `ApplicationExtension` and
 * `LibraryExtension`) only exposes plain property getters for `defaultConfig`/`compileOptions` —
 * no `defaultConfig { }`-style lambda overload — so this is written as property assignment, not
 * as the nested-block syntax an ordinary `build.gradle.kts` would use.
 *
 * No explicit Kotlin `jvmTarget` configuration here: AGP 9's built-in Kotlin support (see
 * `AndroidLibraryConventionPlugin`) derives it from `compileOptions.targetCompatibility` below —
 * Android's own migration notes say so explicitly ("you don't need to set
 * `kotlin.compilerOptions.jvmTarget` because its value defaults to
 * `android.compileOptions.targetCompatibility`") — so setting `targetCompatibility` to 21 *is*
 * setting the Kotlin `jvmTarget` to 21.
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
  commonExtension.compileSdk = 37
  commonExtension.defaultConfig.minSdk = 26
  commonExtension.compileOptions.sourceCompatibility = JavaVersion.VERSION_21
  commonExtension.compileOptions.targetCompatibility = JavaVersion.VERSION_21

  configureJUnit5()
  configureJacoco()
  configureAndroidJacocoReport(commonExtension)
}
