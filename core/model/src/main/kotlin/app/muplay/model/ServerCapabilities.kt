package app.muplay.model

/**
 * The result of OpenSubsonic capability negotiation (see
 * [app.muplay.network.CapabilityNegotiator]): whether the server speaks OpenSubsonic at all, and
 * which extensions it supports.
 *
 * [extensions] maps each advertised extension name to the *list* of versions the server supports
 * for it, deliberately not a `Set<String>` or a `Boolean`-valued map. OpenSubsonic extensions are
 * themselves versioned — `songLyrics` v1 and v2 are genuinely different response shapes — so a
 * caller that only recorded "supported: yes" cannot tell which one it is about to call and risks
 * calling an endpoint shape the server does not actually implement. The version-specific
 * [supports] overload is the real question to ask before making a versioned call; the unversioned
 * [supports] overload is only ever a convenience pre-flight gate — see its own documentation for
 * the guarantee that keeps the two from ever disagreeing.
 *
 * @property isOpenSubsonic mirrors [app.muplay.model.ServerInfo.isOpenSubsonic] as observed during
 *   negotiation's `ping` step. `false` means [extensions] is always empty: negotiation never even
 *   attempts `getOpenSubsonicExtensions` against a server that did not first claim OpenSubsonic
 *   support via `ping`.
 * @property extensions extension name to the list of versions the server advertised for it, taken
 *   from the `getOpenSubsonicExtensions` response's `versions` array for that name. The
 *   OpenSubsonic schema permits that array to be empty for a given name — see [supports] for why
 *   that is not a corner case this type ignores.
 */
data class ServerCapabilities(
  val isOpenSubsonic: Boolean,
  val extensions: Map<String, List<Int>>,
) {

  /**
   * Whether [name] was advertised with at least one usable version.
   *
   * This can never disagree with the version-specific [supports] overload below: if the server
   * advertised [name] but with an empty `versions` array (permitted by the schema), this returns
   * `false` too — exactly the answer the version-specific overload would give for *any* version —
   * rather than reporting "yes" for a name no concrete version-specific call could actually
   * succeed against. A caller using this as a pre-flight gate before a version-specific call is
   * therefore never misled.
   */
  fun supports(name: String): Boolean = extensions[name]?.isNotEmpty() == true

  /**
   * Whether the server advertised [name] specifically at [version] — the question that actually
   * matters before calling a versioned OpenSubsonic endpoint. See the class documentation for why
   * the unversioned [supports] overload above is a convenience, never a substitute for this one.
   */
  fun supports(name: String, version: Int): Boolean = extensions[name]?.contains(version) == true
}
