package app.muplay.integrations.lidarr

/**
 * Reads a committed Lidarr fixture from this module's own test resources.
 *
 * Deliberately **not** in `:core:testing`. Fixtures for an optional integration belong to the
 * module that would be deleted with it — the severability rule in this plan's header is a rule
 * about test data too, and `:core:testing` is a module every one of Plans 1-6 depends on.
 *
 * `checkNotNull` rather than a nullable return, because a missing fixture must be a loud failure
 * naming the fixture: a test whose expected body silently became `""` still sends a request, still
 * records it, and would leave every request-shape assertion in `LidarrAuthTest` green while
 * asserting nothing about a response.
 */
internal fun readFixture(path: String): String =
  checkNotNull(FixtureAnchor.javaClass.getResourceAsStream("/fixtures/$path")) {
    "missing fixture: /fixtures/$path"
  }.use { it.readBytes().decodeToString() }

/**
 * Anchors [readFixture]'s resource lookup to this module's own test classpath.
 *
 * `Class.getResourceAsStream` with a leading `/`, rather than `classLoader.getResourceAsStream`:
 * the class loader form returns a platform-nullable `ClassLoader?` that Kotlin will not let a call
 * be made on, and the two `!!`s needed to silence it would be two more ways for a missing fixture
 * to fail as an NPE rather than as the named `checkNotNull` above.
 */
private object FixtureAnchor
