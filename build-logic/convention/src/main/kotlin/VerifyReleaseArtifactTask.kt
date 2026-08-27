import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Verifies the **uploadable artifact** — the `.aab` `bundleRelease` just produced — rather than the
 * build script that asked for it.
 *
 * Plan 8's whole defect class is *a guarantee that holds on the debug variant and is never checked
 * on the release one*, and this repository has already shipped two of those. Task 2 turned R8 on
 * and closed its own report with the gap it had left: **"nothing yet asserts minification stays on
 * — Task 7's `releaseCheck` should assert it on the artifact, not on the DSL."** A rule that reads
 * `isMinifyEnabled` back out of `ApplicationExtension` proves that a line exists in
 * [configureReleaseBuild]. It does not prove that the bytes in the bundle went through R8, and the
 * two are different claims the moment anything — a variant filter, a `buildTypes` override in a
 * second application module, an AGP upgrade that renames a flag — comes between them.
 *
 * So every assertion here reads the zip. Four of them:
 *
 * 1. **Minification actually happened**, proven by a census of the `base/dex` entries against R8's own
 *    `mapping.txt`. See [verifyMinified] — it is the important one.
 * 2. **No type that only a debug source set declares is in the release program.** The repository
 *    already has `every Hilt entry point is declared in a debug source set`, which is a rule about
 *    *where a file lives*; this is the same claim made about what shipped.
 * 3. **The manifest inside the bundle** carries no attribute that can permit cleartext HTTP and no
 *    `debuggable`. `verifyReleaseManifest` already checks AGP's merged manifest; this checks the
 *    copy that is actually inside the upload, which is a different file produced by a later tool.
 * 4. **Every 64-bit native library tolerates a 16 KB memory page**, which Play requires and nothing
 *    else in this build looks at. See [ElfAlignment].
 *
 * ### Where `mapping.txt` comes from, and why a wrong one cannot fool this
 *
 * Through `SingleArtifact.OBFUSCATION_MAPPING_FILE`, so Gradle carries the dependency on R8 and the
 * file cannot be a leftover from an earlier build — but wrapped in `map { listOf(it) }.orElse(
 * emptyList())` at the wiring site (see `configureReleaseGates`), because when minification is off
 * that artifact does not exist and a file collection built straight from an absent provider fails
 * at resolution. "There is no mapping file" is the single most important thing this task has to be
 * able to *say*, so it must not be a state it cannot configure for.
 *
 * That still leaves the question of whether the mapping and the dex describe the same build, and
 * [verifyMinified] answers it rather than assuming it: every name R8 says it *kept* must be in the
 * dex, every name it says it *renamed* must not be, every renamed class's new name must be, and the
 * app-package names in the dex must be exactly the kept set. Four assertions over the same two
 * files, which disagree loudly if either one is not this artifact's.
 */
abstract class VerifyReleaseArtifactTask : DefaultTask() {

  /** The `.aab` this build produced. Everything below is read out of it. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val bundle: RegularFileProperty

  /** `build/outputs/mapping/<variant>/`, which holds `mapping.txt` when and only when R8 ran. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val mapping: ConfigurableFileCollection

  /** Every Kotlin source under a module's `src/debug/kotlin` directory. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val debugSources: ConfigurableFileCollection

  /**
   * Every Kotlin source under a module's `src/main/kotlin` or `src/release/kotlin` directory.
   *
   * Needed because a build-type source set may legitimately *replace* a type rather than add one:
   * `app/src/debug/.../CleartextPolicyModule.kt` and `app/src/release/.../CleartextPolicyModule.kt`
   * declare the same `app.muplay.di.CleartextPolicyModule`, and the release build contains that
   * name for an entirely correct reason. Subtracting these leaves the types **only** a debug
   * variant can compile, which is the set the release artifact must not contain.
   */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val nonDebugSources: ConfigurableFileCollection

  /** `app.muplay` — the package whose classes the minification census is about. */
  @get:Input
  abstract val applicationPackage: Property<String>

  /** Attribute names that must not appear in the bundle's own `AndroidManifest.xml`. */
  @get:Input
  abstract val forbiddenManifestAttributes: ListProperty<String>

  /**
   * Attribute names that must appear in it.
   *
   * The control for the check above, not a second gate. The bundle's manifest is aapt2's protobuf
   * encoding, not text, and "the forbidden string is absent from a binary blob" is a claim that is
   * equally true of a blob the scan cannot read at all. These prove the encoding still stores
   * attribute names as plain UTF-8 where a substring search finds them.
   */
  @get:Input
  abstract val requiredManifestAttributes: ListProperty<String>

  /**
   * A type descriptor from the platform that must be in the dex.
   *
   * The other end of the same control. Every assertion in [verifyMinified] about a name being
   * *absent* is satisfied by a dex parser that returns an empty list, so one name that is certainly
   * present has to be checked too.
   */
  @get:Input
  abstract val dexProbeDescriptor: Property<String>

  /** The census, written out so the numbers behind a green run are readable rather than implied. */
  @get:OutputFile
  abstract val report: RegularFileProperty

  @TaskAction
  fun verify() {
    val aab = bundle.get().asFile
    val lines = mutableListOf("release artifact: ${aab.name} (${aab.length()} bytes)")
    ZipFile(aab).use { zip ->
      val entries = zip.entries().asSequence().map { it.name }.toList()
      fun read(name: String): ByteArray =
        zip.getInputStream(zip.getEntry(name) ?: throw GradleException(missingEntry(aab, name, entries)))
          .use { it.readBytes() }

      lines += verifyMinified(aab, entries, ::read)
      lines += verifyManifest(aab, ::read)
      lines += verifyNativeLibraryPageSize(aab, entries, ::read)
    }
    report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
    lines.forEach { logger.lifecycle("RELEASE ARTIFACT: $it") }
  }

  // ---------------------------------------------------------------------------------------------
  // 1 + 2. Minification, and the debug source sets.
  // ---------------------------------------------------------------------------------------------

  /**
   * The census that proves R8 ran over the code in this bundle.
   *
   * `mapping.txt` lists every class in R8's *output* program as `original -> replacement:`, where
   * the replacement is the original when the class was kept under its own name and
   * `R8$$REMOVED$$CLASS$$n` when it was removed outright. So the mapping is a complete statement
   * about what the shipped dex should contain, and the dex is the evidence:
   *
   * - every `app.muplay` class R8 **renamed** must be absent from the dex under its original name.
   *   This is the assertion. With minification off, all 355 of them are present.
   * - every `app.muplay` class R8 **kept** must be present. Without this, the assertion above is
   *   satisfied by a dex parser that reads nothing, which is this project's most frequently
   *   recorded defect.
   * - every renamed class's **new** name must be present, so the dex in this bundle is R8's actual
   *   output rather than a differently-produced one that a stale mapping happens to sit beside.
   * - the set of `app.muplay` names in the dex must therefore be **exactly** the kept set. Measured
   *   on 0.2.0: 355 classes in the mapping, 7 kept (`MainActivity`, `MuPlayApplication`,
   *   `MuPlaybackService` — all three are manifest components AGP feeds R8 as keeps — plus the two
   *   Room databases and their generated `_Impl`s, kept by `app/proguard-rules.pro`), 348 renamed
   *   of which 49 removed, and exactly those 7 in `classes.dex`.
   *
   * No threshold and no hand-written list of class names anywhere in that: both sides are derived
   * from the artifacts, so a class added tomorrow is covered without anybody remembering.
   */
  private fun verifyMinified(aab: File, entries: List<String>, read: (String) -> ByteArray): List<String> {
    val dexEntries = entries.filter { it.startsWith("base/dex/") && it.endsWith(".dex") }.sorted()
    if (dexEntries.isEmpty()) {
      throw GradleException("$aab contains no base/dex/*.dex entry, so there is nothing to verify.")
    }
    val dexTypes = dexEntries.flatMap { DexTypes.descriptors(read(it)) }.toSet()

    val probe = dexProbeDescriptor.get()
    if (probe !in dexTypes) {
      throw GradleException(
        "the DEX type table in ${dexEntries.joinToString()} does not contain $probe, so this " +
          "task's dex reader is not seeing type names and every assertion it makes about a name " +
          "being absent is vacuous. Fix the reader (DexTypes), not this probe.",
      )
    }

    val mappingFile = mapping.files.firstOrNull { it.isFile && it.name == MAPPING_FILE_NAME }
      ?: throw GradleException(
        "R8 produced no $MAPPING_FILE_NAME for this build, so the release variant was NOT " +
          "minified. ${dexTypes.size} type descriptors are in ${dexEntries.joinToString()}, of " +
          "which ${dexTypes.count { it.startsWith(packageDescriptorPrefix()) }} are " +
          "${applicationPackage.get()} classes carrying their own source names. Minification is " +
          "set up in build-logic's configureReleaseBuild; a release artifact built without it " +
          "ships every class name, every method name and a great deal of unreachable code, and " +
          "is not the artifact this project's size and keep rules were measured against.",
      )

    val classes = readClassMapping(mappingFile, applicationPackage.get())
    val kept = classes.filter { it.original == it.replacement }.map { it.original }.toSet()
    val renamed = classes.filter { it.original != it.replacement }
    val removed = renamed.filter { it.replacement.startsWith(R8_REMOVED_PREFIX) }
    val liveRenamed = renamed.filterNot { it.replacement.startsWith(R8_REMOVED_PREFIX) }

    if (classes.isEmpty()) {
      throw GradleException(
        "$mappingFile names no ${applicationPackage.get()} class at all. Either the package is " +
          "wrong (applicationPackage is '${applicationPackage.get()}') or this mapping belongs to " +
          "a different build; either way nothing below would be checking anything.",
      )
    }
    if (renamed.isEmpty()) {
      throw GradleException(
        "$mappingFile lists ${classes.size} ${applicationPackage.get()} classes and says R8 " +
          "renamed none of them. That is what a `-keep class ${applicationPackage.get()}.**` rule " +
          "in app/proguard-rules.pro looks like from here: R8 ran, and minified nothing.",
      )
    }

    val appPrefix = packageDescriptorPrefix()
    val dexAppNames = dexTypes.filter { it.startsWith(appPrefix) }.mapNotNull(DexTypes::binaryNameOf).toSet()

    val leaked = renamed.map { it.original }.filter { it in dexAppNames }
    if (leaked.isNotEmpty()) {
      throw GradleException(
        "${leaked.size} class(es) R8's $MAPPING_FILE_NAME says it renamed are in this bundle's " +
          "DEX under their original names, so the packaged code did not come out of R8:\n" +
          leaked.sorted().take(20).joinToString("\n") { "  $it" } +
          (if (leaked.size > 20) "\n  ... and ${leaked.size - 20} more" else ""),
      )
    }
    val missingKept = kept.filterNot { it in dexAppNames }
    if (missingKept.isNotEmpty()) {
      throw GradleException(
        "$mappingFile says R8 kept ${missingKept.size} class(es) under their own names that are " +
          "not in this bundle's DEX: ${missingKept.sorted().take(20).joinToString()}. The mapping " +
          "and the artifact describe different builds.",
      )
    }
    val missingRenamed = liveRenamed.filterNot { DexTypes.descriptorOf(it.replacement) in dexTypes }
    if (missingRenamed.isNotEmpty()) {
      throw GradleException(
        "${missingRenamed.size} of ${liveRenamed.size} renamed classes are missing from this " +
          "bundle's DEX under the new names $MAPPING_FILE_NAME gives them, so the mapping does " +
          "not describe this artifact: " +
          missingRenamed.take(10).joinToString { "${it.original} -> ${it.replacement}" },
      )
    }
    val unaccounted = dexAppNames - kept
    if (unaccounted.isNotEmpty()) {
      throw GradleException(
        "${unaccounted.size} ${applicationPackage.get()} type(s) in this bundle's DEX are not in " +
          "$MAPPING_FILE_NAME's kept set: ${unaccounted.sorted().take(20).joinToString()}",
      )
    }

    val debugOnly = debugOnlyTypes()
    val programNames = classes.map { it.original }.toSet()
    val debugLeak = debugOnly.filter { it in programNames || it in dexAppNames }
    if (debugLeak.isNotEmpty()) {
      throw GradleException(
        "${debugLeak.size} type(s) declared only in a src/debug/ source set are in the release " +
          "program: ${debugLeak.sorted().joinToString()}. All of this repository's Hilt " +
          "@EntryPoints live there so that an instrumented test can reach into the running " +
          "application's object graph; an entry point in a release build is a way for anything " +
          "holding a Context to pull a binding — a credential store among them — straight out of " +
          "it. ConventionTest's `every Hilt entry point is declared in a debug source set` is the " +
          "same rule asked of the source tree; this is it asked of the upload.",
      )
    }

    return listOf(
      "dex entries: ${dexEntries.joinToString()} (${dexTypes.size} type descriptors)",
      "MINIFIED: ${classes.size} ${applicationPackage.get()} classes in $MAPPING_FILE_NAME = " +
        "${kept.size} kept + ${liveRenamed.size} renamed + ${removed.size} removed; " +
        "${dexAppNames.size} in the DEX, all of them kept ones",
      "DEBUG-ONLY TYPES: ${debugOnly.size} declared only in a src/debug source set, none in the release program",
    )
  }

  /**
   * Fully-qualified names declared in a `src/debug/` source set and in no `src/main`/`src/release`
   * one.
   *
   * Derived from the tree rather than listed, because a list of entry points written today is a
   * list that stops being complete the first time somebody adds one. The vacuity guard is the
   * point of the assertion, not decoration: with an empty result the caller's check passes forever.
   */
  private fun debugOnlyTypes(): Set<String> {
    val debug = declaredTypes(debugSources.files)
    if (debug.isEmpty()) {
      throw GradleException(
        "no type is declared under any src/debug/kotlin source set, so the debug-leak check " +
          "below would be checking nothing. If the entry points really have moved, this task's " +
          "debugSources wiring in ReleaseGates.kt is what needs updating.",
      )
    }
    val debugOnly = debug - declaredTypes(nonDebugSources.files)
    if (debugOnly.isEmpty()) {
      throw GradleException(
        "every type declared under a src/debug/kotlin source set is also declared under " +
          "src/main or src/release, so no type is debug-only and this check is vacuous.",
      )
    }
    return debugOnly
  }

  // ---------------------------------------------------------------------------------------------
  // 3. The manifest that is actually inside the upload.
  // ---------------------------------------------------------------------------------------------

  /**
   * `verifyReleaseManifest` reads `SingleArtifact.MERGED_MANIFEST`, which is a *text* XML file AGP
   * produces part-way through the build. What ships is `base/manifest/AndroidManifest.xml` inside
   * the bundle: the same declarations after aapt2 has re-encoded them as protobuf and after
   * `bundletool` has assembled the base module. Two different files from two different tools, and
   * only the second one is uploaded.
   *
   * A substring scan of the protobuf, which is exactly as crude as
   * [VerifyMergedManifestTask]'s and correct for the same reason: an attribute name is stored as
   * plain UTF-8 in that encoding, matching it as text over-matches rather than under-matches, and
   * over-matching is the safe direction for a check on what is *absent*. The required list is the
   * control that keeps that sentence honest.
   */
  private fun verifyManifest(aab: File, read: (String) -> ByteArray): List<String> {
    val text = read(BUNDLE_MANIFEST_ENTRY).decodeToString()
    val missing = requiredManifestAttributes.get().filterNot { text.contains(it) }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "$BUNDLE_MANIFEST_ENTRY in $aab does not contain ${missing.joinToString()}, which every " +
          "release of this app declares. Either the declaration is genuinely gone, or aapt2 no " +
          "longer stores attribute names as readable UTF-8 in this encoding — in which case the " +
          "forbidden-attribute scan beside this one is now vacuous and must be replaced with a " +
          "real protobuf decode rather than relaxed.",
      )
    }
    val found = forbiddenManifestAttributes.get().filter { text.contains(it) }
    if (found.isNotEmpty()) {
      throw GradleException(
        "$BUNDLE_MANIFEST_ENTRY in $aab contains ${found.joinToString()}. `usesCleartextTraffic` " +
          "and `networkSecurityConfig` are the two attributes that can permit plain HTTP to a " +
          "remote host from a shipped build; `debuggable` lets anything on the device attach to " +
          "the process and read the AndroidKeystore-backed credentials out of it.",
      )
    }
    return listOf(
      "BUNDLE MANIFEST: ${text.length} bytes, none of ${forbiddenManifestAttributes.get().joinToString()}; " +
        "control attributes present: ${requiredManifestAttributes.get().joinToString()}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // 4. 16 KB pages.
  // ---------------------------------------------------------------------------------------------

  private fun verifyNativeLibraryPageSize(
    aab: File,
    entries: List<String>,
    read: (String) -> ByteArray,
  ): List<String> {
    val libraries = entries.filter { it.startsWith("base/lib/") && it.endsWith(".so") }.sorted()
    val offenders = mutableListOf<String>()
    var segments = 0
    libraries.forEach { entry ->
      val loaded = ElfAlignment.read(read(entry))
      if (loaded.alignments.isEmpty()) {
        throw GradleException("$entry in $aab has no PT_LOAD segment, so its alignment cannot be read.")
      }
      segments += loaded.alignments.size
      val under = loaded.alignments.filter { it < ElfAlignment.REQUIRED_PAGE_SIZE }
      if (loaded.is64Bit && under.isNotEmpty()) {
        offenders += "$entry: ${under.joinToString { "0x${it.toString(16)}" }}"
      }
    }
    if (offenders.isNotEmpty()) {
      throw GradleException(
        "${offenders.size} 64-bit native library/libraries in $aab have PT_LOAD segments aligned " +
          "below ${ElfAlignment.REQUIRED_PAGE_SIZE} bytes, so they cannot be loaded on a device " +
          "with a 16 KB memory page and Play will reject the upload:\n" +
          offenders.joinToString("\n") { "  $it" } +
          "\nMuPlay writes no native code, so this is a dependency's `.so`: find which one added " +
          "it and upgrade or drop it.",
      )
    }
    return listOf(
      "16 KB PAGES: ${libraries.size} native library/libraries, $segments PT_LOAD segments, " +
        "every 64-bit one aligned to at least ${ElfAlignment.REQUIRED_PAGE_SIZE}",
    )
  }

  // ---------------------------------------------------------------------------------------------

  private fun packageDescriptorPrefix() = "L${applicationPackage.get().replace('.', '/')}/"

  private fun missingEntry(aab: File, name: String, entries: List<String>) =
    "$aab has no entry '$name'. It holds ${entries.size} entries, beginning " +
      entries.take(10).joinToString()

  internal companion object {
    internal const val MAPPING_FILE_NAME = "mapping.txt"
    internal const val BUNDLE_MANIFEST_ENTRY = "base/manifest/AndroidManifest.xml"

    /** R8's marker for a class that is in the mapping only to say it is gone. */
    internal const val R8_REMOVED_PREFIX = "R8\$\$REMOVED\$\$"

    /** One `original -> replacement:` line of a `mapping.txt`. */
    internal data class ClassMapping(val original: String, val replacement: String)

    private val CLASS_MAPPING_LINE = Regex("""^(\S+) -> (\S+):$""")

    /**
     * The `original -> replacement:` lines of [mappingFile] whose original is in [packageName].
     *
     * Streamed rather than read whole: this file is **55 MB** on the 0.2.0 bundle (it maps every
     * class of every dependency, not only this app's), and `readText()` on it inside a Gradle
     * daemon capped at `-Xmx2g` by `gradle.properties` is a real risk rather than a tidiness one.
     */
    internal fun readClassMapping(mappingFile: File, packageName: String): List<ClassMapping> {
      val prefix = "$packageName."
      val mappings = mutableListOf<ClassMapping>()
      mappingFile.forEachLine { line ->
        if (line.startsWith(prefix)) {
          CLASS_MAPPING_LINE.matchEntire(line)?.let { mappings += ClassMapping(it.groupValues[1], it.groupValues[2]) }
        }
      }
      return mappings
    }

    /**
     * Every top-level type [sources] declares, as `package.Name`.
     *
     * Deliberately shallow — top-level `class`/`interface`/`object` only, no nested types and no
     * type aliases. A nested type cannot exist without its outer one, so the outer name is
     * sufficient to detect the file having been compiled in, and a parser that tried to do more
     * would be a Kotlin front end living in a build script.
     */
    internal fun declaredTypes(sources: Iterable<File>): Set<String> =
      sources.filter { it.isFile && it.extension == "kt" }.flatMapTo(mutableSetOf()) { file ->
        val text = file.readText()
        val packageName = PACKAGE_LINE.find(text)?.groupValues?.get(1)
        TYPE_DECLARATION.findAll(text)
          .map { it.groupValues[1] }
          .map { if (packageName == null) it else "$packageName.$it" }
          .toList()
      }

    private val PACKAGE_LINE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

    /**
     * A top-level declaration starts in column 1 — that is what distinguishes it from a nested one
     * in every file in this repository, all of which are formatted by the same convention.
     */
    private val TYPE_DECLARATION = Regex(
      """^(?:public |internal |private |abstract |open |sealed |data |value |annotation |enum |companion )*""" +
        """(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)""",
      RegexOption.MULTILINE,
    )
  }
}
