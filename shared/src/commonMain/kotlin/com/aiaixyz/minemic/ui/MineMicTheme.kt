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
    background = Color.White, onBackground = Color(0xFF191B1D),
    surface = Color(0xFFF5F6F7), onSurface = Color(0xFF191B1D),
    surfaceVariant = Color(0xFFEBEDEF), onSurfaceVariant = Color(0xFF62676D),
    surfaceDim = Color(0xFFE0E3E6), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF0F1F2), surfaceContainerHigh = Color(0xFFE7E9EB),
    surfaceContainerHighest = Color(0xFFDDE0E3), surfaceTint = Color(0xFF006F68),
    outline = Color(0xFF858B91), outlineVariant = Color(0xFFDEE1E4),
    error = Color(0xFFB44435), onError = Color.White,
    errorContainer = Color(0xFFFFE8E2), onErrorContainer = Color(0xFF852D22),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF76D9C7), onPrimary = Color(0xFF003A34),
    primaryContainer = Color(0xFF164840), onPrimaryContainer = Color(0xFFAFEEDF),
    secondary = Color(0xFFB3CBC8), onSecondary = Color(0xFF233C39),
    secondaryContainer = Color(0xFF314B47), onSecondaryContainer = Color(0xFFD2E8E2),
    background = Color.Black, onBackground = Color(0xFFF1F2F3),
    surface = Color(0xFF141516), onSurface = Color(0xFFF1F2F3),
    surfaceVariant = Color(0xFF27292B), onSurfaceVariant = Color(0xFFAFB4BA),
    surfaceDim = Color.Black, surfaceBright = Color(0xFF36393D),
    surfaceContainerLowest = Color(0xFF080809), surfaceContainerLow = Color(0xFF101112),
    surfaceContainer = Color(0xFF1E2022), surfaceContainerHigh = Color(0xFF292C2F),
    surfaceContainerHighest = Color(0xFF36393D), surfaceTint = Color(0xFF76D9C7),
    outline = Color(0xFF848B92), outlineVariant = Color(0xFF35393D),
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
