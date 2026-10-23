package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*

enum class ThemeMode(val value: String, val label: String) {
    SYSTEM("system", "跟随系统"), LIGHT("light", "白色"), DARK("dark", "黑色");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

@Stable
class AppearanceSettings(initialValue: String? = null, private val save: (String) -> Unit = {}) {
    var mode by mutableStateOf(ThemeMode.fromValue(initialValue))
        private set

    fun select(value: ThemeMode) {
        if (mode == value) return
        save(value.value)
        mode = value
    }
}

internal const val APPEARANCE_KEY = "appearance.mode"
@Composable internal expect fun rememberAppearanceSettings(): AppearanceSettings
@Composable internal expect fun PlatformAppearance(mode: ThemeMode, dark: Boolean)
@Composable internal expect fun SettingsBackHandler(enabled: Boolean, onBack: () -> Unit)
