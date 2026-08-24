package app.muplay.model

/**
 * The `type` values this client sends to `getAlbumList2`. An enum rather than a raw string so a
 * typo is a compile error instead of a Subsonic error code 10 at runtime — and so this file is
 * the complete list of what MuPlay actually asks for, rather than the much longer list the
 * protocol allows.
 */
enum class AlbumListType(val wireValue: String) {
  /** Every album, in a stable order — what a full reconcile pages through. */
  ALPHABETICAL_BY_NAME("alphabeticalByName"),

  /** Most recently added first — the browse screen's "recently added" ordering. */
  NEWEST("newest"),
}
