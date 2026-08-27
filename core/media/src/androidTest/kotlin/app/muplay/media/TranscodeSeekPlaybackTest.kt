package app.muplay.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.model.ServerCapabilities
import app.muplay.model.Song
import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicClient
import app.muplay.network.SubsonicSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A seek inside a **forced transcode**, measured from the decoder's own output.
 *
 * ### Why this suite exists, and why it could not exist before
 *
 * `StreamFormat.forSuffix` forces `format=mp3` for `opus`/`ogg`/`oga` and for nothing else, so an
 * Opus track is the only thing this app streams that Navidrome transcodes on the fly. A live
 * transcode answers `Accept-Ranges: none` with **no `Content-Length`**, so an ordinary
 * `player.seekTo(...)` on one either does nothing or resolves against a length the player does not
 * have. Nothing throws, nothing logs; the bar moves and the audio does not. Until Plan 3 Task 12
 * seeded `Offset Track` there was no file in the CI corpus that could reach that path at all, so no
 * gate anywhere could see the failing case.
 *
 * ### The fixture is the oracle, and it has three regions
 *
 * `ci/seed-fixtures.sh` builds thirty seconds of Opus in three ten-second regions: **silence**, a
 * **quiet 440 Hz** tone, and a **loud 1760 Hz** tone. So "did the seek land where it was asked" is
 * answerable from the RMS of the first frames out of the decoder -- no golden file, no timing, and
 * no trust in a position the player computes for itself. Three regions rather than two because two
 * are satisfied by an offset hardcoded to anything inside the tone.
 *
 * Thirty seconds rather than five is the other half of it. `CLAUDE.md`'s *"Five-second fixtures let
 * time pass a test that its own defect should fail"* records three device tests that passed against
 * the very mutation they existed to catch, because a test that **waits** for a state can be
 * satisfied by playback reaching it unaided. Every wait below is bounded far under the time
 * ordinary playback would need to reach the region being measured, and the position assertions do
 * not wait at all.
 *
 * ### No stream URL is ever read
 *
 * A Navidrome stream URL carries `u`, `s=salt` and `t=md5(password+salt)`. The URLs below are
 * handed to a `MediaItem` or counted, never asserted on, never logged and never put in a
 * description. Same rule as `GaplessTest` and `GainAudioProcessorTest`.
 */
@RunWith(AndroidJUnit4::class)
class TranscodeSeekPlaybackTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var opus: Song
  private lateinit var mp3: Song

  private val players = mutableListOf<Player>()
  private val harnesses = mutableMapOf<Player, PlayerHarness>()
  private val caches = mutableListOf<androidx.media3.datasource.cache.Cache>()
  private val dataStoreFiles = mutableListOf<File>()

  /**
   * The port, built here rather than borrowed from [RealTrackBytes].
   *
   * That helper deliberately exposes a **URL** and keeps its client private, which is right for a
   * helper whose job is bytes. This suite needs the port itself: `capabilities()` for the gate, and
   * `streamUrl(id, format, offset)` for the re-issue. Same container, same credentials, one URL
   * constant shared.
   */
  private val client by lazy {
    SubsonicClient(
      SubsonicCredentials(RealTrackBytes.NAVIDROME_URL, USERNAME, PASSWORD),
    )
  }

  private val http by lazy { OkHttpClient() }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "transcode-seek-${System.nanoTime()}")
    runBlocking {
      opus = RealTrackBytes.opusTrack()
      mp3 = RealTrackBytes.musicTracks().first()
    }
    // The premise of every assertion below, stated as a `check` so a corpus that lost the Opus
    // fixture fails here and names it, rather than failing later as an inexplicable silence.
    check(StreamFormat.forSuffix(opus.suffix, TRANSCODE_KBPS) is StreamFormat.Mp3) {
      "the Opus fixture must be the one file this app transcodes; suffix was ${opus.suffix}"
    }
    check(StreamFormat.forSuffix(mp3.suffix, TRANSCODE_KBPS) == StreamFormat.Raw) {
      "the control track must stream raw; suffix was ${mp3.suffix}"
    }
  }

  @After
  fun tearDown() {
    onMain { players.forEach { it.release() } }
    players.clear()
    harnesses.clear()
    caches.forEach { it.release() }
    caches.clear()
    dataStoreFiles.forEach { it.delete() }
    dataStoreFiles.clear()
    cacheDir.deleteRecursively()
  }

  // ---- the headline: the audio starts where the seek asked -------------------------------------

  /**
   * Seek a forced transcode to the second and third regions of the fixture, and the decoder's first
   * frames are the second and third regions' audio.
   *
   * **This is the assertion that would have caught the shipped defect.** Three observations of one
   * quantity, at three targets that produce three different amplitudes: playing from the top is
   * silence, twelve seconds in is a quiet tone, twenty-four seconds in is a loud one. A seek that
   * did nothing lands in the first band whichever target it was given; a seek that landed in the
   * wrong place lands in the wrong band; an offset hardcoded to any constant fails at least one of
   * the two seeks.
   */
  @Test
  fun seekingAForcedTranscodeLandsWhereItWasAsked() {
    val fromTheTop = firstAudioRms(seekToMs = null)
    val fromTwelve = firstAudioRms(seekToMs = 12_000L)
    val fromTwentyFour = firstAudioRms(seekToMs = 24_000L)

    assertThat(fromTheTop)
      .describedAs("first 300 ms with no seek -- the fixture's silent region")
      .isLessThan(SILENT_RMS_MAX)
    assertThat(fromTwelve)
      .describedAs("first 300 ms after a seek to 12 s -- the quiet 440 Hz region")
      .isBetween(QUIET_RMS_MIN, QUIET_RMS_MAX)
    assertThat(fromTwentyFour)
      .describedAs("first 300 ms after a seek to 24 s -- the loud 1760 Hz region")
      .isBetween(LOUD_RMS_MIN, LOUD_RMS_MAX)
  }

  // ---- the clock the user reads ----------------------------------------------------------------

  /**
   * The player's position stays in **real-track time** across a re-issue, and it is read with **no
   * wait at all**.
   *
   * That matters more than it looks. The re-issued stream's own zero is the offset, so a player
   * that simply forwarded `getCurrentPosition` would report 0:00 immediately after a seek and count
   * up from there -- a seek that worked, displayed as one that did not. And a test that *waited*
   * for the position to reach 12 s would be satisfied, on a thirty-second fixture, by playback
   * getting there on its own in twelve seconds. `replaceMediaItem` masks synchronously and
   * `MuPlayer` reads its base off the item, so the right answer is true the instant `seekTo`
   * returns; nothing here has to be waited for.
   *
   * Two targets, because an offset base hardcoded to one value satisfies one of them.
   */
  @Test
  fun thePositionReadoutStaysInRealTrackTimeWithNoWaitAtAll() {
    val player = transcodePlayer()
    onMain {
      player.setMediaItem(itemFor(opus))
      player.prepare()
    }

    onMain { player.seekTo(12_000L) }
    val afterFirstSeek = onMain { player.currentPosition }
    onMain { player.seekTo(24_000L) }
    val afterSecondSeek = onMain { player.currentPosition }

    assertThat(afterFirstSeek).isBetween(12_000L, 12_000L + MASKED_POSITION_SLACK_MS)
    assertThat(afterSecondSeek).isBetween(24_000L, 24_000L + MASKED_POSITION_SLACK_MS)
  }

  /**
   * ...and it keeps running from there, which the two instantaneous reads above cannot show.
   *
   * The bound is what makes this a seek assertion rather than a stopwatch: five seconds of wall
   * clock, for a position that has to be past 24.5 s of a thirty-second track. Playback from the
   * top needs twenty-four of them.
   */
  @Test
  fun theClockKeepsRunningFromTheOffsetRatherThanRestarting() {
    val player = transcodePlayer()
    onMain {
      player.setMediaItem(itemFor(opus))
      player.prepare()
      player.seekTo(24_000L)
      player.play()
    }

    harnessFor(player).await("the position to pass 24.5 s", timeoutMs = SHORT_WAIT_MS) {
      player.currentPosition > 24_500L
    }

    assertThat(onMain { player.currentPosition }).isGreaterThan(24_500L)
  }

  // ---- the raw control: nothing about a raw stream changed --------------------------------------

  /**
   * A raw stream is **not** re-issued, proved by counting the distinct stream URLs the player asked
   * for.
   *
   * Task 1 proved live that `format=raw` honours `Range` with a byte-exact 206, so a raw seek is a
   * second request for the *same* URL carrying a `Range` header. A re-issue is a request for a
   * *different* URL. Counting distinct URLs therefore separates the two, where counting requests
   * would not.
   *
   * The Opus track is the control on the same counter, and it is what stops "one distinct URL" from
   * being read as "this counter never moves".
   */
  @Test
  fun aRawTrackSeeksOnOneUrlWhileATranscodeAsksForASecond() {
    val rawUrls = CountingCallFactory(http)
    val raw = transcodePlayer(callFactory = rawUrls)
    onMain {
      raw.setMediaItem(itemFor(mp3))
      raw.prepare()
      raw.play()
    }
    harnessFor(raw).await("raw playback to start", timeoutMs = SHORT_WAIT_MS) {
      raw.currentPosition > 300L
    }
    onMain { raw.seekTo(2_000L) }
    val rawPositionAfterSeek = onMain { raw.currentPosition }

    val transcodeUrls = CountingCallFactory(http)
    val transcode = transcodePlayer(callFactory = transcodeUrls)
    onMain {
      transcode.setMediaItem(itemFor(opus))
      transcode.prepare()
      transcode.play()
    }
    harnessFor(transcode).await("transcoded playback to start", timeoutMs = SHORT_WAIT_MS) {
      transcode.currentPosition > 300L
    }
    onMain { transcode.seekTo(24_000L) }
    harnessFor(transcode).await("the re-issued stream to be requested", timeoutMs = SHORT_WAIT_MS) {
      transcodeUrls.distinctUrls >= 2
    }

    // A raw seek stays on one URL, whatever else it does.
    assertThat(rawUrls.distinctUrls).describedAs("distinct stream URLs for a raw seek").isEqualTo(1)
    // ...and it really did seek, read with no wait -- an in-place seek masks synchronously.
    assertThat(rawPositionAfterSeek).isBetween(2_000L, 2_000L + MASKED_POSITION_SLACK_MS)
    // The control: the transcode asked for a second, different URL. Without this line the
    // assertion above is equally satisfied by a counter that never counts.
    assertThat(transcodeUrls.distinctUrls)
      .describedAs("distinct stream URLs for a transcoded seek")
      .isEqualTo(2)
  }

  // ---- the capability gate, in both directions ---------------------------------------------------

  /**
   * With the server not advertising `transcodeOffset`, the seek command is **gone from the command
   * set** -- which is what disables Media3's own transport controls without this project writing a
   * line of UI.
   *
   * Both questions are asked, because Media3 asks both: the transport controls and
   * `MediaSession`'s permission check read the whole `Player.Commands`, while application code asks
   * `isCommandAvailable`. Answering them differently is a UI that greys out a button the code still
   * honours, or the reverse.
   *
   * Two controls, so "always false" is not what is being observed: a **raw** item on the same
   * player still offers the seek, and the **same Opus item** on a player whose server does
   * advertise the extension offers it too.
   */
  @Test
  fun withoutTheExtensionATranscodeDoesNotOfferASeekAtAll() {
    val withheld = transcodePlayer(serverAdvertisesTheExtension = false)
    val offered = transcodePlayer()
    onMain {
      withheld.setMediaItem(itemFor(opus))
      withheld.prepare()
      offered.setMediaItem(itemFor(opus))
      offered.prepare()
    }

    assertThat(onMain { withheld.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isFalse
    assertThat(onMain { withheld.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isFalse
    assertThat(onMain { withheld.isCommandAvailable(Player.COMMAND_SEEK_BACK) }).isFalse
    assertThat(onMain { withheld.availableCommands.contains(Player.COMMAND_SEEK_FORWARD) }).isFalse
    // Restarting the item needs no offset and is deliberately still offered.
    assertThat(onMain { withheld.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION) }).isTrue

    // The discriminator, and the reason this pair is built from the **same item** on two players
    // that differ in exactly one thing: what the server said about `transcodeOffset`. Without it,
    // every assertion above is equally satisfied by a player that offers no seek on a transcode
    // ever -- which is what a bare `ExoPlayer` does, because a stream with no `Content-Length` is
    // an unseekable timeline window to `ProgressiveMediaSource`.
    assertThat(onMain { offered.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
    assertThat(onMain { offered.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
    assertThat(onMain { offered.availableCommands.contains(Player.COMMAND_SEEK_FORWARD) }).isTrue
  }

  /**
   * A **raw** item is not touched by the gate in either direction: the wrapped player's own answer
   * stands.
   *
   * Prepared and played until it is ready, because for a raw stream the answer really is the
   * wrapped player's, and an `ExoPlayer` with nothing buffered offers no seek regardless of what
   * this class thinks. That difference is the point -- it is what makes the pair above a
   * measurement of `MuPlayer` rather than of `ExoPlayer`'s buffering state.
   */
  @Test
  fun aRawItemKeepsTheWrappedPlayersOwnAnswerWhateverTheServerSaid() {
    val withheld = transcodePlayer(serverAdvertisesTheExtension = false)
    onMain {
      withheld.setMediaItem(itemFor(mp3))
      withheld.prepare()
      withheld.play()
    }
    harnessFor(withheld).await("the raw stream to be ready", timeoutMs = SHORT_WAIT_MS) {
      withheld.playbackState == Player.STATE_READY
    }

    assertThat(onMain { withheld.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
    assertThat(onMain { withheld.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
  }

  /**
   * A `seekTo` that reaches a withdrawn command moves nothing.
   *
   * `isCommandAvailable` already said no, so nothing in a well-behaved controller gets here -- but
   * "withdrawn" has to mean the position does not move, not merely that a button is greyed out.
   * Read with no wait, against a player that has been prepared but never played.
   */
  @Test
  fun aSeekOnAWithdrawnCommandLeavesThePositionWhereItWas() {
    val withheld = transcodePlayer(serverAdvertisesTheExtension = false)
    onMain {
      withheld.setMediaItem(itemFor(opus))
      withheld.prepare()
    }

    onMain { withheld.seekTo(24_000L) }

    assertThat(onMain { withheld.currentPosition }).isLessThan(MASKED_POSITION_SLACK_MS)
  }

  // ---- the queue, and the wiring ------------------------------------------------------------------

  /**
   * A seek inside track 2 of a three-track queue leaves tracks 1 and 3 exactly where they were.
   *
   * `MuPlayer` re-issues with `replaceMediaItem`, not `setMediaItem`. The difference is invisible to
   * every other assertion in this file -- both play the right audio from the right place -- and it
   * is the difference between a seek and a queue that silently lost everything after the current
   * track.
   */
  @Test
  fun aSeekInsideATranscodeLeavesTheRestOfTheQueueAlone() {
    val player = transcodePlayer()
    val queue = listOf(itemFor(mp3), itemFor(opus), itemFor(mp3))
    onMain {
      player.setMediaItems(queue.toMutableList(), /* startIndex = */ 1, /* startPositionMs = */ 0L)
      player.prepare()
    }
    check(onMain { player.currentMediaItemIndex } == 1) { "the queue did not start on track 2" }

    onMain { player.seekTo(24_000L) }

    assertThat(onMain { player.mediaItemCount }).isEqualTo(3)
    assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { player.getMediaItemAt(0).mediaId }).isEqualTo(mp3.id)
    assertThat(onMain { player.getMediaItemAt(1).mediaId }).isEqualTo(opus.id)
    assertThat(onMain { player.getMediaItemAt(2).mediaId }).isEqualTo(mp3.id)
    // ...and the item that was replaced really was replaced, or the assertions above are equally
    // satisfied by a seek that did nothing at all.
    assertThat(onMain { MediaItems.timeOffsetMsOf(player.getMediaItemAt(1)) }).isEqualTo(24_000L)
    assertThat(onMain { MediaItems.timeOffsetMsOf(player.getMediaItemAt(2)) }).isEqualTo(0L)
  }

  /**
   * The re-issued item is filed in the media cache under its **own** key, not the track's.
   *
   * `TrackIdCacheKeyFactory` files every request under `MediaItem.customCacheKey`, so an offset
   * stream carrying the bare track id is written into the middle of the full track's cache entry --
   * and every later read of that track is served audio from the wrong place. Nothing else in this
   * suite can see that: the audio is right either way *on this run*.
   */
  @Test
  fun aReissuedItemDoesNotShareTheFullTracksCacheKey() {
    val player = transcodePlayer()
    onMain {
      player.setMediaItem(itemFor(opus))
      player.prepare()
    }
    val before = onMain { player.currentMediaItem?.localConfiguration?.customCacheKey }

    onMain { player.seekTo(24_000L) }
    val after = onMain { player.currentMediaItem?.localConfiguration?.customCacheKey }

    assertThat(before).isEqualTo(opus.id)
    assertThat(after).isNotEqualTo(opus.id)
    assertThat(after).isEqualTo(TranscodeSeek.cacheKeyFor(opus.id, 24))
  }

  /**
   * `MuPlayerFactory.create()` -- the one call `MuPlaybackService` makes -- hands the seam its
   * transcode support.
   *
   * Silent if it stops: the factory's default is `TranscodeSeekSupport.None`, which answers
   * `InPlace` for everything, which is exactly the shipped defect. No network and no playback here;
   * the subject is one argument reaching one constructor.
   */
  @Test
  fun theFactoryHandsTheSeamItsTranscodeSupport() {
    val recording = RecordingTranscodeSeekSupport()
    val player = onMain {
      MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(http, newCache()),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
        transcodeSeek = recording,
      ).create().also { players += it }
    }

    onMain {
      player.setMediaItem(itemFor(opus))
      player.seekTo(9_000L)
    }

    // Filtered, not `containsExactly`: `MuPlayer` asks the same object at target `0` whenever it
    // recomputes its command set, which is every player event. What identifies the *seek* is the
    // target it was made with, and there is exactly one of those.
    assertThat(recording.targets).contains(9_000L)
    assertThat(recording.targets.filter { it != 0L })
      .describedAs("every non-zero target the seam asked about")
      .containsExactly(9_000L)
  }

  /**
   * The duration a re-issued transcode reports is the **whole track's**, not what is left of it.
   *
   * Observable only when the offset stream comes back with a length, which for Navidrome means out
   * of its **transcoding cache** rather than produced live -- so this test warms the entry first,
   * through the same (track, bitrate, offset) key the player will ask for, and asserts the premise
   * before it asserts the behaviour. Without that warm-up the response is chunked, the extractor
   * has no duration at all, and the override provably changes nothing.
   *
   * Six seconds remain after a seek to 24 s of a thirty-second track, so the two answers are far
   * apart and a tolerance cannot blur them.
   */
  @Test
  fun aReissuedTranscodeReportsTheWholeTracksDuration() {
    val warmed = warmTranscodeCacheAt(24)
    check(warmed) { "the transcoding cache entry did not warm; this test's premise is not met" }

    val player = transcodePlayer()
    onMain {
      player.setMediaItem(itemFor(opus))
      player.prepare()
      player.seekTo(24_000L)
      player.play()
    }
    harnessFor(player).await("the re-issued stream to report a duration", timeoutMs = SHORT_WAIT_MS) {
      player.duration != C.TIME_UNSET
    }

    assertThat(onMain { player.duration })
      .describedAs("duration after a seek to 24 s of a 30 s track")
      .isBetween(FIXTURE_DURATION_MS - DURATION_SLACK_MS, FIXTURE_DURATION_MS + DURATION_SLACK_MS)
  }

  /**
   * A `MuPlayer` built with **no** transcode support behaves exactly as it did before this task.
   *
   * `TranscodeSeekSupport.None` is `MuPlayerFactory`'s Kotlin default and the answer every
   * hand-constructed player in this module's suites gets. It has to be genuinely inert: a `None`
   * that answered anything but `InPlace` would change what nine other instrumented tests measure,
   * and it is the one implementation in this feature that no other test in this file exercises.
   */
  @Test
  fun aPlayerWithNoTranscodeSupportSeeksEverythingInPlace() {
    val exo = onMain {
      MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(http, newCache()),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
      ).createExoPlayer()
    }
    val player = onMain { MuPlayer(exo, NeverResume).also { players += it } }
    onMain {
      // The Opus item -- the one a transcode-aware player would re-issue.
      player.setMediaItem(itemFor(opus))
      player.prepare()
      player.play()
    }
    harnessFor(player).await("playback to start", timeoutMs = SHORT_WAIT_MS) {
      player.currentPosition > 300L
    }

    onMain { player.seekTo(12_000L) }

    // Nothing was re-issued: the item is the one that was set, offset and cache key untouched.
    assertThat(onMain { MediaItems.timeOffsetMsOf(player.currentMediaItem!!) }).isEqualTo(0L)
    assertThat(onMain { player.currentMediaItem?.localConfiguration?.customCacheKey })
      .isEqualTo(opus.id)
    // ...and `None` answers `InPlace` for the format that would otherwise be re-issued, and hands
    // back the very object it was given. `isSameAs`, not a URI comparison: every call to
    // `itemFor` stamps a fresh auth salt, so two "identical" items are different strings.
    val item = itemFor(opus)
    assertThat(TranscodeSeekSupport.None.methodFor(item, 12_000L)).isEqualTo(SeekMethod.InPlace)
    assertThat(TranscodeSeekSupport.None.reissue(item, 12)).isSameAs(item)
  }

  /**
   * A negotiation that **failed** leaves the conservative answer standing: not supported, so no
   * seek is offered on a transcode.
   *
   * This is the arm that decides what happens on a flaky network, on a server that has gone away,
   * or on a `getOpenSubsonicExtensions` that answers 500 -- and it is the arm where the wrong
   * default is invisible. `true` on failure would offer a seek that re-issues a URI the server
   * cannot honour; `false` shows a disabled bar for a second or two. Spec section 4's rule for a
   * capability query is to degrade, not to fail the thing that asked, and `runCatching` is where
   * that happens.
   *
   * The failure is a **transport** failure (`IOException`), deliberately: `CapabilityNegotiator`
   * already degrades a *Subsonic-level* error to "no extensions" on its own, so a
   * `SubsonicErrorException` would never reach `refresh`'s `runCatching` and would prove nothing
   * about it.
   */
  @Test
  fun aFailedNegotiationLeavesTheSeekWithdrawn() {
    val (provider, file) = fixedSubsonicSourceProvider(
      context,
      UnreachableCapabilities(client),
      RealTrackBytes.NAVIDROME_URL,
    )
    dataStoreFiles += file
    val support = TranscodeOffsetSupport(provider)

    runBlocking { support.refresh() }

    assertThat(support.isSupported).isFalse
    assertThat(support.methodFor(itemFor(opus), 12_000L)).isEqualTo(SeekMethod.NotOffered)
    // ...and the same object, negotiated against the real server, says the opposite -- so this is
    // a measurement of the failure path and not of a gate that is always shut.
    val negotiated = TranscodeOffsetSupport(
      fixedSubsonicSourceProvider(context, client, RealTrackBytes.NAVIDROME_URL)
        .also { dataStoreFiles += it.second }.first,
    )
    runBlocking { negotiated.refresh() }
    assertThat(negotiated.isSupported).isTrue
    assertThat(negotiated.methodFor(itemFor(opus), 12_000L))
      .isEqualTo(SeekMethod.ReissueWithOffset(12))
  }

  /**
   * `reissue` on something it cannot rebuild hands the item straight back rather than throwing.
   *
   * Neither state is reachable from `MuPlayer` -- `methodFor` cannot have answered
   * `ReissueWithOffset` for an item with no format stamp, nor before a negotiation has succeeded --
   * so this is about the method being *public*. A public method that throws on an input it can
   * describe is a worse answer than one that changes nothing, and "changes nothing" has to be
   * asserted or it is just an intention.
   */
  @Test
  fun reissuingSomethingThisAppDidNotBuildChangesNothing() {
    val (provider, file) = fixedSubsonicSourceProvider(context, client, RealTrackBytes.NAVIDROME_URL)
    dataStoreFiles += file
    val negotiated = TranscodeOffsetSupport(provider).also { runBlocking { it.refresh() } }
    val neverNegotiated = TranscodeOffsetSupport(provider)

    val foreign = MediaItem.Builder().setMediaId("not-ours").build()
    val ours = itemFor(opus)

    // No format stamp: nothing to rebuild a URI from.
    assertThat(negotiated.reissue(foreign, 12)).isSameAs(foreign)
    // No source: nothing to rebuild it *with*. `refresh` was never called on this one.
    assertThat(neverNegotiated.reissue(ours, 12)).isSameAs(ours)
    // ...and with both, it really does rebuild -- the control that stops the two lines above from
    // being satisfied by a `reissue` that never does anything at all.
    assertThat(negotiated.reissue(ours, 12)).isNotSameAs(ours)
    assertThat(MediaItems.timeOffsetMsOf(negotiated.reissue(ours, 12))).isEqualTo(12_000L)
  }

  /**
   * What the **wrapped** `ExoPlayer` thinks of a live transcode, measured rather than assumed --
   * and the reason `MuPlayer` grants the seek command rather than only withdrawing it.
   *
   * A live transcode has no `Content-Length`, so `Mp3Extractor` has no constant-bitrate seek map to
   * build and the timeline window comes back unseekable. `ExoPlayerImpl` then leaves
   * `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` out of its available commands, and
   * `MediaControllerImplBase.seekTo` **returns silently** on a command the controller has not been
   * granted (read off the 1.11.0 bytecode). So if the seam merely passed `super`'s answer through,
   * every seek from the app's own UI would be dropped before `MuPlayer` ever saw it.
   *
   * The raw track is the control: an ordinary `format=raw` stream declares a length, is seekable,
   * and offers the command -- which is what makes the transcode's answer a property of the
   * *stream*, not of this apparatus.
   */
  @Test
  fun aLiveTranscodeIsNotSeekableToTheWrappedPlayerButTheSeamOffersTheSeekAnyway() {
    val support = negotiatedSupport()

    // One player at a time, and that is not tidiness. `MuPlayerFactory` builds every player with
    // `setAudioAttributes(.., handleAudioFocus = true)`, so a second player calling `play()` takes
    // focus and Media3 pauses the first -- which arrives here as `state=READY playWhenReady=false
    // position=0` and reads exactly like a stream that never loaded. Measured, on the first run of
    // this test.
    val rawExo = rawExoPlayer()
    onMain {
      rawExo.setMediaItem(itemFor(mp3))
      rawExo.prepare()
      rawExo.play()
    }
    harnessFor(rawExo).await("the raw stream to play", timeoutMs = SHORT_WAIT_MS) {
      rawExo.currentPosition > 300L
    }

    // The control.
    assertThat(onMain { rawExo.isCurrentMediaItemSeekable })
      .describedAs("ExoPlayer's own verdict on a format=raw stream")
      .isTrue
    assertThat(onMain { rawExo.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
    onMain { rawExo.stop() }

    val transcodeExo = rawExoPlayer()
    onMain {
      transcodeExo.setMediaItem(itemFor(opus))
      transcodeExo.prepare()
      transcodeExo.play()
    }
    harnessFor(transcodeExo).await("the transcode to play", timeoutMs = SHORT_WAIT_MS) {
      transcodeExo.currentPosition > 300L
    }

    // The measurement this test exists for -- and it is deliberately **not** an assertion that the
    // transcode is unseekable, because it is not reliably either. Whether Navidrome answers a
    // `format=mp3` request live (chunked, no `Content-Length`, unseekable to `Mp3Extractor`) or out
    // of its transcoding cache (a length, a real seek map) depends on whether that (track, bitrate,
    // offset) key has been produced before -- which is a property of the container's history, not
    // of this app. Measured on the shared container, warm: seekable. On a cold one it is not.
    //
    // That instability is precisely why the seam answers for itself rather than passing `super`'s
    // answer through: an app whose seek bar appears and disappears with a server-side cache is the
    // silent-wrong-answer class wearing a third hat.
    val exoPlayerVerdict = onMain { transcodeExo.isCurrentMediaItemSeekable }
    assertThat(onMain { transcodeExo.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .describedAs("ExoPlayer offers the seek exactly when it calls the item seekable")
      .isEqualTo(exoPlayerVerdict)

    // ...and the seam over that same player says yes either way, because it can seek it by
    // re-issuing. This is the assertion that would fail if the grant were dropped.
    val seam = onMain { MuPlayer(transcodeExo, NeverResume, support).also { players += it } }
    assertThat(onMain { seam.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }).isTrue
    assertThat(onMain { seam.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) })
      .isTrue
  }

  // ---- apparatus ---------------------------------------------------------------------------------

  /**
   * RMS of the first [MEASURE_WINDOW_MS] of audio the decoder emitted **after the pipeline's own
   * last drain**, having optionally seeked first.
   *
   * The drain boundary rather than byte zero, for the reason `GainAudioProcessorTest` uses the same
   * offsets: `TeeAudioProcessor` flushes on every media-period change, so the last flush marks
   * where the re-issued stream's audio begins and nothing here has to guess a frame count.
   *
   * The wait is on **captured bytes**, not on a position, so the measurement does not depend on the
   * very position readout another test in this file is gating.
   */
  private fun firstAudioRms(seekToMs: Long?): Float {
    val capture = CapturingAudioSink()
    val player = transcodePlayer(capture = capture)
    onMain {
      player.setMediaItem(itemFor(opus))
      player.prepare()
      if (seekToMs != null) player.seekTo(seekToMs)
      player.play()
    }

    val harness = harnessFor(player)
    // The window is the **expected** byte count, not one derived from `capture`. Derived, it is
    // zero until the sink has announced a format, and `size - 0 >= 0` is true of an empty capture:
    // the wait would return instantly having measured nothing, which is exactly how the first run
    // of this test reported an RMS over no audio at all.
    harness.await("$MEASURE_WINDOW_MS ms of decoded audio", timeoutMs = SHORT_WAIT_MS) {
      capture.sampleRateHz != 0 &&
        capture.pcm.size - lastDrainOffset(capture, capture.pcm.size) >= WINDOW_BYTES
    }
    harness.assertNoPlaybackError()

    val pcm = capture.pcm
    // The format the pipeline announced, asserted rather than assumed -- a capture that quietly
    // ran at some other rate would turn every millisecond below into a number with no unit.
    assertThat(capture.encoding).isEqualTo(C.ENCODING_PCM_16BIT)
    assertThat(capture.channelCount).isEqualTo(FIXTURE_CHANNEL_COUNT)
    assertThat(capture.sampleRateHz).isEqualTo(FIXTURE_SAMPLE_RATE_HZ)

    val from = lastDrainOffset(capture, pcm.size)
    return rmsOf(pcm.copyOfRange(from, from + WINDOW_BYTES))
  }

  /** The largest flush offset that still leaves a measurable window inside [size]. */
  private fun lastDrainOffset(capture: CapturingAudioSink, size: Int): Int =
    capture.flushOffsets.filter { it < size }.maxOrNull() ?: 0

  /** Root mean square of a 16-bit little-endian buffer, in raw sample units. */
  private fun rmsOf(pcm: ByteArray): Float {
    check(pcm.size >= Short.SIZE_BYTES) { "no audio to measure" }
    val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    var sum = 0.0
    var count = 0
    while (buffer.remaining() >= Short.SIZE_BYTES) {
      val sample = buffer.short.toDouble()
      sum += sample * sample
      count++
    }
    return sqrt(sum / count).toFloat()
  }

  /**
   * A production `MediaItem`, from `MediaItems.of` and the real `StreamFormat.forSuffix` -- nothing
   * hand-built, because the extras this feature reads back are the ones that function stamps.
   */
  private fun itemFor(song: Song): MediaItem {
    val format = StreamFormat.forSuffix(song.suffix, TRANSCODE_KBPS)
    return MediaItems.of(
      song = song,
      streamUri = client.streamUrl(song.id, format),
      artworkUri = null,
      isAudiobook = false,
      format = format,
    )
  }

  /**
   * The shipping player, wrapped in the shipping seam, with the **real** `TranscodeOffsetSupport`
   * over a real `SubsonicSourceProvider` and a real negotiation against the container.
   *
   * `createExoPlayer` plus an explicit `MuPlayer` rather than `create()`, because a capture needs
   * the renderers factory and `create()` takes none;
   * [theFactoryHandsTheSeamItsTranscodeSupport] is what gates the one call production makes.
   *
   * [serverAdvertisesTheExtension] `false` wraps the real client in [WithoutExtensions], which
   * answers the capability query with an empty extension map and delegates everything else. That is
   * a real negotiation against a real server whose answer has been narrowed -- not a stand-in for
   * the gate, which is the production object either way.
   */
  private fun transcodePlayer(
    serverAdvertisesTheExtension: Boolean = true,
    capture: CapturingAudioSink? = null,
    callFactory: Call.Factory = http,
  ): MuPlayer {
    val source: SubsonicSource = if (serverAdvertisesTheExtension) client else WithoutExtensions(client)
    val (provider, file) = fixedSubsonicSourceProvider(context, source, RealTrackBytes.NAVIDROME_URL)
    dataStoreFiles += file
    val support = TranscodeOffsetSupport(provider)
    runBlocking { support.refresh() }
    check(support.isSupported == serverAdvertisesTheExtension) {
      "the capability negotiation reported ${support.isSupported}; the container may be down"
    }

    return onMain {
      val gainProcessor = GainAudioProcessor()
      val exo: ExoPlayer = MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(callFactory, newCache()),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = NeverResume,
        transcodeSeek = support,
      ).createExoPlayer(
        gainProcessor = gainProcessor,
        renderersFactory = if (capture == null) MuPlayRenderersFactory(context, gainProcessor)
        else tappedShippingRenderers(context, gainProcessor, capture),
      )
      MuPlayer(exo, NeverResume, support).also { players += it }
    }
  }

  /**
   * Fetches the offset transcode over plain HTTP so that Navidrome's transcoding cache holds the
   * entry the player is about to ask for, and reports whether it really is warm.
   *
   * The cache is keyed on (track, requested bitrate, **offset**) and not on the auth salt --
   * measured against the container -- so this warms exactly the key production will use.
   */
  private fun warmTranscodeCacheAt(offsetSeconds: Int): Boolean {
    val format = StreamFormat.Mp3(TRANSCODE_KBPS)
    repeat(2) {
      http.newCall(Request.Builder().url(client.streamUrl(opus.id, format, offsetSeconds)).build())
        .execute().use { it.body.bytes() }
    }
    return http.newCall(
      Request.Builder().url(client.streamUrl(opus.id, format, offsetSeconds)).build(),
    ).execute().use { it.header("Accept-Ranges") == "bytes" && it.header("Content-Length") != null }
  }

  /**
   * One harness per player, not one per call: [PlayerHarness] installs an error listener in its
   * constructor, and a fresh one per wait would add a listener per wait.
   */
  /** A negotiated `TranscodeOffsetSupport` against the real container, over its own DataStore file. */
  private fun negotiatedSupport(): TranscodeOffsetSupport {
    val (provider, file) = fixedSubsonicSourceProvider(context, client, RealTrackBytes.NAVIDROME_URL)
    dataStoreFiles += file
    return TranscodeOffsetSupport(provider).also { runBlocking { it.refresh() } }
  }

  /** The shipping `ExoPlayer`, unwrapped -- what `MuPlayer` forwards to. */
  private fun rawExoPlayer(): ExoPlayer = onMain {
    MuPlayerFactory(
      context = context,
      dataSourceFactory = MuPlayDataSourceFactory(http, newCache()),
      loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
      resumePolicy = NeverResume,
    ).createExoPlayer().also { players += it }
  }

  /** A fresh `SimpleCache` per player: one shared between two runs lets the first answer the second. */
  private fun newCache(): androidx.media3.datasource.cache.Cache =
    MediaCache.create(context, File(cacheDir, "cache-${System.nanoTime()}")).also { caches += it }

  private fun harnessFor(player: Player): PlayerHarness =
    harnesses.getOrPut(player) { PlayerHarness(player) }

  /**
   * Runs [block] on the player's application thread.
   *
   * The same shape `MuPlayerTest` uses, and a copy rather than a call into [PlayerHarness] because
   * some of the work below -- constructing a player, above all -- happens before there is a harness
   * to ask.
   */
  private fun <T> onMain(block: () -> T): T {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
    var result: Any? = null
    var thrown: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      runCatching(block).onSuccess { result = it }.onFailure { thrown = it }
    }
    thrown?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /**
   * A `SubsonicSource` that is the real one in every respect except that it reports no OpenSubsonic
   * extensions.
   *
   * Kotlin delegation, so the *only* behaviour substituted is the one sentence this test is about.
   * `isOpenSubsonic = true` matters: "an OpenSubsonic server that does not offer this extension" is
   * the case `TranscodeSeek` answers `NotOffered` for, and a legacy server would degrade for a
   * different reason and prove less.
   */
  private class WithoutExtensions(delegate: SubsonicSource) : SubsonicSource by delegate {
    override suspend fun capabilities(): ServerCapabilities =
      ServerCapabilities(isOpenSubsonic = true, extensions = emptyMap())
  }

  /**
   * A `SubsonicSource` whose capability query fails the way a dead network fails -- an
   * `IOException`, which is **not** a `SubsonicException` and so is not something
   * `CapabilityNegotiator` degrades on its own.
   */
  private class UnreachableCapabilities(delegate: SubsonicSource) : SubsonicSource by delegate {
    override suspend fun capabilities(): ServerCapabilities =
      throw java.io.IOException("the server is unreachable")
  }

  /** Records the targets `MuPlayer` asked about, and re-issues nothing. */
  private class RecordingTranscodeSeekSupport : TranscodeSeekSupport {
    val targets = mutableListOf<Long>()

    override fun methodFor(mediaItem: MediaItem, targetPositionMs: Long): SeekMethod {
      targets += targetPositionMs
      return SeekMethod.InPlace
    }

    override fun reissue(mediaItem: MediaItem, timeOffsetSeconds: Int): MediaItem = mediaItem
  }

  /**
   * Counts the **distinct** URLs a player asked for, and never reveals one.
   *
   * A `Set<String>` of stream URLs would be a set of password equivalents; only its size is ever
   * read, and the set itself is private and never printed.
   */
  private class CountingCallFactory(private val delegate: Call.Factory) : Call.Factory {
    private val seen = mutableSetOf<String>()
    private val count = AtomicInteger()

    val distinctUrls: Int get() = count.get()

    override fun newCall(request: Request): Call {
      synchronized(seen) {
        if (seen.add(request.url.toString())) count.incrementAndGet()
      }
      return delegate.newCall(request)
    }
  }

  private companion object {
    const val USERNAME = "admin"
    const val PASSWORD = "testpass"

    /** What `QueueRepository` asks for, so this suite measures the request production makes. */
    const val TRANSCODE_KBPS = StreamFormat.DEFAULT_TRANSCODE_BITRATE_KBPS

    /** `ci/seed-fixtures.sh` builds the Opus fixture at 48 kHz mono. */
    const val FIXTURE_SAMPLE_RATE_HZ = 48_000
    const val FIXTURE_CHANNEL_COUNT = 1

    /** Thirty seconds, per `books.tsv` (30006 ms, ffprobe) and Navidrome (30 s). */
    const val FIXTURE_DURATION_MS = 30_000L

    /** Room for the encoder's own rounding and for the extractor's estimate, well under 6 s. */
    const val DURATION_SLACK_MS = 3_000L

    /** The window each amplitude is measured over. Long enough to average a 440 Hz tone. */
    const val MEASURE_WINDOW_MS = 300

    /** 48 kHz, mono, 16-bit, for [MEASURE_WINDOW_MS]. Expected, never derived -- see `firstAudioRms`. */
    const val WINDOW_BYTES =
      FIXTURE_SAMPLE_RATE_HZ * FIXTURE_CHANNEL_COUNT * Short.SIZE_BYTES * MEASURE_WINDOW_MS / 1000

    /**
     * Bounded waits, deliberately far shorter than the time playback would need to reach the region
     * being measured on its own -- twelve and twenty-four seconds into a thirty-second fixture.
     * This is `CLAUDE.md`'s "five-second fixtures let time pass a test" rule applied in the only
     * way that works: a wait that a defect cannot outlast.
     */
    const val SHORT_WAIT_MS = 8_000L

    /**
     * How far past a masked seek the position may already have moved when it is read back with no
     * wait. Media3 applies `seekTo` to its masking state synchronously, so this is slack for the
     * one or two milliseconds between the call returning and the read, not a wait.
     */
    const val MASKED_POSITION_SLACK_MS = 700L

    // ---- the three amplitude bands, MEASURED through the path the player takes ------------------
    //
    // Not computed. The first version of these bands was arithmetic -- "a full-scale sine's RMS is
    // 32767/sqrt(2), so 0.12 and 0.9 are 2780 and 20853" -- and it was wrong by a factor of eight,
    // because ffmpeg's `sine` filter does not emit full scale. `GainAudioProcessorTest` had already
    // written that down (its seeded sine peaks at 0.119 of full scale); this file's own bands were
    // derived from the theory instead and the device then reported 361.77 for a seek that had
    // landed exactly where it was asked.
    //
    // So these come from the real thing: `/rest/stream?format=mp3&maxBitRate=192&timeOffset=N`
    // fetched off `ci-navidrome-1` and decoded, RMS over the first 300 ms.
    //
    //     timeOffset=0    0.0     (the silent region)
    //     timeOffset=12   355.6   (the quiet 440 Hz region; the device measured 361.77)
    //     timeOffset=24   2143.6  (the loud 1760 Hz region)
    //
    // The bands are wide around each of those and do not overlap, which is the whole point: "the
    // seek landed somewhere else" has to be as visible as "the seek did nothing".

    const val SILENT_RMS_MAX = 100f
    const val QUIET_RMS_MIN = 200f
    const val QUIET_RMS_MAX = 800f
    const val LOUD_RMS_MIN = 1_300f
    const val LOUD_RMS_MAX = 4_500f
  }
}
