package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*
import java.util.prefs.Preferences

@Composable
internal actual fun rememberMotionSettings(): MotionSettings = remember {
    val preferences = Preferences.userRoot().node("com/aiaixyz/minemic/appearance")
    MotionSettings(preferences.getBoolean(REDUCED_MOTION_KEY, false)) {
        preferences.putBoolean(REDUCED_MOTION_KEY, it)
    }
}

@Composable
internal actual fun rememberReducedMotion(): Boolean = false
