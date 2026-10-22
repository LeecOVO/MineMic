package com.aiaixyz.minemic.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LockScreenState(
    val enabled: Boolean = false,
    val available: Boolean = true,
    val message: String = "在锁屏和灵动岛查看状态、静音或断开；关闭不影响录音",
)

/** iOS owns ActivityKit; Compose only displays the optional controls. */
class LockScreenControls(private val onChange: (Boolean) -> Unit) {
    private val mutableState = MutableStateFlow(LockScreenState())
    val state = mutableState.asStateFlow()
    fun update(enabled: Boolean, available: Boolean, message: String) {
        mutableState.value = LockScreenState(enabled, available, message)
    }
    fun setEnabled(value: Boolean) = onChange(value)
}

interface MicStateSubscription { fun cancel() }
