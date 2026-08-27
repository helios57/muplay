import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails unless the git tag that triggered this build names the version the artifact carries.
 *
 * `verifyReleaseVersion` already proves `versionCode` and `versionName` agree with each other and
 * with the ledger. It cannot see the third name for the same release, which is the tag — and the
 * tag is what the pipeline puts on the GitHub release, what a bug report quotes, and what a person
 * reads to decide which commit shipped. `v0.3.0` pushed at a commit whose `app/build.gradle.kts`
 * still says `0.2.0` produces a perfectly valid bundle that every gate in this repository accepts
 * and that is labelled wrongly everywhere a human will look.
 *
 * ### It cannot pass by doing nothing
 *
 * The tag arrives in an environment variable, and an environment-variable gate is the shape this
 * project has recorded four times as *a check that returns the same falsey answer for "no" and for
 * "I cannot tell"*. So an unset [MUPLAY_RELEASE_TAG_ENV] is a failure, not a skip. The cost of that
 * choice is that this task is useless on a developer's machine, which is why it is one of the two
 * gates [RELEASE_CHECK_EXCLUSIONS] keeps out of `releaseCheck` — and `ConventionTest`'s
 * `every release gate is run by releaseCheck or by the release workflow` is what makes the
 * exclusion mean "the release workflow runs it" rather than "nothing runs it".
 */
abstract class VerifyReleaseTagTask : DefaultTask() {

  /** The tag, or empty when nothing set it. Read through `providers`, so Gradle tracks it. */
  @get:Input
  abstract val tag: Property<String>

  @get:Input
  abstract val versionName: Property<String>

  @TaskAction
  fun verify() {
    val tag = tag.get()
    val versionName = versionName.get()
    val expected = "$RELEASE_TAG_PREFIX$versionName"
    if (tag.isBlank()) {
      throw GradleException(
        "$MUPLAY_RELEASE_TAG_ENV is not set, so there is no tag to hold against " +
          "versionName $versionName. This task exists to be run by the release workflow, which " +
          "sets it from github.ref_name; run it by hand with $MUPLAY_RELEASE_TAG_ENV=$expected.",
      )
    }
    if (tag != expected) {
      throw GradleException(
        "the tag being released is '$tag' but app/build.gradle.kts declares versionName " +
          "'$versionName', so this artifact would be published under a name it does not carry. " +
          "Expected '$expected'. Move versionCode/versionName (and append to " +
          "app/$RELEASE_HISTORY_FILE) in the commit the tag points at, rather than retagging.",
      )
    }
    logger.lifecycle("RELEASE TAG: $tag matches versionName $versionName")
  }
}

/** The environment variable the release workflow puts `github.ref_name` into. */
internal const val MUPLAY_RELEASE_TAG_ENV = "MUPLAY_RELEASE_TAG"

/** Tags are `v` + the version name: `v0.2.0`. */
internal const val RELEASE_TAG_PREFIX = "v"
