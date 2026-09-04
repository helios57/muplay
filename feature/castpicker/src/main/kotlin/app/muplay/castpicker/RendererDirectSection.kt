package app.muplay.castpicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.muplay.database.CastSettings
import app.muplay.designsystem.theme.MuPlaySpacing
import app.muplay.settings.SettingsSection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The switch's own label. Short by design; the sentence that makes it a choice is below it. */
const val RENDERER_DIRECT_TITLE: String = "Let speakers stream from Navidrome directly"

/**
 * **The one string in this app whose job is to make a security decision informed.**
 *
 * A toggle whose consequence is *"hand a speaker a URL carrying a non-expiring auth token"* and
 * which says only "Allow direct streaming" is a toggle nobody can consent to. All three
 * consequences are named here because all three are things the user, and only the user, can weigh:
 *
 * 1. **The token.** A Subsonic stream URL carries `u`, `t` and `s` -- `md5(password + salt)` and
 *    the salt -- which is a password equivalent that does not expire, and speakers write the
 *    addresses they are handed into their own logs.
 * 2. **TLS.** The speaker, not this phone, has to trust the server's certificate. Spec section 6
 *    claims the proxy *defers* this question rather than eliminating it; this is where it comes
 *    back.
 * 3. **The connection.** The bytes travel over the speaker's link, which may be metered, and
 *    nothing this phone is set to applies to it.
 *
 * `RendererDirectCopyTest` asserts each of those by keyword, which is an unusual thing to test and
 * a deliberate one: "someone shortened it" is a real way for this to stop being a choice, and it
 * is a change that breaks nothing else.
 *
 * Note also what is **not** here: no example URL, and no token. This repository does not write a
 * credential-bearing stream URL down -- not in production strings, not in a fixture, not in a test
 * -- and `RendererDirectCopyTest` asserts that this string contains no URL at all.
 */
const val RENDERER_DIRECT_EXPLANATION: String =
  "Normally MuPlay streams to a speaker through this phone, and the speaker never sees your " +
    "server. Turn this on and MuPlay hands the speaker your Navidrome address instead, which " +
    "changes three things.\n\n" +
    "That address carries your Subsonic auth token. It does not expire, it is as good as your " +
    "password, and speakers write the addresses they are given into their own logs.\n\n" +
    "The speaker has to trust your server's TLS certificate by itself. This phone cannot vouch " +
    "for it, and a speaker that does not recognise the certificate will simply refuse to play.\n\n" +
    "The music travels over the speaker's own connection rather than this phone's, which may be " +
    "metered.\n\n" +
    "Leave this off unless a speaker cannot reach this phone. When that happens MuPlay tells you " +
    "so by name rather than quietly streaming from the internet instead."

/**
 * The renderer-direct switch, contributed into `:feature:settings`'s section slot.
 *
 * Task 7 wrote *"So it is a setting, default off, and when it is off the third outcome fires"* while
 * what existed was a `Boolean` constructor parameter hardcoded to `false`. The sentence was right
 * about the design and wrong about the tense: there was no persistence, no UI, and no way for a
 * user to reach it, while `CastRouter`'s failure message told them about a setting that did not
 * exist. This class is the half that was missing.
 *
 * **A `@Singleton` with an injected scope, not `rememberCoroutineScope()`.** A scope remembered by
 * the composable is cancelled when the composable leaves composition, so a user who taps the switch
 * and immediately navigates back can lose the write. That is harmless in one direction and not in
 * the other: losing a *turn it on* is a feature that did not switch on, and losing a *turn it off*
 * leaves a security decision in force that the user believes they revoked.
 */
@Singleton
class RendererDirectSection @Inject constructor(
  private val settings: CastSettings,
  @CastPickerScope private val scope: CoroutineScope,
) : SettingsSection {

  /**
   * Sparse, per [SettingsSection.order], so a later section can be placed either side of this one
   * without renumbering a module that has nothing to do with it.
   */
  override val order: Int = 200

  /**
   * [onNavigate] is ignored, and deliberately so: this section is a switch, not a way in. The
   * parameter exists for sections that open a screen of their own -- Plan 7's integrations row --
   * and a section with nowhere to go says so by not using it.
   */
  @Composable
  override fun Content(onNavigate: (NavKey) -> Unit) {
    // `initialValue = DEFAULT_ALLOW_RENDERER_DIRECT` and not `true`, obviously -- but stated
    // rather than assumed, because the first composition happens before DataStore has answered and
    // a screen that painted the switch **on** for one frame would be telling the user something
    // false about their own security posture.
    val allowed by settings.allowRendererDirect
      .collectAsStateWithLifecycle(initialValue = CastSettings.DEFAULT_ALLOW_RENDERER_DIRECT)

    RendererDirectSwitch(
      allowed = allowed,
      onAllowedChange = { chosen -> scope.launch { settings.setAllowRendererDirect(chosen) } },
    )
  }
}

/**
 * The stateless half, so the switch can be composed on a device with no DataStore and no Hilt graph
 * -- the same split `:feature:player`'s screens use.
 *
 * The whole row is `toggleable` rather than the [Switch] alone, and the `Switch` takes
 * `onCheckedChange = null` so it does not become a second, competing target. Material's own
 * guidance, and it matters here beyond ergonomics: the title and the switch are then **one**
 * semantics node, so a test that finds the title can assert what the control actually reads --
 * which is the assertion that a label wired to the wrong preference would fail.
 *
 * That one node is also the whole tap target, and **`Switch`'s own 48dp minimum does not extend to
 * it**: measured on a device, this row is 32dp tall the moment its vertical padding is removed, so
 * a `Switch` that meets the guidance sits inside a row that does not. The height is therefore
 * stated -- [MuPlaySpacing.minTouchTarget], outside `toggleable` so the ripple and the hit
 * rectangle are the tall one -- rather than left to arrive as the sum of a padding and a control's
 * intrinsic size, which is what it was before and would have gone under again on any typography
 * change. [RendererDirectSectionTest.theWholeRowIsBigEnoughToTapAndNotJustTheSwitch] measures it.
 */
@Composable
internal fun RendererDirectSwitch(
  allowed: Boolean,
  onAllowedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(MuPlaySpacing.sm),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = MuPlaySpacing.minTouchTarget)
        .toggleable(value = allowed, onValueChange = onAllowedChange, role = Role.Switch)
        .padding(vertical = MuPlaySpacing.xs),
      horizontalArrangement = Arrangement.spacedBy(MuPlaySpacing.lg),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = RENDERER_DIRECT_TITLE,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      Switch(checked = allowed, onCheckedChange = null)
    }

    // `onSurfaceVariant`, not `error`: this is not a warning about something that has gone wrong,
    // it is the description of a choice. Painting it red would make the ordinary reading of the
    // screen "something is broken here" and train the user to skip it.
    Text(
      text = RENDERER_DIRECT_EXPLANATION,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
