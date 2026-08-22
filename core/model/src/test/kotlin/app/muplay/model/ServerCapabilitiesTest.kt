package app.muplay.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ServerCapabilities.supports] is plain data-class logic with no Android or network dependency —
 * genuinely JVM-testable, so it is tested here directly rather than only indirectly through
 * `:core:network`'s `CapabilityNegotiatorTest`. Coverage is measured per module (see Task 7's
 * `jacocoTestCoverageVerification`): a `:core:network` test exercising this class contributes to
 * `:core:network`'s own execution data, never to `:core:model`'s, so without a test living in this
 * module, `:core:model`'s own coverage floor would be gated by branches nothing here ever runs —
 * exactly the "artificial gap" this project's coverage floors must not carry (compare
 * `SetupFailureReason.toMessage`, made `internal` and tested for the identical reason).
 *
 * Both overloads' null-safe chains (`extensions[name]?.isNotEmpty() == true` and
 * `extensions[name]?.contains(version) == true`) are each a single real branch — every test below
 * exercises both the "name not present at all" (null) and "name present" sides of it.
 */
class ServerCapabilitiesTest {

  @Test
  fun `supports name is false when the extension was never advertised`() {
    val capabilities = ServerCapabilities(isOpenSubsonic = true, extensions = emptyMap())

    assertThat(capabilities.supports("songLyrics")).isFalse()
  }

  @Test
  fun `supports name is false when the extension was advertised with no versions`() {
    val capabilities =
      ServerCapabilities(isOpenSubsonic = true, extensions = mapOf("songLyrics" to emptyList()))

    assertThat(capabilities.supports("songLyrics")).isFalse()
  }

  @Test
  fun `supports name is true when the extension was advertised with at least one version`() {
    val capabilities =
      ServerCapabilities(isOpenSubsonic = true, extensions = mapOf("songLyrics" to listOf(1, 2)))

    assertThat(capabilities.supports("songLyrics")).isTrue()
  }

  @Test
  fun `supports name and version is false when the extension was never advertised`() {
    val capabilities = ServerCapabilities(isOpenSubsonic = true, extensions = emptyMap())

    assertThat(capabilities.supports("songLyrics", version = 1)).isFalse()
  }

  @Test
  fun `supports name and version is false when the advertised versions do not include it`() {
    val capabilities =
      ServerCapabilities(isOpenSubsonic = true, extensions = mapOf("songLyrics" to listOf(1)))

    assertThat(capabilities.supports("songLyrics", version = 2)).isFalse()
  }

  @Test
  fun `supports name and version is true when the advertised versions include it`() {
    val capabilities =
      ServerCapabilities(isOpenSubsonic = true, extensions = mapOf("songLyrics" to listOf(1, 2)))

    assertThat(capabilities.supports("songLyrics", version = 2)).isTrue()
  }
}
