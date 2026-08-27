import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if AGP's own **merged** manifest for a variant contains any of
 * [forbiddenAttributes], or is missing any of [requiredDeclarations].
 *
 * Reads the merged artifact ([com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST]),
 * never the source manifests: a source-layout argument ("the attribute is only written in
 * `src/debug/`, so it cannot be in release") is exactly the kind of reasoning that stops being
 * true the moment a library dependency's own manifest declares the attribute and the manifest
 * merger pulls it up into the application's. Only the merged file is evidence about what actually
 * ships.
 *
 * A plain substring scan, not an XML attribute lookup, and deliberately so: it is a check on what
 * is *absent*, and the failure mode that matters is the attribute being present under any
 * namespace prefix, on any element, in any position. Matching the attribute's local name as text
 * over-matches rather than under-matches, which is the safe direction for this kind of assertion.
 */
abstract class VerifyMergedManifestTask : DefaultTask() {

  @get:InputFile
  abstract val mergedManifest: RegularFileProperty

  /**
   * Attribute names (local name, no `android:` prefix) that must not appear in [mergedManifest].
   */
  @get:Input
  abstract val forbiddenAttributes: ListProperty<String>

  /**
   * Substrings that **must** appear in [mergedManifest].
   *
   * The mirror of [forbiddenAttributes], and needed for the same reason: a permission or a service
   * declared in a library module's own manifest reaches the application only through the manifest
   * merger, and "it is declared in `:core:media`" is a claim about source layout, not about what
   * ships. Only the merged file is evidence.
   *
   * Substrings, not XML lookups, deliberately -- but in the *opposite* safe direction from
   * [forbiddenAttributes]: a required-presence check that over-matches would pass wrongly, so each
   * entry has to be specific enough to identify one declaration.
   *
   * "Specific enough" is not a style note here, it is the difference between a gate and a
   * decoration, and the obvious spelling of this list gets it wrong. `android.permission
   * .FOREGROUND_SERVICE` is a **prefix** of `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
   * so a bare-name list would report the first as present in a manifest that declares only the
   * second -- and the two are genuinely different permissions with different failure modes.
   * Measured, not reasoned about: with bare names, deleting the `FOREGROUND_SERVICE` line from
   * `core/media/src/main/AndroidManifest.xml` left `verifyDebugManifest` green. Every entry the
   * plugin passes in therefore carries its own `android:name="..."` (or
   * `android:foregroundServiceType="..."`) wrapper, whose closing quote is what makes a prefix
   * stop being a match. See [AndroidApplicationConventionPlugin]'s list.
   */
  @get:Input
  abstract val requiredDeclarations: ListProperty<String>

  @TaskAction
  fun verify() {
    val manifest: File = mergedManifest.get().asFile
    val text = manifest.readText()
    val found = forbiddenAttributes.get().filter { text.contains(it) }
    if (found.isNotEmpty()) {
      throw GradleException(
        "$manifest contains ${found.joinToString(", ")}, which permits cleartext HTTP and must " +
          "never reach this variant's merged manifest. If a debug-only source manifest is the " +
          "source (see app/src/debug/AndroidManifest.xml), it has leaked; if a library " +
          "dependency declares it, override it with tools:remove rather than shipping it. If " +
          "release genuinely needs one of these attributes, that is a deliberate decision to " +
          "make in this task's forbiddenAttributes list (AndroidApplicationConventionPlugin.kt), " +
          "not a manifest edit that slips past this check unnoticed.",
      )
    }
    // Comments stripped for the *presence* half only, and the two halves want opposite things.
    //
    // AGP's merged manifest keeps every source manifest's XML comments verbatim -- measured, not
    // assumed: `core/media`'s twenty-line comment explaining the browse actions is reproduced in
    // `app/build/intermediates/merged_manifest/debug/AndroidManifest.xml`. So a comment that quotes
    // the declaration it explains, which is what a good comment next to `android:name="..."` looks
    // like, would satisfy this check on behalf of a declaration nobody wrote. That is this
    // repository's recorded "assertion that runs but cannot fail" defect, and it has been paid for
    // twice already -- `verifyReleaseNoDestructiveMigration` fails `check` on its own KDoc, and
    // `ConventionTest`'s cleartext rule stayed green with the attribute deleted and only the
    // comment about it left behind.
    //
    // The forbidden half above deliberately keeps reading the comments: it is an *absence* check,
    // where over-matching is the safe direction, and a `usesCleartextTraffic` written into a
    // manifest comment is a change a human should have to look at.
    val declarations = text.replace(Regex("""(?s)<!--.*?-->"""), "")
    val missing = requiredDeclarations.get().filterNot { declarations.contains(it) }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "$manifest is missing ${missing.joinToString(", ")}. Every entry in this list is a " +
          "declaration whose absence fails no build, no install and no test, and shows up only " +
          "in the wild. A playback service that is not declared, or that lacks " +
          "FOREGROUND_SERVICE_MEDIA_PLAYBACK, throws SecurityException from startForeground the " +
          "first time the app is backgrounded with audio playing. An app missing " +
          "android.media.browse.MediaBrowserService, or the com.google.android.gms.car " +
          "meta-data, simply never appears in a car's media app list -- no error, no log line, " +
          "no crash. This is the check that turns either into a build failure. If a declaration " +
          "is only written in a manifest comment, note that comments are stripped before this " +
          "check: a comment quoting a declaration is not the declaration.",
      )
    }
  }
}
