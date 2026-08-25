package app.muplay.cast.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * HTTP header semantics, which are not the same as `Map<String, String>` semantics in three ways
 * that all matter on this wire:
 *
 * 1. **Names are case-insensitive.** Sonos sends `CONTENT-TYPE`, most DLNA renderers send
 *    `Content-Type`, and an SSDP reply sends `LOCATION` in capitals. A `Map` lookup on the wrong
 *    case returns null, and a null `LOCATION` is a device that silently never appears.
 * 2. **A name may repeat.** Rare here but legal, and dropping a repeat silently is the kind of
 *    thing that is only noticed years later.
 * 3. **Order is preserved on the way out.** A renderer must never be able to tell this client
 *    apart by header order changing between runs, and a byte-exact assertion on a rendered
 *    response head is only possible if the order is deterministic.
 */
class HttpHeadersTest {

  private val headers = HttpHeaders.of(
    "Content-Type" to "text/xml; charset=\"utf-8\"",
    "CONTENT-LENGTH" to "128",
    "Server" to "Linux UPnP/1.0 Sonos/84.1-52250",
  )

  @Test
  fun `a header is found whatever case the peer used`() {
    // Two different lookups of two different headers, so a `get` that lowercases only the needle
    // and not the haystack fails, and so does one that does the reverse.
    assertThat(headers["content-type"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["Content-Type"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["CONTENT-TYPE"]).isEqualTo("text/xml; charset=\"utf-8\"")
    assertThat(headers["content-length"]).isEqualTo("128")
    assertThat(headers["Content-Length"]).isEqualTo("128")
  }

  @Test
  fun `a header that is not there is null rather than empty`() {
    // The distinction is load-bearing in Task 6: an absent `Range` means "send the whole thing",
    // and an empty one is malformed. Collapsing them loses the difference.
    assertThat(headers["Range"]).isNull()
    assertThat(headers.all("Range")).isEmpty()
  }

  @Test
  fun `a repeated name keeps every value, in the order the peer sent them`() {
    val repeated = HttpHeaders.of(
      "X-Trial" to "first",
      "x-trial" to "second",
      "X-TRIAL" to "third",
    )

    // The exact list, not `hasSize(3)` and not `contains(...)`: order is the property under test,
    // and `containsExactly` is the only assertion that fails when the order changes.
    assertThat(repeated.all("x-trial")).containsExactly("first", "second", "third")
    // `get` is "the first value", which is what every consumer in this module wants.
    assertThat(repeated["X-Trial"]).isEqualTo("first")
  }

  @Test
  fun `names come back in the order they were given, with the case the peer used`() {
    // Rendering (Task 6) writes these back out verbatim. A `names` that sorted or lowercased would
    // make the byte-exact response-head assertions in `HttpWireTest` unwritable.
    assertThat(headers.names).containsExactly("Content-Type", "CONTENT-LENGTH", "Server")
    assertThat(headers.size).isEqualTo(3)
  }

  @Test
  fun `content length is parsed as a number, and refuses what is not one`() {
    // Four observations, three of them rejections. A `contentLength()` returning a constant passes
    // at most one.
    assertThat(HttpHeaders.of("Content-Length" to "0").contentLength()).isEqualTo(0L)
    assertThat(HttpHeaders.of("Content-Length" to "4096").contentLength()).isEqualTo(4096L)
    assertThat(HttpHeaders.of("Content-Length" to "chunked").contentLength()).isNull()
    assertThat(HttpHeaders.of("Content-Length" to "-1").contentLength()).isNull()
    assertThat(HttpHeaders.EMPTY.contentLength()).isNull()
  }

  @Test
  fun `the empty headers really are empty`() {
    // Rule 3: `EMPTY.names` being empty is what makes every `allMatch`-shaped assertion elsewhere
    // in this module suspect, so the emptiness itself is pinned here where it is the subject.
    assertThat(HttpHeaders.EMPTY.names).isEmpty()
    assertThat(HttpHeaders.EMPTY.size).isZero
    assertThat(HttpHeaders.EMPTY["anything"]).isNull()
  }
}
