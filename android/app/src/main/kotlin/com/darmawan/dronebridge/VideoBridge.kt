package com.darmawan.dronebridge

import android.os.Handler
import android.os.Looper
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.*
import dji.v5.manager.datacenter.livestream.settings.*

/** RTMP push memakai LiveStreamManager bawaan MSDK V5 (tanpa decode/encode ulang di app). */
class VideoBridge(private val rtmpUrl: String) {
    private val lsm get() = MediaDataCenter.getInstance().liveStreamManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val statusListener = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus?) {
            BridgeEvents.log("RTMP streaming=${status?.isStreaming}")
        }
        override fun onError(error: IDJIError?) {
            BridgeEvents.log("RTMP error: ${error?.description()}")
        }
    }

    fun start() {
        running = true
        lsm.addLiveStreamStatusListener(statusListener)
        lsm.liveStreamSettings = LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(RtmpSettings.Builder().setUrl(rtmpUrl).build())
            .build()
        lsm.cameraIndex = ComponentIndexType.LEFT_OR_MAIN
        begin()
    }

    private fun begin() {
        if (!running) return
        lsm.startStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { BridgeEvents.log("RTMP mulai") }
            override fun onFailure(error: IDJIError) {
                BridgeEvents.log("RTMP gagal: ${error.description()} (coba lagi 5 dtk)")
                main.postDelayed({ begin() }, 5000)
            }
        })
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        lsm.stopStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {}
        })
    }
}
