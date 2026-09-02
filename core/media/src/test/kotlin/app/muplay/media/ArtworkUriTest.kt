package app.muplay.media

import app.muplay.cast.net.CredentialQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The two halves of the artwork identity, and the property that makes it a security fix rather than
 * a rename: **whatever goes in, nothing credential-shaped comes out**.
 *
 * On the JVM tier because it is a string transformation and nothing else -- the `MediaItem` this
 * value ends up on can only exist on a device, and `PlatformSessionCredentialTest` is where that
 * half lives.
 */
class ArtworkUriTest {

  @Test
  fun `an id round-trips through the uri and back`() {
    // Two ids, differing in every character, so neither half can be a constant.
    assertThat(ArtworkUri.coverArtIdOf(ArtworkUri.of("al-abc_0"))).isEqualTo("al-abc_0")
    assertThat(ArtworkUri.coverArtIdOf(ArtworkUri.of("mf-9xy"))).isEqualTo("mf-9xy")
  }

  @Test
  fun `the uri names the scheme and the id, and nothing else`() {
    assertThat(ArtworkUri.of("al-abc_0")).isEqualTo("muplay-art:al-abc_0")
    assertThat(ArtworkUri.SCHEME).isEqualTo("muplay-art")
  }

  @Test
  fun `a song with no cover art gets no uri`() {
    // `null` rather than `muplay-art:`, because `MediaItems.of` renders this straight onto the item
    // and an empty-id URI is a bitmap load that can only ever fail.
    assertThat(ArtworkUri.of(null)).isNull()
    assertThat(ArtworkUri.of("")).isNull()
    assertThat(ArtworkUri.of("   ")).isNull()
  }

  @Test
  fun `anything that is not ours reads back as not ours`() {
    // This is what lets `MuPlayBitmapLoader` hand a URI to its delegate untouched instead of
    // guessing, and what stops a browse item's own http URL being mistaken for one of these.
    assertThat(ArtworkUri.coverArtIdOf(null)).isNull()
    assertThat(ArtworkUri.coverArtIdOf("https://nav.example/rest/getCoverArt?id=al-1")).isNull()
    assertThat(ArtworkUri.coverArtIdOf("content://media/external/audio/albumart/7")).isNull()
    assertThat(ArtworkUri.coverArtIdOf("muplay-art")).isNull()
    // The scheme with an empty id is not one of ours either: there is nothing to resolve.
    assertThat(ArtworkUri.coverArtIdOf("muplay-art:")).isNull()
  }

  @Test
  fun `no uri this builds can carry a credential, whatever it is handed`() {
    // The property the whole change exists for, stated where it can be exhaustive. Even handed a
    // whole authenticated URL as an "id" -- which no caller does, `QueueRepository` passes
    // `song.coverArtId` -- the result is a `muplay-art:` URI, and this asserts that the credential
    // scanner finds nothing in it because there is no query position for one to sit in.
    val leaked = "https://nav.example/rest/getCoverArt?id=al-1&u=me&t=abc&s=xyz"

    assertThat(CredentialQuery.carries(leaked)).isTrue()
    // ...and this is the one case where the guard is NOT enough on its own, which is worth stating
    // rather than hiding: the id is embedded verbatim, so a caller that passed a URL here would
    // still put those characters on the item. `MediaItems.of` is not that caller and
    // `QueueRepositoryTest.aSongWithCoverArtGetsAnArtworkIdRatherThanAnAuthenticatedUrl` is what
    // holds it there.
    assertThat(CredentialQuery.carries(ArtworkUri.of(leaked))).isTrue()
    // A real cover-art id has no query in it at all, and that is the whole population of real
    // inputs.
    assertThat(CredentialQuery.carries(ArtworkUri.of("al-7uq0TWT0LFFT65BHbdgSkX_0"))).isFalse()
  }
}
