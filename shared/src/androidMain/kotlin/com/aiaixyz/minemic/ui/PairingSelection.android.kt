package com.aiaixyz.minemic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val usesSystemPairingAppearance: Boolean = false

@Composable
internal actual fun PlatformPairingSelection(
    labels: List<String>, position: Float, enabled: Boolean, engaged: Boolean, rtl: Boolean, compact: Boolean,
    modifier: Modifier, reducedMotion: Boolean,
): Boolean = false
