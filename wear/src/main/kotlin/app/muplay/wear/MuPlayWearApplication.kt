package app.muplay.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The watch's own Hilt graph.
 *
 * A separate `Application` from `:app`'s, over the same `:core:*` modules -- which means the watch
 * has its **own** Room database, its own credential store and its own `media_progress` rows. That
 * is a consequence of two devices, not a design choice, and it is what Task 10 exists to reconcile.
 * `WearSessionJourneyTest` asserts the starting state rather than assuming it, so that Task 10's
 * merge has something documented to merge *from*.
 */
@HiltAndroidApp
class MuPlayWearApplication : Application()
