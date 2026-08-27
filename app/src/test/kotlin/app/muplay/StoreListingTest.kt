package app.muplay

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards `docs/STORE-LISTING.md` — the draft a human pastes into Play Console — against the tree it
 * describes.
 *
 * A store listing is the one artefact in this repository that is read by strangers and checked by
 * nobody: Play does not know what the app can do, and a claim that was true at version 1 and
 * silently stopped being true is invisible from inside the Console forever. Every rule below is
 * therefore the same shape as the rest of this project's gates — hold the written thing against the
 * discoverable thing, and make the scan fail loudly when it finds nothing to scan.
 *
 * Six rules, and what each one is falsified by:
 *
 * 1. the copy fits Play's limits — lengthen the short description past 80 characters
 * 2. the fixed-size assets are the exact sizes Play mandates — regenerate the feature graphic at a
 *    different size, or delete it
 * 3. the screenshots, the capture test and the table are one set — add a `capture(...)` call
 * 4. the declared form factors match the build — flip `androidAuto` in `app/build.gradle.kts`
 * 5. every claim names code that still exists — rename a cited file or symbol
 * 6. the description claims nothing this build cannot do — put "sleep timer" in the feature copy
 *
 * All six were run red before being committed; the measurements are in this task's report.
 */
class StoreListingTest {

  private fun repoRoot(): File {
    var dir = File(".").absoluteFile
    repeat(8) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile ?: return@repeat
    }
    error("could not locate the repository root from ${File(".").absolutePath}")
  }

  private fun listingFile(): File = File(repoRoot(), LISTING_PATH)

  private fun listing(): String = listingFile().readText()

  private fun captureTestFile(): File = File(repoRoot(), CAPTURE_TEST_PATH)

  /**
   * The body of one Markdown section: everything after the heading line, up to the next heading at
   * the same or a shallower level.
   */
  private fun section(heading: String): String {
    val level = heading.takeWhile { it == '#' }.length
    val text = listing()
    val marker = "\n$heading\n"
    val start = text.indexOf(marker)
    check(start >= 0) { "$heading is not a heading in ${listingFile().path}" }
    return text.substring(start + marker.length)
      .lineSequence()
      .takeWhile { line -> !(line.startsWith("#") && line.takeWhile { it == '#' }.length <= level) }
      .joinToString("\n")
  }

  /** A Markdown table's data rows, as trimmed cells. Separator and header rows are dropped. */
  private fun tableRows(body: String): List<List<String>> =
    body.lines()
      .map { it.trim() }
      .filter { it.startsWith("|") && it.endsWith("|") && it.length > 1 }
      .map { line -> line.trim('|').split("|").map { it.trim() } }
      .filterNot { cells -> cells.all { it.matches(SEPARATOR_CELL) } }
      .drop(1)

  private fun backticked(cell: String): String =
    BACKTICKED.find(cell)?.groupValues?.get(1)
      ?: error("expected a `backticked` value in the table cell: $cell")

  /**
   * Width and height out of a PNG's IHDR chunk.
   *
   * Read from the bytes rather than from a name or a comment: the whole point of the rule that uses
   * this is that a regenerated asset can silently change size, and only the file itself knows.
   */
  private fun pngSize(file: File): Pair<Int, Int> {
    val header = file.inputStream().use { it.readNBytes(PNG_HEADER_BYTES) }
    assertThat(header.size).describedAs("${file.path} is too short to be a PNG").isEqualTo(PNG_HEADER_BYTES)
    assertThat(header.copyOfRange(0, PNG_SIGNATURE.size))
      .describedAs("${file.path} does not start with a PNG signature")
      .isEqualTo(PNG_SIGNATURE)
    assertThat(String(header.copyOfRange(12, 16), Charsets.US_ASCII))
      .describedAs("${file.path}'s first chunk")
      .isEqualTo("IHDR")
    fun int(at: Int) = header.copyOfRange(at, at + 4).fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xFF) }
    return int(16) to int(20)
  }

  @Test
  fun `the listing copy fits Play's own field limits`() {
    val listing = listing()
    // The limits themselves are Play's, not this repository's, so they are written here rather than
    // derived -- there is nothing in the tree to derive them from. The document restates each one
    // beside its field, and this asserts the two agree, so neither can drift alone.
    PLAY_FIELD_LIMITS.forEach { (field, limit) ->
      val match = Regex(
        """### ${Regex.escape(field)} \(Play limit: (\d+) characters\)\s*\n+```text\n(.*?)\n```""",
        RegexOption.DOT_MATCHES_ALL,
      ).find(listing)
      assertThat(match)
        .describedAs("a '### $field (Play limit: N characters)' section with a ```text block in ${listingFile().path}")
        .isNotNull()
      val declared = match!!.groupValues[1].toInt()
      val copy = match.groupValues[2]
      assertThat(declared)
        .describedAs("$field's limit as this document states it")
        .isEqualTo(limit)
      assertThat(copy.trim())
        .describedAs("$field must not be empty -- an empty block would satisfy every length check here")
        .isNotEmpty()
      assertThat(copy.length)
        .describedAs("$field is ${copy.length} characters; Play truncates or rejects above $limit")
        .isLessThanOrEqualTo(limit)
    }
    // Play renders both of these on one line and strips newlines out of them.
    listOf("App name", "Short description").forEach { field ->
      val copy = copyBlock(field)
      assertThat(copy.lines())
        .describedAs("$field must be a single line")
        .hasSize(1)
    }
  }

  private fun copyBlock(field: String): String =
    Regex(
      """### ${Regex.escape(field)} \(Play limit: \d+ characters\)\s*\n+```text\n(.*?)\n```""",
      RegexOption.DOT_MATCHES_ALL,
    ).find(listing())?.groupValues?.get(1) ?: error("no $field block in ${listingFile().path}")

  @Test
  fun `every fixed-size store asset exists at the exact size Play requires`() {
    val rows = tableRows(section(FIXED_ASSETS_HEADING))
    // Vacuity first: a table this test could not parse would satisfy every assertion below by
    // having nothing in it to contradict them.
    assertThat(rows).describedAs("rows of $FIXED_ASSETS_HEADING").isNotEmpty()
    assertThat(rows.map { backticked(it[FILE_CELL]) })
      .describedAs("the two assets Play requires of every listing")
      .contains("app/src/main/ic_launcher-playstore.png", "play/feature-graphic.png")

    rows.forEach { cells ->
      val asset = cells[NAME_CELL]
      val file = File(repoRoot(), backticked(cells[FILE_CELL]))
      assertThat(file).describedAs("$asset").exists()
      val (width, height) = pngSize(file)
      assertThat("${width}x$height")
        .describedAs("$asset (${file.path}) as Play will measure it")
        .isEqualTo(cells[PIXELS_CELL])
      // The producer is named so a human can regenerate the asset; a name that no longer resolves
      // to a file is a dead instruction.
      assertThat(File(repoRoot(), backticked(cells[PRODUCER_CELL])))
        .describedAs("the script this document says produces $asset")
        .exists()
    }
  }

  @Test
  fun `the phone screenshots, the capture test and the listing table are the same set`() {
    val captureTest = captureTestFile()
    assertThat(captureTest).describedAs("the screenshot journey").exists()
    val captured = Regex("""capture\(output, "([^"]+)"\)""")
      .findAll(captureTest.readText())
      .map { it.groupValues[1] + ".png" }
      .toSortedSet()
    // Vacuity, and Play's own bounds on how many phone screenshots a listing may carry.
    assertThat(captured)
      .describedAs("capture(output, \"...\") calls in ${captureTest.path} -- a regex that stopped matching would make every check below trivially true")
      .isNotEmpty()
    assertThat(captured.size)
      .describedAs("Play accepts $MIN_SCREENSHOTS to $MAX_SCREENSHOTS phone screenshots")
      .isBetween(MIN_SCREENSHOTS, MAX_SCREENSHOTS)

    val tabled = tableRows(section(SCREENSHOTS_HEADING))
      .map { backticked(it[FILE_CELL - 1]) }
      .toSortedSet()
    assertThat(tabled)
      .describedAs("$SCREENSHOTS_HEADING lists the files ${captureTest.name} captures")
      .isEqualTo(captured)

    val directory = File(repoRoot(), SCREENSHOT_DIRECTORY)
    val committed = directory.listFiles().orEmpty().filter { it.extension == "png" }
    assertThat(committed.map { it.name }.toSortedSet())
      .describedAs("PNGs committed under $SCREENSHOT_DIRECTORY -- re-run ci/store-screenshots.sh")
      .isEqualTo(captured)
    assertThat(committed.filter { it.length() == 0L })
      .describedAs("empty screenshot files")
      .isEmpty()
    committed.forEach { file ->
      val (width, height) = pngSize(file)
      assertThat(minOf(width, height))
        .describedAs("${file.name} is ${width}x$height; Play's minimum side is $MIN_SIDE_PX")
        .isGreaterThanOrEqualTo(MIN_SIDE_PX)
      assertThat(maxOf(width, height))
        .describedAs("${file.name} is ${width}x$height; Play's maximum side is $MAX_SIDE_PX")
        .isLessThanOrEqualTo(MAX_SIDE_PX)
    }
  }

  @Test
  fun `the listing declares exactly the form factors the build declares`() {
    val root = repoRoot()
    val declaredByTheBuild = mapOf(
      // Task 1's own opt-in, and the line `ConventionTest` already holds against the descriptor it
      // promises. Declaring Auto opens a second review surface with its own screenshot slot, which
      // is why the listing has to say so out loud.
      "Android Auto" to Regex("""androidAuto\s*=\s*true""")
        .containsMatchIn(File(root, "app/build.gradle.kts").readText()),
      // A Wear app is a module, not a flag. The roadmap's `:wear` does not exist yet, and every
      // mention of Wear in the tree is a forward-looking comment.
      "Wear OS" to Regex("""include\("[^"]*:wear""")
        .containsMatchIn(File(root, "settings.gradle.kts").readText()),
      "Android TV" to sourceManifests().any { it.readText().contains("android.software.leanback") },
    )

    val rows = tableRows(section(FORM_FACTOR_HEADING)).associate { it[NAME_CELL] to it[DECLARED_CELL] }
    assertThat(rows.keys)
      .describedAs("rows of $FORM_FACTOR_HEADING")
      .containsAll(declaredByTheBuild.keys)

    declaredByTheBuild.forEach { (factor, declared) ->
      assertThat(rows.getValue(factor))
        .describedAs(
          "$factor is ${if (declared) "" else "not "}declared by the build, so $LISTING_PATH must " +
            "say ${if (declared) "Yes" else "No"}",
        )
        .isEqualTo(if (declared) "Yes" else "No")
    }
  }

  @Test
  fun `every claim names code that still exists`() {
    val rows = tableRows(section(CLAIMS_HEADING).substringBefore(NOT_YET_HEADING))
    assertThat(rows).describedAs("rows of $CLAIMS_HEADING").isNotEmpty()

    val broken = rows.mapNotNull { cells ->
      val claim = cells[NAME_CELL]
      val file = File(repoRoot(), backticked(cells[FILE_CELL]))
      when {
        !file.isFile -> "$claim -> ${cells[FILE_CELL]} does not exist"
        !file.readText().contains(backticked(cells[TOKEN_CELL])) ->
          "$claim -> ${cells[FILE_CELL]} no longer contains ${cells[TOKEN_CELL]}"
        else -> null
      }
    }
    assertThat(broken)
      .describedAs(
        "a store listing claim whose evidence has moved or been renamed. Either the claim is no " +
          "longer true, or the table needs pointing at where the code went -- decide which before " +
          "editing the table.",
      )
      .isEmpty()
  }

  @Test
  fun `the description does not claim a capability this build does not have`() {
    // Written down rather than derived, and legitimately so: this is a list of things the tree does
    // NOT contain, which nothing in the tree can produce. It comes from the capability audit
    // recorded in this document's own "Not in this version" table -- each entry there was measured
    // (the class exists and nothing constructs it, or no module declares it) rather than assumed.
    val banned = listOf(
      Regex("""\bsleep timer\b""", RegexOption.IGNORE_CASE),
      Regex("""\bSonos\b""", RegexOption.IGNORE_CASE),
      Regex("""\bDLNA\b""", RegexOption.IGNORE_CASE),
      Regex("""\bUPnP\b""", RegexOption.IGNORE_CASE),
      Regex("""\bChromecast\b""", RegexOption.IGNORE_CASE),
      Regex("""\bcast(s|ing)?\b""", RegexOption.IGNORE_CASE),
      Regex("""\bplayback speed\b""", RegexOption.IGNORE_CASE),
      Regex("""\bchapters?\b""", RegexOption.IGNORE_CASE),
      Regex("""\bdownload\w*\b""", RegexOption.IGNORE_CASE),
      Regex("""\boffline\b""", RegexOption.IGNORE_CASE),
      Regex("""\bWear OS\b""", RegexOption.IGNORE_CASE),
      Regex("""\bMaterial You\b""", RegexOption.IGNORE_CASE),
      Regex("""\bdynamic colou?r\b""", RegexOption.IGNORE_CASE),
      Regex("""\bLidarr\b""", RegexOption.IGNORE_CASE),
      Regex("""\bBindery\b""", RegexOption.IGNORE_CASE),
      Regex("""\bscrobbl\w*\b""", RegexOption.IGNORE_CASE),
      Regex("""\bsilence\b""", RegexOption.IGNORE_CASE),
    )

    // The vacuity guard, and it is a strong one: every pattern above must match somewhere in this
    // document, because the "Not in this version" prose and table name all of them. A pattern that
    // matches nothing anywhere is a pattern that could never have caught anything.
    val whole = listing()
    assertThat(banned.filterNot { it.containsMatchIn(whole) }.map { it.pattern })
      .describedAs(
        "these patterns match nothing in $LISTING_PATH at all, so they cannot be shown to work. " +
          "Every banned capability is named in its 'Not in this version' table; add the missing " +
          "row, or delete the pattern.",
      )
      .isEmpty()

    // What the listing actually claims: the app name, the short description, and the full
    // description down to the line where it starts listing what is absent.
    val claimText = listOf(
      copyBlock("App name"),
      copyBlock("Short description"),
      copyBlock("Full description").substringBefore(NOT_YET_MARKER),
    ).joinToString("\n")
    assertThat(claimText)
      .describedAs("the claim text must stop at '$NOT_YET_MARKER'; without that split this rule scans the whole description and can never pass")
      .doesNotContain(NOT_YET_MARKER)
    assertThat(claimText.length)
      .describedAs("the claim text is suspiciously short -- did the '$NOT_YET_MARKER' marker move?")
      .isGreaterThan(MIN_CLAIM_TEXT_LENGTH)

    val offences = banned.mapNotNull { pattern ->
      pattern.find(claimText)?.let { "\"${it.value}\" (${pattern.pattern})" }
    }
    assertThat(offences)
      .describedAs(
        "the listing claims something this build cannot do. Either wire the capability up, or take " +
          "the words out -- a store description is the one place in this repository where an " +
          "untrue sentence reaches a stranger and nothing goes red.",
      )
      .isEmpty()
  }

  private fun sourceManifests(): List<File> =
    repoRoot().walkTopDown()
      // Same skip list as `ConventionTest`, for the same reason: `.claude` holds git worktrees, so
      // walking into it finds a second copy of every module's manifest.
      .onEnter { it.name != "build" && it.name != ".git" && it.name != ".claude" }
      .filter { it.name == "AndroidManifest.xml" }
      .toList()

  private companion object {
    const val LISTING_PATH = "docs/STORE-LISTING.md"
    const val CAPTURE_TEST_PATH = "app/src/androidTest/kotlin/app/muplay/StoreScreenshotsTest.kt"
    const val SCREENSHOT_DIRECTORY = "play/screenshots/phone"

    const val FIXED_ASSETS_HEADING = "### Fixed-size assets"
    const val SCREENSHOTS_HEADING = "### Phone screenshots"
    const val FORM_FACTOR_HEADING = "## Form factors"
    const val CLAIMS_HEADING = "## Claims, and where each one is implemented"
    const val NOT_YET_HEADING = "### Not in this version"

    /** The heading inside the full description at which it stops claiming and starts disclaiming. */
    const val NOT_YET_MARKER = "NOT IN THIS VERSION"

    /** Play's own field limits, as of the date at the top of the listing document. */
    val PLAY_FIELD_LIMITS = mapOf(
      "App name" to 30,
      "Short description" to 80,
      "Full description" to 4000,
    )

    const val MIN_SCREENSHOTS = 2
    const val MAX_SCREENSHOTS = 8
    const val MIN_SIDE_PX = 320
    const val MAX_SIDE_PX = 3840

    /** Well under the real length, and enough to catch a marker that swallowed the whole body. */
    const val MIN_CLAIM_TEXT_LENGTH = 500

    // Column positions. The claims and form-factor tables are three-wide and the asset table is
    // four-wide; the screenshot table's file is its first column, hence the one adjustment at its
    // call site.
    const val NAME_CELL = 0
    const val DECLARED_CELL = 1
    const val FILE_CELL = 1
    const val TOKEN_CELL = 2
    const val PIXELS_CELL = 2
    const val PRODUCER_CELL = 3

    const val PNG_HEADER_BYTES = 24
    val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
      '\r'.code.toByte(), '\n'.code.toByte(), 0x1A, '\n'.code.toByte(),
    )

    val SEPARATOR_CELL = Regex(""":?-{2,}:?""")
    val BACKTICKED = Regex("""`([^`]+)`""")
  }
}
