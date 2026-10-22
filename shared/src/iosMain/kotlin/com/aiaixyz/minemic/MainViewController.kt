package com.aiaixyz.minemic

import androidx.compose.ui.window.ComposeUIViewController
import com.aiaixyz.minemic.connection.MicController
import com.aiaixyz.minemic.connection.LockScreenControls

// App-scoped owner controls the session. Disposing a screen only removes UI.
fun MainViewController(controller: MicController, lockScreenControls: LockScreenControls? = null) =
    ComposeUIViewController { App(controller = controller, lockScreenControls = lockScreenControls) }
