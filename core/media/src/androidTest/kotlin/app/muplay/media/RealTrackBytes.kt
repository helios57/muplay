package app.muplay.media

import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Real audio, off the real container, through the real stream URL.
 *
 * Not a fixture copied into `src/androidTest/assets`: `ci/seed-fixtures.sh` builds those four
 * files and `ci/fixtures.md5` pins them, and a second copy is a second thing to keep in sync that
 * nothing checks. `.github/workflows/e2e.yml` starts the container and `ci/prepare-emulator.sh`
 * sets up `adb reverse tcp:4533 tcp:4533`, so `http://localhost:4533` reaches it from the guest.
 *
 * One helper for the whole module rather than a private copy per test class: two ways to fetch the
 * same bytes is two things to keep pointing at the same container.
 *
 * **One client, and the fixtures fetched once.** Every method here used to build a fresh
 * `OkHttpClient` *and* a fresh `SubsonicClient` (which builds a second `OkHttpClient` of its own
 * inside Retrofit) per call, from a `@Before` that runs per test method: twenty-two real HTTP
 * fetches for one run of [MediaCacheTest], and as many connection pools and dispatcher thread
 * pools, none of them ever shut down. The bytes cannot change while a suite runs -- the container
 * is seeded once and this project's own note forbids reseeding it underneath a live run -- so
 * they are fetched once per process and handed out afterwards. Deliberately not thread-safe:
 * instrumented tests run their lifecycle methods on one thread, and a lock here would be
 * machinery for a concurrency that does not exist.
 */
object RealTrackBytes {

  /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
  const val NAVIDROME_URL = "http://localhost:4533"
  const val MUSIC_LIBRARY_ID = 1

  private val http: OkHttpClient by lazy { OkHttpClient() }

  private val client: SubsonicClient by lazy {
    SubsonicClient(
      SubsonicCredentials(baseUrl = NAVIDROME_URL, username = "admin", password = "testpass"),
    )
  }

  private var tracks: List<Song>? = null
  private val bytesById = mutableMapOf<String, ByteArray>()

  /** The three seeded music tracks, in title order — "Track 1", "Track 2", "Track 3". */
  suspend fun musicTracks(): List<Song> =
    tracks ?: client.getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = SubsonicClient.MAX_RANDOM_SONGS)
      .sortedBy { it.title }
      .also { tracks = it }

  /** One seeded mp3 — the single track `MuPlayDataSourceFactoryTest` plays. */
  suspend fun oneMp3Track(): ByteArray =
    bytesOf(musicTracks().first { it.suffix?.lowercase() == "mp3" })

  suspend fun bytesOf(song: Song): ByteArray = bytesById.getOrPut(song.id) {
    val request = Request.Builder().url(client.streamUrl(song.id, StreamFormat.Raw)).build()
    http.newCall(request).execute().use { checkNotNull(it.body).bytes() }
  }

  /** Two genuinely different tracks' bytes — the pair [MediaCacheTest] needs for its control. */
  suspend fun twoDifferentTracks(): Pair<ByteArray, ByteArray> {
    val tracks = musicTracks()
    check(tracks.size >= 2) {
      "the seeded music library must hold at least two tracks, found ${tracks.size}"
    }
    return bytesOf(tracks[0]) to bytesOf(tracks[1])
  }
}
