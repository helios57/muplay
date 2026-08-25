package app.muplay.cast.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Which of this machine's addresses routes to a given peer.
 *
 * The proxy (Task 6) has to hand a renderer a URL the renderer can actually fetch, and on a phone
 * "the local address" is not one thing. Getting it wrong produces a cast that appears to start and
 * plays nothing, with no error anywhere -- which is why this has a test at all rather than being
 * left as one obvious line.
 */
class LocalAddressTest {

  @Test
  fun `the route to loopback is a loopback address, and never the wildcard`() {
    val chosen = LocalAddress.towards(InetAddress.getLoopbackAddress())

    assertThat(chosen).isNotNull
    assertThat(chosen!!.isLoopbackAddress).isTrue
    // `0.0.0.0` is what an unbound socket reports, and it is the one answer that would look
    // plausible in a log and be useless in a URL. `takeUnless` exists for it.
    assertThat(chosen.isAnyLocalAddress).isFalse
  }

  @Test
  fun `the route to an address on a real interface is that interface's own address`() {
    // The second, discriminating observation: it is the one that fails for a `towards` that
    // returned loopback, or a fixed address, or the first interface it enumerated. Routing to an
    // address this machine itself holds selects that address as the source, so the expected value
    // is known exactly rather than merely "not loopback".
    //
    // Skipped, not faked, on a host with no non-loopback IPv4 interface at all -- a bare container
    // being the realistic case. The unconditional loopback assertion above still runs there.
    val ownAddress = NetworkInterface.getNetworkInterfaces().toList()
      .filter { it.isUp && !it.isLoopback }
      .flatMap { it.inetAddresses.toList() }
      .filterIsInstance<Inet4Address>()
      .firstOrNull { !it.isLinkLocalAddress }
    assumeTrue(ownAddress != null, "this host has no non-loopback IPv4 interface")

    assertThat(LocalAddress.towards(ownAddress!!)).isEqualTo(ownAddress)
  }
}
