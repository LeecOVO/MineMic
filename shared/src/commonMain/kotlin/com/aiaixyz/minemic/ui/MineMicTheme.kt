package com.aiaixyz.minemic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1C6558), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECE5), onPrimaryContainer = Color(0xFF174D43),
    secondary = Color(0xFF64746C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EAE4), onSecondaryContainer = Color(0xFF344B40),
    background = Color(0xFFF5F5F0), onBackground = Color(0xFF202A26),
    surface = Color(0xFFFDFDF9), onSurface = Color(0xFF202A26),
    surfaceVariant = Color(0xFFEBEEE7), onSurfaceVariant = Color(0xFF68726C),
    surfaceDim = Color(0xFFDFE3DA), surfaceBright = Color(0xFFFDFDF9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F5F0),
    surfaceContainer = Color(0xFFEFF1EA), surfaceContainerHigh = Color(0xFFE6EAE2),
    surfaceContainerHighest = Color(0xFFDFE5DC), surfaceTint = Color(0xFF1C6558),
    outline = Color(0xFF7A867E), outlineVariant = Color(0xFFDCE1D9),
    error = Color(0xFFB44435), onError = Color.White,
    errorContainer = Color(0xFFFFE8E2), onErrorContainer = Color(0xFF852D22),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1D5BD), onPrimary = Color(0xFF163D31),
    primaryContainer = Color(0xFF294B3F), onPrimaryContainer = Color(0xFFC3EAD7),
    secondary = Color(0xFFB3C3B8), onSecondary = Color(0xFF27392D),
    secondaryContainer = Color(0xFF34443A), onSecondaryContainer = Color(0xFFD5E4D9),
    background = Color(0xFF151B18), onBackground = Color(0xFFE8EEE7),
    surface = Color(0xFF1E2722), onSurface = Color(0xFFE8EEE7),
    surfaceVariant = Color(0xFF2B352F), onSurfaceVariant = Color(0xFFA6B2A9),
    surfaceDim = Color(0xFF151B18), surfaceBright = Color(0xFF354039),
    surfaceContainerLowest = Color(0xFF101511), surfaceContainerLow = Color(0xFF1A221D),
    surfaceContainer = Color(0xFF202A23), surfaceContainerHigh = Color(0xFF2A342D),
    surfaceContainerHighest = Color(0xFF354039), surfaceTint = Color(0xFFA1D5BD),
    outline = Color(0xFF7B8B80), outlineVariant = Color(0xFF354039),
    error = Color(0xFFFFB4A6), onError = Color(0xFF5F160B),
    errorContainer = Color(0xFF4A2922), onErrorContainer = Color(0xFFFFC7BC),
)

@Composable
fun MineMicTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
