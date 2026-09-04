package app.muplay.designsystem.theme

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [bookVoiceScheme] is the audiobook half of "two voices, one chassis" made structural instead of
 * repeated — see `Color.kt`'s header for the palette it enforces, and this function's own KDoc for
 * the leak it closes.
 *
 * Testable here for the same reason [ThemeTest] is: a [androidx.compose.material3.ColorScheme] is a
 * plain JVM value, so this needs neither Robolectric nor an emulator.
 */
class BookVoiceTest {

  private val light = colorSchemeFor(darkTheme = false)
  private val dark = colorSchemeFor(darkTheme = true)

  @Test
  fun `the roles Material reaches for by default carry the audiobook voice`() {
    // Every one of these is a role a Material component picks up *without being told*:
    // `Button`'s container, `OutlinedButton`'s and `TextButton`'s content, `Switch`'s track and
    // thumb, `LinearProgressIndicator`'s bar. On a book screen they were all rendering in the
    // music voice, which is the exact opposite of what the palette says it does.
    val voice = bookVoiceScheme(light)

    assertThat(voice.primary).isEqualTo(light.tertiary)
    assertThat(voice.onPrimary).isEqualTo(light.onTertiary)
    assertThat(voice.primaryContainer).isEqualTo(light.tertiaryContainer)
    assertThat(voice.onPrimaryContainer).isEqualTo(light.onTertiaryContainer)
  }

  @Test
  fun `an elevated surface on a book screen is not tinted with the music voice`() {
    // `surfaceTint` is the quiet one: neither scheme sets it, so `lightColorScheme()` defaults it
    // to `primary`, and every elevated Card and Surface on a book screen was picking up a cold
    // tint that nothing in the source names.
    assertThat(bookVoiceScheme(light).surfaceTint).isEqualTo(light.tertiary)
    assertThat(bookVoiceScheme(dark).surfaceTint).isEqualTo(dark.tertiary)
  }

  @Test
  fun `the chassis is shared, so only the voice moves`() {
    // The palette's own claim: "surfaces, outlines and type are identical across both, so the two
    // instruments never read as two apps". A wrapper that recoloured the chassis would break the
    // thing it exists to express.
    val voice = bookVoiceScheme(dark)

    assertThat(voice.surface).isEqualTo(dark.surface)
    assertThat(voice.onSurface).isEqualTo(dark.onSurface)
    assertThat(voice.surfaceVariant).isEqualTo(dark.surfaceVariant)
    assertThat(voice.background).isEqualTo(dark.background)
    assertThat(voice.outline).isEqualTo(dark.outline)
    assertThat(voice.error).isEqualTo(dark.error)
  }

  @Test
  fun `the tertiary roles are left alone, so an already-warm call site does not move`() {
    // Sixteen call sites across the three book screens already name `tertiary` explicitly. If this
    // function remapped those too they would shift under it, and the fix would be a regression for
    // every place that had been getting it right by hand.
    val voice = bookVoiceScheme(light)

    assertThat(voice.tertiary).isEqualTo(light.tertiary)
    assertThat(voice.onTertiary).isEqualTo(light.onTertiary)
    assertThat(voice.tertiaryContainer).isEqualTo(light.tertiaryContainer)
  }

  @Test
  fun `the music voice is genuinely a different colour, so these assertions are not vacuous`() {
    // Without this, every assertion above would pass against `bookVoiceScheme = { it }` if the
    // palette ever collapsed to one accent. It is the premise the whole file rests on.
    assertThat(light.primary).isNotEqualTo(light.tertiary)
    assertThat(dark.primary).isNotEqualTo(dark.tertiary)
  }
}
