package com.darmawan.dronebridge

import android.content.Context
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

typealias Reply = (accepted: Boolean, message: String) -> Unit

/**
 * Executes PASUPASASTRA GCS commands on the Matrice 4T through MSDK v5.
 * Same protocol as the Autel bridge (command / mission -> ack, plus events).
 * Nothing runs unless "Izinkan kendali dari GCS" is enabled on the controller (interlock).
 *
 * DJI note: MSDK v5 has no separate arm/disarm; motors start with takeoff and stop after landing.
 */
class DjiCommands(
    private val ctx: Context,
    private val allowControl: Boolean,
    private val state: Map<String, Any>,
    private val send: (String) -> Unit,
) {
    private enum class MissionState { NONE, UPLOADED, RUNNING, PAUSED }

    @Volatile private var missionState = MissionState.NONE
    @Volatile private var missionId: String? = null
    @Volatile private var missionFile: File? = null
    @Volatile private var missionTotal = 0
    @Volatile private var lastIndex = -1
    @Volatile private var pendingStart: Reply? = null

    private val manager get() = WaypointMissionManager.getInstance()

    private val executeListener = WaypointMissionExecuteStateListener { s ->
        when (s) {
            WaypointMissionExecuteState.FINISHED -> if (missionState == MissionState.RUNNING) {
                missionState = MissionState.NONE
                event("info", "Misi selesai")
            }
            else -> Unit
        }
    }

    private val executingListener = object : WaylineExecutingInfoListener {
        override fun onWaylineExecutingInfoUpdate(it: WaylineExecutingInfo) {
            val index = it.currentWaypointIndex
            if (index != lastIndex && missionState == MissionState.RUNNING) {
                lastIndex = index
                event("info", "Menuju titik ${index + 1}/$missionTotal")
            }
        }

        override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError?) {
            if (error != null) event("warning", "Misi terhenti: ${error.description() ?: error.errorCode()}")
        }
    }

    fun attach() {
        runCatching {
            manager.addWaypointMissionExecuteStateListener(executeListener)
            manager.addWaylineExecutingInfoListener(executingListener)
        }
    }

    fun detach() {
        runCatching {
            manager.removeWaypointMissionExecuteStateListener(executeListener)
            manager.removeWaylineExecutingInfoListener(executingListener)
        }
    }

    fun hello(droneId: String?) {
        val caps = JSONArray().put("telemetry").put("lens")
        if (allowControl) caps.put("commands").put("mission")
        val msg = JSONObject().put("type", "hello").put("vendor", "dji").put("capabilities", caps)
        if (droneId != null) msg.put("droneId", droneId)
        send(msg.toString())
    }

    /** Returns true when the message was a GCS request handled here. */
    fun onMessage(msg: JSONObject): Boolean {
        val id = msg.optString("id")
        return when (msg.optString("type")) {
            "command" -> { guarded(id, msg.optJSONObject("command")?.optString("type") == "set-lens") { command(msg.getJSONObject("command"), it) }; true }
            "mission" -> { guarded(id, false) { upload(msg.getJSONObject("plan"), it) }; true }
            else -> false
        }
    }

    private fun ack(id: String): Reply = { accepted, message ->
        send(JSONObject().put("type", "ack").put("id", id).put("accepted", accepted).put("message", message).toString())
        BridgeEvents.log("Perintah ${if (accepted) "diterima" else "ditolak"}: $message")
    }

    private fun event(severity: String, message: String) {
        send(JSONObject().put("type", "event").put("severity", severity).put("message", message).toString())
    }

    private fun guarded(id: String, lensOnly: Boolean, block: (Reply) -> Unit) {
        val reply = ack(id)
        if (!allowControl && !lensOnly) {
            reply(false, "Kendali dari GCS dinonaktifkan di aplikasi bridge (aktifkan \"Izinkan kendali dari GCS\")")
            return
        }
        if (!DjiSdk.productConnected) {
            reply(false, "Drone belum terhubung ke remote controller")
            return
        }
        try {
            block(reply)
        } catch (e: IllegalArgumentException) {
            reply(false, e.message ?: "Parameter tidak valid")
        } catch (t: Throwable) {
            reply(false, "Bridge gagal menjalankan perintah: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- commands

    private fun flying(): Boolean = state["flying"] as? Boolean ?: false

    private fun mode(): String = (state["mode"] as? String ?: "").uppercase()

    private fun position(): Pair<Double?, Double?> {
        val pos = state["pos"] as? Map<*, *>
        return Pair((pos?.get("lat") as? Number)?.toDouble(), (pos?.get("lon") as? Number)?.toDouble())
    }

    private fun perform(key: dji.sdk.keyvalue.key.DJIActionKeyInfo<EmptyMsg, EmptyMsg>, reply: Reply, ok: String, fail: String) {
        key.create().action({ reply(true, ok) }, { e: IDJIError -> reply(false, "$fail: ${e.description() ?: e.errorCode()}") })
    }

    private fun command(cmd: JSONObject, reply: Reply) {
        when (val type = cmd.optString("type")) {
            "arm" -> reply(false, "DJI tidak mendukung arm terpisah: motor menyala otomatis saat Lepas landas")
            "disarm" -> reply(false, "DJI mematikan motor otomatis setelah mendarat")
            "takeoff" -> {
                require(!flying()) { "Drone sudah di udara" }
                perform(FlightControllerKey.KeyStartTakeoff, reply, "Lepas landas (M4T naik ±1,2 m lalu hover)", "Gagal lepas landas")
            }
            "land" -> perform(FlightControllerKey.KeyStartAutoLanding, reply, "Mendarat di posisi saat ini", "Gagal mendarat")
            "rtl" -> perform(FlightControllerKey.KeyStartGoHome, reply, "Kembali ke home", "Gagal kembali ke home")
            "hold" -> hold(reply)
            "set-mode" -> when (cmd.optString("mode")) {
                "RTL" -> perform(FlightControllerKey.KeyStartGoHome, reply, "Kembali ke home", "Gagal kembali ke home")
                "LAND" -> perform(FlightControllerKey.KeyStartAutoLanding, reply, "Mendarat", "Gagal mendarat")
                "LOITER", "POSHOLD", "GUIDED" -> hold(reply)
                "AUTO" -> start(reply)
                else -> reply(false, "Mode ${cmd.optString("mode")} tidak didukung M4T dari GCS")
            }
            "goto" -> goTo(cmd, reply)
            "mission-start" -> start(reply)
            "mission-pause" -> {
                require(missionState == MissionState.RUNNING) { "Tidak ada misi berjalan" }
                manager.pauseMission(completion(reply, "Misi dijeda", "Gagal menjeda misi") { missionState = MissionState.PAUSED })
            }
            "set-lens" -> {
                val lens = cmd.optString("lens")
                if (lens == "split") TelemetryBridge.setSplit(true) else {
                    TelemetryBridge.setLens(lens)
                    TelemetryBridge.setSplit(false)
                }
                reply(true, "Lensa ${lens.uppercase()} dipilih")
            }
            "payload-release" -> reply(false, "Pelepasan payload M4T memerlukan integrasi PSDK khusus")
            else -> reply(false, "Perintah tidak dikenal: $type")
        }
    }

    private fun hold(reply: Reply) {
        val m = mode()
        when {
            missionState == MissionState.RUNNING ->
                manager.pauseMission(completion(reply, "Misi dijeda, menahan posisi", "Gagal menjeda misi") { missionState = MissionState.PAUSED })
            m.contains("GO_HOME") -> perform(FlightControllerKey.KeyStopGoHome, reply, "RTH dibatalkan, menahan posisi", "Gagal membatalkan RTH")
            m.contains("LANDING") -> perform(FlightControllerKey.KeyStopAutoLanding, reply, "Pendaratan dibatalkan", "Gagal membatalkan pendaratan")
            else -> reply(true, "Drone sudah menahan posisi")
        }
    }

    private fun goTo(cmd: JSONObject, reply: Reply) {
        require(flying()) { "Lepas landas terlebih dahulu" }
        require(missionState != MissionState.RUNNING) { "Jeda misi yang sedang berjalan terlebih dahulu" }
        val alt = if (cmd.has("altM")) cmd.getDouble("altM") else ((state["pos"] as? Map<*, *>)?.get("alt") as? Number)?.toDouble() ?: 30.0
        val plan = JSONObject().put("name", "goto").put("endAction", "hold").put("takeoffAltM", alt)
            .put("waypoints", JSONArray().put(
                JSONObject().put("lat", cmd.getDouble("lat")).put("lon", cmd.getDouble("lon"))
                    .put("altM", alt).put("speedMs", 5.0).put("holdS", 0).put("action", "none")
            ))
        upload(plan) { ok, message -> if (ok) start(reply) else reply(false, message) }
    }

    private fun upload(plan: JSONObject, reply: Reply) {
        require(missionState != MissionState.RUNNING) { "Misi sedang berjalan, jeda atau selesaikan terlebih dahulu" }
        val (lat, lon) = position()
        val points = DjiMission.parse(plan, lat, lon)
        val file = DjiMission.writeKmz(ctx, plan.optString("name", "misi"), points, plan.optDouble("takeoffAltM", 20.0), plan.optString("endAction", "rtl"))
        manager.pushKMZFileToAircraft(file.absolutePath, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double) = Unit

            override fun onSuccess() {
                missionFile = file
                missionId = file.nameWithoutExtension
                missionTotal = points.size
                missionState = MissionState.UPLOADED
                reply(true, "Misi diunggah ke M4T: ${points.size} titik")
            }

            override fun onFailure(error: IDJIError) = reply(false, "Gagal mengunggah misi: ${error.description() ?: error.errorCode()}")
        })
    }

    private fun start(reply: Reply) {
        when (missionState) {
            MissionState.PAUSED -> manager.resumeMission(completion(reply, "Misi dilanjutkan", "Gagal melanjutkan misi") { missionState = MissionState.RUNNING })
            MissionState.UPLOADED -> {
                val id = missionId ?: return reply(false, "Belum ada misi yang diunggah")
                val file = missionFile ?: return reply(false, "Belum ada misi yang diunggah")
                require(!mode().contains("GO_HOME") && !mode().contains("LANDING")) { "Keluar dari mode RTH/mendarat terlebih dahulu" }
                val waylines = manager.getAvailableWaylineIDs(file.absolutePath)
                lastIndex = -1
                manager.startMission(id, waylines, completion(reply, "Misi dimulai ($missionTotal titik)", "Gagal memulai misi") { missionState = MissionState.RUNNING })
            }
            MissionState.RUNNING -> reply(false, "Misi sudah berjalan")
            MissionState.NONE -> reply(false, "Belum ada misi yang diunggah ke drone")
        }
    }

    private fun completion(reply: Reply, ok: String, fail: String, after: () -> Unit = {}) = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() {
            after()
            reply(true, ok)
        }

        override fun onFailure(error: IDJIError) = reply(false, "$fail: ${error.description() ?: error.errorCode()}")
    }
}
