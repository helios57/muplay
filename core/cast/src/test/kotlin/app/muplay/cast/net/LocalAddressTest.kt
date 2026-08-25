package app.muplay.cast.net

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
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

  @Test
  fun `the address the kernel chose is the answer, whatever address that is`() {
    // The discriminating observation, made hermetically. The test above it can only make it when
    // this host has a non-loopback IPv4 interface, and on a bare container it skips -- at which
    // point the only surviving observation of `towards` was satisfied by
    // `{ InetAddress.getLoopbackAddress() }` and the module's floor stayed green. Both guards
    // degraded to nothing at the same moment, and nothing said so.
    //
    // The seam is a hand-written `DatagramSocket` (no mock framework; `connect` and
    // `getLocalAddress` are both overridable on JDK 21, measured). 192.0.2.0/24 is TEST-NET-1, so
    // no address here is one this machine could hold by accident.
    val chosen = InetAddress.getByName("192.0.2.7")

    val answer = LocalAddress.towards(InetAddress.getByName("198.51.100.9")) { FakeDatagramSocket(chosen) }

    assertThat(answer).isEqualTo(chosen)
  }

  @Test
  fun `the wildcard is never the answer, because it is the one that looks plausible in a log`() {
    // `0.0.0.0` is what an unbound socket reports. In the URL this feeds a renderer it is useless,
    // and it is the failure that produces a cast which appears to start and plays nothing.
    val answer = LocalAddress.towards(InetAddress.getByName("198.51.100.9")) {
      FakeDatagramSocket(InetAddress.getByName("0.0.0.0"))
    }

    assertThat(answer).isNull()
  }

  @Test
  fun `a kernel that refuses the route probe is a null answer, not an exception`() {
    // "No route to that peer" is a real answer the router (Task 7) turns into a named failure.
    // Before the seam, this arm was unreachable from a hermetic test and the class was gated on
    // LINE instead of BRANCH because of it.
    val answer = LocalAddress.towards(InetAddress.getByName("198.51.100.9")) {
      FakeDatagramSocket(refuseConnect = true)
    }

    assertThat(answer).isNull()
  }

  /**
   * A `DatagramSocket` that answers the route probe with what a test told it to.
   *
   * Hand-written, because this project bans mock frameworks -- and it needs nothing a framework
   * would give it: `connect(SocketAddress)` and `getLocalAddress()` are both overridable, and
   * `DatagramSocket(null)` binds nothing, so this touches no port and sends no packet.
   */
  private class FakeDatagramSocket(
    private val chosen: InetAddress? = null,
    private val refuseConnect: Boolean = false,
  ) : DatagramSocket(null as SocketAddress?) {

    override fun connect(addr: SocketAddress?) {
      if (refuseConnect) throw SocketException("network is unreachable")
    }

    override fun getLocalAddress(): InetAddress? = chosen
  }
}
