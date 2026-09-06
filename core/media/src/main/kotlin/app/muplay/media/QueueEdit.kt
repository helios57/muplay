package app.muplay.media

/**
 * What a queue edit actually did.
 *
 * Emitted by [QueueEditor] **after** the controller has accepted the change, and by nothing else.
 * That is the whole point of routing a confirmation through the editor rather than raising it at
 * the tap: "Added to queue" is a claim about the timeline, and a message the button posted on its
 * own would go on being true after the edit stopped happening. `enqueue(emptyList())` is the case
 * that makes it concrete -- it is an ordinary race a user can lose (an album whose songs the mirror
 * has not delivered yet), it changes nothing, and it says nothing.
 *
 * [trackCount] is carried rather than the sentence, because a sentence is the UI layer's and this
 * module has no strings in it. `:feature:player`'s `queueEditMessage` is where it becomes words.
 */
sealed interface QueueEdit {

  /** How many tracks the edit put on the queue. Always one or more. */
  val trackCount: Int

  /** Appended to the end of the queue. */
  data class Appended(override val trackCount: Int) : QueueEdit

  /** Inserted directly after whatever is playing. */
  data class InsertedNext(override val trackCount: Int) : QueueEdit
}
