# Plan 6 Task 6 — the proxy: a range-serving HTTP/1.1 server, and the token that is not a track id

Branch `p6t6-branch`, worktree `.claude/worktrees/p6t6`, branched from master at `6be28b4` and
merged up to `d8e84fa`. `:core:cast` is a pure-JVM module: **no emulator was used and the device
lock was never taken.**

---

## 1. What was built

Four production files under `core/cast/src/main/kotlin/app/muplay/cast/proxy/`, four test files
beside them, plus the floors, the live task registration, the CI job and ten mutation probes.

### The public API, in full, so Tasks 7–11 need not read the diff

```kotlin
package app.muplay.cast.proxy

// ---- RangeHeader.kt -------------------------------------------------------------------------
data class ByteRange(val firstByte: Long, val lastByte: Long) { val length: Long }

sealed interface RangeRequest {
  data object Absent : RangeRequest
  data object Ignored : RangeRequest                                   // malformed -> 200, NOT 416
  data class Bounded(val firstByte: Long, val lastByte: Long?) : RangeRequest
  data class Suffix(val lastBytes: Long) : RangeRequest
}

sealed interface RangeResolution {
  data object Whole : RangeResolution
  data class Partial(val range: ByteRange) : RangeResolution
  data object Unsatisfiable : RangeResolution
}

object RangeHeader {
  fun parse(value: String?): RangeRequest
  fun resolve(request: RangeRequest, totalLength: Long): RangeResolution
}

// ---- ProxyRegistry.kt -----------------------------------------------------------------------
data class PublishedMedia(
  val token: String, val path: String, val upstreamUrl: String, val served: ServedMedia,
)

class ProxyRegistry(random: SecureRandom = SecureRandom()) {
  fun publish(upstreamUrl: String, served: ServedMedia): PublishedMedia
  fun resolve(path: String): PublishedMedia?          // whole-path match; traversal -> null
  fun revoke(token: String)
  fun revokeAll()
  companion object { const val TOKEN_BYTES: Int = 16; const val PATH_PREFIX: String = "/media/" }
}

// ---- ProxyUpstream.kt -----------------------------------------------------------------------
class UpstreamThrottledException(val retryAfterSeconds: Long?) : IOException

object ProxyRetry {
  const val TOO_MANY_REQUESTS: Int = 429
  const val MAX_ATTEMPTS: Int = 4          // the last attempt that may be RETRIED: 5 requests max
  const val BASE_BACKOFF_MS: Long = 500L
  const val MAX_BACKOFF_MS: Long = 8_000L
  fun retryDelayMs(responseCode: Int, retryAfterHeader: String?, attempt: Int): Long?
}

interface ProxyUpstream {
  fun totalLength(url: String): Long?                 // null = the origin declares no length
  fun open(url: String, range: ByteRange): InputStream // caller closes
}

class OkHttpProxyUpstream(
  client: OkHttpClient,
  sleep: (Long) -> Unit = Thread::sleep,
) : ProxyUpstream

// ---- MediaProxyServer.kt --------------------------------------------------------------------
data class ProxyRequest(
  val method: String, val token: String?, val rangeHeader: String?, val status: Int,
)

class MediaProxyServer(
  upstream: ProxyUpstream,
  registry: ProxyRegistry,
  bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
  requestedPort: Int = 0,
  internal val acceptConnection: (ServerSocket) -> Socket? = LocalNetworkOnly::acceptLocal,
) : Closeable {
  val port: Int
  val requestLog: List<ProxyRequest>
  fun start(): Int
  fun urlFor(media: PublishedMedia, host: String): String   // "http://$host:$port${media.path}"
  fun awaitRequest(token: String, timeoutMs: Long): Boolean // GET/HEAD on a published token only
  override fun close()
}
```

### Five departures from the plan's listing, each measured

1. **`OkHttpProxyUpstream(client, retry: ProxyRetry = ProxyRetry)` became
   `OkHttpProxyUpstream(client, sleep: (Long) -> Unit = Thread::sleep)`.** A `ProxyRetry` parameter
   typed by the object it defaults to buys nothing; a `sleep` seam buys the ability to *assert the
   delays this class actually asks for* (`ProxyUpstreamTest` observes `[500, 1000]`, `[3000]`,
   `[500, 1000, 2000, 4000]`) without a unit suite that really waits 7.5 seconds.
2. **The plan's `withRetries` returns a closed stream.** It wrote
   `response.use { checkNotNull(response.body).byteStream() }` — `use` closes the response, which
   closes the body, so `open` would have handed the relay an already-closed stream. Restructured:
   the fetch returns the `Response`, `totalLength` closes it, and `open` wraps `byteStream()` in a
   `FilterInputStream` whose `close` also closes the response (which is what returns the
   connection).
3. **The plan's `open` put the URL in an exception message** (`"no body from $url"`). Every URL in
   this package is a Navidrome stream URL carrying `u`, `t` and `s`. Removed; nothing in this
   package names a URL in a message, a log line or a `ProxyRequest`.
4. **`MediaProxyServer` accepts through `LocalNetworkOnly.acceptLocal`, not `ServerSocket.accept`.**
   The plan's accept loop called `accept()` directly. Task 1's security review added `acceptLocal`
   *for this call site* — its KDoc says so in as many words — and the plan predates it.
5. **`MAX_ATTEMPTS` is the last attempt that may be followed by another**, so the bound is
   `MAX_ATTEMPTS + 1 = 5` requests and `MAX_ATTEMPTS = 4` waits totalling 7.5 s. Measured on the
   origin, not read off the constant; the plan's own assertions
   (`retryDelayMs(429, null, MAX_ATTEMPTS + 1) == null`) require exactly this reading, and the
   KDoc now spells the off-by-one out.

Two hardenings the plan's code did not have, both with their own tests:

* **`RangeHeader.parse` used `toLong()` on the suffix**, which throws `NumberFormatException` on
  `bytes=-99999999999999999999` — out of a parser whose contract is a decision for every string a
  LAN peer can send, past every caller's `catch (IOException)`. Now `toLongOrNull() ?: Ignored`, on
  both ends.
* **`ProxyRetry` clamped only the upper end.** `Retry-After: -5` reached `Thread.sleep(-5000)` as an
  `IllegalArgumentException`. Now `coerceIn(0L, MAX_BACKOFF_MS)`.

---

## 2. Test counts, all measured

| Suite | Tier | Tests |
|---|---|---|
| `RangeHeaderTest` | JVM | **15** |
| `ProxyRegistryTest` | JVM | **10** |
| `ProxyUpstreamTest` | JVM | **13** |
| `MediaProxyServerTest` | JVM | **23** |
| `LiveNavidromeProxyTest` | live | **8** |

`:core:cast:test` totals **380** tests, 0 failures. `:core:cast:liveNavidromeTest` is 8/8 against
the running `ci-navidrome-1`.

`ProxyUpstreamTest` is not in the plan's file list. It exists because `OkHttpProxyUpstream`'s 429
loop cannot be observed from `MediaProxyServerTest` (which never reaches OkHttp) and must not be
observed only in the live suite (which would need a throttled Navidrome). It drives a real HTTP
origin on loopback, scripted **by request index**, because every interesting case here is *the same
request answered differently the second time*.

`./gradlew --no-build-cache check verifyNoMockFrameworks` → **BUILD SUCCESSFUL** (2m21s), with
`COVERAGE: :core:cast -- jacocoJvmCoverageVerification evaluated all 10 of its coverage floors.`
`./gradlew :core:media:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` →
**BUILD SUCCESSFUL**.

---

## 3. Coverage floors — measured, then falsified

Measured per class from `core/cast/build/reports/jacoco/test/jacocoTestReport.xml` after a plain
`:core:cast:test` (the live suite is `@Tag("live")` and contributes nothing).

```
RangeHeader                  BRANCH  48/48   LINE  29/29
MediaProxyServer             BRANCH  34/34   LINE 124/125
ProxyRetry                   BRANCH   8/8    LINE   4/4
OkHttpProxyUpstream          BRANCH   8/8    LINE  21/21
UpstreamThrottledException   BRANCH   2/2    LINE   4/4
ProxyRegistry                BRANCH  none    LINE  14/14
PublishedMedia               BRANCH  none    LINE   5/5
ProxyRequest                 BRANCH  none    LINE   1/1
ByteRange                    BRANCH  none    LINE   2/2
RangeRequest$Bounded/$Suffix BRANCH  none    LINE   1/1 each
RangeResolution$Partial      BRANCH  none    LINE   1/1
OkHttpProxyUpstream$open$1   BRANCH  none    LINE   4/4
```

100 BRANCH counters across the five branch-carrying classes, all covered. `:core:cast` went from
**8 floors to 10**: one BRANCH rule over those five, one LINE rule over the eight that carry a LINE
counter and no BRANCH counter.

**The plan's instruction was to put all six named classes on the BRANCH floor. That would have been
two vacuous rules**: `ProxyRegistry` and `ByteRange` carry *no BRANCH counter at all*, and a
CLASS-element BRANCH rule over a class with none is JaCoCo's `0/0` = `NaN`, reported as no violation
at any minimum — over the class that decides which requests this server will answer. They are on the
LINE rule instead, the same call `XmlText` and `ServedMedia` already got in this module.

`MediaProxyServer`'s one uncovered line is the `bindAddress` default (`0.0.0.0`); the tests bind
loopback deliberately, and I did not add a wildcard-bound listening socket to a unit suite to light
one line. It is 124/125 and `MediaProxyServer` is gated on BRANCH, so no floor rests on it.

### Falsification of the floors (withheld tests, measured)

| Floor | Withheld | Measured | Fired? |
|---|---|---|---|
| `RangeHeader` BRANCH | `everything unparseable is Ignored…` alone | **46/48 = 0.9583** | **no** |
| `RangeHeader` BRANCH | …plus `a number too large to hold…` and `a range against an empty entity…` | **41/48 = 0.8542** | yes |
| `MediaProxyServer` BRANCH | the **entire** eleven-row range table | **34/34 = 1.0000** | **no** |
| `MediaProxyServer` BRANCH | …plus the 206-body, Accept-Ranges and both HEAD tests (5) | **32/34 = 0.9412** | no |
| `MediaProxyServer` BRANCH | …plus 405, 400, 503 (8) | **31/34 = 0.9118** | no |
| `MediaProxyServer` BRANCH | …plus 503-no-delay, 502, truncated, traversal, 404 (13) | **28/34 = 0.8235** | yes |
| `OkHttpProxyUpstream` BRANCH | both give-up tests | **5/8 = 0.6250** | yes |
| `ProxyRegistry` LINE | `a revoked token…` + `revokeAll…` | **13/14 = 0.9286** | **no** |
| `ProxyRegistry` LINE | …plus `an unknown token is 404 and a revoked one stops working` | **11/14 = 0.7857** | yes |

Exact messages, e.g. *"Rule violated for class app.muplay.cast.proxy.RangeHeader: branches covered
ratio is 0.85, but expected minimum is 0.90"*.

**The second row is the most interesting result in this report.** Withholding the whole range table
— the eleven-case parameterised assertion this task's design section is built around — leaves
`MediaProxyServer` at **34/34, unchanged**. That is this plan's own thesis about the defect class,
demonstrated on its own code: a proxy that ignored `Range` entirely executes every one of those
branches. The table's value is in what it asserts (exact status, exact `Content-Range`, exact
**bytes**), and no coverage number can see it. The mutation probes can, and do.

---

## 4. Falsification transcript

Every mutation below was applied to the committed tree, `:core:cast:test` was run, the failing test
names were read out of the JUnit XML, and the mutation was reverted. Nothing here is predicted.

### Unit tier (16 mutations, all caught)

| # | Mutation | Red |
|---|---|---|
| 1 | `RangeHeader.resolve`: `firstByte >= totalLength` → `>` | 2 — `a first byte at or past the end is unsatisfiable` (*expected Unsatisfiable, was Partial(1000,999)*); table row `bytes=1000-` (*expected 416, was 206*) |
| 2 | `RangeHeader.parse`: suffix → `Ignored` | 3 — `a suffix range names how many bytes from the end`, `whitespace and case…`, table row `bytes=-1` (*expected 206, was 200*) |
| 3 | `resolve`: `Suffix(0)` → `Whole` | 2 — `a suffix of zero bytes is unsatisfiable…`, table row `bytes=-0` (*expected 416, was 200*) |
| 4 | `parse`: `Bounded` → `Ignored` | 9 — six parse/table assertions plus `a HEAD does not open the upstream body`, `every request is logged…`, `two renderers reading two ranges at once…` |
| 5 | 206 without `Content-Range` | 2 — `a ranged HEAD reports the range's length…` (*expected "bytes 100-199/1000", was null*), table `Content-Range` row |
| 6 | `HEAD` streams the body | 2 — `a HEAD returns the same headers as the GET and no body`, `a HEAD does not open the upstream body` |
| 7 | `Content-Type` hardcoded `audio/mpeg` | 2 — `the content type is the served mime type, at two different formats` (*expected audio/flac, was audio/mpeg*); the `MimeAgreement` sweep on its `.flac` leg |
| 8 | `resolve` matches `substringAfterLast('/')` | 1 — `a path outside the media prefix resolves to nothing, traversal included` (*expected null, was PublishedMedia(...)*) |
| 9 | token = `upstreamUrl.hashCode()` | 6 — including `two publications of the same url get different tokens`, `a token is long enough not to be guessed` (*Expected size: 32 but was: 8*) |
| 10 | `stream` relays `ByteRange(0, length-1)` | 3 — `a 206 body is the bytes that were asked for…` (*expected [-6,0,1,…], was [0,1,2,…]*), the table's body rows, `two renderers reading two ranges at once…` |
| 11 | `ProxyRetry` ignores `Retry-After` | 4 — including `the origin's own retry-after wins over the backoff` (*[500] vs [3000]*) |
| 12 | 503 → 502 | 2 — both throttle tests |
| 13 | `acceptConnection` default → `{ it.accept() }` | 1 — `connections are taken through the inbound local-network guard` |
| 14 | `awaitRequest` counts any method | 1 — `awaitRequest waits for a fetch of that token…` |
| 15 | path = prefix + token (no extension) | 4 — including the `MimeAgreement` sweep: *"the resource URL <…> carries no file extension, and Sonos infers the MIME type from the URL"* |
| 16 | `Accept-Ranges` dropped from `bodyHeaders` | 2 — `every response advertises byte ranges…`, `a HEAD returns the same headers…` |

### Live tier (4 mutations)

| Mutation | Red |
|---|---|
| `totalLength` reads `Content-Length` instead of `Content-Range` | **7 of 8** — including `the length probe against a real navidrome returns the real length` (*expected 40638, was 1*), `a stream url that no longer authenticates is a 502…` (*expected 502, was 200*), and the byte-exact relay |
| `stream` relays from byte 0 | **3** — every byte-exact range test |
| 206 without `Content-Range` | **3** — tail, middle and suffix |
| `resolve`: `>=` → `>` | **1** — `a range at or past the end…` at `bytes=40638-` (*expected 416, was 206*) — **but only after the test was fixed; see below** |

### The assertion that could not fail, and what was done about it

`a range past the end of a real track is 416 with the real length` asked for
`bytes=${direct.size + 1000}-`. The `>=` → `>` mutation changes the answer at **exactly**
`firstByte == totalLength` and nowhere else, so `length + 1000` is unsatisfiable under both the
correct code and the defect. **The live 416 test was green against the very defect it is named
for**, and the mutation run is what showed it — the run reported `*** NOTHING WENT RED ***`.

It now asks at three offsets: `length - 1` (206, and the file's real last byte, so the 416s are a
boundary and not this proxy's answer to every open-ended range), `length` (416) and `length + 1000`
(416). The mutation now reddens it. The unit table already had the boundary right (`bytes=1000-` on
a 1000-byte body), which is why the JVM tier caught this mutation all along.

### Mutation probes

Ten `proxy/*` entries added to `ci/mutation-probes.sh` (file constants `PROXY_RANGE`,
`PROXY_REGISTRY`, `PROXY_UPSTREAM`, `PROXY_SERVER`; `revert()` already checks out the whole
`core/cast` directory, so no change was needed there, and the probe count stays derived).

```
$ ./ci/mutation-probes.sh proxy/
Running 10 mutation probe(s). Each is applied alone and reverted.
  CAUGHT  proxy/range-boundary-off-by-one
  CAUGHT  proxy/range-suffix-zero-is-whole
  CAUGHT  proxy/no-content-range
  CAUGHT  proxy/stream-from-byte-zero
  CAUGHT  proxy/token-out-of-any-path
  CAUGHT  proxy/token-is-the-url
  CAUGHT  proxy/path-without-an-extension
  CAUGHT  proxy/retry-after-ignored
  CAUGHT  proxy/throttle-is-502
  CAUGHT  proxy/no-inbound-guard-at-the-call-site

10/10 probes caught (of 230 in the list).
```

`git status --porcelain` was empty immediately afterwards.

---

## 5. What the live container actually did

Measured against the running `deluan/navidrome:0.63.2` (`ci-navidrome-1`) on 2026-08-25, on a
seeded 64 kbps MP3 fixture of 40 638 bytes.

### `HEAD /rest/stream…&format=raw` — **200, with an accurate length**

```
HTTP/1.1 200 OK
Accept-Ranges: bytes
Content-Length: 40638
Content-Type: audio/mpeg
```

So a `HEAD`-based length probe *would* have worked. `OkHttpProxyUpstream` keeps the one-byte range
probe anyway — it costs one byte, it is right for both answers, and it depends only on RFC 7233
behaviour spec §4 already verified. The answer is pinned as an assertion
(`what a HEAD on rest slash stream really does, pinned`) so a change in Navidrome's behaviour is a
red build rather than a discovery during a cast. **The plan guessed `Content-Length` would be
`null` here; it is not.**

### `Range: bytes=0-0` on `format=raw` — **206 with the total**

```
HTTP/1.1 206 Partial Content
Content-Range: bytes 0-0/40638
Content-Length: 1
```

`Range` on a raw stream is honoured for real: the proxy's byte-exact tail, middle and suffix tests
all pass against it, and 416 comes back at exactly `firstByte == length`.

### Cold vs warm transcode — measured, and the reason no test here depends on it

* **Cold** (first request for a given track + *requested* bitrate, below source):
  `200`, `Accept-Ranges: none`, `Transfer-Encoding: chunked`, **no `Content-Length`, no
  `Content-Range` even when one is requested**. `totalLength` → `null` → the proxy's 502.
* **Warm** (every request after): an ordinary file — `Accept-Ranges: bytes`, an accurate
  `Content-Length`, `Range` answered 206.
* **A `HEAD` reports cold correctly and then warms it.** Measured twice, reproducibly: `HEAD` on an
  uncached transcode answers `Accept-Ranges: none` with no length, and **starts a background
  transcode that has populated the cache about a second later**. Two `HEAD`s back to back both say
  cold; the same URL a few hundred milliseconds later is warm.

That last fact is new, cost real time, and is now in `CLAUDE.md`. It has a hard consequence: **there
is no safe way to search for a cold entry.** Any probe that finds one has warmed it, and the
assertion that follows races the transcoder — the first version of `LiveNavidromeProxyTest` did
exactly that, passed once, then failed three runs running with `expected: 502 but was: 200`.
Searching *through the proxy*, so the search's own response is the observation, is correct and is
worse: a run that finds nothing has requested every bitrate below the source and cached all of them.

**I did that, and it has a cost other lanes will feel — see §7.**

### The 502 branch, live, without the cache

The proxy's `null`-length branch is now exercised against a different real Navidrome response with
no `Content-Range`: a stream URL with `u`, `t` and `s` stripped.

```
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 190
(no Content-Range)
```

This is stable, costs nothing, and is a **sharper** assertion than the transcode one: the response
carries a perfectly good `Content-Length`, so a probe that read `Content-Length` instead of
`Content-Range` would answer 190 and this proxy would hand a speaker a JSON error document as
`audio/mpeg`, with a length that agreed with itself. That mutation reddens seven of the eight live
tests. That a live transcode declares no length stays pinned where it already was, in
`:core:network`'s `LiveNavidromeTest.coldTranscode`, in the same CI job.

### The live gate can fail

* Two consecutive `:core:cast:liveNavidromeTest` invocations both **executed** (no `UP-TO-DATE`, no
  `FROM-CACHE`): the `outputs.upToDateWhen { false }` / `cacheIf { false }` pair generalised to
  `:core:cast` along with the task.
* Pointed at a dead port (`localhost:4544`) instead of stopping the shared container:
  **`8 tests completed, 8 failed`, BUILD FAILED.** A live gate that passes with no Navidrome is the
  silent gate this project has already found once; this one is not it.

---

## 6. Wiring

* `build.gradle.kts`: the `liveNavidromeTest` registration became
  `listOf(":core:network", ":core:cast").forEach { livePath -> project(livePath) { … } }`.
  `LIVE_NAVIDROME_TEST_TASK_NAME` is untouched, so `ConventionTest`'s
  `the live-Navidrome test task name is not hand-synced into drift` still reads one constant on each
  side. Verified: `:core:cast:liveNavidromeTest` and `:core:network:liveNavidromeTest` both exist
  and describe themselves correctly.
* `.github/workflows/pr.yml`: the `live-navidrome` job runs
  `./gradlew :core:network:liveNavidromeTest :core:cast:liveNavidromeTest`, and its failure-artifact
  path became a two-line list. Kept deliberately small and localised — `p4t1-branch` is holding this
  file.
* `core/cast/build.gradle.kts`: `testImplementation(project(":core:network"))`, **test-only**, for
  `LiveNavidromeProxyTest`'s stream URLs. Nothing in `:core:cast`'s main source set knows Navidrome
  exists; the proxy takes a URL string and relays it, which is what lets Task 7 decide where the URL
  comes from. `ConventionTest`'s `only the cast module's proxy package may reach for OkHttp` scans
  `src/main` only and was already exempting `app.muplay.cast.proxy`, which is where
  `OkHttpProxyUpstream` lives.

---

## 7. What I did not fix, and what I believe is still wrong

1. **I exhausted the transcoding cache for one of the three seeded MP3 tracks on the shared
   container.** A 63-bitrate `HEAD` sweep plus three proxy-search runs cached every bitrate below
   64 kbps for `Track 1`. Spot-checked afterwards: that track has 0 of 5 sampled bitrates still
   cold, the other two have 4/5 and 5/5. `:core:network`'s `LiveNavidromeTest.coldTranscode` picks a
   random Music track, so it now has roughly a **1-in-3 chance of failing** with its own
   "no bitrate below 64 kbps produced a live transcode" message. That is a flake I handed to whoever
   runs it next, and the only repair is recreating the container, which is not something one agent
   may do to a shared single-instance container. Nothing in `:core:cast` depends on a cold entry any
   more, so the damage stops here. **This is worth a controller's decision about when to recreate
   `ci-navidrome-1`.**
2. **A poisoned shared build-cache entry made `check` fail on someone else's file.** The first
   `./gradlew check` run failed `:core:media:lintDebug` on
   `.claude/worktrees/p3t8b/core/media/src/main/kotlin/app/muplay/media/ProgressWriter.kt:141`
   (a Media3 `@UnstableApi` opt-in error). **That file does not exist in this worktree or on
   master** — the result was restored from `~/.gradle/caches/build-cache-1`, produced by another
   lane's worktree. The same run printed `evaluated all 8 of its coverage floors` for `:core:cast`
   from a cached notice while the real number was 10. `./gradlew --no-build-cache check
   verifyNoMockFrameworks` is **BUILD SUCCESSFUL** and reports 10 floors. So: **a lint or notice
   failure naming a path under another lane's worktree is a cache artefact, not your build** — and
   it means `check` results in this repo are only trustworthy with `--no-build-cache` while several
   worktrees share a cache. I did not change any cache configuration; that is a project-level call.
   Confirmed afterwards: `ProgressWriter.kt` has since landed on master (`d8e84fa`) with the
   opt-in in place, and `--no-build-cache check verifyNoMockFrameworks` on the merged branch is
   green — so the failure really was a stale entry from that lane's in-progress worktree, and
   never a state master was in.
3. **`ConventionTest`'s mock-framework rule did not trip on the worktrees this time.** CLAUDE.md
   warns that a worktree under `.claude/worktrees/` reddens it. It passed here (`:app:testDebugUnitTest`
   green inside the `check` run). Nothing to do; recorded because the brief expected a failure.
4. **`MediaProxyServer` binds `0.0.0.0` by default and no test exercises that default.** The
   inbound guard is what makes the wildcard bind defensible, and Task 7 owns choosing the bind
   address via `LocalAddress`. I did not add a wildcard-bound socket to the unit suite.
5. **The inbound guard's *refusal* is not observable from this module's tests.** Loopback is local
   by construction, so a loopback-only test can only assert that the proxy accepts *through*
   `LocalNetworkOnly::acceptLocal` (function-reference equality, mutation-probed) and that its loop
   carries on after a refusal. The refusal itself is covered by `LocalNetworkOnlyTest` and the
   pre-existing `cast/no-inbound-guard` probe. The chain is complete but it is a chain, not one
   assertion, and that is worth knowing before someone "simplifies" either end.
6. **`ProxyRegistry.resolve` is a linear scan.** A cast session publishes a queue, not a library, so
   this is not a real cost — but if Task 9 ever publishes several hundred items and polls, it is
   worth an index. The whole-path comparison is the security control and must survive any such
   change.
7. **The proxy relays; it does not cache**, per the plan's scope section. Serving the Media3 cache
   to a renderer is still deferred.
