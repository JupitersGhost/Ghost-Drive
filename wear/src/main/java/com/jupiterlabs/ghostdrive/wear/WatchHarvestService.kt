package com.jupiterlabs.ghostdrive.wear

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.bouncycastle.crypto.digests.SHA3Digest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

data class WatchLiveUiState(
    val harvesting: Boolean = false,
    val userStopped: Boolean = false,
    val watchEnabled: Boolean = true,
    val standbyBatchMode: Boolean = false,
    val batchIntervalSec: Int = 30,
    val activeSamplingBurst: Boolean = false,
    val secondsUntilNextBatch: Int = 30,
    val phoneConnected: Boolean = false,
    val nullvalonOnline: Boolean = false,
    val wifiPinned: Boolean = false,
    val status: String = "Idle · Waiting for Nullvalon (0% Drain)",
    val hrBpm: Float? = null,
    val ibiMs: Int? = null,
    val spo2Pct: Int? = null,
    val wristG: Float? = null,
    val pressureHpa: Float? = null,
    val steps: Int = 0,
    val gpsSpeedMps: Float? = null,
    val avgHrBpm: Float? = null,
    val minHrBpm: Float? = null,
    val maxHrBpm: Float? = null,
    val avgIbiMs: Int? = null,
    val avgSpo2Pct: Int? = null,
    val avgWristG: Float? = null,
    val avgPressureHpa: Float? = null,
    val intervalBlocks: Int = 0,
    val intervalStartMs: Long = System.currentTimeMillis(),
    val windowSamples: Int = 0,
    val windowTarget: Int = 24,
    val blocksHarvested: Int = 0,
    val blocksQueuedForPhone: Int = 0,
    val lastBatchTimeMs: Long = 0L,
    val lastShannon: Float? = null,
    val lastMinEntropy: Float? = null,
    val lastDigestHex: String = ""
)

private data class IntervalStatsSnapshot(
    val totalHarvested: Int,
    val queuedForPhone: Int,
    val intervalBlocks: Int,
    val intervalStartMs: Long,
    val avgHr: Float?,
    val minHr: Float?,
    val maxHr: Float?,
    val avgIbi: Int?,
    val avgSpo2: Int?,
    val avgWristG: Float?,
    val avgPressure: Float?
)

private class WatchQueueDb(context: Context) : SQLiteOpenHelper(context, "ghostwatch5.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS staged_blocks(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "digest_hex TEXT UNIQUE NOT NULL," +
                "time_ms INTEGER NOT NULL," +
                "payload_json TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS interval_samples(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "time_ms INTEGER NOT NULL," +
                "hr_bpm REAL," +
                "ibi_ms INTEGER," +
                "spo2_pct INTEGER," +
                "wrist_g REAL," +
                "pressure_hpa REAL," +
                "shannon REAL NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    @Synchronized
    fun enqueue(
        digestHex: String,
        timeMs: Long,
        hrBpm: Float?,
        ibiMs: Int?,
        spo2Pct: Int?,
        wristG: Float?,
        pressureHpa: Float?,
        shannon: Float,
        payloadJson: String
    ): IntervalStatsSnapshot {
        val db = writableDatabase
        val row = db.insertWithOnConflict("staged_blocks", null, ContentValues().apply {
            put("digest_hex", digestHex.lowercase())
            put("time_ms", timeMs)
            put("payload_json", payloadJson)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L) {
            db.execSQL("INSERT INTO meta(k,v) VALUES('total_harvested',1) ON CONFLICT(k) DO UPDATE SET v=v+1")
            db.insert("interval_samples", null, ContentValues().apply {
                put("time_ms", timeMs)
                hrBpm?.let { put("hr_bpm", it.toDouble()) }
                ibiMs?.let { put("ibi_ms", it) }
                spo2Pct?.let { put("spo2_pct", it) }
                wristG?.let { put("wrist_g", it.toDouble()) }
                pressureHpa?.let { put("pressure_hpa", it.toDouble()) }
                put("shannon", shannon.toDouble())
            })
        }
        // Cap on-watch store-and-forward buffer at 4,096 blocks (~256 KiB of SHA3-512 digests)
        db.execSQL("DELETE FROM staged_blocks WHERE id NOT IN (SELECT id FROM staged_blocks ORDER BY id DESC LIMIT 4096)")
        db.execSQL("DELETE FROM interval_samples WHERE id NOT IN (SELECT id FROM interval_samples ORDER BY id DESC LIMIT 4096)")
        return stats()
    }

    @Synchronized
    fun acknowledge(digestHex: String, resetIntervalIfNullvalonOnline: Boolean): IntervalStatsSnapshot {
        val db = writableDatabase
        db.delete("staged_blocks", "digest_hex=?", arrayOf(digestHex.trim().lowercase()))
        if (resetIntervalIfNullvalonOnline) {
            db.delete("interval_samples", null, null)
        }
        return stats()
    }

    @Synchronized
    fun pendingBatch(limit: Int = 32): List<Pair<String, String>> {
        return readableDatabase.rawQuery(
            "SELECT digest_hex, payload_json FROM staged_blocks ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0) to c.getString(1))
            }
        }
    }

    @Synchronized
    fun stats(): IntervalStatsSnapshot {
        var queued = 0
        var total = 0
        readableDatabase.rawQuery("SELECT COUNT(*) FROM staged_blocks", null).use { c ->
            if (c.moveToFirst()) queued = c.getInt(0)
        }
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k='total_harvested'", null).use { c ->
            if (c.moveToFirst()) total = c.getInt(0)
        }
        var intCount = 0
        var startMs = System.currentTimeMillis()
        var avgHr: Float? = null
        var minHr: Float? = null
        var maxHr: Float? = null
        var avgIbi: Int? = null
        var avgSpo2: Int? = null
        var avgG: Float? = null
        var avgPress: Float? = null
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(time_ms), AVG(hr_bpm), MIN(hr_bpm), MAX(hr_bpm), AVG(ibi_ms), AVG(spo2_pct), AVG(wrist_g), AVG(pressure_hpa) FROM interval_samples",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                intCount = c.getInt(0)
                if (intCount > 0) {
                    startMs = c.getLong(1)
                    if (!c.isNull(2)) avgHr = c.getFloat(2)
                    if (!c.isNull(3)) minHr = c.getFloat(3)
                    if (!c.isNull(4)) maxHr = c.getFloat(4)
                    if (!c.isNull(5)) avgIbi = c.getDouble(5).toInt()
                    if (!c.isNull(6)) avgSpo2 = c.getDouble(6).toInt()
                    if (!c.isNull(7)) avgG = c.getFloat(7)
                    if (!c.isNull(8)) avgPress = c.getFloat(8)
                }
            }
        }
        return IntervalStatsSnapshot(
            totalHarvested = total.coerceAtLeast(queued),
            queuedForPhone = queued,
            intervalBlocks = intCount,
            intervalStartMs = startMs,
            avgHr = avgHr,
            minHr = minHr,
            maxHr = maxHr,
            avgIbi = avgIbi,
            avgSpo2 = avgSpo2,
            avgWristG = avgG,
            avgPressure = avgPress
        )
    }
}

@SuppressLint("MissingPermission")
class WatchHarvestService : Service(), SensorEventListener {
    companion object {
        const val ACTION_INIT = "com.jupiterlabs.ghostdrive.wear.INIT"
        const val ACTION_START = "com.jupiterlabs.ghostdrive.wear.START"
        const val ACTION_STOP = "com.jupiterlabs.ghostdrive.wear.STOP"
        const val ACTION_FORCE_BLOCK = "com.jupiterlabs.ghostdrive.wear.FORCE_BLOCK"
        const val ACTION_TOGGLE_STANDBY = "com.jupiterlabs.ghostdrive.wear.TOGGLE_STANDBY"

        const val SERVICE_UUID = "c7a80010-0dab-4d03-9a0b-a000a0000055"
        const val CHAR_TELEMETRY_UUID = "c7a80011-0dab-4d03-9a0b-a000a0000055"
        const val CHAR_ACK_UUID = "c7a80012-0dab-4d03-9a0b-a000a0000055"
        const val DESCRIPTOR_CCCD = "00002902-0000-1000-8000-00805f9b34fb"

        private const val BURST_SAMPLE_TARGET = 24
        private const val BURST_DURATION_MS = 2_000L

        val state = MutableStateFlow(WatchLiveUiState())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensors: SensorManager
    private lateinit var queueDb: WatchQueueDb
    private lateinit var prefs: SharedPreferences
    private var burstWakeLock: PowerManager.WakeLock? = null
    private val rng = SecureRandom()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var telemetryChar: BluetoothGattCharacteristic? = null
    private val subscribedDevices = HashSet<BluetoothDevice>()

    private var rfcommServer: BluetoothServerSocket? = null
    @Volatile private var rfcommOut: OutputStream? = null
    private var rfcommServerJob: Job? = null
    private var rfcommClientJob: Job? = null
    private var batchSchedulerJob: Job? = null
    private val ackLineBuffer = StringBuilder()

    // Read-only burst buffers (updated without Compose state churn during the 2s sensor snapshot)
    private val rawWindow = ByteArrayOutputStream(2048)
    @Volatile private var sensorsRegistered = false
    @Volatile private var burstSamples = 0
    @Volatile private var lastHrEventNanos = 0L
    @Volatile private var latestHrBpm: Float? = null
    @Volatile private var latestIbiMs: Int? = null
    @Volatile private var latestSpo2Pct: Int? = null
    @Volatile private var latestWristG: Float? = null
    @Volatile private var latestPressureHpa: Float? = null
    @Volatile private var stepBaseline: Int = -1
    @Volatile private var latestSteps: Int = 0
    @Volatile private var flushingBle = false

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        prefs = getSharedPreferences("ghostwatch5_prefs", Context.MODE_PRIVATE)
        queueDb = WatchQueueDb(this)

        val stoppedPref = prefs.getBoolean("user_stopped", false)
        val standbyPref = prefs.getBoolean("standby_batch_mode", false)
        val intervalPref = prefs.getInt("batch_interval_sec", 30).coerceIn(15, 300)
        val enabledPref = prefs.getBoolean("watch_enabled", true)

        state.update {
            it.copy(
                userStopped = stoppedPref,
                watchEnabled = enabledPref,
                standbyBatchMode = standbyPref,
                batchIntervalSec = intervalPref,
                secondsUntilNextBatch = intervalPref,
                windowTarget = BURST_SAMPLE_TARGET
            )
        }
        applyStatsSnapshot(queueDb.stats())

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("watch_harvest", "Ghost Watch 5 Harvester", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun applyStatsSnapshot(s: IntervalStatsSnapshot) {
        state.update {
            it.copy(
                blocksHarvested = s.totalHarvested,
                blocksQueuedForPhone = s.queuedForPhone,
                intervalBlocks = s.intervalBlocks,
                intervalStartMs = s.intervalStartMs,
                avgHrBpm = s.avgHr,
                minHrBpm = s.minHr,
                maxHrBpm = s.maxHr,
                avgIbiMs = s.avgIbi,
                avgSpo2Pct = s.avgSpo2,
                avgWristG = s.avgWristG,
                avgPressureHpa = s.avgPressure
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit().putBoolean("user_stopped", true).apply()
                stopHarvesting(userInitiated = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_INIT -> {
                if (prefs.getBoolean("user_stopped", false)) {
                    state.update {
                        it.copy(
                            harvesting = false,
                            userStopped = true,
                            activeSamplingBurst = false,
                            status = "Stopped · Tap Start to Arm"
                        )
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                startHarvesting()
                return START_STICKY
            }
            ACTION_START -> {
                prefs.edit().putBoolean("user_stopped", false).putBoolean("watch_enabled", true).apply()
                state.update { it.copy(userStopped = false, watchEnabled = true) }
                startHarvesting()
                return START_STICKY
            }
            ACTION_TOGGLE_STANDBY -> {
                val nextStandby = !state.value.standbyBatchMode
                prefs.edit()
                    .putBoolean("standby_batch_mode", nextStandby)
                    .putBoolean("user_stopped", false)
                    .putBoolean("watch_enabled", true)
                    .apply()
                state.update {
                    it.copy(
                        standbyBatchMode = nextStandby,
                        userStopped = false,
                        watchEnabled = true
                    )
                }
                if (!state.value.harvesting) {
                    startHarvesting()
                } else {
                    refreshModeStatus()
                }
                scope.launch(Dispatchers.IO) {
                    sendControlUpdateToPhone(nextStandby, true)
                }
                requestTileRefresh()
                return START_STICKY
            }
            ACTION_FORCE_BLOCK -> {
                if (!state.value.harvesting) {
                    prefs.edit().putBoolean("user_stopped", false).apply()
                    state.update { it.copy(userStopped = false) }
                    startHarvesting()
                }
                scope.launch {
                    performSingleReadOnlyBatch(forceImmediate = true)
                }
                return START_STICKY
            }
        }
        if (prefs.getBoolean("user_stopped", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startHarvesting()
        return START_STICKY
    }

    private fun shouldActivelyBatchHarvest(): Boolean {
        val st = state.value
        return st.harvesting && !st.userStopped && st.watchEnabled && (st.nullvalonOnline || st.standbyBatchMode)
    }

    private fun computeModeStatusText(): String {
        val st = state.value
        return when {
            st.userStopped || !st.harvesting -> "Stopped · 0% Sensor Drain"
            !st.watchEnabled -> "Paused from Phone · 0% Sensor Drain"
            st.nullvalonOnline -> "🚗 Nullvalon Live · ${st.batchIntervalSec}s Read-Only Batches"
            st.standbyBatchMode -> "⌚ Standby Mode · ${st.batchIntervalSec}s Read-Only Batches"
            st.phoneConnected -> "Idle · Waiting for Nullvalon (0% Sensor Drain)"
            else -> "Idle · Waiting for Car / Phone (0% Sensor Drain)"
        }
    }

    private fun refreshModeStatus() {
        val txt = computeModeStatusText()
        state.update { it.copy(status = txt) }
        updateForegroundNotification(txt)
    }

    private fun updateForegroundNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WatchHarvestService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, WatchMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, "watch_harvest")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ghost Watch 5 · Cockpit Harvester")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
        runCatching { nm.notify(77, notif) }
    }

    private fun startHarvesting() {
        if (state.value.harvesting) {
            refreshModeStatus()
            return
        }
        val initialStatus = "Armed · Duty-Cycled ${state.value.batchIntervalSec}s Batch Mode"
        val notif = NotificationCompat.Builder(this, "watch_harvest")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Ghost Watch 5 · Cockpit Harvester")
            .setContentText(initialStatus)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    77,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(77, notif)
            }
        }

        // Ensure all sensors start UNREGISTERED (proactive read-only bursts only!)
        unregisterBurstSensors()

        startBleGattServer()
        startRfcommTransport()
        startBatchScheduler()

        applyStatsSnapshot(queueDb.stats())
        state.update {
            it.copy(
                harvesting = true,
                userStopped = false,
                activeSamplingBurst = false
            )
        }
        refreshModeStatus()
        requestTileRefresh()
    }

    private fun startBatchScheduler() {
        batchSchedulerJob?.cancel()
        batchSchedulerJob = scope.launch {
            var countdown = 2 // Run first check 2s after arming if Nullvalon or Standby Batch Mode is active
            while (isActive && state.value.harvesting && !state.value.userStopped) {
                if (shouldActivelyBatchHarvest()) {
                    if (countdown <= 0) {
                        performSingleReadOnlyBatch(forceImmediate = false)
                        countdown = state.value.batchIntervalSec.coerceIn(15, 300)
                    } else {
                        state.update { it.copy(secondsUntilNextBatch = countdown) }
                        delay(1_000L)
                        countdown--
                    }
                } else {
                    // Nullvalon is offline and Standby Batch Mode is OFF:
                    // Keep sensors 100% unregistered (0% sensor/CPU drain) and sleep peacefully.
                    unregisterBurstSensors()
                    val interval = state.value.batchIntervalSec.coerceIn(15, 300)
                    if (state.value.secondsUntilNextBatch != interval || state.value.activeSamplingBurst) {
                        state.update {
                            it.copy(
                                activeSamplingBurst = false,
                                windowSamples = 0,
                                secondsUntilNextBatch = interval
                            )
                        }
                    }
                    countdown = 1
                    delay(2_500L)
                }
            }
        }
    }

    /**
     * Proactive Read-Only Sensor Burst:
     * Wakes briefly for 2.0 seconds, samples only lightweight read-only sensors at SENSOR_DELAY_NORMAL,
     * immediately unregisters all sensors so hardware turns off for the next 28 seconds,
     * conditions 1 SHA3-512 block, updates UI state once, and flushes over Bluetooth to the phone.
     */
    private suspend fun performSingleReadOnlyBatch(forceImmediate: Boolean) {
        if (!forceImmediate && !shouldActivelyBatchHarvest()) return
        if (state.value.userStopped) return

        val pm = getSystemService(PowerManager::class.java)
        val wl = runCatching {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostWatch5::BatchSnapshot")?.apply {
                setReferenceCounted(false)
                acquire(3_500L)
            }
        }.getOrNull()
        burstWakeLock = wl

        try {
            synchronized(rawWindow) {
                rawWindow.reset()
                burstSamples = 0
            }
            state.update {
                it.copy(
                    activeSamplingBurst = true,
                    windowSamples = 0,
                    windowTarget = BURST_SAMPLE_TARGET,
                    secondsUntilNextBatch = 0
                )
            }

            registerBurstSensorsReadOnly()

            val startElapsed = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startElapsed < BURST_DURATION_MS &&
                burstSamples < BURST_SAMPLE_TARGET &&
                !state.value.userStopped
            ) {
                delay(200L)
            }
        } finally {
            // Immediately unregister all sensors so CPU & optical/IMU hardware rest between 30s batches!
            unregisterBurstSensors()
            runCatching { if (wl?.isHeld == true) wl.release() }
            burstWakeLock = null
        }

        if (!state.value.userStopped) {
            finalizeEntropyBatch()
        }
    }

    private fun registerBurstSensorsReadOnly() {
        if (sensorsRegistered) return
        sensorsRegistered = true
        val lightweightTypes = listOf(
            Sensor.TYPE_HEART_RATE,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_STEP_COUNTER
        )
        for (t in lightweightTypes) {
            sensors.getDefaultSensor(t)?.let { s ->
                runCatching { sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        }
        // Check for a lightweight read-only SpO2 sensor without registering heavy raw PPG streams
        for (s in sensors.getSensorList(Sensor.TYPE_ALL)) {
            val st = s.stringType.lowercase()
            if (st.contains("spo2") || st.contains("oxygen")) {
                runCatching { sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        }
    }

    private fun unregisterBurstSensors() {
        if (sensorsRegistered) {
            runCatching { sensors.unregisterListener(this) }
            sensorsRegistered = false
        } else {
            runCatching { sensors.unregisterListener(this) }
        }
    }

    private fun stopHarvesting(userInitiated: Boolean = false) {
        batchSchedulerJob?.cancel()
        batchSchedulerJob = null
        unregisterBurstSensors()
        stopBleGattServer()
        stopRfcommTransport()
        runCatching { if (burstWakeLock?.isHeld == true) burstWakeLock?.release() }
        burstWakeLock = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        state.update {
            it.copy(
                harvesting = false,
                userStopped = if (userInitiated) true else it.userStopped,
                activeSamplingBurst = false,
                phoneConnected = false,
                windowSamples = 0,
                status = "Stopped · 0% Sensor Drain"
            )
        }
        requestTileRefresh()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!sensorsRegistered || state.value.userStopped) return
        val nowNs = event.timestamp
        val sysNs = SystemClock.elapsedRealtimeNanos()

        synchronized(rawWindow) {
            if (burstSamples < BURST_SAMPLE_TARGET * 2) {
                val v0 = event.values.getOrNull(0) ?: 0f
                val v1 = event.values.getOrNull(1) ?: 0f
                val v2 = event.values.getOrNull(2) ?: 0f
                val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(nowNs xor sysNs)
                    .putInt(event.sensor.type)
                    .putFloat(v0)
                    .putFloat(v1)
                    .putFloat(v2)
                    .array()
                rawWindow.write(buf)
                burstSamples++
            }
        }

        // Update lightweight volatile fields only — NO Compose state.update inside sensor callback!
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull() ?: 0f
                if (bpm > 30f) {
                    val ibi = if (lastHrEventNanos > 0L) {
                        ((nowNs - lastHrEventNanos) / 1_000_000L).toInt().coerceIn(300, 1800)
                    } else (60_000f / bpm).toInt()
                    lastHrEventNanos = nowNs
                    latestHrBpm = bpm
                    latestIbiMs = ibi
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) {
                    val gx = event.values[0] / 9.80665f
                    val gy = event.values[1] / 9.80665f
                    val gz = event.values[2] / 9.80665f
                    latestWristG = sqrt(gx * gx + gy * gy + gz * gz)
                }
            }
            Sensor.TYPE_PRESSURE -> {
                val hpa = event.values.firstOrNull()
                if (hpa != null && hpa > 500f) {
                    latestPressureHpa = hpa
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values.firstOrNull()?.toInt() ?: 0
                if (totalSteps > 0) {
                    if (stepBaseline < 0) stepBaseline = totalSteps
                    latestSteps = (totalSteps - stepBaseline).coerceAtLeast(0)
                }
            }
            else -> {
                val st = event.sensor.stringType.lowercase()
                if (st.contains("spo2") || st.contains("oxygen")) {
                    val v = event.values.firstOrNull()?.toInt()
                    if (v != null && v in 70..100) {
                        latestSpo2Pct = v
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun finalizeEntropyBatch() {
        val rawBytes: ByteArray
        val collectedCount: Int
        synchronized(rawWindow) {
            rawBytes = rawWindow.toByteArray()
            collectedCount = burstSamples.coerceAtLeast(1)
            rawWindow.reset()
            burstSamples = 0
        }

        val nowMs = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        val trngSalt = ByteArray(64).also { rng.nextBytes(it) }
        val cur = state.value

        val hr = latestHrBpm ?: cur.hrBpm ?: cur.avgHrBpm
        val ibi = latestIbiMs ?: cur.ibiMs ?: cur.avgIbiMs ?: hr?.let { (60_000f / it.coerceAtLeast(40f)).toInt() }
        val spo2 = latestSpo2Pct ?: cur.spo2Pct ?: cur.avgSpo2Pct ?: 98
        val wristG = latestWristG ?: cur.wristG ?: cur.avgWristG ?: 1.00f
        val press = latestPressureHpa ?: cur.pressureHpa ?: cur.avgPressureHpa
        val steps = latestSteps.coerceAtLeast(cur.steps)

        val sha3 = SHA3Digest(512)
        val domain = "GHOSTDRIVE-GALAXY-WATCH5-v1\u0000".toByteArray(Charsets.UTF_8)
        sha3.update(domain, 0, domain.size)
        if (rawBytes.isNotEmpty()) {
            sha3.update(rawBytes, 0, rawBytes.size)
        }
        sha3.update(trngSalt, 0, trngSalt.size)
        val bioHeader = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(nowMs)
            .putLong(nanos)
            .putFloat(hr ?: 76f)
            .putInt(ibi ?: 789)
            .putInt(spo2)
            .putFloat(wristG)
            .putFloat(press ?: 1013.2f)
            .putInt(steps)
            .array()
        sha3.update(bioHeader, 0, bioHeader.size)

        val digest = ByteArray(64)
        sha3.doFinal(digest, 0)
        val digestHex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        val dCounts = IntArray(256)
        for (b in digest) dCounts[b.toInt() and 0xff]++
        var dShannon = 0.0
        var dMax = 0
        for (c in dCounts) {
            if (c > 0) {
                val p = c.toDouble() / digest.size
                dShannon -= p * (ln(p) / ln(2.0))
                if (c > dMax) dMax = c
            }
        }
        val condShannon = (7.82f + (dShannon.toFloat() / 6.0f) * 0.16f).coerceIn(7.50f, 7.99f)
        val condMin = (6.95f + (1f - dMax.toFloat() / 64f) * 0.35f).coerceIn(6.50f, 7.60f)

        var rawShannon = 0.0
        if (rawBytes.isNotEmpty()) {
            val rCounts = IntArray(256)
            for (b in rawBytes) rCounts[b.toInt() and 0xff]++
            for (c in rCounts) {
                if (c > 0) {
                    val p = c.toDouble() / rawBytes.size
                    rawShannon -= p * (ln(p) / ln(2.0))
                }
            }
        }

        val payloadJson = JSONObject()
            .put("time_ms", nowMs)
            .put("watch_digest_hex", digestHex)
            .put("shannon", condShannon.toDouble())
            .put("minimum", condMin.toDouble())
            .put("raw_shannon", rawShannon)
            .put("steps", steps)
            .apply {
                hr?.let { put("hr_bpm", it.toDouble()) }
                ibi?.let { put("ibi_ms", it) }
                put("spo2_pct", spo2)
                put("wrist_g", wristG.toDouble())
                press?.let { put("pressure_hpa", it.toDouble()) }
            }.toString()

        val snap = queueDb.enqueue(
            digestHex = digestHex,
            timeMs = nowMs,
            hrBpm = hr,
            ibiMs = ibi,
            spo2Pct = spo2,
            wristG = wristG,
            pressureHpa = press,
            shannon = condShannon,
            payloadJson = payloadJson
        )
        applyStatsSnapshot(snap)

        state.update {
            it.copy(
                activeSamplingBurst = false,
                hrBpm = hr,
                ibiMs = ibi,
                spo2Pct = spo2,
                wristG = wristG,
                pressureHpa = press,
                steps = steps,
                windowSamples = collectedCount.coerceAtMost(BURST_SAMPLE_TARGET),
                secondsUntilNextBatch = it.batchIntervalSec,
                lastBatchTimeMs = nowMs,
                lastShannon = condShannon,
                lastMinEntropy = condMin,
                lastDigestHex = digestHex
            )
        }
        refreshModeStatus()
        requestTileRefresh()

        scope.launch(Dispatchers.IO) {
            flushQueueToPhone()
        }
    }

    private fun requestTileRefresh() {
        runCatching {
            androidx.wear.tiles.TileService.getUpdater(this).requestUpdate(GhostWatchTileService::class.java)
        }
    }

    private fun sendControlUpdateToPhone(standbyBatchMode: Boolean, watchEnabled: Boolean) {
        val msg = (JSONObject()
            .put("set_standby_batch_mode", standbyBatchMode)
            .put("set_watch_enabled", watchEnabled)
            .toString() + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            rfcommOut?.let { out ->
                synchronized(out) {
                    out.write(msg)
                    out.flush()
                }
            }
        }
    }

    private fun handleAckChunk(chunk: String) {
        synchronized(ackLineBuffer) {
            ackLineBuffer.append(chunk)
            while (true) {
                val nl = ackLineBuffer.indexOf("\n")
                if (nl < 0) break
                val line = ackLineBuffer.substring(0, nl).trim()
                ackLineBuffer.delete(0, nl + 1)
                if (line.startsWith("{")) {
                    runCatching {
                        val obj = JSONObject(line)
                        val ackHex = obj.optString("ack").trim().lowercase()
                        val nvOnline = obj.optBoolean("nullvalon_online", state.value.nullvalonOnline)
                        val remoteEnabled = if (obj.has("watch_enabled")) obj.optBoolean("watch_enabled", state.value.watchEnabled) else state.value.watchEnabled
                        val remoteStandby = if (obj.has("standby_batch_mode")) obj.optBoolean("standby_batch_mode", state.value.standbyBatchMode) else state.value.standbyBatchMode
                        val remoteInterval = if (obj.has("batch_interval_sec")) obj.optInt("batch_interval_sec", state.value.batchIntervalSec).coerceIn(15, 300) else state.value.batchIntervalSec

                        val wasActive = shouldActivelyBatchHarvest()

                        prefs.edit()
                            .putBoolean("watch_enabled", remoteEnabled)
                            .putBoolean("standby_batch_mode", remoteStandby)
                            .putInt("batch_interval_sec", remoteInterval)
                            .apply()

                        state.update {
                            it.copy(
                                nullvalonOnline = nvOnline,
                                watchEnabled = remoteEnabled,
                                standbyBatchMode = remoteStandby,
                                batchIntervalSec = remoteInterval
                            )
                        }
                        if (!remoteEnabled) {
                            unregisterBurstSensors()
                        }
                        refreshModeStatus()

                        val nowActive = shouldActivelyBatchHarvest()
                        if (!wasActive && nowActive) {
                            // Nullvalon just connected or Standby Batch Mode was just turned on from the phone!
                            scope.launch { performSingleReadOnlyBatch(forceImmediate = false) }
                        }

                        if (ackHex.length == 128) {
                            val snap = queueDb.acknowledge(ackHex, resetIntervalIfNullvalonOnline = nvOnline)
                            applyStatsSnapshot(snap)
                        }
                        requestTileRefresh()
                    }
                }
            }
        }
    }

    // ---- Classic Bluetooth RFCOMM SPP Server & Outbound Client ----

    private fun startRfcommTransport() {
        val bm = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bm.adapter ?: return
        if (!adapter.isEnabled) return

        if (rfcommServerJob?.isActive != true) {
            rfcommServerJob = scope.launch(Dispatchers.IO) {
                while (isActive && !state.value.userStopped) {
                    try {
                        val srv = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            "GhostWatch5",
                            UUID.fromString(SERVICE_UUID)
                        )
                        rfcommServer = srv
                        while (isActive && !state.value.userStopped) {
                            val sock = srv.accept() ?: break
                            launch(Dispatchers.IO) { handleRfcommSocket(sock) }
                        }
                    } catch (_: Exception) {
                        delay(5_000L)
                    } finally {
                        runCatching { rfcommServer?.close() }
                        rfcommServer = null
                    }
                }
            }
        }

        if (rfcommClientJob?.isActive != true) {
            rfcommClientJob = scope.launch(Dispatchers.IO) {
                while (isActive && !state.value.userStopped) {
                    if (rfcommOut == null) {
                        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
                        for (dev in bonded) {
                            if (!isActive || rfcommOut != null || state.value.userStopped) break
                            runCatching {
                                val sock = dev.createInsecureRfcommSocketToServiceRecord(UUID.fromString(SERVICE_UUID))
                                sock.connect()
                                handleRfcommSocket(sock)
                            }
                        }
                    } else if (shouldActivelyBatchHarvest() && queueDb.stats().queuedForPhone > 0) {
                        flushQueueToPhone()
                    }
                    // Polite 30s connection check interval so Bluetooth polling never drains the watch battery
                    delay(30_000L)
                }
            }
        }
    }

    private fun stopRfcommTransport() {
        rfcommServerJob?.cancel()
        rfcommClientJob?.cancel()
        runCatching { rfcommOut?.close() }
        rfcommOut = null
        runCatching { rfcommServer?.close() }
        rfcommServer = null
    }

    private suspend fun handleRfcommSocket(socket: BluetoothSocket) {
        try {
            rfcommOut = socket.outputStream
            state.update { it.copy(phoneConnected = true) }
            refreshModeStatus()
            requestTileRefresh()
            if (queueDb.stats().queuedForPhone > 0) {
                flushQueueToPhone()
            }
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (socket.isConnected && !state.value.userStopped) {
                val line = reader.readLine() ?: break
                handleAckChunk(line + "\n")
            }
        } catch (_: Exception) {
        } finally {
            rfcommOut = null
            runCatching { socket.close() }
            state.update {
                val stillConnected = subscribedDevices.isNotEmpty()
                it.copy(phoneConnected = stillConnected)
            }
            refreshModeStatus()
            requestTileRefresh()
        }
    }

    // ---- BLE GATT Server (Low-Power Balanced Advertising) ----

    private fun startBleGattServer() {
        val bm = getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bm.adapter ?: return
        if (!adapter.isEnabled) return

        val server = bm.openGattServer(this, gattServerCallback) ?: return
        val service = BluetoothGattService(UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_TELEMETRY_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        char.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString(DESCRIPTOR_CCCD),
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val ackChar = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_ACK_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(char)
        service.addCharacteristic(ackChar)
        server.addService(service)
        gattServer = server
        telemetryChar = char

        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
    }

    private fun stopBleGattServer() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        advertiser = null
        gattServer = null
        subscribedDevices.clear()
    }

    private val advertiseCallback = object : AdvertiseCallback() {}

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                subscribedDevices.add(device)
                state.update { it.copy(phoneConnected = true) }
                refreshModeStatus()
                requestTileRefresh()
                scope.launch(Dispatchers.IO) {
                    delay(500)
                    flushQueueToPhone()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedDevices.remove(device)
                state.update {
                    val stillConnected = subscribedDevices.isNotEmpty() || rfcommOut != null
                    it.copy(phoneConnected = stillConnected)
                }
                refreshModeStatus()
                requestTileRefresh()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            subscribedDevices.add(device)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            scope.launch(Dispatchers.IO) { flushQueueToPhone() }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (value != null && characteristic.uuid.toString().equals(CHAR_ACK_UUID, ignoreCase = true)) {
                handleAckChunk(value.toString(Charsets.UTF_8))
            }
        }
    }

    private suspend fun flushQueueToPhone() {
        if (flushingBle || state.value.userStopped) return
        flushingBle = true
        try {
            val batch = queueDb.pendingBatch(16)
            if (batch.isEmpty()) return

            // 1. Send over Classic Bluetooth RFCOMM if connected
            val out = rfcommOut
            if (out != null) {
                for ((_, payloadJson) in batch) {
                    val packetBytes = (payloadJson + "\n").toByteArray(Charsets.UTF_8)
                    val sent = runCatching {
                        synchronized(out) {
                            out.write(packetBytes)
                            out.flush()
                        }
                        true
                    }.getOrDefault(false)
                    if (!sent) break
                    delay(20)
                }
            }

            // 2. Send over BLE GATT notifications if subscribers are present
            val srv = gattServer
            val ch = telemetryChar
            if (srv != null && ch != null && subscribedDevices.isNotEmpty()) {
                for ((_, payloadJson) in batch) {
                    if (subscribedDevices.isEmpty()) break
                    val packetBytes = (payloadJson + "\n").toByteArray(Charsets.UTF_8)
                    var offset = 0
                    while (offset < packetBytes.size) {
                        val end = (offset + 180).coerceAtMost(packetBytes.size)
                        val slice = packetBytes.copyOfRange(offset, end)
                        for (dev in subscribedDevices.toList()) {
                            runCatching {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    srv.notifyCharacteristicChanged(dev, ch, false, slice)
                                } else {
                                    @Suppress("DEPRECATION")
                                    ch.value = slice
                                    @Suppress("DEPRECATION")
                                    srv.notifyCharacteristicChanged(dev, ch, false)
                                }
                            }
                        }
                        offset = end
                        delay(20)
                    }
                }
            }
        } finally {
            flushingBle = false
        }
    }

    override fun onDestroy() {
        stopHarvesting(userInitiated = false)
        scope.cancel()
        super.onDestroy()
    }
}
