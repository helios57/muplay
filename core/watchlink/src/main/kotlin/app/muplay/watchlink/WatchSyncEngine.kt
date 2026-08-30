package app.muplay.watchlink

import app.muplay.database.CredentialStore
import app.muplay.database.dao.MediaProgressDao
import app.muplay.database.entity.MediaProgressEntity
import app.muplay.model.SubsonicCredentials
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The slice of credential storage this module needs: read one, write one.
 *
 * A two-method interface rather than a dependency on `CredentialStore` itself, for exactly the
 * reason `:feature:setup`'s `SetupCredentialSink` gives -- the real store talks to the Android
 * Keystore, which does not exist on a JVM, and `:core:database`'s own instrumented
 * `CredentialStoreTest` already proves it there. Without this seam every test of [WatchSyncEngine]
 * would need an emulator, and the emulator is the one thing this task cannot have.
 *
 * Deliberately **not** an interface extracted from `CredentialStore` in `:core:database`, which is
 * what Plan 5 Task 10's listing suggested. That would rename or re-front a Keystore-backed class
 * with fourteen call sites whose only tests live in `src/androidTest`, in a task that cannot run
 * `src/androidTest` -- a refactor whose verification tier is dead. This module is also not entitled
 * to `clear()`: signing a device out is not something a payload from the other wrist may do, and a
 * narrow port says so structurally rather than in a comment.
 */
interface WatchSyncCredentialStore {
  suspend fun load(): SubsonicCredentials?
  suspend fun save(credentials: SubsonicCredentials)
}

/**
 * The slice of `media_progress` this module needs. Same reasoning as [WatchSyncCredentialStore]:
 * the real one is Room, which needs a device.
 *
 * Three methods and not the whole DAO. In particular **not `clear`** -- deletions do not replicate
 * (see [ProgressMerge]), and a port that cannot delete is a design decision the compiler enforces.
 */
interface WatchSyncProgressStore {
  suspend fun recentlyPlayed(limit: Int): List<MediaProgressEntity>
  suspend fun findIn(mediaIds: List<String>): List<MediaProgressEntity>
  suspend fun upsert(progress: MediaProgressEntity)
}

/**
 * Keeps a phone and its watch agreeing about where every book is.
 *
 * Never talks to Navidrome. Spec sections 4 and 11 rule out **server** progress sync, and every
 * reason they give -- no `createBookmark` write path, no `savePlayQueue`, no conflict resolution
 * against a server, no background worker, no exposure to the milliseconds-versus-seconds hazard --
 * is still true of a replication that goes phone-to-watch and nowhere else. "Book positions are
 * local only" is a statement about the *server*, not about the *device*.
 *
 * ### Why there is no `Clock` here
 *
 * The plan's listing injected one and never read it, and offered "stamp a `publishedAtEpochMs` on
 * the payload for diagnostics" as the fix. It was removed instead, and the reason is stronger than
 * tidiness: [apply] must write the peer's row **with the peer's own `lastPlayedAtEpochMs`**. Any
 * re-stamping with local now would make every applied row the newest row on this device, which
 * would then win the next merge on the peer, which would re-stamp it in turn -- two devices writing
 * the same book to each other forever. The one place a clock could plausibly be read is the one
 * place reading it would be a defect, so there is nothing for it to do.
 */
@Singleton
class WatchSyncEngine(
  private val link: WatchLink,
  private val credentials: WatchSyncCredentialStore,
  private val progress: WatchSyncProgressStore,
) {

  /**
   * The production wiring: the real Keystore-backed store and the real Room DAO, adapted to the two
   * ports above. Same shape as `SetupViewModel`'s secondary `@Inject` constructor and for the same
   * reason.
   */
  @Inject
  constructor(
    link: WatchLink,
    credentialStore: CredentialStore,
    mediaProgressDao: MediaProgressDao,
  ) : this(
    link = link,
    credentials = object : WatchSyncCredentialStore {
      override suspend fun load(): SubsonicCredentials? = credentialStore.load()
      override suspend fun save(credentials: SubsonicCredentials) = credentialStore.save(credentials)
    },
    progress = object : WatchSyncProgressStore {
      override suspend fun recentlyPlayed(limit: Int) = mediaProgressDao.recentlyPlayed(limit)
      override suspend fun findIn(mediaIds: List<String>) = mediaProgressDao.findIn(mediaIds)
      override suspend fun upsert(progress: MediaProgressEntity) = mediaProgressDao.upsert(progress)
    },
  )

  private var job: Job? = null

  /**
   * Begins applying whatever the peer publishes, on [scope].
   *
   * Cancels a previous collection rather than adding a second: two collectors would apply every
   * payload twice, which is harmless for the merge (it is idempotent) and misleading for anything
   * counting writes.
   */
  fun start(scope: CoroutineScope) {
    job?.cancel()
    job = scope.launch {
      link.incoming().collect { payload -> apply(payload) }
    }
  }

  /** Stops applying. The scope [start] was given is not cancelled -- it is the caller's. */
  fun stop() {
    job?.cancel()
    job = null
  }

  /** Publishes this device's credentials and its most recently played rows. */
  suspend fun publishLocalState() {
    val stored = credentials.load()
    link.publish(
      WatchSyncPayload(
        version = WatchSyncPayload.VERSION,
        credentials = stored?.let { CredentialSnapshot(it.baseUrl, it.username, it.password) },
        // `recentlyPlayed` and not `findAll`: the Data Layer caps an item at 100 KB, and the rows a
        // second device can use are the recent ones. Plan 2 Task 1's DAO already orders by
        // lastPlayedAt descending.
        progress = progress.recentlyPlayed(WatchSyncPayload.MAX_PROGRESS_ROWS)
          .map(ProgressSnapshot::of),
      ),
    )
  }

  /**
   * Applies a peer's payload. Returns how many progress rows were written.
   *
   * Credentials are taken **only when this device has none**. A watch that has been set up
   * independently, or a phone whose server moved, must not be silently repointed by whatever the
   * other device last published -- and the two devices have no way to tell which of two different
   * configurations is the newer intention.
   */
  suspend fun apply(payload: WatchSyncPayload): Int {
    payload.credentials?.let { snapshot ->
      if (credentials.load() == null) {
        credentials.save(SubsonicCredentials(snapshot.baseUrl, snapshot.username, snapshot.password))
      }
    }

    val remote = payload.progress.map(ProgressSnapshot::toEntity)
    if (remote.isEmpty()) return 0
    val local = progress.findIn(remote.map { it.mediaId })
    val updates = ProgressMerge.updates(local, remote)
    updates.forEach { progress.upsert(it) }
    return updates.size
  }
}
