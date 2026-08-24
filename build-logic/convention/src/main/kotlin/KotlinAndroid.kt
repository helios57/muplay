import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/**
 * Settings shared by every Android module, application and library alike: JDK 21 toolchain,
 * `compileSdk 37`, `minSdk 26`, Kotlin `jvmTarget` 21, JUnit 5, JaCoCo, and the instrumentation
 * runner/coverage wiring Tier 2's emulator journey needs. Kept in one place so the ten modules
 * coming after this one cannot configure any of it differently.
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

  // Here, not in `app/build.gradle.kts`: `ConventionTest`'s "no module configures android or
  // kotlin blocks directly" allow-lists exactly four properties inside a module's own
  // `android { }` block (namespace, applicationId, versionCode, versionName), so a
  // `testInstrumentationRunner` line there fails that test by construction. It also belongs here
  // on its own merits -- every Android module that ever grows an `androidTest` source set needs
  // the same runner, and only one runner can be right for a project with no mock framework and
  // one AndroidX test stack.
  commonExtension.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

  // Instrumented (on-device) coverage for the debug build type -- what makes
  // `Jacoco.kt`'s `mergedExecutionData` glob of `outputs/code_coverage` match anything at all.
  // AGP registers *no* JaCoCo Gradle report task from this flag. What it does register, observed
  // directly in this project's own build output, is `jacocoDebug` (offline JaCoCo instrumentation
  // of the variant's classes before packaging) and `generateDebugJacocoPropertiesFile`; the
  // on-device run then writes `/data/data/app.muplay/coverage.ec`, which
  // `connectedDebugAndroidTest` pulls off with `run-as ... cat` into
  // `<app module>/build/outputs/code_coverage/debugAndroidTest/connected/<device>/coverage.ec`.
  //
  // `configureEach` with a name check, not `getByName("debug")`: the latter realizes the build
  // type immediately, and this function runs from inside `extensions.configure<...>` during
  // plugin `apply()` -- `configureEach` applies to build types created before *or* after this
  // line, which is the same reason `Jacoco.kt` uses `tasks.withType(...).configureEach`.
  //
  // Deliberately not `enableUnitTestCoverage`: JVM unit-test execution data already comes from
  // the `jacoco` plugin's own agent on every `Test` task (see `configureJacoco`), and turning
  // this on as well would instrument the same classes twice.
  commonExtension.buildTypes.configureEach {
    if (name == "debug") {
      enableAndroidTestCoverage = true
    }
  }
  // Pins AGP's on-device JaCoCo to the same version `configureJacoco` gives the Gradle-side
  // report/verification tasks -- one catalogue entry, both halves. They are otherwise *not* the
  // same: AGP 9.3.1's own default is 0.8.14 (`JacocoOptions.DEFAULT_VERSION`, read out of
  // `com.android.tools.build:gradle:9.3.1`), while `libs.versions.toml` pins 0.8.12.
  //
  // This line pins only AGP's *own* use of JaCoCo. It does not stop AGP overwriting the Gradle
  // `jacoco` plugin's `toolVersion` -- `DependencyConfigurator.configureJacocoTransforms` does
  // that unconditionally, with a hardcoded 0.8.14, without reading the property assigned here.
  // Binding that half is `configureJacoco`'s job; see its own comments for the measured damage,
  // and for the two further facts that make this assignment less isolated than it looks: AGP's
  // `AndroidUnitTest$CreationAction.configure` reads *this* value back into the Gradle plugin's
  // `toolVersion` at task realization, and Gradle's own `JacocoPlugin.DEFAULT_JACOCO_VERSION` is
  // the same string AGP hardcodes, so observing "0.8.14" never identifies which of them wrote it.
  //
  // Nothing is broken by that particular pair today -- `ExecutionDataWriter.FORMAT_VERSION` is
  // 0x1007 in both 0.8.12 and 0.8.14, checked in the bytecode of both jars -- but the mismatch is
  // exactly the kind that stops being harmless silently: `ExecutionDataReader.read` compares the
  // file's version word against its own `FORMAT_VERSION` and throws
  // `IncompatibleExecDataVersionException` when they differ (also read out of the bytecode, not
  // inferred). Deriving both from one number means a future JaCoCo bump cannot leave the writer
  // and the reader on different formats without anyone choosing it.
  commonExtension.testCoverage.jacocoVersion = libs.findVersion("jacoco").get().requiredVersion

  configureJUnit5()
  excludeByteBuddyFromInstrumentedTests()
  configureJacoco()
  configureAndroidJacocoReport(commonExtension)
  configureAndroidJacocoCoverageVerification(commonExtension)
}
