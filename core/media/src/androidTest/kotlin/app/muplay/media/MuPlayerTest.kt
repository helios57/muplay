package app.muplay.media

import android.content.Context
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seam, exercised through **all six** `setMediaItem(s)` overloads.
 *
 * Missing one is not a partial failure, it is a total one: a `MediaController` in a car, a headset
 * button handler, or a feature written next year calls the one that was not overridden and sets
 * whatever position it likes, and the guarantee spec section 3 rests on is gone.
 *
 * ### Why every assertion here is read back with no wait at all
 *
 * `CLAUDE.md`'s newest section: on this project's five-second fixtures, three device tests were
 * green *against the very mutation they existed to catch*, because an assertion that waits for a
 * state is satisfied by playback reaching that state on its own. Nothing in this file waits, and
 * nothing in it plays: `setMediaItem(s)` is masked synchronously by ExoPlayer, so the index and the
 * position are readable on the very next line. A player that is never prepared cannot drift into
 * the state being asserted.
 *
 * ### Why the fake policy answers a non-zero position
 *
 * `NeverResume` answers zero, and so does an un-overridden `setMediaItem(item)`. Every test below
 * that has to tell "the policy was consulted" from "nothing happened" therefore drives a policy
 * answering **7000**, which no un-overridden overload can produce.
 */
@RunWith(AndroidJUnit4::class)
class MuPlayerTest {

  /**
   * A recording fake, not a mock -- this project bans mock frameworks, and a recorder is what the
   * assertions actually need: each test asserts two independent things, that the policy was
   * consulted at all and that its answer (not the caller's) is what reached the player.
   */
  private class RecordingPolicy(private val target: ResumeTarget) : ResumePolicy {
    val calls = mutableListOf<Pair<List<String>, Int>>()

    override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
      calls += mediaIds to requestedIndex
      return target
    }
  }

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private val players = mutableListOf<ExoPlayer>()
  private val seams = mutableListOf<MuPlayer>()

  private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("https://host/$id").build()

  private val items get() = listOf(item("a"), item("b"), item("c"))

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    cacheDir = File(context.cacheDir, "muplayer-test-${System.nanoTime()}")
  }

  @After
  fun tearDown() {
    onMain {
      players.forEach { it.release() }
      seams.forEach { it.release() }
    }
    players.clear()
    seams.clear()
    cacheDir.deleteRecursively()
  }

  private fun <T> onMain(block: () -> T): T {
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
   * A **fresh** raw player, built through [MuPlayerFactory] because that is the only construction
   * site this module permits (`PlayerConstructionTest` fails the build on a second one, in test
   * sources included).
   *
   * Fresh per case rather than reused, and that is not tidiness: `setMediaItem(item, resetPosition
   * = false)` means *"keep the current position"*, so an un-overridden one would inherit the 7000
   * a previous case had left behind and pass vacuously. A player that has never been touched
   * cannot supply that.
   */
  private fun rawPlayer(): ExoPlayer = onMain {
    MuPlayerFactory(
      context = context,
      dataSourceFactory = MuPlayDataSourceFactory(
        OkHttpClient(),
        MediaCache.create(context, File(cacheDir, "cache-${System.nanoTime()}")),
      ),
      loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
      resumePolicy = NeverResume,
    ).createExoPlayer().also { players += it }
  }

  @Test
  fun allSixOverloadsConsultTheResumePolicy() {
    val policy = RecordingPolicy(ResumeTarget(startIndex = 0, startPositionMs = 0L))
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, policy) }
    val queue = items

    onMain {
      muPlayer.setMediaItem(queue[0])
      muPlayer.setMediaItem(queue[0], 30_000L)
      muPlayer.setMediaItem(queue[0], /* resetPosition = */ false)
      muPlayer.setMediaItems(queue.toMutableList())
      muPlayer.setMediaItems(queue.toMutableList(), /* resetPosition = */ false)
      muPlayer.setMediaItems(queue.toMutableList(), /* startIndex = */ 1, /* startPositionMs = */ 30_000L)
    }

    // Six calls, not "at least one". A `ForwardingPlayer` that overrode five and inherited the
    // sixth records five, and this is the assertion that catches it.
    assertThat(policy.calls).hasSize(6)
    assertThat(policy.calls.map { it.first }).containsExactly(
      listOf("a"), listOf("a"), listOf("a"),
      listOf("a", "b", "c"), listOf("a", "b", "c"), listOf("a", "b", "c"),
    )
    // The requested index reaches the policy too, and only the last overload names one. A seam
    // that passed a constant here would take "play track 3 of this album" away from every caller.
    assertThat(policy.calls.map { it.second }).containsExactly(0, 0, 0, 0, 0, 1)
  }

  /**
   * **Each of the six, individually decisive.** One test, six observations, and deleting any single
   * override fails it -- which is the property the brief asks for and the reason the policy answers
   * 7000 rather than 0.
   *
   * What each un-overridden overload would land instead, measured against this assertion:
   * `setMediaItem(item)` / `setMediaItems(list)` / `setMediaItems(list, reset)` land position 0;
   * `setMediaItem(item, 30_000)` lands 30 000; `setMediaItem(item, resetPosition = false)` lands 0
   * on an untouched player; `setMediaItems(list, 1, 30_000)` lands index 1 and 30 000. None of them
   * is (index 0, 7000).
   */
  @Test
  fun everyOverloadIndividuallyLandsThePolicysAnswerAndNotTheCallers() {
    val target = ResumeTarget(startIndex = 0, startPositionMs = 7_000L)
    val calls: List<Pair<String, MuPlayer.() -> Unit>> = listOf(
      "setMediaItem(item)" to { setMediaItem(item("a")) },
      "setMediaItem(item, startPositionMs)" to { setMediaItem(item("a"), 30_000L) },
      "setMediaItem(item, resetPosition)" to { setMediaItem(item("a"), false) },
      "setMediaItems(items)" to { setMediaItems(items.toMutableList()) },
      "setMediaItems(items, resetPosition)" to { setMediaItems(items.toMutableList(), false) },
      "setMediaItems(items, startIndex, startPositionMs)" to {
        setMediaItems(items.toMutableList(), 1, 30_000L)
      },
    )

    for ((name, call) in calls) {
      val inner = rawPlayer()
      val muPlayer = onMain { MuPlayer(inner, RecordingPolicy(target)) }

      onMain { muPlayer.call() }

      assertThat(onMain { inner.currentPosition })
        .describedAs("position after %s", name)
        .isEqualTo(7_000L)
      assertThat(onMain { inner.currentMediaItemIndex })
        .describedAs("index after %s", name)
        .isZero
    }
  }

  @Test
  fun aCallersRequestedPositionNeverReachesThePlayer() {
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, NeverResume) }

    onMain { muPlayer.setMediaItem(item("a"), 30_000L) }

    // The single most important assertion in this class. 30 seconds was asked for; zero is where
    // the player is, read back with no wait, so nothing can have "arrived there on its own".
    assertThat(onMain { inner.currentPosition }).isZero
  }

  /**
   * **The resolution of Task 6's `startIndex` fix against this seam, in one observation.**
   *
   * `PlaybackLauncher` calls `setMediaItems(items, queue.startIndex, 0L)` so that tapping track 2
   * of an album starts on track 2. This class discards the caller's *position*; if it discarded the
   * caller's *index* as well, that fix would be silently undone for every album in the library.
   * Both halves are asserted here because only both together are the contract.
   */
  @Test
  fun theCallersIndexSurvivesWhileTheCallersPositionDoesNot() {
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, NeverResume) }

    onMain { muPlayer.setMediaItems(items.toMutableList(), 1, 30_000L) }

    assertThat(onMain { inner.currentPosition }).isZero
    assertThat(onMain { inner.currentMediaItemIndex }).isEqualTo(1)
    assertThat(onMain { inner.currentMediaItem?.mediaId }).isEqualTo("b")
  }

  /**
   * The other observation, and the one that stops "always zero" from being mistaken for "the policy
   * was consulted": a policy answering 7000 at index 2 must produce a player at 7000 on item "c".
   */
  @Test
  fun thePolicysAnswerIsWhatThePlayerActuallyGets() {
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, RecordingPolicy(ResumeTarget(2, 7_000L))) }

    onMain { muPlayer.setMediaItems(items.toMutableList(), 0, 0L) }

    assertThat(onMain { inner.currentPosition }).isEqualTo(7_000L)
    assertThat(onMain { inner.currentMediaItemIndex }).isEqualTo(2)
    assertThat(onMain { inner.currentMediaItem?.mediaId }).isEqualTo("c")
  }

  /**
   * Clearing a queue is `setMediaItems(emptyList())`, and it reaches the policy like any other
   * call. `ResumePolicyTest` pins that `NeverResume` answers rather than throwing; this pins what
   * Media3 does with the answer, which only a real `ExoPlayer` can say. 8a's report asked for it by
   * name.
   */
  @Test
  fun clearingTheQueueThroughTheSeamIsAnsweredRatherThanThrownOn() {
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, NeverResume) }
    onMain { muPlayer.setMediaItems(items.toMutableList(), 1, 0L) }

    onMain { muPlayer.setMediaItems(mutableListOf()) }

    assertThat(onMain { inner.mediaItemCount }).isZero
    assertThat(onMain { inner.currentMediaItem }).isNull()
  }

  /**
   * An index the policy returns that names no item is **Media3's** error, thrown from
   * `super.setMediaItems`, and this records that rather than inventing a clamp here.
   *
   * 8a's report raised it as unverified from the JVM tier, and the division of labour is the reason
   * not to fix it in this class: `PlaybackLauncher.launchQueue` already clamps a stale index to the
   * queue it is launching, which is where a *user's* out-of-range tap is answered. What is left is
   * a policy returning an index outside the queue it was just shown -- a programming error in a
   * policy -- and failing loudly at the point of the mistake beats starting playback somewhere the
   * policy did not choose.
   */
  @Test
  fun anIndexOutsideTheQueueIsMedia3sOwnRefusalRatherThanASilentClamp() {
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, RecordingPolicy(ResumeTarget(7, 0L))) }

    assertThatThrownBy { onMain { muPlayer.setMediaItems(items.toMutableList(), 0, 0L) } }
      .isInstanceOf(IllegalSeekPositionException::class.java)
  }

  /**
   * **What the session is actually given.** `MuPlayerFactory.create()` is the one call
   * `MuPlaybackService` makes, so it is where "everything outside this module sees a player that
   * cannot be told where to start" is either true or not.
   *
   * The policy handed to the factory answers 7000, and 7000 is what the returned player lands at --
   * so a `create()` that returned the raw `ExoPlayer`, or that wrapped it in a `MuPlayer` built on
   * a hardcoded `NeverResume` instead of the injected binding, fails here. Neither would fail any
   * other test in this file.
   */
  @Test
  fun theFactoryHandsOutTheSeamBuiltOnTheInjectedPolicy() {
    val policy = RecordingPolicy(ResumeTarget(startIndex = 1, startPositionMs = 7_000L))
    val player = onMain {
      MuPlayerFactory(
        context = context,
        dataSourceFactory = MuPlayDataSourceFactory(
          OkHttpClient(),
          MediaCache.create(context, File(cacheDir, "cache-${System.nanoTime()}")),
        ),
        loadErrorPolicy = NavidromeLoadErrorHandlingPolicy(),
        resumePolicy = policy,
      ).create().also { seams += it }
    }

    onMain { player.setMediaItems(items.toMutableList(), 0, 30_000L) }

    assertThat(policy.calls).hasSize(1)
    assertThat(onMain { player.currentPosition }).isEqualTo(7_000L)
    assertThat(onMain { player.currentMediaItemIndex }).isEqualTo(1)
  }

  @Test
  fun everythingElseStillForwards() {
    // A ForwardingPlayer that broke ordinary delegation would be worse than no seam at all.
    val inner = rawPlayer()
    val muPlayer = onMain { MuPlayer(inner, NeverResume) }

    onMain {
      muPlayer.setMediaItems(items.toMutableList())
      muPlayer.playWhenReady = false
    }

    assertThat(onMain { muPlayer.mediaItemCount }).isEqualTo(3)
    assertThat(onMain { inner.playWhenReady }).isFalse
    assertThat(onMain { muPlayer.currentMediaItem?.mediaId }).isEqualTo("a")
    // Reads go through untouched in both directions: the seam overrides six setters and nothing
    // else, so what the inner player reports is what the session reports.
    assertThat(onMain { muPlayer.currentMediaItemIndex }).isEqualTo(onMain { inner.currentMediaItemIndex })
  }
}
