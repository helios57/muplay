package app.muplay.integrations

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The whole contract of the one value both integrations are built on.
 *
 * Two separate jobs are tested here and they are tested separately on purpose: **a credential
 * must never survive into the stored URL**, and **the cleartext policy must be unbypassable**.
 * Each failure member is observed at a real input, and every accepted URL is observed at *two*
 * different inputs, because a `parse` that returned a constant `IntegrationBaseUrl` would satisfy
 * a single-observation test of either job.
 */
class IntegrationBaseUrlTest {

  private fun valid(raw: String, policy: CleartextPolicy = CleartextPolicy.Forbidden): String {
    val result = IntegrationBaseUrl.parse(raw, policy)
    assertThat(result).isInstanceOf(BaseUrlResult.Valid::class.java)
    return (result as BaseUrlResult.Valid).url.value
  }

  private fun url(raw: String, policy: CleartextPolicy = CleartextPolicy.Forbidden) =
    (IntegrationBaseUrl.parse(raw, policy) as BaseUrlResult.Valid).url

  @Test
  fun `an https url is accepted and normalised to end in a slash`() {
    // Two observations of the host, so a hardcoded return value fails one of them.
    assertThat(valid("https://lidarr.example.com")).isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://books.example.net")).isEqualTo("https://books.example.net/")
  }

  @Test
  fun `a url that already ends in a slash is not given a second one`() {
    assertThat(valid("https://lidarr.example.com/")).isEqualTo("https://lidarr.example.com/")
  }

  /**
   * The reverse-proxy case. Servarr apps support a `urlBase` setting, so a real deployment is
   * commonly at `https://home.example.com/lidarr` rather than at a host root — and Retrofit
   * resolves a relative path against a base URL by *replacing* the last path segment unless the
   * base ends in `/`. Getting this wrong turns `api/v1/system/status` into
   * `https://home.example.com/api/v1/system/status`, silently dropping the prefix.
   */
  @Test
  fun `a url base path is preserved and terminated with a slash`() {
    assertThat(valid("https://home.example.com/lidarr")).isEqualTo("https://home.example.com/lidarr/")
    assertThat(valid("https://home.example.com/books")).isEqualTo("https://home.example.com/books/")
  }

  @Test
  fun `a non-default port is preserved`() {
    // Two ports, not one: a `parse` that hardcoded :8686 passes a single-observation test.
    assertThat(valid("https://nas.local:8686")).isEqualTo("https://nas.local:8686/")
    assertThat(valid("https://nas.local:9090")).isEqualTo("https://nas.local:9090/")
  }

  /**
   * The requirement this whole type exists for. Lidarr accepts its API key as a query parameter,
   * so a URL copied out of a browser address bar can arrive with the key already in it. `parse`
   * does not *reject* that — a rejection would be a dead end for a user who did nothing wrong —
   * it **discards** the query, so the secret cannot reach DataStore, a log line, a recorded
   * request or a crash report.
   */
  @Test
  fun `a query string is discarded, including one carrying an api key`() {
    assertThat(valid("https://lidarr.example.com/?apikey=SUPERSECRET"))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://lidarr.example.com/?apikey=SUPERSECRET")).doesNotContain("SUPERSECRET")
    assertThat(valid("https://lidarr.example.com/settings/general?x=1&y=2"))
      .isEqualTo("https://lidarr.example.com/settings/general/")
  }

  @Test
  fun `a fragment is discarded`() {
    assertThat(valid("https://lidarr.example.com/#/settings/general"))
      .isEqualTo("https://lidarr.example.com/")
  }

  /**
   * `https://user:hunter2@host` is the other way a secret rides on a URL, and OkHttp parses it
   * happily. Discarded for the same reason as the query.
   */
  @Test
  fun `userinfo is discarded`() {
    val parsed = valid("https://luc:hunter2@lidarr.example.com/")

    assertThat(parsed).isEqualTo("https://lidarr.example.com/")
    assertThat(parsed).doesNotContain("hunter2")
    assertThat(parsed).doesNotContain("luc")
  }

  /**
   * All three secret-bearing components at once, on the shape a user would actually paste: the
   * Lidarr settings page, reached through a proxy that asked for basic auth, with the API key in
   * the query. Each component is discarded by its own line in `normalise`, so this is the one
   * observation that would notice a future edit dropping any single one of them while the
   * three tests above still passed — none of them varies more than one component at a time.
   */
  @Test
  fun `userinfo, query and fragment are all discarded from one url at once`() {
    val parsed = valid("https://luc:hunter2@lidarr.example.com/settings/general?apikey=SUPERSECRET#tab")

    assertThat(parsed).isEqualTo("https://lidarr.example.com/settings/general/")
    assertThat(parsed).doesNotContain("hunter2", "luc", "SUPERSECRET", "tab", "?", "#", "@")
  }

  @Test
  fun `surrounding whitespace is trimmed rather than rejected`() {
    // Pasting from a notes app or a terminal brings a trailing newline with it.
    assertThat(valid("  https://lidarr.example.com \n")).isEqualTo("https://lidarr.example.com/")
  }

  @Test
  fun `a blank url is Blank, and that is distinct from malformed`() {
    assertThat(IntegrationBaseUrl.parse("", CleartextPolicy.Forbidden)).isEqualTo(BaseUrlResult.Blank)
    assertThat(IntegrationBaseUrl.parse("   ", CleartextPolicy.Forbidden)).isEqualTo(BaseUrlResult.Blank)
  }

  /**
   * `192.168.1.20:8686` is what a user types, and `HttpUrl.parse` returns null for it exactly as
   * it does for a genuine non-URL. Collapsing the two would print "that is not a valid URL" at
   * someone who typed something entirely reasonable.
   */
  @Test
  fun `a url with no scheme is MissingScheme, not Malformed`() {
    assertThat(IntegrationBaseUrl.parse("192.168.1.20:8686", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.MissingScheme)
    assertThat(IntegrationBaseUrl.parse("lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.MissingScheme)
  }

  @Test
  fun `a non-http scheme is Malformed`() {
    assertThat(IntegrationBaseUrl.parse("ftp://lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.Malformed)
    assertThat(IntegrationBaseUrl.parse("https://", CleartextPolicy.Allowed))
      .isEqualTo(BaseUrlResult.Malformed)
  }

  /**
   * The two halves of the cleartext resolution, each observed. Neither is satisfied by a `parse`
   * that ignores its `policy` argument: the first would fail if `parse` always allowed, the
   * second if it always forbade. **This is the argument-passthrough rule applied to a policy
   * object** — the defect class this project has already shipped six times is a method that
   * accepts an argument and then hardcodes the value.
   */
  @Test
  fun `http is accepted when the policy allows cleartext`() {
    assertThat(valid("http://192.168.1.20:8686", CleartextPolicy.Allowed))
      .isEqualTo("http://192.168.1.20:8686/")
    assertThat(valid("http://nas.local:8686", CleartextPolicy.Allowed))
      .isEqualTo("http://nas.local:8686/")
  }

  @Test
  fun `http is refused when the policy forbids cleartext, and the host is reported`() {
    // The host comes back in the result so the message can name it. Two different hosts, so a
    // hardcoded host fails.
    assertThat(IntegrationBaseUrl.parse("http://192.168.1.20:8686", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("192.168.1.20"))
    assertThat(IntegrationBaseUrl.parse("http://nas.local:8686", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
  }

  /**
   * The refusal path carries a string into a UI message, so it is a place a secret could escape
   * even though the URL itself is never stored. `HttpUrl.host` is host-only today; this pins that,
   * because the obvious "improvement" — reporting `parsed.toString()` or the authority so the
   * message can show the port — would put `luc:hunter2@` on screen and into any screenshot of it.
   */
  @Test
  fun `the refused host carries no userinfo and no port`() {
    assertThat(IntegrationBaseUrl.parse("http://luc:hunter2@nas.local:8686", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
  }

  @Test
  fun `https is accepted under both policies`() {
    // The policy must gate `http` and *only* `http`. Without this, a `parse` that refused
    // everything under `Forbidden` would still pass the test above it.
    assertThat(valid("https://lidarr.example.com", CleartextPolicy.Forbidden))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(valid("https://lidarr.example.com", CleartextPolicy.Allowed))
      .isEqualTo("https://lidarr.example.com/")
  }

  @Test
  fun `the scheme check is case-insensitive`() {
    assertThat(valid("HTTPS://lidarr.example.com", CleartextPolicy.Forbidden))
      .isEqualTo("https://lidarr.example.com/")
    assertThat(IntegrationBaseUrl.parse("HTTP://nas.local", CleartextPolicy.Forbidden))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
  }

  @Test
  fun `two urls with the same value are equal and hash alike`() {
    // The type is used as a map key and compared in tests; identity equality would make both
    // silently wrong.
    val a = url("https://a.example.com")
    val b = url("https://a.example.com/")
    val c = url("https://b.example.com")

    assertThat(a).isEqualTo(b)
    assertThat(a.hashCode()).isEqualTo(b.hashCode())
    assertThat(a).isNotEqualTo(c)
  }

  /**
   * **The origin comparison an API key's whole blast radius rests on.**
   *
   * `LidarrAuthInterceptor` and `BinderyAuthInterceptor` are network interceptors that ask this on
   * every redirect hop and attach `X-Api-Key` only when the answer is `true`. Before they did,
   * OkHttp carried that header to a cross-origin redirect target verbatim -- measured on 5.5.0,
   * `RetryAndFollowUpInterceptor.buildRedirectRequest` strips `Authorization` and nothing else --
   * so a server answering `302 Location: https://evil.example/` was given the key. Bindery's is
   * instance-wide and admin-equivalent.
   *
   * The positive case is asserted at two different base URLs, because a method that returned a
   * constant `true` would satisfy a single one and is the exact shape this file's other tests
   * guard against everywhere else.
   */
  @Test
  fun `a url on the same scheme, host and port is the same origin`() {
    assertThat(url("https://lidarr.example.com").isSameOrigin("https://lidarr.example.com/api/v1/system/status".toHttpUrl()))
      .isTrue()
    assertThat(url("https://books.example.net:8787").isSameOrigin("https://books.example.net:8787/api/v1/health".toHttpUrl()))
      .isTrue()
  }

  /**
   * The path is deliberately **not** part of an origin, and this is the case that makes the whole
   * guard usable rather than a ban on redirects.
   *
   * A Servarr `urlBase` install answers `/api/v1/...` with a `307` to `{urlBase}/api/v1/...`
   * (`UrlBaseMiddleware.cs`), and a reverse proxy that normalises a path does the same. Those hops
   * must keep the key or every proxied install breaks. A query and a fragment are on the same
   * footing: neither changes who is being talked to.
   */
  @Test
  fun `a different path, query or fragment on the same server is still the same origin`() {
    val base = url("https://home.example.com/lidarr")

    assertThat(base.isSameOrigin("https://home.example.com/lidarr/api/v1/system/status".toHttpUrl())).isTrue()
    // Above the configured path prefix, not merely under it -- which is what a `urlBase` redirect
    // from a base URL configured at the host root actually looks like.
    assertThat(base.isSameOrigin("https://home.example.com/somewhere/else".toHttpUrl())).isTrue()
    assertThat(base.isSameOrigin("https://home.example.com/lidarr/x?y=z#f".toHttpUrl())).isTrue()
  }

  /**
   * A **scheme** change is a different origin, including the `https` -> `http` downgrade an
   * attacker would prefer and the `http` -> `https` upgrade a well-meaning proxy might answer.
   *
   * Both are refused, and the upgrade being refused is a deliberate false positive rather than an
   * oversight: withholding a secret is the fail-closed direction, and in a shipping build it
   * cannot arise at all, because [CleartextPolicy.Forbidden] refuses an `http://` base URL at
   * [IntegrationBaseUrl.parse] and the configured origin is therefore always `https`.
   */
  @Test
  fun `a different scheme is a different origin`() {
    assertThat(url("https://lidarr.example.com").isSameOrigin("http://lidarr.example.com/x".toHttpUrl()))
      .isFalse()
    assertThat(
      url("http://nas.local:8686", CleartextPolicy.Allowed)
        .isSameOrigin("https://nas.local:8686/x".toHttpUrl()),
    ).isFalse()
  }

  /**
   * A **host** change is a different origin. This is the one the vulnerability was reported for:
   * `302 Location: https://evil.example/` and the key goes with it.
   */
  @Test
  fun `a different host is a different origin`() {
    val base = url("https://lidarr.example.com")

    assertThat(base.isSameOrigin("https://evil.example.com/x".toHttpUrl())).isFalse()
    // A subdomain is a different host, not a related one. Cookies have a domain rule; an API key
    // header has none, and inventing one here would be inventing a way to leak the key.
    assertThat(base.isSameOrigin("https://evil.lidarr.example.com/x".toHttpUrl())).isFalse()
    // ...and a prefix of the configured host, which a naive `startsWith` would accept.
    assertThat(base.isSameOrigin("https://lidarr.example.com.evil.test/x".toHttpUrl())).isFalse()
  }

  /**
   * A **port** change is a different origin, on the identical host.
   *
   * Not a hypothetical distinction: a machine hosting several self-hosted apps behind one name
   * separates them by port, and on a shared box those are routinely *different people's* servers.
   */
  @Test
  fun `a different port on the same host is a different origin`() {
    assertThat(url("https://nas.local:8686").isSameOrigin("https://nas.local:9090/x".toHttpUrl()))
      .isFalse()
  }

  /**
   * The reason this comparison lives on this type rather than in each client: it is made against
   * the URL **as OkHttp canonicalised it**, and a client re-deriving it from the stored string
   * would disagree with the parser that actually connects -- on exactly the inputs an attacker
   * picks.
   *
   * Both halves here would fail a hand-rolled comparison. An uppercase host is the same host
   * (`HttpUrl` lowercases it), and an explicit `:443` on `https` is the same port (`HttpUrl`
   * defaults it from the scheme), so a `String`-equality guard would withhold the key from the
   * user's own server and produce a bug report about authentication rather than about redirects.
   */
  @Test
  fun `the comparison uses OkHttp's canonical host and port, not the raw text`() {
    val base = url("https://lidarr.example.com")

    assertThat(base.isSameOrigin("https://LIDARR.EXAMPLE.COM/api/v1/system/status".toHttpUrl())).isTrue()
    assertThat(base.isSameOrigin("https://lidarr.example.com:443/api/v1/system/status".toHttpUrl())).isTrue()
  }

  /**
   * The three arms of `equals` the test above never reaches: the identity short-circuit, and the
   * two ways a non-`IntegrationBaseUrl` argument arrives. The `String` case is the one that
   * matters in practice — `value` is a `String`, so an `equals` written as `value == other` would
   * pass every assertion above and quietly make `IntegrationBaseUrl` equal to its own raw text.
   */
  @Test
  fun `a url equals only itself and another url with the same value`() {
    val a = url("https://a.example.com")

    assertThat(a).isEqualTo(a)
    assertThat(a).isNotEqualTo(null)
    assertThat(a).isNotEqualTo("https://a.example.com/")
    assertThat(a.equals("https://a.example.com/")).isFalse()
  }

  /**
   * `toString` is the form this value reaches a log line, a `Retrofit.baseUrl` call and an
   * assertion message in, so it has to be the normalised URL rather than
   * `IntegrationBaseUrl@1b6d3586`. Two different inputs, so a hardcoded string fails one.
   */
  @Test
  fun `toString is the normalised value`() {
    assertThat(url("https://a.example.com").toString()).isEqualTo("https://a.example.com/")
    assertThat(url("https://home.example.com/lidarr").toString())
      .isEqualTo("https://home.example.com/lidarr/")
  }

  /**
   * The structural guarantee the whole type rests on: `IntegrationCredentials` (Task 2) can hold
   * an `IntegrationBaseUrl` and be sure it went through [IntegrationBaseUrl.parse], because there
   * is no other way to make one. That is true only while every constructor stays non-public, and
   * deleting the word `private` is a one-word edit no other test in this file would notice.
   *
   * Synthetic constructors are filtered out rather than asserted on, and that is not a loophole
   * being waved through: Kotlin compiles a `private constructor` that its own companion calls into
   * a private constructor **plus** a `public synthetic` bridge taking a trailing
   * `DefaultConstructorMarker` (confirmed with `javap -v`: `flags: (0x1001) ACC_PUBLIC,
   * ACC_SYNTHETIC`). `ACC_SYNTHETIC` is exactly the flag that makes a member unnameable from Java
   * and Kotlin source alike -- `javac` rejects a reference to one outright -- so it is reachable
   * only by the same reflection this test is written in. Asserting over the unfiltered array would
   * mean asserting that Kotlin does not do what Kotlin does, which is a test that fails on
   * day one and gets deleted.
   */
  @Test
  fun `there is no public way to construct a base url`() {
    val fromSource = IntegrationBaseUrl::class.java.declaredConstructors.filterNot { it.isSynthetic }

    // A filter that removed everything would make the assertion below vacuously true.
    assertThat(fromSource).describedAs("non-synthetic constructors").isNotEmpty()
    assertThat(fromSource)
      .describedAs("a public constructor bypasses the cleartext policy and the secret stripping")
      .allMatch { !java.lang.reflect.Modifier.isPublic(it.modifiers) }
  }

  /**
   * Every failure member carries a message and every message names the service, so the same type
   * serves both configuration screens without either of them writing copy of its own.
   *
   * Asserted as an **exact mapped list**, not with `allMatch`: `allMatch` over a collection is
   * vacuously true if the collection is empty, and `isNotNull` on each would be satisfied by one
   * shared string.
   */
  @Test
  fun `every failure has a distinct actionable message and Valid has none`() {
    val failures = listOf(
      BaseUrlResult.Blank,
      BaseUrlResult.MissingScheme,
      BaseUrlResult.Malformed,
      BaseUrlResult.CleartextForbidden("nas.local"),
    )

    val messages = failures.map { it.message(IntegrationService.LIDARR) }

    assertThat(messages).doesNotContainNull()
    assertThat(messages).doesNotHaveDuplicates()
    // The service name is interpolated, so the same result under the other service reads
    // differently -- proving the argument is used rather than accepted and dropped.
    assertThat(BaseUrlResult.MissingScheme.message(IntegrationService.LIDARR))
      .contains("Lidarr").contains("https://")
    assertThat(BaseUrlResult.MissingScheme.message(IntegrationService.BINDERY))
      .contains("Bindery").contains("https://")
    // The cleartext message must name the host and say what to do about it, or it is a dead end.
    assertThat(BaseUrlResult.CleartextForbidden("nas.local").message(IntegrationService.LIDARR))
      .contains("nas.local")
      .contains("HTTPS")
    val valid = IntegrationBaseUrl.parse("https://a.example.com", CleartextPolicy.Forbidden)
    assertThat(valid.message(IntegrationService.LIDARR)).isNull()
  }

  /**
   * The other three messages carry the service name too, and each at *two* services — the test
   * above pins that only for `MissingScheme`, so a `Blank` or `Malformed` message with the name
   * hardcoded to "Lidarr" would ship, and Bindery's setup screen would tell the user to enter the
   * address of their Lidarr server. `CleartextForbidden` additionally has to vary by host.
   */
  @Test
  fun `every message varies with the service it is asked about`() {
    val results = listOf(
      BaseUrlResult.Blank,
      BaseUrlResult.MissingScheme,
      BaseUrlResult.Malformed,
      BaseUrlResult.CleartextForbidden("nas.local"),
    )

    for (result in results) {
      assertThat(result.message(IntegrationService.LIDARR))
        .describedAs("%s under Lidarr", result)
        .contains("Lidarr")
        .doesNotContain("Bindery")
      assertThat(result.message(IntegrationService.BINDERY))
        .describedAs("%s under Bindery", result)
        .contains("Bindery")
        .doesNotContain("Lidarr")
    }

    assertThat(BaseUrlResult.CleartextForbidden("books.local").message(IntegrationService.BINDERY))
      .contains("books.local")
      .doesNotContain("nas.local")
  }

  /**
   * The results are compared by later tasks' tests and held in UI state, so their equality has to
   * discriminate. A `data class` gets this from the compiler — but `CleartextForbidden` is the one
   * member carrying data, and an `equals` that ignored `host` (or a `host` that stopped being a
   * constructor property) would leave every assertion above green, since each of those compares a
   * result against one built from the *same* host.
   */
  @Test
  fun `results with different contents are not equal`() {
    assertThat(BaseUrlResult.CleartextForbidden("nas.local"))
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local"))
    assertThat(BaseUrlResult.CleartextForbidden("nas.local"))
      .isNotEqualTo(BaseUrlResult.CleartextForbidden("books.local"))
    assertThat(BaseUrlResult.CleartextForbidden("nas.local").hashCode())
      .isEqualTo(BaseUrlResult.CleartextForbidden("nas.local").hashCode())

    assertThat(BaseUrlResult.Blank).isNotEqualTo(BaseUrlResult.MissingScheme)
    assertThat(BaseUrlResult.MissingScheme).isNotEqualTo(BaseUrlResult.Malformed)
    assertThat(BaseUrlResult.Malformed).isNotEqualTo(BaseUrlResult.Blank)

    val valid = IntegrationBaseUrl.parse("https://a.example.com", CleartextPolicy.Forbidden)
    assertThat(valid).isEqualTo(BaseUrlResult.Valid(url("https://a.example.com")))
    assertThat(valid).isNotEqualTo(BaseUrlResult.Valid(url("https://b.example.com")))
    assertThat(valid).isNotEqualTo(BaseUrlResult.Blank)
  }

  @Test
  fun `the service display names are the ones a user reads`() {
    assertThat(IntegrationService.entries.map { it.displayName })
      .containsExactly("Lidarr", "Bindery")
  }
}
