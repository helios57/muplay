package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One `media_progress` row, on the wire. */
@Serializable
data class ProgressSnapshot(
  val mediaId: String,
  val positionMs: Long,
  val isFinished: Boolean,
  val lastPlayedAtEpochMs: Long,
  val speed: Float,
  val skipSilence: Boolean,
  val gainDb: Float,
) {
  fun toEntity(): MediaProgressEntity = MediaProgressEntity(
    mediaId = mediaId,
    positionMs = positionMs,
    isFinished = isFinished,
    lastPlayedAtEpochMs = lastPlayedAtEpochMs,
    speed = speed,
    skipSilence = skipSilence,
    gainDb = gainDb,
  )

  companion object {
    fun of(entity: MediaProgressEntity) = ProgressSnapshot(
      mediaId = entity.mediaId,
      positionMs = entity.positionMs,
      isFinished = entity.isFinished,
      lastPlayedAtEpochMs = entity.lastPlayedAtEpochMs,
      speed = entity.speed,
      skipSilence = entity.skipSilence,
      gainDb = entity.gainDb,
    )
  }
}

/**
 * The server the other device should talk to, and how.
 *
 * The password crosses the wrist in cleartext **inside the payload**, and that is not a shortcut:
 * spec section 4 records that Navidrome's token auth needs the password in cleartext at request
 * time, so there is no hashed-at-rest form to send -- `CredentialStore`'s own header says the same
 * thing from the other side. What protects it is the channel and the store: the Wearable Data Layer
 * is encrypted between paired devices and scoped to items written by an app with the same package
 * **and signing key**, and the receiving device puts it straight into `CredentialStore`, which is
 * Android Keystore backed. It is never logged and never written to a file.
 *
 * [toString] is hand-written to omit [password] for the same reason `SubsonicCredentials`' is: a
 * `data class` toString names every constructor property, and this type is one `Log.d` away from
 * being the place the plaintext leaks.
 */
@Serializable
data class CredentialSnapshot(
  val baseUrl: String,
  val username: String,
  val password: String,
) {
  override fun toString(): String =
    "CredentialSnapshot(baseUrl='$baseUrl', username='$username', password=<redacted>)"
}

/**
 * Everything one device tells the other.
 *
 * One payload rather than two channels, because the two facts arrive together in the only case that
 * matters: a watch that has just been set up needs credentials *and* the positions it should show.
 */
@Serializable
data class WatchSyncPayload(
  val version: Int,
  val credentials: CredentialSnapshot?,
  val progress: List<ProgressSnapshot>,
) {
  companion object {
    /** Bumped whenever this shape changes. A payload from a newer version is refused, not guessed at. */
    const val VERSION: Int = 1

    /**
     * The Wearable Data Layer caps one data item at 100 KB. At roughly 130 bytes a row that is
     * thousands of rows, but a cap that is a measured number beats one that is discovered in the
     * field: the most recently played rows are the ones a second device can use.
     *
     * A **sender-side** policy, applied by [WatchSyncEngine.publishLocalState] and deliberately not
     * by [decode]. A receiver that refused an over-long payload would be refusing one built by a
     * peer running a build whose cap is different, which is the same "half-read a payload this
     * build does not understand" hazard [decode] exists to avoid -- except that here the payload is
     * perfectly understood and merging more rows than 200 costs nothing.
     */
    const val MAX_PROGRESS_ROWS: Int = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payload: WatchSyncPayload): ByteArray =
      json.encodeToString(serializer(), payload).toByteArray()

    /**
     * `null` for anything this build cannot fully understand.
     *
     * A newer version on the other device is the case worth naming: applying the half of a payload
     * whose shape this build knows is how a progress row ends up at a position nobody was ever at,
     * and there is no user-visible signal when it happens.
     *
     * One `catch`, not two. The plan's listing caught `SerializationException` **and**
     * `IllegalArgumentException`; measured, the first already *is* the second
     * (`kotlinx.serialization.SerializationException : IllegalArgumentException`), so the second arm
     * was unreachable -- an uncoverable branch that would have sat permanently below any floor over
     * this class. `ByteArray.decodeToString` cannot throw: invalid UTF-8 becomes U+FFFD, which then
     * fails the parse like any other malformed input.
     */
    fun decode(bytes: ByteArray): WatchSyncPayload? = try {
      json.decodeFromString(serializer(), bytes.decodeToString())
        .takeIf { it.version == VERSION }
    } catch (e: SerializationException) {
      null
    }
  }
}
