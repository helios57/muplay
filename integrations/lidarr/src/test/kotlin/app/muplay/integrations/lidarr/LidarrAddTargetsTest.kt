package app.muplay.integrations.lidarr

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Where an added album goes, decided as a pure function.
 *
 * Separate from the HTTP client on purpose: the whole cascade — root-folder defaults, the
 * greater-than-zero requirement, the fallback to the first profile, the give-up case — is real
 * logic with real branches, and it is Tier-1 enforceable at 100% branch coverage with no server
 * in sight. Exactly the argument `StreamRetryPolicy` and `StreamFormat.forSuffix` make in Plan 3.
 */
class LidarrAddTargetsTest {

  private fun folder(
    path: String = "/music",
    quality: Int = 2,
    metadata: Int = 3,
    monitor: String = "all",
    newItems: String = "none",
    accessible: Boolean = true,
  ) = LidarrRootFolder(
    id = 1, name = "Music", path = path, accessible = accessible, freeSpaceBytes = null,
    defaultQualityProfileId = quality, defaultMetadataProfileId = metadata,
    defaultMonitorOption = monitor, defaultNewItemMonitorOption = newItems,
  )

  private val profiles = listOf(LidarrProfile(9, "Any"), LidarrProfile(10, "Lossless"))

  @Test
  fun `the root folder's own defaults win when they are usable`() {
    val targets = LidarrAddTargets.resolve(folder(), profiles, profiles)

    assertThat(targets).isNotNull
    assertThat(targets!!.rootFolderPath).isEqualTo("/music")
    assertThat(targets.qualityProfileId).isEqualTo(2)
    assertThat(targets.metadataProfileId).isEqualTo(3)
    assertThat(targets.monitorOption).isEqualTo("all")
    assertThat(targets.newItemMonitorOption).isEqualTo("none")
  }

  @Test
  fun `every field comes from the folder it was given, not from a constant`() {
    // The second observation of the whole cascade.
    val targets = LidarrAddTargets.resolve(
      folder(path = "/archive", quality = 20, metadata = 30, monitor = "future", newItems = "all"),
      profiles,
      profiles,
    )!!

    assertThat(targets.rootFolderPath).isEqualTo("/archive")
    assertThat(targets.qualityProfileId).isEqualTo(20)
    assertThat(targets.metadataProfileId).isEqualTo(30)
    assertThat(targets.monitorOption).isEqualTo("future")
    assertThat(targets.newItemMonitorOption).isEqualTo("all")
  }

  /**
   * `ValidId` on the controller requires a profile id **greater than zero**, and a root folder
   * created through the API rather than the UI can carry zeros. Falling back to the first profile
   * the server reports is what turns a 400 nobody can act on into a working add.
   */
  @Test
  fun `a zero default falls back to the first profile the server reports`() {
    val targets = LidarrAddTargets.resolve(folder(quality = 0, metadata = 0), profiles, profiles)!!

    assertThat(targets.qualityProfileId).isEqualTo(9)
    assertThat(targets.metadataProfileId).isEqualTo(9)
  }

  /**
   * The fallback is the **first** profile, not any profile, and it is read from the list this call
   * was handed rather than from the other one.
   *
   * Two observations at once: a `resolve` that took `last()` would pass the test above (both lists
   * are the same there, and `9` happens to be first), and one that fell back to
   * `metadataProfiles` for a missing quality id would too.
   */
  @Test
  fun `the fallback is the first profile of the matching list`() {
    val quality = listOf(LidarrProfile(41, "Q-first"), LidarrProfile(42, "Q-second"))
    val metadata = listOf(LidarrProfile(51, "M-first"), LidarrProfile(52, "M-second"))

    val targets = LidarrAddTargets.resolve(folder(quality = 0, metadata = 0), quality, metadata)!!

    assertThat(targets.qualityProfileId).isEqualTo(41)
    assertThat(targets.metadataProfileId).isEqualTo(51)
  }

  @Test
  fun `each profile falls back independently of the other`() {
    // Without this, a `resolve` that fell back for both whenever either was zero would pass the
    // test above.
    assertThat(LidarrAddTargets.resolve(folder(quality = 0, metadata = 3), profiles, profiles)!!)
      .satisfies({ assertThat(it.qualityProfileId).isEqualTo(9) })
      .satisfies({ assertThat(it.metadataProfileId).isEqualTo(3) })
    assertThat(LidarrAddTargets.resolve(folder(quality = 2, metadata = 0), profiles, profiles)!!)
      .satisfies({ assertThat(it.qualityProfileId).isEqualTo(2) })
      .satisfies({ assertThat(it.metadataProfileId).isEqualTo(9) })
  }

  @Test
  fun `there is no answer when a needed profile is zero and no profile exists to fall back to`() {
    // Null, not a fabricated id 1. An add against a profile id that does not exist fails with a
    // validation message about profiles, which is a worse experience than being told up front.
    assertThat(LidarrAddTargets.resolve(folder(quality = 0), emptyList(), profiles)).isNull()
    assertThat(LidarrAddTargets.resolve(folder(metadata = 0), profiles, emptyList())).isNull()
  }

  /**
   * An empty profile list is only fatal for a profile the folder does not already name. Without
   * this, a `resolve` that gave up whenever *either* list was empty would pass the test above
   * while refusing every add on a server whose root folder carries perfectly good defaults.
   */
  @Test
  fun `an empty profile list is harmless when the folder's own default is usable`() {
    val targets = LidarrAddTargets.resolve(folder(), emptyList(), emptyList())!!

    assertThat(targets.qualityProfileId).isEqualTo(2)
    assertThat(targets.metadataProfileId).isEqualTo(3)
  }

  @Test
  fun `an inaccessible root folder has no answer`() {
    // Offering it would produce an add that fails on a path validation the user cannot interpret.
    // Measured on 3.1.0.4875-ls40, that validation reads "Folder '/music' is not writable by user
    // 'abc'" -- a message about UNIX ownership, shown to someone who was choosing an album.
    assertThat(LidarrAddTargets.resolve(folder(accessible = false), profiles, profiles)).isNull()
  }

  @Test
  fun `a root folder with a blank path has no answer`() {
    assertThat(LidarrAddTargets.resolve(folder(path = "  "), profiles, profiles)).isNull()
    assertThat(LidarrAddTargets.resolve(folder(path = ""), profiles, profiles)).isNull()
  }

  @Test
  fun `a blank monitor default becomes all rather than an empty string on the wire`() {
    // `MonitorTypes` has no empty member; sending "" would be a 400. "all" is Lidarr's own UI
    // default (frontend/src/Utilities/Artist/monitorOptions.js lists it first).
    val targets = LidarrAddTargets.resolve(folder(monitor = "", newItems = ""), profiles, profiles)!!

    assertThat(targets.monitorOption).isEqualTo("all")
    assertThat(targets.newItemMonitorOption).isEqualTo("all")
  }

  /**
   * The two monitor defaults fall back independently, and the substitution is per-field rather
   * than per-folder. A `resolve` that replaced both whenever either was blank passes the test
   * above and quietly discards a deliberate `newItemMonitorOption` of `none`.
   */
  @Test
  fun `each monitor default falls back independently of the other`() {
    assertThat(LidarrAddTargets.resolve(folder(monitor = "", newItems = "none"), profiles, profiles)!!)
      .satisfies({ assertThat(it.monitorOption).isEqualTo("all") })
      .satisfies({ assertThat(it.newItemMonitorOption).isEqualTo("none") })
    assertThat(LidarrAddTargets.resolve(folder(monitor = "future", newItems = " "), profiles, profiles)!!)
      .satisfies({ assertThat(it.monitorOption).isEqualTo("future") })
      .satisfies({ assertThat(it.newItemMonitorOption).isEqualTo("all") })
  }
}
