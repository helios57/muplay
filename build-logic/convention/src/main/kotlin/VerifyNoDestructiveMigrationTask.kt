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
 * Fails the build if any `main` Kotlin source in this module calls
 * `fallbackToDestructiveMigration` -- **unless** [exemptionMarker] resolves to exactly one file,
 * in which case the call is tolerated but this task still says so, loudly, on every run.
 *
 * A silent version of this task -- a gate that could quietly go a whole plan without ever being
 * wired into CI -- is exactly the failure this project spent five rounds of Plan 1 review
 * eliminating: *a mechanism that reports the absence of a problem must be provably incapable of
 * staying quiet when it did not run.* Two things follow from that:
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
 *
 * Unlike [VerifyMergedManifestTask]'s cleartext-traffic check, there is no merge step to prefer
 * over the source here: a Kotlin function body is not assembled from a debug/release manifest
 * overlay, so the one call site this watches for can only ever appear as exactly the text this
 * task reads. Scanning `main`'s own Kotlin sources is already "what ships" -- there is no more
 * authoritative compiled artifact to read instead, the way the manifest task prefers the merged
 * manifest over `AndroidManifest.xml`.
 *
 * Registered by [AndroidRoomConventionPlugin] under the **release** variant only (mirroring
 * `configureReleaseManifestVerification`): the call site does not vary by build type (there is
 * one `DataModule.kt`, not a debug/release overlay), so "release-scoped" here is about naming and
 * precedent, not about the check seeing different content for different variants.
 *
 * `fallbackToDestructiveMigration` is Room's "drop every table and start over" escape hatch --
 * the right call before anything has shipped, and exactly the wrong one to ship: the first
 * post-release schema-version bump would silently delete `media_progress` -- every stored
 * audiobook position -- for every user, with no server copy to recover it from (book positions
 * are never sent to Navidrome). A comment saying so is not a gate; this task, actually run, is.
 */
abstract class VerifyNoDestructiveMigrationTask : DefaultTask() {

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val kotlinSources: ConfigurableFileCollection

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
    val offenders: List<File> = kotlinSources.files
      .filter { it.extension == "kt" && it.readText().contains(FORBIDDEN_CALL) }
    if (offenders.isEmpty()) {
      // The clean, post-release-prep state: no call, nothing to exempt, nothing to say.
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
  }
}
