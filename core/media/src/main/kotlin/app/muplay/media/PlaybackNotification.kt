package app.muplay.media

/**
 * The identity of the one notification this app posts.
 *
 * Constants rather than literals scattered through the service, because both are asserted by
 * `MuPlaybackServiceTest` against the notification the system is actually holding, and one of them
 * (the channel id) is visible to a user in system settings for as long as the app is installed --
 * changing it strands the old channel's settings, including a "silent" choice they made once and
 * would have to make again.
 *
 * Neither value is Media3's default. That is what makes asserting them worth doing: a service that
 * never configured its notification provider posts on Media3's own channel, under Media3's own id,
 * and the two assertions in `theNotificationIsOnThisAppsOwnChannelUnderThisAppsOwnId` fail.
 */
object PlaybackNotification {
  const val CHANNEL_ID: String = "muplay_playback"
  const val NOTIFICATION_ID: Int = 1001
}
