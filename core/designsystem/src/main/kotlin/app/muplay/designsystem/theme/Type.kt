package app.muplay.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Only the styles the app actually uses so far ([SetupScreen][app.muplay.setup.SetupScreen] reads
 * `headlineSmall`/`bodyLarge`/`labelLarge`) are overridden; every other [Typography] slot keeps
 * Material 3's own default — there is no design brief yet for the rest.
 */
val MuPlayTypography = Typography(
  headlineSmall = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 24.sp,
    lineHeight = 32.sp,
  ),
  bodyLarge = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 24.sp,
  ),
  labelLarge = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
  ),
)
