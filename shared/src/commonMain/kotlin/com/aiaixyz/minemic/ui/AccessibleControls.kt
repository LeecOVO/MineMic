package com.aiaixyz.minemic.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Returns true only when a platform control owns both touch and accessibility interaction. */
@Composable
internal expect fun PlatformAccessibleGainSlider(
    value: Int, enabled: Boolean, onValueChange: (Int) -> Unit, modifier: Modifier,
    visible: Boolean = true,
    label: String = "收音增强",
    hint: String = "范围 0 到 18 分贝，0 表示不增强。向上或向下轻扫，每次调整 1 分贝；也可双击并按住，听到提示音后左右拖动。",
): Boolean

/** Category choices remain separately selectable options, rather than a numeric slider. */
@Composable
internal expect fun PlatformAccessibleChoice(
    labels: List<String>, hints: List<String>, groupLabel: String,
    selectedIndex: Int, enabled: Boolean, visible: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier,
): Boolean
