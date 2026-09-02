package app.muplay.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request

/**
 * **Where a credential-free artwork URI becomes a picture, without the credential ever leaving this
 * process.**
 *
 * [ArtworkUri] is the other half: `MediaItems.of` puts `muplay-art:<coverArtId>` on the item, so the
 * platform `MediaSession` -- which any app holding notification-listener access can read, without
 * ever passing [ControllerAccessPolicy]'s gate -- carries an identifier instead of the user's
 * password equivalent. This is what keeps the notification, the lock screen, Android Auto and Wear
 * showing cover art anyway.
 *
 * It works because none of those surfaces fetch the URI. Read off `media3-session-1.11.0.aar`:
 * `MediaSessionLegacyStub` calls `getBitmapLoader().loadBitmapFromMetadata(metadata)` and passes the
 * resulting `Bitmap` to `LegacyConversions.convertToMediaMetadataCompat`, which writes it as
 * `METADATA_KEY_ALBUM_ART`; `DefaultMediaNotificationProvider` loads the notification's large icon
 * the same way. A `BitmapLoader` on the session is therefore the exact seam, and it is the fix
 * [ControllerAccessPolicy]'s own KDoc named and deferred.
 *
 * ### What it does not do, deliberately
 *
 * **No caching, no size limiting, and no `Bitmap` recycling.** `MediaSession.BuilderBase` wraps
 * whatever loader it is given in `SizeLimitedBitmapLoader` and then in `CacheBitmapLoader` (and, on
 * API 29 only, in `SizeAvoidingBitmapLoader`) -- verified in the same bytecode. Re-implementing any
 * of that here would be a second policy competing with Media3's own.
 *
 * **Nothing is logged, including on failure.** The URL this fetches carries the user's `u`, `t` and
 * `s`, and `MuPlaybackService`'s own KDoc records that the reflex when an image will not load is to
 * log the URL. A failed load surfaces as Media3's own debug line and a placeholder, which is what a
 * missing cover should look like.
 *
 * @param delegate what a URI this loader does not recognise is handed to -- an ordinary `http`
 *   artwork URI on an item this app did not build, for instance. Ours are the only ones rewritten.
 * @param scope whose lifetime the resolve and the fetch belong to. **Its dispatcher is not used**:
 *   the work is moved to [Dispatchers.IO] below, because the scope the playback service supplies is
 *   main-confined and this does blocking I/O twice over.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `BitmapLoader` is `@UnstableApi`, and Media3's
// marker is an `androidx.annotation.RequiresOptIn` the Kotlin compiler cannot see at all -- without
// this the file compiles clean and `check` fails much later at `lintDebug`.
@OptIn(UnstableApi::class)
class MuPlayBitmapLoader(
  private val artworkUrls: ArtworkUrls,
  private val callFactory: Call.Factory,
  private val delegate: BitmapLoader,
  private val scope: CoroutineScope,
) : BitmapLoader {

  override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = delegate.decodeBitmap(data)

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
    // Not ours: an item this app did not build, or a browse item's own URL. Handed on untouched
    // rather than guessed at -- see `ArtworkUri.coverArtIdOf` for why `null` has to mean this.
    if (ArtworkUri.coverArtIdOf(uri.toString()) == null) return delegate.loadBitmap(uri)

    val future = SettableFuture.create<Bitmap>()
    // **`Dispatchers.IO`, and not by preference.** The scope this is given is
    // `MuPlaybackService.serviceScope`, which dispatches onto the **main looper** -- it exists so
    // that `ProgressWriter`'s ticker can read a `Player` on the thread the player was built on.
    // A blocking OkHttp call there is `NetworkOnMainThreadException`, and the DataStore read inside
    // `ArtworkUrls.urlFor` has no business on it either. Named here rather than left to the caller
    // because the caller's scope is main-confined for a reason that has nothing to do with this.
    scope.launch(Dispatchers.IO) {
      val url = artworkUrls.urlFor(uri.toString())
      if (url == null) {
        // Signed out, or a URI whose id is gone. `setException`, not `set(null)`: Media3 treats a
        // failed future as "no artwork" and a null Bitmap as a contract violation.
        future.setException(IOException("no artwork source for this item"))
        return@launch
      }
      runCatching { fetch(url) }
        .onSuccess { bitmap ->
          if (bitmap == null) future.setException(IOException("the artwork bytes did not decode"))
          else future.set(bitmap)
        }
        .onFailure(future::setException)
    }
    return future
  }

  /**
   * The bytes, decoded.
   *
   * `bytes()` rather than a stream into `decodeStream`, because a `BitmapFactory` decode that fails
   * partway through a network stream returns `null` with no way to tell a short read from a
   * corrupt image -- and a cover is small enough that the difference costs nothing. The response is
   * closed either way.
   *
   * No `require`, no `check`, and no message that names [url]: it carries the user's credentials,
   * and an exception message is the one string in this project that reliably reaches a bug report.
   */
  private fun fetch(url: String): Bitmap? =
    callFactory.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (!response.isSuccessful) throw IOException("the artwork request was refused")
      val bytes = response.body.bytes()
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
