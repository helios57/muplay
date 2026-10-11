package io.github.helios57.muplay.waves

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while the binaural generator sounds, so it keeps playing with
 * the screen off and the music stopped -- which is how it is used, at night. The notification says
 * what is playing and offers Stop; the service ends itself the moment the generator goes idle.
 */
@AndroidEntryPoint
class BinauralService : Service() {

  @Inject
  lateinit var player: BinauralPlayer

  private val scope = MainScope()
  private var watching: Job? = null
  private var shown: String? = null
  private var shownAt = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) player.stop()
    goForeground(player.status.value)
    if (watching == null) {
      watching = scope.launch {
        player.status.collect { status ->
          if (status is BinauralStatus.Playing) {
            refresh(status)
          } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          }
        }
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun goForeground(status: BinauralStatus) {
    val notification = notification(status)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /** At most every ten seconds: the text moves with a ramp, and a notification is not a meter. */
  private fun refresh(status: BinauralStatus.Playing) {
    val text = notificationText(status)
    val now = System.currentTimeMillis()
    if (text == shown || now - shownAt < REFRESH_MILLIS && shown != null) return
    shown = text
    shownAt = now
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
  }

  private fun notification(status: BinauralStatus): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while binaural beats are playing."
        setShowBadge(false)
      },
    )
    val stop = PendingIntent.getService(
      this, 0, Intent(this, BinauralService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val playing = status as? BinauralStatus.Playing
    return Notification.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_waves_notification)
      .setContentTitle(playing?.profile?.name ?: CHANNEL_NAME)
      .setContentText(playing?.let(::notificationText) ?: "Starting…")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(Notification.CATEGORY_TRANSPORT)
      .setContentIntent(open)
      .addAction(Notification.Action.Builder(null, STOP_LABEL, stop).build())
      .build()
  }

  companion object {
    const val ACTION_STOP = "io.github.helios57.muplay.waves.STOP"
    const val CHANNEL_ID = "binaural"
    const val CHANNEL_NAME = "Binaural beats"
    const val NOTIFICATION_ID = 0xB1
    private const val REFRESH_MILLIS = 10_000L
  }
}
