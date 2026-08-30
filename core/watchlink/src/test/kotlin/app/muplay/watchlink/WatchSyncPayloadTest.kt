package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity
import kotlinx.serialization.descriptors.SerialDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WatchSyncPayloadTest {

  @Test
  fun `a payload round-trips with every field intact`() {
    val payload = WatchSyncPayload(
      version = WatchSyncPayload.VERSION,
      credentials = CredentialSnapshot("https://music.example", "luc", "hunter2"),
      progress = listOf(
        ProgressSnapshot("b-1", 11_500, false, 1_700_000_000_000, 1.4f, true, -3f),
        ProgressSnapshot("b-2", 0, true, 1_600_000_000_000, 1.0f, false, 0f),
      ),
    )

    val decoded = WatchSyncPayload.decode(WatchSyncPayload.encode(payload))

    // Field by field, and with two rows that differ in every field, so a decoder that dropped one
    // fails here rather than in a car six months later.
    assertThat(decoded).isEqualTo(payload)
    assertThat(decoded?.progress?.map { it.positionMs }).containsExactly(11_500, 0)
    assertThat(decoded?.progress?.map { it.isFinished }).containsExactly(false, true)
    assertThat(decoded?.progress?.map { it.speed }).containsExactly(1.4f, 1.0f)
    assertThat(decoded?.progress?.map { it.skipSilence }).containsExactly(true, false)
    assertThat(decoded?.progress?.map { it.gainDb }).containsExactly(-3f, 0f)
    assertThat(decoded?.credentials?.password).isEqualTo("hunter2")
  }

  @Test
  fun `the row order survives, because it is the order the sender chose`() {
    val payload = WatchSyncPayload(
      WatchSyncPayload.VERSION,
      credentials = null,
      progress = listOf(snapshot("c"), snapshot("a"), snapshot("b")),
    )

    assertThat(WatchSyncPayload.decode(WatchSyncPayload.encode(payload))?.progress?.map { it.mediaId })
      .containsExactly("c", "a", "b")
  }

  @Test
  fun `a payload from a future version is refused rather than half-read`() {
    val future = WatchSyncPayload.encode(
      WatchSyncPayload(version = WatchSyncPayload.VERSION + 1, credentials = null, progress = emptyList()),
    )

    // The other device may be a newer build of this app. Applying half of a payload whose shape
    // this build does not know is how a progress row ends up at a position nobody was ever at.
    assertThat(WatchSyncPayload.decode(future)).isNull()
  }

  /**
   * The other side of the same guard. `decode` tests `== VERSION`, not `<= VERSION`, so a payload
   * from an **older** build is refused too -- and that is deliberate rather than incidental: this
   * build has no reader for a shape it has never seen, in either direction.
   */
  @Test
  fun `a payload from an older version is refused too`() {
    val ancient = WatchSyncPayload.encode(
      WatchSyncPayload(version = WatchSyncPayload.VERSION - 1, credentials = null, progress = emptyList()),
    )

    assertThat(WatchSyncPayload.decode(ancient)).isNull()
  }

  @Test
  fun `malformed bytes decode to null rather than throwing into a listener`() {
    // The last two are the shapes a listener actually meets: a well-formed JSON document of the
    // wrong shape, and one missing a required field. Both parse and neither is a payload.
    assertThat(
      listOf(
        ByteArray(0),
        "not json".toByteArray(),
        "{".toByteArray(),
        "[]".toByteArray(),
        """{"version":1}""".toByteArray(),
      ).map(WatchSyncPayload::decode),
    ).containsExactly(null, null, null, null, null)
  }

  @Test
  fun `a payload with no credentials is legal and carries none`() {
    val decoded = WatchSyncPayload.decode(
      WatchSyncPayload.encode(WatchSyncPayload(WatchSyncPayload.VERSION, null, listOf(snapshot("a")))),
    )

    assertThat(decoded?.credentials).isNull()
    assertThat(decoded?.progress).hasSize(1)
  }

  /**
   * The wire is UTF-8 JSON, and this is the one test that says so out loud.
   *
   * Without it every assertion above would still pass over a hand-rolled binary format, a Java
   * `Serializable` blob, or anything else symmetric -- and the format is the thing two independently
   * updated devices have to agree on. It also pins `version` as a top-level key, which is what makes
   * the refusal above possible for a *future* shape at all.
   */
  @Test
  fun `the wire form is UTF-8 JSON with version at the top level`() {
    val wire = WatchSyncPayload
      .encode(WatchSyncPayload(WatchSyncPayload.VERSION, null, listOf(snapshot("a"))))
      .decodeToString()

    assertThat(wire).startsWith("{").endsWith("}").contains("\"version\":1")
    assertThat(wire).contains("\"credentials\":null")
  }

  /**
   * `ProgressSnapshot.of` and `toEntity` are each other's inverse.
   *
   * Asserted as a round trip through the *entity*, not as two field-by-field readings, because the
   * failure this protects against is a field added to `MediaProgressEntity` and wired into only one
   * of the two directions -- which no per-field assertion written today would notice.
   */
  @Test
  fun `a progress row survives the trip to the wire and back`() {
    val entity = MediaProgressEntity(
      mediaId = "b-7",
      positionMs = 91_235,
      isFinished = true,
      lastPlayedAtEpochMs = 1_700_000_000_000,
      speed = 1.4f,
      skipSilence = true,
      gainDb = -3.5f,
    )

    assertThat(ProgressSnapshot.of(entity).toEntity()).isEqualTo(entity)
  }

  /**
   * The password never appears in a `toString`, which is the one place it would otherwise reach a
   * log. Same hand-written `toString` as `SubsonicCredentials`, for the same reason, and asserted
   * here rather than assumed because `data class` regenerates the leaking form on any edit.
   */
  @Test
  fun `a credential snapshot does not print its password`() {
    val snapshot = CredentialSnapshot("https://music.example", "luc", "hunter2")

    assertThat(snapshot.toString()).contains("music.example", "luc", "<redacted>").doesNotContain("hunter2")
  }

  /**
   * The JSON key names, pinned.
   *
   * They are the actual contract between two devices running two different builds of this app, and
   * they are the one part of it a refactor changes silently: renaming `positionMs` to `posMs` keeps
   * every round-trip test above green (both halves rename together) and makes an older peer's
   * payload undecodable in the field. Read off the descriptors rather than off a sample document so
   * that a nested type -- `CredentialSnapshot`, which no test can encode on its own -- is covered
   * too.
   *
   * `getElementName` and `elementsCount` rather than the `elementNames` extension: the latter is
   * `@ExperimentalSerializationApi` and this needs no opt-in.
   */
  @Test
  fun `the wire field names are the contract between two builds`() {
    assertThat(fieldNames(WatchSyncPayload.serializer().descriptor))
      .containsExactly("version", "credentials", "progress")
    assertThat(fieldNames(CredentialSnapshot.serializer().descriptor))
      .containsExactly("baseUrl", "username", "password")
    assertThat(fieldNames(ProgressSnapshot.serializer().descriptor))
      .containsExactly(
        "mediaId", "positionMs", "isFinished", "lastPlayedAtEpochMs", "speed", "skipSilence", "gainDb",
      )
  }

  private companion object {
    fun snapshot(id: String) = ProgressSnapshot(id, 1_000, false, 100, 1.0f, false, 0f)

    fun fieldNames(descriptor: SerialDescriptor): List<String> =
      (0 until descriptor.elementsCount).map(descriptor::getElementName)
  }
}
