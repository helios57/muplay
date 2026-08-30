package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.SubsonicCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The engine, on the **JVM tier**, against two hand-written stores and a hand-written link.
 *
 * Plan 5 Task 10 routed this suite to the phone emulator so it could run against a real Room. That
 * costs the whole class of coverage this task exists to buy -- the emulator is down and the transport
 * this engine drives is the one thing an emulator could never have proved anyway -- so the engine
 * takes two narrow ports instead (see `WatchSyncCredentialStore`), and every decision it makes is
 * gated here. What is *not* gated here, and would be on a device, is that
 * `MediaProgressDao.recentlyPlayed`'s SQL really does order and filter the way
 * [InMemoryProgressStore] below claims; `:core:database`'s own instrumented suite owns that.
 *
 * The plan also noted that its listed tests called `apply(...)` directly and never exercised
 * `start()`/`deliver()`, leaving the incoming-collection path untested by anything. Three tests
 * below drive it.
 *
 * No mock framework anywhere near this file, per this project's rule 5: the fakes record so that a
 * test can assert on **arguments**, not merely that a call happened.
 */
class WatchSyncEngineTest {

  private val link = InMemoryWatchLink()
  private val credentials = RecordingCredentialStore()
  private val progress = InMemoryProgressStore()
  private val engine = WatchSyncEngine(link, credentials, progress)

  @Test
  fun `an incoming payload writes the rows that win and no others`() = runTest {
    progress.seed(row("b-1", positionMs = 1_000, lastPlayedAt = 100))
    progress.seed(row("b-2", positionMs = 8_000, lastPlayedAt = 900))

    val written = engine.apply(
      WatchSyncPayload(
        WatchSyncPayload.VERSION,
        credentials = null,
        progress = listOf(
          ProgressSnapshot("b-1", 11_500, false, 500, 1.4f, true, -3f), // newer -> wins
          ProgressSnapshot("b-2", 2_000, false, 100, 1.0f, false, 0f), // older -> loses
          ProgressSnapshot("b-3", 3_000, false, 700, 1.0f, false, 0f), // unseen -> written
        ),
      ),
    )

    assertThat(written).isEqualTo(2)
    // Read back from the store, not from the return value: "the engine said it wrote two" and "two
    // rows are in the table" are different claims.
    val stored = progress.rows()
    assertThat(stored.keys.sorted()).containsExactly("b-1", "b-2", "b-3")
    assertThat(stored.getValue("b-1").positionMs).isEqualTo(11_500)
    assertThat(stored.getValue("b-1").speed).isEqualTo(1.4f)
    assertThat(stored.getValue("b-2").positionMs).isEqualTo(8_000) // untouched
    assertThat(stored.getValue("b-3").positionMs).isEqualTo(3_000)
  }

  /**
   * The peer's own `lastPlayedAtEpochMs` is what gets written -- no re-stamping with local now.
   *
   * This is the assertion behind `WatchSyncEngine`'s "why there is no Clock" paragraph, and it is
   * the one that would have failed had the plan's injected clock been used the way the plan
   * suggested: a re-stamped row is the newest row on this device, wins the peer's next merge, gets
   * re-stamped there, and the two devices write the same book to each other forever.
   */
  @Test
  fun `an applied row keeps the peer's timestamp`() = runTest {
    progress.seed(row("b-1", positionMs = 1_000, lastPlayedAt = 100))

    engine.apply(
      WatchSyncPayload(
        WatchSyncPayload.VERSION,
        credentials = null,
        progress = listOf(ProgressSnapshot("b-1", 11_500, false, 500, 1.0f, false, 0f)),
      ),
    )

    assertThat(progress.rows().getValue("b-1").lastPlayedAtEpochMs).isEqualTo(500)
  }

  @Test
  fun `credentials are taken only when this device has none`() = runTest {
    engine.apply(
      WatchSyncPayload(WatchSyncPayload.VERSION, CredentialSnapshot("https://a", "u", "p"), emptyList()),
    )
    assertThat(credentials.saved.map { it.baseUrl }).containsExactly("https://a")

    // A second, different payload must not silently repoint a device that is already configured.
    engine.apply(
      WatchSyncPayload(WatchSyncPayload.VERSION, CredentialSnapshot("https://b", "u2", "p2"), emptyList()),
    )
    assertThat(credentials.saved.map { it.baseUrl }).containsExactly("https://a")
  }

  @Test
  fun `publishing sends the credentials and the recent rows with every field intact`() = runTest {
    credentials.save(SubsonicCredentials("https://music.example", "luc", "hunter2"))
    progress.seed(row("b-1", 11_500, 900).copy(speed = 1.4f, skipSilence = true))
    progress.seed(row("b-2", 2_000, 100))

    engine.publishLocalState()

    // Asserting the **argument**, not that publishing happened -- rule 5.
    val payload = link.published.single()
    assertThat(payload.version).isEqualTo(WatchSyncPayload.VERSION)
    assertThat(payload.credentials).isEqualTo(CredentialSnapshot("https://music.example", "luc", "hunter2"))
    // recentlyPlayed orders by lastPlayedAt descending, so the order is a property here.
    assertThat(payload.progress.map { it.mediaId }).containsExactly("b-1", "b-2")
    assertThat(payload.progress.map { it.positionMs }).containsExactly(11_500, 2_000)
    assertThat(payload.progress.map { it.speed }).containsExactly(1.4f, 1.0f)
    assertThat(payload.progress.map { it.skipSilence }).containsExactly(true, false)
  }

  /**
   * The 100 KB item cap, as the number the engine actually asks for.
   *
   * Asserted on the **argument** rather than on the row count, because a store with three rows in it
   * returns three rows whatever limit it is handed -- so a test that only counted the payload could
   * not tell `recentlyPlayed(200)` from `recentlyPlayed(2_000_000)` or from `findAll()`.
   */
  @Test
  fun `publishing asks for exactly the capped number of rows`() = runTest {
    engine.publishLocalState()

    assertThat(progress.limitsRequested).containsExactly(WatchSyncPayload.MAX_PROGRESS_ROWS)
    assertThat(WatchSyncPayload.MAX_PROGRESS_ROWS).isEqualTo(200)
  }

  /**
   * A device with nothing stored still publishes -- an empty payload is how a freshly installed
   * watch says "I have nothing", which is different from saying nothing at all.
   */
  @Test
  fun `a device with no credentials and no rows publishes an empty payload rather than none`() = runTest {
    engine.publishLocalState()

    val payload = link.published.single()
    assertThat(payload.credentials).isNull()
    assertThat(payload.progress).isEmpty()
  }

  @Test
  fun `nothing this engine sends is addressed at Navidrome`() = runTest {
    // The absence spec sections 4 and 11 demand, asserted **with a positive control** so a check
    // that recorded nothing cannot pass. Same technique Plan 4 Task 6 used for the same claim.
    credentials.save(SubsonicCredentials("https://music.example", "luc", "hunter2"))
    progress.seed(row("b-1", 11_500, 900))

    engine.publishLocalState()

    // Positive control: the engine really did publish something, so "no server call" is not
    // "nothing happened at all".
    assertThat(link.published).hasSize(1)
    assertThat(link.published.single().progress).isNotEmpty

    // And the payload's own text contains no Subsonic verb. `createBookmark`, `savePlayQueue` and
    // `scrobble` are the three spec section 4 names as the write paths this design removes.
    val wire = WatchSyncPayload.encode(link.published.single()).decodeToString()
    assertThat(listOf("createBookmark", "savePlayQueue", "scrobble", "/rest/").map(wire::contains))
      .containsExactly(false, false, false, false)
  }

  /**
   * The path the plan's own listing left untested: `start()` collecting `incoming()`.
   *
   * Every other test here calls `apply` directly, which proves the merge and proves nothing about
   * whether anything ever calls it.
   */
  @Test
  fun `start applies whatever the peer delivers`() = runTest {
    progress.seed(row("b-1", positionMs = 1_000, lastPlayedAt = 100))
    val collecting = startCollecting()

    link.deliver(
      WatchSyncPayload(
        WatchSyncPayload.VERSION,
        credentials = CredentialSnapshot("https://a", "u", "p"),
        progress = listOf(ProgressSnapshot("b-1", 11_500, false, 500, 1.0f, false, 0f)),
      ),
    )

    assertThat(progress.rows().getValue("b-1").positionMs).isEqualTo(11_500)
    assertThat(credentials.saved.map { it.baseUrl }).containsExactly("https://a")
    collecting.cancel()
  }

  @Test
  fun `stop ends the collection, and a later delivery is ignored`() = runTest {
    val collecting = startCollecting()
    link.deliver(payloadFor("b-1", positionMs = 1_000))
    assertThat(progress.rows().keys).containsExactly("b-1")

    engine.stop()
    link.deliver(payloadFor("b-2", positionMs = 2_000))

    assertThat(progress.rows().keys).containsExactly("b-1")
    collecting.cancel()
  }

  /**
   * `stop()` before `start()` is a no-op, not a crash.
   *
   * The reachable case, not a hypothetical: a `Service` whose `onDestroy` runs after an `onCreate`
   * that returned early has exactly this shape, and the alternative to asserting it is a
   * `NullPointerException` in teardown that nothing else in this build would catch.
   */
  @Test
  fun `stop before start does nothing`() = runTest {
    engine.stop()

    assertThat(progress.findInCalls).isZero
    assertThat(link.published).isEmpty()
  }

  /**
   * Calling `start` twice leaves **one** collector, not two.
   *
   * Counted by reads and not by writes, deliberately: the merge is idempotent, so a second
   * application of the same payload writes nothing and a test counting `upsert` would pass over two
   * live collectors. `findIn` is called once per application whatever the merge then decides.
   */
  @Test
  fun `starting twice replaces the collection rather than adding a second`() = runTest {
    val collecting = startCollecting()
    engine.start(collecting)

    link.deliver(payloadFor("b-1", positionMs = 1_000))

    assertThat(progress.findInCalls).isEqualTo(1)
    collecting.cancel()
  }

  /**
   * A payload carrying no rows reads nothing at all. Not a micro-optimisation: `findIn` binds one
   * SQL host variable per element and the DAO's own documentation names the 999-variable limit, so
   * the empty case is the one shape that must not reach it.
   */
  @Test
  fun `a payload with no progress rows queries nothing`() = runTest {
    val written = engine.apply(
      WatchSyncPayload(WatchSyncPayload.VERSION, CredentialSnapshot("https://a", "u", "p"), emptyList()),
    )

    assertThat(written).isZero
    assertThat(progress.findInCalls).isZero
  }

  /**
   * `recentlyPlayed`'s SQL is `WHERE isFinished = 0`, so a finished book's row does not cross.
   *
   * Recorded as a test rather than left to be discovered: it means "I finished this on the phone"
   * reaches the watch only while some unfinished row is still carrying the sync, and it is the DAO's
   * decision rather than this engine's. [InMemoryProgressStore] mirrors that query on purpose.
   */
  @Test
  fun `a finished row is not published, because the DAO's query excludes it`() = runTest {
    progress.seed(row("b-1", 11_500, 900).copy(isFinished = true))
    progress.seed(row("b-2", 2_000, 100))

    engine.publishLocalState()

    assertThat(link.published.single().progress.map { it.mediaId }).containsExactly("b-2")
  }

  /**
   * Starts the engine on a scope whose work runs **eagerly**, so that `deliver` below is applied by
   * the time it returns and no test here has to reason about when a scheduler gets around to it.
   *
   * Not `runTest`'s own `backgroundScope`, which is what Plan 5 Task 10's shape suggests. Measured
   * on kotlinx-coroutines 1.11.0: a collector launched there was never run by `advanceUntilIdle()`
   * at all, and all three of these tests failed reporting an empty table -- a fake that looked
   * broken for a reason that was nothing to do with it. An `UnconfinedTestDispatcher` over the
   * test's own scheduler resumes the collector inside `emit`, which removes the question.
   *
   * The scope is the caller's to cancel, exactly as `WatchSyncEngine.stop`'s own contract says.
   */
  // `UnconfinedTestDispatcher` is `@ExperimentalCoroutinesApi`. Opted in on the declaration, with
  // the member named, which is this project's house style for an opt-in.
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun TestScope.startCollecting(): CoroutineScope =
    CoroutineScope(UnconfinedTestDispatcher(testScheduler)).also(engine::start)

  private fun payloadFor(mediaId: String, positionMs: Long) = WatchSyncPayload(
    WatchSyncPayload.VERSION,
    credentials = null,
    progress = listOf(ProgressSnapshot(mediaId, positionMs, false, 500, 1.0f, false, 0f)),
  )

  /** A hand-written credential store that records. No mock framework enters this build. */
  private class RecordingCredentialStore : WatchSyncCredentialStore {
    val saved = mutableListOf<SubsonicCredentials>()
    private var current: SubsonicCredentials? = null

    override suspend fun load(): SubsonicCredentials? = current

    override suspend fun save(credentials: SubsonicCredentials) {
      saved += credentials
      current = credentials
    }
  }

  /**
   * `media_progress` in a map, mirroring the two queries this engine uses -- including
   * `recentlyPlayed`'s `WHERE isFinished = 0 ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit`, which
   * is copied from the DAO rather than invented here.
   */
  private class InMemoryProgressStore : WatchSyncProgressStore {
    private val stored = linkedMapOf<String, MediaProgressEntity>()
    val limitsRequested = mutableListOf<Int>()
    var findInCalls: Int = 0
      private set

    fun seed(entity: MediaProgressEntity) {
      stored[entity.mediaId] = entity
    }

    fun rows(): Map<String, MediaProgressEntity> = stored.toMap()

    override suspend fun recentlyPlayed(limit: Int): List<MediaProgressEntity> {
      limitsRequested += limit
      return stored.values
        .filterNot { it.isFinished }
        .sortedByDescending { it.lastPlayedAtEpochMs }
        .take(limit)
    }

    override suspend fun findIn(mediaIds: List<String>): List<MediaProgressEntity> {
      findInCalls++
      return mediaIds.mapNotNull(stored::get)
    }

    override suspend fun upsert(progress: MediaProgressEntity) {
      stored[progress.mediaId] = progress
    }
  }

  private companion object {
    fun row(mediaId: String, positionMs: Long, lastPlayedAt: Long) = MediaProgressEntity(
      mediaId = mediaId,
      positionMs = positionMs,
      isFinished = false,
      lastPlayedAtEpochMs = lastPlayedAt,
      speed = 1.0f,
      skipSilence = false,
      gainDb = 0f,
    )
  }
}
