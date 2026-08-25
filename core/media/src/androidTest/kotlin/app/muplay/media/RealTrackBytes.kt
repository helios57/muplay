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
 */
object RealTrackBytes {

  /** Reached from inside the emulator via `adb reverse tcp:4533 tcp:4533` -- ci/prepare-emulator.sh. */
  const val NAVIDROME_URL = "http://localhost:4533"
  const val MUSIC_LIBRARY_ID = 1

  fun client(): SubsonicClient = SubsonicClient(
    SubsonicCredentials(baseUrl = NAVIDROME_URL, username = "admin", password = "testpass"),
  )

  /** The three seeded music tracks, in title order — "Track 1", "Track 2", "Track 3". */
  suspend fun musicTracks(): List<Song> =
    client().getRandomSongs(musicFolderId = MUSIC_LIBRARY_ID, size = MAX_LIBRARY_PAGE)
      .sortedBy { it.title }

  /** One seeded mp3 — the single track `MuPlayDataSourceFactoryTest` plays. */
  suspend fun oneMp3Track(): ByteArray =
    bytesOf(musicTracks().first { it.suffix?.lowercase() == "mp3" })

  suspend fun bytesOf(song: Song): ByteArray {
    val request = Request.Builder().url(client().streamUrl(song.id, StreamFormat.Raw)).build()
    return OkHttpClient().newCall(request).execute().use { checkNotNull(it.body).bytes() }
  }

  /** Two genuinely different tracks' bytes — the pair [MediaCacheTest] needs for its control. */
  suspend fun twoDifferentTracks(): Pair<ByteArray, ByteArray> {
    val tracks = musicTracks()
    check(tracks.size >= 2) {
      "the seeded music library must hold at least two tracks, found ${tracks.size}"
    }
    return bytesOf(tracks[0]) to bytesOf(tracks[1])
  }

  private const val MAX_LIBRARY_PAGE = 500
}
