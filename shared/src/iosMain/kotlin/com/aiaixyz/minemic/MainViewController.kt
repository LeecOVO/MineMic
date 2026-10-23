package com.aiaixyz.minemic

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.CompositionLocalProvider
import com.aiaixyz.minemic.connection.MicController
import com.aiaixyz.minemic.connection.LockScreenControls
import com.aiaixyz.minemic.ui.LocalNativeGlassEffectFactory
import com.aiaixyz.minemic.ui.NativeGlassEffectFactory

// App-scoped owner controls the session. Disposing a screen only removes UI.
fun MainViewController(
    controller: MicController, lockScreenControls: LockScreenControls? = null,
    glassEffects: NativeGlassEffectFactory? = null,
) = ComposeUIViewController {
    CompositionLocalProvider(LocalNativeGlassEffectFactory provides glassEffects) {
        App(controller = controller, lockScreenControls = lockScreenControls)
    }
}
