package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*
import androidx.compose.ui.uikit.LocalUIViewController
import platform.Foundation.NSUserDefaults
import platform.UIKit.*

@Composable internal actual fun rememberAppearanceSettings(): AppearanceSettings = remember {
    val preferences = NSUserDefaults.standardUserDefaults
    AppearanceSettings(preferences.stringForKey(APPEARANCE_KEY)) {
        preferences.setObject(it, forKey = APPEARANCE_KEY)
    }
}

@Composable internal actual fun PlatformAppearance(mode: ThemeMode, dark: Boolean) {
    val controller = LocalUIViewController.current
    DisposableEffect(controller, mode) {
        controller.overrideUserInterfaceStyle = when (mode) {
            ThemeMode.SYSTEM -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
            ThemeMode.LIGHT -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ThemeMode.DARK -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
        }
        controller.setNeedsStatusBarAppearanceUpdate()
        onDispose { controller.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified }
    }
}

@Composable internal actual fun SettingsBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
