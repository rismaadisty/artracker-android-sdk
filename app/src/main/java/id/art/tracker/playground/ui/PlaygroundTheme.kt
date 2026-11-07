package id.art.tracker.playground.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colours for SDK states; `remote` marks traffic that reaches a real backend. */
@Immutable
data class StatusColors(val accepted: Color, val retry: Color, val error: Color, val remote: Color, val onRemote: Color)

private val LightStatus = StatusColors(Color(0xFF1B7F3B), Color(0xFF9A6700), Color(0xFFB3261E), Color(0xFFB3261E), Color.White)
private val DarkStatus = StatusColors(Color(0xFF6FD08C), Color(0xFFE3B341), Color(0xFFF2B8B5), Color(0xFF8C1D18), Color.White)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val Light = lightColorScheme(
    primary = Color(0xFF0B6E75),
    secondary = Color(0xFF4A6366),
    tertiary = Color(0xFF515B92),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF7FD3DA),
    secondary = Color(0xFFB1CBCE),
    tertiary = Color(0xFFBBC3FF),
)

@Composable
fun PlaygroundTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
