package com.aiaixyz.minemic

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import com.aiaixyz.minemic.connection.PhonePlatform
import com.aiaixyz.minemic.connection.MicController

fun MainViewController(platform: PhonePlatform) = ComposeUIViewController {
    val controller = remember(platform) { MicController(platform) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    App(controller)
}
