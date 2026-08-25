package app.muplay.media

/**
 * Whether swiping the app out of the recents list should stop the playback service.
 *
 * A plain object taking two nullable primitives, with no Android or Media3 type in its signature,
 * for the reason this module keeps making: `Service.onTaskRemoved` is called by the system and by
 * nothing else, so the *adapter* is unreachable from any test this project can run -- but the
 * *rule* it applies does not have to be. Same split as `StreamRetryPolicy` behind
 * `NavidromeLoadErrorHandlingPolicy`, and `ResumePolicy` behind the progress writer.
 *
 * **Both halves of the rule matter, and they fail in opposite directions.** Stopping
 * unconditionally kills music a user is listening to while they tidy their recents list -- the
 * single most-reported complaint about media apps that get this wrong. Never stopping leaves an
 * idle foreground service and a notification the user cannot get rid of, because the app it belongs
 * to is gone from recents. Until [TaskRemovalPolicyTest] existed, that argument lived only in a
 * comment on the service.
 *
 * A queue with nothing in it counts as stopped even when the player is nominally ready to play:
 * `playWhenReady` survives `clearMediaItems`, so a service that checked only that flag would keep
 * itself alive around an empty player forever.
 */
object TaskRemovalPolicy {

  /**
   * [playWhenReady] and [mediaItemCount] are nullable because there may be no session and therefore
   * no player at all -- which is itself the clearest possible reason to stop, and is expressed here
   * rather than as a null check at the call site so that the whole decision has one home.
   */
  fun stopsService(playWhenReady: Boolean?, mediaItemCount: Int?): Boolean =
    playWhenReady != true || (mediaItemCount ?: 0) == 0
}
