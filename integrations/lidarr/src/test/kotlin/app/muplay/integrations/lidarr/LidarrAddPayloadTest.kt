package app.muplay.integrations.lidarr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The exact JSON this client asks Lidarr to add.
 *
 * A pure test over a pure function, with no server in it, because this is where every field belongs
 * under a microscope. The plan's brief names the failure this guards against by name: *a test
 * asserting a request was submitted rather than that its body carried the right identifier.*
 *
 * **Why "the right identifier" is not pedantry, measured rather than argued.** A live
 * `3.1.0.4875-ls40` answering `term=kind of blue` returns **seven** albums, of which **four** are
 * titled exactly `Kind of Blue` by four different artists and a fifth is `KIND OF BLUE`. The title
 * a user reads separates almost none of them; the only field that does is `foreignAlbumId`. A
 * builder that dropped or fixed that field would add somebody else's album and every visible thing
 * about the result would look right.
 */
class LidarrAddPayloadTest {

  private val json = Json

  private fun candidate(
    albumId: String = "album-mbid",
    artistId: String = "artist-mbid",
    extra: String = "",
  ): LidarrAlbumCandidate {
    val raw = json.parseToJsonElement(
      """
      {"foreignAlbumId":"$albumId","title":"An album","monitored":false,
       "unmodelledAlbumField":"survive me"$extra,
       "artist":{"foreignArtistId":"$artistId","artistName":"An artist","id":0,
                 "unmodelledArtistField":"survive me too"}}
      """.trimIndent(),
    ).jsonObject
    return LidarrAlbumCandidate(
      foreignAlbumId = albumId, title = "An album", disambiguation = null, albumType = null,
      releaseDate = null, remoteCoverUrl = null, artistName = "An artist",
      foreignArtistId = artistId, alreadyAdded = false, raw = raw,
    )
  }

  private fun targets(
    path: String = "/music", quality: Int = 2, metadata: Int = 3,
    monitor: String = "all", newItems: String = "none",
  ) = LidarrAddTargets(path, quality, metadata, monitor, newItems)

  private fun build(
    candidate: LidarrAlbumCandidate = candidate(),
    targets: LidarrAddTargets = targets(),
    searchNow: Boolean = true,
  ): JsonObject = LidarrAddPayload.build(candidate, targets, searchNow)

  @Test
  fun `the album identifier on the body is the one that was asked for`() {
    // Two observations. A payload builder that hardcoded the id passes neither this nor any
    // downstream test, and this is the exact assertion the plan's brief singles out.
    assertThat(build(candidate(albumId = "mbid-a"))["foreignAlbumId"]!!.jsonPrimitive.content)
      .isEqualTo("mbid-a")
    assertThat(build(candidate(albumId = "mbid-b"))["foreignAlbumId"]!!.jsonPrimitive.content)
      .isEqualTo("mbid-b")
  }

  @Test
  fun `the nested artist identifier is the one that was asked for`() {
    assertThat(
      build(candidate(artistId = "art-a"))["artist"]!!.jsonObject["foreignArtistId"]!!
        .jsonPrimitive.content,
    ).isEqualTo("art-a")
    assertThat(
      build(candidate(artistId = "art-b"))["artist"]!!.jsonObject["foreignArtistId"]!!
        .jsonPrimitive.content,
    ).isEqualTo("art-b")
  }

  /**
   * The typed identity wins over whatever the raw element carried, at two values each.
   *
   * Not the same assertion as the two above. Those prove the field is not a constant; this one
   * proves which of the two available sources it comes from, by making them **disagree**. A builder
   * that read `raw["foreignAlbumId"]` instead of `candidate.foreignAlbumId` passes both tests above
   * and fails this one -- and it is not a hypothetical builder, it is the shorter one.
   */
  @Test
  fun `the typed identity overrides a disagreeing raw element`() {
    val disagreeing = candidate(albumId = "typed-album", artistId = "typed-artist").copy(
      raw = json.parseToJsonElement(
        """{"foreignAlbumId":"raw-album","artist":{"foreignArtistId":"raw-artist"}}""",
      ).jsonObject,
    )

    val body = LidarrAddPayload.build(disagreeing, targets(), searchNow = true)

    assertThat(body["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo("typed-album")
    assertThat(body["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo("typed-artist")
  }

  @Test
  fun `every field the lookup sent that this client does not model survives`() {
    // The reason `raw` exists. Lidarr's own `openapi.json` is absent on this build entirely (the
    // pinned 3.1.0.4875 answers `/api/v1/openapi.json` with a 404), so there is no published
    // statement of what the POST wants at all -- the only complete one is what Lidarr sent, and a
    // payload rebuilt from typed fields silently drops the rest.
    val body = build()

    assertThat(body["unmodelledAlbumField"]!!.jsonPrimitive.content).isEqualTo("survive me")
    assertThat(body["artist"]!!.jsonObject["unmodelledArtistField"]!!.jsonPrimitive.content)
      .isEqualTo("survive me too")
  }

  /**
   * The measured reason the passthrough is not merely tidy.
   *
   * A lookup element for an artist **already in this Lidarr** carries `artist.id` -- measured on
   * the live instance, `term=TQ They Never Saw Me Coming` returns elements whose nested artist has
   * `"id": 2` while the search that found a brand-new artist omits the key entirely. That id is how
   * the server attaches the new album to the existing artist row instead of creating a second one,
   * and this client never reads it, models it, or restates it. It survives only because the raw
   * element does.
   */
  @Test
  fun `an existing artist's database id rides along untouched`() {
    val known = candidate().copy(
      raw = json.parseToJsonElement(
        """{"foreignAlbumId":"m","artist":{"foreignArtistId":"a","artistName":"n","id":2}}""",
      ).jsonObject,
    )

    assertThat(
      LidarrAddPayload.build(known, targets(), searchNow = true)["artist"]!!.jsonObject["id"]!!
        .jsonPrimitive.int,
    ).isEqualTo(2)
  }

  @Test
  fun `the three add targets are written onto the nested artist`() {
    // Two values each, so none of the three can be a constant.
    val a = build(
      targets = targets(path = "/music", quality = 2, metadata = 3),
    )["artist"]!!.jsonObject
    val b = build(
      targets = targets(path = "/archive", quality = 20, metadata = 30),
    )["artist"]!!.jsonObject

    assertThat(a["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
    assertThat(b["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/archive")
    assertThat(a["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(b["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(20)
    assertThat(a["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(3)
    assertThat(b["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(30)
  }

  /**
   * The two profile ids are not interchangeable, and a swap is invisible to every test above.
   *
   * `qualityProfileId` decides what gets downloaded and `metadataProfileId` decides which releases
   * exist at all. Measured on the live instance: both are validated only for existence and for
   * being greater than zero (`ValidId`), and every id that exists in one table also exists in the
   * other on a default install -- 1..3 quality, 1..2 metadata -- so a swapped pair is accepted with
   * a **201** and no complaint. This asserts them at values that cannot be confused with each
   * other's.
   */
  @Test
  fun `the quality and metadata profile ids are not swapped`() {
    val artist = build(targets = targets(quality = 11, metadata = 22))["artist"]!!.jsonObject

    assertThat(artist["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(11)
    assertThat(artist["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(22)
  }

  @Test
  fun `both monitored flags are set, because an unmonitored album is never fetched`() {
    val body = build()

    assertThat(body["monitored"]!!.jsonPrimitive.boolean).isTrue()
    assertThat(body["artist"]!!.jsonObject["monitored"]!!.jsonPrimitive.boolean).isTrue()
    // ...and the lookup element's own `monitored: false` was overwritten, not merged around. The
    // fixture candidate really does carry `"monitored":false`, so this arm is live.
    assertThat(candidate().raw["monitored"]!!.jsonPrimitive.boolean).isFalse()
    assertThat(build(candidate())["monitored"]!!.jsonPrimitive.boolean).isTrue()
  }

  @Test
  fun `the monitor options come from the targets, on the artist and not on the album`() {
    val a = build(targets = targets(monitor = "all", newItems = "none"))["artist"]!!.jsonObject
    val b = build(targets = targets(monitor = "first", newItems = "all"))["artist"]!!.jsonObject

    assertThat(a["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content).isEqualTo("all")
    assertThat(b["addOptions"]!!.jsonObject["monitor"]!!.jsonPrimitive.content).isEqualTo("first")
    assertThat(a["monitorNewItems"]!!.jsonPrimitive.content).isEqualTo("none")
    assertThat(b["monitorNewItems"]!!.jsonPrimitive.content).isEqualTo("all")
  }

  /**
   * **Trap 2.** `AddAlbumOptions` is `{ AddType, SearchForNewAlbum }` — there is no `monitor` and
   * no `monitored` on it. Lidarr binds JSON case-insensitively and drops unknown members without
   * complaint, so a `monitor` placed here would be accepted and ignored, which is the quietest
   * possible way to be wrong.
   */
  @Test
  fun `the album's addOptions carries only searchForNewAlbum`() {
    val addOptions = build()["addOptions"]!!.jsonObject

    assertThat(addOptions.keys).containsExactly("searchForNewAlbum")
  }

  /**
   * The other side of Trap 2: the artist's `addOptions` carries exactly the three members
   * `AddArtistOptions : MonitoringOptions` declares that this client sets, and nothing else.
   *
   * `containsExactlyInAnyOrder` rather than `containsExactly`, because the key *order* inside this
   * object is not something Lidarr cares about and pinning it would be an assertion about
   * `buildJsonObject` rather than about the payload.
   */
  @Test
  fun `the artist's addOptions carries exactly the three members this client sets`() {
    val addOptions = build()["artist"]!!.jsonObject["addOptions"]!!.jsonObject

    assertThat(addOptions.keys)
      .containsExactlyInAnyOrder("monitor", "monitored", "searchForMissingAlbums")
  }

  /**
   * A lookup element that already carried an `addOptions` is **replaced**, not merged into.
   *
   * The passthrough is a decorate, and a decorate that deep-merged would let a stray member of the
   * source element survive into a sub-object whose exact key set the two tests above pin. Nothing
   * a real lookup returns carries `addOptions` today; a Lidarr upgrade adding one would turn that
   * from true into false silently.
   */
  @Test
  fun `an addOptions on the lookup element does not survive into the payload`() {
    val polluted = candidate().copy(
      raw = json.parseToJsonElement(
        """
        {"foreignAlbumId":"m","addOptions":{"addType":"automatic","searchForNewAlbum":false},
         "artist":{"foreignArtistId":"a","artistName":"n",
                   "addOptions":{"searchForMissingAlbums":true,"monitor":"none"}}}
        """.trimIndent(),
      ).jsonObject,
    )

    val body = LidarrAddPayload.build(polluted, targets(), searchNow = true)

    assertThat(body["addOptions"]!!.jsonObject.keys).containsExactly("searchForNewAlbum")
    assertThat(body["addOptions"]!!.jsonObject["searchForNewAlbum"]!!.jsonPrimitive.boolean)
      .isTrue()
    val artistAddOptions = body["artist"]!!.jsonObject["addOptions"]!!.jsonObject
    assertThat(artistAddOptions.keys)
      .containsExactlyInAnyOrder("monitor", "monitored", "searchForMissingAlbums")
    assertThat(artistAddOptions["searchForMissingAlbums"]!!.jsonPrimitive.boolean).isFalse()
    assertThat(artistAddOptions["monitor"]!!.jsonPrimitive.content).isEqualTo("all")
  }

  @Test
  fun `searchForNewAlbum is whatever the caller asked for`() {
    assertThat(
      build(searchNow = true)["addOptions"]!!.jsonObject["searchForNewAlbum"]!!
        .jsonPrimitive.boolean,
    ).isTrue()
    assertThat(
      build(searchNow = false)["addOptions"]!!.jsonObject["searchForNewAlbum"]!!
        .jsonPrimitive.boolean,
    ).isFalse()
  }

  /**
   * **Trap 1, and the most important assertion in this task.**
   *
   * `AddAlbumService`: `if (artist.AddOptions?.SearchForMissingAlbums ?? false)
   * { album.AddOptions.SearchForNewAlbum = false; }`. A payload that sets both gets a 201, a
   * monitored album, and no search at all — nothing is downloaded and nothing says why. Upstream
   * issue Lidarr #5012.
   *
   * Asserted at **both** values of `searchNow`, because the interaction is only visible when the
   * caller asked for a search.
   *
   * **What this assertion is, and is not, evidence of** — measured, because the honest statement is
   * narrower than the one this test's name invites. Against the live `3.1.0.4875-ls40` with a
   * working positive control (a hand-pushed `AlbumSearchCommand` appears in `/api/v1/command` and
   * completes), an add carrying `addOptions.searchForNewAlbum: true` produced **no** `AlbumSearch`
   * command at all — for a new artist, for an existing one, and for a minimal payload with no
   * `artist.addOptions` whatsoever. So on that build the album-add search does not happen for
   * *either* value of the artist flag, and this container cannot discriminate the trap. The pin
   * stays because Lidarr's own source says what the flag does when the search does work, and
   * sending `false` cannot make anything worse; the transcript is in task-6-report.md.
   */
  @Test
  fun `the artist never asks for a missing-albums search, which would cancel the album search`() {
    for (searchNow in listOf(true, false)) {
      val artistAddOptions =
        build(searchNow = searchNow)["artist"]!!.jsonObject["addOptions"]!!.jsonObject
      assertThat(artistAddOptions["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
        .describedAs("searchForMissingAlbums with searchNow=%s", searchNow)
        .isFalse()
    }
  }

  @Test
  fun `addType is not sent, because the server overwrites it on every album post`() {
    // `AddAlbumService` sets `album.AddOptions.AddType = AlbumAddType.Manual` unconditionally.
    // Sending it is noise that reads like a decision.
    assertThat(build()["addOptions"]!!.jsonObject.keys).doesNotContain("addType")
  }

  /**
   * An artist already in this Lidarr comes back from lookup with a real `path`. `AlbumController`
   * applies the `rootFolderPath` rule only `When(s.Artist.Path.IsNullOrWhiteSpace())`, so leaving
   * an existing path alone is correct — the artist keeps the folder it already lives in.
   */
  @Test
  fun `an existing artist's own path is left alone`() {
    val existing = candidate().let { c ->
      c.copy(
        raw = Json.parseToJsonElement(
          """{"foreignAlbumId":"m","artist":{"foreignArtistId":"a","id":7,"path":"/music/An artist"}}""",
        ).jsonObject,
      )
    }

    val artist = LidarrAddPayload.build(existing, targets(), searchNow = true)["artist"]!!.jsonObject

    assertThat(artist["path"]!!.jsonPrimitive.content).isEqualTo("/music/An artist")
    // `rootFolderPath` is still written: it is harmless when `path` is set (the validator skips
    // its rule) and required when it is not, and branching here would add an untestable-in-
    // isolation decision to a builder that has no business making it.
    assertThat(artist["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
  }

  @Test
  fun `a candidate whose raw element has no artist object still produces a valid nested artist`() {
    // Defensive but reachable: `toCandidate` requires `artist.foreignArtistId` to exist, so this
    // shape cannot come from `lookupAlbums` -- but `LidarrAddPayload` is a public object and a
    // caller could construct a candidate by hand. It must not produce a body with no `artist`,
    // which Lidarr rejects with a validation error about a null artist -- measured, `NotNullValidator`
    // on `propertyName: "Artist"`.
    val hand = LidarrAlbumCandidate(
      foreignAlbumId = "m", title = "t", disambiguation = null, albumType = null,
      releaseDate = null, remoteCoverUrl = null, artistName = "n", foreignArtistId = "a",
      alreadyAdded = false, raw = Json.parseToJsonElement("""{"foreignAlbumId":"m"}""").jsonObject,
    )

    val artist = LidarrAddPayload.build(hand, targets(), searchNow = true)["artist"]!!.jsonObject

    assertThat(artist["foreignArtistId"]!!.jsonPrimitive.content).isEqualTo("a")
    assertThat(artist["artistName"]!!.jsonPrimitive.content).isEqualTo("n")
    // ...and every field the controller validates is still there, which is the whole point of the
    // arm: a body with an artist object missing these is a 400, not a silent partial add.
    assertThat(artist["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(artist["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(3)
    assertThat(artist["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
  }

  /**
   * A non-object `artist` on the raw element is treated as no artist at all, rather than crashing.
   *
   * The `as?` in the builder has two arms and only one of them is reachable from `lookupAlbums`.
   * This is the other, and it is the arm a `ClassCastException` would live in.
   */
  @Test
  fun `a raw element whose artist is not an object still produces a valid nested artist`() {
    val odd = candidate().copy(
      raw = json.parseToJsonElement("""{"foreignAlbumId":"m","artist":"not an object"}""")
        .jsonObject,
    )

    val artist = LidarrAddPayload.build(odd, targets(), searchNow = true)["artist"]!!.jsonObject

    assertThat(artist["foreignArtistId"]!!.jsonPrimitive.content).isEqualTo("artist-mbid")
    assertThat(artist["artistName"]!!.jsonPrimitive.content).isEqualTo("An artist")
  }

  /**
   * The whole body, against a real lookup element from the pinned container.
   *
   * The synthetic candidates above each isolate one field; this one proves the builder produces a
   * payload Lidarr **accepted**, from bytes Lidarr **sent**. The element is the third of the seven
   * in `fixtures/lidarr/album-lookup.json` — `Kind of Blue` by *Swiss Blues Authority*, which is
   * deliberately not the Miles Davis record three other elements share a title with — and posting
   * exactly this body to the live instance answered **201** with
   * `foreignAlbumId: c35e782d-be05-380b-ac26-1b9c48878ee5`. Transcript in task-6-report.md.
   *
   * The sharpest thing this fixture carries, and the reason a synthetic candidate could not stand
   * in for it: the element's own nested artist arrives with **`qualityProfileId: 0` and
   * `metadataProfileId: 0`**. Both are rejected by `ValidId` — measured against the live instance,
   * `"'Artist. Metadata Profile Id' must be greater than '0'."` So the order in which the builder
   * writes the passthrough and the targets is not a style question: the wrong order sends two zeros
   * and the add is a 400 the user cannot act on.
   */
  @Test
  fun `the real lookup element from a pinned lidarr builds the body that instance accepted`() {
    val element = json.parseToJsonElement(readFixture("lidarr/album-lookup.json"))
      .let { it as kotlinx.serialization.json.JsonArray }
      .map { it.jsonObject }
      .single { it["foreignAlbumId"]!!.jsonPrimitive.content == SWISS_BLUES_ALBUM_MBID }
    val real = LidarrAlbumCandidate(
      foreignAlbumId = SWISS_BLUES_ALBUM_MBID,
      title = element["title"]!!.jsonPrimitive.content,
      disambiguation = null, albumType = null, releaseDate = null, remoteCoverUrl = null,
      artistName = element["artist"]!!.jsonObject["artistName"]!!.jsonPrimitive.content,
      foreignArtistId = SWISS_BLUES_ARTIST_MBID,
      alreadyAdded = false, raw = element,
    )

    val body = LidarrAddPayload.build(
      real,
      LidarrAddTargets("/music", 2, 2, "all", "none"),
      searchNow = false,
    )

    // The identifier, first: six of the seven fixture elements are titled `Kind of Blue`, so this
    // is the only field that says which record the instance was asked for.
    assertThat(body["foreignAlbumId"]!!.jsonPrimitive.content).isEqualTo(SWISS_BLUES_ALBUM_MBID)
    assertThat(body["artist"]!!.jsonObject["foreignArtistId"]!!.jsonPrimitive.content)
      .isEqualTo(SWISS_BLUES_ARTIST_MBID)
    // ...and it is not the Miles Davis element, which is the failure mode this whole task exists
    // to prevent. Both are in the fixture and both are titled `Kind of Blue`.
    assertThat(body["foreignAlbumId"]!!.jsonPrimitive.content).isNotEqualTo(MILES_ALBUM_MBID)
    assertThat(body["title"]!!.jsonPrimitive.content).isEqualTo("Kind of Blue")

    // The element really does arrive with both profile ids at zero -- the positive half, without
    // which the two assertions after it prove nothing about ordering.
    val rawArtist = element["artist"]!!.jsonObject
    assertThat(rawArtist["qualityProfileId"]!!.jsonPrimitive.int).isZero()
    assertThat(rawArtist["metadataProfileId"]!!.jsonPrimitive.int).isZero()
    assertThat(rawArtist["monitored"]!!.jsonPrimitive.boolean).isFalse()

    // Everything the controller validates, present and from the targets rather than the element.
    val artist = body["artist"]!!.jsonObject
    assertThat(artist["qualityProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(artist["metadataProfileId"]!!.jsonPrimitive.int).isEqualTo(2)
    assertThat(artist["rootFolderPath"]!!.jsonPrimitive.content).isEqualTo("/music")
    assertThat(artist["monitored"]!!.jsonPrimitive.boolean).isTrue()
    assertThat(artist["addOptions"]!!.jsonObject["searchForMissingAlbums"]!!.jsonPrimitive.boolean)
      .isFalse()
    assertThat(body["addOptions"]!!.jsonObject["searchForNewAlbum"]!!.jsonPrimitive.boolean)
      .isFalse()

    // The real element's own richness survived: nineteen top-level keys, of which this client
    // models six, and a nested `releases` array nothing here has a type for.
    assertThat(body["releases"]).isNotNull()
    assertThat(body.keys).contains("ratings", "images", "links", "media", "secondaryTypes")
  }

  private companion object {
    /** `Kind of Blue` by Swiss Blues Authority — element 3 of the seven in the lookup fixture. */
    const val SWISS_BLUES_ALBUM_MBID = "c35e782d-be05-380b-ac26-1b9c48878ee5"
    const val SWISS_BLUES_ARTIST_MBID = "7afb9e2b-0f0d-4be0-887d-b856f63d4ea3"

    /** `Kind of Blue` by Miles Davis — element 1, same title, different record. */
    const val MILES_ALBUM_MBID = "8e8a594f-2175-38c7-a871-abb68ec363e7"
  }
}
