package com.jupiterlabs.ghostdrive.data

import android.os.SystemClock
import com.jupiterlabs.ghostdrive.protocol.*
import com.jupiterlabs.ghostdrive.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlin.math.cos
import kotlin.math.sin

/** UI-only synthetic telemetry. Never starts GPS, authenticates a peer, or enters the upload queues. */
class DemoController(private val repo: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var demoTrip: Trip? = null
    fun start() {
        if(job != null || repo.live.value.armed) return
        repo.live.value = LiveState(bleStatus = "SIMULATOR · synthetic data", demo = true, syncStatus = "Simulator never uploads", entropyVault = repo.db.entropySummary(), phoneEntropy = repo.live.value.phoneEntropy)
        repo.phoneHarvester.start(isBurstTest = true)
        repo.log("INFO", "Demo", "Simulator started. All metrics and route points are synthetic; GPS and upload are off.")
        val now = System.currentTimeMillis()
        val initialTrip = Trip(startMs = now, bootId = 56, demo = true, startName = "Demo home", endName = "Demo drive", vehicle = repo.settings.value.vehicle)
        demoTrip = initialTrip
        repo.io { repo.db.saveTrip(initialTrip) }
        repo.refresh()
        repo.voice.onSessionStart(repo.settings.value.vehicle, isSimulator = true)
        job = scope.launch {
            var tick = 0L
            while(isActive) {
                tick++; val phase = tick.toDouble() / 5
                for(id in SourceId.entries) {
                    val h = if(id == SourceId.HardwareRng) 7.93f else (1.4 + id.ordinal * .55 + sin(phase + id.ordinal) * .3).toFloat()
                    val stats = Stats(8192, "byte_0_255", h, (h - .75f).coerceAtLeast(.2f), .72f, 265f, 255, .31f, 3, true, 8, true)
                    val conditioned = stats.copy(samples = 4096, shannon = 7.94f, minimum = 6.98f)
                    val report = SourceReport(id, "synthetic_demo", Health.Accepted, tick, tick * 1000,
                        stats, conditioned, tick * 8192, tick * 8192, 0, 0, true)
                    repo.record(Envelope(56, tick, Message.Source(report), ByteArray(0)), demo = true)
                }
                val demoDigest = ByteArray(64) { i -> ((tick * 31 + i * 17 + System.nanoTime()) and 0xff).toByte() }
                val poolReport = PoolReport(Health.Accepted, tick, demoDigest, 7.95f, 7.01f, .91f,
                    260f, 255, .4f, 127, SourceId.HardwareRng, 8192, 0)
                if (repo.live.value.entropyVault.pendingWatchBlocks > 0) {
                    val watchSnap = repo.live.value.watch
                    repo.io {
                        repo.db.fuseAndSaveEntropyBlock(56, poolReport, watchSnap)
                        val sum = repo.db.entropySummary()
                        repo.live.update { it.copy(entropyVault = sum) }
                    }
                }
                repo.record(Envelope(56, tick, Message.Pool(poolReport), ByteArray(0)), demo = true)
                repo.record(Envelope(56, tick, Message.WifiMotion((50 + sin(phase) * 30).toInt(), 8, 10, 12), ByteArray(0)), true)
                repo.record(Envelope(56, tick, Message.WifiSummary(12, -62, 1720), ByteArray(0)), true)
                repo.record(Envelope(56, tick, Message.RadarSummary(true, 3, 42, 28, 100, 75, 105), ByteArray(0)), true)
                // Realistic multi-tier speed profile (city -> arterial -> highway -> arterial)
                val speedWave = (sin(phase * 0.65) + 1.0) * 0.5 // 0.0 .. 1.0
                val speedSim = (6.0 + speedWave * 24.0).toFloat() // 6 m/s (13 mph) up to 30 m/s (67 mph)
                val original = demoTrip!!
                val prev = original.end
                val headingRad = (phase * 0.18) + sin(phase * 0.35) * 0.45
                val stepDegLat = (cos(headingRad) * speedSim * 0.000009)
                val stepDegLon = (sin(headingRad) * speedSim * 0.000012)
                val newLat = (prev?.latitude ?: 44.4748) + stepDegLat
                val newLon = (prev?.longitude ?: -88.0690) + stepDegLon
                val simEntropy = (6.1f + ((sin(phase * 0.85) + 1.0) * 0.94).toFloat()).coerceIn(5.8f, 7.98f)
                val simRf = (50 + sin(phase) * 38).toInt().coerceIn(5, 98)
                val simRadar = (40 + cos(phase * 1.2) * 35).toInt().coerceIn(5, 95)
                val tempFix = Fix(newLat, newLon, System.currentTimeMillis(), SystemClock.elapsedRealtime(), 6f, speedSim, null,
                    entropyShannon = simEntropy, rfScore = simRf, radarEnergy = simRadar)
                val computedBearing = if (prev != null) Geo.bearing(prev.latitude, prev.longitude, tempFix.latitude, tempFix.longitude) else 45f
                val fix = tempFix.copy(bearingDeg = computedBearing ?: prev?.bearingDeg ?: 45f)
                val updatedTrip = original.copy(start = original.start ?: fix, end = fix, durationMs = tick * 1000, movingMs = tick * 1000,
                    distanceM = original.distanceM + speedSim, maxSpeedMps = maxOf(original.maxSpeedMps, speedSim))
                demoTrip = updatedTrip
                repo.live.update { it.copy(trip = updatedTrip, lastFix = fix, nowMs = System.currentTimeMillis()) }
                repo.io { repo.db.saveTrip(updatedTrip); repo.db.addFix(original.id, fix) }
                delay(1000)
            }
        }
    }
    fun stop() {
        job?.cancel(); job = null
        repo.phoneHarvester.stop(silent = true)
        demoTrip?.let {
            val closed = it.copy(endMs = System.currentTimeMillis(), endReason = "Demo ended")
            repo.io { repo.db.saveTrip(closed) }
            repo.voice.onSessionStop(it.endName, it.distanceM, repo.settings.value.displayUnits, isSimulator = true)
        }
        demoTrip = null
        repo.live.update { LiveState(entropyVault = it.entropyVault, phoneEntropy = it.phoneEntropy) }
        repo.refresh()
        repo.log("INFO", "Demo", "Simulator stopped.")
    }
}
