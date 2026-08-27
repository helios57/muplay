import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `muplay.android.application`: everything `muplay.android.library` sets up
 * (see [configureKotlinAndroid]) plus `targetSdk 36` — a real application module needs one —
 * the release-manifest check below, and everything that makes the release variant shippable
 * rather than merely buildable (see [configureReleaseBuild]: R8, resource shrinking, signing,
 * the bundle's split layout and the version gate).
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
        configureReleaseBuild(this)
        // After `configureReleaseBuild`, and outside it: the gates below read the *artifact*
        // `configureReleaseBuild` causes to be produced, through the Variant API rather than
        // through the DSL. See ReleaseGates.kt.
        configureReleaseGates(this)
      }

      // Per-module policy the plugin cannot decide for itself, created before the two
      // verifications below read it. `convention`, not `set`: a module that says nothing is not an
      // Android Auto app, and a module that says `androidAuto = true` in its own build script
      // overrides this without ordering mattering -- both gates take the value as a `Provider`.
      val muplay = extensions.create<MuPlayApplicationExtension>("muplayApplication")
      muplay.androidAuto.convention(false)

      configureMergedManifestVerification(muplay)
      configureAutomotiveDescriptorVerification(muplay)
    }
  }
}

/**
 * Registers `verify<Variant>Manifest` for **every** variant and wires each into `check`.
 *
 * Two halves, split across the variants for opposite reasons. The forbidden half is
 * release-only: the release variant's merged
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
 *
 * The **required** half runs for every variant, because a missing permission is wrong in both,
 * and it exists because Spec section 7's permission list has no other enforcement anywhere.
 * Missing `FOREGROUND_SERVICE_MEDIA_PLAYBACK` does not fail a build, does not fail an install,
 * and does not fail a foreground test — it throws `SecurityException` from `startForeground`
 * the first time the app is backgrounded with audio playing, which is precisely the case a quick
 * manual test does not cover. The same task that already proves an attribute is *absent* now
 * proves these are *present*, on the same evidence: AGP's own merged manifest.
 *
 * `onVariants` with no selector, not a `withBuildType("release")` one: the release-only
 * expectation moved into `forbiddenAttributes`'s own value rather than staying in the selector,
 * so the debug variant gets a `verifyDebugManifest` of its own. The release task's name is
 * unchanged, so `.github/workflows/pr.yml`'s "Release manifest" step still works — it now
 * names both.
 */
private fun Project.configureMergedManifestVerification(muplay: MuPlayApplicationExtension) {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
  androidComponents.onVariants { variant ->
    val taskName = "verify${variant.name.replaceFirstChar(Char::titlecase)}Manifest"
    val verifyTask = tasks.register<VerifyMergedManifestTask>(taskName) {
      group = "verification"
      description = "Checks the ${variant.name} variant's merged manifest."
      mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
      // Debug legitimately carries `usesCleartextTraffic` -- it talks to a plain-HTTP container on
      // localhost:4533 (Tier 2's journey) -- and release must never carry it. Same task, different
      // expectation per variant, which is why the forbidden half is variant-dependent and the
      // required half below is not.
      forbiddenAttributes.set(
        if (variant.buildType == "release") {
          listOf("usesCleartextTraffic", "networkSecurityConfig")
        } else {
          emptyList()
        },
      )
      // Two lists, because they are true of different modules. `BASE_DECLARATIONS` is every
      // application module's floor; `AUTOMOTIVE_DECLARATIONS` is added only for one that says it
      // ships to Android Auto. Both are named constants below rather than inline `listOf(...)`
      // calls so that `ConventionTest` can read each one on its own and see which entries a future
      // edit dropped.
      requiredDeclarations.set(BASE_DECLARATIONS)
      // A `Provider`, not a read: the module's build script sets `androidAuto` *after* this plugin
      // has been applied, so reading the `Property` here would capture its `false` convention
      // forever and this whole gate would be dead with every task still green.
      requiredDeclarations.addAll(
        muplay.androidAuto.map { if (it) AUTOMOTIVE_DECLARATIONS else emptyList() },
      )
    }
    tasks.named("check").configure { dependsOn(verifyTask) }
  }
}

/**
 * Registers `verifyAutomotiveDescriptor` and wires it into `check`.
 *
 * Separate from the manifest task rather than a fourth property on it, because it reads a different
 * artifact for a reason that is structural: see [VerifyAutomotiveDescriptorTask]'s own header. A
 * merged manifest carries `android:resource="@xml/automotive_app_desc"` and never the resource, so
 * an empty descriptor passes every manifest check there is.
 *
 * `onlyIf`, not "register only when the flag is true": the task exists in every application module
 * so that `./gradlew :wear:verifyAutomotiveDescriptor` is a task that resolves and reports SKIPPED,
 * rather than a task name that does not exist and therefore cannot be observed to have not run.
 * The `onlyIf` reads the `Property` at execution time, which is after the module's build script has
 * configured it.
 */
private fun Project.configureAutomotiveDescriptorVerification(muplay: MuPlayApplicationExtension) {
  val verifyTask = tasks.register<VerifyAutomotiveDescriptorTask>("verifyAutomotiveDescriptor") {
    group = "verification"
    description = "Checks res/xml/automotive_app_desc.xml declares this app as an Auto media app."
    onlyIf { muplay.androidAuto.get() }
    descriptor.set(layout.projectDirectory.file("src/main/res/xml/automotive_app_desc.xml"))
    requiredUses.set(listOf("media"))
  }
  tasks.named("check").configure { dependsOn(verifyTask) }
}

/**
 * Spec section 7's permission list, plus the service and the one action that would otherwise fail
 * only in the wild.
 *
 * Every entry carries its own `android:name="..."` wrapper rather than being a bare name, and that
 * is load-bearing rather than tidy -- see [VerifyMergedManifestTask.requiredDeclarations] for the
 * measurement. In short: `android.permission.FOREGROUND_SERVICE` is a prefix of
 * `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so the bare form of this list reports the
 * shorter permission present in a manifest that declares only the longer one.
 */
private val BASE_DECLARATIONS = listOf(
  // The stream comes over the network.
  """android:name="android.permission.INTERNET"""",
  // `dangerous` from API 33. Without it the media notification is silently not shown.
  """android:name="android.permission.POST_NOTIFICATIONS"""",
  // A foreground service needs both: the generic permission, and the typed one that
  // matches `foregroundServiceType` from API 34. Missing the typed one throws
  // SecurityException from `startForeground` -- and only once the app is backgrounded
  // with audio playing, which no quick manual test covers.
  """android:name="android.permission.FOREGROUND_SERVICE"""",
  """android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"""",
  // The service itself, reaching the application from `:core:media`'s own manifest
  // through the merger. "It is declared in the library" is a claim about source layout;
  // this is the evidence.
  """android:name="app.muplay.media.MuPlaybackService"""",
  // The intent-filter action that makes a MediaSessionService discoverable by Android
  // Auto, Wear, Assistant and the system media controls.
  """android:name="androidx.media3.session.MediaSessionService"""",
  """android:foregroundServiceType="mediaPlayback"""",
)

/**
 * What Android Auto needs, and what nothing at runtime on a phone will ever tell you is missing.
 *
 * `android.media.browse.MediaBrowserService` is the one to read twice. Auto enumerates media apps
 * by that legacy action and by no other -- it talks `MediaBrowserCompat`, which Media3's session
 * library bridges -- so an app declaring only Media3's own actions is simply absent from the car's
 * list. No error, no log line, no crash. Measured off media3-session-1.11.0's bytecode:
 * `MediaSessionService.onBind` switches on exactly two action strings, and this is the second of
 * them; anything else gets `null`.
 *
 * The last two entries are one declaration between them, split because the presence of the
 * `<meta-data>` element and the identity of the resource it names are separately losable in a
 * manifest edit. Neither proves the resource *says* anything -- that is
 * `verifyAutomotiveDescriptor`'s job, and the reason it exists.
 *
 * `android.media.action.MEDIA_PLAY_FROM_SEARCH` **is** here as of Plan 5 Task 6, and it was
 * deliberately absent before that. The rule was that requiring the filter before its handler
 * existed would mean shipping a manifest claiming to answer an Assistant intent nothing answers;
 * the handler is now `MuPlaybackService.onStartCommand`, so the claim is true and this entry is
 * what stops a later manifest edit from deleting the filter silently. `ConventionTest`'s
 * `a declared play-from-search filter must have a handler and a gate entry` holds all three -- the
 * filter, the handler and this line -- to a single answer, in either direction.
 *
 * It is also the only replacement `app/lint.xml` has for `MissingIntentFilterForMediaSearch`, which
 * is disabled there because `AndroidAutoDetector` reads `:app`'s own manifest sources and this
 * service is declared in `:core:media`'s -- measured, and recorded in that file.
 */
private val AUTOMOTIVE_DECLARATIONS = listOf(
  """android:name="androidx.media3.session.MediaLibraryService"""",
  """android:name="android.media.browse.MediaBrowserService"""",
  """android:name="com.google.android.gms.car.application"""",
  """android:resource="@xml/automotive_app_desc"""",
  """android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH"""",
)
