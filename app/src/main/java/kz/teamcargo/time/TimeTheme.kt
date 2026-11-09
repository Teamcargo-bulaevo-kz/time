package kz.teamcargo.time

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GraphiteRed = darkColorScheme(
    primary = Color(0xFFE36A72), onPrimary = Color(0xFF2B1013),
    secondary = Color(0xFFB98B8F), onSecondary = Color(0xFF241619),
    background = Color(0xFF100D0E), onBackground = Color(0xFFBDB7B8),
    surface = Color(0xFF191416), onSurface = Color(0xFFBDB7B8),
    surfaceVariant = Color(0xFF302326), onSurfaceVariant = Color(0xFF8F8587),
    error = Color(0xFFFF9B9B), onError = Color(0xFF690005)
)
private val MidnightBlue = darkColorScheme(
    primary = Color(0xFF82A9D8), onPrimary = Color(0xFF10243A),
    secondary = Color(0xFF9AAFC4), onSecondary = Color(0xFF172432),
    background = Color(0xFF0B1119), onBackground = Color(0xFFB8C2CC),
    surface = Color(0xFF121B26), onSurface = Color(0xFFB8C2CC),
    surfaceVariant = Color(0xFF202D3B), onSurfaceVariant = Color(0xFF8797A8),
    error = Color(0xFFFFA6A6), onError = Color(0xFF690005)
)
private val ForestNight = darkColorScheme(
    primary = Color(0xFF8EBA9A), onPrimary = Color(0xFF102A1B),
    secondary = Color(0xFFA5B9A8), onSecondary = Color(0xFF1A2A20),
    background = Color(0xFF0D130F), onBackground = Color(0xFFBAC2BA),
    surface = Color(0xFF151D17), onSurface = Color(0xFFBAC2BA),
    surfaceVariant = Color(0xFF263229), onSurfaceVariant = Color(0xFF8E9B91),
    error = Color(0xFFFFA6A6), onError = Color(0xFF690005)
)

@Composable
fun TimeTheme(palette: String = "red", content: @Composable () -> Unit) {
    val colors = when (palette) {
        "blue" -> MidnightBlue
        "green" -> ForestNight
        else -> GraphiteRed
    }
    MaterialTheme(colorScheme = colors, content = content)
}
