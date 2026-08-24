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
 * manifest must not contain `usesCleartextTraffic` or `networkSecurityConfig` — the two attributes
 * that can permit cleartext HTTP in a shipped manifest. `usesCleartextTraffic="true"` does it
 * directly; `networkSecurityConfig="@xml/..."` does it indirectly, by pointing at a
 * `<network-security-config>` resource that can set `cleartextTrafficPermitted="true"` at the base
 * level or inside any `<domain-config>`. Either one reaching release defeats the same rule.
 *
 * MuPlay talks to a public HTTPS Navidrome in production; the *debug* build talks to a plain-HTTP
 * container on `localhost:4533` (Tier 2's emulator journey — see `app/src/debug/AndroidManifest.xml`
 * and `.github/workflows/e2e.yml`), which is why `usesCleartextTraffic` exists in this repository at
 * all. `networkSecurityConfig` has no legitimate user in this repo today — no manifest references
 * it — but Plan 6 (casting) adds an on-device HTTP proxy and LAN renderers that speak plain HTTP,
 * which is precisely the feature whose author reaches for `networkSecurityConfig` to scope
 * cleartext to LAN domains. The gate has to already be closed when that happens, not patched after.
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
 *
 * `networkSecurityConfig` is rejected outright — its mere presence fails the build — rather than by
 * reading the XML resource it references and permitting a config that never sets
 * `cleartextTrafficPermitted="true"`. Reading the XML would be more permissive, but
 * [VerifyMergedManifestTask] only has the merged *manifest* as input, and the manifest carries
 * `networkSecurityConfig="@xml/foo"` — a resource reference, not the referenced file's content — so
 * proving a config is safe would mean adding a second, separate resource-reading mechanism
 * alongside this one just to approve an attribute this gate can reject in one line today. That is
 * the more complex path for a security gate to take on for a feature nothing in this repo needs
 * yet, and it is also the more defeatable one, since it would need to keep up with every syntax the
 * config XML schema allows (`<base-config>`, `<domain-config>`, nested configs) rather than one
 * substring check. If a future change genuinely needs `networkSecurityConfig` in release, that has
 * to be a deliberate, reviewed decision made *here* — narrowing `forbiddenAttributes` or adding a
 * scoped exception — not a manifest edit that slips past this task unnoticed.
 */
private fun Project.configureReleaseManifestVerification() {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}Manifest"
    val verifyTask = tasks.register<VerifyMergedManifestTask>(taskName) {
      group = "verification"
      description = "Fails if the ${variant.name} variant's merged manifest enables cleartext HTTP."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      forbiddenAttributes.set(listOf("usesCleartextTraffic", "networkSecurityConfig"))
    }
    tasks.named("check").configure { dependsOn(verifyTask) }
  }
}
