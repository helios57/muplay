import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import java.io.File
import java.util.Properties
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * The release variant's shrinker, signing config, bundle layout and version gate — everything that
 * separates "an APK that runs on this laptop" from "an artifact that can be uploaded".
 *
 * All of it lives here rather than in `app/build.gradle.kts` because `ConventionTest`'s
 * `no module configures android or kotlin blocks directly` allow-lists exactly four properties
 * inside a module's own `android { }` block — `namespace`, and `applicationId`/`versionCode`/
 * `versionName` inside `defaultConfig { }`. A `buildTypes { release { … } }` block there fails
 * that rule by construction, and it belongs here on its own merits: a second application module
 * (the roadmap's Wear OS app) must not be able to ship unminified or unsigned by forgetting to
 * copy a block.
 *
 * The two halves that deliberately stay in the module: `versionCode` and `versionName`. They are
 * release identity, they move every release, and putting them in `build-logic` would make every
 * release a build-logic change. [configureReleaseVersionGate] reads them back out from here.
 */
internal fun Project.configureReleaseBuild(extension: ApplicationExtension) {
  val signing = releaseSigningConfig(extension)

  extension.buildTypes.configureEach {
    if (name != "release") return@configureEach

    // The point of the whole task. `isMinifyEnabled` runs R8 over the code; `isShrinkResources`
    // needs it (AGP refuses resource shrinking without code shrinking) and removes resources no
    // kept code can reach.
    //
    // What makes this dangerous rather than routine, and why `app/proguard-rules.pro` is as long
    // as it is: R8 in full mode — AGP's default since 8.0 — assumes anything not reachable from a
    // keep rule is dead, and this application reaches a great deal of its own code only through
    // reflection (Room's generated `_Impl`, Hilt's generated components, kotlinx-serialization's
    // synthetic `$serializer`, Retrofit's `Proxy` over an annotated interface, Media3's
    // `Bundle`-based session IPC). None of that is visible to a JVM unit test or to a debug run.
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
      // `proguard-android-optimize.txt`, not `proguard-android.txt`: the plain file turns
      // optimisation off wholesale (`-dontoptimize`), which is most of the size win.
      extension.getDefaultProguardFile("proguard-android-optimize.txt"),
      file("proguard-rules.pro"),
    )

    // Left null when no key material is configured, which is what a CI job that only compiles the
    // release variant gets. AGP then produces an unsigned artifact rather than failing, so
    // `assembleRelease` stays a usable compile gate on a machine that holds no key.
    signingConfig = signing
  }

  configureBundle(extension)
  configureReleaseVersionGate(extension)
}

/**
 * Where the upload key comes from, in priority order, and nothing about what it is.
 *
 * 1. Environment variables — what CI uses. The four values arrive from repository secrets and the
 *    keystore itself is materialised to a path by the workflow.
 * 2. `keystore.properties` in the repository root — what a developer uses. Git-ignored, and
 *    `ConventionTest`'s `no keystore material is tracked by git` proves it stays that way against
 *    the index rather than against the ignore file's text.
 * 3. Neither: no signing config at all.
 *
 * Nothing here reads a password into a message, a log line or a task input. The only strings this
 * function ever puts in front of a human are key *names* and the properties file's path.
 *
 * Both sources are read through `providers`, not `File.readText()`/`System.getenv()`: those are
 * configuration-cache inputs Gradle tracks, so a changed keystore invalidates a cached
 * configuration instead of being silently reused — the same class of stale-gate defect this
 * repository has already recorded against `--configuration-cache` and against the shared build
 * cache.
 */
private fun Project.releaseSigningConfig(extension: ApplicationExtension): ApkSigningConfig? {
  val material = signingMaterialFromEnvironment() ?: signingMaterialFromPropertiesFile() ?: return null

  if (!material.storeFile.isFile) {
    // Naming the path is safe and is the only useful thing to say: a keystore configured but
    // missing is otherwise reported by AGP as an unsigned build, hundreds of lines later.
    logger.warn(
      "MuPlay release signing: ${material.source} names a keystore at ${material.storeFile}, " +
        "which does not exist. The release variant will be built UNSIGNED.",
    )
    return null
  }

  val config = extension.signingConfigs.create("release")
  config.storeFile = material.storeFile
  config.storePassword = material.storePassword
  config.keyAlias = material.keyAlias
  config.keyPassword = material.keyPassword
  // v1 (jar signing) off, v2/v3 on: `minSdk` is 26, well past the API 24 that v2 needs, and a v1
  // signature is the one an upload can be stripped of and re-signed.
  config.enableV1Signing = false
  config.enableV2Signing = true
  config.enableV3Signing = true
  return config
}

/** The four values a signing config needs, and where they came from. Never logged, never an input. */
private data class SigningMaterial(
  val storeFile: File,
  val storePassword: String,
  val keyAlias: String,
  val keyPassword: String,
  val source: String,
)

internal const val KEYSTORE_PATH_ENV = "MUPLAY_KEYSTORE_PATH"
internal const val KEYSTORE_PASSWORD_ENV = "MUPLAY_KEYSTORE_PASSWORD"
internal const val KEY_ALIAS_ENV = "MUPLAY_KEY_ALIAS"
internal const val KEY_PASSWORD_ENV = "MUPLAY_KEY_PASSWORD"

/** The git-ignored file a developer puts their own upload key's coordinates in. */
internal const val KEYSTORE_PROPERTIES_FILE = "keystore.properties"

private fun Project.signingMaterialFromEnvironment(): SigningMaterial? {
  fun env(name: String) = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

  val path = env(KEYSTORE_PATH_ENV) ?: return null
  val storePassword = env(KEYSTORE_PASSWORD_ENV)
  val alias = env(KEY_ALIAS_ENV)
  val keyPassword = env(KEY_PASSWORD_ENV)
  if (storePassword == null || alias == null || keyPassword == null) {
    // Half-configured CI is the case worth failing loudly on: silently producing an unsigned
    // bundle from a release job that believes it signed one is the release-shaped version of this
    // project's recorded "a gate that cannot fail" defect.
    error(
      "$KEYSTORE_PATH_ENV is set, so release signing is expected, but " +
        listOf(KEYSTORE_PASSWORD_ENV, KEY_ALIAS_ENV, KEY_PASSWORD_ENV)
          .filterIndexed { index, _ -> listOf(storePassword, alias, keyPassword)[index] == null }
          .joinToString(", ") + " is missing or blank.",
    )
  }
  return SigningMaterial(File(path), storePassword, alias, keyPassword, "the $KEYSTORE_PATH_ENV environment")
}

private fun Project.signingMaterialFromPropertiesFile(): SigningMaterial? {
  val file = rootProject.layout.projectDirectory.file(KEYSTORE_PROPERTIES_FILE)
  val text = providers.fileContents(file).asText.orNull ?: return null
  val properties = Properties().apply { load(text.reader()) }

  fun value(key: String) = properties.getProperty(key)?.takeIf { it.isNotBlank() }

  val path = value("storeFile")
  val storePassword = value("storePassword")
  val alias = value("keyAlias")
  val keyPassword = value("keyPassword")
  if (path == null || storePassword == null || alias == null || keyPassword == null) {
    error(
      "$KEYSTORE_PROPERTIES_FILE exists but does not define all four of storeFile, storePassword, " +
        "keyAlias, keyPassword. (Values are never printed; only these key names are.)",
    )
  }
  // Relative paths resolve against the repository root, so the file can name `upload-keystore.jks`
  // beside itself without every developer's checkout path leaking into it.
  val resolved = File(path).let { if (it.isAbsolute) it else rootProject.layout.projectDirectory.file(path).asFile }
  return SigningMaterial(resolved, storePassword, alias, keyPassword, KEYSTORE_PROPERTIES_FILE)
}

/**
 * The `.aab`'s split configuration.
 *
 * ABI and density splits stay on (AGP's default): they are pure download-size wins with no
 * behavioural consequence. The **language** split is turned off deliberately. With it on, Play
 * installs only the device's current locale, and an app that later offers an in-app language
 * picker finds the strings for every other language simply absent on the device. MuPlay ships one
 * language today, so this costs nothing now and removes a failure that would otherwise appear for
 * the first time in a store build, on somebody else's phone, after localisation lands.
 */
private fun configureBundle(extension: ApplicationExtension) {
  extension.bundle.language.enableSplit = false
}

/**
 * Registers `verifyReleaseVersion` and wires it into `check` and into the release variant's own
 * `assemble`/`bundle` lifecycle tasks.
 *
 * Both, not either. `check` is what makes the gate part of the fast tier, so a version mistake is
 * caught in the same run as everything else. The lifecycle wiring is what makes it true of the
 * thing being *produced*: `./gradlew bundleRelease` alone does not run `check`, and "the artifact
 * you are about to upload does not reuse a spent version code" is a claim about that artifact.
 *
 * `assemble<Variant>`/`bundle<Variant>` by name is safe in a way `processReleaseMainManifest` is
 * not (see [configureMergedManifestVerification]'s note): those are Gradle *lifecycle* task names
 * derived from the variant name and are part of AGP's public contract, not internal task
 * identities that have changed across versions.
 */
private fun Project.configureReleaseVersionGate(extension: ApplicationExtension) {
  val verify = tasks.register<VerifyReleaseVersionTask>("verifyReleaseVersion") {
    group = "verification"
    description = "Fails if this build reuses a version code that has already been spent -- " +
      "see app/$RELEASE_HISTORY_FILE."
    // Read from `defaultConfig`, which is where `app/build.gradle.kts` declares them, so the gate
    // reads the same two numbers the packaged artifact carries rather than a copy of them.
    versionCode.set(
      provider {
        extension.defaultConfig.versionCode
          ?: error("app/build.gradle.kts must declare a versionCode in defaultConfig")
      },
    )
    versionName.set(
      provider {
        extension.defaultConfig.versionName
          ?: error("app/build.gradle.kts must declare a versionName in defaultConfig")
      },
    )
    history.set(layout.projectDirectory.file(RELEASE_HISTORY_FILE))
  }
  tasks.named("check").configure { dependsOn(verify) }
  tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verify) }
}
