package io.github.helios57.muplay.binaural

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a profile on the list came from, which decides what the editor offers for it. */
enum class ProfileOrigin { BUILT_IN, EDITED, CUSTOM }

data class ProfileEntry(val profile: BinauralProfile, val origin: ProfileOrigin)

/**
 * The file's whole content. Built-ins are not stored -- only the listener's edits of them, so a
 * later version's improved defaults reach everyone who has not changed that profile.
 */
@Serializable
data class ProfileDocument(
  val version: Int = VERSION,
  val overrides: List<BinauralProfile> = emptyList(),
  val custom: List<BinauralProfile> = emptyList(),
  val safetyAcknowledged: Boolean = false,
  val masterVolume: Double = 1.0,
) {
  val entries: List<ProfileEntry>
    get() {
      val edited = overrides.associateBy { it.id }
      return Presets.all.map { builtIn ->
        edited[builtIn.id]?.let { ProfileEntry(it, ProfileOrigin.EDITED) }
          ?: ProfileEntry(builtIn, ProfileOrigin.BUILT_IN)
      } + custom.map { ProfileEntry(it, ProfileOrigin.CUSTOM) }
    }

  fun find(id: String): ProfileEntry? = entries.firstOrNull { it.profile.id == id }

  companion object {
    const val VERSION = 1
  }
}

/**
 * **The listener's profiles, kept in one JSON file on the phone.** Nothing here leaves the device.
 *
 * A file that cannot be read -- corrupt, from a newer version, half-written by a crash -- is
 * treated as empty rather than as a reason to crash: the built-ins are all still there, and the
 * listener loses their edits rather than the feature. Writes go to a temporary file renamed over
 * the real one, so a crash mid-write leaves the previous version, never half of one.
 */
class ProfileLibrary(
  private val file: File,
  private val io: CoroutineDispatcher,
  private val newId: () -> String = { "custom-" + UUID.randomUUID() },
) {
  private val mutex = Mutex()
  private val state = MutableStateFlow(ProfileDocument())
  private var loaded = false

  val document: StateFlow<ProfileDocument> = state.asStateFlow()

  /** Reads the file the first time it is called; later calls return at once. */
  suspend fun load() {
    mutex.withLock { ensureLoaded() }
  }

  /**
   * Stores [profile]: an edit of a built-in under the built-in's id, anything else as the
   * listener's own. Refuses a profile the generator could not play.
   */
  suspend fun save(profile: BinauralProfile) = change { doc ->
    require(profile.isPlayable()) { "refusing to store an unplayable profile: ${problemsOf(profile)}" }
    if (Presets.builtIn(profile.id) != null) {
      val unchanged = Presets.builtIn(profile.id) == profile
      doc.copy(overrides = doc.overrides.filterNot { it.id == profile.id } + listOfNotNull(profile.takeUnless { unchanged }))
    } else {
      val replaced = doc.custom.any { it.id == profile.id }
      doc.copy(
        custom = if (replaced) doc.custom.map { if (it.id == profile.id) profile else it } else doc.custom + profile,
      )
    }
  }

  /** Puts a built-in back to how the app ships it. */
  suspend fun reset(id: String) = change { doc -> doc.copy(overrides = doc.overrides.filterNot { it.id == id }) }

  /** A copy of [id] as a profile of the listener's own, which is returned so the editor can open it. */
  suspend fun duplicate(id: String): BinauralProfile? {
    var copy: BinauralProfile? = null
    change { doc ->
      val source = doc.find(id)?.profile ?: return@change doc
      copy = source.copy(id = newId(), name = copyName(source.name, doc))
      doc.copy(custom = doc.custom + copy!!)
    }
    return copy
  }

  /** Deletes one of the listener's own profiles. A built-in cannot be deleted, only reset. */
  suspend fun delete(id: String) = change { doc -> doc.copy(custom = doc.custom.filterNot { it.id == id }) }

  suspend fun acknowledgeSafety() = change { it.copy(safetyAcknowledged = true) }

  suspend fun setMasterVolume(volume: Double) = change { it.copy(masterVolume = volume.coerceIn(0.0, 1.0)) }

  private fun copyName(name: String, doc: ProfileDocument): String {
    val taken = doc.entries.map { it.profile.name }.toSet()
    val base = "$name (copy)".take(Limits.MAX_NAME_LENGTH)
    return generateSequence(2) { it + 1 }.map { "$name (copy $it)".take(Limits.MAX_NAME_LENGTH) }
      .let { sequenceOf(base) + it }
      .first { it !in taken }
  }

  private suspend fun change(transform: (ProfileDocument) -> ProfileDocument) {
    mutex.withLock {
      ensureLoaded()
      val next = transform(state.value)
      if (next == state.value) return
      write(next)
      state.value = next
    }
  }

  private suspend fun ensureLoaded() {
    if (loaded) return
    state.value = withContext(io) { read() }
    loaded = true
  }

  private fun read(): ProfileDocument {
    if (!file.exists()) return ProfileDocument()
    return try {
      val doc = JSON.decodeFromString<ProfileDocument>(file.readText())
      if (doc.version != ProfileDocument.VERSION) return ProfileDocument()
      // A profile a hand-edit or an older rule let through is dropped rather than played.
      doc.copy(
        overrides = doc.overrides.filter { Presets.builtIn(it.id) != null && it.isPlayable() },
        custom = doc.custom.filter { it.isPlayable() }.distinctBy { it.id },
        masterVolume = doc.masterVolume.coerceIn(0.0, 1.0),
      )
    } catch (_: SerializationException) {
      ProfileDocument()
    } catch (_: IllegalArgumentException) {
      ProfileDocument()
    } catch (_: IOException) {
      ProfileDocument()
    }
  }

  private suspend fun write(doc: ProfileDocument) = withContext(io) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(JSON.encodeToString(ProfileDocument.serializer(), doc))
    if (!temp.renameTo(file)) {
      temp.delete()
      throw IOException("could not replace $file")
    }
  }

  private companion object {
    val JSON = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }
  }
}
