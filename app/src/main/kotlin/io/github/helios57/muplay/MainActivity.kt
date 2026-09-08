package io.github.helios57.muplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
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
    // Edge-to-edge is enforced at API 35+; Scaffold below handles the resulting insets.
    enableEdgeToEdge()
    askForNotificationPermission()

    setContent {
      MuPlayTheme {
        Scaffold { innerPadding ->
          MuPlayApp(modifier = Modifier.padding(innerPadding))
        }
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
