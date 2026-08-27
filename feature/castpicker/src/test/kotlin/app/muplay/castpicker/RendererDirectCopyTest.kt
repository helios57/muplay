package app.muplay.castpicker

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A test about **copy**, which is unusual and deliberate.
 *
 * [RENDERER_DIRECT_EXPLANATION] is the one string in this application whose job is to make a
 * security decision informed. Turning the switch beside it on means handing a speaker a URL that
 * carries a non-expiring Subsonic auth token; a label reading only "Allow direct streaming" is not
 * a choice a user can be said to have made. "Someone shortened it" is a real way for that to stop
 * being true, and it is a change that breaks nothing else in the build.
 *
 * Each consequence is asserted separately rather than as one long `contains` chain, so a red names
 * the one that went missing.
 */
class RendererDirectCopyTest {

  @Test
  fun `the explanation says the speaker is handed the user's auth token`() {
    assertThat(RENDERER_DIRECT_EXPLANATION)
      .describedAs("consequence 1: the credential")
      .containsIgnoringCase("token")
  }

  @Test
  fun `the explanation says the token does not expire, which is what makes it a password`() {
    // The half a user cannot guess. "It carries a token" reads like a session cookie; the reason
    // this matters is that it is `md5(password + salt)` and it is good forever.
    assertThat(RENDERER_DIRECT_EXPLANATION)
      .describedAs("consequence 1, the half that makes it serious")
      .containsIgnoringCase("does not expire")
  }

  @Test
  fun `the explanation says speakers log the addresses they are given`() {
    // Why "hand it to a device you own" is not the end of the argument.
    assertThat(RENDERER_DIRECT_EXPLANATION)
      .describedAs("consequence 1, where the token ends up")
      .containsIgnoringCase("logs")
  }

  @Test
  fun `the explanation says the speaker has to trust the server's TLS certificate`() {
    // Spec section 6 claims the proxy *defers* the Let's Encrypt trust question rather than
    // eliminating it. This switch is where it comes back, and this sentence is where the user is
    // told so.
    assertThat(RENDERER_DIRECT_EXPLANATION)
      .describedAs("consequence 2: whose trust store decides")
      .containsIgnoringCase("TLS certificate")
  }

  @Test
  fun `the explanation says the bytes go over the speaker's own connection, which may be metered`() {
    assertThat(RENDERER_DIRECT_EXPLANATION)
      .describedAs("consequence 3: whose data plan pays")
      .containsIgnoringCase("metered")
  }

  @Test
  fun `the explanation says which way to leave it`() {
    // A description of three risks with no recommendation leaves the reader to infer one, and the
    // inference people make from a switch on a settings screen is "this is a normal thing to turn
    // on".
    assertThat(RENDERER_DIRECT_EXPLANATION).containsIgnoringCase("Leave this off")
  }

  @Test
  fun `the explanation is a paragraph a person can act on, not a label`() {
    // The mutation this is aimed at is literally `RENDERER_DIRECT_EXPLANATION = "Allow direct
    // streaming"`. Every keyword assertion above fails on that, and this one states the property
    // they are each an instance of, so a rewrite that keeps the words and loses the argument still
    // has something to clear.
    assertThat(RENDERER_DIRECT_EXPLANATION.length).isGreaterThan(400)
    assertThat(RENDERER_DIRECT_EXPLANATION).isNotEqualTo(RENDERER_DIRECT_TITLE)
  }

  @Test
  fun `the title names the speaker and the server, so the switch means something on its own`() {
    // A user who reads only the label still has to be able to tell what is being switched. "Direct
    // streaming" says nothing about who is talking to whom.
    assertThat(RENDERER_DIRECT_TITLE).containsIgnoringCase("speakers")
    assertThat(RENDERER_DIRECT_TITLE).containsIgnoringCase("Navidrome")
  }

  @Test
  fun `neither string contains a URL, because this repository does not write one down`() {
    // A worked example is the obvious way to improve copy like this, and it is exactly the way a
    // credential-bearing stream URL gets committed -- once in a string resource, forever in the
    // git history. `MediaModuleTest`'s `UPSTREAM` constant keeps the same rule from the other side.
    //
    // `://` rather than "http": the explanation is free to talk about addresses, and should.
    assertThat(RENDERER_DIRECT_EXPLANATION).doesNotContain("://")
    assertThat(RENDERER_DIRECT_TITLE).doesNotContain("://")
    // The three Subsonic auth parameters, by name, in the shape they appear in a stream URL.
    assertThat(RENDERER_DIRECT_EXPLANATION).doesNotContain("&t=", "&s=", "&u=")
  }
}
