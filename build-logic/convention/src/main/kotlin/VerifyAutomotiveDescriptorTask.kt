import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if the Android Auto descriptor is not an `<automotiveApp>` document declaring
 * every `<uses name="…"/>` in [requiredUses].
 *
 * The companion to [VerifyMergedManifestTask]'s `requiredDeclarations`, and it exists because that
 * task structurally cannot do this job: a merged manifest carries
 * `android:resource="@xml/automotive_app_desc"` — a *reference* — so a descriptor that existed but
 * was empty would satisfy every manifest check there is and still leave the app invisible in a car.
 * The same limitation is already recorded, for `networkSecurityConfig`, in
 * [AndroidApplicationConventionPlugin]'s own documentation.
 *
 * ### The limitation this task has, stated rather than papered over
 *
 * It reads the project's own `src/main/res/xml` file, not a merged-resources artifact, because AGP
 * exposes no public single-artifact handle for merged resources the way
 * [com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST] does for the manifest. That is a
 * real hole and not a preference: a *library* dependency could in principle contribute a competing
 * `automotive_app_desc.xml` that this task never sees, and resource merging would let the
 * dependency's copy lose to this one silently rather than fail. It is acceptable here only because
 * exactly one module in this build opts in (`muplayApplication { androidAuto = true }`) and
 * `ConventionTest`'s `the app module opts in to Android Auto and ships the descriptor it promises`
 * asserts, over the whole tree, that the module which opts in is the module that has the file.
 *
 * ### What this proves, and what only a car proves
 *
 * Everything statically checkable about Android Auto's media-app requirements is checked between
 * this task and [VerifyMergedManifestTask]: the descriptor exists and says `media`, the meta-data
 * points at it, and the service is exported with the three browse actions. Everything else Google's
 * own media validator checks — that the browse tree loads inside the host's timeout, that content
 * styles render, that the driver-distraction list limits are honoured — is a runtime property of a
 * host this repository cannot start, and no build-time gate here claims otherwise.
 */
abstract class VerifyAutomotiveDescriptorTask : DefaultTask() {

  @get:InputFile
  abstract val descriptor: RegularFileProperty

  /** `<uses name="…"/>` values that must be present. `"media"` is what makes this a media app. */
  @get:Input
  abstract val requiredUses: ListProperty<String>

  @TaskAction
  fun verify() {
    val file: File = descriptor.get().asFile
    // Comments stripped before either check, and this is not tidiness. Both checks below are
    // *presence* checks over text, and a presence check that reads prose is satisfied by the
    // comment explaining the declaration it is looking for -- which is exactly what a good comment
    // in an XML declaration file looks like. This repository has paid for that twice already:
    // `verifyReleaseNoDestructiveMigration` fails `check` on its own KDoc, and
    // `ConventionTest`'s cleartext rule stayed green with the attribute deleted and only the
    // comment about it left behind. `ConventionTest.withoutBlockComments` is the same one line.
    val text = file.readText().replace(Regex("""(?s)<!--.*?-->"""), "")
    if (!text.contains("<automotiveApp")) {
      throw GradleException(
        "$file is not an Android Auto descriptor: its root element must be <automotiveApp>. " +
          "Android Auto reads this file to decide what kind of app this is; a file it cannot " +
          "parse makes the app invisible in a car, with no error anywhere.",
      )
    }
    val missing = requiredUses.get().filterNot { use ->
      // Attribute order and quoting style both vary between hand-written and generated files, so
      // this matches the one thing that cannot: a `uses` element naming this value. Anchored on
      // `<uses` with whitespace after it, so `<usesSomethingElse name="media">` is not a match and
      // neither is the word appearing in this file's own explanatory comment.
      //
      // The **closing** quote is part of the pattern, for the same reason every entry in
      // `VerifyMergedManifestTask.requiredDeclarations` carries its `android:name="..."` wrapper:
      // without it a required value is satisfied by any longer value it happens to prefix, and a
      // presence check that over-matches passes wrongly. `media` is a prefix of `mediaTemplate`.
      val quotedName = "\"" + Regex.escape(use) + "\""
      Regex("""<uses\s+[^>]*name\s*=\s*$quotedName""").containsMatchIn(text)
    }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "$file does not declare <uses name=\"${missing.joinToString("\"/> or <uses name=\"")}\"/>. " +
          "Without it Android Auto does not treat this app as a media app, and it does not appear " +
          "in the car's media app list. Nothing at runtime reports this.",
      )
    }
  }
}
