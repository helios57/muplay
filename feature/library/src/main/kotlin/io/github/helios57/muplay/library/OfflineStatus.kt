package io.github.helios57.muplay.library

import io.github.helios57.muplay.database.OfflinePlaylist
import io.github.helios57.muplay.media.DownloadActivity

/** Where one playlist stands on this phone, as the playlist screen shows it. */
sealed interface OfflineStatus {
  data object NotKept : OfflineStatus

  data class Downloading(val done: Int, val total: Int) : OfflineStatus

  /** The downloader stopped on a failure; the playlist screen offers to try again. */
  data class Paused(val done: Int, val total: Int) : OfflineStatus

  data object Kept : OfflineStatus
}

/**
 * Complete wins over whatever the downloader is doing: it stalls on *some* song, which may belong
 * to another playlist, and a playlist whose every song is here is on the phone regardless.
 */
fun offlineStatus(saved: OfflinePlaylist?, activity: DownloadActivity): OfflineStatus = when {
  saved == null -> OfflineStatus.NotKept
  saved.downloaded >= saved.total -> OfflineStatus.Kept
  activity is DownloadActivity.Stalled -> OfflineStatus.Paused(saved.downloaded, saved.total)
  else -> OfflineStatus.Downloading(saved.downloaded, saved.total)
}

internal fun OfflineStatus.label(): String? = when (this) {
  OfflineStatus.NotKept -> null
  is OfflineStatus.Downloading -> "Downloading $done of $total…"
  is OfflineStatus.Paused -> "Download paused, $done of $total on this phone. The server could not be reached."
  OfflineStatus.Kept -> KEPT_LABEL
}

/** The playlists list's one-line note beside a kept playlist. */
internal fun keptBadge(saved: OfflinePlaylist?): String? = when {
  saved == null -> null
  saved.downloaded >= saved.total -> KEPT_LABEL
  else -> "Downloading ${saved.downloaded} of ${saved.total}"
}

internal const val KEPT_LABEL = "On this phone"
internal const val KEEP_OFFLINE_LABEL = "Keep on this phone"
internal const val REMOVE_OFFLINE_LABEL = "Remove from this phone"
internal const val KEPT_ONLY_NOTICE =
  "The server cannot be reached. Showing the playlists kept on this phone."
internal const val KEPT_COPY_NOTICE =
  "The server cannot be reached. This is the copy kept on this phone; only downloaded songs are listed."
