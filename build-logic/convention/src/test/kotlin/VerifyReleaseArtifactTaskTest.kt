import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Drives the real `verifyReleaseArtifact` over synthetic bundles, one defect at a time.
 *
 * The task has been watched failing on a real artifact too, and that run is the headline evidence:
 * with `isMinifyEnabled`/`isShrinkResources` flipped to `false` in `configureReleaseBuild`,
 * `./gradlew :app:verifyReleaseArtifact --no-build-cache` built a real `.aab` and refused it —
 *
 *     R8 produced no mapping.txt for this build, so the release variant was NOT minified. 27978
 *     type descriptors are in base/dex/classes.dex, base/dex/classes2.dex, base/dex/classes3.dex,
 *     base/dex/classes4.dex, of which 920 are io.github.helios57.muplay classes carrying their own source names.
 *
 * — against 6146 descriptors in one dex, 7 of them `io.github.helios57.muplay`, on the minified build minutes
 * earlier. That is one branch of one check. This file is the rest of them, and it exists for the
 * reason [VerifyMergedManifestTaskTest] states at length: a gate whose only exercise is a green
 * build is a gate nobody has watched fail, and *green* is what this one produces on every correct
 * release forever.
 *
 * ### Synthetic DEX and ELF files, deliberately
 *
 * [dex] and [elf] build the few dozen bytes [DexTypes] and [ElfAlignment] actually read. That is
 * not a shortcut around using a real artifact — the real artifact is covered above — it is what
 * makes it possible to ask questions no real build produces on demand: a mapping that describes a
 * *different* build, a debug-only entry point that R8 renamed so it is invisible in the DEX and
 * visible only in the mapping, a 64-bit library aligned to 4 KB. Each of those is a defect this
 * task claims to catch and none of them can be arranged by flipping a build flag.
 */
class VerifyReleaseArtifactTaskTest {

  // -------------------------------------------------------------------------------------------
  // The compliant fixture. Every case below is this, minus or plus one thing. It is the 0.2.0
  // bundle's shape in miniature: a handful of classes R8 kept under their own names, more that it
  // renamed, one it removed outright, and a platform type as the DEX reader's proof of life.
  // -------------------------------------------------------------------------------------------

  private val keptClass = "io.github.helios57.muplay.MainActivity"
  private val renamedClass = "io.github.helios57.muplay.library.LibraryScreenKt"
  private val debugOnlyClass = "io.github.helios57.muplay.media.PlaybackEntryPoint"

  private val mapping = """
    io.github.helios57.muplay.MainActivity -> io.github.helios57.muplay.MainActivity:
        void onCreate(android.os.Bundle) -> onCreate
    io.github.helios57.muplay.library.LibraryScreenKt -> a1:
    io.github.helios57.muplay.player.PlayerViewModel -> b2:
    io.github.helios57.muplay.gone.NeverReferenced -> R8${'$'}${'$'}REMOVED${'$'}${'$'}CLASS${'$'}${'$'}17:
    androidx.room.RoomDatabase -> androidx.room.RoomDatabase:
  """.trimIndent()

  private val dexTypes = listOf(
    "Landroid/os/Bundle;",
    "Ljava/lang/String;",
    "Lio/github/helios57/muplay/MainActivity;",
    "La1;",
    "Lb2;",
  )

  /** aapt2 stores attribute names as plain UTF-8 in the bundle's protobuf manifest. */
  private val manifest =
    "\u0002\u0010manifest\u0000android:name=\"android.permission.INTERNET\"\u0000" +
      "android:name=\"io.github.helios57.muplay.media.MuPlaybackService\"\u0000" +
      "android:foregroundServiceType=\"mediaPlayback\"\u0000"

  private val required = listOf("foregroundServiceType", "io.github.helios57.muplay.media.MuPlaybackService", "android.permission.INTERNET")
  private val forbidden = listOf("usesCleartextTraffic", "networkSecurityConfig", "debuggable")

  /** One 64-bit and one 32-bit library, both 16 KB aligned, like the real bundle's eight. */
  private val libraries = mapOf(
    "base/lib/arm64-v8a/libpath.so" to Elf(is64Bit = true, alignments = listOf(0x4000L, 0x4000L)),
    "base/lib/armeabi-v7a/libpath.so" to Elf(is64Bit = false, alignments = listOf(0x4000L)),
  )

  /** `app/src/debug/...` declares one type that no main or release source set declares. */
  private val debugSources = mapOf(
    "PlaybackEntryPoint.kt" to "package io.github.helios57.muplay.media\n\ninterface PlaybackEntryPoint\n",
    // The build-type *twin*: the same fully-qualified name exists in `src/release` too, so it is
    // legitimately in the release program and must not be reported. Without this case in the
    // fixture, the subtraction that makes the check correct would be untested.
    "CleartextPolicyModule.kt" to "package io.github.helios57.muplay.di\n\nobject CleartextPolicyModule\n",
  )
  private val nonDebugSources = mapOf(
    "CleartextPolicyModule.kt" to "package io.github.helios57.muplay.di\n\nobject CleartextPolicyModule\n",
    "MainActivity.kt" to "package io.github.helios57.muplay\n\nclass MainActivity\n",
  )

  // -------------------------------------------------------------------------------------------
  // The premise.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `a minified bundle with no debug leak, no cleartext and 16 KB libraries passes`(@TempDir dir: File) {
    // Every case below is a mutation of this one. A task that threw unconditionally would satisfy
    // all of them and this suite would be reporting a gate that only ever says no.
    assertThatCode { verify(dir) }.doesNotThrowAnyException()
  }

  // -------------------------------------------------------------------------------------------
  // Minification.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `no mapping file at all is reported as an unminified release`(@TempDir dir: File) {
    // The headline case, and the one measured end to end against a real build. R8 writes no
    // mapping when it does not run, so its absence is the artifact's own statement.
    assertThatThrownBy { verify(dir, mapping = null) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("was NOT minified")
      .hasMessageContaining("carrying their own source names")
  }

  @Test
  fun `a class the mapping says was renamed, still in the DEX under its own name, fails`(@TempDir dir: File) {
    // What an artifact assembled from un-minified classes beside a fresh mapping looks like -- and
    // what a `-keep` rule silently widened to cover everything would look like if R8 still wrote a
    // mapping for it.
    assertThatThrownBy { verify(dir, dexTypes = dexTypes + descriptor(renamedClass)) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("did not come out of R8")
      .hasMessageContaining(renamedClass)
  }

  @Test
  fun `a class the mapping says was kept, missing from the DEX, fails`(@TempDir dir: File) {
    // The control assertion, tested as an assertion rather than trusted as one: without it, every
    // "this name is absent" check above is satisfied by a DEX reader that returns nothing.
    assertThatThrownBy { verify(dir, dexTypes = dexTypes - descriptor(keptClass)) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("describe different builds")
      .hasMessageContaining(keptClass)
  }

  @Test
  fun `a renamed class missing from the DEX under its new name fails`(@TempDir dir: File) {
    // The stale-mapping case: `mapping.txt` from a previous build sitting beside a DEX it does not
    // describe. Neither file is wrong on its own; only holding them against each other sees it.
    assertThatThrownBy { verify(dir, dexTypes = dexTypes - "La1;") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("does not describe this artifact")
      .hasMessageContaining("$renamedClass -> a1")
  }

  @Test
  fun `an app class in the DEX that the mapping never mentions fails`(@TempDir dir: File) {
    // A class that reached the artifact around R8 rather than through it. Distinct from the
    // renamed-original case: this name is in no mapping line at all, so a check written only
    // against the renamed set would wave it through.
    assertThatThrownBy { verify(dir, dexTypes = dexTypes + "Lio/github/helios57/muplay/Injected;") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("not in mapping.txt's kept set")
      .hasMessageContaining("io.github.helios57.muplay.Injected")
  }

  @Test
  fun `a mapping that renames nothing is not a minified build`(@TempDir dir: File) {
    // `-keep class io.github.helios57.muplay.** { *; }` pasted in to fix a reflection crash. R8 runs, a mapping
    // is written, and the shipped artifact carries every one of this application's own names.
    val keepEverything = """
      io.github.helios57.muplay.MainActivity -> io.github.helios57.muplay.MainActivity:
      io.github.helios57.muplay.library.LibraryScreenKt -> io.github.helios57.muplay.library.LibraryScreenKt:
    """.trimIndent()
    assertThatThrownBy {
      verify(dir, mapping = keepEverything, dexTypes = dexTypes + descriptor(renamedClass))
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("R8 ran, and minified nothing")
  }

  @Test
  fun `a mapping naming no class of this application is refused rather than believed`(@TempDir dir: File) {
    // Vacuity from the other end: a mapping for a different module, or an `applicationPackage`
    // that no longer matches, leaves every set below empty and every check trivially satisfied.
    assertThatThrownBy {
      verify(dir, mapping = "androidx.room.RoomDatabase -> androidx.room.RoomDatabase:", dexTypes = dexTypes - descriptor(keptClass) - "La1;" - "Lb2;")
    }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("names no io.github.helios57.muplay class at all")
  }

  @Test
  fun `a DEX the reader cannot see type names in is refused rather than passed`(@TempDir dir: File) {
    // The proof of life. If [DexTypes] ever stops returning descriptors -- a format change, an
    // off-by-one in the header offsets -- every absence check in this task becomes vacuously true,
    // and this is the only thing that notices.
    assertThatThrownBy { verify(dir, dexTypes = dexTypes - "Landroid/os/Bundle;") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("is not seeing type names")
      .hasMessageContaining("vacuous")
  }

  // -------------------------------------------------------------------------------------------
  // Debug-only types.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `a debug-only entry point in the release program fails, even when R8 renamed it`(@TempDir dir: File) {
    // **The case that matters, and the reason the mapping is searched and not only the DEX.** A
    // Hilt @EntryPoint that leaked into the release graph is an ordinary class: R8 renames it like
    // any other, so it is in the artifact under a name like `c3` and appears nowhere in the DEX
    // under its own. `mapping.txt` is the only place the original name still exists.
    val leaked = "$mapping\n$debugOnlyClass -> c3:"
    assertThatThrownBy { verify(dir, mapping = leaked, dexTypes = dexTypes + "Lc3;") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining(debugOnlyClass)
      .hasMessageContaining("pull a binding")
  }

  @Test
  fun `a type declared in both a debug and a release source set is not a leak`(@TempDir dir: File) {
    // `CleartextPolicyModule` is declared in `src/debug` *and* in `src/release`, and the release
    // build legitimately contains that name. Asserted rather than assumed, because the naive
    // spelling of the rule above -- "no name declared under src/debug" -- fails here on the very
    // file pair this repository uses to prove build-type source sets work.
    val withTwin = "$mapping\nio.github.helios57.muplay.di.CleartextPolicyModule -> d4:"
    assertThatCode { verify(dir, mapping = withTwin, dexTypes = dexTypes + "Ld4;") }
      .doesNotThrowAnyException()
  }

  @Test
  fun `a debug source set that declares nothing is refused rather than passed`(@TempDir dir: File) {
    assertThatThrownBy { verify(dir, debugSources = emptyMap()) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("would be checking nothing")
  }

  @Test
  fun `a debug source set that declares nothing of its own is refused rather than passed`(@TempDir dir: File) {
    // Everything under `src/debug` also under `src/main`: the subtraction leaves nothing, so the
    // leak check would pass for every artifact ever built.
    assertThatThrownBy { verify(dir, nonDebugSources = nonDebugSources + debugSources) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("no type is debug-only")
  }

  // -------------------------------------------------------------------------------------------
  // The manifest inside the bundle.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `cleartext in the bundle manifest fails`(@TempDir dir: File) {
    assertThatThrownBy { verify(dir, manifest = manifest + "android:usesCleartextTraffic=\"true\"") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("usesCleartextTraffic")
      .hasMessageContaining("plain HTTP to a remote host")
  }

  @Test
  fun `a debuggable release is refused`(@TempDir dir: File) {
    assertThatThrownBy { verify(dir, manifest = manifest + "android:debuggable=\"true\"") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("debuggable")
  }

  @Test
  fun `a manifest whose attribute names this scan cannot read is refused rather than passed`(@TempDir dir: File) {
    // The control for the two cases above. The bundle's manifest is protobuf; if aapt2 ever stops
    // storing attribute names as readable UTF-8, "the forbidden string is absent" becomes true of
    // every artifact and the gate silently stops existing.
    assertThatThrownBy { verify(dir, manifest = "\u0000\u0001\u0002 an encoding this scan cannot read") }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("no longer stores attribute names as readable UTF-8")
  }

  // -------------------------------------------------------------------------------------------
  // 16 KB pages.
  // -------------------------------------------------------------------------------------------

  @Test
  fun `a 64-bit library aligned to 4 KB fails`(@TempDir dir: File) {
    val fourKilobytes = libraries + ("base/lib/x86_64/liblegacy.so" to Elf(is64Bit = true, alignments = listOf(0x1000L)))
    assertThatThrownBy { verify(dir, libraries = fourKilobytes) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("cannot be loaded on a device with a 16 KB memory page")
      .hasMessageContaining("liblegacy.so")
  }

  @Test
  fun `a 32-bit library aligned to 4 KB passes`(@TempDir dir: File) {
    // Play's requirement is about 64-bit ABIs; a 32-bit library cannot run on a 16 KB device at
    // all. Pinned so the check cannot be quietly widened into one that fails every build.
    val legacy = libraries + ("base/lib/x86/liblegacy.so" to Elf(is64Bit = false, alignments = listOf(0x1000L)))
    assertThatCode { verify(dir, libraries = legacy) }.doesNotThrowAnyException()
  }

  @Test
  fun `a library whose load segments cannot be read is refused rather than passed`(@TempDir dir: File) {
    val unreadable = libraries + ("base/lib/x86_64/libempty.so" to Elf(is64Bit = true, alignments = emptyList()))
    assertThatThrownBy { verify(dir, libraries = unreadable) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("no PT_LOAD segment")
  }

  @Test
  fun `a bundle with no dex is refused`(@TempDir dir: File) {
    assertThatThrownBy { verify(dir, dexTypes = null) }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("no base/dex")
  }

  // -------------------------------------------------------------------------------------------
  // The harness.
  // -------------------------------------------------------------------------------------------

  private data class Elf(val is64Bit: Boolean, val alignments: List<Long>)

  private fun descriptor(binaryName: String) = "L${binaryName.replace('.', '/')};"

  /**
   * Builds a synthetic `.aab` and runs the real `@TaskAction` over it.
   *
   * `ProjectBuilder` and the real task class, for [VerifyMergedManifestTaskTest]'s reason: the
   * inputs are Gradle-generated properties, so letting Gradle build the instance is both the only
   * way to get one and the only way to be sure it is the class the build runs.
   */
  private fun verify(
    dir: File,
    dexTypes: List<String>? = this.dexTypes,
    mapping: String? = this.mapping,
    manifest: String = this.manifest,
    libraries: Map<String, Elf> = this.libraries,
    debugSources: Map<String, String> = this.debugSources,
    nonDebugSources: Map<String, String> = this.nonDebugSources,
  ) {
    val aab = File(dir, "app-release.aab")
    ZipOutputStream(aab.outputStream()).use { zip ->
      fun entry(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
      if (dexTypes != null) entry("base/dex/classes.dex", dex(dexTypes))
      entry(VerifyReleaseArtifactTask.BUNDLE_MANIFEST_ENTRY, manifest.toByteArray())
      libraries.forEach { (name, library) -> entry(name, elf(library)) }
    }

    fun sources(name: String, files: Map<String, String>): List<File> =
      files.map { (fileName, text) ->
        File(dir, "$name/$fileName").apply { parentFile.mkdirs(); writeText(text) }
      }

    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    val task = project.tasks.register("verifyTestArtifact", VerifyReleaseArtifactTask::class.java).get()
    task.bundle.set(aab)
    if (mapping != null) {
      task.mapping.from(File(dir, "mapping/mapping.txt").apply { parentFile.mkdirs(); writeText(mapping) })
    }
    task.debugSources.from(sources("debug", debugSources))
    task.nonDebugSources.from(sources("main", nonDebugSources))
    task.applicationPackage.set("io.github.helios57.muplay")
    task.forbiddenManifestAttributes.set(forbidden)
    task.requiredManifestAttributes.set(required)
    task.dexProbeDescriptor.set("Landroid/os/Bundle;")
    task.report.set(File(dir, "report.txt"))
    task.verify()
  }

  /**
   * A DEX file holding exactly [types] in its type table, and nothing else.
   *
   * Only the four header fields [DexTypes] reads are filled in — `string_ids` at 56/60 and
   * `type_ids` at 64/68 — followed by the two tables and the string data. Every type is its own
   * string, so the two indexes coincide.
   */
  private fun dex(types: List<String>): ByteArray {
    val headerSize = 0x70
    val stringIdsOff = headerSize
    val typeIdsOff = stringIdsOff + 4 * types.size
    val dataOff = typeIdsOff + 4 * types.size

    val data = ByteArrayOutputStream()
    val stringOffsets = types.map { type ->
      val offset = dataOff + data.size()
      // ULEB128 utf16_size; every descriptor here is short ASCII, so one byte.
      check(type.length < 0x80) { "the fixture's descriptors are short by construction" }
      data.write(type.length)
      data.write(type.toByteArray())
      data.write(0)
      offset
    }

    val bytes = ByteArray(dataOff + data.size())
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
    buffer.putInt(56, types.size)
    buffer.putInt(60, stringIdsOff)
    buffer.putInt(64, types.size)
    buffer.putInt(68, typeIdsOff)
    stringOffsets.forEachIndexed { index, offset -> buffer.putInt(stringIdsOff + 4 * index, offset) }
    types.indices.forEach { buffer.putInt(typeIdsOff + 4 * it, it) }
    data.toByteArray().copyInto(bytes, dataOff)
    return bytes
  }

  /** An ELF shared object with [Elf.alignments] `PT_LOAD` program headers and nothing else. */
  private fun elf(library: Elf): ByteArray {
    val entrySize = if (library.is64Bit) 0x38 else 0x20
    val headerOffset = 0x40
    val bytes = ByteArray(headerOffset + entrySize * maxOf(library.alignments.size, 1))
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()).copyInto(bytes)
    bytes[4] = if (library.is64Bit) 2 else 1
    bytes[5] = 1 // little-endian
    if (library.is64Bit) {
      buffer.putLong(0x20, headerOffset.toLong())
      buffer.putShort(0x36, entrySize.toShort())
      buffer.putShort(0x38, library.alignments.size.toShort())
    } else {
      buffer.putInt(0x1C, headerOffset)
      buffer.putShort(0x2A, entrySize.toShort())
      buffer.putShort(0x2C, library.alignments.size.toShort())
    }
    library.alignments.forEachIndexed { index, alignment ->
      val entry = headerOffset + index * entrySize
      buffer.putInt(entry, 1) // PT_LOAD
      if (library.is64Bit) buffer.putLong(entry + 0x30, alignment) else buffer.putInt(entry + 0x1C, alignment.toInt())
    }
    return bytes
  }

  @Test
  fun `the synthetic DEX and ELF fixtures are read the way a real one is`() {
    // The harness's own premise. A `dex()` that produced bytes [DexTypes] happens to read as an
    // empty table would make half of this file pass for the wrong reason -- and the assertions
    // above are mostly about names being *absent*, which is exactly what an empty table gives.
    assertThat(DexTypes.descriptors(dex(dexTypes))).containsExactlyElementsOf(dexTypes)
    val library = ElfAlignment.read(elf(Elf(is64Bit = true, alignments = listOf(0x4000L, 0x1000L))))
    assertThat(library.is64Bit).isTrue()
    assertThat(library.alignments).containsExactly(0x4000L, 0x1000L)
    assertThat(ElfAlignment.read(elf(Elf(is64Bit = false, alignments = listOf(0x4000L)))).is64Bit).isFalse()
  }
}
