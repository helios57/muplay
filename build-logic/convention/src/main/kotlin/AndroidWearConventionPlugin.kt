import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * `muplay.android.wear`: an application module that runs on a watch.
 *
 * Everything `muplay.android.application` sets up -- `verify<Variant>Manifest`, `releaseCheck` and
 * its gates, `verifyReleaseVersion`, R8, signing, the bundle layout -- plus the single thing that
 * genuinely differs: **[WEAR_MIN_SDK]**. Wear OS 3 is API 30, there is no earlier Wear release
 * Compose for Wear OS supports (Wear OS 2 is a different app model entirely), and the project's
 * `minSdk 26` (spec section 2, set once in [configureKotlinAndroid]) therefore cannot apply here.
 *
 * This is the only module in the build that moves that floor, and it moves it **in build-logic**
 * rather than in a module's own `android { }` block, which `ConventionTest`'s
 * `no module configures android or kotlin blocks directly` forbids by construction -- its
 * allow-list is `namespace`, `applicationId`, `versionCode`, `versionName` and nothing else.
 *
 * ### Why the minSdk is also asserted against the merged manifest
 *
 * `defaultConfig.minSdk = 30` below is an assignment, and an assignment that stopped taking effect
 * -- because a later plugin overwrote it, because the ordering below was reversed, because someone
 * deleted the line -- fails **nothing**. The module would go on compiling, installing on the phone
 * emulator and passing every test in this repository while shipping a watch APK that claims to run
 * on API 26 devices Compose for Wear OS cannot support.
 *
 * That is this repository's recorded "a decision verified at a different layer than it is applied"
 * defect, so the claim is checked where it becomes real: AGP's own merged manifest, through the
 * `verify<Variant>Manifest` task the application convention plugin already registers for every
 * variant and already wires into `check`. One added required declaration, derived from the same
 * constant the assignment uses, so the two cannot disagree.
 *
 * Measured, both directions, on this module's debug variant:
 *
 *   * with `defaultConfig.minSdk = 30`, `:wear:verifyDebugManifest` is UP-TO-DATE/SUCCESS;
 *   * with the assignment removed (i.e. inheriting the project-wide 26),
 *     `:wear:verifyDebugManifest` FAILS with
 *     `debug's merged manifest is missing required declarations: android:minSdkVersion="30"`.
 *
 * `tasks.withType(...).configureEach`, not `tasks.named("verifyDebugManifest")`: those task names
 * are built from the variant name inside the application plugin, and a lazy `withType` reaches
 * every variant that exists now or is added later without this file knowing their names.
 *
 * ### What this plugin deliberately does not do
 *
 * `muplayApplication.androidAuto` is left at its `false` convention. A watch app declaring itself
 * an Android Auto media app would be a wrong claim in a shipped manifest, and it is why
 * `verifyAutomotiveDescriptor` reports SKIPPED for this module rather than checking a descriptor a
 * watch has no business shipping.
 */
class AndroidWearConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("muplay.android.application")

      extensions.configure<ApplicationExtension> {
        // After the application plugin, so this wins over `configureKotlinAndroid`'s 26.
        defaultConfig.minSdk = WEAR_MIN_SDK
      }

      tasks.withType<VerifyMergedManifestTask>().configureEach {
        requiredDeclarations.add("""android:minSdkVersion="$WEAR_MIN_SDK"""")
      }
    }
  }
}

/**
 * Wear OS 3, and the one place this number is written.
 *
 * Both the `defaultConfig.minSdk` assignment and the merged-manifest declaration that proves it
 * took effect are derived from it, so "the gate agrees with the setting" is structural rather than
 * a thing anyone has to keep in sync.
 */
internal const val WEAR_MIN_SDK = 30
