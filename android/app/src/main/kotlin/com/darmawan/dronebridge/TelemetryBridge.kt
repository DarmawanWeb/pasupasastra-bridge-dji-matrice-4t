package com.darmawan.dronebridge

import android.content.Context
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.ThermalDisplayMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Telemetry (10 Hz) + GCS commands for the Matrice 4T.
 * Flight commands and missions run only when [allowControl] is enabled on the controller
 * (interlock); lens switching is always allowed. See DjiCommands for the protocol.
 */
class TelemetryBridge(
    private val ctx: Context,
    private val wsUrl: String,
    private val allowControl: Boolean,
) {
    private val holder = Any()
    private val state = ConcurrentHashMap<String, Any>()
    private val client = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    private var backoffMs = 1000L
    private var tick = 0
    private val commands = DjiCommands(ctx, allowControl, state) { text -> ws?.send(text) }

    private fun <T> bind(info: DJIKeyInfo<T>, name: String, map: (T) -> Any = { it as Any }) {
        KeyManager.getInstance().listen(
            KeyTools.createKey(info), holder,
            object : CommonCallbacks.KeyListener<T> {
                override fun onValueChange(oldValue: T?, newValue: T?) {
                    if (newValue != null) state[name] = map(newValue)
                }
            })
    }

    fun start() {
        running = true
        bind(FlightControllerKey.KeyAircraftLocation3D, "pos") {
            mapOf("lat" to it.latitude, "lon" to it.longitude, "alt" to it.altitude)
        }
        bind(FlightControllerKey.KeyAircraftAttitude, "att") {
            mapOf("pitch" to it.pitch, "roll" to it.roll, "yaw" to it.yaw)
        }
        bind(FlightControllerKey.KeyAircraftVelocity, "vel") {
            mapOf("x" to it.x, "y" to it.y, "z" to it.z)
        }
        bind(FlightControllerKey.KeyCompassHeading, "heading")
        bind(FlightControllerKey.KeyIsFlying, "flying")
        bind(FlightControllerKey.KeyGPSSatelliteCount, "sats")
        bind(BatteryKey.KeyChargeRemainingInPercent, "battery")
        bind(FlightControllerKey.KeyFlightMode, "mode") { (it as Enum<*>).name }
        bind(FlightControllerKey.KeyAreMotorsOn, "motors")
        bind(FlightControllerKey.KeyHomeLocation, "home") {
            mapOf("lat" to it.latitude, "lon" to it.longitude)
        }
        commands.attach()

        connect()
        exec.scheduleAtFixedRate({ runCatching { push() } }, 0, 100, TimeUnit.MILLISECONDS) // 10 Hz
    }

    private fun push() {
        if (state.isEmpty()) return
        val j = JSONObject()
        state.forEach { (k, v) -> j.put(k, JSONObject.wrap(v)) }
        j.put("ts", System.currentTimeMillis())
        ws?.send(j.toString())
        if (++tick % 5 == 0) BridgeEvents.emit(mapOf("type" to "telemetry", "data" to j.toString()))
    }

    private fun connect() {
        if (!running) return
        ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000; BridgeEvents.log("WebSocket terhubung (kendali GCS: ${if (allowControl) "aktif" else "nonaktif"})")
                commands.hello(null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val j = JSONObject(text)
                    if (commands.onMessage(j)) return@runCatching
                    // Legacy lens protocol (older GCS builds)
                    if (j.optString("cmd") == "set_lens") setLens(j.getString("lens"))
                    if (j.optString("cmd") == "set_display") setSplit(j.optString("mode") == "split")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                BridgeEvents.log("WS putus: ${t.message}"); retry()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()
        })
    }

    private fun retry() {
        if (!running) return
        exec.schedule({ connect() }, backoffMs, TimeUnit.MILLISECONDS)
        backoffMs = (backoffMs * 2).coerceAtMost(15_000)
    }

    fun stop() {
        running = false
        commands.detach()
        KeyManager.getInstance().cancelListen(holder)
        ws?.close(1000, "bye")
        exec.shutdownNow()
    }

    companion object {
        /** Split-screen (PIP): thermal + kamera visual dalam SATU stream kiri-kanan. Thermal harus jadi sumber aktif. */
        fun setSplit(enable: Boolean) {
            if (enable) setLens("thermal")
            val key = KeyTools.createKey(CameraKey.KeyThermalDisplayMode, ComponentIndexType.LEFT_OR_MAIN)
            val mode = if (enable) ThermalDisplayMode.PIP else ThermalDisplayMode.THERMAL_ONLY
            KeyManager.getInstance().setValue(key, mode, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { BridgeEvents.log("Display -> ${if (enable) "split" else "single"}") }
                override fun onFailure(error: IDJIError) { BridgeEvents.log("Split gagal: ${error.description()}") }
            })
        }
        /** Ganti sumber video kamera utama. Bukan perintah terbang. */
        fun setLens(lens: String) {
            val type = if (lens == "thermal") CameraVideoStreamSourceType.INFRARED_CAMERA
                       else CameraVideoStreamSourceType.WIDE_CAMERA
            val key = KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, ComponentIndexType.LEFT_OR_MAIN)
            KeyManager.getInstance().setValue(key, type, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { BridgeEvents.log("Lens -> $lens") }
                override fun onFailure(error: IDJIError) { BridgeEvents.log("Ganti lens gagal: ${error.description()}") }
            })
        }
    }
}
