import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `muplay.android.application`: everything `muplay.android.library` sets up
 * (see [configureKotlinAndroid]) plus `targetSdk 36` — a real application module needs one,
 * a library module does not — and the release-manifest check below.
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

      configureReleaseManifestVerification()
    }
  }
}

/**
 * Registers `verifyReleaseManifest` and wires it into `check`: the release variant's merged
 * manifest must not contain `usesCleartextTraffic`.
 *
 * MuPlay talks to a public HTTPS Navidrome in production; the *debug* build talks to a plain-HTTP
 * container on `localhost:4533` (Tier 2's emulator journey — see `app/src/debug/AndroidManifest.xml`
 * and `.github/workflows/e2e.yml`), which is why the attribute exists in this repository at all.
 * "It is only in `src/debug/`" is a claim about source layout, and the thing that actually ships
 * is the *merged* manifest — which also absorbs every dependency's manifest. This task checks that
 * artifact, through AGP's own Variant API, so the claim is verified on every `check` rather than
 * once by hand.
 *
 * `onVariants` with a `withBuildType("release")` selector, not a hardcoded task name: the merged
 * manifest's producing task and output path are AGP internals that have changed name across
 * versions (`processReleaseManifest`, then `processReleaseMainManifest`), whereas
 * [SingleArtifact.MERGED_MANIFEST] is the public, stable handle for it — and taking it as a
 * `Provider` carries the task dependency automatically, so `verifyReleaseManifest` builds the
 * manifest it verifies instead of silently reading a stale one or none at all.
 */
private fun Project.configureReleaseManifestVerification() {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}Manifest"
    val verifyTask = tasks.register<VerifyMergedManifestTask>(taskName) {
      group = "verification"
      description = "Fails if the ${variant.name} variant's merged manifest enables cleartext HTTP."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      forbiddenAttributes.set(listOf("usesCleartextTraffic"))
    }
    tasks.named("check").configure { dependsOn(verifyTask) }
  }
}
