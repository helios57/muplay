package app.muplay.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muplay.media.di.MediaCacheModule
import app.muplay.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cache, measured rather than asserted-by-flag.
 *
 * Every "did the cache work" claim here is a **request count on a real HTTP server**, and where a
 * request count cannot answer the question, the bytes the cache serves are read back and compared
 * (`assertCachedContentIs`). A test that asked the `Cache` object whether it holds a key would
 * pass against a cache that is never read from, which is exactly the defect this task exists to
 * prevent — and a test that compared *lengths* would pass against a cache that served the wrong
 * track, because every seeded fixture is the same length.
 *
 * Two things in this file are not about caching at all and are here because this is where they can
 * be observed: that a stream URL never reaches persistent storage
 * ([aRedirectedStreamUrlIsNeverWrittenToTheCachesPersistentIndex]) and never reaches an exception
 * message ([aDataSpecWithNoCustomCacheKeyIsRejectedRatherThanFallingBackToTheUri]).
 */
@RunWith(AndroidJUnit4::class)
class MediaCacheTest {

  private lateinit var context: Context
  private lateinit var cacheDir: File
  private lateinit var cache: Cache
  private lateinit var server: MockWebServer
  private lateinit var audio: ByteArray
  private lateinit var otherAudio: ByteArray

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // A per-test directory: SimpleCache refuses a second live instance on one folder, and a
    // shared folder would make these tests depend on the order they ran in.
    cacheDir = File(context.cacheDir, "media-test-${System.nanoTime()}")
    cache = MediaCache.create(context, cacheDir)
    server = MockWebServer()
    server.start()

    val bytes = runBlocking { RealTrackBytes.twoDifferentTracks() }
    audio = bytes.first
    otherAudio = bytes.second
    assertThat(audio.size).isGreaterThan(1000)
    assertThat(otherAudio.size).isGreaterThan(1000)
    // The two tracks must be genuinely different **in content**, or "served the wrong track from
    // cache" is undetectable below. Their *lengths* are no use for that and the assertion here
    // deliberately does not pretend otherwise: `ci/seed-fixtures.sh` builds all three music
    // fixtures from one recipe -- five-second mono CBR 64 kbps sines -- so every
    // `ci/fixtures/Music/Test Artist/Test Album/*.mp3` is byte-for-byte **exactly 40638 bytes**
    // long. A comment in this file used to claim the two sizes were "close but not equal" and two
    // length assertions downstream were quietly asserting the same number twice; the fix is
    // `assertCachedContentIs`, which compares the bytes the cache serves.
    assertThat(audio).isNotEqualTo(otherAudio)
    assertThat(audio.size)
      .describedAs("the seeded fixtures are one recipe, so a length is not an identity")
      .isEqualTo(otherAudio.size)
  }

  @After
  fun tearDown() {
    if (::cache.isInitialized) cache.release()
    if (::cacheDir.isInitialized) cacheDir.deleteRecursively()
    if (::server.isInitialized) server.close()
  }

  @Test
  fun theCacheKeyIsTheCustomKeyAndNotTheUri() {
    val first = DataSpec.Builder()
      .setUri("http://host/rest/stream?id=track-1&t=aaa&s=111")
      .setKey("track-1").build()
    val second = DataSpec.Builder()
      // Same track, different salt, different token, different bitrate -- i.e. everything Tempo's
      // URL-derived key would treat as a different resource.
      .setUri("http://host/rest/stream?id=track-1&t=zzz&s=999&maxBitRate=96")
      .setKey("track-1").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(first)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(second)).isEqualTo("track-1")
  }

  @Test
  fun twoTracksDoNotShareACacheKey() {
    // The other direction. A `buildCacheKey` that returned a constant would pass the test above
    // and fail this one; one that returned the URI would pass this one and fail the test above.
    val a = DataSpec.Builder().setUri("http://host/a").setKey("track-1").build()
    val b = DataSpec.Builder().setUri("http://host/b").setKey("chapter-14").build()

    assertThat(TrackIdCacheKeyFactory.buildCacheKey(a)).isEqualTo("track-1")
    assertThat(TrackIdCacheKeyFactory.buildCacheKey(b)).isEqualTo("chapter-14")
  }

  /**
   * Media3's `CacheKeyFactory.DEFAULT` falls back to the URI when no custom key is set. That
   * fallback is the entire Tempo defect: this client's URLs carry a fresh salt every time, so the
   * fallback silently produces a cache with a 0% hit rate that still consumes disk. Failing loudly
   * is the point.
   */
  @Test
  fun aDataSpecWithNoCustomCacheKeyIsRejectedRatherThanFallingBackToTheUri() {
    val noKey = DataSpec.Builder().setUri(credentialUrl("track-1")).build()

    assertThatExceptionOfType(MissingCacheKeyException::class.java)
      .isThrownBy { TrackIdCacheKeyFactory.buildCacheKey(noKey) }
      .withMessageContaining("setCustomCacheKey")
      // Which track, so the message is worth reading...
      .withMessageContaining("track-1")
      // ...and not which salt. This exception is thrown inside `CacheDataSource.open`, which runs
      // inside `Loader$LoadTask.run`; that method logs it and then wraps it into an
      // `ExoPlaybackException` that `ExoPlayerImplInternal` logs again. `u`, `s` and
      // `t = md5(password + salt)` are a replayable password equivalent -- Navidrome tracks no
      // salt nonce -- so a URL here is a credential in logcat, in every bug report, and in any
      // crash reporter ever attached. `MediaModuleTest`'s "nothing logs on the client that
      // carries the credentials" exists for exactly this, and an exception message routes around
      // it.
      .withMessageNotContainingAny(SALT, TOKEN, USERNAME)
  }

  /**
   * The same defence, **through a real player**, which is the layer where Tempo's bug lives.
   *
   * This exists because the brief's own falsification plan was wrong about this and the mutation
   * run proved it: swapping `TrackIdCacheKeyFactory` for `CacheKeyFactory.DEFAULT` changes
   * *nothing* as long as every `MediaItem` sets a custom cache key -- `DEFAULT` returns
   * `dataSpec.key` first and only falls back to the URI when it is null. So the defect cannot be
   * reproduced by mutating the factory; it is reproduced by **omitting the key on the item**,
   * which is exactly the omission Tempo makes.
   *
   * With Media3's default factory that omission is silent: the URI becomes the key, this client's
   * URLs carry a fresh salt every call, and the cache fills with entries nothing will ever read
   * again. Here it is a playback error naming its own cause, on the first run.
   *
   * `cache.keys` is asserted empty as well as the error, and that is the assertion that actually
   * discriminates: a fallback that cached under the URL and *also* somehow errored would still
   * leave a key behind.
   */
  @Test
  fun aMediaItemWithNoCustomCacheKeyFailsLoudlyInsteadOfCachingUnderItsUrl() {
    server.enqueue(audioResponse(audio))

    val error = playExpectingFailure(server.url(credentialPath("track-1")).toString())

    val chain = generateSequence(error as Throwable) { if (it.cause === it) null else it.cause }
      .toList()
    val described = chain.map { "${it.javaClass.name}: ${it.message}" }
    assertThat(chain.filterIsInstance<MissingCacheKeyException>())
      // The whole chain in the message: this assertion's job is to stop "the cache key was
      // missing" and "the network was unreachable" looking alike, and a failure that did not name
      // what it found would put them back together. (It did not, until this fix: the format
      // arguments were written with their `$` escaped, so every entry read back as the literal
      // text `${it.javaClass.name}` and named nothing at all.)
      .describedAs("MissingCacheKeyException in %s", described)
      .isNotEmpty()
    assertThat(cache.keys).isEmpty()
    // The message and every cause of it, as logcat and a crash reporter would receive them, and
    // the credential is in none of them. Asserted here as well as on the unit-level throw above
    // because these are two different facts: that the exception is built without a URL, and that
    // nothing between it and the player's reported error puts one back.
    assertThat(described.joinToString("\n"))
      .describedAs("the cause chain a crash reporter would upload")
      .doesNotContain(SALT, TOKEN, USERNAME)
  }

  /**
   * The key [MediaItems] sets is the key the cache **files under**.
   *
   * Task 4 could only observe `setCustomCacheKey` at one layer -- `MediaItemsTest` asserts the
   * value sitting on the built `MediaItem` -- because `TrackIdCacheKeyFactory` and this file did
   * not exist on its branch. That is the classic split this project tracks by name: "the key is
   * set here" and "the key that is set is the key the cache reads" are different facts, and the
   * first one alone is satisfied by a cache that never consults it.
   *
   * `containsExactly` rather than `contains`, deliberately: a cache that filed the item under
   * *both* the song id and the URL -- which is what a second, URL-derived write would look like --
   * passes `contains` and fails this.
   */
  @Test
  fun theCustomCacheKeyMediaItemsSetsIsTheKeyTheCacheFilesUnder() {
    server.enqueue(audioResponse(audio))
    val song = Song(
      id = "song-1",
      libraryId = 1,
      title = "Track 1",
      albumId = null,
      albumName = null,
      artistId = null,
      artistName = null,
      trackNumber = null,
      discNumber = null,
      durationSeconds = 5,
      suffix = "mp3",
      coverArtId = null,
    )
    // A URL shaped like the real thing: `SubsonicClient.streamUrl` stamps a fresh `s` salt on
    // every call, so the URI is exactly the thing that must not become the key.
    val streamUri = server.url("/rest/stream?id=song-1&t=aaa&s=111").toString()

    playItemToEnd(MediaItems.of(song, streamUri, artworkUri = null))

    assertThat(cache.keys).containsExactly(song.id)
    assertThat(cache.getCachedBytes(song.id, 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
  }

  /**
   * The factory the app actually builds is the one wired to [TrackIdCacheKeyFactory].
   *
   * Without this, every assertion above is about an object the production data source might never
   * consult -- the difference between "the decision is right" and "the decision is applied", which
   * is a defect class this project tracks by name. The playback tests below observe the same fact
   * end-to-end; this one names it directly so a regression says *which* wire came loose.
   */
  @Test
  fun theDataSourceTheAppBuildsIsWiredToTheTrackIdKeyFactory() {
    val source = MuPlayDataSourceFactory(OkHttpClient(), cache).create().createDataSource()

    assertThat(source).isInstanceOf(CacheDataSource::class.java)
    val cacheDataSource = source as CacheDataSource
    assertThat(cacheDataSource.cacheKeyFactory).isSameAs(TrackIdCacheKeyFactory)
    // The same `Cache` instance the caller passed, not a second one built on the side: a factory
    // that quietly made its own would still cache, and would still fail to share a byte with the
    // rest of the process.
    assertThat(cacheDataSource.cache).isSameAs(cache)
  }

  /**
   * **The measurement that matters.** Play a track, then play it again through a URL that differs
   * in exactly the ways this client's URLs really differ — a new salt, a new token, a bitrate cap
   * — and require that **not one further byte** is fetched.
   *
   * Holding the track id constant while varying everything else in the URL is what makes this
   * discriminating: a cache keyed on the URL passes the first playback and fails here.
   */
  @Test
  fun replayingATrackThroughADifferentUrlFetchesNothingFurther() {
    server.enqueue(audioResponse(audio))

    playToEnd(uri = server.url("/stream?id=track-1&t=aaa&s=111").toString(), cacheKey = "track-1")
    assertThat(server.requestCount).isEqualTo(1)

    playToEnd(
      uri = server.url("/stream?id=track-1&t=zzz&s=999&maxBitRate=96").toString(),
      cacheKey = "track-1",
    )

    // No second response was ever enqueued: had the player gone to the network, MockWebServer
    // would have blocked and the playback would have timed out rather than merely fetching twice.
    // Both facts are asserted, because either one alone leaves a way for this to pass wrongly.
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun aDifferentTrackIsNotServedFromAnotherTracksCache() {
    // The control. Without it, a cache that returned the first track's bytes for every key would
    // pass the test above perfectly.
    server.enqueue(audioResponse(audio))
    server.enqueue(audioResponse(otherAudio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")
    playToEnd(server.url("/stream?id=track-2").toString(), cacheKey = "track-2")

    assertThat(server.requestCount).isEqualTo(2)
    assertThat(cache.keys).contains("track-1", "track-2")
    // Each key holds **its own track's bytes**, compared as bytes and not as a length. A length
    // cannot do this job here and the comment that said it could was wrong: `ci/seed-fixtures.sh`
    // builds all three music fixtures from one recipe, so both are exactly 40638 bytes and both
    // expectations were the same number. A cache that served the first track's bytes for the
    // second -- the adversary this test names -- passed both of them.
    assertCachedContentIs("track-1", audio)
    assertCachedContentIs("track-2", otherAudio)
  }

  /**
   * The `cacheDir` trade, measured rather than asserted in a comment.
   *
   * [MediaCache] chooses `context.cacheDir` over `filesDir` on the claim that the OS reclaiming it
   * "costs a re-download and never a wrong answer". Nothing checked that claim. Here the cache
   * files are removed from under a live `SimpleCache` — which is what reclamation looks like from
   * the process's point of view, since its in-memory index still says the resource is held — and
   * the next playback has to succeed by going back to the server.
   *
   * `server.requestCount` going 1 -> 2 is the evidence, not "no exception": a player that
   * produced zero samples would also throw nothing. And `isNotEmpty()` on the span list is the
   * guard one level down — if `SimpleCache` ever stops naming its files this way, damaging nothing
   * would leave the cache healthy and this test would pass while measuring nothing at all.
   *
   * **What this test does not cover, and where it is covered instead.**
   * `FLAG_IGNORE_CACHE_ON_ERROR` in `MuPlayDataSourceFactory.create()` is not observed here, and
   * for the reason the two sabotages measured for this test showed: removing the span files is
   * repaired by `SimpleCache.getSpan` itself (`span.isCached && !span.file.exists()` -> drop the
   * stale spans, return a hole), so the flag never enters the picture — that is the path this test
   * exercises; and revoking *read* permission does reach `FileDataSource`, but the playback then
   * dies **with the flag set as well as without it**, because `DefaultLoadErrorHandlingPolicy`
   * does not retry a `FileNotFoundException`.
   *
   * That was once recorded as "the flag is a live, unobserved value", which was one measurement
   * short: a cache **write** failure does discriminate, and
   * [aCacheThatCannotBeWrittenToCostsBandwidthAndNotTheTrack] is the test that makes it.
   */
  @Test
  fun aCacheDirectoryReclaimedByTheOsCostsARedownloadAndNotAWrongAnswer() {
    server.enqueue(audioResponse(audio))
    playToEnd(server.url("/stream?id=track-1&s=111").toString(), cacheKey = "track-1")
    assertThat(server.requestCount).isEqualTo(1)

    val spans = cacheDir.walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.toList()
    assertThat(spans).isNotEmpty()
    assertThat(spans).allSatisfy { assertThat(it.delete()).isTrue() }

    server.enqueue(audioResponse(audio))
    playToEnd(server.url("/stream?id=track-1&s=999").toString(), cacheKey = "track-1")

    // The whole track came back off the wire, and the cache refilled from it.
    assertThat(server.requestCount).isEqualTo(2)
    assertThat(cache.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
  }

  @Test
  fun theCachedBytesOnDiskAreTheWholeTrack() {
    server.enqueue(audioResponse(audio))

    playToEnd(server.url("/stream?id=track-1").toString(), cacheKey = "track-1")

    // Direct evidence, alongside the request count: the cache holds the complete resource, not a
    // prefix that happened to satisfy a short playback.
    assertThat(cache.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isEqualTo(audio.size.toLong())
    assertThat(cache.cacheSpace).isGreaterThanOrEqualTo(audio.size.toLong())
  }

  /**
   * **A redirect must not leave the stream URL in the cache's persistent index.**
   *
   * `CacheDataSource.openNextSource`, having opened the upstream, reads `upstream.getUri()` into
   * `actualUri`; when that differs from the `DataSpec`'s own URI it calls
   * `ContentMetadataMutations.setRedirectedUri(..)` and hands the result to
   * `cache.applyContentMetadataMutations(key, ..)` -- `exo_redir`, in `exoplayer_internal.db`
   * under `/data/data/app.muplay/databases/`, which is **outside `cacheDir`** and survives OS
   * cache reclamation, a cache clear and a logout alike. `OkHttpDataSource.getUri()` returns the
   * URL *after* redirects, and the URL this client sends carries `u`, `s` and
   * `t = md5(password + salt)` -- a replayable, non-expiring password equivalent, since Navidrome
   * tracks no salt nonce. `CredentialStore` seals the password with an AndroidKeystore AES-GCM key
   * so only ciphertext ever reaches DataStore; a plaintext password equivalent in SQLite in the
   * same sandbox undoes that.
   *
   * The trigger needs no second defect: a reverse proxy redirecting `http` to `https` is the exact
   * deployment [MuPlayDataSourceFactory]'s own note gives as its reason for choosing OkHttp, and
   * `MediaModuleTest`'s "redirects are followed, including across protocols" pins as supported.
   * [RequestedUriDataSource] is the fix, and this is the test that fails without it.
   */
  @Test
  fun aRedirectedStreamUrlIsNeverWrittenToTheCachesPersistentIndex() {
    server.enqueue(
      MockResponse.Builder()
        .code(302)
        .addHeader("Location", server.url(REDIRECT_TARGET_PATH).toString())
        .build(),
    )
    server.enqueue(audioResponse(audio))

    playToEnd(server.url(credentialPath("track-1")).toString(), cacheKey = "track-1")

    // The redirect was really followed. Without this pair the assertions below would pass just as
    // happily on a run where nothing redirected at all -- the shape of vacuity this file's own
    // KDoc is about.
    assertThat(server.requestCount).isEqualTo(2)
    assertThat(nextRequest().url.encodedPath).isEqualTo(STREAM_PATH)
    assertThat(nextRequest().url.encodedPath).isEqualTo(REDIRECT_TARGET_PATH)

    val metadata = cache.getContentMetadata("track-1")
    assertThat(ContentMetadata.getRedirectedUri(metadata))
      .describedAs("exo_redir stored against track-1")
      .isNull()
    // And the general form, which is the claim that actually matters: **no** metadata value on
    // this key carries the credential. `exo_redir` is the one that does today; this assertion does
    // not have to be rewritten when Media3 adds another.
    assertThat(metadata).isInstanceOf(DefaultContentMetadata::class.java)
    val stored = (metadata as DefaultContentMetadata).entrySet()
      .joinToString("\n") { "${it.key}=${String(it.value, Charsets.UTF_8)}" }
    assertThat(stored)
      .describedAs("everything persisted against track-1")
      .doesNotContain(SALT, TOKEN, USERNAME)
  }

  /**
   * [RequestedUriDataSource]'s contract at close range: it answers **one** question itself and
   * forwards every other, and it is a substitute for what it wraps rather than a narrowing of it.
   *
   * The test above proves the wrapper does its job in a real player. This one proves it does not
   * quietly cost anything else — Media3 ships no `ForwardingDataSource`, so every one of these
   * methods is hand-written and every one of them could have been forgotten. The `getUri` window
   * either side of an open is asserted too: `DataSource` specifies null while a source is not
   * open, and answering with the delegate's URI there would be a leak with no live request behind
   * it.
   */
  @Test
  fun theUpstreamWrapperAnswersTheRequestedUriAndForwardsEverythingElse() {
    val redirected = Uri.parse("https://elsewhere.example/behind-the-proxy")
    val delegate = RecordingHttpDataSource(redirected)
    val source = RequestedUriDataSource(delegate)
    val requested = Uri.parse(credentialUrl("track-1"))
    val listener = object : TransferListener {
      override fun onTransferInitializing(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
      override fun onTransferStart(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
      override fun onBytesTransferred(s: DataSource, d: DataSpec, isNetwork: Boolean, bytes: Int) = Unit
      override fun onTransferEnd(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
    }

    assertThat(source.uri).describedAs("before open").isNull()

    source.addTransferListener(listener)
    val length = source.open(DataSpec.Builder().setUri(requested).setKey("track-1").build())

    assertThat(length).isEqualTo(RecordingHttpDataSource.OPENED_LENGTH)
    // The delegate followed a redirect and says so; this source does not repeat it. That
    // difference is precisely what `CacheDataSource.openNextSource` compares against the DataSpec
    // before deciding whether to persist `exo_redir`.
    assertThat(delegate.uri).isEqualTo(redirected)
    assertThat(source.uri).isEqualTo(requested)

    assertThat(source.read(ByteArray(8), 1, 2)).isEqualTo(C.RESULT_END_OF_INPUT)
    assertThat(source.responseHeaders).isEqualTo(RecordingHttpDataSource.HEADERS)
    assertThat(source.responseCode).isEqualTo(RecordingHttpDataSource.RESPONSE_CODE)
    source.setRequestProperty("Range", "bytes=0-")
    source.clearRequestProperty("Range")
    source.clearAllRequestProperties()
    source.close()

    assertThat(delegate.closed).isTrue()
    assertThat(source.uri).describedAs("after close").isNull()
    assertThat(delegate.calls).containsExactly(
      "addTransferListener",
      "open($requested)",
      "read(1,2)",
      "responseHeaders",
      "responseCode",
      "setRequestProperty(Range=bytes=0-)",
      "clearRequestProperty(Range)",
      "clearAllRequestProperties",
      "close",
    )
  }

  /**
   * **The size bound and the eviction policy, applied rather than declared.**
   *
   * `MediaCache.create` passes `LeastRecentlyUsedCacheEvictor(maxBytes)`, and until this test the
   * only assertion touching that bound read the *constant's declaration*. `SimpleCache` exposes no
   * evictor and `LeastRecentlyUsedCacheEvictor` has no `maxBytes` getter (both checked in 1.11.0),
   * so there was no seam at all: replacing the evictor with `NoOpCacheEvictor()` -- an unbounded
   * cache that fills the user's device -- left all 43 tests in this module green, and so did
   * multiplying the bound by a hundred. Only the harmless direction (an absurdly *small* bound)
   * was caught, and only by accident.
   *
   * One playback per track over a cache sized at one and a half of them observes three things at
   * once, which is why it is worth a device test: an evictor exists at all, it is
   * least-recently-**used** (the older track goes, not the one just written), and its bound is the
   * value it was handed rather than [MediaCache.MAX_BYTES] or anything else. A cache that ignored
   * its `maxBytes` argument fails here exactly as loudly as one with no evictor.
   */
  @Test
  fun theCacheEvictsTheLeastRecentlyUsedTrackAtTheBoundItWasGiven() {
    // One and a half tracks: track-1 alone fits, track-1 and track-2 together do not.
    val maxBytes = audio.size + otherAudio.size / 2L
    val boundedDir = File(context.cacheDir, "media-bounded-${System.nanoTime()}")
    val bounded = MediaCache.create(context, boundedDir, maxBytes)
    try {
      server.enqueue(audioResponse(audio))
      server.enqueue(audioResponse(otherAudio))

      playToEnd(server.url(credentialPath("track-1")).toString(), "track-1", into = bounded)
      // The premise, asserted rather than assumed: track-1 really was cached, so what happens
      // next is an eviction and not a write that never landed.
      assertThat(bounded.keys).containsExactly("track-1")
      assertThat(bounded.cacheSpace).isEqualTo(audio.size.toLong())

      playToEnd(server.url(credentialPath("track-2")).toString(), "track-2", into = bounded)

      assertThat(bounded.keys).contains("track-2")
      assertThat(bounded.keys).doesNotContain("track-1")
      assertThat(bounded.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isZero()
      assertThat(bounded.cacheSpace).isLessThanOrEqualTo(maxBytes)
      assertThat(server.requestCount).isEqualTo(2)
    } finally {
      bounded.release()
      boundedDir.deleteRecursively()
    }
  }

  /**
   * **`FLAG_IGNORE_CACHE_ON_ERROR` is observable, and here is the observation.**
   *
   * A previous sweep concluded that nothing discriminated this flag and recorded it as a live,
   * unobserved value. Its two measurements were right and its conclusion was not: deleting the
   * span files is repaired by `SimpleCache.getSpan` itself, and revoking *read* permission raises
   * a `FileNotFoundException`, which `DefaultLoadErrorHandlingPolicy` refuses to retry -- so the
   * track dies with the flag as well as without it. A cache **write** failure is the case that
   * separates them: `CacheDataSource.handleBeforeThrow` sets `seenCacheError` when the throwable
   * is a `Cache$CacheException`, `CacheDataSink$CacheDataSinkException` *is* one, and it is a
   * plain `IOException` besides, so it is not on the non-retriable list. On the retry
   * `shouldIgnoreCacheForRequest` answers `CACHE_IGNORED_REASON_ERROR`, the cache is bypassed and
   * the track plays off the wire. Without the flag the write fails on every attempt and the track
   * dies with an `ExoPlaybackException`.
   *
   * Deterministic, not one-in-ten: `SimpleCache.startFile` picks one of ten numeric
   * sub-directories and creates it when it is absent, and in a directory this fresh **none** of
   * the ten exists, so the first write must create one -- and cannot.
   */
  @Test
  fun aCacheThatCannotBeWrittenToCostsBandwidthAndNotTheTrack() {
    val readOnlyDir = File(context.cacheDir, "media-readonly-${System.nanoTime()}")
    // Built while the directory is still writable: `SimpleCache`'s constructor creates the folder
    // and its `.uid` file, and this test is about a failing *write of a span*, not about a cache
    // that could not be constructed.
    val readOnly = MediaCache.create(context, readOnlyDir)
    try {
      // `SimpleCache` creates its directory on a background thread, and its constructor can return
      // before that has happened: the init thread calls `conditionVariable.open()` **before** it
      // calls `initialize()`, so the constructor's `block()` returns as soon as the thread has
      // taken the monitor. Every other method is `synchronized` on that same monitor, so touching
      // one waits for the work. Without this line the chmod below raced the `mkdirs` and returned
      // false against a directory that did not exist yet -- measured on this emulator, not
      // anticipated -- and the failure named the chmod rather than the race.
      assertThat(readOnly.keys).isEmpty()
      assertThat(readOnlyDir).isDirectory()
      assertThat(readOnlyDir.setWritable(false, false))
        .describedAs("made %s unwritable", readOnlyDir)
        .isTrue()

      // A dispatcher rather than an enqueued response, and the reason is the measurement itself:
      // the *first* attempt reaches the wire before the cache write fails (`TeeDataSource.open`
      // opens the upstream and only then the sink), so recovering costs a second request. With a
      // queue of one, the retry found it empty, MockWebServer blocked, and the failure read as a
      // 30-second buffering timeout rather than as anything about the cache.
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = audioResponse(audio)
      }

      playToEnd(server.url(credentialPath("track-1")).toString(), "track-1", into = readOnly)
      val afterFirst = server.requestCount

      assertThat(readOnly.getCachedBytes("track-1", 0L, Long.MAX_VALUE)).isZero()

      // Nothing was cached, so a second playback has to go back to the wire too. That is the
      // price of the flag and the whole point of it: a cache that cannot be written costs
      // bandwidth, never the song.
      playToEnd(server.url(credentialPath("track-1")).toString(), "track-1", into = readOnly)

      // 2 and 4, measured on the emulator rather than reasoned about. Each playback costs two
      // requests: the first is abandoned when `CacheDataSink.open` cannot create its span file --
      // `TeeDataSource.open` opens the upstream *before* the sink, so the wire has already been
      // touched -- and the retry, now with `seenCacheError` set, bypasses the cache and plays.
      // `seenCacheError` is per `CacheDataSource` instance and `playToEnd` builds a fresh player
      // over a fresh one, so the second playback pays the same price rather than remembering.
      assertThat(afterFirst).describedAs("requests for the first playback").isEqualTo(2)
      assertThat(server.requestCount).describedAs("requests for both playbacks").isEqualTo(4)
    } finally {
      readOnlyDir.setWritable(true, true)
      readOnly.release()
      readOnlyDir.deleteRecursively()
    }
  }

  /**
   * The directory the production cache writes to, named and located — reached through the
   * **binding the graph actually uses**, not through [MediaCache] directly.
   *
   * Going via `MediaCacheModule.provideMediaCache` is the point rather than a detour. Every other test
   * in this file passes its own directory, so nothing else in the project observes which
   * directory production ends up with; a provider that called the two-argument overload with
   * `filesDir`, or with a name of its own, would satisfy every assertion in this file if this test
   * called [MediaCache] itself. Where a decision is *applied* and where it is *declared* are
   * different layers, and this project tracks tests that verify only the second.
   */
  @Test
  fun theProductionCacheLivesInAKnownDirectoryUnderCacheDir() {
    val expected = File(context.cacheDir, MediaCache.DIRECTORY_NAME)
    // Another test **in this process** may already hold the production cache: Task 5's Hilt
    // instrumented test injects the very same `@Singleton Cache`, on this very directory.
    // `SimpleCache` refuses a second live instance on a folder another instance holds, and the
    // `finally` below used to `deleteRecursively()` that folder unconditionally -- so depending
    // on which test ran first this one either threw or deleted a live cache out from under its
    // owner. `isCacheFolderLocked` is a public static and answers exactly that question. When the
    // folder is already held the binding under test has demonstrably already run, so there is
    // nothing to construct: assert against what it left, and touch nothing.
    val alreadyHeld = SimpleCache.isCacheFolderLocked(expected)
    val production = if (alreadyHeld) null else MediaCacheModule.provideMediaCache(context)
    try {
      // Discriminating in both directions: a `create` that used `filesDir`, or that derived a
      // different sub-directory name than `DIRECTORY_NAME`, leaves this path non-existent.
      assertThat(expected).isDirectory()
      // Not `filesDir`: the OS may reclaim `cacheDir` under storage pressure, which is the right
      // trade for a read-through cache of data the server still has, and the wrong one for a
      // download. Asserted as an absence as well as a presence, because a `create` that wrote to
      // both would satisfy the line above on its own.
      assertThat(File(context.filesDir, MediaCache.DIRECTORY_NAME)).doesNotExist()
      // A change-detector, and named as one rather than dressed up: 512 MiB cannot be observed
      // behaviourally without filling a cache that size. It catches an edited constant, which is
      // the only failure mode it claims to catch.
      assertThat(MediaCache.MAX_BYTES).isEqualTo(512L * 1024L * 1024L)
    } finally {
      production?.release()
      if (!alreadyHeld) expected.deleteRecursively()
    }
  }

  private fun audioResponse(bytes: ByteArray): MockResponse =
    MockResponse.Builder()
      .code(200)
      .addHeader("Content-Type", "audio/mpeg")
      .addHeader("Accept-Ranges", "bytes")
      .body(Buffer().write(bytes))
      .build()

  /**
   * Plays an item carrying **no** custom cache key and returns the error the player reports.
   *
   * Deliberately not a flag on [playToEnd]: that method treats a playback error as a broken
   * premise and abandons its wait, which is right everywhere else in this file and exactly wrong
   * here, where the error *is* the assertion. Same split, and the same reason, as
   * `PlayerHarness.awaitPlaybackError` versus `PlayerHarness.await`.
   */
  private fun playExpectingFailure(uri: String): PlaybackException {
    val factory = MuPlayDataSourceFactory(OkHttpClient(), cache)
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
          .build(),
      )
    }
    try {
      harness.onMain {
        // `MediaItem.fromUri`, i.e. no `setCustomCacheKey` -- the omission under test.
        harness.player.setMediaItem(MediaItem.fromUri(uri))
        harness.player.prepare()
        harness.player.play()
      }
      return harness.awaitPlaybackError()
    } finally {
      harness.release()
    }
  }

  /**
   * Builds a fresh player over [into], plays one item to the end, and releases it.
   *
   * [into] defaults to this test's own cache and is a parameter for the three tests that need a
   * *differently built* one -- a bound small enough to evict, and a directory the process cannot
   * write to. Those caches cannot be the shared one: `SimpleCache` refuses a second live instance
   * on a folder another instance holds.
   */
  private fun playToEnd(uri: String, cacheKey: String, into: Cache = cache) =
    playItemToEnd(MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build(), into)

  private fun playItemToEnd(item: MediaItem, into: Cache = cache) {
    val factory = MuPlayDataSourceFactory(OkHttpClient(), into)
    lateinit var harness: PlayerHarness
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      harness = PlayerHarness(
        ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(factory.create()))
          .build(),
      )
    }
    try {
      harness.onMain {
        harness.player.setMediaItem(item)
        harness.player.prepare()
        harness.player.play()
      }
      // Position, then ENDED: "reached the end" alone is satisfied by a zero-length source.
      harness.awaitPositionAtLeast(1_000L)
      harness.awaitEnded()
    } finally {
      harness.release()
    }
  }

  /**
   * Everything the cache holds under [key], **read back through Media3's own cache read path**.
   *
   * `setUpstreamDataSourceFactory(null)` makes this a cache-only source, which is the point: a
   * hole in the resource becomes an error here rather than a silent trip to the network, so this
   * helper cannot accidentally measure the server. Reading the `.exo` span files off disk would
   * be shorter and would answer a different question; what a caller wants to know is what a
   * *player* would be served for this key.
   */
  private fun readCachedBytes(key: String): ByteArray {
    val source = CacheDataSource.Factory()
      .setCache(cache)
      .setCacheKeyFactory(TrackIdCacheKeyFactory)
      .setUpstreamDataSourceFactory(null)
      .createDataSource()
    // The URI is never fetched -- there is no upstream -- so this is deliberately not a URL: the
    // key is the whole of what the cache is being asked for, which is this module's entire point.
    val spec = DataSpec.Builder()
      .setUri("muplay-test://cache/$key")
      .setKey(key)
      .setLength(C.LENGTH_UNSET.toLong())
      .build()
    source.open(spec)
    return try {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(READ_BUFFER_BYTES)
      while (true) {
        val read = source.read(buffer, 0, buffer.size)
        if (read == C.RESULT_END_OF_INPUT) break
        out.write(buffer, 0, read)
      }
      out.toByteArray()
    } finally {
      source.close()
    }
  }

  private fun assertCachedContentIs(key: String, expected: ByteArray) =
    assertThat(readCachedBytes(key))
      .describedAs("the bytes the cache serves for %s", key)
      .isEqualTo(expected)

  /**
   * The next request the player actually sent.
   *
   * The timed overload deliberately, never the no-argument one: that blocks forever on an empty
   * queue, so a regression where the player stops issuing a request at all would hang the run
   * until CI killed it and surface as infrastructure rather than as this test failing. Same
   * reasoning as `MuPlayDataSourceFactoryTest.nextRequest` and `SubsonicClientTest.nextRequest`.
   */
  private fun nextRequest(): RecordedRequest =
    checkNotNull(server.takeRequest(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      "the player sent no request within $REQUEST_TIMEOUT_SECONDS s"
    }

  /**
   * A stream URL shaped the way `SubsonicClient.streamUrl` really shapes one — including the three
   * parameters that make it a credential.
   *
   * Every test here that could use a bare `/stream?id=..` uses this instead, so that "the URL must
   * not be written down" is asserted against a URL that would actually matter if it were.
   */
  private fun credentialPath(trackId: String): String =
    "$STREAM_PATH?id=$trackId&u=$USERNAME&t=$TOKEN&s=$SALT&v=1.16.1&c=MuPlay"

  /** The same, absolute, for the tests that never issue a request. */
  private fun credentialUrl(trackId: String): String =
    "http://navidrome.example${credentialPath(trackId)}"

  /**
   * A hand-written `HttpDataSource` that records what was asked of it and reports a URI of its own.
   *
   * No mock framework on this project and none wanted here: the assertion the wrapper needs is
   * "every method was forwarded, in order, with its arguments", and a recorded call list says that
   * in one `containsExactly` where a strict mock would need a stub per method — a second list to
   * keep in step with the first.
   */
  private class RecordingHttpDataSource(private val reportedUri: Uri) : HttpDataSource {
    val calls = mutableListOf<String>()
    var closed = false

    override fun addTransferListener(transferListener: TransferListener) {
      calls += "addTransferListener"
    }

    override fun open(dataSpec: DataSpec): Long {
      calls += "open(${dataSpec.uri})"
      return OPENED_LENGTH
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      calls += "read($offset,$length)"
      return C.RESULT_END_OF_INPUT
    }

    /** The URL a redirect landed on — what `OkHttpDataSource` would report here. */
    override fun getUri(): Uri = reportedUri

    override fun getResponseHeaders(): Map<String, List<String>> {
      calls += "responseHeaders"
      return HEADERS
    }

    override fun getResponseCode(): Int {
      calls += "responseCode"
      return RESPONSE_CODE
    }

    override fun setRequestProperty(name: String, value: String) {
      calls += "setRequestProperty($name=$value)"
    }

    override fun clearRequestProperty(name: String) {
      calls += "clearRequestProperty($name)"
    }

    override fun clearAllRequestProperties() {
      calls += "clearAllRequestProperties"
    }

    override fun close() {
      calls += "close"
      closed = true
    }

    companion object {
      const val OPENED_LENGTH = 1234L
      const val RESPONSE_CODE = 206
      val HEADERS = mapOf("X-Recorded" to listOf("yes"))
    }
  }

  private companion object {
    const val STREAM_PATH = "/rest/stream"

    /** Where the reverse proxy in `aRedirectedStreamUrlIsNeverWrittenToTheCachesPersistentIndex` sends it. */
    const val REDIRECT_TARGET_PATH = "/rest/stream-behind-the-proxy"

    /**
     * The three values that make a Subsonic URL a password equivalent: the user, the salt, and
     * `md5(password + salt)`. Navidrome tracks no salt nonce, so the triple replays forever. They
     * are constants rather than inline literals because several assertions need to say "none of
     * these appears anywhere in this string", and a value that is asserted-absent in one place and
     * spelled differently in another is asserted-absent nowhere.
     */
    const val USERNAME = "admin"
    const val SALT = "9f8e7d6c5b4a3210"
    const val TOKEN = "1f0e2d3c4b5a69788796a5b4c3d2e1f0"

    const val READ_BUFFER_BYTES = 8 * 1024
    const val REQUEST_TIMEOUT_SECONDS = 10L
  }
}
