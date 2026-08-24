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
 * [forbiddenAttributes].
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
  }
}
