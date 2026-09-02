package app.muplay.cast.net

import app.muplay.model.StreamFormat
import app.muplay.model.SubsonicCredentials
import app.muplay.network.SubsonicAuth
import app.muplay.network.SubsonicClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **The guard, held against the thing that mints what it looks for.**
 *
 * [CredentialQuery.AUTH_PARAMETERS] is the one place in `:core:cast` that knows Navidrome exists,
 * and a list of parameter names written down in a module that cannot see where they come from is
 * exactly the "hand-written list describing something discoverable" this repository has been bitten
 * by three times. So the cases below do not compare it with a second copy of the same list: they
 * build **real authenticated URLs through `SubsonicClient`** -- the class the production cast path
 * is handed a URL from -- and require this object to detect them.
 *
 * `:core:network` is a `testImplementation` dependency of this module and deliberately not an
 * `implementation` one, for the reason `core/cast/build.gradle.kts` states: nothing in this
 * module's main source set knows Navidrome exists. That is what makes this the right tier for the
 * pinning -- the coupling lives in a test, where it is visible, rather than in a dependency.
 */
class CredentialQueryTest {

  @Test
  fun `a real cover art url built by the client is detected`() {
    // The exact URL that reached a renderer for the whole of this bug's life.
    val url = client().coverArtUrl("al-abc_0", sizePx = 512)

    assertThat(CredentialQuery.carries(url)).isTrue()
    assertThat(CredentialQuery.parametersIn(url)).containsExactly("u", "t", "s")
  }

  @Test
  fun `a real stream url built by the client is detected`() {
    val url = client().streamUrl("song-1", StreamFormat.Raw, timeOffsetSeconds = null)

    assertThat(CredentialQuery.carries(url)).isTrue()
    assertThat(CredentialQuery.parametersIn(url)).containsExactly("u", "t", "s")
  }

  @Test
  fun `every parameter this guard looks for is one the auth scheme really mints`() {
    // The other direction, and the one that catches a *typo* rather than an omission: a guard
    // watching for a parameter no client sends is a guard that can never fire, which reads exactly
    // like a guard that found nothing.
    val minted = SubsonicAuth.authParams(CREDENTIALS, salt = "abcdef").keys

    assertThat(minted).containsAll(CredentialQuery.AUTH_PARAMETERS)
  }

  @Test
  fun `the parameters the auth scheme mints that are not credentials are deliberately absent`() {
    // `v`, `c` and `f` ride on every authenticated URL and reveal nothing: the protocol version,
    // the client name, the response format. Naming them would make this object report
    // "credentials" for a URL that carries none, and an over-reporting guard is one somebody later
    // switches off. Stated as an assertion rather than a comment so that adding one is a decision.
    val minted = SubsonicAuth.authParams(CREDENTIALS, salt = "abcdef").keys

    assertThat(minted - CredentialQuery.AUTH_PARAMETERS.toSet())
      .containsExactlyInAnyOrder("v", "c", "f")
  }

  @Test
  fun `a proxy url this module mints carries nothing`() {
    // The URLs the fix hands a renderer instead. No query component at all, so they answer `false`
    // without a special case -- which is what makes the guard usable at runtime.
    assertThat(CredentialQuery.carries("http://10.0.0.2:8080/media/deadbeef.mp3")).isFalse()
    assertThat(CredentialQuery.carries("http://10.0.0.2:8080/art/deadbeef")).isFalse()
    assertThat(CredentialQuery.carries(null)).isFalse()
  }

  @Test
  fun `a parameter name that merely appears in the text is not a credential`() {
    // `s` is one character. A guard that reported every `s=` in a document would fire on a track
    // titled "s = 1" and would be switched off within a week, so a parameter counts only in a
    // query position.
    assertThat(CredentialQuery.parametersIn("<dc:title>Formula s=mc2, u=3</dc:title>")).isEmpty()
    assertThat(CredentialQuery.parametersIn("http://h/x.mp3")).isEmpty()
    // ...and it does fire one character later, which is what stops the case above from being a
    // guard that simply never fires.
    assertThat(CredentialQuery.parametersIn("http://h/x.mp3?s=abc")).containsExactly("s")
  }

  @Test
  fun `an ampersand escaped once, twice, or numerically is still a query separator`() {
    // The artifact this scan is aimed at is escaped TWICE: `DidlLite` escapes the URL into the
    // document and `SoapEnvelope` escapes the document into an argument. A scan that only knew
    // about a bare `&` would find nothing in the one place it most needs to look, and would report
    // "no credentials" about a SOAP body carrying three.
    val plain = "http://h/x?id=1&u=me&t=abc&s=xyz"

    assertThat(CredentialQuery.parametersIn(plain)).containsExactly("u", "t", "s")
    assertThat(CredentialQuery.parametersIn(plain.replace("&", "&amp;")))
      .containsExactly("u", "t", "s")
    assertThat(CredentialQuery.parametersIn(plain.replace("&", "&amp;amp;")))
      .containsExactly("u", "t", "s")
    assertThat(CredentialQuery.parametersIn(plain.replace("&", "&#38;")))
      .containsExactly("u", "t", "s")
  }

  @Test
  fun `a run of escaping longer than the bound is not unwrapped forever`() {
    // The input is a document a *peer* can influence, and an unbounded rewrite over hostile input
    // is a denial of service inside a guard. The bound is generous against the real artifact (two
    // passes) and this case is what keeps it a bound rather than a comment.
    val deeplyEscaped = "http://h/x?id=1" + "&amp;amp;amp;amp;amp;amp;" + "u=me"

    assertThat(CredentialQuery.parametersIn(deeplyEscaped)).isEmpty()
  }

  private fun client() = SubsonicClient(CREDENTIALS)

  private companion object {
    val CREDENTIALS = SubsonicCredentials(
      baseUrl = "https://nav.example",
      username = "listener",
      password = "not-a-real-password",
    )
  }
}
