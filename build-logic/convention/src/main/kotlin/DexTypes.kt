import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the **type descriptor table** out of a DEX file.
 *
 * This exists because every other way of asking "is the shipped code minified?" asks something
 * else. `isMinifyEnabled` is a line in a build script. A `mapping.txt` on disk is a claim by the
 * tool that produced it. `usage.txt` is a claim about what was removed. The only artefact that is
 * actually installed on a phone is `classes.dex`, and the only question that matters is which type
 * names are in it — so [descriptors] parses that file rather than trusting anything that describes
 * it. See [VerifyReleaseArtifactTask] for what is done with the answer.
 *
 * ### Why a parser rather than a substring search over the bytes
 *
 * A `bytes.contains("Lapp/muplay/MainActivity;")` scan can answer "is this name present" and
 * nothing else. The assertion that carries the weight is the *census* one — **every** `app.muplay`
 * type in the shipped dex must be one R8 said it deliberately kept — and that needs the full list,
 * not a membership test against names guessed in advance. A rule that can only ask about names
 * somebody wrote down cannot see the class that was added last week, which is the exact failure
 * mode `ConventionTest`'s own `nothing a release build compiles names CleartextPolicy Allowed`
 * was written to fix.
 *
 * ### The format, which is fixed and tiny
 *
 * DEX's header is a fixed-layout little-endian struct (`dex_file.h`, unchanged since DEX 035):
 * `string_ids_size`/`string_ids_off` at byte 56/60 and `type_ids_size`/`type_ids_off` at 64/68. A
 * `string_id_item` is a 4-byte offset to a `string_data_item`, which is a ULEB128 UTF-16 length
 * followed by MUTF-8 bytes terminated by NUL. A `type_id_item` is a 4-byte index into the string
 * table. That is the whole of what is read here — no instructions, no class definitions, no
 * annotations — which is why this is fifty lines instead of a dependency on a bytecode library.
 *
 * Type descriptors are JVM-shaped: `Lapp/muplay/MainActivity;`, `Ljava/lang/String;`, `[I`.
 * The table holds every type the file *references*, not only the ones it defines, which is what
 * makes a framework name like `Landroid/os/Bundle;` usable as a proof-of-life control.
 */
internal object DexTypes {

  /** Every type descriptor in [dex], in table order. */
  fun descriptors(dex: ByteArray): List<String> {
    require(dex.size > HEADER_SIZE && dex.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) {
      "not a DEX file (${dex.size} bytes, first four are ${dex.take(4)})"
    }
    val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
    val stringIdsSize = buffer.getInt(STRING_IDS_SIZE)
    val stringIdsOff = buffer.getInt(STRING_IDS_OFF)
    val typeIdsSize = buffer.getInt(TYPE_IDS_SIZE)
    val typeIdsOff = buffer.getInt(TYPE_IDS_OFF)

    val strings = arrayOfNulls<String>(stringIdsSize)
    fun stringAt(index: Int): String =
      strings[index] ?: readString(dex, buffer.getInt(stringIdsOff + 4 * index)).also { strings[index] = it }

    return (0 until typeIdsSize).map { stringAt(buffer.getInt(typeIdsOff + 4 * it)) }
  }

  /** `app.muplay.Foo.Bar` -> `Lapp/muplay/Foo$Bar;`, the shape a descriptor table holds. */
  fun descriptorOf(binaryName: String): String = "L${binaryName.replace('.', '/')};"

  /** `Lapp/muplay/MainActivity;` -> `app.muplay.MainActivity`; anything else -> null. */
  fun binaryNameOf(descriptor: String): String? =
    if (descriptor.length > 2 && descriptor.startsWith('L') && descriptor.endsWith(';')) {
      descriptor.substring(1, descriptor.length - 1).replace('/', '.')
    } else {
      null
    }

  private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"
  private const val HEADER_SIZE = 0x70
  private const val STRING_IDS_SIZE = 56
  private const val STRING_IDS_OFF = 60
  private const val TYPE_IDS_SIZE = 64
  private const val TYPE_IDS_OFF = 68

  private fun readString(dex: ByteArray, offset: Int): String {
    var index = offset
    // ULEB128 utf16_size, discarded: the data is NUL-terminated, and every descriptor is ASCII.
    while (dex[index].toInt() and 0x80 != 0) index++
    index++
    val start = index
    while (dex[index].toInt() != 0) index++
    return String(dex, start, index - start, Charsets.UTF_8)
  }
}
