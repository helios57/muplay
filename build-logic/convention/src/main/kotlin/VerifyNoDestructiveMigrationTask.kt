import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if any Kotlin or Java source that actually compiles into the release variant
 * calls `fallbackToDestructiveMigration` -- **unless** [exemptionMarker] resolves to exactly one
 * file, in which case the call is tolerated but this task still says so, loudly, on every run.
 *
 * A silent version of this task -- a gate that could quietly go a whole plan without ever being
 * wired into CI -- is exactly the failure this project spent five rounds of Plan 1 review
 * eliminating: *a mechanism that reports the absence of a problem must be provably incapable of
 * staying quiet when it did not run.* Four things follow from that, the third and fourth added
 * after successive re-reviews found the earlier ones were not enough on their own:
 *
 * 1. This task must actually run in CI on every PR, not merely exist and be reachable by name --
 *    see the explicit step in `.github/workflows/pr.yml`.
 * 2. Because the call site is legitimate and required throughout the rest of this plan (see the
 *    comment at its one call site, `DataModule.provideDatabase`), a task that ran in CI and simply
 *    failed on the call's presence would fail every PR from the moment it was added -- so passing
 *    needs a way to exist that is not "quietly do nothing." [exemptionMarker] is that way: its
 *    *presence* is what makes this task pass, its *content* names the reason and the work that
 *    must remove it, and -- the load-bearing property -- passing while it is present is never
 *    silent. Deleting the marker is therefore the one action that arms this gate for real, and
 *    that deletion is a deliberate, committed, reviewable diff rather than a fact someone has to
 *    remember.
 * 3. [kotlinSources] must be **what actually compiles**, not a guess at a conventional layout,
 *    and the "nothing found" path must be provably a real scan rather than an empty one. A
 *    re-review proved both were wrong in the first version of this task: it scanned a hardcoded
 *    `fileTree("src/main/kotlin")`, but `src/main/java` compiles into this project's Kotlin
 *    output too (AGP's built-in Kotlin support merges "java-shaped" and "kotlin-shaped" source
 *    roots into one Kotlin compilation, confirmed by placing a `.kt`-content file under
 *    `src/main/java` and watching `compileReleaseKotlin` fail on it), and so does
 *    `src/release/kotlin` -- exactly the release-only root a real regression would use. Worse,
 *    the clean path was a bare early return with no output, so "the scan found nothing to flag"
 *    and "the scan found nothing because the file tree resolved to zero files" were
 *    byte-identical. [kotlinSources] is now populated from
 *    [com.android.build.api.variant.Sources.getKotlin] and
 *    [com.android.build.api.variant.Sources.getJava] -- the variant's own account of what it
 *    compiles, not a path this file guesses at -- and [verify] prints every root it scanned and
 *    the total file count on every single run.
 * 4. A count of zero is not, on its own, evidence the scan looked in the right place, and reading
 *    a source root without reading every file type that can live in it is the same mismatch one
 *    level down. A further re-review demonstrated both: (a) with the wiring collapsed to only
 *    `build/generated/ksp/release/kotlin`, `verify` printed `scanned 3 Kotlin source file(s)
 *    across 1 root(s)` -- Room's own generated `_Impl` files, which can never contain a
 *    hand-written call -- and passed clean, because the old guard only asked "is the total
 *    non-zero", and generated output alone satisfies that; (b) a `.java` file containing the
 *    forbidden call, sitting in an already-scanned `Sources.java` root, was invisible, because
 *    the old file filter only read `*.kt`. [verify] now requires at least one scanned file to
 *    live under this module's own `src/` directory specifically (never satisfied by
 *    `build/generated/...`, which is the only kind of output that can pass the old aggregate
 *    check for the wrong reason), and reads both `.kt` and `.java` extensions out of every root.
 *
 * Unlike [VerifyMergedManifestTask]'s cleartext-traffic check, there is no merge step to prefer
 * over the source here: a Kotlin function body is not assembled from a debug/release manifest
 * overlay, so the one call site this watches for can only ever appear as exactly the text this
 * task reads. Scanning the variant's own sources is already "what ships" -- there is no more
 * authoritative compiled artifact to read instead, the way the manifest task prefers the merged
 * manifest over `AndroidManifest.xml`.
 *
 * Registered by [AndroidRoomConventionPlugin] under the **release** variant only (mirroring
 * `configureReleaseManifestVerification`): the call site does not vary by build type in the
 * *main* source set (there is one `DataModule.kt`, not a debug/release overlay), so
 * "release-scoped" is mostly about naming and precedent -- but [kotlinSources] deliberately still
 * asks the *release variant specifically* what it compiles, because a release-only source root
 * (`src/release/kotlin`) is exactly where a regression could hide from a main-only scan.
 *
 * `fallbackToDestructiveMigration` is Room's "drop every table and start over" escape hatch --
 * the right call before anything has shipped, and exactly the wrong one to ship: the first
 * post-release schema-version bump would silently delete `media_progress` -- every stored
 * audiobook position -- for every user, with no server copy to recover it from (book positions
 * are never sent to Navidrome). A comment saying so is not a gate; this task, actually run, is.
 */
abstract class VerifyNoDestructiveMigrationTask : DefaultTask() {

  /**
   * Every directory the release variant's own [com.android.build.api.variant.Sources.getKotlin]
   * and [com.android.build.api.variant.Sources.getJava] report as a source root -- not a
   * hardcoded path. This is what makes a new or renamed source root (a release-only Kotlin
   * directory, a `src/main/java` file, a future product flavor) impossible to fall outside of:
   * the variant is asked, not assumed. Includes generated (KSP) output directories, deliberately
   * -- see [verify] for why the zero-file guard cannot be satisfied by those alone.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val kotlinSources: ConfigurableFileCollection

  /**
   * This module's own `src` directory, as plain text -- `File(projectDir, "src").path`, set once
   * by the registering plugin. A `@Input String`, not a re-derivation from [kotlinSources] at
   * execution time, because "which scanned files count as hand-written" has to be answered from
   * the module's own layout, not from whatever [kotlinSources] happens to resolve to that run --
   * the whole point is to keep the guard meaningful even if [kotlinSources]' wiring regresses to
   * generated-output-only again.
   */
  @get:Input
  abstract val projectSrcPath: Property<String>

  /**
   * Resolves to the committed exemption marker file if (and only if) it currently exists --
   * declared as a [ConfigurableFileCollection], not a singular `@InputFile`, specifically so a
   * *missing* marker is a normal, trackable "this collection currently has zero files" input
   * rather than a validation error. Gradle still invalidates this task's up-to-date state when the
   * marker is added, removed, or edited, because the collection is a real `@InputFiles` input.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val exemptionMarker: ConfigurableFileCollection

  /**
   * The human-readable path [exemptionMarker] is expected to resolve to, for the failure message
   * alone. Needed as its own `@Input` because [exemptionMarker] itself resolves to *zero* files
   * in exactly the failure case this message fires for -- `ConfigurableFileCollection.asPath` on
   * an empty collection is `""`, which is not a helpful thing to print in a message whose entire
   * job is telling someone where to put the file back.
   */
  @get:Input
  abstract val exemptionMarkerPath: Property<String>

  @TaskAction
  fun verify() {
    val roots: List<File> = kotlinSources.files.filter { it.isDirectory }
    // Both extensions, not just `.kt`: a scanned root can hold either (AGP's built-in Kotlin
    // support compiles both "kotlin-shaped" and "java-shaped" roots into the same Kotlin output),
    // and a `.java` file can call a public, Java-callable Room builder method just as easily as a
    // `.kt` file can.
    val scannedFiles: List<File> = roots
      .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in SCANNED_EXTENSIONS } }
      .distinct()

    // The whole point of this line: "ran and found nothing" and "never really ran" must never
    // look the same. Printed unconditionally, pass or fail, clean or exempted.
    logger.lifecycle(
      "verifyNoDestructiveMigration: scanned ${scannedFiles.size} Kotlin/Java source file(s) " +
        "across ${roots.size} root(s): ${roots.joinToString { it.path }}",
    )

    // Not "is the total non-zero": a total built entirely from `build/generated/ksp/.../*.kt`
    // (Room's own `_Impl` output, which can never contain a hand-written call) satisfies a bare
    // non-zero check while the hand-written roots this task actually cares about are silently
    // absent -- demonstrated live by collapsing the wiring to the generated directory alone and
    // watching this guard pass. Requiring at least one scanned file under this module's own
    // `src/` is what makes the guard fail specifically when the hand-written roots are the ones
    // missing, which is the regression this guard exists to catch.
    val srcPrefix = projectSrcPath.get() + File.separator
    val handWrittenFiles = scannedFiles.filter { it.path.startsWith(srcPrefix) }
    if (handWrittenFiles.isEmpty()) {
      throw GradleException(
        "verifyNoDestructiveMigration scanned ${scannedFiles.size} file(s) across " +
          "${roots.size} root(s) (${roots.joinToString { it.path }}), but none of them live " +
          "under ${projectSrcPath.get()} -- only generated/build output, if anything, was " +
          "found. A module with no hand-written source to scan is not a state this task can " +
          "pass through silently: either this module genuinely has none (in which case it " +
          "should not apply muplay.android.room, or this task needs excluding explicitly), or " +
          "the source roots changed shape and this task's input wiring in " +
          "AndroidRoomConventionPlugin needs to follow.",
      )
    }

    val offenders: List<File> = scannedFiles.filter { it.readText().contains(FORBIDDEN_CALL) }
    if (offenders.isEmpty()) {
      // The clean, post-release-prep state: no call, nothing to exempt. Nothing further to
      // report -- the scan summary above already proved this was a real scan, not an empty one.
      return
    }

    val marker = exemptionMarker.files.singleOrNull()
    if (marker == null) {
      throw GradleException(
        "${offenders.joinToString { it.path }} call(s) `$FORBIDDEN_CALL`, which must never " +
          "reach a release build: it silently drops every table -- including `media_progress`, " +
          "every stored audiobook position -- on the next schema-version bump after a user " +
          "already has the app installed, with no server copy to recover from. Remove the call " +
          "(replace it with real `Migration` objects, verified against the exported schema JSON " +
          "in `core/database/schemas/`) before this ships. If this call is still legitimately " +
          "needed (pre-release development), restore the exemption marker this task expects at " +
          "${exemptionMarkerPath.get()} -- deleting it is what makes this gate start enforcing " +
          "again, so it should not be restored without a real reason written into it.",
      )
    }

    // Exempted -- but never silently. A passing build is not the same thing as nothing to report
    // here: the whole point of a marker file over a magic comment is that its presence is visible
    // on every single run, not just the run where someone happens to look for it.
    val reason = marker.readText().trim().ifEmpty { "(the marker file has no content)" }
    logger.warn(
      "\n" +
        "################################################################################\n" +
        "# DESTRUCTIVE MIGRATION EXEMPTION IN EFFECT -- ${offenders.size} call site(s) of\n" +
        "# `$FORBIDDEN_CALL` found, tolerated only because ${marker.path} exists.\n" +
        "#\n" +
        "# Reason recorded in that file:\n" +
        reason.lineSequence().joinToString("\n") { "# > $it" } + "\n" +
        "#\n" +
        "# Deleting ${marker.name} is what arms this gate for real. Do not let this exemption\n" +
        "# outlive the work it names.\n" +
        "################################################################################\n",
    )
  }

  companion object {
    private const val FORBIDDEN_CALL = "fallbackToDestructiveMigration"
    private val SCANNED_EXTENSIONS = setOf("kt", "java")
  }
}
