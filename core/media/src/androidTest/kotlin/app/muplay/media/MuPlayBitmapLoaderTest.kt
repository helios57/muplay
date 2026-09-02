package app.muplay.media

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.net.toUri
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The half of the credential fix that has to keep working, against a real server.**
 *
 * Removing the authenticated cover URL from every `MediaItem` is only half a change: the other half
 * is that a listener still sees artwork on the lock screen, in the notification, in Android Auto
 * and on Wear. None of those fetches a URI -- `MediaSessionLegacyStub` and
 * `DefaultMediaNotificationProvider` both ask the session's `BitmapLoader` for a `Bitmap` -- so this
 * class is the entire mechanism by which the picture survives.
 *
 * It is therefore tested against **the real Navidrome container**, on a real cover of a real seeded
 * album, decoding real bytes into a real `Bitmap`. `MuPlayBitmapLoader` measured 0/17 lines when it
 * was first written, which is the shape this repository already has a name for: a component nobody
 * has seen work. A stub upstream would have reproduced that state with more ceremony.
 *
 * The credentialed URL exists only inside this process, for the length of one `GET`. Nothing here
 * asserts on it, prints it, or puts it in a message.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MuPlayBitmapLoaderTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var storeFile: File? = null

  @After
  fun tearDown() {
    scope.cancel()
    storeFile?.delete()
  }

  @Test
  fun aCoverArtUriBecomesTheRealCoverOfTheRealAlbum(): Unit = runBlocking {
    val song = RealTrackBytes.musicTracks().first { it.coverArtId != null }

    val bitmap = loader().loadBitmap(ArtworkUri.of(song.coverArtId)!!.toUri()).get(TIMEOUT_S, TimeUnit.SECONDS)

    // A decoded image, not merely a non-null future: a loader that returned a 1x1 placeholder --
    // or an error document decoded as a bitmap -- would pass a null check and show a grey square.
    assertThat(bitmap.width).isGreaterThan(1)
    assertThat(bitmap.height).isGreaterThan(1)
  }

  @Test
  fun theMetadataPathMedia3ActuallyUsesResolvesTheSameWay(): Unit = runBlocking {
    // `MediaSessionLegacyStub` calls `loadBitmapFromMetadata`, not `loadBitmap`, and the default
    // implementation of the first is what routes to the second. Driving the entry point Media3
    // really uses is the difference between testing this class and testing the thing that runs.
    val song = RealTrackBytes.musicTracks().first { it.coverArtId != null }
    val metadata = MediaMetadata.Builder()
      .setArtworkUri(ArtworkUri.of(song.coverArtId)!!.toUri())
      .build()

    val bitmap = loader().loadBitmapFromMetadata(metadata)!!.get(TIMEOUT_S, TimeUnit.SECONDS)

    assertThat(bitmap.width).isGreaterThan(1)
  }

  @Test
  fun aUriThisLoaderDoesNotRecogniseGoesToTheDelegateUntouched(): Unit = runBlocking {
    // An item this app did not build, or a browse item's own URL. Handed on rather than guessed at:
    // rewriting one would be this class deciding what somebody else's URI means.
    val delegate = RecordingDelegate()
    val loader = loader(delegate)
    val foreign = "https://example.invalid/cover.jpg".toUri()

    runCatching { loader.loadBitmap(foreign).get(TIMEOUT_S, TimeUnit.SECONDS) }

    assertThat(delegate.requested).containsExactly(foreign.toString())
  }

  @Test
  fun aCoverArtUriDoesNotGoToTheDelegate(): Unit = runBlocking {
    // The other arm, so the case above cannot be satisfied by a loader that delegates everything.
    val song = RealTrackBytes.musicTracks().first { it.coverArtId != null }
    val delegate = RecordingDelegate()

    loader(delegate).loadBitmap(ArtworkUri.of(song.coverArtId)!!.toUri()).get(TIMEOUT_S, TimeUnit.SECONDS)

    assertThat(delegate.requested).isEmpty()
  }

  @Test
  fun anIdTheServerHasNoCoverForFailsRatherThanReturningNothing(): Unit = runBlocking {
    // Media3 treats a failed future as "no artwork" and a null `Bitmap` as a contract violation, so
    // the distinction is not cosmetic. A `null` here would be an `IllegalStateException` on the
    // session's own handler.
    val future = loader().loadBitmap(ArtworkUri.of("no-such-cover-at-all")!!.toUri())

    assertThatExceptionOfType(ExecutionException::class.java)
      .isThrownBy { future.get(TIMEOUT_S, TimeUnit.SECONDS) }
  }

  // ---- harness -----------------------------------------------------------------------------------

  private fun loader(delegate: BitmapLoader = DataSourceBitmapLoader(context)): MuPlayBitmapLoader {
    val (provider, file) = fixedSubsonicSourceProvider(
      context,
      SubsonicClient(
        SubsonicCredentials(
          baseUrl = RealTrackBytes.NAVIDROME_URL,
          username = "admin",
          password = "testpass",
        ),
      ),
      baseUrl = RealTrackBytes.NAVIDROME_URL,
    )
    storeFile = file
    return MuPlayBitmapLoader(
      artworkUrls = ArtworkUrls(provider),
      callFactory = OkHttpClient(),
      delegate = delegate,
      scope = scope,
    )
  }

  /** A delegate that records rather than loads, so "was this handed on" is observable. */
  private class RecordingDelegate : BitmapLoader {
    val requested = mutableListOf<String>()

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
      SettableFuture.create<Bitmap>().also { it.setException(UnsupportedOperationException()) }

    override fun loadBitmap(uri: android.net.Uri): ListenableFuture<Bitmap> {
      requested += uri.toString()
      return SettableFuture.create<Bitmap>().also { it.setException(UnsupportedOperationException()) }
    }
  }

  private companion object {
    /** Generous: this fetches a real image over a real socket, on an emulator under load. */
    const val TIMEOUT_S = 30L
  }
}
