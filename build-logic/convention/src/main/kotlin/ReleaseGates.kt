import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * `releaseCheck`, and the three artifact-level gates behind it.
 *
 * Plan 8 Task 7's deliverable is "cleartext, debug entry points and unminified release all fail the
 * build". Two of those three were already covered *as claims about the source tree*:
 * `verifyReleaseManifest` reads AGP's merged manifest, and `ConventionTest`'s
 * `every Hilt entry point is declared in a debug source set` reads the file paths. The third was
 * covered by nothing at all — Task 2's own report closed with it: *"nothing yet asserts
 * minification stays on — the exact defect class this plan targets, still open."*
 *
 * What is added here is the same three questions asked of the `.aab` instead. See
 * [VerifyReleaseArtifactTask] for why that distinction is the whole point and not a refinement.
 *
 * ### Why `releaseCheck` and not `check`
 *
 * `check` runs on every commit and must stay in the fast tier; this one runs R8 and `bundletool`,
 * measured at **4 m 33 s** from cold on a 24-core host. It is a lifecycle task the release pipeline
 * runs, not a per-commit gate — and `verifyReleaseVersion` and `verifyReleaseManifest`, which are
 * cheap, remain wired into `check` exactly as they were.
 *
 * ### How `releaseCheck` finds its gates
 *
 * By matching `verifyRelease*` in this project, minus [RELEASE_CHECK_EXCLUSIONS], rather than by
 * naming them. This repository has had three gates rot because a list written by hand in one file
 * described something discoverable from the tree and drifted from it — the emulator job's module
 * list being the most expensive. A new release gate is therefore run by `releaseCheck` the moment
 * it is registered, with nobody having to remember, and the only names written down anywhere are
 * the two deliberate *exclusions* — which `ConventionTest` then holds against the release workflow.
 */
internal fun Project.configureReleaseGates(extension: ApplicationExtension) {
  val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()

  androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val bundle = variant.artifacts.get(SingleArtifact.BUNDLE)

    tasks.register<VerifyReleaseArtifactTask>("verifyReleaseArtifact") {
      group = "verification"
      description = "Proves, from the ${variant.name} .aab itself, that it is minified, carries no " +
        "debug-only type, permits no cleartext, and is 16 KB-page safe."
      this.bundle.set(bundle)
      // `map { listOf(it) }.orElse(emptyList())`, not a plain `from(provider)`: with minification
      // off AGP never produces this artifact, and a file collection built straight from an absent
      // provider fails when it is resolved -- which would replace this task's own explanation of
      // what went wrong with Gradle's. The `map` chain keeps the task dependency on R8 while
      // letting "there is no mapping file" reach `verifyMinified` as the finding it is.
      mapping.from(
        variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
          .map { listOf(it) }
          .orElse(emptyList()),
      )
      debugSources.from(repositorySources("debug"))
      nonDebugSources.from(repositorySources("main"), repositorySources("release"))
      applicationPackage.set(
        extension.defaultConfig.applicationId
          ?: error("app/build.gradle.kts must declare an applicationId in defaultConfig"),
      )
      // The same two attributes `verifyReleaseManifest` forbids in the merged manifest, plus
      // `debuggable` -- which cannot appear there today (AGP writes it only for a debuggable build
      // type) and is worth refusing in the artifact for the same reason the other two are.
      forbiddenManifestAttributes.set(listOf("usesCleartextTraffic", "networkSecurityConfig", "debuggable"))
      // The control. See VerifyReleaseArtifactTask.requiredManifestAttributes: the bundle's
      // manifest is protobuf, and "the forbidden string is absent" is equally true of a blob this
      // scan cannot read.
      requiredManifestAttributes.set(
        listOf("foregroundServiceType", "app.muplay.media.MuPlaybackService", "android.permission.INTERNET"),
      )
      // The other control, for the DEX reader. A platform type every Android app references.
      dexProbeDescriptor.set("Landroid/os/Bundle;")
      report.set(layout.buildDirectory.file("reports/release/${variant.name}-artifact.txt"))
    }

    tasks.register<VerifyReleaseSignedTask>("verifyReleaseSigned") {
      group = "verification"
      description = "Fails unless the ${variant.name} .aab carries a signature that covers its code."
      this.bundle.set(bundle)
      // The code and the manifest: a signature over `META-INF` and the resources, with the dex
      // outside it, is the artifact worth refusing.
      signedEntries.set(listOf("base/dex/classes.dex", VerifyReleaseArtifactTask.BUNDLE_MANIFEST_ENTRY))
      report.set(layout.buildDirectory.file("reports/release/${variant.name}-signing.txt"))
    }
  }

  tasks.register<VerifyReleaseTagTask>("verifyReleaseTag") {
    group = "verification"
    description = "Fails unless $MUPLAY_RELEASE_TAG_ENV names the versionName this build carries."
    tag.set(providers.environmentVariable(MUPLAY_RELEASE_TAG_ENV).orElse(""))
    versionName.set(
      provider {
        extension.defaultConfig.versionName
          ?: error("app/build.gradle.kts must declare a versionName in defaultConfig")
      },
    )
  }

  tasks.register("releaseCheck") {
    group = "verification"
    description = "Every release gate that needs no upload key and no git tag."
    dependsOn(
      tasks.matching { it.name.startsWith(RELEASE_GATE_PREFIX) && it.name !in RELEASE_CHECK_EXCLUSIONS },
    )
  }
}

/** The prefix `releaseCheck` collects its gates by. */
internal const val RELEASE_GATE_PREFIX = "verifyRelease"

/**
 * The release gates `releaseCheck` deliberately leaves out, because each needs something a
 * developer's checkout does not have.
 *
 * `verifyReleaseSigned` needs the upload key, which is never in this repository and is not on the
 * machine of anyone who is merely building. `verifyReleaseTag` needs the tag being released, which
 * only exists inside the pipeline. Both are hard errors when what they need is absent — that is the
 * whole reason they are excluded rather than written to skip quietly, which is the shape this
 * project has recorded four separate times as *a check that cannot tell "no" from "I cannot tell"*.
 *
 * Excluding a gate is therefore the same thing as promising the release workflow runs it, and
 * `ConventionTest`'s `every release gate is run by releaseCheck or by the release workflow` is what
 * turns that promise into a check. Adding a name here without adding it to
 * `.github/workflows/release.yml` fails that test.
 */
internal val RELEASE_CHECK_EXCLUSIONS = listOf("verifyReleaseSigned", "verifyReleaseTag")

/**
 * Every Kotlin source under a module's `src/<sourceSet>/kotlin` directory, gathered from the projects
 * `settings.gradle.kts` declares rather than by walking the repository root.
 *
 * Walking the root is what the equivalent scans in `ConventionTest` do, and every one of them
 * carries a hard-won `it.name != ".claude"` guard: a git worktree checked out inside the repository
 * puts a second copy of every source file in the tree, and a rule that finds them reports on code
 * nobody in this build wrote. Deriving the directories from the project list cannot meet that at
 * all — a worktree is not a Gradle project of this build — and it is derived from the same file
 * that decides what gets compiled, so it cannot drift from the set of modules either.
 */
private fun Project.repositorySources(sourceSet: String): FileTree =
  rootProject.subprojects
    .map { fileTree(File(it.projectDir, "src/$sourceSet/kotlin")) { include("**/*.kt") } }
    .reduce(FileTree::plus)
