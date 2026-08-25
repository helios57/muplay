package app.muplay.network

import app.muplay.model.Album
import app.muplay.model.AlbumListType
import app.muplay.model.AlbumWithSongs
import app.muplay.model.MusicLibrary
import app.muplay.model.ScanStatus
import app.muplay.model.SearchResults
import app.muplay.model.ServerInfo
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials

/**
 * Everything the data layer asks of a Subsonic server, as one interface.
 *
 * This is a **port**, not a domain layer and not a use-case class: it declares no behaviour of
 * its own, adds no method [SubsonicClient] does not already implement, and exists for exactly one
 * reason — a test needs to be able to make a *specific call* fail at a *specific point*. The
 * sync engine's most important property is that a failure part-way through a reconcile must not
 * advance the watermark, and there is no way to make a real Navidrome fail on the fourth of
 * seven calls on demand. A hand-written fake implementing this interface can, with no mock
 * framework anywhere near the build.
 *
 * Every scoped method takes `musicFolderId` as a **non-null `Int`**. That is the structural half
 * of this project's defence against the scoping trap: Navidrome silently ignores a
 * `musicFolderId` it cannot parse and widens the response to every library with `status: "ok"`,
 * so a blank or non-numeric value is a scope leak that no response assertion can detect. An `Int`
 * rendered with `toString()` can never be blank or non-numeric.
 */
interface SubsonicSource {

  suspend fun ping(): ServerInfo

  suspend fun getMusicFolders(): List<MusicLibrary>

  suspend fun getScanStatus(): ScanStatus

  /**
   * One page of albums from one library. [size] is clamped to the protocol maximum of 500;
   * [offset] pages through. A page shorter than [size] is the last page — and a past-the-end
   * offset returns an empty list, not an error (confirmed live: the server sends
   * `"albumList2": {}`).
   */
  suspend fun getAlbumList2(
    musicFolderId: Int,
    type: AlbumListType,
    size: Int,
    offset: Int,
  ): List<Album>

  /**
   * One album and its tracks. [musicFolderId] is **not sent** — the endpoint takes only an id —
   * and exists solely to stamp the library the caller already scoped by onto the results.
   */
  suspend fun getAlbum(albumId: String, musicFolderId: Int): AlbumWithSongs

  suspend fun search3(
    query: String,
    musicFolderId: Int,
    artistCount: Int,
    albumCount: Int,
    songCount: Int,
  ): SearchResults

  /**
   * Random songs from one library — the server side of library-scoped shuffle. [size] is clamped
   * to 500, which Navidrome enforces silently.
   */
  suspend fun getRandomSongs(musicFolderId: Int, size: Int): List<Song>

  /**
   * An authenticated cover-art URL. Not `suspend`: it opens no connection, it builds a URL.
   *
   * The URL carries a **fresh salt**, so two calls for the same art produce different strings.
   * Any image loader that keys its cache on the URL will therefore never hit that cache — see
   * `:feature:library`'s `CoverArt.kt`, which supplies an explicit, art-id-derived cache key for
   * exactly this reason.
   */
  fun coverArtUrl(coverArtId: String, sizePx: Int?): String

  /**
   * An authenticated `/rest/stream` URL for one song. Not `suspend`: it opens no connection, it
   * builds a URL.
   *
   * Handed to Media3, which fetches it with **its own** HTTP stack and none of this client's
   * interceptors, so every credential has to be in the string. It carries a **fresh salt**, so two
   * calls for the same song produce different strings — see [coverArtUrl] for the same property
   * and `:core:media`'s `TrackIdCacheKeyFactory` for why that makes a URL-derived cache key
   * unusable.
   *
   * [format] is a [StreamFormat], never a `String`: the global constraints say stream requests
   * force `raw` or `mp3` and **never** Opus, and the way to enforce that is to make `opus`
   * unrepresentable rather than to check for it.
   */
  fun streamUrl(songId: String, format: StreamFormat): String
}

/**
 * Builds a [SubsonicSource] for a given set of credentials.
 *
 * A factory rather than an injectable singleton because the credentials are not known until the
 * user types them, and they change when the user signs into a different server. Repositories
 * inject this and the credential store, and build a source per operation.
 */
fun interface SubsonicSourceFactory {
  fun create(credentials: SubsonicCredentials): SubsonicSource
}

/** The production factory: a real [SubsonicClient], with its real Retrofit stack. */
object DefaultSubsonicSourceFactory : SubsonicSourceFactory {
  override fun create(credentials: SubsonicCredentials): SubsonicSource = SubsonicClient(credentials)
}
