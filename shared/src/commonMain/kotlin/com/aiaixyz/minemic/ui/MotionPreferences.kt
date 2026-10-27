package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*

/** The manual preference can add motion reduction, but never override a system accessibility request. */
@Stable
class MotionSettings(initialEnabled: Boolean = false, private val save: (Boolean) -> Unit = {}) {
    var enabled by mutableStateOf(initialEnabled)
        private set

    fun select(value: Boolean) {
        if (enabled == value) return
        save(value)
        enabled = value
    }

    fun isReduced(systemReducedMotion: Boolean): Boolean = enabled || systemReducedMotion
}

internal const val REDUCED_MOTION_KEY = "accessibility.reduceMotion"
internal val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal expect fun rememberMotionSettings(): MotionSettings

/** Observes the operating system preference independently of the app's manual setting. */
@Composable
internal expect fun rememberReducedMotion(): Boolean
