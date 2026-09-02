package app.muplay.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.database.AudiobookRepository
import app.muplay.database.MirrorMapper
import app.muplay.database.MuPlayDatabase
import app.muplay.database.entity.LibraryEntity
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.LibraryRole
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Real audio, resuming at a real position.**
 *
 * The rule this class is built around: *a resume test that asserts the player was told to seek
 * proves nothing.* `ResumeTarget.startPositionMs` being right, `seekTo` having been called and the
 * policy having been consulted are all satisfied by a player that then ignores the answer, by a URL
 * that 404s into a swallowed error, and by a decoder that produced no sample. So every test here
 * asserts **two** things: the position playback started at, and that playback then **advanced past
 * it** -- which only a decoder that actually decoded can do.
 *
 * Positions are chosen **off every chapter boundary in their book**. Second Book's chapters start at
 * 0 / 4000 / 9000 / 15000; 17 500 is 2.5 s into the last one, so "resumed at the chapter start" and
 * "resumed exactly" are different numbers. With a position of 15 000 they would not be.
 *
 * The clock is **fixed** and each stored `lastPlayedAtEpochMs` is set relative to it, so the smart
 * rewind is an exact expected value rather than a band that drifts with how slow the emulator is.
 *
 * ### Everything below the policy is production
 *
 * A real Room mirror seeded from the real container, the real `AudiobookRepository`, the real
 * `AudiobookSnapshot`, the real `MuPlayerFactory` and therefore the real `MuPlayer` seam. The only
 * stand-in is the `Clock`, which is the one thing spec section 10's test hierarchy names as
 * legitimately fakeable.
 *
 * The plan for this task specified a shared `BookPlaybackHarness` for three suites. Two of the three
 * already exist on master and do not use one (`SleepTimerControllerTest` builds its player inline),
 * and the third is a concurrent lane's, so a "shared" harness here would have exactly one caller and
 * an add/add conflict. It also listed `RealTrackBytes.client()`, which is private -- `source()` and
 * `rawStreamUrl()` are what that object exposes -- and an `ExoPlayer.Builder`, which
 * `PlayerConstructionTest` refuses anywhere in this module, test sources included.
 */
@RunWith(AndroidJUnit4::class)
class AudiobookResumeTest {

  private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC)

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var db: MuPlayDatabase
  private lateinit var snapshot: AudiobookSnapshot
  private lateinit var songs: List<Song>
  private val harnesses = mutableListOf<PlayerHarness>()
  private val cleanups = mutableListOf<() -> Unit>()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "audiobook-resume-${System.nanoTime()}")
    db = Room.inMemoryDatabaseBuilder(context, MuPlayDatabase::class.java).build()
    runBlocking {
      val books = RealTrackBytes.audiobookFiles()
      val music = RealTrackBytes.musicTracks()
      songs = books + music
      // The real mirror, seeded from the real server, so `AudiobookRepository` reads the rows the
      // app would -- including library 2 tagged AUDIOBOOKS and library 1 tagged MUSIC. Nothing here
      // is derived from a count: `RealTrackBytes` returns whatever the shared corpus holds, and a
      // fifth fixture landing mid-run must not redden this suite.
      db.libraryDao().mergeFromServer(
        listOf(
          LibraryEntity(RealTrackBytes.MUSIC_LIBRARY_ID, "Music", LibraryRole.UNASSIGNED),
          LibraryEntity(RealTrackBytes.AUDIOBOOK_LIBRARY_ID, "Audiobooks", LibraryRole.UNASSIGNED),
        ),
      )
      // Through `setRole`: `mergeFromServer` deliberately never writes the role column, so seeding
      // it in the entity above would leave both libraries UNASSIGNED and every book here would be
      // invisible to the snapshot -- which is a green "music does not resume" and nothing else.
      db.libraryDao().setRole(RealTrackBytes.MUSIC_LIBRARY_ID, LibraryRole.MUSIC)
      db.libraryDao().setRole(RealTrackBytes.AUDIOBOOK_LIBRARY_ID, LibraryRole.AUDIOBOOKS)
      db.browseDao().replaceLibraryContents(
        RealTrackBytes.AUDIOBOOK_LIBRARY_ID,
        emptyList(),
        emptyList(),
        books.map(MirrorMapper::songEntity),
      )
      db.browseDao().replaceLibraryContents(
        RealTrackBytes.MUSIC_LIBRARY_ID,
        emptyList(),
        emptyList(),
        music.map(MirrorMapper::songEntity),
      )
    }
    snapshot = AudiobookSnapshot(
      AudiobookRepository(db.audiobookDao(), db.mediaProgressDao(), db.bookSettingsDao(), clock),
      db.mediaProgressDao(),
      db.bookSettingsDao(),
    )
  }

  @After
  fun tearDown() {
    // Guarded rather than assumed initialised: a failure inside `setUp` -- a container that is down,
    // a corpus assertion -- would otherwise be replaced by an `UninitializedPropertyAccessException`
    // from here, which is the only message the report would carry.
    harnesses.forEach { it.release() }
    harnesses.clear()
    if (::snapshot.isInitialized) snapshot.stop()
    if (::db.isInitialized) db.close()
    cleanups.forEach { it() }
    cleanups.clear()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
  }

  private fun song(title: String): Song = songs.single { it.title == title }

  private fun store(title: String, positionMs: Long, agoMs: Long, finished: Boolean = false) =
    runBlocking {
      db.mediaProgressDao().upsert(
        MediaProgressEntity(
          song(title).id,
          positionMs,
          finished,
          clock.millis() - agoMs,
          1f,
          false,
          0f,
        ),
      )
      snapshot.refresh()
    }

  private fun itemFor(title: String): MediaItem {
    val s = song(title)
    return MediaItems.of(
      song = s,
      streamUri = RealTrackBytes.rawStreamUrl(s),
      artworkId = null,
      isAudiobook = s.libraryId == RealTrackBytes.AUDIOBOOK_LIBRARY_ID,
      format = StreamFormat.Raw,
    )
  }

  /**
   * Starts [titles] **through the `MuPlayer` seam**, with the real policy and a caller-supplied
   * position of 99 000 ms -- an impossible position for any fixture in this corpus, so if it ever
   * reaches the player the assertion that catches it is unambiguous.
   */
  private fun startThroughTheSeam(titles: List<String>, requestedIndex: Int): PlayerHarness {
    val cache = MediaCache.create(context, File(cacheDir, "run-${System.nanoTime()}"))
    cleanups += { cache.release() }
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      // Through the factory, because that is the only construction site this module permits and it
      // is what attaches the 429 retry policy -- see `MuPlayerFactory`, and
      // `PlayerConstructionTest`, which fails the build on a second one.
      val player = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(OkHttpClient(), cache),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = AudiobookResumePolicy(snapshot, clock),
      ).create()
      harness = PlayerHarness(player)
      harnesses += harness
      player.setMediaItems(titles.map(::itemFor).toMutableList(), requestedIndex, CALLERS_POSITION_MS)
      player.prepare()
      player.play()
    }
    return harness
  }

  @Test
  fun aBookResumesExactlyWhereItWasLeftAndThenKeepsPlaying() {
    // 17 500 ms: 2.5 s into Second Book's last chapter, which starts at 15 000. Off the boundary on
    // purpose -- at 15 000, "resumed exactly" and "resumed at the chapter start" would be the same
    // number and this test would prove neither.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    // 1. It started where it was left. `agoMs = 0` puts the away time in the 0 ms rewind band, so
    //    this is an equality rather than a band.
    harness.awaitPositionAtLeast(17_500L, timeoutMs = READY_TIMEOUT_MS)
    // 2. It kept going. A player parked at 17 500 -- seeked and then silent -- fails here, and a
    //    player that started at 0 cannot reach 19 000 inside this timeout.
    harness.awaitPositionAtLeast(19_000L, timeoutMs = PLAY_ON_TIMEOUT_MS)
    harness.assertNoPlaybackError()
  }

  @Test
  fun aSecondBookResumesAtItsOwnPositionAndNotTheFirstBooks() {
    // The original complaint's smallest possible form. With one book, "resumes at B's position" and
    // "resumes at the only position stored" are the same program.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)
    store("Test Book", positionMs = 11_200L, agoMs = 0L)

    val harness = startThroughTheSeam(listOf("Test Book"), requestedIndex = 0)

    harness.awaitPositionAtLeast(11_200L, timeoutMs = READY_TIMEOUT_MS)
    assertThat(harness.onMain { harness.player.currentPosition })
      .describedAs("Test Book must not resume at Second Book's position")
      .isLessThan(15_000L)
    harness.awaitPositionAtLeast(12_500L, timeoutMs = PLAY_ON_TIMEOUT_MS)
  }

  @Test
  fun aBookLeftAWhileAgoRewindsByTheBandsAmount() {
    // Ten minutes away -> `SmartRewind`'s 5 s band. Expected start 12 500, which is 3.5 s into
    // Second Book's third chapter (9 000..15 000): again, off every boundary and nowhere near zero.
    store("Second Book", positionMs = 17_500L, agoMs = 600_000L)

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    harness.awaitPositionAtLeast(12_500L, timeoutMs = READY_TIMEOUT_MS)
    val started = harness.onMain { harness.player.currentPosition }
    // Strictly inside the window between "no rewind at all" (17 500) and "rewound into the previous
    // chapter" (under 9 000). Both endpoints are real bugs, and both are excluded.
    assertThat(started).isBetween(12_000L, 13_500L)
    harness.awaitPositionAtLeast(14_000L, timeoutMs = PLAY_ON_TIMEOUT_MS)
  }

  @Test
  fun aMultiFileBookResumesOntoTheRightFile() {
    // Half of "per-book resume", and the half a single-file corpus cannot express: coming back to
    // the right *file*, not just the right offset.
    store("Part Two", positionMs = 3_500L, agoMs = 0L)

    val harness = startThroughTheSeam(listOf("Part One", "Part Two", "Part Three"), requestedIndex = 1)

    // Read back with no wait at all first: `setMediaItems` is masked synchronously, so a queue that
    // started at index 0 is observable here and cannot be papered over by playback advancing into
    // index 1 during a wait -- the exact defect CLAUDE.md records against a five-second corpus.
    assertThat(harness.onMain { harness.player.currentMediaItemIndex }).isEqualTo(1)
    harness.awaitPositionAtLeast(3_500L, timeoutMs = READY_TIMEOUT_MS)
    // Part Two is 6 s long, so reaching index 2 proves it played on from 3.5 s rather than
    // restarting -- a restart would take 6 s to get there and this timeout will not allow it.
    harness.await("playback to advance into the third file", timeoutMs = PART_TRANSITION_TIMEOUT_MS) {
      harness.player.currentMediaItemIndex == 2
    }
  }

  @Test
  fun musicStartsAtZeroEvenWithProgressStored() {
    // Spec section 3, against a real player. The music track has a real progress row and a real
    // position; it must be ignored on prepare, and it is ignored *structurally* -- the snapshot has
    // no entry for a media id outside an AUDIOBOOKS library.
    store("Track 1", positionMs = 3_000L, agoMs = 0L)

    val harness = startThroughTheSeam(listOf("Track 1"), requestedIndex = 0)

    harness.awaitState(Player.STATE_READY, timeoutMs = READY_TIMEOUT_MS)
    // Read early and assert small: a track that started at 3 000 would already be past 2 000 here.
    assertThat(harness.onMain { harness.player.currentPosition }).isLessThan(2_000L)
    harness.awaitPositionAtLeast(2_000L, timeoutMs = PLAY_ON_TIMEOUT_MS)
  }

  @Test
  fun aFinishedBookStartsOver() {
    store("Test Book", positionMs = 14_500L, agoMs = 0L, finished = true)

    val harness = startThroughTheSeam(listOf("Test Book"), requestedIndex = 0)

    harness.awaitState(Player.STATE_READY, timeoutMs = READY_TIMEOUT_MS)
    assertThat(harness.onMain { harness.player.currentPosition }).isLessThan(2_000L)
    harness.awaitPositionAtLeast(2_000L, timeoutMs = PLAY_ON_TIMEOUT_MS)
  }

  @Test
  fun theCallersRequestedPositionIsStillDiscarded() {
    // Plan 3 Task 8's guarantee, re-asserted now that a policy actually returns something. Every
    // start above passes 99 000 -- an impossible position for a 21 s book -- and it must never be
    // what the player does. Read with no wait: `setMediaItems` is masked synchronously.
    store("Second Book", positionMs = 17_500L, agoMs = 0L)

    val harness = startThroughTheSeam(listOf("Second Book"), requestedIndex = 0)

    val masked = harness.onMain { harness.player.currentPosition }
    assertThat(masked).isEqualTo(17_500L)
    assertThat(masked).isNotEqualTo(CALLERS_POSITION_MS)
    harness.awaitPositionAtLeast(19_000L, timeoutMs = READY_TIMEOUT_MS)
  }

  private companion object {
    const val NOW_MS = 1_700_000_000_000L

    /** Impossible for any fixture in this corpus, so its arrival would be unambiguous. */
    const val CALLERS_POSITION_MS = 99_000L

    const val READY_TIMEOUT_MS = 20_000L
    const val PLAY_ON_TIMEOUT_MS = 10_000L
    const val PART_TRANSITION_TIMEOUT_MS = 6_000L
  }
}
