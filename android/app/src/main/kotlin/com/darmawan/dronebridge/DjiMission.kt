package com.darmawan.dronebridge

import android.content.Context
import com.dji.wpmzsdk.common.data.Template
import com.dji.wpmzsdk.manager.WPMZManager
import dji.sdk.wpmz.value.mission.ActionAircraftHoverParam
import dji.sdk.wpmz.value.mission.ActionTakePhotoParam
import dji.sdk.wpmz.value.mission.CameraLensType
import dji.sdk.wpmz.value.mission.WaylineActionGroup
import dji.sdk.wpmz.value.mission.WaylineActionInfo
import dji.sdk.wpmz.value.mission.WaylineActionNodeList
import dji.sdk.wpmz.value.mission.WaylineActionTreeNode
import dji.sdk.wpmz.value.mission.WaylineActionTrigger
import dji.sdk.wpmz.value.mission.WaylineActionTriggerType
import dji.sdk.wpmz.value.mission.WaylineActionType
import dji.sdk.wpmz.value.mission.WaylineActionsRelationType
import dji.sdk.wpmz.value.mission.WaylineAltitudeMode
import dji.sdk.wpmz.value.mission.WaylineCoordinateMode
import dji.sdk.wpmz.value.mission.WaylineCoordinateParam
import dji.sdk.wpmz.value.mission.WaylineDroneInfo
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostAction
import dji.sdk.wpmz.value.mission.WaylineExitOnRCLostBehavior
import dji.sdk.wpmz.value.mission.WaylineFinishedAction
import dji.sdk.wpmz.value.mission.WaylineFlyToWaylineMode
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate2D
import dji.sdk.wpmz.value.mission.WaylineLocationCoordinate3D
import dji.sdk.wpmz.value.mission.WaylineMission
import dji.sdk.wpmz.value.mission.WaylineMissionConfig
import dji.sdk.wpmz.value.mission.WaylinePayloadInfo
import dji.sdk.wpmz.value.mission.WaylinePositioningType
import dji.sdk.wpmz.value.mission.WaylineTemplateWaypointInfo
import dji.sdk.wpmz.value.mission.WaylineWaypoint
import dji.sdk.wpmz.value.mission.WaylineWaypointPitchMode
import dji.sdk.wpmz.value.mission.WaylineWaypointTurnMode
import dji.sdk.wpmz.value.mission.WaylineWaypointYawMode
import dji.sdk.wpmz.value.mission.WaylineWaypointYawParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Validated GCS waypoint, ready to be written into a WPML (KMZ) file. */
data class GcsWaypoint(val lat: Double, val lon: Double, val altM: Double, val speedMs: Double, val holdS: Double, val photo: Boolean)

/**
 * Converts a PASUPATASTRA mission plan into a DJI WPML KMZ (Waypoint V3), following
 * DJI's KMZTestUtil (Mobile-SDK-Android-V5 sample). Altitudes are relative to the takeoff point.
 */
object DjiMission {
    const val MAX_ALT_M = 120.0
    const val MIN_ALT_M = 5.0
    const val MAX_WAYPOINT_DISTANCE_M = 1_000.0
    const val MAX_SPEED_MS = 10.0
    const val MAX_WAYPOINTS = 99

    @Volatile private var wpmzReady = false

    /** Parses and checks the plan against the bridge safety limits. @throws IllegalArgumentException */
    fun parse(plan: JSONObject, droneLat: Double?, droneLon: Double?): List<GcsWaypoint> {
        val points = plan.optJSONArray("waypoints") ?: JSONArray()
        require(points.length() in 1..MAX_WAYPOINTS) { "Misi harus berisi 1–$MAX_WAYPOINTS titik" }
        require(droneLat != null && droneLon != null) { "Posisi drone belum diketahui (GPS)" }
        return (0 until points.length()).map { i ->
            val w = points.getJSONObject(i)
            val lat = w.getDouble("lat")
            val lon = w.getDouble("lon")
            val alt = w.getDouble("altM")
            require(alt in MIN_ALT_M..MAX_ALT_M) { "Titik ${i + 1}: ketinggian harus ${MIN_ALT_M.toInt()}–${MAX_ALT_M.toInt()} m" }
            val dist = distanceM(droneLat, droneLon, lat, lon)
            require(dist <= MAX_WAYPOINT_DISTANCE_M) {
                "Titik ${i + 1} berjarak ${dist.toInt()} m dari drone (batas ${MAX_WAYPOINT_DISTANCE_M.toInt()} m)"
            }
            val action = w.optString("action", "none")
            val hold = if (action == "hover") maxOf(w.optDouble("holdS", 0.0), 5.0) else w.optDouble("holdS", 0.0)
            val speed = (if (w.isNull("speedMs")) 5.0 else w.getDouble("speedMs")).coerceIn(1.0, MAX_SPEED_MS)
            GcsWaypoint(lat, lon, alt, speed, hold, action == "photo")
        }
    }

    /** Writes the KMZ and returns its file (mission id = file name without ".kmz"). */
    fun writeKmz(ctx: Context, name: String, points: List<GcsWaypoint>, takeoffAltM: Double, endAction: String): File {
        if (!wpmzReady) {
            WPMZManager.getInstance().init(ctx)
            wpmzReady = true
        }
        val dir = File(ctx.getExternalFilesDir(null), "wpmz").apply { mkdirs() }
        val safe = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifEmpty { "misi" }
        val file = File(dir, "gcs_${safe}_${System.currentTimeMillis()}.kmz")

        val now = System.currentTimeMillis().toDouble()
        val mission = WaylineMission()
        mission.setCreateTime(now)
        mission.setUpdateTime(now)

        val config = WaylineMissionConfig()
        config.setFlyToWaylineMode(WaylineFlyToWaylineMode.SAFELY)
        config.setFinishAction(finishAction(endAction))
        config.setDroneInfo(WaylineDroneInfo())
        config.setSecurityTakeOffHeight(takeoffAltM.coerceIn(MIN_ALT_M, MAX_ALT_M))
        config.setIsSecurityTakeOffHeightSet(true)
        config.setExitOnRCLostBehavior(WaylineExitOnRCLostBehavior.EXCUTE_RC_LOST_ACTION)
        config.setExitOnRCLostType(WaylineExitOnRCLostAction.GO_BACK)
        config.setGlobalTransitionalSpeed(points.first().speedMs)
        config.setPayloadInfo(ArrayList<WaylinePayloadInfo>())

        val waypoints = points.mapIndexed { index, p ->
            WaylineWaypoint().apply {
                setWaypointIndex(index)
                setLocation(WaylineLocationCoordinate2D(p.lat, p.lon))
                setHeight(p.altM)
                setEllipsoidHeight(p.altM)
                setSpeed(p.speedMs)
                setUseGlobalTurnParam(true)
                setUseGlobalYawParam(true)
                setGimbalPitchAngle(-30.0)
            }
        }
        val info = WaylineTemplateWaypointInfo()
        info.setWaypoints(waypoints)
        info.setActionGroups(actionGroups(points))
        info.setGlobalFlightHeight(points.first().altM)
        info.setIsGlobalFlightHeightSet(true)
        info.setGlobalTurnMode(WaylineWaypointTurnMode.TO_POINT_AND_STOP_WITH_DISCONTINUITY_CURVATURE)
        info.setUseStraightLine(true)
        info.setIsTemplateGlobalTurnModeSet(true)
        val yaw = WaylineWaypointYawParam()
        yaw.setYawMode(WaylineWaypointYawMode.FOLLOW_WAYLINE)
        yaw.setPoiLocation(WaylineLocationCoordinate3D(points.first().lat, points.first().lon, points.first().altM))
        info.setGlobalYawParam(yaw)
        info.setIsTemplateGlobalYawParamSet(true)
        info.setPitchMode(WaylineWaypointPitchMode.USE_POINT_SETTING)

        val coordinate = WaylineCoordinateParam()
        coordinate.setCoordinateMode(WaylineCoordinateMode.WGS84)
        coordinate.setPositioningType(WaylinePositioningType.GPS)
        coordinate.setIsWaylinePositioningTypeSet(true)
        coordinate.setAltitudeMode(WaylineAltitudeMode.RELATIVE_TO_START_POINT)

        val template = Template()
        template.setWaypointInfo(info)
        template.setCoordinateParam(coordinate)
        template.setUseGlobalTransitionalSpeed(true)
        template.setAutoFlightSpeed(points.first().speedMs)
        template.setPayloadParam(ArrayList())

        WPMZManager.getInstance().generateKMZFile(file.absolutePath, mission, config, template)
        require(file.exists() && file.length() > 0) { "Gagal membuat file KMZ misi" }
        return file
    }

    /** Enum constants other than GO_HOME differ between SDK versions: resolve by name, fall back to GO_HOME. */
    private fun finishAction(endAction: String): WaylineFinishedAction {
        val wanted = when (endAction) {
            "land" -> listOf("AUTO_LAND", "AUTO_LANDING")
            "hold" -> listOf("NO_ACTION")
            else -> listOf("GO_HOME")
        }
        return WaylineFinishedAction.values().firstOrNull { it.name in wanted } ?: WaylineFinishedAction.GO_HOME
    }

    private fun actionGroups(points: List<GcsWaypoint>): List<WaylineActionGroup> {
        val groups = ArrayList<WaylineActionGroup>()
        points.forEachIndexed { index, p ->
            val actions = ArrayList<WaylineActionInfo>()
            if (p.holdS > 0) {
                actions.add(WaylineActionInfo().apply {
                    setActionType(WaylineActionType.HOVER)
                    setAircraftHoverParam(ActionAircraftHoverParam().apply { setHoverTime(p.holdS) })
                })
            }
            if (p.photo) {
                actions.add(WaylineActionInfo().apply {
                    setActionType(WaylineActionType.TAKE_PHOTO)
                    setTakePhotoParam(ActionTakePhotoParam(0, true, ArrayList<CameraLensType>(), "gcs").apply { setPayloadPositionIndex(0) })
                })
            }
            if (actions.isEmpty()) return@forEachIndexed
            val group = WaylineActionGroup()
            group.setTrigger(WaylineActionTrigger().apply { setTriggerType(WaylineActionTriggerType.REACH_POINT) })
            group.setGroupId(groups.size)
            group.setStartIndex(index)
            group.setEndIndex(index)
            group.setActions(actions)
            val root = WaylineActionNodeList().apply {
                setNodes(listOf(WaylineActionTreeNode().apply {
                    setNodeType(WaylineActionsRelationType.SEQUENCE)
                    setChildrenNum(actions.size)
                }))
            }
            val children = WaylineActionNodeList().apply {
                setNodes(actions.indices.map { j ->
                    WaylineActionTreeNode().apply {
                        setNodeType(WaylineActionsRelationType.LEAF)
                        setActionIndex(j)
                    }
                })
            }
            group.setNodeLists(listOf(root, children))
            groups.add(group)
        }
        return groups
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
