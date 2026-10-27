package com.aiaixyz.minemic.ui

import android.database.ContentObserver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberMotionSettings(): MotionSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val preferences = context.getSharedPreferences("minemic.appearance", Context.MODE_PRIVATE)
        MotionSettings(preferences.getBoolean(REDUCED_MOTION_KEY, false)) {
            preferences.edit().putBoolean(REDUCED_MOTION_KEY, it).apply()
        }
    }
}

@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
