package com.aiaixyz.minemic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal expect val usesSystemPairingAppearance: Boolean

/** Returns true when the platform draws the selection and labels; gestures remain in Compose. */
@Composable
internal expect fun PlatformPairingSelection(
    labels: List<String>, position: Float, enabled: Boolean, engaged: Boolean, rtl: Boolean,
    modifier: Modifier,
): Boolean
