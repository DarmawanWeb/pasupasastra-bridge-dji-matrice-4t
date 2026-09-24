package com.darmawan.dronebridge

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/** Kirim event native -> Flutter. Aman kalau UI Flutter sedang tidak ada (sink = null). */
object BridgeEvents {
    @Volatile var sink: EventChannel.EventSink? = null
    private val main = Handler(Looper.getMainLooper())

    fun emit(m: Map<String, Any?>) { main.post { sink?.success(m) } }
    fun log(msg: String) = emit(mapOf("type" to "log", "msg" to msg))
    fun sdk(msg: String) = emit(mapOf("type" to "sdk", "msg" to msg))
}
