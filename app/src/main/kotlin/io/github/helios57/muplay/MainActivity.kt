package io.github.helios57.muplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.helios57.muplay.designsystem.theme.MuPlayTheme
import io.github.helios57.muplay.ui.MuPlayApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  /**
   * Registered as a field, which is not a style choice: `registerForActivityResult` must be called
   * before the activity reaches `STARTED`, and it throws
   * `LifecycleOwner is attempting to register while current state is RESUMED` if it is reached from
   * anywhere later -- a click handler, a `LaunchedEffect`.
   *
   * There is no callback body because there is nothing to do with the answer. A granted permission
   * is observed by the notification appearing; a denied one is the user's decision and the app goes
   * on working, minus the notification. What it must not do is ask again on the next launch, and it
   * does not have to: the platform stops showing the dialog after the second refusal and
   * [ContextCompat.checkSelfPermission] below keeps this quiet in every later launch.
   */
  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Edge-to-edge is enforced at API 35+. `MuPlayApp` owns the one `Scaffold` in this app and
    // that is what handles the resulting insets -- see [setContent]'s note below for what a second
    // one cost.
    enableEdgeToEdge()
    askForNotificationPermission()

    // **One `Scaffold`, and it is `MuPlayApp`'s.** This used to wrap it in a second one and hand
    // its `innerPadding` down, which reads like ordinary edge-to-edge boilerplate and quietly
    // consumed every system-bar inset *twice*: the outer `Scaffold` padded the content in by the
    // 24dp navigation-bar inset, and the `NavigationBar` inside the inner one then applied its own
    // `NavigationBarDefaults.windowInsets` again on top of that.
    //
    // Measured on the emulator at 420dpi before the fix: the bottom chrome ran 1888..2337 of a
    // 2400px screen while the gesture bar itself occupies only 2337..2400 -- 48dp of gesture-bar
    // padding for a 24dp gesture bar, and the same 24dp doubled under the status bar at the top.
    // 48dp of a 914dp screen, spent on nothing, which is what "on the bottom there is too much
    // space wasted" was pointing at.
    //
    // Nothing is lost by removing it: `Scaffold`'s own `contentWindowInsets` defaults to
    // `WindowInsets.systemBars`, so the inner one still pads content away from the status bar on
    // the screens that draw no top bar, and still pads it away from the gesture bar on the player
    // screens that draw no bottom bar. `ConventionTest`'s `the app composes exactly one Scaffold`
    // is what keeps a second one from coming back.
    setContent {
      MuPlayTheme {
        MuPlayApp()
      }
    }
  }

  /**
   * Asks for `POST_NOTIFICATIONS`, without which this app has no transport anywhere.
   *
   * The permission has been declared in `core/media`'s manifest since the playback service was
   * written, and until this method existed nothing ever requested it. From API 33 it is
   * `dangerous`, so declaring it grants nothing: `MuPlaybackService` starts, plays audio and posts
   * *no* notification -- no lock-screen transport, nothing for a paired watch or a car to mirror,
   * and a foreground service the user cannot see or stop. Nothing throws and nothing is logged.
   *
   * It survived because the only thing that ever granted it was `GrantPermissionRule` in the
   * instrumented tests, so every notification assertion in `MuPlaybackServiceTest` passed against a
   * permission the shipped app never asked for.
   * `ConventionTest`'s `every runtime permission this app declares is requested somewhere in src
   * main` is what keeps it asked for, and it reads the declared set out of the manifests rather
   * than naming this one.
   *
   * **Asked at launch rather than at first play.** Android's guidance is to ask in context, and for
   * this app the context is the app: there is no screen that is not a step towards playing
   * something, and the alternative -- asking from the moment audio starts -- puts a system dialog
   * over the player at the exact moment the user is reaching for it.
   */
  private fun askForNotificationPermission() {
    // The permission does not exist before API 33; requesting an unknown permission is not an
    // error, it is silently denied forever, which would leave the guard above permanently true.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (granted) return
    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
