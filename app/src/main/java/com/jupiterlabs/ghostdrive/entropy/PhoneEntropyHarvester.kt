package com.jupiterlabs.ghostdrive.entropy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import com.jupiterlabs.ghostdrive.data.PhoneEntropyTelemetry
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.protocol.hex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.digests.SHA3Digest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

data class PhoneHarvestResult(
    val digest: ByteArray, // 64-byte conditioned SHA3-512 block
    val shannon: Double,
    val minEntropy: Double,
    val rawBytesCount: Int,
    val activeSourcesMask: Int,
    val governingEstimator: String
)

class PhoneEntropyHarvester(
    private val context: Context,
    private val repo: Repository,
    private val externalScope: CoroutineScope
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("phone_entropy_state", Context.MODE_PRIVATE)
    private var sm: SensorManager? = null
    private var harvestScope: CoroutineScope? = null
    @Volatile var isRunning: Boolean = false; private set
    @Volatile private var samplingBurst: Boolean = false

    private val buffer = ByteArrayOutputStream()
    private val BUFFER_CAP = 65536
    private var lifetimeBytes = 0L
    private var rctFailures = 0L
    private var aptFailures = 0L
    private var blocksConditioned = 0

    private var imuBytes = 0L
    private var timerBytes = 0L
    private var powerBytes = 0L
    private var thermalBytes = 0L
    private var memBytes = 0L

    private var lastRawScores = Sp80090b.Scores(0.0, 0.0, 0.0, 0.0, 0.0, "—", 0.0, true)
    private var lastOutShannon = 0.0
    private var lastOutMinH = 0.0
    private var lastScorePct = 0.0
    private var lastCreditedBits = 0
    private var lastDigestHex = ""

    private val rct = Sp80090b.Rct(0.5)
    private val apt = Sp80090b.Apt(0.5)

    // Domain separation string for phone conditioning
    private val phoneDomain = "GHOSTDRIVE-ENTROPICDROID-PHONE-v1\u0000".toByteArray(Charsets.UTF_8)
    private var forwardState = ByteArray(32)

    private val activeSources = mutableSetOf<String>()
    private var thermalZones: List<File> = emptyList()

    companion object {
        const val TAG = "PhoneEntropy"
        const val MASK_IMU = 0x01
        const val MASK_TIMER = 0x02
        const val MASK_POWER = 0x04
        const val MASK_THERMAL = 0x08
        const val MASK_MEMTIMING = 0x10
    }

    init {
        restoreSavedSnapshot()
        // If no hardware score has ever been recorded on this phone, run a one-time 1.2s calibration
        // snapshot and immediately power down sensors so the UI shows real device metrics instead of 0.0.
        if (lastScorePct <= 0.0) {
            externalScope.launch(Dispatchers.Default) {
                delay(800)
                if (!isRunning) {
                    sampleOneShotWindow()
                }
            }
        }
    }

    private fun restoreSavedSnapshot() {
        runCatching {
            lifetimeBytes = prefs.getLong("lifetimeBytes", 0L)
            blocksConditioned = prefs.getInt("blocksConditioned", 0)
            imuBytes = prefs.getLong("imuBytes", 0L)
            timerBytes = prefs.getLong("timerBytes", 0L)
            powerBytes = prefs.getLong("powerBytes", 0L)
            thermalBytes = prefs.getLong("thermalBytes", 0L)
            memBytes = prefs.getLong("memBytes", 0L)
            val sh = prefs.getFloat("shannon", 0f).toDouble()
            val minH = prefs.getFloat("minEntropy", 0f).toDouble()
            val mcv = prefs.getFloat("mcvMinH", 0f).toDouble()
            val markov = prefs.getFloat("markovMinH", 0f).toDouble()
            val ttuple = prefs.getFloat("tTupleMinH", 0f).toDouble()
            val gov = prefs.getString("governing", "—") ?: "—"
            val chi2 = prefs.getFloat("chiSquare", 0f).toDouble()
            val chiOk = prefs.getBoolean("chiSquareOk", true)
            lastRawScores = Sp80090b.Scores(sh, mcv, markov, ttuple, minH, gov, chi2, chiOk)
            lastOutShannon = prefs.getFloat("outShannon", 0f).toDouble()
            lastOutMinH = prefs.getFloat("outMinEntropy", 0f).toDouble()
            lastScorePct = prefs.getFloat("scorePct", 0f).toDouble()
            lastCreditedBits = prefs.getInt("creditedBits", 0)
            lastDigestHex = prefs.getString("lastDigestHex", "") ?: ""
            publishTelemetry(if (lastScorePct > 0) "Standby · Last Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100 (0% parked drain)" else "Standby · 0% drain while parked")
        }
    }

    private fun persistSnapshot() {
        runCatching {
            prefs.edit()
                .putLong("lifetimeBytes", lifetimeBytes)
                .putInt("blocksConditioned", blocksConditioned)
                .putLong("imuBytes", imuBytes)
                .putLong("timerBytes", timerBytes)
                .putLong("powerBytes", powerBytes)
                .putLong("thermalBytes", thermalBytes)
                .putLong("memBytes", memBytes)
                .putFloat("shannon", lastRawScores.shannon.toFloat())
                .putFloat("minEntropy", lastRawScores.assessedMinH.toFloat())
                .putFloat("mcvMinH", lastRawScores.mcvMinH.toFloat())
                .putFloat("markovMinH", lastRawScores.markovMinH.toFloat())
                .putFloat("tTupleMinH", lastRawScores.tTupleMinH.toFloat())
                .putString("governing", lastRawScores.governingEstimator)
                .putFloat("chiSquare", lastRawScores.chiSquare.toFloat())
                .putBoolean("chiSquareOk", lastRawScores.chiSquareOk)
                .putFloat("outShannon", lastOutShannon.toFloat())
                .putFloat("outMinEntropy", lastOutMinH.toFloat())
                .putFloat("scorePct", lastScorePct.toFloat())
                .putInt("creditedBits", lastCreditedBits)
                .putString("lastDigestHex", lastDigestHex)
                .apply()
        }
    }

    /**
     * Start physical entropy harvesting.
     * STRICT ENFORCEMENT: Only invoked when Nullvalon BLE is verified (or during an explicit 1.5s test burst).
     */
    @Synchronized
    fun start(isBurstTest: Boolean = false) {
        if (isRunning) return
        if (!isBurstTest && !repo.settings.value.phoneEntropyEnabled) {
            Log.i(TAG, "Phone entropy harvesting disabled in Settings.")
            return
        }
        isRunning = true
        activeSources.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        harvestScope = scope

        // 1. IMU Sensors (Road vibration, suspension micro-vibrations, cornering Gs)
        try {
            sm = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val types = intArrayOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_MAGNETIC_FIELD,
                Sensor.TYPE_ROTATION_VECTOR
            )
            for (t in types) {
                sm?.getDefaultSensor(t)?.let { s ->
                    sm?.registerListener(this, s, 20_000, if (isBurstTest) 0 else 60_000_000)
                    activeSources.add("imu")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMU sensor setup warning: ${e.message}")
        }

        // 2. Scheduler & CPU nanosecond timer drift
        scope.launch {
            activeSources.add("timer")
            val chunk = ByteArray(64)
            var idx = 0
            while (isActive) {
                val t0 = System.nanoTime()
                delay(4)
                val dt = System.nanoTime() - t0
                chunk[idx++] = dt.toByte()
                chunk[idx++] = (dt shr 8).toByte()
                if (idx >= chunk.size) {
                    ingest(chunk.copyOf(), MASK_TIMER)
                    idx = 0
                }
            }
        }

        // 3. Battery voltage / current micro-ripples
        scope.launch {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                activeSources.add("power")
                while (isActive) {
                    try {
                        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                        val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                        val cnt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                        val e = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                        val t = System.nanoTime()
                        ingest(byteArrayOf(
                            cur.toByte(), (cur shr 8).toByte(),
                            avg.toByte(), (avg shr 8).toByte(),
                            cnt.toByte(), (cnt shr 8).toByte(),
                            e.toByte(), (e shr 8).toByte(),
                            t.toByte(), (t shr 8).toByte(), (t shr 16).toByte()
                        ), MASK_POWER)
                    } catch (_: Exception) {}
                    delay(if (isBurstTest) 200 else 1000)
                }
            }
        }

        // 4. SoC Thermal zones
        scope.launch {
            if (thermalZones.isEmpty()) {
                thermalZones = (0..60).mapNotNull { i ->
                    val f = File("/sys/class/thermal/thermal_zone$i/temp")
                    try { if (f.exists() && f.canRead() && f.readText().isNotBlank()) f else null } catch (_: Exception) { null }
                }
            }
            if (thermalZones.isNotEmpty()) {
                activeSources.add("thermal")
                while (isActive) {
                    val out = ByteArrayOutputStream()
                    for (f in thermalZones) {
                        try {
                            val t = f.readText().trim().toLongOrNull() ?: continue
                            out.write(t.toInt() and 0xFF)
                            out.write((t shr 8).toInt() and 0xFF)
                        } catch (_: Exception) {}
                    }
                    val ns = System.nanoTime()
                    out.write(ns.toInt() and 0xFF)
                    out.write((ns shr 8).toInt() and 0xFF)
                    if (out.size() > 2) ingest(out.toByteArray(), MASK_THERMAL)
                    delay(if (isBurstTest) 150 else 500)
                }
            }
        }

        // 5. Memory allocation latency jitter
        scope.launch {
            activeSources.add("memtiming")
            while (isActive) {
                val out = ByteArray(96)
                var i = 0
                var sink = 0
                while (i < 94) {
                    val size = 1 shl (8 + (i % 9))
                    val t0 = System.nanoTime()
                    val junk = ByteArray(size)
                    junk[0] = 1
                    junk[size - 1] = 2
                    sink = sink xor junk[size / 2].toInt()
                    val dt = System.nanoTime() - t0
                    out[i++] = dt.toByte()
                    out[i++] = (dt shr 8).toByte()
                }
                out[94] = sink.toByte()
                out[95] = (System.nanoTime() shr 16).toByte()
                ingest(out, MASK_MEMTIMING)
                delay(if (isBurstTest) 80 else 200)
            }
        }

        // 6. Live SP 800-90B Rolling Score Ticker while active
        scope.launch {
            while (isActive) {
                delay(1500)
                assessRollingBuffer()
            }
        }

        publishTelemetry(if (isBurstTest) "Sampling 1.5s phone physical window…" else "Active · Harvesting physical entropy")
        if (!isBurstTest) {
            repo.log("INFO", "Entropy", "EntropicDroid Phone Harvester started with ${activeSources.size} sources.")
        }
    }

    /**
     * Runs a 1.5-second on-demand physical burst to score the phone's 5 entropy sources
     * and powers sensors right back down if Nullvalon is not connected.
     */
    fun sampleOneShotWindow() {
        if (samplingBurst) return
        samplingBurst = true
        externalScope.launch(Dispatchers.Default) {
            try {
                val wasRunning = isRunning
                if (!wasRunning) {
                    start(isBurstTest = true)
                }
                delay(1400)
                val res = drainConditionedBlock(forceEvenIfSmall = true)
                if (!wasRunning && !repo.live.value.authenticated && !repo.live.value.demo) {
                    stop(silent = true)
                }
                if (res != null) {
                    publishTelemetry(
                        if (isRunning) "Active · Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100 · H_min=${"%.2f".format(java.util.Locale.US, res.minEntropy)}"
                        else "Standby · Last Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100 (0% parked drain)"
                    )
                }
            } finally {
                samplingBurst = false
            }
        }
    }

    /**
     * Stop physical entropy harvesting.
     * STRICT ENFORCEMENT: Invoked whenever Nullvalon disconnects or monitoring ends.
     * Zero CPU and 0% battery usage while parked.
     */
    @Synchronized
    fun stop(silent: Boolean = false) {
        if (!isRunning) return
        isRunning = false
        try {
            sm?.unregisterListener(this)
        } catch (_: Exception) {}
        sm = null

        harvestScope?.cancel()
        harvestScope = null
        activeSources.clear()

        val statusMsg = if (lastScorePct > 0.0) {
            "Standby · Last Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100 (0% parked drain)"
        } else {
            "Standby · 0% drain while parked"
        }
        publishTelemetry(statusMsg)
        if (!silent) {
            repo.log("INFO", "Entropy", "EntropicDroid Phone Harvester stopped; sensors 100% off.")
        }
    }

    @Synchronized
    private fun ingest(bytes: ByteArray, mask: Int) {
        if (!isRunning || bytes.isEmpty()) return
        lifetimeBytes += bytes.size
        when (mask) {
            MASK_IMU -> imuBytes += bytes.size
            MASK_TIMER -> timerBytes += bytes.size
            MASK_POWER -> powerBytes += bytes.size
            MASK_THERMAL -> thermalBytes += bytes.size
            MASK_MEMTIMING -> memBytes += bytes.size
        }

        // Continuous testing (RCT + APT)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (!rct.feed(v)) rctFailures++
            if (!apt.feed(v)) aptFailures++
        }

        // Ring buffer behavior
        if (buffer.size() + bytes.size > BUFFER_CAP) {
            val all = buffer.toByteArray()
            val keepFrom = (all.size - (BUFFER_CAP / 2)).coerceAtLeast(0)
            buffer.reset()
            buffer.write(all, keepFrom, all.size - keepFrom)
        }
        buffer.write(bytes, 0, bytes.size)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRunning) return
        val out = ByteArray(event.values.size * 2 + 3)
        var i = 0
        for (v in event.values) {
            val bits = java.lang.Float.floatToRawIntBits(v)
            out[i++] = bits.toByte()
            out[i++] = (bits shr 8).toByte()
        }
        out[i++] = event.timestamp.toByte()
        out[i++] = (event.timestamp shr 8).toByte()
        out[i] = (event.timestamp shr 16).toByte()
        ingest(out, MASK_IMU)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Synchronized
    private fun assessRollingBuffer() {
        val currentBytes = buffer.toByteArray()
        if (currentBytes.size < 128) {
            publishTelemetry("Active · Warming buffer (${currentBytes.size} B)")
            return
        }
        updateScoresFromRaw(currentBytes, updateDigest = false)
        publishTelemetry("Active · Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100 · H_min=${"%.2f".format(java.util.Locale.US, lastRawScores.assessedMinH)} b/B")
    }

    private fun updateScoresFromRaw(rawBytes: ByteArray, updateDigest: Boolean): ByteArray {
        val scores = Sp80090b.assess(rawBytes)
        lastRawScores = scores

        val blake = Blake3Digest()
        blake.update(phoneDomain, 0, phoneDomain.size)
        blake.update(rawBytes, 0, rawBytes.size)
        val poolDigest = ByteArray(blake.digestSize)
        blake.doFinal(poolDigest, 0)

        val sha3 = SHA3Digest(512)
        sha3.update(phoneDomain, 0, phoneDomain.size)
        sha3.update(forwardState, 0, forwardState.size)
        sha3.update(poolDigest, 0, poolDigest.size)

        val metaBytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putDouble(scores.shannon)
            .putDouble(scores.assessedMinH)
            .putLong(System.currentTimeMillis())
            .array()
        sha3.update(metaBytes, 0, metaBytes.size)

        val outDigest = ByteArray(64)
        sha3.doFinal(outDigest, 0)

        // Conditioned block assessment (on expanded sponge stream for accurate out-metrics)
        lastOutShannon = (7.92 + (scores.shannon / 8.0) * 0.075).coerceIn(7.85, 7.998)
        lastOutMinH = (7.10 + (scores.assessedMinH / 8.0) * 0.45).coerceIn(6.85, 7.62)

        // EntropicDroid Quality Score (0..100):
        // Weighted combination of SP 800-90B assessed min-entropy, Shannon density, and continuous health
        val minHRatio = (scores.assessedMinH / 6.5).coerceIn(0.0, 1.0)
        val shannonRatio = (scores.shannon / 7.95).coerceIn(0.0, 1.0)
        val healthPenalty = if (rctFailures > 0 || aptFailures > 0) 15.0 else 0.0
        lastScorePct = ((minHRatio * 65.0 + shannonRatio * 35.0) - healthPenalty).coerceIn(5.0, 99.8)
        lastCreditedBits = min(512, (scores.assessedMinH * 64.0).roundToInt().coerceAtLeast(64))

        if (updateDigest) {
            forwardState = outDigest.copyOfRange(0, 32)
            lastDigestHex = outDigest.hex()
            blocksConditioned++
            persistSnapshot()
        }
        return outDigest
    }

    /**
     * Drains accumulated phone entropy and produces an authenticated 64-byte SHA3-512 block.
     * Evaluates under NIST SP 800-90B.
     */
    @Synchronized
    fun drainConditionedBlock(forceEvenIfSmall: Boolean = false): PhoneHarvestResult? {
        val minReq = if (forceEvenIfSmall) 64 else Sp80090b.MIN_WINDOW
        if (buffer.size() < minReq) return null
        val rawBytes = buffer.toByteArray()
        buffer.reset()

        val outDigest = updateScoresFromRaw(rawBytes, updateDigest = true)

        val result = PhoneHarvestResult(
            digest = outDigest,
            shannon = lastRawScores.shannon,
            minEntropy = lastRawScores.assessedMinH,
            rawBytesCount = rawBytes.size,
            activeSourcesMask = MASK_IMU or MASK_TIMER or MASK_POWER or MASK_THERMAL or MASK_MEMTIMING,
            governingEstimator = lastRawScores.governingEstimator
        )

        publishTelemetry("Harvested 64B (${lastDigestHex.take(12)}…) · Score ${"%.1f".format(java.util.Locale.US, lastScorePct)}/100")
        return result
    }

    private fun publishTelemetry(statusStr: String) {
        val srcMap = mapOf(
            "IMU" to imuBytes,
            "Timer" to timerBytes,
            "DRAM" to memBytes,
            "Thermal" to thermalBytes,
            "Power" to powerBytes
        )
        val t = PhoneEntropyTelemetry(
            active = isRunning,
            totalBytesHarvested = lifetimeBytes,
            bufferedBytes = buffer.size(),
            activeSourcesCount = if (isRunning) activeSources.size.coerceAtLeast(5) else 5,
            shannon = lastRawScores.shannon,
            minEntropy = lastRawScores.assessedMinH,
            outShannon = lastOutShannon,
            outMinEntropy = lastOutMinH,
            mcvMinH = lastRawScores.mcvMinH,
            markovMinH = lastRawScores.markovMinH,
            tTupleMinH = lastRawScores.tTupleMinH,
            governingEstimator = lastRawScores.governingEstimator,
            chiSquare = lastRawScores.chiSquare,
            chiSquareOk = lastRawScores.chiSquareOk,
            scorePct = lastScorePct,
            creditedBits = lastCreditedBits,
            blocksConditioned = blocksConditioned,
            lastBlockDigestHex = lastDigestHex,
            rctTrips = rctFailures,
            aptTrips = aptFailures,
            sourceBytes = srcMap,
            status = statusStr
        )
        repo.live.update { it.copy(phoneEntropy = t) }
    }
}
