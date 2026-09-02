package app.muplay.cast.route

import app.muplay.cast.proxy.PublishedArtwork
import app.muplay.cast.proxy.PublishedMedia

/** Why a renderer cannot be given anything to play. */
enum class UnroutableReason {
  /** This phone has no network route to the renderer at all. */
  NO_ROUTE_TO_RENDERER,

  /**
   * The renderer never fetched from the proxy, and renderer-direct is switched off.
   *
   * The important outcome. Without it the failure is: the tap is accepted, `Play` returns 200, the
   * UI says "Playing on Kitchen", and nothing comes out of the speaker, forever, with nothing
   * reported anywhere.
   */
  PROXY_UNREACHABLE_AND_DIRECT_DISABLED,
}

/**
 * The **artwork** leg of a proxied route: a cover image published on this phone, and the URL a
 * renderer is given for it.
 *
 * Its own type rather than two more fields on [CastRoute.Proxied], because the two values are one
 * fact -- a published cover and where it can be fetched -- and either alone is useless: the URL
 * cannot be re-derived from [media] without the phone's address, and [media] cannot be revoked
 * from [url] without the token.
 *
 * `null` on a route means *no cover will be sent*, which happens for an item with no artwork at
 * all and, deliberately, for every route that is not [CastRoute.Proxied]. See
 * [CastRoute.RendererDirect].
 */
data class ProxiedArtwork(val url: String, val media: PublishedArtwork)

/** Where a renderer is told to get the bytes. */
sealed interface CastRoute {

  /**
   * From the phone. The default, and what spec section 6's table wants in all three situations.
   *
   * [deviceName] and [proofRequired] are carried on the route rather than passed to
   * [CastRouter.confirm], because `confirm` is called from a `Player`'s load path (Task 8) with
   * the route and nothing else. Both are decisions [CastRouter.candidate] already made and neither
   * can be re-derived from [url]:
   *
   * - [deviceName] is what the `Unroutable` message has to name, and the plan's own test requires
   *   it (*"a reason enum with an empty detail would leave the picker saying 'something went
   *   wrong'"*).
   * - [proofRequired] is the [SubnetMatch] fast path's answer, taken at the moment the two
   *   addresses were in hand. `false` means "this renderer is on this phone's own subnet, do not
   *   wait for it to prove what is already obvious"; `true` means the route is proved, which is
   *   slower and never wrong.
   *
   * [proofRequired] carries **no default**, deliberately. A defaulted parameter compiles to a
   * second, synthetic constructor that only a caller omitting it can reach -- and no caller does,
   * so it measured as three permanently uncovered lines on a class whose floor is LINE. A default
   * of `true` would also be the safe-looking way to add a `Proxied` that quietly never gets proved
   * if the default were ever flipped. Every route says which it is.
   */
  data class Proxied(
    val url: String,
    val media: PublishedMedia,
    /**
     * The cover image, published on this phone alongside the track, or `null` when the item has
     * none.
     *
     * **This field is a security fix and not a feature.** Before it, `<upnp:albumArtURI>` carried
     * the Navidrome `getCoverArt` URL itself, complete with the Subsonic `u`, `t` and `s`
     * parameters -- a non-expiring password equivalent -- and it did so on the **default** route,
     * with `allowRendererDirect` false. UPnP AV has no authentication, so any device on the LAN
     * can read it back out of the renderer with `GetMediaInfo`, renderers log the URLs they are
     * given, and [app.muplay.cast.http.CastHttpClient] speaks plain HTTP. The 128-bit token design
     * that keeps the *stream* URL off the wire was simply never applied to the artwork.
     *
     * A second token rather than a second use of the track's: the two are fetched independently,
     * an artwork fetch must not count as this route's reachability proof (see
     * [app.muplay.cast.proxy.MediaProxyServer.awaitRequest], which waits on this route's own
     * token), and the cover has to be revocable on its own when a route falls back to
     * [RendererDirect].
     */
    val artwork: ProxiedArtwork?,
    val deviceName: String,
    val proofRequired: Boolean,
  ) : CastRoute

  /**
   * From Navidrome, directly.
   *
   * **Off by default**, and that is a security decision rather than a conservative one. It hands
   * the speaker a URL carrying the user's Subsonic auth token -- which does not expire, and which
   * speakers log -- requires the speaker to trust Navidrome's TLS chain, and streams over the
   * user's connection without saying so. Reasonable if chosen knowingly; not as a silent fallback.
   *
   * **Measured limit, recorded here because nothing downstream can see it.** A Subsonic stream URL
   * is `/rest/stream?id=...`: its path carries **no file extension**, and spec section 6 records
   * that Sonos infers the MIME type from the URL rather than from `Content-Type`. Task 3's
   * `FakeRenderer` -- strict in exactly the way a real Sonos is -- answers `714 Illegal MIME-type`
   * to a `SetAVTransportURI` carrying one, which `CastRouterTest` measures rather than argues. So
   * on a Sonos this branch fails, loudly, at `setUri`, and it is a fallback for generic DLNA
   * renderers that read `protocolInfo` rather than a fallback for everything. Not fixed here: a
   * URL Navidrome will answer *and* that ends in an extension is not something this module can
   * mint, and inventing one would mean re-proxying, which is the branch this one exists to avoid.
   *
   * ### No artwork travels on this route, ever
   *
   * A decision rather than an omission, and either half of it would be enough on its own:
   *
   * - A renderer reaches this branch precisely because it could **not** fetch from this phone, so
   *   a proxy artwork URL minted for it would 404 by construction -- a broken image on the speaker
   *   rather than no image.
   * - The alternative, which is what the code did before the artwork proxy existed, is to send
   *   Navidrome's own cover URL. That is the credential leak this whole mechanism closes. A user's
   *   opt-in to renderer-direct is an opt-in to *streaming* from Navidrome and the stream URL is
   *   what it buys; nothing in it asks for a second credentialed URL to be handed to a speaker for
   *   a picture.
   *
   * So this route carries a `CurrentURI` and a DIDL document with `<dc:title>`, `<upnp:artist>`,
   * `<upnp:album>` and `<res>`, and **no `<upnp:albumArtURI>` element at all** -- `DidlLite` omits
   * an absent optional field rather than rendering it empty, which is what a renderer needs.
   */
  data class RendererDirect(val url: String) : CastRoute

  /** Neither. Casting fails, with a reason a user can act on. */
  data class Unroutable(val reason: UnroutableReason, val detail: String) : CastRoute
}
