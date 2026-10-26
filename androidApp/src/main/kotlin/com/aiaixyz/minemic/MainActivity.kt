package com.aiaixyz.minemic

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import com.aiaixyz.minemic.connection.MicController
import com.aiaixyz.minemic.connection.IpVersion

class MicViewModel(app: Application) : AndroidViewModel(app) {
    val controller = MicController(AndroidPhonePlatform(app))
    init { MicForegroundService.disconnect = { controller.disconnect() } }
    override fun onCleared() { MicForegroundService.disconnect = null; controller.close() }
}
class MainActivity : ComponentActivity() {
    private lateinit var model: MicViewModel
    private var pending: Triple<String, String, IpVersion>? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val request = pending; pending = null
        if (results.values.all { it } && request != null) model.controller.connect(request.first, request.second, request.third)
        else Toast.makeText(this, "需要麦克风和局域网权限才能连接 PC", Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        model = ViewModelProvider(this)[MicViewModel::class.java]
        setContent { App(controller = model.controller, lockScreenControls = null, onConnectWithIpVersion = { code, host, version ->
            val required = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
            }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (required.isEmpty()) model.controller.connect(code, host, version)
            else { pending = Triple(code, host, version); microphone.launch(required.toTypedArray()) }
        }) }
    }
}
