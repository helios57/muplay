package app.muplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import app.muplay.designsystem.theme.MuPlayTheme
import app.muplay.ui.MuPlayApp

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Edge-to-edge is enforced at API 35+; Scaffold below handles the resulting insets.
    enableEdgeToEdge()

    setContent {
      MuPlayTheme {
        Scaffold { innerPadding ->
          MuPlayApp(modifier = Modifier.padding(innerPadding))
        }
      }
    }
  }
}
