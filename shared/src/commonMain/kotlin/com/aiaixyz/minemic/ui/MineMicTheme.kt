package com.aiaixyz.minemic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006F68), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF2EC), onPrimaryContainer = Color(0xFF00544F),
    secondary = Color(0xFF637776), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EFEC), onSecondaryContainer = Color(0xFF354E4B),
    background = Color(0xFFF4F7F8), onBackground = Color(0xFF152B2C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF152B2C),
    surfaceVariant = Color(0xFFE9F0F0), onSurfaceVariant = Color(0xFF647677),
    surfaceDim = Color(0xFFDEE8E9), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F7F8),
    surfaceContainer = Color(0xFFEAF0F1), surfaceContainerHigh = Color(0xFFE2EAEB),
    surfaceContainerHighest = Color(0xFFD9E5E6), surfaceTint = Color(0xFF006F68),
    outline = Color(0xFF819496), outlineVariant = Color(0xFFDCE6E7),
    error = Color(0xFFB44435), onError = Color.White,
    errorContainer = Color(0xFFFFE8E2), onErrorContainer = Color(0xFF852D22),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF76D9C7), onPrimary = Color(0xFF003A34),
    primaryContainer = Color(0xFF164840), onPrimaryContainer = Color(0xFFAFEEDF),
    secondary = Color(0xFFB3CBC8), onSecondary = Color(0xFF233C39),
    secondaryContainer = Color(0xFF314B47), onSecondaryContainer = Color(0xFFD2E8E2),
    background = Color(0xFF101A1D), onBackground = Color(0xFFE5F0EF),
    surface = Color(0xFF19272B), onSurface = Color(0xFFE5F0EF),
    surfaceVariant = Color(0xFF26383C), onSurfaceVariant = Color(0xFFA2B8B8),
    surfaceDim = Color(0xFF101A1D), surfaceBright = Color(0xFF35494C),
    surfaceContainerLowest = Color(0xFF0C1518), surfaceContainerLow = Color(0xFF142125),
    surfaceContainer = Color(0xFF203136), surfaceContainerHigh = Color(0xFF2A3D41),
    surfaceContainerHighest = Color(0xFF35494C), surfaceTint = Color(0xFF76D9C7),
    outline = Color(0xFF789496), outlineVariant = Color(0xFF35494C),
    error = Color(0xFFFFB4A6), onError = Color(0xFF5F160B),
    errorContainer = Color(0xFF4A2922), onErrorContainer = Color(0xFFFFC7BC),
)

@Composable
fun MineMicTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
