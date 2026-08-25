package app.muplay.model.browse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which tree a given client gets.
 *
 * Four arguments, and each one is varied **alone**, with the other three held constant, at two or
 * more values -- rule 2. A test that fed one combination and asserted one answer would pass against
 * a function that ignored three of its inputs, which is exactly the shape of the defect this plan
 * was warned about by name.
 */
class BrowseSurfacesTest {

  @Test
  fun `each of the four arguments changes the answer on its own`() {
    // The baseline: this app's own phone UI.
    val baseline = BrowseSurfaces.of(
      packageName = OWN,
      ownPackageName = OWN,
      isCarController = false,
      hintSurface = null,
    )
    // Vary exactly one argument per row. Four rows, four different reasons, and the exact answers.
    val varied = listOf(
      // packageName alone
      BrowseSurfaces.of(ANDROID_AUTO, OWN, isCarController = false, hintSurface = null),
      // isCarController alone
      BrowseSurfaces.of(OWN, OWN, isCarController = true, hintSurface = null),
      // hintSurface alone
      BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
      // ownPackageName alone -- the same hint, now from a package that is not ours
      BrowseSurfaces.of(OWN, "com.example.other", isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
    )

    assertThat(baseline).isEqualTo(BrowseSurface.PHONE)
    assertThat(varied).containsExactly(
      BrowseSurface.CAR,
      BrowseSurface.CAR,
      BrowseSurface.WATCH,
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun `every known car and watch package maps to its surface`() {
    val cars = BrowseSurfaces.CAR_PACKAGES.toList().sorted()
    val watches = BrowseSurfaces.WATCH_PACKAGES.toList().sorted()

    // Mapped and compared as exact lists rather than `allMatch`, which is vacuously true on an
    // empty set -- and an empty set is exactly what a bad refactor of these constants produces.
    assertThat(cars.map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) })
      .isNotEmpty
      .allSatisfy { assertThat(it).isEqualTo(BrowseSurface.CAR) }
    assertThat(watches.map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) })
      .isNotEmpty
      .allSatisfy { assertThat(it).isEqualTo(BrowseSurface.WATCH) }

    // The lists themselves, pinned. These are Google's package names, and a silent edit to one of
    // them is a silent change to which tree a car gets.
    assertThat(cars).containsExactly(
      "com.android.car.carlauncher",
      "com.android.car.media",
      "com.google.android.apps.automotive.templates.host",
      "com.google.android.gms.car",
      "com.google.android.projection.gearhead",
    )
    assertThat(watches).containsExactly(
      "com.google.android.wearable.app",
      "com.google.android.wearable.media.sessions",
    )
  }

  @Test
  fun `the media3 predicate wins over every package and every hint`() {
    // Google owns the real answer to "is this a car". Our package list is a backstop for hosts
    // Media3 does not know yet, never an override of one it does.
    assertThat(
      listOf(
        BrowseSurfaces.of(OWN, OWN, isCarController = true, hintSurface = BrowseSurfaces.HINT_WATCH),
        BrowseSurfaces.of(WEAR_APP, OWN, isCarController = true, hintSurface = null),
        BrowseSurfaces.of("com.example.unknown", OWN, isCarController = true, hintSurface = null),
      ),
    ).containsExactly(BrowseSurface.CAR, BrowseSurface.CAR, BrowseSurface.CAR)
  }

  @Test
  fun `a hint is honoured from our own package and refused from any other`() {
    val fromUs = listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH)
      .map { BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = it) }
    val fromThem = listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH)
      .map { BrowseSurfaces.of("com.example.other", OWN, isCarController = false, hintSurface = it) }

    assertThat(fromUs).containsExactly(BrowseSurface.CAR, BrowseSurface.WATCH)
    assertThat(fromThem).containsExactly(BrowseSurface.PHONE, BrowseSurface.PHONE)
  }

  @Test
  fun `an unrecognised hint from our own package is the phone tree, not a crash`() {
    assertThat(
      listOf("", "  ", "automotive", "WATCH", "car ").map {
        BrowseSurfaces.of(OWN, OWN, isCarController = false, hintSurface = it)
      },
    ).containsExactly(
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      // Hints are exact, lower-case tokens. "WATCH" is not HINT_WATCH.
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
    )
  }

  @Test
  fun `package matching is exact, not a prefix and not case-insensitive`() {
    // Both of these have shipped in real apps: a `startsWith` check that a repackaged app can
    // satisfy, and an `equalsIgnoreCase` that treats a different package as the same one.
    assertThat(
      listOf(
        "com.google.android.projection.gearhead.evil",
        "evil.com.google.android.projection.gearhead",
        "COM.GOOGLE.ANDROID.PROJECTION.GEARHEAD",
        "com.google.android.projection.gearhea",
      ).map { BrowseSurfaces.of(it, OWN, isCarController = false, hintSurface = null) },
    ).containsExactly(
      BrowseSurface.PHONE, BrowseSurface.PHONE, BrowseSurface.PHONE, BrowseSurface.PHONE,
    )
  }

  @Test
  fun `the hint key is a namespaced constant, because it goes into someone else's bundle`() {
    // Connection hints are a shared Bundle. A key of "surface" would collide with anything else
    // that had the same idea, and the collision would be silent.
    assertThat(BrowseSurfaces.HINT_KEY).isEqualTo("app.muplay.browse.SURFACE")
    assertThat(listOf(BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH))
      .containsExactly("car", "watch")
  }

  @Test
  fun `our own package is compared exactly too, so a suffixed applicationId is a different app`() {
    // The `packageName == ownPackageName` guard is a *string* comparison, and the near-misses that
    // matter here are the ones a build produces on purpose: `applicationIdSuffix = ".debug"` makes
    // `app.muplay.debug` a real, installable, DIFFERENT app. A `startsWith` or an
    // `equalsIgnoreCase` on this side would hand it -- and `app.muplay.evil` with it -- the
    // self-declaration that is supposed to be ours alone. The brief's exactness test covers
    // CAR_PACKAGES; this covers the other comparison, which is the one the hint hangs off.
    assertThat(
      listOf(
        BrowseSurfaces.of("app.muplay.debug", OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
        BrowseSurfaces.of("app.muplay.evil", OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_CAR),
        BrowseSurfaces.of("app.muplay", "app.muplay.debug", isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
        BrowseSurfaces.of("APP.MUPLAY", OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
        // ...and the same four values with the two sides genuinely equal, so this test cannot pass
        // against a function that has stopped honouring the hint at all.
        BrowseSurfaces.of("app.muplay.debug", "app.muplay.debug", isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
      ),
    ).containsExactly(
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      BrowseSurface.PHONE,
      BrowseSurface.WATCH,
    )
  }

  @Test
  fun `the two package lists are disjoint, so the order they are consulted in decides nothing`() {
    // `of` consults CAR_PACKAGES first. That ordering is invisible today and must stay invisible:
    // a package added to both lists would silently be a car, and no assertion above would move.
    assertThat(BrowseSurfaces.CAR_PACKAGES).isNotEmpty
    assertThat(BrowseSurfaces.WATCH_PACKAGES).isNotEmpty
    assertThat(BrowseSurfaces.CAR_PACKAGES intersect BrowseSurfaces.WATCH_PACKAGES).isEmpty()
    // Nor may either list claim this app itself: `of` checks both before the hint, so our own
    // package appearing in one would make the hint -- the watch's only way to identify itself --
    // permanently unreachable.
    assertThat(BrowseSurfaces.CAR_PACKAGES + BrowseSurfaces.WATCH_PACKAGES).doesNotContain(OWN)
  }

  @Test
  fun `a known host keeps its own surface however it labels itself`() {
    // Precedence 2 over 3, at both packages and in the direction that would be wrong. A car host
    // that sent HINT_WATCH, or a watch host that sent HINT_CAR, is not ours to believe -- the hint
    // is a self-declaration, and neither of those packages is us.
    assertThat(
      listOf(
        BrowseSurfaces.of(ANDROID_AUTO, OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_WATCH),
        BrowseSurfaces.of(WEAR_APP, OWN, isCarController = false, hintSurface = BrowseSurfaces.HINT_CAR),
      ),
    ).containsExactly(BrowseSurface.CAR, BrowseSurface.WATCH)
  }

  @Test
  fun `a caller we do not recognise gets the phone tree, whatever it claims to be`() {
    // The security-relevant statement, and the reason this rule is safe to run on an untrusted
    // `packageName`: every unrecognised claim lands on PHONE, which is the tree an unrecognised
    // caller gets anyway. Lying gains a caller nothing -- CAR and WATCH are strict *reductions*
    // of what PHONE already offers (see `BrowseSurface`'s own `continueLimit`s), so the worst a
    // spoofed hint can do is show the liar less.
    val stranger = "com.example.stranger"
    val everyClaim = listOf(null, "", BrowseSurfaces.HINT_CAR, BrowseSurfaces.HINT_WATCH, "phone")
      .map { BrowseSurfaces.of(stranger, OWN, isCarController = false, hintSurface = it) }

    assertThat(everyClaim).isNotEmpty.allSatisfy { assertThat(it).isEqualTo(BrowseSurface.PHONE) }
    assertThat(BrowseSurface.PHONE.continueLimit)
      .isGreaterThan(BrowseSurface.CAR.continueLimit)
      .isGreaterThan(BrowseSurface.WATCH.continueLimit)
  }

  private companion object {
    const val OWN = "app.muplay"
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    const val WEAR_APP = "com.google.android.wearable.app"
  }
}
