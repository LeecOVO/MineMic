package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*
import java.util.prefs.Preferences

@Composable internal actual fun rememberAppearanceSettings(): AppearanceSettings = remember {
    val preferences = Preferences.userRoot().node("com/aiaixyz/minemic/appearance")
    AppearanceSettings(preferences.get(APPEARANCE_KEY, null)) { preferences.put(APPEARANCE_KEY, it) }
}

@Composable internal actual fun PlatformAppearance(mode: ThemeMode, dark: Boolean) = Unit
@Composable internal actual fun SettingsBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
