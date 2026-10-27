@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.aiaixyz.minemic.ui

import androidx.compose.runtime.*
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification

@Composable
internal actual fun rememberMotionSettings(): MotionSettings = remember {
    val preferences = NSUserDefaults.standardUserDefaults
    MotionSettings(preferences.boolForKey(REDUCED_MOTION_KEY)) {
        preferences.setBool(it, forKey = REDUCED_MOTION_KEY)
    }
}

@Composable
internal actual fun rememberReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null, queue = NSOperationQueue.mainQueue) {
            reduced = UIAccessibilityIsReduceMotionEnabled()
        }
        onDispose { center.removeObserver(observer) }
    }
    return reduced
}
