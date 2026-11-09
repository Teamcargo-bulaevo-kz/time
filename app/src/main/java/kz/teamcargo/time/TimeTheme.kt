package kz.teamcargo.time

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TimeDarkColors = darkColorScheme(
    primary = Color(0xFFE36A72),
    onPrimary = Color(0xFF2B1013),
    secondary = Color(0xFFB98B8F),
    onSecondary = Color(0xFF241619),
    background = Color(0xFF100D0E),
    onBackground = Color(0xFFBDB7B8),
    surface = Color(0xFF191416),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF302326),
    onSurfaceVariant = Color(0xFF8F8587),
    error = Color(0xFFFF9B9B),
    onError = Color(0xFF690005)
)

@Composable
fun TimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TimeDarkColors,
        content = content
    )
}
