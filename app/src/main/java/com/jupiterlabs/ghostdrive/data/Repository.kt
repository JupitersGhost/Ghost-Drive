package com.jupiterlabs.ghostdrive.data

import android.content.Context
import android.util.Base64
import com.jupiterlabs.ghostdrive.ble.WatchProtocol
import com.jupiterlabs.ghostdrive.protocol.*
import com.jupiterlabs.ghostdrive.tracking.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

data class GraphSample(val timeMs: Long, val shannon: Float?, val minimum: Float?, val conditionedShannon: Float? = null, val conditionedMinimum: Float? = null)
data class SourceState(val id: SourceId, val health: Health = Health.Absent, val filled: Int = 0, val needed: Int = 8192,
    val report: SourceReport? = null, val updatedMs: Long = 0, val reportMs: Long = 0, val graph: List<GraphSample> = emptyList())
data class PhoneEntropyTelemetry(
    val active: Boolean = false,
    val totalBytesHarvested: Long = 0,
    val bufferedBytes: Int = 0,
    val activeSourcesCount: Int = 0,
    val shannon: Double = 0.0,
    val minEntropy: Double = 0.0,
    val outShannon: Double = 0.0,
    val outMinEntropy: Double = 0.0,
    val mcvMinH: Double = 0.0,
    val markovMinH: Double = 0.0,
    val tTupleMinH: Double = 0.0,
    val governingEstimator: String = "—",
    val chiSquare: Double = 0.0,
    val chiSquareOk: Boolean = true,
    val scorePct: Double = 0.0,
    val creditedBits: Int = 0,
    val blocksConditioned: Int = 0,
    val lastBlockDigestHex: String = "",
    val rctTrips: Long = 0,
    val aptTrips: Long = 0,
    val sourceBytes: Map<String, Long> = emptyMap(),
    val status: String = "Standby · 0% drain while parked"
)

data class LiveState(val armed: Boolean = false, val bleStatus: String = "Monitoring stopped", val authenticated: Boolean = false,
    val gpsActive: Boolean = false, val bootId: Long? = null, val sources: Map<SourceId, SourceState> = SourceId.entries.associateWith { SourceState(it) },
    val pool: PoolReport? = null, val poolGraph: List<GraphSample> = emptyList(), val poolUpdatedMs: Long = 0,
    val wifi: Message.WifiSummary? = null, val wifiMotion: Message.WifiMotion? = null,
    val radar: Message.RadarSummary? = null, val diagnostic: Message.Diagnostic? = null,
    val trip: Trip? = null, val lastFix: Fix? = null, val queueCount: Int = 0,
    val syncStatus: String = "Forwarding disabled", val lastSyncMs: Long? = null,
    val sequence: Long = 0, val gaps: Long = 0, val nowMs: Long = System.currentTimeMillis(), val demo: Boolean = false,
    val entropyVault: EntropyVaultSummary = EntropyVaultSummary(),
    val watch: WatchTelemetry = WatchTelemetry(),
    val phoneEntropy: PhoneEntropyTelemetry = PhoneEntropyTelemetry())

data class DiscoveredDevice(val name: String, val address: String, val rssi: Int, val isNullvalon: Boolean)

class Repository(val context: Context) {
    val db = Database(context)
    val vault = SecretVault(context)
    // Single ordered background thread for all SQLite I/O so UI, BLE, and 120Hz map rendering never block on disk.
    private val dbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ghostdrive-db").apply { isDaemon = true }
    }
    val dbScope = CoroutineScope(SupervisorJob() + dbExecutor.asCoroutineDispatcher())
    val phoneHarvester = com.jupiterlabs.ghostdrive.entropy.PhoneEntropyHarvester(context, this, dbScope)
    fun io(block: () -> Unit) {
        dbScope.launch {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("GhostDrive", "Background database work failed", e)
            }
        }
    }
    private val settingsStore = SettingsStore(context)
    val settings = MutableStateFlow(settingsStore.load())
    val voice = VoiceCopilot(context, this)
    val live = MutableStateFlow(LiveState(entropyVault = runCatching { db.entropySummary() }.getOrDefault(EntropyVaultSummary())))
    val trips = MutableStateFlow(db.trips())
    val places = MutableStateFlow(db.places())
    val logs = MutableStateFlow(db.logs())
    val deviceId: String = context.getSharedPreferences("identity", Context.MODE_PRIVATE).run {
        getString("device_id", null) ?: UUID.randomUUID().toString().also { edit().putString("device_id", it).apply() }
    }
    fun saveSettings(s: Settings) {
        val stamped = s.copy(updatedAtMs = System.currentTimeMillis())
        settings.value = stamped
        io { settingsStore.save(stamped) }
        log("INFO", "Settings", "Preferences saved.")
    }
    fun applySyncedSettings(s: Settings) {
        if (s != settings.value) {
            settings.value = s
            io { settingsStore.save(s) }
        }
    }
    fun log(level: String, category: String, message: String) {
        io {
            db.log(level, category, message)
            logs.value = db.logs()
        }
    }
    fun refresh() {
        io {
            val t = db.trips()
            val p = db.places()
            val q = db.queueSize()
            val v = runCatching { db.entropySummary() }.getOrNull()
            trips.value = t
            places.value = p
            live.update { it.copy(queueCount = q, entropyVault = v ?: it.entropyVault) }
        }
    }
    fun savePlace(p: Place) {
        io {
            db.savePlace(p)
            trips.value = db.trips()
            places.value = db.places()
            db.log("INFO", "Places", "Saved ${p.name} with a ${p.radiusM.toInt()} m recognition area.")
            logs.value = db.logs()
        }
    }
    fun deletePlace(p: Place) {
        io {
            db.deletePlace(p.id)
            places.value = db.places()
            db.log("INFO", "Places", "Removed ${p.name}.")
            logs.value = db.logs()
        }
    }
    fun event(type: String, payload: JSONObject): JSONObject = JSONObject().put("schema_version", 1).put("event_id", UUID.randomUUID().toString())
        .put("device_id", deviceId).put("device_model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
        .put("vehicle", settings.value.vehicle).put("driver", settings.value.driver)
        .put("captured_at_ms", System.currentTimeMillis()).put("type", type).put("payload", payload)
    fun queue(type: String, payload: JSONObject, kind: String = "history") {
        val evt = event(type, payload)
        io {
            if (!db.enqueue(kind, evt)) {
                live.update { it.copy(syncStatus = "Telemetry queue full; new telemetry is not queued. Trip records are preserved.") }
            }
            val q = db.queueSize()
            live.update { it.copy(queueCount = q) }
        }
    }
    fun record(envelope: Envelope, demo: Boolean = false) {
        val m = envelope.message; val now = System.currentTimeMillis(); val limit = settings.value.graphSamples
        live.update { old ->
            val new = old.copy(sequence = envelope.sequence, nowMs = now, demo = demo)
            when(m) {
                is Message.Progress -> new.copy(sources = old.sources + (m.id to old.sources.getValue(m.id).copy(health = m.health, filled = m.filled, needed = m.needed, updatedMs = now)))
                is Message.Source -> {
                    val r = m.report; val previous = old.sources.getValue(r.id)
                    val sample = GraphSample(now, r.raw?.shannon, r.raw?.minimum, r.conditioned?.shannon, r.conditioned?.minimum)
                    new.copy(sources = old.sources + (r.id to previous.copy(report = r, health = r.health, reportMs = now, updatedMs = now, graph = (previous.graph + sample).takeLast(limit))))
                }
                is Message.Pool -> new.copy(pool = m.report, poolUpdatedMs = now,
                    poolGraph = (old.poolGraph + GraphSample(now, m.report.shannon, m.report.minimum)).takeLast(limit))
                is Message.WifiSummary -> new.copy(wifi = m)
                is Message.WifiMotion -> new.copy(wifiMotion = m)
                is Message.RadarSummary -> new.copy(radar = m)
                is Message.Diagnostic -> new.copy(diagnostic = m)
                is Message.Boot -> new.copy(bootId = m.bootId)
            }
        }
        if (demo) return
        val watchSnap = live.value.watch
        io {
            val data = m.json().put("boot_id", envelope.bootId).put("packet_sequence", envelope.sequence)
                .put("received_at_ms", now).put("firmware_packet_base64", Base64.encodeToString(envelope.signedBytes, Base64.NO_WRAP))
            if (m is Message.Source || m is Message.Pool || m is Message.WifiMotion || m is Message.RadarSummary) db.metric(data)
            // Include environmental observations for the later web mirror; high-rate progress stays live-only.
            if (m !is Message.Progress) {
                db.enqueue("history", event("telemetry", data))
            }
            if (m is Message.Pool && m.report.health == Health.Accepted) {
                val phoneBlock = if (settings.value.phoneEntropyEnabled) phoneHarvester.drainConditionedBlock() else null
                val fused = db.fuseAndSaveEntropyBlock(envelope.bootId, m.report, watchSnap, phoneBlock)
                val summary = db.entropySummary()
                live.update {
                    it.copy(
                        entropyVault = summary,
                        pool = m.report.copy(digest = fused.finalDigest, sourceMask = fused.finalSourceMask)
                    )
                }
                voice.onEntropyBlockHarvested(summary.blocks)
                data.put("conditioned_digest_hex", fused.finalDigest.hex())
                data.put("accepted_sources_mask", fused.finalSourceMask)
                data.put("nullvalon_digest_hex", fused.nullvalonHex)
                fused.watchFoldedHex?.let { data.put("watch_digest_hex", it) }
                data.put("watch_blocks_folded", fused.watchBlocksFolded)
                data.put("offline_span_ms", fused.offlineSpanMs)
                fused.phoneHex?.let {
                    data.put("phone_digest_hex", it)
                    data.put("phone_sources_mask", fused.phoneSourcesMask)
                    data.put("phone_min_entropy", fused.phoneMinEntropy ?: 0.0)
                    data.put("phone_shannon", fused.phoneShannon ?: 0.0)
                }
                fused.hrBpm?.let { data.put("hr_bpm", it.toDouble()) }
                fused.avgHrBpm?.let { data.put("avg_hr_bpm", it.toDouble()) }
                fused.minHrBpm?.let { data.put("min_hr_bpm", it.toDouble()) }
                fused.maxHrBpm?.let { data.put("max_hr_bpm", it.toDouble()) }
                fused.avgIbiMs?.let { data.put("avg_ibi_ms", it) }
                fused.spo2Pct?.let { data.put("spo2_pct", it) }
                fused.avgSpo2Pct?.let { data.put("avg_spo2_pct", it) }
                fused.wristG?.let { data.put("wrist_g", it.toDouble()) }
                fused.avgWristG?.let { data.put("avg_wrist_g", it.toDouble()) }
                fused.pressureHpa?.let { data.put("pressure_hpa", it.toDouble()) }
                fused.avgPressureHpa?.let { data.put("avg_pressure_hpa", it.toDouble()) }

                val parts = mutableListOf<String>()
                parts.add("Nullvalon win #${m.report.windowId}")
                if (fused.watchBlocksFolded > 0) {
                    val spanMins = fused.offlineSpanMs / 60_000L
                    val spanDesc = if (spanMins > 0) " (${spanMins}m span · Avg HR ${fused.avgHrBpm?.toInt() ?: "—"} BPM)" else ""
                    parts.add("Watch5 (${fused.watchBlocksFolded} blk$spanDesc)")
                }
                if (fused.phoneHex != null) {
                    parts.add("Phone EntropicDroid")
                }
                if (fused.watchBlocksFolded > 0 || fused.phoneHex != null) {
                    db.log(
                        "INFO",
                        "Entropy",
                        "Tri-Fusion Sponge: combined [${parts.joinToString(" + ")}] → ${fused.finalDigest.hex().take(24)}…"
                    )
                    logs.value = db.logs()
                }
                db.enqueue("entropy", event("conditioned_entropy", data))
                if (fused.watchBlocksFolded > 0 || fused.phoneHex != null) {
                    db.enqueue("history", event("entropy_fused_summary", data))
                }
            }
            if (m is Message.Source) {
                val r = m.report
                db.log(if(r.health == Health.Quarantined) "WARN" else "INFO", "Entropy", "${r.id.label}: ${r.health.label}; window ${r.windowId}; accepted ${r.accepted} B, rejected ${r.rejected} B.")
                logs.value = db.logs()
            }
            if (m !is Message.Progress) {
                live.update { it.copy(queueCount = db.queueSize()) }
            }
        }
    }

    fun updateWatchStatus(status: String, connected: Boolean) {
        live.update { it.copy(watch = it.watch.copy(connected = connected, status = status, updatedMs = System.currentTimeMillis())) }
    }

    fun updateWatchLiveMetrics(
        hrBpm: Float?,
        ibiMs: Int?,
        spo2Pct: Int?,
        wristG: Float?,
        pressureHpa: Float?,
        steps: Int,
        gpsSpeedMps: Float?,
        shannon: Float?,
        minEntropy: Float?
    ) {
        live.update { old ->
            old.copy(
                watch = old.watch.copy(
                    connected = true,
                    hrBpm = hrBpm ?: old.watch.hrBpm,
                    ibiMs = ibiMs ?: old.watch.ibiMs,
                    spo2Pct = spo2Pct ?: old.watch.spo2Pct,
                    wristG = wristG ?: old.watch.wristG,
                    pressureHpa = pressureHpa ?: old.watch.pressureHpa,
                    steps = if (steps > 0) steps else old.watch.steps,
                    gpsSpeedMps = gpsSpeedMps ?: old.watch.gpsSpeedMps,
                    shannon = shannon ?: old.watch.shannon,
                    minEntropy = minEntropy ?: old.watch.minEntropy,
                    updatedMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun ingestWatchStagingBlock(block: WatchStagingBlock, gpsSpeedMps: Float? = null) {
        io {
            val inserted = db.stageWatchBlock(block)
            val isNullvalonReady = live.value.authenticated
            val updatedWatch = live.value.watch.copy(
                connected = true,
                hrBpm = block.hrBpm ?: live.value.watch.hrBpm,
                ibiMs = block.ibiMs ?: live.value.watch.ibiMs,
                spo2Pct = block.spo2Pct ?: live.value.watch.spo2Pct,
                wristG = block.wristG ?: live.value.watch.wristG,
                pressureHpa = block.pressureHpa ?: live.value.watch.pressureHpa,
                steps = if (block.steps > 0) block.steps else live.value.watch.steps,
                gpsSpeedMps = gpsSpeedMps ?: live.value.watch.gpsSpeedMps,
                shannon = block.shannon,
                minEntropy = block.minimum,
                updatedMs = block.timeMs,
                lastWatchDigestHex = block.watchDigestHex
            )
            if (inserted) {
                val payload = JSONObject()
                    .put("type", "watch_telemetry")
                    .put("time_ms", block.timeMs)
                    .put("watch_digest_hex", block.watchDigestHex)
                    .put("nullvalon_online", isNullvalonReady)
                    .put("accepted_sources_mask", WatchProtocol.SOURCE_MASK_WATCH_BIO)
                    .put("shannon", block.shannon.toDouble())
                    .put("minimum", block.minimum.toDouble())
                    .put("steps", block.steps)
                block.hrBpm?.let { payload.put("hr_bpm", it.toDouble()) }
                block.ibiMs?.let { payload.put("ibi_ms", it) }
                block.spo2Pct?.let { payload.put("spo2_pct", it) }
                block.wristG?.let { payload.put("wrist_g", it.toDouble()) }
                block.pressureHpa?.let { payload.put("pressure_hpa", it.toDouble()) }
                gpsSpeedMps?.let { payload.put("gps_speed_mps", it.toDouble()) }

                db.metric(payload)
                // Only enqueue immediately when driving (Nullvalon online); when not driving, accumulate & average until Nullvalon connects!
                if (isNullvalonReady) {
                    db.enqueue("history", event("telemetry", payload))
                    db.enqueue("entropy", event("watch_staging", payload))
                }
            }
            val summary = db.entropySummary()
            val spanMins = summary.pendingSpanMs / 60_000L
            val statusMsg = if (isNullvalonReady) {
                "🚗 Driving · Live BT Watch 5 fusion with Nullvalon active"
            } else {
                "Averaging Over Time (${summary.pendingWatchBlocks}w · ${spanMins}m span) · sends when Nullvalon connects"
            }
            live.update { old ->
                old.copy(
                    entropyVault = summary,
                    watch = updatedWatch.copy(status = statusMsg),
                    queueCount = db.queueSize()
                )
            }
            if (inserted) {
                db.log(
                    "INFO",
                    "Watch",
                    "Conditioned & staged Galaxy Watch 5 block (${summary.pendingWatchBlocks} queued for Nullvalon fusion · HR ${block.hrBpm?.toInt() ?: "—"} BPM · IBI ${block.ibiMs ?: "—"} ms · SpO2 ${block.spo2Pct ?: "—"}%)."
                )
                logs.value = db.logs()
            }
        }
    }

    /** Harvests a realistic overtime/work Watch 5 sample into the staging buffer (waiting for Nullvalon). */
    fun harvestOvertimeWatchSample(): WatchStagingBlock {
        val rng = SecureRandom()
        val noise = ByteArray(64).also { rng.nextBytes(it) }
        val prevSteps = live.value.watch.steps
        val hr = 74f + (rng.nextFloat() * 24f)
        val ibi = (60_000f / hr + (rng.nextInt(35) - 17)).toInt()
        val spo2 = 96 + rng.nextInt(4)
        val wristG = 0.98f + rng.nextFloat() * 0.45f
        val press = 994.2f + (rng.nextFloat() - 0.5f) * 1.8f
        val steps = prevSteps + 18 + rng.nextInt(32)
        val block = WatchProtocol.conditionRawWatchSample(
            hrBpm = hr,
            ibiMs = ibi,
            spo2Pct = spo2,
            wristG = wristG,
            pressureHpa = press,
            steps = steps,
            gpsSpeedMps = 1.2f + rng.nextFloat() * 0.6f,
            extraNoise = noise
        )
        ingestWatchStagingBlock(block, 1.35f)
        return block
    }
}
