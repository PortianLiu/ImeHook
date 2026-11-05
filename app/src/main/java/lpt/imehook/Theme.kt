package lpt.imehook

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF136B4B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA5F2CB),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9D9),
    onSecondaryContainer = Color(0xFF092016),
    background = Color(0xFFF5FBF5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF5FBF5),
    surfaceVariant = Color(0xFFDEE5DB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5EE),
    surfaceContainerHighest = Color(0xFFDEE5DB),
    onSurface = Color(0xFF171D19),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF6F7A72),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD6AE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005137),
    onPrimaryContainer = Color(0xFFA5F2CB),
    secondary = Color(0xFFB3CCBC),
    onSecondary = Color(0xFF0A1F14),
    secondaryContainer = Color(0xFF354B3E),
    onSecondaryContainer = Color(0xFFCFE9D9),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DD),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF404943),
    surfaceContainerLowest = Color(0xFF0A100D),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainerHighest = Color(0xFF333A35),
    onSurface = Color(0xFFDEE4DD),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938C),
)

@Composable
fun ImeHookTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
