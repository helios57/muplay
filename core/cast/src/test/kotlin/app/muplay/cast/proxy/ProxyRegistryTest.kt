package app.muplay.cast.proxy

import app.muplay.cast.didl.ServedMedia
import app.muplay.model.StreamFormat
import java.security.SecureRandom
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The capability the proxy serves, and the path that carries it.
 *
 * The token is the whole of the proxy's authorisation: it binds on the LAN, so a path a peer can
 * guess is an open relay for the library. Every assertion below is therefore about a *rejection*
 * or about two publications differing -- a registry that resolved anything would pass a test that
 * only ever resolved a path it had just minted.
 */
class ProxyRegistryTest {

  private val registry = ProxyRegistry()

  private val mp3 = ServedMedia.of("mp3", StreamFormat.Raw)

  @Test
  fun `a published path ends in the served extension, because sonos sniffs the url`() {
    // Two observations, so the extension cannot be a constant. Task 3's FakeRenderer answers
    // 714 to an extensionless URL, so this is enforced end to end as well as here.
    assertThat(registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw)).path).endsWith(".mp3")
    assertThat(registry.publish(UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw)).path).endsWith(".flac")
  }

  @Test
  fun `a published path is the prefix, the token and the extension, and nothing else`() {
    // The exact shape, against a random that is not random: a token this test chose, so the path
    // can be asserted whole rather than by its ends. The 0xff byte is the one that matters --
    // `"%02x".format` on a Byte that arrives sign-extended renders `ffffffff`, a 40-character
    // token that still ends in `.mp3` and still passes every `endsWith` assertion here.
    val bytes = ByteArray(ProxyRegistry.TOKEN_BYTES) { 0 }.also { it[0] = 0xFF.toByte(); it[1] = 0x0A }
    val fixed = ProxyRegistry(FixedRandom(bytes))

    val expectedToken = "ff0a" + "0".repeat((ProxyRegistry.TOKEN_BYTES - 2) * 2)

    assertThat(fixed.publish(UPSTREAM, mp3).path).isEqualTo("/media/$expectedToken.mp3")
  }

  @Test
  fun `two publications of the same url get different tokens`() {
    // A token is a capability, not an identity. Reusing one across sessions would make a revoked
    // session's URL work again.
    assertThat(registry.publish(UPSTREAM, mp3).token).isNotEqualTo(registry.publish(UPSTREAM, mp3).token)
  }

  @Test
  fun `a token is long enough not to be guessed`() {
    assertThat(registry.publish(UPSTREAM, mp3).token).hasSize(ProxyRegistry.TOKEN_BYTES * 2)
    assertThat(ProxyRegistry.TOKEN_BYTES).isGreaterThanOrEqualTo(16)
    assertThat(registry.publish(UPSTREAM, mp3).token).matches("[0-9a-f]+")
  }

  @Test
  fun `resolving a published path returns the upstream url it was published for`() {
    // Two different upstreams, so `resolve` cannot return a constant.
    val first = registry.publish("https://nav.example/rest/stream?id=1", mp3)
    val second = registry.publish("https://nav.example/rest/stream?id=2", mp3)

    assertThat(registry.resolve(first.path)!!.upstreamUrl).isEqualTo("https://nav.example/rest/stream?id=1")
    assertThat(registry.resolve(second.path)!!.upstreamUrl).isEqualTo("https://nav.example/rest/stream?id=2")
  }

  @Test
  fun `resolving a published path returns the format it was published for`() {
    // The other field on the item, observed at two values for the same reason.
    val asMp3 = registry.publish(UPSTREAM, ServedMedia.of("mp3", StreamFormat.Raw))
    val asFlac = registry.publish(UPSTREAM, ServedMedia.of("flac", StreamFormat.Raw))

    assertThat(registry.resolve(asMp3.path)!!.served.mimeType).isEqualTo("audio/mpeg")
    assertThat(registry.resolve(asFlac.path)!!.served.mimeType).isEqualTo("audio/flac")
  }

  @Test
  fun `an unknown path resolves to nothing`() {
    assertThat(registry.resolve("/media/deadbeef.mp3")).isNull()
    assertThat(registry.resolve("/media/")).isNull()
    assertThat(registry.resolve("/")).isNull()
  }

  @Test
  fun `a path outside the media prefix resolves to nothing, traversal included`() {
    // The rejection paths. A registry that stripped the prefix without checking it would resolve
    // `/../../etc/passwd` to whatever `substringAfterLast('/')` produced.
    val published = registry.publish(UPSTREAM, mp3)

    assertThat(registry.resolve("/etc/passwd")).isNull()
    assertThat(registry.resolve("/media/../${published.token}.mp3")).isNull()
    assertThat(registry.resolve("/../media/${published.token}.mp3")).isNull()
    assertThat(registry.resolve("/MEDIA/${published.token}.mp3")).isNull()
    assertThat(registry.resolve("${published.path}/extra")).isNull()
    assertThat(registry.resolve("${published.path}?x=1")).isNull()
    // ...and the control: the path it really published still resolves, so the six nulls above are
    // a registry that discriminates rather than one that resolves nothing at all.
    assertThat(registry.resolve(published.path)).isNotNull()
  }

  @Test
  fun `a revoked token resolves to nothing, and its neighbours still resolve`() {
    val kept = registry.publish("https://nav.example/rest/stream?id=1", mp3)
    val dropped = registry.publish("https://nav.example/rest/stream?id=2", mp3)

    registry.revoke(dropped.token)

    assertThat(registry.resolve(dropped.path)).isNull()
    assertThat(registry.resolve(kept.path)).isNotNull()
  }

  @Test
  fun `revokeAll empties the registry`() {
    val first = registry.publish("https://nav.example/1", mp3)
    val second = registry.publish("https://nav.example/2", mp3)

    registry.revokeAll()

    // The exact list, not `allMatch`: `allMatch` is vacuously true over an empty collection, and
    // this collection is built from the two publications above so a `map` that produced nothing
    // would be the same defect one layer up.
    assertThat(listOf(first, second).map { registry.resolve(it.path) }).containsExactly(null, null)
  }

  private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
    override fun nextBytes(target: ByteArray) {
      bytes.copyInto(target)
    }
  }

  private companion object {
    const val UPSTREAM = "https://nav.example/rest/stream?id=1&format=raw"
  }
}
