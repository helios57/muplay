package app.muplay.watchlink

import app.muplay.database.entity.MediaProgressEntity

/**
 * Who wins when a phone and a watch disagree about where a book is.
 *
 * Three rules and one honest limitation, all argued in Plan 5 Task 10's own header:
 *
 * 1. **Last writer wins, by `lastPlayedAtEpochMs`.**
 * 2. **A tie is broken by the greater `positionMs`.** Two devices writing in the same millisecond is
 *    reachable -- a batch apply writes many rows at one clock read -- and an arbitrary tie-break
 *    would make the merge non-deterministic, so the same row could flip back and forth on every
 *    sync. "Further along wins" is also the answer a listener wants.
 * 3. **Deletions do not replicate.** Plan 4's `restart(bookId)` clears a progress row; a cleared row
 *    is an absence, and an absence is indistinguishable from "this device has never seen that book".
 *    The consequence, stated rather than hidden: restarting a book on the phone does not restart it
 *    on the watch until the watch plays it, at which point the watch's newer row wins normally.
 *
 * And a named risk: **two devices' clocks are not the same clock.** `lastPlayedAtEpochMs` from a
 * watch is only comparable with the phone's because Wear OS forces automatic time from the paired
 * phone, so the skew is a network round trip rather than a user's setting. Rule 2 limits the damage
 * when it is not: a device whose clock is behind loses ties it should win, but never loses a
 * position it is ahead on.
 *
 * Pure, and therefore fully gated on the JVM tier -- which matters more here than almost anywhere
 * else in this plan, because the transport that carries these rows is the one thing no emulator can
 * run.
 */
object ProgressMerge {

  /** The rows [remote] should cause this device to write. Never includes a row [local] already wins. */
  fun updates(
    local: List<MediaProgressEntity>,
    remote: List<MediaProgressEntity>,
  ): List<MediaProgressEntity> {
    val byId = local.associateBy(MediaProgressEntity::mediaId)
    return remote.filter { candidate ->
      val existing = byId[candidate.mediaId] ?: return@filter true
      winner(existing, candidate) !== existing
    }
  }

  /**
   * The row that should survive.
   *
   * Returns [local] itself (by identity) when local wins, which is what lets [updates] tell "remote
   * won" from "they are equal" without comparing every field. The identity comparison is why the
   * `else` arm returns `local` rather than `remote`: for two rows that are `equals` but not
   * identical, "nothing to write" is the answer, and `remote` would report a write on every sync
   * forever.
   */
  fun winner(local: MediaProgressEntity, remote: MediaProgressEntity): MediaProgressEntity = when {
    remote.lastPlayedAtEpochMs > local.lastPlayedAtEpochMs -> remote
    remote.lastPlayedAtEpochMs < local.lastPlayedAtEpochMs -> local
    remote.positionMs > local.positionMs -> remote
    else -> local
  }
}
