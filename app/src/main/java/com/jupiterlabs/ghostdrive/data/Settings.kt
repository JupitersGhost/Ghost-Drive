package com.jupiterlabs.ghostdrive.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class VehicleProfile(val address: String, val name: String, val driver: String = "Driver") {
    fun json(): JSONObject = JSONObject().put("address", address).put("name", name).put("driver", driver)
    companion object {
        fun fromJson(o: JSONObject) = VehicleProfile(
            address = o.optString("address").trim().uppercase(),
            name = o.optString("name").ifBlank { "Vehicle" },
            driver = o.optString("driver").ifBlank { "Driver" }
        )
    }
}

data class CustomMeshNode(
    val id: String,
    val name: String,
    val url: String,
    val type: String = "OPENAI", // "OPENAI" (/v1/chat/completions) or "OLLAMA" (/api/chat)
    val model: String = "local-model"
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("type", type)
        .put("model", model)

    companion object {
        fun fromJson(o: JSONObject): CustomMeshNode {
            val rawUrl = o.optString("url").trim().trimEnd('/')
            val rawName = o.optString("name").trim().ifBlank { rawUrl }
            val rawId = o.optString("id").trim().ifBlank {
                "node_" + rawUrl.lowercase().replace(Regex("[^a-z0-9]"), "_").takeLast(24)
            }
            return CustomMeshNode(
                id = rawId,
                name = rawName,
                url = rawUrl,
                type = o.optString("type", "OPENAI").uppercase().let { if (it == "OLLAMA") "OLLAMA" else "OPENAI" },
                model = o.optString("model").trim().ifBlank { "local-model" }
            )
        }
    }
}

data class Settings(
    val theme: String = "midnight", val driver: String = "Driver", val vehicle: String = "Vehicle",
    val deviceAddress: String = "", val gpsIntervalSeconds: Int = 1, val maxAccuracyM: Float = 75f,
    val movingThresholdMps: Float = 1.5f, val gpsEnabled: Boolean = true,
    val historyEndpoint: String = "",
    val entropyEndpoint: String = "",
    val syncPullEndpoint: String = "",
    val syncEnabled: Boolean = false,
    val syncOnlyConnected: Boolean = true, val syncIntervalSeconds: Int = 15,
    val retentionDays: Int = 90, val logRetentionDays: Int = 30,
    val mapPath: String = "", val offlineMapUrl: String = "https://download.mapsforge.org/maps/v5/north-america/us/wisconsin.map",
    val displayUnits: String = "mi", val showMerlin: Boolean = true,
    val graphSamples: Int = 120, val keepScreenOn: Boolean = false,
    val autoStartOnBoot: Boolean = true, val wasArmed: Boolean = false,
    val vehicles: List<VehicleProfile> = emptyList(),
    val customMeshNodes: List<CustomMeshNode> = emptyList(),
    val mapStyle: String = "auto",
    val routeColorMode: String = "speed",
    val voiceEnabled: Boolean = true,
    val geminiVoiceEnabled: Boolean = true,
    val voiceAnnounceEntropy: Boolean = false,
    val voiceName: String = "",
    val voicePitch: Float = 0.96f,
    val voiceRate: Float = 1.03f,
    val voiceStyle: String = "merlin",
    val geminiVoice: String = "Aoede",
    val voiceSource: String = "gemini",
    val autoSyncArchive: Boolean = false,
    val watchEnabled: Boolean = true,
    val watchStandbyBatchMode: Boolean = false,
    val watchBatchIntervalSec: Int = 30,
    val phoneEntropyEnabled: Boolean = true,
    val onboardBrainEnabled: Boolean = true,
    val heyMerlinEnabled: Boolean = false,
    val preferredBrainRoute: String = "auto",
    val updatedAtMs: Long = 0L
) {
    fun toSyncJson(): JSONObject = JSONObject()
        .put("updated_at_ms", updatedAtMs)
        .put("theme", theme)
        .put("driver", driver)
        .put("vehicle", vehicle)
        .put("device_address", deviceAddress)
        .put("gps_interval_seconds", gpsIntervalSeconds)
        .put("max_accuracy_m", maxAccuracyM.toDouble())
        .put("moving_threshold_mps", movingThresholdMps.toDouble())
        .put("gps_enabled", gpsEnabled)
        .put("history_endpoint", historyEndpoint)
        .put("entropy_endpoint", entropyEndpoint)
        .put("sync_pull_endpoint", syncPullEndpoint)
        .put("sync_enabled", syncEnabled)
        .put("sync_only_connected", syncOnlyConnected)
        .put("sync_interval_seconds", syncIntervalSeconds)
        .put("retention_days", retentionDays)
        .put("log_retention_days", logRetentionDays)
        .put("offline_map_url", offlineMapUrl)
        .put("display_units", displayUnits)
        .put("show_merlin", showMerlin)
        .put("graph_samples", graphSamples)
        .put("keep_screen_on", keepScreenOn)
        .put("auto_start_on_boot", autoStartOnBoot)
        .put("map_style", mapStyle)
        .put("route_color_mode", routeColorMode)
        .put("voice_enabled", voiceEnabled)
        .put("gemini_voice_enabled", geminiVoiceEnabled)
        .put("voice_announce_entropy", voiceAnnounceEntropy)
        .put("voice_name", voiceName)
        .put("voice_pitch", voicePitch.toDouble())
        .put("voice_rate", voiceRate.toDouble())
        .put("voice_style", voiceStyle)
        .put("gemini_voice", geminiVoice)
        .put("voice_source", voiceSource)
        .put("auto_sync_archive", autoSyncArchive)
        .put("watch_enabled", watchEnabled)
        .put("watch_standby_batch_mode", watchStandbyBatchMode)
        .put("watch_batch_interval_sec", watchBatchIntervalSec)
        .put("phone_entropy_enabled", phoneEntropyEnabled)
        .put("onboard_brain_enabled", onboardBrainEnabled)
        .put("hey_merlin_enabled", heyMerlinEnabled)
        .put("preferred_brain_route", preferredBrainRoute)
        .put("vehicles", JSONArray().apply { vehicles.forEach { put(it.json()) } })
        .put("custom_mesh_nodes", JSONArray().apply { customMeshNodes.forEach { put(it.json()) } })

    fun mergeFromSyncJson(o: JSONObject): Settings {
        val remoteTs = o.optLong("updated_at_ms", 0L)
        val mergedVehicles = buildList {
            val byAddr = LinkedHashMap<String, VehicleProfile>()
            vehicles.forEach { if (it.address.isNotBlank()) byAddr[it.address.uppercase()] = it }
            val arr = o.optJSONArray("vehicles")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val vp = VehicleProfile.fromJson(arr.getJSONObject(i))
                    if (vp.address.isNotBlank()) {
                        if (remoteTs >= updatedAtMs || !byAddr.containsKey(vp.address.uppercase())) {
                            byAddr[vp.address.uppercase()] = vp
                        }
                    }
                }
            }
            addAll(byAddr.values)
        }
        val mergedNodes = buildList {
            val byId = LinkedHashMap<String, CustomMeshNode>()
            customMeshNodes.forEach { if (it.url.isNotBlank()) byId[it.id] = it }
            val arr = o.optJSONArray("custom_mesh_nodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val node = CustomMeshNode.fromJson(arr.getJSONObject(i))
                    if (node.url.isNotBlank()) {
                        if (remoteTs >= updatedAtMs || !byId.containsKey(node.id)) {
                            byId[node.id] = node
                        }
                    }
                }
            }
            addAll(byId.values)
        }
        if (remoteTs <= 0L || (updatedAtMs > 0L && remoteTs < updatedAtMs)) {
            return copy(vehicles = mergedVehicles, customMeshNodes = mergedNodes)
        }
        val incomingGps = o.optInt("gps_interval_seconds", gpsIntervalSeconds)
        val resolvedGps = if (incomingGps == 5 && gpsIntervalSeconds == 1) 1 else incomingGps.coerceIn(1, 60)
        return copy(
            theme = o.optString("theme", theme).ifBlank { theme },
            driver = o.optString("driver", driver).ifBlank { driver },
            vehicle = o.optString("vehicle", vehicle).ifBlank { vehicle },
            deviceAddress = o.optString("device_address", deviceAddress).trim().uppercase(),
            gpsIntervalSeconds = resolvedGps,
            maxAccuracyM = o.optDouble("max_accuracy_m", maxAccuracyM.toDouble()).toFloat().coerceIn(5f, 200f),
            movingThresholdMps = o.optDouble("moving_threshold_mps", movingThresholdMps.toDouble()).toFloat().coerceIn(0.5f, 10f),
            gpsEnabled = o.optBoolean("gps_enabled", gpsEnabled),
            historyEndpoint = o.optString("history_endpoint", historyEndpoint).ifBlank { historyEndpoint },
            entropyEndpoint = o.optString("entropy_endpoint", entropyEndpoint).ifBlank { entropyEndpoint },
            syncPullEndpoint = o.optString("sync_pull_endpoint", syncPullEndpoint).ifBlank { syncPullEndpoint },
            syncEnabled = o.optBoolean("sync_enabled", syncEnabled),
            syncOnlyConnected = o.optBoolean("sync_only_connected", syncOnlyConnected),
            syncIntervalSeconds = o.optInt("sync_interval_seconds", syncIntervalSeconds).coerceIn(5, 300),
            retentionDays = o.optInt("retention_days", retentionDays).coerceIn(0, 3650),
            offlineMapUrl = o.optString("offline_map_url", offlineMapUrl).ifBlank { offlineMapUrl },
            displayUnits = o.optString("display_units", displayUnits).ifBlank { displayUnits },
            showMerlin = o.optBoolean("show_merlin", showMerlin),
            graphSamples = o.optInt("graph_samples", graphSamples).coerceIn(30, 600),
            keepScreenOn = o.optBoolean("keep_screen_on", keepScreenOn),
            autoStartOnBoot = o.optBoolean("auto_start_on_boot", autoStartOnBoot),
            vehicles = mergedVehicles,
            customMeshNodes = mergedNodes,
            mapStyle = o.optString("map_style", mapStyle).ifBlank { mapStyle },
            routeColorMode = o.optString("route_color_mode", routeColorMode).ifBlank { routeColorMode },
            voiceEnabled = o.optBoolean("voice_enabled", voiceEnabled),
            geminiVoiceEnabled = o.optBoolean("gemini_voice_enabled", geminiVoiceEnabled),
            voiceAnnounceEntropy = o.optBoolean("voice_announce_entropy", voiceAnnounceEntropy),
            voiceName = o.optString("voice_name", voiceName),
            voicePitch = o.optDouble("voice_pitch", voicePitch.toDouble()).toFloat().coerceIn(0.5f, 2.0f),
            voiceRate = o.optDouble("voice_rate", voiceRate.toDouble()).toFloat().coerceIn(0.5f, 2.0f),
            voiceStyle = o.optString("voice_style", voiceStyle).ifBlank { voiceStyle },
            geminiVoice = o.optString("gemini_voice", geminiVoice).ifBlank { geminiVoice },
            voiceSource = o.optString("voice_source", voiceSource).ifBlank { voiceSource },
            autoSyncArchive = o.optBoolean("auto_sync_archive", autoSyncArchive),
            watchEnabled = o.optBoolean("watch_enabled", watchEnabled),
            watchStandbyBatchMode = o.optBoolean("watch_standby_batch_mode", watchStandbyBatchMode),
            watchBatchIntervalSec = o.optInt("watch_batch_interval_sec", watchBatchIntervalSec).coerceIn(15, 300),
            phoneEntropyEnabled = o.optBoolean("phone_entropy_enabled", phoneEntropyEnabled),
            onboardBrainEnabled = o.optBoolean("onboard_brain_enabled", onboardBrainEnabled),
            heyMerlinEnabled = o.optBoolean("hey_merlin_enabled", heyMerlinEnabled),
            preferredBrainRoute = o.optString("preferred_brain_route", preferredBrainRoute).ifBlank { preferredBrainRoute },
            updatedAtMs = remoteTs
        )
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun loadVehicles(): List<VehicleProfile> = runCatching {
        val raw = prefs.getString("vehicles_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val vp = VehicleProfile.fromJson(arr.getJSONObject(i))
                if (vp.address.isNotBlank()) add(vp)
            }
        }
    }.getOrDefault(emptyList())

    private fun loadCustomMeshNodes(): List<CustomMeshNode> = runCatching {
        val raw = prefs.getString("custom_mesh_nodes_json", "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val n = CustomMeshNode.fromJson(arr.getJSONObject(i))
                if (n.url.isNotBlank()) add(n)
            }
        }
    }.getOrDefault(emptyList())

    fun load(): Settings {
        val rawGps = prefs.getInt("gpsIntervalSeconds", 1)
        val migratedGps = if (rawGps == 5 && !prefs.getBoolean("gpsIntervalExplicit", false)) {
            prefs.edit().putInt("gpsIntervalSeconds", 1).apply()
            1
        } else rawGps

        return Settings(
            theme = prefs.getString("theme", "midnight")!!, driver = prefs.getString("driver", "Driver")!!,
            vehicle = prefs.getString("vehicle", "Vehicle")!!, deviceAddress = prefs.getString("deviceAddress", "")!!,
            gpsIntervalSeconds = migratedGps, maxAccuracyM = prefs.getFloat("maxAccuracyM", 75f),
            movingThresholdMps = prefs.getFloat("movingThresholdMps", 1.5f), gpsEnabled = prefs.getBoolean("gpsEnabled", true),
            historyEndpoint = prefs.getString("historyEndpoint", "")!!,
            entropyEndpoint = prefs.getString("entropyEndpoint", "")!!,
            syncPullEndpoint = prefs.getString("syncPullEndpoint", "")!!,
            syncEnabled = prefs.getBoolean("syncEnabled", false), syncOnlyConnected = prefs.getBoolean("syncOnlyConnected", true),
            syncIntervalSeconds = prefs.getInt("syncIntervalSeconds", 15), retentionDays = prefs.getInt("retentionDays", 90),
            logRetentionDays = prefs.getInt("logRetentionDays", 30), mapPath = prefs.getString("mapPath", "")!!,
            offlineMapUrl = prefs.getString("offlineMapUrl", Settings().offlineMapUrl)!!, displayUnits = prefs.getString("displayUnits", "mi")!!,
            showMerlin = prefs.getBoolean("showMerlin", true), graphSamples = prefs.getInt("graphSamples", 120),
            keepScreenOn = prefs.getBoolean("keepScreenOn", false),
            autoStartOnBoot = prefs.getBoolean("autoStartOnBoot", true),
            wasArmed = prefs.getBoolean("wasArmed", false),
            vehicles = loadVehicles(),
            customMeshNodes = loadCustomMeshNodes(),
            mapStyle = prefs.getString("mapStyle", "auto")!!,
            routeColorMode = prefs.getString("routeColorMode", "speed")!!,
            voiceEnabled = prefs.getBoolean("voiceEnabled", true),
            geminiVoiceEnabled = prefs.getBoolean("geminiVoiceEnabled", true),
            voiceAnnounceEntropy = prefs.getBoolean("voiceAnnounceEntropy", false),
            voiceName = prefs.getString("voiceName", "")!!,
            voicePitch = prefs.getFloat("voicePitch", 0.96f),
            voiceRate = prefs.getFloat("voiceRate", 1.03f),
            voiceStyle = prefs.getString("voiceStyle", "merlin")!!,
            geminiVoice = prefs.getString("geminiVoice", "Aoede")!!,
            voiceSource = prefs.getString("voiceSource", "gemini")!!,
            autoSyncArchive = prefs.getBoolean("autoSyncArchive", false),
            watchEnabled = prefs.getBoolean("watchEnabled", true),
            watchStandbyBatchMode = prefs.getBoolean("watchStandbyBatchMode", false),
            watchBatchIntervalSec = prefs.getInt("watchBatchIntervalSec", 30).coerceIn(15, 300),
            phoneEntropyEnabled = prefs.getBoolean("phoneEntropyEnabled", true),
            onboardBrainEnabled = prefs.getBoolean("onboardBrainEnabled", true),
            heyMerlinEnabled = prefs.getBoolean("heyMerlinEnabled", false),
            preferredBrainRoute = prefs.getString("preferredBrainRoute", "auto")!!,
            updatedAtMs = prefs.getLong("updatedAtMs", 0L)
        )
    }
    fun save(s: Settings) {
        val vehiclesArr = JSONArray().apply { s.vehicles.forEach { put(it.json()) } }
        val nodesArr = JSONArray().apply { s.customMeshNodes.forEach { put(it.json()) } }
        prefs.edit().putString("theme", s.theme).putString("driver", s.driver).putString("vehicle", s.vehicle)
            .putString("deviceAddress", s.deviceAddress).putInt("gpsIntervalSeconds", s.gpsIntervalSeconds)
            .putBoolean("gpsIntervalExplicit", true)
            .putFloat("maxAccuracyM", s.maxAccuracyM).putFloat("movingThresholdMps", s.movingThresholdMps)
            .putBoolean("gpsEnabled", s.gpsEnabled).putString("historyEndpoint", s.historyEndpoint)
            .putString("entropyEndpoint", s.entropyEndpoint).putString("syncPullEndpoint", s.syncPullEndpoint)
            .putBoolean("syncEnabled", s.syncEnabled).putBoolean("syncOnlyConnected", s.syncOnlyConnected)
            .putInt("syncIntervalSeconds", s.syncIntervalSeconds).putInt("retentionDays", s.retentionDays)
            .putInt("logRetentionDays", s.logRetentionDays).putString("mapPath", s.mapPath).putString("offlineMapUrl", s.offlineMapUrl)
            .putString("displayUnits", s.displayUnits).putBoolean("showMerlin", s.showMerlin)
            .putInt("graphSamples", s.graphSamples).putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("autoStartOnBoot", s.autoStartOnBoot).putBoolean("wasArmed", s.wasArmed)
            .putString("vehicles_json", vehiclesArr.toString())
            .putString("custom_mesh_nodes_json", nodesArr.toString())
            .putString("mapStyle", s.mapStyle)
            .putString("routeColorMode", s.routeColorMode)
            .putBoolean("voiceEnabled", s.voiceEnabled)
            .putBoolean("geminiVoiceEnabled", s.geminiVoiceEnabled)
            .putBoolean("voiceAnnounceEntropy", s.voiceAnnounceEntropy)
            .putString("voiceName", s.voiceName)
            .putFloat("voicePitch", s.voicePitch)
            .putFloat("voiceRate", s.voiceRate)
            .putString("voiceStyle", s.voiceStyle)
            .putString("geminiVoice", s.geminiVoice)
            .putString("voiceSource", s.voiceSource)
            .putBoolean("autoSyncArchive", s.autoSyncArchive)
            .putBoolean("watchEnabled", s.watchEnabled)
            .putBoolean("watchStandbyBatchMode", s.watchStandbyBatchMode)
            .putInt("watchBatchIntervalSec", s.watchBatchIntervalSec.coerceIn(15, 300))
            .putBoolean("phoneEntropyEnabled", s.phoneEntropyEnabled)
            .putBoolean("onboardBrainEnabled", s.onboardBrainEnabled)
            .putBoolean("heyMerlinEnabled", s.heyMerlinEnabled)
            .putString("preferredBrainRoute", s.preferredBrainRoute)
            .putLong("updatedAtMs", s.updatedAtMs).apply()
    }
}

/** Device key and bearer tokens are AES-GCM sealed under Android Keystore, excluded from backups. */
class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("ghostdrive-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("ghostdrive-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun put(name: String, bytes: ByteArray) {
        if (bytes.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val sealed = c.iv + c.doFinal(bytes)
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }
    fun get(name: String): ByteArray? = prefs.getString(name, null)?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    fun present(name: String) = prefs.contains(name)
}
