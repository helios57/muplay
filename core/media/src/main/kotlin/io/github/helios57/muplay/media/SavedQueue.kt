package io.github.helios57.muplay.media

import io.github.helios57.muplay.model.ReplayGain
import io.github.helios57.muplay.model.Song
import io.github.helios57.muplay.model.SongRating
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The play queue as it stood when it was last saved: what [QueueStore] writes and what the
 * service restores after the system has reclaimed it.
 *
 * [repeatMode] is Media3's `Player.REPEAT_MODE_*` value, kept as the plain `Int` it is so this type
 * stays free of Media3 and the codec can be held on the fast tier.
 */
data class SavedQueue(
  val songs: List<Song>,
  val currentIndex: Int,
  val positionMs: Long,
  val repeatMode: Int,
  val shuffle: Boolean,
)

/**
 * [SavedQueue] to and from one JSON document.
 *
 * Hand-built over `JsonElement` rather than generated, so `:core:model`'s `Song` does not have to
 * become `@Serializable` (and take the compiler plugin) for the sake of one file. The format is
 * versioned; a document of any other version reads as "no saved queue", which costs a user one
 * queue rather than a crash on start-up.
 *
 * **Ids and metadata only.** No stream URL, which carries password-equivalent query parameters;
 * the URL is rebuilt from the stored credentials when the queue comes back.
 */
object SavedQueueCodec {

  private const val VERSION = 1

  private val json = Json { ignoreUnknownKeys = true }

  fun encode(queue: SavedQueue): String = buildJsonObject {
    put("version", VERSION)
    put("currentIndex", queue.currentIndex)
    put("positionMs", queue.positionMs)
    put("repeatMode", queue.repeatMode)
    put("shuffle", queue.shuffle)
    put("songs", buildJsonArray { queue.songs.forEach { add(encodeSong(it)) } })
  }.toString()

  fun decode(text: String): SavedQueue? {
    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (root.int("version") != VERSION) return null
    val raw = root["songs"] as? JsonArray ?: return null
    val savedIndex = root.int("currentIndex") ?: 0

    // A song that does not decode is dropped, and the index moved to keep pointing at the same
    // song where it still can -- one damaged entry should not cost the whole queue.
    val songs = mutableListOf<Song>()
    var index = 0
    raw.forEachIndexed { position, element ->
      val song = (element as? JsonObject)?.let(::decodeSong) ?: return@forEachIndexed
      if (position <= savedIndex) index = songs.size
      songs += song
    }
    if (songs.isEmpty()) return null
    return SavedQueue(
      songs = songs,
      currentIndex = index.coerceIn(songs.indices),
      positionMs = (root.long("positionMs") ?: 0L).coerceAtLeast(0L),
      repeatMode = root.int("repeatMode") ?: 0,
      shuffle = root.bool("shuffle") ?: false,
    )
  }

  private fun encodeSong(song: Song): JsonObject = buildJsonObject {
    put("id", song.id)
    put("libraryId", song.libraryId)
    put("title", song.title)
    song.albumId?.let { put("albumId", it) }
    song.albumName?.let { put("albumName", it) }
    song.artistId?.let { put("artistId", it) }
    song.artistName?.let { put("artistName", it) }
    song.trackNumber?.let { put("trackNumber", it) }
    song.discNumber?.let { put("discNumber", it) }
    put("durationSeconds", song.durationSeconds)
    song.suffix?.let { put("suffix", it) }
    song.coverArtId?.let { put("coverArtId", it) }
    song.path?.let { put("path", it) }
    song.replayGain?.let { gain ->
      gain.trackGainDb?.let { put("trackGainDb", it) }
      gain.albumGainDb?.let { put("albumGainDb", it) }
      gain.peakAmplitude?.let { put("peakAmplitude", it) }
    }
    put("rating", song.rating.name)
  }

  private fun decodeSong(o: JsonObject): Song? {
    val id = o.string("id") ?: return null
    val libraryId = o.int("libraryId") ?: return null
    val title = o.string("title") ?: return null
    val track = o.float("trackGainDb")
    val album = o.float("albumGainDb")
    val peak = o.float("peakAmplitude")
    return Song(
      id = id,
      libraryId = libraryId,
      title = title,
      albumId = o.string("albumId"),
      albumName = o.string("albumName"),
      artistId = o.string("artistId"),
      artistName = o.string("artistName"),
      trackNumber = o.int("trackNumber"),
      discNumber = o.int("discNumber"),
      durationSeconds = o.int("durationSeconds") ?: 0,
      suffix = o.string("suffix"),
      coverArtId = o.string("coverArtId"),
      replayGain = if (track == null && album == null && peak == null) null else ReplayGain(track, album, peak),
      path = o.string("path"),
      rating = SongRating.entries.firstOrNull { it.name == o.string("rating") } ?: SongRating.Neutral,
    )
  }

  private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

  /** A JSON string only: `"id": 17` is not an id, and reading it as `"17"` would invent one. */
  private fun JsonObject.string(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content

  private fun JsonObject.int(key: String): Int? = primitive(key)?.takeIf { !it.isString }?.intOrNull

  private fun JsonObject.long(key: String): Long? =
    primitive(key)?.takeIf { !it.isString }?.longOrNull

  private fun JsonObject.float(key: String): Float? =
    primitive(key)?.takeIf { !it.isString }?.floatOrNull

  private fun JsonObject.bool(key: String): Boolean? =
    primitive(key)?.takeIf { !it.isString }?.booleanOrNull
}
