package app.muplay.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.google.common.collect.ImmutableList

/**
 * The two transport buttons Media3 does not draw by itself: rewind and fast-forward.
 *
 * ### Why this file exists
 *
 * `DefaultMediaNotificationProvider` builds exactly three buttons -- previous, play/pause, next --
 * from the player's own commands, and everything else the player can do is simply not offered.
 * Measured on the emulator against a four-track queue mid-playback, `dumpsys notification` reported
 * the whole action list as
 *
 *     [0] "Seek to previous item"  [1] "Pause"  [2] "Seek to next item"
 *
 * on an app whose audiobook screen is built around a rewind, whose [MuPlayer] goes to real trouble
 * to make [Player.COMMAND_SEEK_BACK] and [Player.COMMAND_SEEK_FORWARD] work *on a live transcode*,
 * and which advertises both commands to every controller. The capability was there and no remote
 * surface -- notification, lock screen, car, watch -- had a way to reach it.
 *
 * ### Every button here declares [CommandButton.SLOT_OVERFLOW], and that is not decoration
 *
 * Read from `CommandButton.getCustomLayoutFromMediaButtonPreferences`'s bytecode in 1.11.0, because
 * this cost a device run to find. Media3 resolves media button preferences into a flat list in
 * three steps: the first button whose slots contain `SLOT_BACK` becomes element 0, the first
 * containing `SLOT_FORWARD` becomes element 1, and then *only* those remaining buttons whose slots
 * contain `SLOT_OVERFLOW` are appended. A button offered nothing but a secondary slot matches none
 * of the three and is dropped on the floor -- silently, with no warning anywhere.
 *
 * The first version of this file declared these two with `SLOT_BACK_SECONDARY` and
 * `SLOT_FORWARD_SECONDARY` alone, which reads exactly right and produced a notification with the
 * same three actions it had before the file existed. The secondary slots stay first in each list
 * because a surface that *has* one -- a car, a watch face with five positions -- should put rewind
 * beside previous rather than in an overflow menu; `SLOT_OVERFLOW` is the fallback that makes the
 * button exist at all on the surfaces that do not.
 *
 * ### Previous and next are deliberately *not* declared here
 *
 * Media3 already draws both, from `COMMAND_SEEK_TO_PREVIOUS`/`COMMAND_SEEK_TO_NEXT`, and declaring
 * them as preferences replaces those defaults with custom buttons. Measured: doing so published
 * them to the platform session as
 *
 *     custom actions=[Action:mName='Seek to previous item', Action:mName='Seek to next item']
 *
 * -- i.e. it moved the two most standard controls in media playback *out* of the standard
 * `ACTION_SKIP_TO_NEXT`/`ACTION_SKIP_TO_PREVIOUS` bits that every remote surface knows how to draw
 * and into an app-specific list each one has to opt into understanding. A paired watch renders the
 * standard bits; it is not obliged to render anyone's custom actions. So the rule this file follows
 * is: **declare only what Media3 does not already offer**, and let it keep ownership of the rest.
 *
 * Play/pause is omitted for the same reason and one stronger one -- Media3 places it centrally from
 * the player's `COMMAND_PLAY_PAUSE` and keeps its icon in sync with the playback state, which a
 * declared button would have to reimplement.
 *
 * ### Nothing here decides what is *available*
 *
 * A button whose player command the current item does not offer is disabled by Media3 and dropped,
 * which is why this list is a constant rather than a function of state. At the end of a queue "next"
 * disappears on its own -- observed, not assumed: the same emulator session reported two actions on
 * the last track and three in the middle. So this file states what the app *can* be asked to do, and
 * [MuPlayer.getAvailableCommands] stays the single place that decides what it can do *now*.
 *
 * ### The strings are Media3's own
 *
 * `androidx.media3.session.R.string.media3_controls_*` are the names Media3 gives these very
 * commands, already translated into every locale the library ships. Writing our own would be a
 * second English string for a button the library already names, and `MuPlaybackServiceTest` reads
 * these same resources rather than literals so the assertion survives a locale change.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `CommandButton` and its slot constants are
// `@UnstableApi`, an `androidx.annotation.RequiresOptIn` the Kotlin compiler cannot see -- `check`
// then fails at `lintDebug` with `UnsafeOptInUsageError`. See `MuPlayerFactory` for the argument.
@OptIn(UnstableApi::class)
object TransportButtons {

  /** The buttons this app adds to the three Media3 draws for itself, in slot-preference order. */
  fun preferences(context: Context): ImmutableList<CommandButton> =
    ImmutableList.of(
      button(
        context = context,
        command = Player.COMMAND_SEEK_BACK,
        icon = CommandButton.ICON_SKIP_BACK,
        name = androidx.media3.session.R.string.media3_controls_seek_back_description,
      ).setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
      button(
        context = context,
        command = Player.COMMAND_SEEK_FORWARD,
        icon = CommandButton.ICON_SKIP_FORWARD,
        name = androidx.media3.session.R.string.media3_controls_seek_forward_description,
      ).setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW).build(),
    )

  /**
   * Everything about a button except its slots, which each caller states literally.
   *
   * The slots are **not** a parameter here, and that is lint's requirement rather than a style
   * choice: `setSlots` is annotated `@IntDef`, and passing an `IntArray` through a helper --
   * `.setSlots(*slots)` -- loses the annotation, so `lintDebug` fails the build with
   *
   *     Error: Must be one of: CommandButton.SLOT_CENTRAL, CommandButton.SLOT_BACK, ...
   *     [WrongConstant]      .setSlots(*slots)
   *
   * Kotlin compiles it clean and every test passes; only lint sees it. Same shape as `@UnstableApi`
   * above, from the other direction -- there the compiler cannot see an annotation lint enforces,
   * here the spread erases one lint enforces.
   */
  private fun button(
    context: Context,
    command: Int,
    icon: Int,
    name: Int,
  ): CommandButton.Builder =
    CommandButton.Builder(icon)
      .setPlayerCommand(command)
      .setDisplayName(context.getString(name))
}
