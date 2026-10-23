package com.aiaixyz.minemic.ui

import androidx.compose.runtime.staticCompositionLocalOf
import platform.UIKit.UIVisualEffect

/** Swift owns availability checks so new UIKit classes are weak-linked on older iOS versions. */
interface NativeGlassEffectFactory {
    fun create(interactive: Boolean): UIVisualEffect?
}

internal val LocalNativeGlassEffectFactory = staticCompositionLocalOf<NativeGlassEffectFactory?> { null }
