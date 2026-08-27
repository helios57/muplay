package app.muplay.cast.route

import java.net.InetAddress

/**
 * Whether two addresses share a network prefix.
 *
 * **A fast path in [CastRouter], not the routing rule.** Spec section 6 words the rule as "same
 * subnet as the speaker", and its own third situation -- the phone on a VPN, the speaker on the
 * home LAN -- is routinely *not* subnet-equal while the proxy works perfectly over the tunnel. So
 * a `true` here means "skip the reachability proof, this will obviously work"; a `false` means
 * nothing at all except "find out".
 *
 * Nothing here reads an SSID, a `ConnectivityManager` or a permission of any kind: it is two byte
 * arrays and a prefix length, which is why this module stays pure JVM and why the rule that
 * replaces the subnet comparison needs strictly *less* than the comparison did.
 */
object SubnetMatch {

  fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
    val left = a.address
    val right = b.address
    // Comparing a 4-byte and a 16-byte address bit by bit would read past the end of one of them.
    if (left.size != right.size) return false
    if (prefixLength <= 0) return true
    if (prefixLength > left.size * Byte.SIZE_BITS) return left.contentEquals(right)

    val wholeBytes = prefixLength / Byte.SIZE_BITS
    val remainingBits = prefixLength % Byte.SIZE_BITS
    repeat(wholeBytes) { index -> if (left[index] != right[index]) return false }
    if (remainingBits == 0) return true
    // The partial byte. A byte-wise-only implementation silently gives the wrong answer for every
    // prefix that is not a multiple of 8 -- /22 and /26 are ordinary on a real network.
    val mask = (0xFF shl (Byte.SIZE_BITS - remainingBits)) and 0xFF
    return (left[wholeBytes].toInt() and mask) == (right[wholeBytes].toInt() and mask)
  }
}
