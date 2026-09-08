package io.github.helios57.muplay.network

import java.lang.reflect.Modifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import retrofit2.http.GET

/**
 * **Book positions are local only** -- spec sections 2, 4 and 11, and a global constraint of every
 * plan in this project. Until this file existed that was prose, and `docs/PRIVACY.md` made a claim
 * nothing in the build could check.
 *
 * Rule 5: a gate reporting the absence of a problem must be provably incapable of staying quiet
 * when it did not run. So every assertion here is an **exact set**, never a `doesNotContain` over
 * four names somebody thought of: a `doesNotContain` passes just as happily against an empty list
 * produced by a broken reflection call, and it cannot notice a fifth way to send a position that
 * nobody named. Every set is also asserted non-empty first, for the same reason.
 *
 * ### What is actually being kept out
 *
 * Subsonic's write surface for listening state is `scrobble`, `nowPlaying`, `savePlayQueue`,
 * `savePlayQueueByIndex` and `createBookmark`. None of them is declared anywhere in this module, and
 * that is a **non-goal of this project rather than an omission**: every one of them puts a
 * listener's position, or what they are listening to *right now*, on a server.
 *
 * Spec section 4 records the specific hazard that makes the write path worse than useless anyway:
 * `createBookmark.position` is documented in **milliseconds** while `bookmarkPosition` on a `Child`
 * is documented in **seconds**, so a sync built on the pair puts every resume out by 1000x.
 *
 * ### The constraint is a position, not a write, and that distinction was bought
 *
 * This file used to assert that the client declared **no writes at all**, and named `setRating`
 * among the forbidden endpoints. That was an accurate description of the code and a slightly wrong
 * statement of the rule, and the difference surfaced the moment the thumbs landed (`de3d8df`): the
 * app now calls `setRating`, `createPlaylist` and `updatePlaylist`, deliberately and at the user's
 * request -- *"I need a thumbs up and down for each song while playing ... promoted are played
 * twice as much and added to a special playlist (promoted-username), so each user has his promoted
 * songs"*.
 *
 * A rating is a **statement about the music**; a position is **a private detail of how far somebody
 * got**. Subsonic's rating is per authenticated user by construction, which is what makes "each
 * user has his promoted songs" true with no user model of this app's own -- and it is exactly the
 * opposite decision from the one taken for audiobook positions, on purpose. What the three writes
 * put on a server is a star on a track, a playlist name, and track ids; none of them carries a
 * time, and none of them is issued while a book is playing.
 *
 * That correction does not weaken this file. The gate was never "the count of writes is zero" -- it
 * is "the set is exactly this, and growing it is a decision somebody had to type out". Every
 * assertion below is still an exact set over what reflection finds now.
 *
 * ### Why the exact lists, and not a scanner
 *
 * The lists below are what this module actually declares, read back by reflection at the moment the
 * test runs. Adding a method to [SubsonicSource] or an endpoint to [SubsonicApi] fails this file
 * with a message naming the constraint -- which is the point: the failure is a decision point, not
 * a bug report. The endpoint assertion is split into reads and writes so the decision cannot be
 * made by reflex: a new endpoint has to be classified before it compiles green, and classifying one
 * as a write is where somebody stops to ask what it sends.
 */
class LocalOnlyProgressTest {

  /**
   * What a future contributor reads when they add a method and this goes red.
   *
   * Attached to every assertion below rather than to one, because whichever of them fires is the
   * one whose message gets read.
   */
  private val why =
    "Book positions and now-playing state are LOCAL ONLY (spec sections 2, 4 and 11; " +
      "docs/PRIVACY.md). If the member you just added is scrobble / nowPlaying / savePlayQueue / " +
      "savePlayQueueByIndex / createBookmark -- or any other way to send a listening position or " +
      "what is playing right now to a server -- stop: that is a non-goal of this project, not an " +
      "omission. Anything else is a decision: add it to the list in this test, on the reads or the " +
      "writes side, and say in the commit message what it puts on somebody's server."

  @Test
  fun `the Subsonic port declares exactly these operations and no way to write progress`() {
    // Synthetics filtered: `streamUrl` has a defaulted `timeOffsetSeconds`, and Kotlin emits a
    // static `streamUrl$default` bridge into the interface for it. It is not a declared operation
    // and naming it here would be recording a compiler artefact as a product decision.
    val methods = SubsonicSource::class.java.methods
      .filterNot { it.isSynthetic || it.isBridge }
      .map { it.name }
      .distinct()

    assertThat(methods).describedAs("reflection found no methods at all; %s", why).isNotEmpty
    assertThat(methods).describedAs(why).containsExactlyInAnyOrder(
      "ping",
      "getMusicFolders",
      "getScanStatus",
      "getAlbumList2",
      "getAlbum",
      "getPlaylists",
      "getPlaylist",
      "search3",
      "getRandomSongs",
      "coverArtUrl",
      "streamUrl",
      "capabilities",
      // The three writes the thumbs need. See the class doc: a rating and a playlist of track ids,
      // no time in any of them.
      "setRating",
      "createPlaylist",
      "updatePlaylist",
    )
  }

  @Test
  fun `the shipping client adds nothing to that surface but a capability read`() {
    // The port is an interface, so a write could be added to [SubsonicClient] *without* touching
    // it and still be callable -- `:core:media` holds a `SubsonicClient` directly in its own test
    // helper, and nothing stops production doing the same. This is that hole closed.
    //
    // `getOpenSubsonicExtensions` is the one member the client has and the port does not, and it
    // is a read: `capabilities()` calls it, and it is public because `CapabilityNegotiatorTest`
    // drives it directly.
    val methods = SubsonicClient::class.java.declaredMethods
      .filterNot { it.isSynthetic || it.isBridge }
      .filter { Modifier.isPublic(it.modifiers) }
      .map { it.name }
      .distinct()

    assertThat(methods).describedAs("reflection found no methods at all; %s", why).isNotEmpty
    assertThat(methods).describedAs(why).containsExactlyInAnyOrder(
      "ping",
      "getMusicFolders",
      "getScanStatus",
      "getAlbumList2",
      "getAlbum",
      "getPlaylists",
      "getPlaylist",
      "search3",
      "getRandomSongs",
      "coverArtUrl",
      "streamUrl",
      "capabilities",
      "getOpenSubsonicExtensions",
      "setRating",
      "createPlaylist",
      "updatePlaylist",
    )
  }

  @Test
  fun `every declared endpoint is one of these reads, or one of these three writes`() {
    // The wire, which is the layer that actually matters: a method on the port could be
    // implemented against any endpoint at all, and these are the only ones this app can reach.
    //
    // Note what is NOT here: `rest/getCoverArt`. Cover art and stream URLs are *built* rather than
    // fetched -- they are handed to an image loader and to Media3, which use their own HTTP stacks
    // -- so they are not Retrofit endpoints and never appear in this list. The plan for this task
    // expected `getCoverArt` here, and expected these values without their `rest/` prefix; both
    // were wrong about the code.
    val reads = listOf(
      "rest/getAlbum",
      "rest/getAlbumList2",
      "rest/getMusicFolders",
      "rest/getOpenSubsonicExtensions",
      // `getPlaylist`/`getPlaylists` open and list a server-side playlist. They were here before
      // the app could write one, and they are still reads.
      "rest/getPlaylist",
      "rest/getPlaylists",
      "rest/getRandomSongs",
      "rest/getScanStatus",
      "rest/ping",
      "rest/search3",
    )

    // Every endpoint that changes something on somebody else's machine, and the whole list of them.
    // Each is here because the thumbs feature asked for it, and each is bounded:
    //
    //  - `setRating` sends a track id and a number from 0 to 5. Per authenticated user, so it is
    //    what makes "each user has his promoted songs" true without this app inventing users.
    //  - `createPlaylist` sends a name and track ids -- the `promoted-<username>` playlist, made
    //    once, on the first thumb up.
    //  - `updatePlaylist` sends a playlist id, track ids to add and *indexes* to remove, which is
    //    the only removal Subsonic offers.
    //
    // None carries a position, a timestamp or a now-playing state, and no audiobook path reaches
    // any of them. A fourth entry in this list is a change to what MuPlay puts on a stranger's
    // server, and `docs/PRIVACY.md`, `docs/PLAY-DATA-SAFETY.md` and `docs/REVIEWER-ACCESS.md` all
    // describe this list to people who will never read the code.
    val writes = listOf(
      "rest/createPlaylist",
      "rest/setRating",
      "rest/updatePlaylist",
    )

    val paths = SubsonicApi::class.java.declaredMethods
      .mapNotNull { it.getAnnotation(GET::class.java)?.value }
      .distinct()
      .sorted()

    assertThat(paths).describedAs("reflection found no endpoints at all; %s", why).isNotEmpty
    // One assertion over the union rather than two subset checks: an endpoint that is in neither
    // list has to fail, and a subset check would let it through on the other list's behalf.
    assertThat(paths).describedAs(why).containsExactlyElementsOf((reads + writes).sorted())
  }

  @Test
  fun `every endpoint carries a GET annotation and nothing else`() {
    // Subsonic is a GET protocol, so every *write* in it is still a GET -- which is exactly why the
    // assertion above is on the path list rather than on the HTTP verb, and why this test on its
    // own would prove nothing about progress. It is here because the set of annotation kinds is a
    // second, independent thing that must not grow: a `@POST`, a `@FormUrlEncoded` or a `@Body`
    // appearing on this interface would mean something new and unexamined.
    val declared = SubsonicApi::class.java.declaredMethods
    val annotationNames = declared
      .flatMap { method -> method.annotations.map { it.annotationClass.simpleName ?: "?" } }
      .distinct()

    // The premise: "no POSTs" is vacuously true of an interface with no methods.
    assertThat(declared).describedAs("reflection found no methods at all; %s", why).isNotEmpty
    assertThat(annotationNames).describedAs(why).containsExactly("GET")
    // ...and every method really is annotated, which the set above cannot say: one unannotated
    // method would leave the set exactly `[GET]`.
    assertThat(declared.filter { it.getAnnotation(GET::class.java) == null })
      .describedAs(why)
      .isEmpty()
  }
}
