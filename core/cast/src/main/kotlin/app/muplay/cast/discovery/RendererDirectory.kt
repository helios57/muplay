package app.muplay.cast.discovery

import app.muplay.cast.net.LocalNetworkOnly
import app.muplay.model.RememberedRenderer
import app.muplay.model.RememberedRenderers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one discovery pass found, and what it looked for and could not find. */
data class DiscoveryResult(
  val devices: List<CastDevice>,
  /**
   * Remembered devices that answered neither the search nor a direct fetch.
   *
   * Surfaced rather than dropped: "Bedroom is not answering" is information a user can act on, and
   * an empty picker is not. Task 10 renders these greyed out with that wording.
   */
  val unreachable: List<RememberedRenderer>,
)

/**
 * Discovery, deduplication, ordering, and the fallback spec section 12 calls required.
 *
 * The fallback has **three layers**, and each exists because the one before it has a case it
 * cannot cover:
 *
 * 1. **Multicast M-SEARCH.** Fails entirely across a VPN tunnel -- an app cannot escape a VPN
 *    without `allowBypass()`, and multicast does not cross one regardless.
 * 2. **Re-fetch a remembered `LOCATION` directly.** Covers Sonos completely, because Sonos serves
 *    its description on port 1400 and that port has never moved. One HTTP GET, and the `UDN` in
 *    the response confirms it is still the same device rather than a new one on a recycled IP.
 * 3. **Unicast M-SEARCH to the remembered host.** Covers the generic DLNA renderer that binds an
 *    **ephemeral** port for its description server, so its `LOCATION` changes on every reboot and
 *    layer 2 fetches a dead port. The unicast reply carries the new `LOCATION`.
 *
 * Layer 3 is not redundant with layer 2 and layer 2 is not redundant with layer 3; each covers a
 * class of device the other misses, which is why both are here and why a plan reviewer should
 * expect to see both.
 *
 * @param destinations where a search is sent. Production passes
 *   `DatagramSsdpTransport::multicastDestinations`; the tests pass one loopback endpoint, which
 *   exercises layer 1's code over layer 3's transport.
 * @param http fetches a description document, or returns `null` if it cannot. A function rather
 *   than a [app.muplay.cast.http.CastHttpClient] so that a test can serve documents without a
 *   device; production passes [DescriptionFetcher.overHttp]. It is **blocking**, and is called on
 *   [Dispatchers.IO] here so that a caller on the main thread does not have to know that.
 */
class RendererDirectory(
  private val transport: SsdpTransport,
  private val destinations: () -> List<InetSocketAddress>,
  private val http: (URI) -> String?,
  private val remembered: RememberedRenderers,
  private val listenWindowMs: Long = DEFAULT_LISTEN_WINDOW_MS,
) {

  suspend fun discover(mxSeconds: Int? = SsdpSearch.DEFAULT_MX_SECONDS): DiscoveryResult {
    val announcements = destinations().flatMap { destination ->
      transport.search(destination, SEARCH_TARGETS, mxSeconds, listenWindowMs)
    }

    // Deduplicate on the UDN, keeping the FIRST announcement for each: one device answers once per
    // matching search target, so a Sonos answers twice with two `ST` values and one `LOCATION`.
    // `distinct()` over `SsdpResponse` itself would keep both, and the picker would show "Küche"
    // twice.
    val found = announcements
      .distinctBy { it.udn }
      // The `takeIf` is a trust check, not a tidy-up. Without it, identity comes from the
      // DESCRIPTION DOCUMENT while deduplication came from the ANNOUNCEMENT, so a device may
      // announce one UDN and describe another -- and since `remember()` below persists what
      // `found` says, an impostor announcing junk and describing `uuid:REAL-SONOS` writes
      // `{udn: REAL_SONOS, descriptionUrl: attacker}` into the store. That entry then survives the
      // attacker leaving the network and is preferred by `recover()` on every later open. A
      // transient impostor becomes a permanent one.
      //
      // Both other layers already do exactly this -- `recover()` compares against the remembered
      // UDN before returning, and `unicastSearch` ends in the same `takeIf` -- and their comments
      // give the same reason. This path was the one that did not.
      //
      // It does not fix M4: an impostor that copies the real UDN into both the announcement and
      // the description still passes here, because nothing in unauthenticated SSDP can tell the
      // two apart on a first encounter. What it fixes is the *persistence*, which is strictly
      // worse than M4 and was unmitigated.
      .mapNotNull { announcement -> describe(announcement.location)?.takeIf { it.udn == announcement.udn } }

    val seen = found.map { it.udn }.toSet()
    val stale = remembered.load().filterNot { it.udn in seen }
    val recovered = ArrayList<CastDevice>()
    val unreachable = ArrayList<RememberedRenderer>()

    stale.forEach { candidate ->
      val device = recover(candidate)
      if (device != null) recovered += device else unreachable += candidate
    }

    // Sorted here and nowhere else. Arrival order is a property of the network, and a picker whose
    // entries move between openings is one a user cannot build a habit with. Case-insensitive by
    // name, then by UDN so two identically-named speakers have a stable order rather than an
    // arbitrary one.
    val devices = (found + recovered).sortedWith(BY_NAME_THEN_UDN)
    val stillMissing = unreachable.sortedWith(compareBy({ it.friendlyName.lowercase() }, { it.udn }))

    // The devices that answered, **then** the ones that did not -- not the answering ones alone.
    // Writing only `devices` here would forget a speaker the moment one discovery pass failed to
    // reach it, which is precisely the run this store exists for: the next open would have no
    // fallback URL to try and nothing to name in the "not answering" list. The order matters
    // because the store is bounded ([RememberedRenderers.MAX_REMEMBERED]) and truncates from the
    // end, so a device that is on the air outranks one that is not.
    remembered.remember(devices.map { it.remembered() } + stillMissing)
    return DiscoveryResult(devices, stillMissing)
  }

  /** Layer 2, then layer 3. */
  private suspend fun recover(candidate: RememberedRenderer): CastDevice? {
    val stored = runCatching { URI(candidate.descriptionUrl) }.getOrNull() ?: return null
    // The UDN check is what makes this a recovery rather than a guess: a DHCP lease that moved to
    // a different device answers this URL perfectly well, and casting to it would send someone
    // else's speaker a track.
    describe(stored)?.let { if (it.udn == candidate.udn) return it }
    return unicastSearch(stored, candidate)
  }

  /**
   * Layer 3: ask the remembered **host** directly, on the SSDP port, and take the fresh `LOCATION`
   * out of its reply.
   *
   * Sent with no `MX`, per UPnP Device Architecture 1.1 section 1.3.3 -- there is one recipient,
   * so there is nothing to spread replies over.
   */
  private suspend fun unicastSearch(stored: URI, candidate: RememberedRenderer): CastDevice? {
    val host = stored.host ?: return null
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    // The stored URL came from a device on the network and has been round-tripped through disk
    // since. The local-network rule applies to a remembered address exactly as it applies to a
    // freshly announced one; without this, a poisoned store is a datagram to the internet.
    if (!LocalNetworkOnly.isLocal(address)) return null

    val fresh = transport
      .search(InetSocketAddress(address, SsdpSearch.PORT), SEARCH_TARGETS, null, listenWindowMs)
      .firstOrNull { it.udn == candidate.udn }
      ?: return null
    return describe(fresh.location)?.takeIf { it.udn == candidate.udn }
  }

  private suspend fun describe(location: URI): CastDevice? {
    // Per-device `runCatching`, deliberately, and not one around the whole loop: one dead or
    // malformed device on the network must not empty the picker of the working ones.
    val xml = withContext(Dispatchers.IO) { runCatching { http(location) }.getOrNull() } ?: return null
    val root = runCatching { DeviceDescription.parse(xml, location) }.getOrNull() ?: return null
    return CastDevice.from(root, location)
  }

  companion object {
    /**
     * Both targets, in this order. A generic renderer answers only the first; some Sonos firmware
     * answers the second more reliably than the first, and the second also identifies the device
     * as a Sonos before its description has been read.
     */
    val SEARCH_TARGETS: List<String> =
      listOf(SsdpSearch.TARGET_MEDIA_RENDERER, SsdpSearch.TARGET_SONOS_ZONE_PLAYER)

    /** `MX` is 2 s, so 3 s covers the slowest conformant reply plus the round trip. */
    const val DEFAULT_LISTEN_WINDOW_MS: Long = 3_000L

    private val BY_NAME_THEN_UDN: Comparator<CastDevice> =
      compareBy({ it.friendlyName.lowercase() }, { it.udn })
  }
}
