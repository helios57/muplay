package app.muplay.model

/**
 * The `type` values this client sends to `getAlbumList2`. An enum rather than a raw string so a
 * typo is a compile error instead of a server-side failure at runtime — and so this file is
 * the complete list of what MuPlay actually asks for, rather than the much longer list the
 * protocol allows.
 *
 * The enum protects the *call sites*; it cannot protect the strings below, which are a protocol
 * contract with the server and which a typo inside this file would break silently. Both are
 * therefore asserted as literals on the wire (`BrowseEndpointsTest`) and accepted by a real
 * `deluan/navidrome:0.63.2` (`LiveNavidromeTest`) — a review found that corrupting [NEWEST] to
 * `"nEwEsT_typo"` left the entire build green, since nothing referenced it and its zero branches
 * put it out of reach of every coverage floor.
 *
 * Measured, not assumed: Navidrome answers an unimplemented `type` with `status: "failed"` and
 * error code **0**, message `"type 'nEwEsT_typo' not implemented"`. An earlier version of this
 * comment claimed code 10; that was wrong.
 */
enum class AlbumListType(val wireValue: String) {
  /** Every album, in a stable order — what a full reconcile pages through. */
  ALPHABETICAL_BY_NAME("alphabeticalByName"),

  /** Most recently added first — the browse screen's "recently added" ordering. */
  NEWEST("newest"),
}
