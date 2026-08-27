package app.muplay.cast.route

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The **fast path**, not the rule. See `CastRouter`'s documentation for why: spec section 6's own
 * VPN row is routinely not subnet-equal, and the proxy still works there.
 */
class SubnetMatchTest {

  private fun same(a: String, b: String, prefix: Int) =
    SubnetMatch.sameSubnet(InetAddress.getByName(a), InetAddress.getByName(b), prefix)

  @Test
  fun `two addresses in one 24 are the same subnet, and the neighbours are not`() {
    assertThat(same("192.168.1.20", "192.168.1.50", 24)).isTrue
    assertThat(same("192.168.1.20", "192.168.2.50", 24)).isFalse
    assertThat(same("192.168.1.20", "192.168.0.255", 24)).isFalse
  }

  @Test
  fun `the prefix length is used, and changing only it changes the answer`() {
    // The argument's effect, isolated. A `sameSubnet` that hardcoded /24 passes the test above and
    // fails here, and a home network on a /16 is ordinary.
    assertThat(same("192.168.1.20", "192.168.2.50", 16)).isTrue
    assertThat(same("192.168.1.20", "192.168.2.50", 24)).isFalse
    assertThat(same("10.0.1.20", "10.0.2.50", 8)).isTrue
    // /23 and not the plan's /22. THE PLAN'S EXPECTATION IS ARITHMETICALLY WRONG and this is where
    // it was caught: 10.0.0.0/22 spans 10.0.0.0-10.0.3.255, so 10.0.1.20 and 10.0.2.50 are both
    // inside it and `same(..., 22)` is `true`. Measured -- the test failed against a correct
    // implementation, which is the only way that kind of error surfaces. /23 is where these two
    // actually separate, and it is a non-byte boundary too, so nothing is lost.
    assertThat(same("10.0.1.20", "10.0.2.50", 23)).isFalse
  }

  @Test
  fun `a prefix that is not a whole number of bytes is handled`() {
    // /22 covers 10.0.0.0 - 10.0.3.255. The bit-level boundary is where a byte-wise
    // implementation quietly gives the wrong answer.
    assertThat(same("10.0.3.255", "10.0.0.1", 22)).isTrue
    assertThat(same("10.0.4.0", "10.0.0.1", 22)).isFalse
  }

  @Test
  fun `a prefix of zero matches everything and a prefix of 32 matches only itself`() {
    assertThat(same("1.2.3.4", "250.251.252.253", 0)).isTrue
    assertThat(same("1.2.3.4", "1.2.3.4", 32)).isTrue
    assertThat(same("1.2.3.4", "1.2.3.5", 32)).isFalse
  }

  @Test
  fun `a prefix longer than the address itself is exact equality, not an index out of bounds`() {
    // Not a hypothetical: `LinkProperties.prefixLength` is an `Int` this module never validates,
    // and Task 9 will hand it straight through. /33 on a four-byte address walks off the end of
    // both arrays in a naive loop. The `>` guard is the one arm of this function no other test
    // reaches -- /32 is not greater than 32.
    assertThat(same("1.2.3.4", "1.2.3.4", 33)).isTrue
    assertThat(same("1.2.3.4", "1.2.3.5", 33)).isFalse
    assertThat(same("fd00::1", "fd00::1", 129)).isTrue
    assertThat(same("fd00::1", "fd00::2", 129)).isFalse
  }

  @Test
  fun `addresses of different families are never the same subnet`() {
    assertThat(same("192.168.1.1", "fd00::1", 24)).isFalse
  }

  @Test
  fun `ipv6 prefixes work too`() {
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:1::20", 64)).isTrue
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:2::20", 64)).isFalse
    assertThat(same("fd00:0:0:1::10", "fd00:0:0:2::20", 48)).isTrue
  }

  @Test
  fun `spec section 6's own VPN row is not subnet-equal, which is why this is not the rule`() {
    // The measurement the whole of `CastRouter`'s design rests on, stated here as an arithmetic
    // fact rather than as prose. Spec section 6's third situation -- phone on a VPN into home,
    // speaker on the home LAN, "proxy over the tunnel" -- is FALSE under the sentence the same
    // section states as the rule, at every prefix a real deployment uses. WireGuard/OpenVPN `tun`
    // and Tailscale's CGNAT block are both shown, because both are how the row is actually built.
    assertThat(same("10.8.0.3", "192.168.1.50", 24)).isFalse
    assertThat(same("10.8.0.3", "192.168.1.50", 16)).isFalse
    assertThat(same("10.8.0.3", "192.168.1.50", 8)).isFalse
    assertThat(same("100.101.102.103", "192.168.1.50", 10)).isFalse
    // ...and the row the sentence gets right, so this is a discrimination and not a constant.
    assertThat(same("192.168.1.20", "192.168.1.50", 24)).isTrue
  }
}
