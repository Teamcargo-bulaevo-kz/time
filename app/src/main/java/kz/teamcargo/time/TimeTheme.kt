package kz.teamcargo.time

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TimeDarkColors = darkColorScheme(
    primary = Color(0xFFB7C8FF),
    onPrimary = Color(0xFF172B55),
    secondary = Color(0xFFBFC7DC),
    onSecondary = Color(0xFF252B38),
    background = Color(0xFF101114),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF292D35),
    onSurfaceVariant = Color(0xFFC3C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun TimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TimeDarkColors,
        content = content
    )
}
