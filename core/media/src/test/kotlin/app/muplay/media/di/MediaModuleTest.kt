package app.muplay.media.di

import app.muplay.media.NeverResume
import okhttp3.OkHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The media layer's HTTP client is a set of **decisions**, and until this existed every one of
 * them was a comment.
 *
 * `MediaModule`'s own doc explains at length why this client is not `:core:network`'s: a call
 * timeout is a safety net on a short JSON request and a guaranteed mid-track failure on a body
 * that is legitimately open for four minutes. That reasoning was protected by nothing. A later
 * edit adding `.callTimeout(30, SECONDS)` "for symmetry with the other client" would have compiled,
 * passed every test in the project, and broken playback of any track longer than the cap — on a
 * device, intermittently, in a way that looks like a server problem.
 *
 * A plain JVM test: `OkHttpClient` has no Android in it, which is what lets the fast tier hold
 * these numbers.
 */
class MediaModuleTest {

  private val client = MediaModule.provideMediaCallFactory() as OkHttpClient

  @Test
  fun `there is no call timeout, because a streaming body is legitimately open for a whole track`() {
    // 0 is OkHttp's "no timeout". This is the assertion the module's comment was standing in for:
    // "we did not set it" and "we thought about it and must not set it" are different facts, and
    // only one of them survives a refactor.
    assertThat(client.callTimeoutMillis).isEqualTo(0)
  }

  @Test
  fun `the connect and read timeouts are the two the media layer chose, and they are not each other`() {
    // Two values, deliberately different, each asserted at its own number: a single constant --
    // or a copy-paste that gives both limbs the same one -- fails here. A read timeout is how long
    // a *read* may stall, not how long the whole body may take, which is why it can be generous
    // without capping a track's length.
    assertThat(client.connectTimeoutMillis).isEqualTo(15_000)
    assertThat(client.readTimeoutMillis).isEqualTo(30_000)
    assertThat(client.connectTimeoutMillis).isNotEqualTo(client.readTimeoutMillis)
  }

  @Test
  fun `nothing logs on the client that carries the credentials`() {
    // This client's URLs are `/rest/stream` URLs, and `SubsonicClient.streamUrl` puts `t` (the
    // auth token) and `s` (the salt) in the query string of every one of them. An
    // `HttpLoggingInterceptor` added "just for debugging" would write those to logcat, where any
    // app with READ_LOGS -- and any bug report -- picks them up. Nothing in the build stopped
    // that; this does.
    //
    // Asserted as emptiness rather than as "no logging interceptor" on purpose: naming the type
    // would gate one library, and the risk is any interceptor that sees the URL at all.
    assertThat(client.interceptors).isEmpty()
    assertThat(client.networkInterceptors).isEmpty()
  }

  @Test
  fun `redirects are followed, including across protocols`() {
    // The first of the two stated reasons for choosing OkHttp over `DefaultHttpDataSource` at all:
    // a Navidrome behind a reverse proxy commonly redirects `http` to `https`, and a client that
    // refuses that presents as a dead track with nothing in the logs. OkHttp's defaults are right
    // here -- the point of asserting them is that a later `.followSslRedirects(false)`, which is
    // exactly the kind of line a security review adds, has to break a test named for the reason.
    assertThat(client.followRedirects).isTrue()
    assertThat(client.followSslRedirects).isTrue()
  }

  @Test
  fun `the bound resume policy is the one that resumes nothing`() {
    // Spec section 3's stated behaviour for music, and the binding Plan 4 replaces. Two
    // observations, because the identity check alone would survive `NeverResume` itself being
    // changed to resume, and the behavioural one alone would survive this module binding some
    // other policy that also happens to answer zero today.
    val policy = MediaModule.provideResumePolicy()

    assertThat(policy).isSameAs(NeverResume)
    assertThat(policy.resolve(listOf("a", "b"), requestedIndex = 1).startPositionMs).isZero
    assertThat(policy.resolve(listOf("a", "b"), requestedIndex = 1).startIndex).isEqualTo(1)
  }
}
