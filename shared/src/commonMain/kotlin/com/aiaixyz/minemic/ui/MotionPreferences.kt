package com.aiaixyz.minemic.ui

import androidx.compose.runtime.Composable

/** System accessibility preference used by the new form and processing-status transitions. */
@Composable
internal expect fun rememberReducedMotion(): Boolean
