package app.muplay.player

import androidx.lifecycle.ViewModel
import app.muplay.media.QueueEdit
import app.muplay.media.QueueEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The sentence one queue edit is worth.
 *
 * Four forms and not two, because "Added 12 tracks to the queue" is what a user needs to read after
 * queueing an album and "Added to the queue (12)" is what a program needs to write. The count is
 * only ever spoken when it is more than one: a parenthetical "(1)" beside every single-track add is
 * noise on the common case.
 *
 * A plain function over a [QueueEdit], with no Android type in its signature, so the fast tier
 * holds this decision to a floor -- the same split `PlaybackFailure.of` records for the same
 * reason.
 */
internal fun queueEditMessage(edit: QueueEdit): String = when (edit) {
  is QueueEdit.Appended ->
    if (edit.trackCount == 1) QUEUE_ADDED_LABEL else "Added ${edit.trackCount} tracks to the queue"

  is QueueEdit.InsertedNext ->
    if (edit.trackCount == 1) QUEUE_NEXT_LABEL else "${edit.trackCount} tracks playing next"
}

/**
 * Turns what the queue editor did into what the user is told.
 *
 * Holds no state and makes no decision -- [queueEditMessage] is the decision, and it is a pure
 * function with its own floor. This exists because `MuPlayApp` is a composable and a `@Singleton`
 * is not something a composable may reach for; a view model is this app's only door to one.
 *
 * The constructor parameter is a `Flow` rather than the editor, which is seam enough on its own:
 * `Flow` is already an interface, so a test hands it a `MutableSharedFlow` and needs no fake, no
 * mock framework (banned here) and no media session.
 */
@HiltViewModel
class QueueFeedbackViewModel(edits: Flow<QueueEdit>) : ViewModel() {

  @Inject constructor(editor: QueueEditor) : this(editor.edits)

  /**
   * One message per edit that reached the timeline, and nothing while the app is not looking:
   * `QueueEditor.edits` has no replay, so a confirmation missed while backgrounded stays missed.
   * That is correct -- a snackbar is about a tap that just happened.
   */
  val messages: Flow<String> = edits.map(::queueEditMessage)
}

/** Shown after appending one track. */
const val QUEUE_ADDED_LABEL: String = "Added to the queue"

/** Shown after inserting one track directly after what is playing. */
const val QUEUE_NEXT_LABEL: String = "Playing next"

/**
 * The snackbar's action: go and look at what you just changed.
 *
 * A confirmation with nowhere to go is a confirmation you have to trust. This is also the only way
 * a user discovers the queue screen exists without first opening the player.
 */
const val QUEUE_VIEW_LABEL: String = "View"
