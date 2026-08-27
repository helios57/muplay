package app.muplay.database

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The renderer-direct default, on the tier that can gate it.
 *
 * **Why this class exists at all, when `CastSettingsStoreTest` already reads the same value off a
 * real DataStore on a real device.** The instrumented test is the honest end-to-end reading, and
 * it runs in the 45-minute emulator tier. The mutation it is protecting against --
 * `?: DEFAULT_ALLOW_RENDERER_DIRECT` becoming `?: true`, or the constant itself being flipped --
 * is the one change in this repository that silently converts a security decision into its
 * opposite: every route still resolves, every cast still starts, and a speaker is quietly handed a
 * URL carrying a non-expiring Subsonic auth token. A defect of that shape must not be reachable
 * only from the slow tier, and it must be reachable by `ci/mutation-probes.sh`, which runs JVM
 * suites alone.
 *
 * `CastSettings.readAllowRendererDirect` is `internal` and takes a plain
 * `androidx.datastore.preferences.core.Preferences` -- pure Kotlin, no file, no Android -- for
 * exactly that reason. This class is the payoff.
 */
class CastSettingsTest {

  @Test
  fun `the shipped default is off, and that is the security decision three other arguments rest on`() {
    // Written as a bare assertion on the constant rather than through the reader, deliberately:
    // the reader could be changed to ignore the constant, and the constant could be flipped while
    // the reader stayed correct. Both are checked, one per test, so neither hides the other.
    assertThat(CastSettings.DEFAULT_ALLOW_RENDERER_DIRECT).isFalse()
  }

  @Test
  fun `a store with nothing in it reads as off`() {
    // The state every install starts in, and the one the constant above is only a claim about
    // until something executes the `?:`.
    assertThat(CastSettings.readAllowRendererDirect(emptyPreferences())).isFalse()
  }

  @Test
  fun `a store holding false reads as off, which is not the same observation as an empty one`() {
    // A reader that answered `false` unconditionally passes the two tests above. This one and the
    // next are the pair that discriminate: the value on disk is what comes back, both ways.
    val stored = mutablePreferencesOf(CastSettings.ALLOW_RENDERER_DIRECT to false)

    assertThat(CastSettings.readAllowRendererDirect(stored)).isFalse()
  }

  @Test
  fun `a store holding true reads as on`() {
    val stored = mutablePreferencesOf(CastSettings.ALLOW_RENDERER_DIRECT to true)

    assertThat(CastSettings.readAllowRendererDirect(stored)).isTrue()
  }

  @Test
  fun `the reader ignores a value stored under any other name`() {
    // The default is not "whatever boolean happens to be in the file". Without this, a reader that
    // took the first boolean it found would pass every test above -- and would be turned on by an
    // unrelated preference this store gains later.
    val stored = mutablePreferencesOf(booleanPreferencesKey("some_other_switch") to true)

    assertThat(CastSettings.readAllowRendererDirect(stored)).isFalse()
  }

  @Test
  fun `the key is the one already written to disk on users' devices`() {
    // Spelled out as a literal rather than compared to itself. A rename moves reader and writer
    // together, so it round-trips perfectly and only loses the stored choice on upgrade -- and it
    // loses it in the *safe* direction (back to off), which is precisely why nothing else in this
    // repository would ever report it.
    assertThat(CastSettings.ALLOW_RENDERER_DIRECT.name).isEqualTo("cast_allow_renderer_direct")
  }
}
