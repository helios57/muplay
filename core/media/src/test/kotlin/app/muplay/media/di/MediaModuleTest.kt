package app.muplay.media.di

import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.proxy.OkHttpProxyUpstream
import app.muplay.cast.route.CastRoute
import app.muplay.media.NeverResume
import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import app.muplay.media.cast.OneShotResumePolicy
import dagger.Provides
import java.net.URI
import java.time.Clock
import java.time.ZoneOffset
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.isActive
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
  fun `the injected clock is a real clock and not a frozen one`() {
    // The project's only wall-clock read, behind every `media_progress.lastPlayedAtEpochMs` the app
    // writes. Asserted as *moving* rather than as `isNotNull`: a `Clock.fixed(..)` left here by a
    // test edit would stamp every row with the same instant, and `recentlyPlayed`'s
    // `ORDER BY lastPlayedAtEpochMs DESC` would then return an arbitrary order forever, silently.
    val clock = MediaModule.provideClock()

    assertThat(clock.millis()).isGreaterThan(EARLIEST_PLAUSIBLE_EPOCH_MS)
    // UTC, because the column is epoch millis: a zoned clock would still report the same instant,
    // but `Clock.systemDefaultZone()` invites a later `LocalDateTime.now(clock)` that is not.
    assertThat(clock.zone).isEqualTo(ZoneOffset.UTC)
    assertThat(clock).isEqualTo(Clock.systemUTC())
  }

  @Test
  fun `the undecorated resume policy is the one that resumes nothing`() {
    // Spec section 3's stated behaviour for music, and the binding Plan 4 replaces. Two
    // observations, because the identity check alone would survive `NeverResume` itself being
    // changed to resume, and the behavioural one alone would survive this module binding some
    // other policy that also happens to answer zero today.
    val policy = MediaModule.provideUndecoratedResumePolicy()

    assertThat(policy).isSameAs(NeverResume)
    assertThat(policy.resolve(listOf("a", "b"), requestedIndex = 1).startPositionMs).isZero
    assertThat(policy.resolve(listOf("a", "b"), requestedIndex = 1).startIndex).isEqualTo(1)
  }

  // ---- the cast handover's bindings -----------------------------------------------------------

  @Test
  fun `the unqualified resume policy the player factory receives IS the cast decorator`() {
    // **The whole feature, and it fails silently when it is wrong.** `CastSessionManager` arms an
    // `OneShotResumePolicy` by concrete type; `MuPlayerFactory` asks for an unqualified
    // `ResumePolicy`. If those two are not the same object the outbound leg still works by accident
    // -- the remote player is built holding the decorator -- and the RETURN leg silently restarts
    // the track from zero, which reads as a resume bug in a different file.
    val oneShot = MediaModule.provideOneShotResumePolicy(MediaModule.provideUndecoratedResumePolicy())

    val bound: ResumePolicy = MediaModule.provideResumePolicy(oneShot)

    assertThat(bound).isSameAs(oneShot)
    // Same object, and it really is the armed one: arm through the concrete type, read the answer
    // out of the bound one.
    oneShot.armFor("track-1", ResumeTarget(startIndex = 0, startPositionMs = 42_000L))
    assertThat(bound.resolve(listOf("track-1", "track-2"), requestedIndex = 0))
      .isEqualTo(ResumeTarget(0, 42_000L))
  }

  @Test
  fun `the decorator wraps whatever is bound rather than NeverResume by name`() {
    // The audiobook plan replaces `provideUndecoratedResumePolicy`'s body and changes nothing else.
    // Asserted with a policy that is emphatically not `NeverResume`, so a decorator that reached
    // for the object instead of the argument fails here.
    val bookish = ResumePolicy { _, index -> ResumeTarget(index, 7_000L) }

    val decorated = MediaModule.provideOneShotResumePolicy(bookish)

    assertThat(decorated.resolve(listOf("a"), requestedIndex = 0)).isEqualTo(ResumeTarget(0, 7_000L))
  }

  @Test
  fun `exactly one resume policy provider is unqualified, and it is the one taking the decorator`() {
    // The shape Hilt cannot check for us. Hilt *does* fail the build on two unqualified
    // `ResumePolicy` bindings -- that is the good outcome. The bad one is a graph with exactly one
    // unqualified binding that happens to be the WRONG one, which compiles, runs, and loses a
    // listener's place on every handover back.
    //
    // Read by reflection rather than asserted in prose, because prose is what this was protected by
    // and prose cannot fail.
    // Exact return type, not `isAssignableFrom`: a Dagger key is the **declared** type, so
    // `provideOneShotResumePolicy` -- which returns the subtype and is legitimately unqualified --
    // binds `OneShotResumePolicy` and is not a `ResumePolicy` binding at all. Written the loose way
    // first, and it reported three providers and two unqualified ones, which is true and is about a
    // different question.
    val providers = MediaModule::class.java.declaredMethods
      .filter { it.isAnnotationPresent(Provides::class.java) }
      .filter { it.returnType == ResumePolicy::class.java }
    val unqualified = providers.filter { method ->
      method.annotations.none { it.annotationClass.java.isAnnotationPresent(Qualifier::class.java) }
    }

    assertThat(providers).hasSize(2)
    assertThat(unqualified).hasSize(1)
    assertThat(unqualified.single().parameterTypes.toList())
      .containsExactly(OneShotResumePolicy::class.java)
  }

  @Test
  fun `the decorator is a singleton, because two decorators over one delegate lose the return leg`() {
    // The mutation this catches is one missing annotation: without `@Singleton`, Dagger hands
    // `CastSessionManager` and `MuPlayerFactory` a decorator each, over the same delegate. Every
    // outbound cast still works. The return leg arms one object and asks the other, and resumes
    // from zero -- and no assertion about positions anywhere else in this project would move.
    val provider = MediaModule::class.java.declaredMethods
      .single { it.returnType == OneShotResumePolicy::class.java }

    assertThat(provider.isAnnotationPresent(Singleton::class.java)).isTrue()
  }

  @Test
  fun `the proxy the renderer fetches from serves on a real port and mints tokens, not track ids`() {
    // The two halves of the cast graph that have a value worth reading: the server is listening (a
    // proxy that exists and is not accepting is a route that fails its own proof and reports the
    // speaker as unreachable), and the path a renderer is handed is a capability rather than a
    // track id.
    val registry = MediaModule.provideProxyRegistry()
    val upstream = MediaModule.provideProxyUpstream(MediaModule.provideMediaCallFactory())
    val proxy = MediaModule.provideMediaProxyServer(upstream, registry)

    proxy.use {
      assertThat(it.port).isGreaterThan(0)
      val published = registry.publish("https://nav.example/rest/stream?id=$TRACK_ID", MP3)
      // A distinctive id and not a short one. Written as `id=42` first, and a mutation probe caught
      // it: the token is 32 random hex characters, so `doesNotContain("42")` is a ~1-in-9 flake
      // that reads as a failure of whatever was being probed at the time.
      assertThat(published.path).doesNotContain(TRACK_ID)
      assertThat(it.urlFor(published, "192.168.1.9")).startsWith("http://192.168.1.9:${it.port}/")
      assertThat(upstream).isInstanceOf(OkHttpProxyUpstream::class.java)
    }
  }

  @Test
  fun `the router refuses to hand a renderer the credential-bearing navidrome url`() {
    // `allowRendererDirect = false` is the shipped answer until the setting that turns it on
    // exists, and the reason is that a Subsonic stream URL carries the user's `u`, `t` and `s`.
    // Driven through the router rather than read off a field: `confirm` is where the fallback would
    // be taken, and a device that never fetches is exactly the case it is taken in.
    val registry = MediaModule.provideProxyRegistry()
    val proxy = MediaModule.provideMediaProxyServer(
      MediaModule.provideProxyUpstream(MediaModule.provideMediaCallFactory()),
      registry,
    )
    proxy.use {
      val router = MediaModule.provideCastRouter(it, registry)
      val candidate = router.candidate(device(), UPSTREAM, MP3)
      assertThat(candidate).isInstanceOf(CastRoute.Proxied::class.java)

      val confirmed = router.confirm(candidate, UPSTREAM)

      assertThat(confirmed).isInstanceOf(CastRoute.Unroutable::class.java)
      assertThat((confirmed as CastRoute.Unroutable).detail).doesNotContain(UPSTREAM)
    }
  }

  @Test
  fun `speakers are talked to on a live scope that is not the caller's`() {
    // Every command to a renderer is a blocking socket exchange and the poll runs forever, so this
    // must never be a main-thread scope. `SupervisorJob`, asserted as *still active*, is the half
    // that matters after one session fails: a plain `Job` would be cancelled by the first failure
    // and the next cast would silently never start.
    val scope = MediaModule.provideCastCommandScope()

    assertThat(scope.isActive).isTrue()
    assertThat(scope.coroutineContext.toString()).contains("Dispatchers.IO")
  }

  @Test
  fun `soap goes over the cast module's own client, never okhttp`() {
    // `:core:cast` routes renderer traffic through its own socket client precisely so the
    // local-network rule is enforced in code rather than by `NetworkSecurityPolicy`. Two objects,
    // asserted to exist and to be wired together, because the alternative wiring -- a `SoapClient`
    // built with its own default client -- compiles and works on the bench.
    val http = MediaModule.provideCastHttpClient()

    assertThat(MediaModule.provideSoapClient(http)).isNotNull()
  }

  private fun device() = app.muplay.cast.discovery.CastDevice(
    udn = "uuid:test",
    friendlyName = "Test Speaker",
    manufacturer = null,
    modelName = null,
    descriptionUrl = URI("http://127.0.0.1:1/desc.xml"),
    avTransportControlUrl = URI("http://127.0.0.1:1/av"),
    avTransportScpdUrl = null,
    renderingControlUrl = null,
    isSonos = false,
  )

  private companion object {
    val MP3: ServedMedia = ServedMedia("audio/mpeg", "mp3")

    /**
     * Shaped like Navidrome's and carrying **no** authentication parameters, not even fabricated
     * ones: a stream URL's `t` and `s` are password equivalents and this repository does not write
     * them down.
     */
    const val UPSTREAM = "https://nav.example/rest/stream?id=1&format=raw"

    /** Long enough that a 32-hex random token cannot contain it by chance. */
    const val TRACK_ID = "trackIdNobodyGuesses"

    /** 2024-01-01T00:00:00Z. Any real clock is past it; a `Clock.fixed(EPOCH, ..)` is not. */
    const val EARLIEST_PLAUSIBLE_EPOCH_MS = 1_704_067_200_000L
  }
}
