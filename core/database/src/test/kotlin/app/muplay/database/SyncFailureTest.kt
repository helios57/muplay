package app.muplay.database

import app.muplay.network.SubsonicErrorException
import app.muplay.network.SubsonicHttpException
import java.io.IOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SyncFailure.of] is a plain function over a `Throwable` with no Android type in its signature,
 * so the fast tier holds every branch of it — the same split `PlaybackFailure.of` makes for the
 * same reason.
 *
 * What it exists to stop: `LibraryViewModel.refresh` used to render every [SyncState.Failed] as
 * *"Could not reach the server. Showing your last synced library."* — for a changed password, for
 * a 502 from a reverse proxy, for an expired certificate, and for a first run that had never
 * synced anything and so had no last synced library to show.
 */
class SyncFailureTest {

  @Test
  fun `a subsonic error code is a rejection carrying that code`() {
    val failure = SyncFailure.of(SubsonicErrorException(40, "Wrong username or password"))

    assertThat(failure).isEqualTo(SyncFailure.SignInRejected(40))
  }

  @Test
  fun `an unsuccessful http status is a server failure carrying that status`() {
    assertThat(SyncFailure.of(SubsonicHttpException(502))).isEqualTo(SyncFailure.ServerError(502))
  }

  @Test
  fun `never having been configured is its own failure`() {
    assertThat(SyncFailure.of(NotConfiguredException())).isEqualTo(SyncFailure.NotConfigured)
  }

  @Test
  fun `a server that reports no libraries is its own failure`() {
    assertThat(SyncFailure.of(EmptyLibraryListException())).isEqualTo(SyncFailure.NoLibraries)
  }

  @Test
  fun `a blocked cleartext request names the scheme rather than the connection`() {
    val failure = SyncFailure.of(UnknownServiceException("CLEARTEXT communication not permitted"))

    assertThat(failure).isEqualTo(SyncFailure.CleartextForbidden)
  }

  @Test
  fun `an expired or untrusted certificate is distinguished from an unreachable server`() {
    // A self-hoster's most likely second failure, after cleartext: a Let's Encrypt certificate
    // that lapsed, or a self-signed one. "Check your connection" is the wrong advice for both.
    assertThat(SyncFailure.of(SSLHandshakeException("cert expired")))
      .isEqualTo(SyncFailure.CertificateInvalid)
  }

  @Test
  fun `a plain transport failure is unreachable`() {
    assertThat(SyncFailure.of(IOException("connection refused"))).isEqualTo(SyncFailure.Unreachable)
  }

  @Test
  fun `an exception nothing recognises is unknown rather than silently unreachable`() {
    // Deliberately not folded into Unreachable: "could not reach the server" is a claim, and
    // making it about an exception nobody classified is how the defect this type fixes started.
    assertThat(SyncFailure.of(IllegalStateException("boom"))).isEqualTo(SyncFailure.Unknown)
  }
}
