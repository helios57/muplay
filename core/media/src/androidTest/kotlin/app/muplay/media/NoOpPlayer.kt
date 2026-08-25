package app.muplay.media

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi

/**
 * A `Player` that plays nothing and holds nothing.
 *
 * Hand-written, not a mock: this project bans mock frameworks, and a mock would in any case add
 * nothing here -- the tests that use it are about the **write** and about the two `?: return`
 * branches, and never touch playback. `SimpleBasePlayer` supplies every `Player` method from one
 * `State`, so an empty state is a complete, correct, inert implementation in three lines.
 *
 * [ProgressWriter] handles it correctly by construction: `currentMediaItem` is `null` for an empty
 * playlist, so `captureCurrent` and `flushBlocking` return early and only explicit `write(...)`
 * calls reach the database. `ProgressWriterTest` asserts that early return rather than assuming it
 * -- an inert player that quietly wrote a row keyed on an empty id would be worse than one that
 * threw.
 */
// `androidx.annotation.OptIn`, not `kotlin.OptIn`: `SimpleBasePlayer` is `@UnstableApi`, which the
// Kotlin compiler cannot see -- see `MuPlayerFactory` for the full argument.
@OptIn(UnstableApi::class)
class NoOpPlayer(looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {
  override fun getState(): State =
    State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
}
