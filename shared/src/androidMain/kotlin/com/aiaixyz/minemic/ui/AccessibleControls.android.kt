package com.aiaixyz.minemic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformAccessibleGainSlider(
    value: Int, enabled: Boolean, onValueChange: (Int) -> Unit, modifier: Modifier,
    visible: Boolean,
    label: String, hint: String,
): Boolean = false

@Composable
internal actual fun PlatformAccessibleChoice(
    labels: List<String>, hints: List<String>, groupLabel: String,
    selectedIndex: Int, enabled: Boolean, visible: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier,
): Boolean = false
