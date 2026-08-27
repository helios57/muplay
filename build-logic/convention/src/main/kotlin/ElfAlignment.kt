import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `p_align` of every `PT_LOAD` program header in an ELF shared object.
 *
 * Google Play requires that an app's **64-bit** native libraries be loadable on devices with a
 * 16 KB memory page, and what that actually means is that every loadable segment is aligned to at
 * least 16384 bytes. A library built for 4 KB pages installs and runs everywhere today and simply
 * fails to load on a 16 KB device — no build error, no lint warning, no crash on any emulator this
 * project runs. Plan 8 lists it as "a verification step, not a port", and this is the verification:
 * [VerifyReleaseArtifactTask] reads it out of the `.aab` that is about to be uploaded.
 *
 * MuPlay writes no native code. It ships eight `.so` files anyway, all from AndroidX — measured on
 * the 0.2.0 bundle: `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`, for
 * `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. That is exactly why the check is worth
 * mechanising rather than reasoning about: nothing in this repository's source implies a native
 * library exists at all, so the set is entirely determined by dependency upgrades nobody reviews
 * for page alignment.
 *
 * ### Format
 *
 * `e_ident[EI_CLASS]` at byte 4 is 1 for ELF32 and 2 for ELF64, and it changes the offsets of
 * everything after it. For ELF64: `e_phoff` (8 bytes) at 0x20, `e_phentsize` (2) at 0x36,
 * `e_phnum` (2) at 0x38; each program header is `p_type` (4) at +0x00 and `p_align` (8) at +0x30.
 * For ELF32: `e_phoff` (4) at 0x1C, `e_phentsize` at 0x2A, `e_phnum` at 0x2C; each header is
 * `p_type` at +0x00 and `p_align` (4) at +0x1C. `PT_LOAD` is 1.
 *
 * Little-endian is assumed and checked: every ABI Android supports is little-endian, and reading a
 * big-endian file with these offsets would produce nonsense alignments that happen to pass or fail
 * at random — which is worse than refusing.
 */
internal object ElfAlignment {

  /** The page size Play requires 64-bit libraries to tolerate. */
  const val REQUIRED_PAGE_SIZE = 16 * 1024

  /** What [read] found: whether the file is 64-bit, and the `p_align` of each `PT_LOAD`. */
  data class LoadSegments(val is64Bit: Boolean, val alignments: List<Long>)

  fun read(elf: ByteArray): LoadSegments {
    require(elf.size > 64 && elf.copyOfRange(0, 4).contentEquals(ELF_MAGIC)) {
      "not an ELF file (${elf.size} bytes)"
    }
    val is64Bit = when (val elfClass = elf[4].toInt()) {
      1 -> false
      2 -> true
      else -> error("unknown ELF class $elfClass")
    }
    require(elf[5].toInt() == 1) { "not a little-endian ELF file (EI_DATA=${elf[5].toInt()})" }

    val buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
    val headerOffset: Long
    val entrySize: Int
    val count: Int
    if (is64Bit) {
      headerOffset = buffer.getLong(0x20)
      entrySize = buffer.getShort(0x36).toInt() and 0xFFFF
      count = buffer.getShort(0x38).toInt() and 0xFFFF
    } else {
      headerOffset = buffer.getInt(0x1C).toLong() and 0xFFFFFFFFL
      entrySize = buffer.getShort(0x2A).toInt() and 0xFFFF
      count = buffer.getShort(0x2C).toInt() and 0xFFFF
    }

    val alignments = (0 until count).mapNotNull { index ->
      val entry = (headerOffset + index.toLong() * entrySize).toInt()
      if (buffer.getInt(entry) != PT_LOAD) {
        null
      } else if (is64Bit) {
        buffer.getLong(entry + 0x30)
      } else {
        buffer.getInt(entry + 0x1C).toLong() and 0xFFFFFFFFL
      }
    }
    return LoadSegments(is64Bit, alignments)
  }

  private val ELF_MAGIC = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
  private const val PT_LOAD = 1
}
