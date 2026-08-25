package app.muplay.media

/**
 * Where playback should actually start: which item, and how far into it.
 *
 * Two numbers and no third: there is deliberately nowhere here to carry a caller's *requested*
 * position, which is what [ResumePolicy] exists to keep out of the player.
 */
data class ResumeTarget(val startIndex: Int, val startPositionMs: Long)

/**
 * Decides where a queue starts. The **only** thing in this application permitted to choose a
 * playback position.
 *
 * [resolve] is deliberately never given the caller's requested position. Spec §3's seam exists
 * because a single global "now playing position" that the next thing played overwrites is why
 * every other player loses an audiobook's place; taking the position out of the signature means no
 * implementation can accidentally trust one.
 *
 * The requested **index** is passed, because an index is queue membership rather than progress —
 * "play track 3 of this album" is a legitimate request. The index belongs to the CALLER and a
 * policy must not override it: "play this book" and "play chapter 1 from the top" both arrive as
 * index 0, so overriding would make tapping chapter 1 jump to chapter 14. The audiobook plan
 * resumes at chapter 14 by choosing the index before it calls `setMediaItems`. This policy chooses
 * only the position.
 *
 * **Implementations must answer without blocking, and must not throw.** `MuPlayer` calls this from
 * `setMediaItems`, which runs on the player's application thread: a Room query there would jank the
 * UI, and an exception would take playback down. The intended mechanism for the audiobook plan is
 * an in-memory snapshot of `media_progress` kept current by a Flow collector, not a blocking read.
 * `mediaIds` may be empty — `setMediaItems(emptyList())` is how a queue is cleared — and
 * `requestedIndex` is the caller's, not yet validated against the list, so an implementation that
 * indexes into `mediaIds` must guard rather than assume.
 *
 * A `fun interface` on purpose: the audiobook plan replaces this binding with a policy that reads a
 * snapshot, and that wants to be a lambda closing over the snapshot rather than a class. It stays a
 * `fun interface` only while it has exactly one abstract member, which `ResumePolicyTest` asserts.
 */
fun interface ResumePolicy {
  fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget
}

/**
 * Plan 3's policy: start the item the caller chose, from the beginning.
 *
 * Not a placeholder. Spec §3: *"Only books get resume treatment. Music restarts from 0 — progress
 * is still recorded, just not honoured on prepare."* This is that behaviour, and `ProgressWriter`
 * is the "progress is still recorded" half. The audiobook plan replaces this object and changes
 * nothing else.
 *
 * `mediaIds` is unread here, and that is the specified behaviour rather than an omission: nothing
 * about *which* items are queued can change where music starts. It is still a parameter because it
 * is what the policy that replaces this one answers from.
 */
object NeverResume : ResumePolicy {
  override fun resolve(mediaIds: List<String>, requestedIndex: Int): ResumeTarget =
    ResumeTarget(startIndex = requestedIndex, startPositionMs = 0L)
}
