package app.muplay.cast.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * The rule that replaces a manifest-wide cleartext switch.
 *
 * MuPlay talks plain HTTP to renderers because renderers have no TLS, and it must never talk plain
 * HTTP to anything else. `android:usesCleartextTraffic` cannot express that -- it is host-blind --
 * and a `network_security_config.xml` cannot express it either, because that format takes domain
 * names and IP literals and has no way to say "a subnet". This does say it, in one place.
 *
 * Every address below is asserted **in both directions**: an `isLocal` that returned `true`
 * unconditionally fails the second half of every test, and one that returned `false`
 * unconditionally fails the first. That symmetry is the whole point -- a guard observed only
 * succeeding is not a guard.
 */
class LocalNetworkOnlyTest {

  private fun local(literal: String) = LocalNetworkOnly.isLocal(InetAddress.getByName(literal))

  @Test
  fun `rfc 1918 private ipv4 is local, and the addresses just outside each block are not`() {
    // The boundaries are the whole test. An implementation that checked only the first octet
    // passes "10.0.0.1" and "172.16.0.1" and fails here.
    assertThat(local("10.0.0.1")).isTrue
    assertThat(local("10.255.255.255")).isTrue
    assertThat(local("9.255.255.255")).isFalse
    assertThat(local("11.0.0.0")).isFalse

    assertThat(local("172.16.0.1")).isTrue
    assertThat(local("172.31.255.255")).isTrue
    assertThat(local("172.15.255.255")).isFalse
    assertThat(local("172.32.0.0")).isFalse

    assertThat(local("192.168.0.1")).isTrue
    assertThat(local("192.168.255.255")).isTrue
    assertThat(local("192.167.255.255")).isFalse
    assertThat(local("192.169.0.0")).isFalse
  }

  @Test
  fun `loopback is local, because that is where the in-process renderer lives`() {
    // Spec section 10's Tier 1 row puts the fake renderer on 127.0.0.1:0. If this guard forbade
    // loopback, the entire cast test suite would be untestable -- and, worse, a reader would
    // "fix" it by relaxing the guard instead of by reading this comment.
    assertThat(local("127.0.0.1")).isTrue
    assertThat(local("127.255.255.254")).isTrue
    assertThat(local("::1")).isTrue
  }

  @Test
  fun `link-local is local, because that is what a renderer with no dhcp lease has`() {
    assertThat(local("169.254.0.1")).isTrue
    assertThat(local("169.254.255.255")).isTrue
    assertThat(local("169.253.255.255")).isFalse
    assertThat(local("fe80::1")).isTrue
  }

  /**
   * RFC 6598 carrier-grade NAT, `100.64.0.0/10`.
   *
   * `InetAddress.isSiteLocalAddress()` returns **false** for this whole block, and Tailscale hands
   * out addresses from exactly it. Spec section 6's third routing situation is "Remote + VPN", so
   * leaving this out would make a named user requirement fail on the most common way people build
   * the network the spec describes -- and fail as a refusal to connect, which reads as "the
   * speaker is not there".
   */
  @Test
  fun `carrier-grade nat is local, and the addresses either side of the block are not`() {
    assertThat(local("100.64.0.0")).isTrue
    assertThat(local("100.100.100.100")).isTrue
    assertThat(local("100.127.255.255")).isTrue
    assertThat(local("100.63.255.255")).isFalse
    assertThat(local("100.128.0.0")).isFalse
  }

  /**
   * IPv6 unique local addresses, `fc00::/7`. `isSiteLocalAddress()` covers only the deprecated
   * `fec0::/10`, so this needs its own check, and its own boundary observations.
   */
  @Test
  fun `ipv6 unique local addresses are local`() {
    assertThat(local("fd00::1")).isTrue
    assertThat(local("fc00::1")).isTrue
    assertThat(local("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).isTrue
    assertThat(local("fe00::1")).isFalse
  }

  @Test
  fun `a public address is not local`() {
    // Four of them, from four different registries, because "not local" is the assertion the whole
    // guard exists to make and one example is not a test.
    assertThat(local("8.8.8.8")).isFalse
    assertThat(local("93.184.216.34")).isFalse
    assertThat(local("1.1.1.1")).isFalse
    assertThat(local("2001:4860:4860::8888")).isFalse
  }

  @Test
  fun `require throws for a public address and names both the host and the address`() {
    // The message matters: this exception is what a user sees behind "could not reach that
    // speaker", and an exception that says only "refused" sends the next debugger to the wrong
    // layer entirely.
    assertThatExceptionOfType(NonLocalAddressException::class.java)
      .isThrownBy { LocalNetworkOnly.require("evil.example.com", InetAddress.getByName("93.184.216.34")) }
      .withMessageContaining("evil.example.com")
      .withMessageContaining("93.184.216.34")
      .withMessageContaining("local network")
  }

  @Test
  fun `require returns quietly for a private address`() {
    // The other direction. Without it, a `require` that threw unconditionally passes the test
    // above and breaks every cast.
    LocalNetworkOnly.require("192.168.1.50", InetAddress.getByName("192.168.1.50"))
    LocalNetworkOnly.require("localhost", InetAddress.getByName("127.0.0.1"))
  }

  @Test
  fun `a connection from the local network is accepted, and comes back open`() {
    // The inbound direction, which had no guard at all: `isLocal` and `require` existed and
    // nothing called either of them on an `accept()`. Task 6 binds a `ServerSocket` that serves
    // Navidrome-authenticated audio, reachable by every device on the LAN -- coffee-shop Wi-Fi
    // included -- and by every other app on the phone.
    //
    // This half is a real socket pair on loopback rather than a fake, because "comes back open"
    // is a property of the socket the caller then reads a request head from.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
      Socket().use { client ->
        client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))

        val accepted = LocalNetworkOnly.acceptLocal(server)

        assertThat(accepted).isNotNull
        assertThat(accepted!!.isClosed).isFalse
        assertThat(accepted.inetAddress.isLoopbackAddress).isTrue
        accepted.close()
      }
    }
  }

  @Test
  fun `a connection from off the local network is refused and closed rather than served`() {
    // The discriminating half, and the one no real socket can produce here: a peer on 8.8.8.8
    // cannot be conjured on this host, so the `ServerSocket` and the connection it hands back are
    // hand-written (this project bans mock frameworks; `ServerSocket.accept` and
    // `Socket.getInetAddress` are both overridable, which is all a fake needs).
    val peer = FakeSocket(InetAddress.getByName("8.8.8.8"))

    val accepted = acceptFrom(peer)

    assertThat(accepted).isNull()
    assertThat(peer.closed)
      .describedAs("a refused connection left open is a descriptor a peer can hold as many of as it likes")
      .isTrue
  }

  @Test
  fun `a connection with no peer address at all is not local`() {
    // Fail-closed, and the direction a `?: true` gets backwards while every other test stays
    // green: a socket already closed by the time it is asked answers null here.
    val orphan = FakeSocket(null)

    assertThat(LocalNetworkOnly.isLocalPeer(orphan)).isFalse
    assertThat(acceptFrom(orphan)).isNull()
  }

  @Test
  fun `isLocalPeer reads the peer's address, in both directions`() {
    // Two observations, so neither a constant `true` nor a constant `false` survives -- the same
    // symmetry every assertion in this class is built on.
    assertThat(LocalNetworkOnly.isLocalPeer(FakeSocket(InetAddress.getByName("192.168.1.9")))).isTrue
    assertThat(LocalNetworkOnly.isLocalPeer(FakeSocket(InetAddress.getByName("93.184.216.34")))).isFalse
  }

  private fun acceptFrom(peer: Socket): Socket? =
    FakeServerSocket(peer).use { LocalNetworkOnly.acceptLocal(it) }

  /** A connection that reports the peer it was told to and remembers being closed. */
  private class FakeSocket(private val peer: InetAddress?) : Socket() {
    var closed = false
      private set

    override fun getInetAddress(): InetAddress? = peer

    override fun close() {
      closed = true
      super.close()
    }
  }

  /** An unbound `ServerSocket` whose `accept()` hands back one connection this test made. */
  private class FakeServerSocket(private val next: Socket) : ServerSocket() {
    override fun accept(): Socket = next
  }
}
