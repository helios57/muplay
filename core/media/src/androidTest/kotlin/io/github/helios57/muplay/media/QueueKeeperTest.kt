package io.github.helios57.muplay.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.helios57.muplay.model.Song
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [QueueKeeper] over a real player: what it writes, and -- the half that matters most -- what it
 * refuses to overwrite. The queue used to live only in the player, so "Nothing playing" after the
 * system reclaimed a paused service was a lost queue; this file is where it now survives.
 *
 * Nothing here prepares the player, so the items' URIs are never fetched.
 */
@RunWith(AndroidJUnit4::class)
class QueueKeeperTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val file = File(context.cacheDir, "queue-keeper-test.json")
  private val store = QueueStore(file)
  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + main.asCoroutineDispatcher())

  private val songs = (1..4).map { song("s$it") }
  private val byId = songs.associateBy { it.id }

  private lateinit var harness: PlayerHarness
  private lateinit var keeper: QueueKeeper
  private lateinit var cache: androidx.media3.datasource.cache.Cache

  @Before
  fun setUp() {
    file.delete()
    // Through `MuPlayerFactory`: `PlayerConstructionTest` refuses any other construction site.
    cache = MediaCache.create(context, File(context.cacheDir, "queue-keeper-${System.nanoTime()}"))
    val player = onMain {
      MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
      ).createExoPlayer()
    }
    harness = PlayerHarness(player)
    keeper = QueueKeeper(store, byId::get, scope)
    onMain { keeper.attach(player) }
  }

  @After
  fun tearDown() {
    if (::keeper.isInitialized) onMain { keeper.stop() }
    if (::harness.isInitialized) harness.release()
    scope.cancel()
    file.delete()
    if (::cache.isInitialized) cache.release()
  }

  @Test
  fun theQueueIsWrittenWithItsCurrentItemRepeatAndShuffle() {
    onMain {
      keeper.restoreFinished()
      harness.player.setMediaItems(songs.map(::item), 2, 0L)
      harness.player.repeatMode = Player.REPEAT_MODE_ALL
      harness.player.shuffleModeEnabled = true
    }

    val saved = awaitSaved { it.shuffle }

    assertThat(saved.songs.map { it.id }).containsExactly("s1", "s2", "s3", "s4")
    assertThat(saved.currentIndex).isEqualTo(2)
    assertThat(saved.repeatMode).isEqualTo(Player.REPEAT_MODE_ALL)
  }

  @Test
  fun anEmptyPlayerAtStartUpDoesNotEraseTheQueueItIsAboutToRestore() {
    // The service starts with an empty player and fills it from the file a moment later. Saving
    // that empty player first would destroy exactly what it was about to put back.
    runBlocking { store.save(SavedQueue(songs, 1, 4_000, Player.REPEAT_MODE_OFF, false)) }

    onMain {
      harness.player.repeatMode = Player.REPEAT_MODE_ONE // an event, before restore finished
      keeper.restoreFinished()
    }
    Thread.sleep(QueueKeeper.SETTLE_MS * 3)

    assertThat(runBlocking { store.load() }?.songs?.map { it.id })
      .containsExactly("s1", "s2", "s3", "s4")
  }

  @Test
  fun emptyingAQueueTheListenerHadForgetsIt() {
    onMain {
      keeper.restoreFinished()
      harness.player.setMediaItems(songs.map(::item), 0, 0L)
    }
    awaitSaved { true }

    onMain { harness.player.clearMediaItems() }

    harness.await("the saved queue to be cleared") { !file.exists() }
  }

  @Test
  fun anItemThisProcessDidNotBuildIsLeftOutAndTheIndexStillPointsAtTheSameSong() {
    onMain {
      keeper.restoreFinished()
      harness.player.setMediaItems(
        listOf(item(songs[0]), MediaItem.Builder().setMediaId("stranger").setUri("x").build()) +
          songs.drop(1).map(::item),
        2,
        0L,
      )
    }

    val saved = awaitSaved { true }

    assertThat(saved.songs.map { it.id }).containsExactly("s1", "s2", "s3", "s4")
    assertThat(saved.songs[saved.currentIndex].id).isEqualTo("s2")
  }

  @Test
  fun flushingWritesAtOnceForAServiceThatIsGoingAway() {
    onMain {
      keeper.restoreFinished()
      harness.player.setMediaItems(songs.map(::item), 3, 0L)
      keeper.flushBlocking()
    }

    assertThat(runBlocking { store.load() }?.currentIndex).isEqualTo(3)
  }

  private fun awaitSaved(condition: (SavedQueue) -> Boolean): SavedQueue {
    var last: SavedQueue? = null
    harness.await("a saved queue") {
      last = runBlocking { store.load() }
      last?.let(condition) == true
    }
    return checkNotNull(last)
  }

  private fun <T> onMain(block: () -> T): T =
    InstrumentationRegistry.getInstrumentation().let { instrumentation ->
      var result: Result<T>? = null
      instrumentation.runOnMainSync { result = runCatching(block) }
      checkNotNull(result).getOrThrow()
    }

  private fun item(song: Song) = MediaItem.Builder().setMediaId(song.id).setUri("https://example.invalid/${song.id}").build()

  private fun song(id: String) = Song(
    id = id, libraryId = 1, title = "Song $id", albumId = "al", albumName = "Album",
    artistId = "ar", artistName = "Artist", trackNumber = null, discNumber = null,
    durationSeconds = 60, suffix = "mp3", coverArtId = null,
  )
}
