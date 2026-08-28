package app.muplay.media.di

import app.muplay.cast.didl.ServedMedia
import app.muplay.cast.proxy.MediaProxyServer
import app.muplay.cast.proxy.OkHttpProxyUpstream
import app.muplay.cast.proxy.ProxyRegistry
import app.muplay.cast.route.CastRoute
import app.muplay.media.AudiobookItem
import app.muplay.media.AudiobookItemSource
import app.muplay.media.AudiobookResumePolicy
import app.muplay.media.AudiobookSnapshot
import app.muplay.media.BookPlaybackSettings
import app.muplay.cast.route.UnroutableReason
import app.muplay.media.NeverResume
import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import app.muplay.media.cast.OneShotResumePolicy
import app.muplay.media.cast.RendererDirectPolicy
import app.muplay.media.cast.StoredRendererDirectPolicy
import dagger.Provides
import java.net.URI
import java.time.Clock
import java.time.Instant
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
  fun `the undecorated resume policy is the one that actually resumes a book`() {
    // **The single most important binding in this application**, and until Plan 4 Task 6 it was
    // `NeverResume` -- the policy that starts everything from zero. Restoring that line is the
    // whole defect this project exists to fix, and it is silent: nothing throws, nothing logs, and
    // every other test in this module stays green because they all construct the policy directly.
    //
    // It is gated **here**, on the JVM tier, and that is the reason `provideUndecoratedResumePolicy`
    // takes an `AudiobookItemSource` rather than the `AudiobookSnapshot` the plan for that task
    // specified. With the concrete snapshot this provider could only be reached behind an emulator,
    // and that plan recorded the binding as a known ungated line for a later task to close.
    //
    // A two-entry source, so "it resumed" is not "it returned the only number there was".
    val library = mapOf(
      "book-a-1" to item("book-a-1", "book-a", positionMs = 12_345L),
      "book-b-1" to item("book-b-1", "book-b", positionMs = 60_000L),
    )

    val policy = MediaModule.provideUndecoratedResumePolicy({ library[it] }, FIXED_CLOCK)

    // Behaviour first, and it is what a restored `NeverResume` fails on: two books, two positions.
    assertThat(policy.resolve(listOf("book-a-1"), requestedIndex = 0).startPositionMs)
      .isEqualTo(12_345L)
    assertThat(policy.resolve(listOf("book-b-1"), requestedIndex = 0).startPositionMs)
      .isEqualTo(60_000L)
    // Music -- an id the source does not know -- still starts from zero, which is spec section 3
    // and is the half a policy that resumed everything would break.
    assertThat(policy.resolve(listOf("a-song"), requestedIndex = 0).startPositionMs).isZero
    // The caller's index survives, because it is queue membership rather than progress.
    assertThat(policy.resolve(listOf("a-song", "book-b-1"), requestedIndex = 1))
      .isEqualTo(ResumeTarget(1, 60_000L))
    // ...and the type, which the behavioural checks alone would not pin: a different policy that
    // happened to answer the same three numbers today would satisfy every line above.
    assertThat(policy).isInstanceOf(AudiobookResumePolicy::class.java)
    // `NeverResume` is deliberately NOT deleted -- it is still the reference implementation of "no
    // resume" and `ResumePolicyTest` is what keeps `resolve`'s signature honest. It is simply no
    // longer what this module binds, and that is what this line says.
    assertThat(policy).isNotSameAs(NeverResume)
  }

  @Test
  fun `the bound policy reads the clock, so a book left for a week is rewound`() {
    // The other half of the binding, and the one the test above cannot reach: it holds the clock at
    // the row's own timestamp, so a provider that passed no clock at all -- or passed
    // `Clock.systemUTC()` instead of the injected one -- agrees with it exactly. `DataModule`'s
    // `Clock` is the only wall-clock read behind every `lastPlayedAtEpochMs` this app writes, and
    // the smart rewind is the only thing that consumes it on the way back out.
    val library = mapOf("book-a-1" to item("book-a-1", "book-a", positionMs = 60_000L))

    val policy = MediaModule.provideUndecoratedResumePolicy(
      { library[it] },
      Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS + 7L * 86_400_000L), ZoneOffset.UTC),
    )

    // Seven days away lands in `SmartRewind`'s top band: 20 s off a 60 s position.
    assertThat(policy.resolve(listOf("book-a-1"), requestedIndex = 0).startPositionMs)
      .isEqualTo(40_000L)
  }

  // ---- the cast handover's bindings -----------------------------------------------------------

  @Test
  fun `the unqualified resume policy the player factory receives IS the cast decorator`() {
    // **The whole feature, and it fails silently when it is wrong.** `CastSessionManager` arms an
    // `OneShotResumePolicy` by concrete type; `MuPlayerFactory` asks for an unqualified
    // `ResumePolicy`. If those two are not the same object the outbound leg still works by accident
    // -- the remote player is built holding the decorator -- and the RETURN leg silently restarts
    // the track from zero, which reads as a resume bug in a different file.
    val oneShot = MediaModule.provideOneShotResumePolicy(
      MediaModule.provideUndecoratedResumePolicy({ null }, FIXED_CLOCK),
    )

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
  fun `what answers which files are books is the snapshot, and there is exactly one such binding`() {
    // Task 7 shipped a stand-in `provideAudiobookItemSource` answering `null` for everything, with
    // a KDoc saying Task 6 replaces its *body*. Task 6 instead added a second, unqualified `@Binds`
    // -- and Hilt failed the build with `AudiobookItemSource is bound multiple times`, which that
    // KDoc had named in advance as the good outcome. The stand-in is gone; this is what replaced it.
    //
    // Asserted on the declaration rather than on a graph, because the bad merge is not "the wrong
    // answer" but "two bindings where the wrong one wins", and that is visible here and nowhere
    // else on this tier.
    //
    // Falsified: put the stand-in back beside the `@Binds` and this test fails, alone, in 1m15s --
    // `:core:media:testDebugUnitTest` red on this method and no other. Measured 2026-08-28.
    val bindings = MediaModule.Bindings::class.java.declaredMethods
      .filter { it.returnType == AudiobookItemSource::class.java }

    assertThat(bindings).hasSize(1)
    assertThat(bindings.single().parameterTypes).containsExactly(AudiobookSnapshot::class.java)
    assertThat(MediaModule::class.java.declaredMethods.map { it.returnType })
      .doesNotContain(AudiobookItemSource::class.java)
  }

  @Test
  fun `a media id the snapshot has never heard of plays as music, not as a book at some speed`() {
    // Survives the stand-in it was written against: the property is `BookPlaybackSettings.of`'s,
    // not the binding's. A file no snapshot knows is not an audiobook, so it plays at 1.0x with
    // silence skipping off -- which is what closes the speed leak for a song whatever the wiring.
    assertThat(BookPlaybackSettings.of(null)).isEqualTo(BookPlaybackSettings.MUSIC)
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
    // The user has not turned renderer-direct on, which is every user until one deliberately does.
    // The reason it matters is that a Subsonic stream URL carries the user's `u`, `t` and `s` --
    // password equivalents that do not expire. Driven through the router rather than read off a
    // field: `confirm` is where the fallback would be taken, and a renderer that never fetches is
    // exactly the case it is taken in.
    withProxy { proxy, registry ->
      val router = MediaModule.provideCastRouter(proxy, registry) { false }
      val candidate = router.candidate(device(), UPSTREAM, MP3)
      assertThat(candidate).isInstanceOf(CastRoute.Proxied::class.java)

      val confirmed = router.confirm(candidate, UPSTREAM)

      assertThat(confirmed).isInstanceOf(CastRoute.Unroutable::class.java)
      // The URL is not in the message a user reads, and it is not in this file either -- see
      // `UPSTREAM`'s own note. A failure detail is the one string in this path that gets pasted
      // into a bug report.
      assertThat((confirmed as CastRoute.Unroutable).detail).doesNotContain(UPSTREAM)
      assertThat(confirmed.reason)
        .isEqualTo(UnroutableReason.PROXY_UNREACHABLE_AND_DIRECT_DISABLED)
    }
  }

  @Test
  fun `the same shipped provider does hand it over once the user has said yes`() {
    // The other direction of the same switch, and without it the test above is satisfied by a
    // provider that ignores the policy entirely and refuses unconditionally -- which is exactly
    // what this module shipped before Task 12, and which nothing could tell apart from a working
    // setting. Two observations of one value, on the production provider.
    withProxy { proxy, registry ->
      val router = MediaModule.provideCastRouter(proxy, registry) { true }
      val candidate = router.candidate(device(), UPSTREAM, MP3)

      val confirmed = router.confirm(candidate, UPSTREAM)

      assertThat(confirmed).isEqualTo(CastRoute.RendererDirect(UPSTREAM))
    }
  }

  @Test
  fun `the shipped router asks the setting when it needs it, not when the graph is assembled`() {
    // `provideCastRouter` is `@Singleton`, so a provider that resolved the answer in its own body
    // -- `allowRendererDirect = runBlocking { settings.allowRendererDirect.first() }`, the obvious
    // and rejected wiring -- would resolve it once per process. Turning the switch on and casting
    // would then do nothing until the app was restarted, and nothing would say so.
    //
    // Counted rather than trusted: zero reads through construction and candidate-minting, then one
    // per fallback, and the answer changes between two casts on the same router object.
    withProxy { proxy, registry ->
      var reads = 0
      var allowed = false
      val router = MediaModule.provideCastRouter(proxy, registry) { reads++; allowed }

      val first = router.candidate(device(), UPSTREAM, MP3)
      assertThat(reads).isZero
      assertThat(router.confirm(first, UPSTREAM)).isInstanceOf(CastRoute.Unroutable::class.java)
      assertThat(reads).isEqualTo(1)

      allowed = true
      val second = router.candidate(device(), UPSTREAM, MP3)

      assertThat(router.confirm(second, UPSTREAM)).isEqualTo(CastRoute.RendererDirect(UPSTREAM))
    }
  }

  @Test
  fun `the policy the graph binds is the one that reads the stored setting`() {
    // The binding, not the behaviour -- the behaviour needs a DataStore and therefore a device
    // (`StoredRendererDirectPolicyTest`). What this can see from the JVM tier is the shape that
    // would otherwise be invisible: a second `RendererDirectPolicy` implementation added later
    // (a debug-only "always allow", say) and bound in place of this one compiles, passes every
    // other test in this file, and turns the security default inside out for everybody.
    val binding = MediaModule.Bindings::class.java.declaredMethods
      .single { it.returnType == RendererDirectPolicy::class.java }

    assertThat(binding.parameterTypes.toList())
      .containsExactly(StoredRendererDirectPolicy::class.java)
    assertThat(binding.isAnnotationPresent(Singleton::class.java)).isTrue()
  }

  /** A started proxy and the registry it shares with the router, closed however the block ends. */
  private fun withProxy(block: (MediaProxyServer, ProxyRegistry) -> Unit) {
    val registry = MediaModule.provideProxyRegistry()
    val proxy = MediaModule.provideMediaProxyServer(
      MediaModule.provideProxyUpstream(MediaModule.provideMediaCallFactory()),
      registry,
    )
    proxy.use { block(it, registry) }
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

    /** Fixed, so the resume assertions above are equalities rather than bands. */
    const val FIXED_NOW_MS = 1_700_000_000_000L

    val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(FIXED_NOW_MS), ZoneOffset.UTC)
  }

  /** Stored **now**, so the away time is in `SmartRewind`'s no-rewind band and the position is exact. */
  private fun item(mediaId: String, bookId: String, positionMs: Long) = AudiobookItem(
    mediaId = mediaId,
    bookId = bookId,
    positionMs = positionMs,
    lastPlayedAtEpochMs = FIXED_NOW_MS,
    isFinished = false,
    speed = 1.0f,
    skipSilence = false,
  )
}
