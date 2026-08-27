package app.muplay.cast.control

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RendererCapabilitiesTest {

  private fun scpd(seekModes: List<String>, actions: List<String>) = """
    <?xml version="1.0"?>
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>
        ${actions.joinToString("") { "<action><name>$it</name></action>" }}
      </actionList>
      <serviceStateTable>
        <stateVariable sendEvents="no">
          <name>A_ARG_TYPE_SeekMode</name>
          <dataType>string</dataType>
          <allowedValueList>
            ${seekModes.joinToString("") { "<allowedValue>$it</allowedValue>" }}
          </allowedValueList>
        </stateVariable>
      </serviceStateTable>
    </scpd>
  """.trimIndent()

  @Test
  fun `the declared seek modes are read, in the order the device declared them`() {
    // Order is a property: `preferredSeekMode` prefers REL_TIME when offered, and falls back to
    // the device's own first choice otherwise, which is only meaningful if the order survives.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR", "REL_TIME", "X_DLNA_REL_BYTE"), listOf("Play")))
        .seekModes,
    ).containsExactly("TRACK_NR", "REL_TIME", "X_DLNA_REL_BYTE")
  }

  @Test
  fun `rel time is preferred when the device offers it`() {
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR", "REL_TIME"), listOf("Play"))).preferredSeekMode,
    ).isEqualTo(RendererCapabilities.REL_TIME)
  }

  @Test
  fun `abs time is used when rel time is not offered`() {
    // The second observation. Without it, `preferredSeekMode` hardcoded to REL_TIME passes the
    // test above and produces a 710 on every seek against a real ABS_TIME-only renderer.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("ABS_TIME", "TRACK_NR"), listOf("Play"))).preferredSeekMode,
    ).isEqualTo(RendererCapabilities.ABS_TIME)
  }

  @Test
  fun `a device offering neither time mode reports that it cannot seek`() {
    // Null, not a default. Task 8 turns this into "no seek bar", which is the honest UI for a
    // device that cannot seek -- rather than a bar that produces a 710 on every drag.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("TRACK_NR"), listOf("Play"))).preferredSeekMode,
    ).isNull()
  }

  @Test
  fun `SetNextAVTransportURI is detected from the action list, both ways`() {
    // Two observations of one boolean. It gates gapless-ish queueing in Task 8, and a hardcoded
    // `true` produces a 401 on every track transition against a device that lacks it.
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("REL_TIME"), listOf("Play", "SetNextAVTransportURI")))
        .supportsSetNextUri,
    ).isTrue
    assertThat(
      RendererCapabilities.fromScpd(scpd(listOf("REL_TIME"), listOf("Play", "Stop"))).supportsSetNextUri,
    ).isFalse
  }

  @Test
  fun `an unreadable scpd falls back to the conservative default rather than throwing`() {
    // A device whose SCPD 404s is still castable; it just cannot be asked what it supports. The
    // default assumes REL_TIME (what Sonos and most renderers accept) and NO SetNextAVTransportURI
    // -- optimistic where being wrong costs a failed seek, pessimistic where being wrong costs a
    // failed track transition.
    assertThat(RendererCapabilities.fromScpd("<html>404</html>")).isEqualTo(RendererCapabilities.DEFAULT)
    assertThat(RendererCapabilities.DEFAULT.seekModes).containsExactly(RendererCapabilities.REL_TIME)
    assertThat(RendererCapabilities.DEFAULT.supportsSetNextUri).isFalse
  }

  @Test
  fun `a seek mode list that is present but empty is the default too, not an unseekable device`() {
    // The other arm of the same guard, and a different fact from the one above: the element is
    // there and says nothing. Reading it as "no seek modes" would turn a device that merely
    // declared badly into one this app refuses to draw a seek bar for.
    val declared = RendererCapabilities.fromScpd(scpd(emptyList(), listOf("Play")))

    assertThat(declared).isEqualTo(RendererCapabilities.DEFAULT)
    assertThat(declared.preferredSeekMode).isEqualTo(RendererCapabilities.REL_TIME)
  }
}
