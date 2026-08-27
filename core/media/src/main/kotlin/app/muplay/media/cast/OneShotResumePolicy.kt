package app.muplay.media.cast

import app.muplay.media.ResumePolicy
import app.muplay.media.ResumeTarget
import java.util.concurrent.atomic.AtomicReference

/**
 * **The handover's position, carried through the seam rather than around it.**
 *
 * [app.muplay.media.MuPlayer] overrides all six `setMediaItem(s)` overloads to discard the caller's
 * position and ask a [ResumePolicy] instead, so that *"no code path can set a wrong position"*.
 * That is what makes a book impossible to get wrong -- and it is also what makes handover
 * impossible by the obvious route: `remote.setMediaItems(items, index, positionMs)` has its
 * position thrown away, so casting mid-song would restart the track. Every time, and it would look
 * like a Media3 quirk rather than like the seam working as designed.
 *
 * The wrong fix is an unwrapped player for handovers, which would create the one code path that can
 * set a position and leave it lying around for the next plan to find. The right fix is to **feed
 * the seam**: write the outgoing position to `media_progress`, arm a one-shot target here, then
 * call `setMediaItems` as usual. Handover and resume become one mechanism, and when the audiobook
 * plan swaps in a book-aware [delegate] nothing here changes.
 *
 * A **decorator**, deliberately: it wraps whatever policy is bound rather than replacing it, and it
 * has no signature of its own. If the audiobook plan widens [ResumePolicy.resolve], widen this
 * identically -- do not fork the interface.
 *
 * ### Thread safety
 *
 * `resolve` is called on the player's application thread; `armFor` is called from the handover,
 * which runs there too. The [AtomicReference] is not for those two: it is for the fact that the
 * *arming* and the *consuming* player are different objects during a handover, and a session that
 * failed on a background thread can arm through `handleSessionEnded` before the main thread has
 * finished the switch. `getAndSet` makes "the target is spent" one operation rather than a read and
 * a write with a window between them.
 */
class OneShotResumePolicy(private val delegate: ResumePolicy) : ResumePolicy {

  private data class Armed(val mediaId: String, val target: ResumeTarget)

  private val armed = AtomicReference<Armed?>(null)

  /** Arms one target for [mediaId], consumed by the next [resolve] that sees that id. */
  fun armFor(mediaId: String, target: ResumeTarget) {
    armed.set(Armed(mediaId, target))
  }

  fun disarm() {
    armed.set(null)
  }

  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget {
    // `getAndSet(null)` whatever happens next: a target that survived one resolve would make the
    // next shuffle, the next album and the next book all start where the last handover was.
    val pending = armed.getAndSet(null)
      ?: return delegate.resolve(mediaIds, requestedIndex)

    // The **media id** is the identity; the index is not. A handover can re-fetch an album or
    // regenerate a shuffle, so the armed index may name a different track in the new queue.
    val index = mediaIds.indexOf(pending.mediaId)
    // Not in the new queue at all: discard rather than apply. Starting an unrelated track partway
    // in is the silent-wrong-answer class -- the exact failure this architecture exists to prevent
    // for books.
    if (index < 0) return delegate.resolve(mediaIds, requestedIndex)

    return ResumeTarget(startIndex = index, startPositionMs = pending.target.startPositionMs)
  }
}
