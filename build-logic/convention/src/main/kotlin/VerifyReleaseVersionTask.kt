import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** The ledger of version codes that have already been spent, relative to the `:app` project. */
internal const val RELEASE_HISTORY_FILE = "release-history.tsv"

/**
 * Fails the build if `:app`'s `versionCode`/`versionName` are not a legal *next* release.
 *
 * ### The scheme
 *
 * `versionName` is `MAJOR.MINOR.PATCH`, and `versionCode` is
 * `MAJOR * 10_000 + MINOR * 100 + PATCH` with `MINOR` and `PATCH` each below 100. So `0.2.0` is
 * `200`, `1.0.0` is `10_000`, `1.12.3` is `11_203`. Two properties make this worth having over
 * "increment a number": the code is *derivable* from the name, so the two cannot silently disagree
 * (which is the mistake that ships an update users see as an older version than the one they have),
 * and it is monotonic in the name, so a higher name is always a higher code.
 *
 * No plugin computes it and no script rewrites the build file. `versionCode` and `versionName` stay
 * two literals a human edits in `app/build.gradle.kts`, which is also the only shape
 * `ConventionTest`'s `android { }` allow-list permits there.
 *
 * ### The gate
 *
 * `app/release-history.tsv` lists every version code this project has ever *spent* — uploaded, or
 * built as a release artifact that left the machine. Google Play rejects a second upload with a
 * version code it has already seen, which is a fine gate except that it fires at the end of a
 * release, after the artifact exists and after a tag has usually been pushed. This fires before
 * `bundleRelease` produces anything.
 *
 * Four checks, and the interesting one is the third:
 *
 * 1. The history file itself is well formed and free of duplicates — a corrupted ledger must not
 *    quietly weaken the rules built on it.
 * 2. `versionCode` matches the value derived from `versionName`.
 * 3. `versionCode` does not appear in the history, and neither does `versionName`. A repeated
 *    *name* with a fresh code is the subtler half and is just as wrong: two different artifacts
 *    that both call themselves 0.2.0 make every bug report ambiguous.
 * 4. `versionCode` is strictly greater than every code in the history — so a release can only move
 *    forward, never sideways into an unused gap below the last one, which Play would accept at
 *    upload and then refuse to serve as an update.
 *
 * The gate is only as strong as the discipline of appending to the file, and that is deliberate:
 * the alternative — deriving "already released" from git tags or from the Play API — makes an
 * offline build depend on a network service and makes the gate unable to run at all when it
 * cannot reach one, which is the failure mode this repository has written down three times now
 * (a check that returns the same falsey answer for "no" and for "I cannot tell").
 */
abstract class VerifyReleaseVersionTask : DefaultTask() {

  @get:Input
  abstract val versionCode: Property<Int>

  @get:Input
  abstract val versionName: Property<String>

  @get:InputFile
  abstract val history: RegularFileProperty

  @TaskAction
  fun verify() {
    val file = history.get().asFile
    val spent = parseHistory(file.readLines(), file.path)
    val problems = problemsWith(versionCode.get(), versionName.get(), spent)
    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString(
          prefix = "This build's version is not a legal next release:\n  - ",
          separator = "\n  - ",
          postfix = "\n\nSee app/$RELEASE_HISTORY_FILE and VerifyReleaseVersionTask's own docs.",
        ),
      )
    }
  }

  /** One line of the history: a spent version code and the name it was spent under. */
  internal data class SpentVersion(val code: Int, val name: String)

  internal companion object {

    /**
     * `MAJOR * 10_000 + MINOR * 100 + PATCH`, or null if [versionName] is not `MAJOR.MINOR.PATCH`
     * with `MINOR` and `PATCH` below 100.
     *
     * Returns null rather than throwing so the caller can report a malformed name as one problem
     * among several, instead of the first malformed thing hiding every other one.
     */
    fun versionCodeFor(versionName: String): Int? {
      val parts = versionName.split('.')
      if (parts.size != 3) return null
      val numbers = parts.map { it.toIntOrNull() ?: return null }
      if (numbers.any { it < 0 }) return null
      val (major, minor, patch) = numbers
      if (minor > 99 || patch > 99) return null
      return major * 10_000 + minor * 100 + patch
    }

    /**
     * Parses the history file: tab-separated `code`, `name`, and free text this task ignores.
     * Blank lines and `#` comments are skipped.
     *
     * Throws on a malformed or duplicated entry rather than skipping it. A ledger that silently
     * drops the line naming the code you are about to reuse is worse than no ledger.
     */
    fun parseHistory(lines: List<String>, path: String): List<SpentVersion> {
      val entries = lines
        .mapIndexed { index, line -> index + 1 to line.trim() }
        .filterNot { (_, line) -> line.isEmpty() || line.startsWith("#") }
        .map { (number, line) ->
          val fields = line.split('\t')
          if (fields.size < 2) {
            throw GradleException("$path:$number: expected at least two tab-separated fields, got: $line")
          }
          val code = fields[0].trim().toIntOrNull()
            ?: throw GradleException("$path:$number: first field is not a version code: ${fields[0]}")
          SpentVersion(code, fields[1].trim())
        }

      entries.groupBy { it.code }.filterValues { it.size > 1 }.keys.sorted().forEach { code ->
        throw GradleException("$path: version code $code is listed more than once")
      }
      return entries
    }

    /**
     * Every reason [versionCode]/[versionName] are not a legal next release, given [spent].
     *
     * Pulled out as a pure function of three values so `build-logic`'s own test suite can drive it
     * without a Gradle build — the same reason [VerifyMergedManifestTask.verify] is shaped the way
     * it is, and for the same recorded defect: a gate whose only exercise is "the build was green"
     * is a gate nobody has watched fail.
     */
    fun problemsWith(versionCode: Int, versionName: String, spent: List<SpentVersion>): List<String> {
      val problems = mutableListOf<String>()

      val derived = versionCodeFor(versionName)
      when {
        derived == null -> problems += "versionName \"$versionName\" is not MAJOR.MINOR.PATCH with " +
          "MINOR and PATCH below 100, so no version code can be derived from it"
        derived != versionCode -> problems += "versionCode $versionCode does not match versionName " +
          "\"$versionName\", which derives $derived (MAJOR * 10000 + MINOR * 100 + PATCH)"
      }

      if (spent.any { it.code == versionCode }) {
        problems += "version code $versionCode has already been spent"
      }
      if (spent.any { it.name == versionName }) {
        problems += "version name \"$versionName\" has already been spent"
      }
      val highest = spent.maxOfOrNull { it.code }
      if (highest != null && versionCode <= highest) {
        problems += "version code $versionCode is not above the highest already spent ($highest); " +
          "a release can only move forward"
      }
      return problems
    }
  }
}
