package com.darmawan.dronebridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        val messenger = engine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "m4t/bridge").setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    val i = Intent(this, BridgeService::class.java)
                        .putExtra("ws", call.argument<String>("ws"))
                        .putExtra("rtmp", call.argument<String>("rtmp"))
                        .putExtra("control", call.argument<Boolean>("control") ?: false)
                    ContextCompat.startForegroundService(this, i)
                    result.success(true)
                }
                "stop" -> { stopService(Intent(this, BridgeService::class.java)); result.success(true) }
                "setLens" -> { TelemetryBridge.setLens(call.argument<String>("lens") ?: "rgb"); result.success(true) }
                "status" -> result.success(mapOf("registered" to DjiSdk.registered, "connected" to DjiSdk.productConnected))
                else -> result.notImplemented()
            }
        }

        EventChannel(messenger, "m4t/events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink?) { BridgeEvents.sink = sink }
            override fun onCancel(args: Any?) { BridgeEvents.sink = null }
        })
    }
}
