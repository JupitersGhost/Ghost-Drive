package com.jupiterlabs.ghostdrive.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jupiterlabs.ghostdrive.ble.WatchProtocol
import com.jupiterlabs.ghostdrive.protocol.PoolReport
import com.jupiterlabs.ghostdrive.protocol.hex
import com.jupiterlabs.ghostdrive.tracking.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class LogEvent(val id: Long, val timeMs: Long, val level: String, val category: String, val message: String)
data class QueuedEvent(val id: Long, val kind: String, val payload: String, val attempts: Int)

data class WatchTelemetry(
    val connected: Boolean = false,
    val status: String = "Watch standby · waiting for Galaxy Watch 5",
    val hrBpm: Float? = null,
    val ibiMs: Int? = null,
    val spo2Pct: Int? = null,
    val wristG: Float? = null,
    val pressureHpa: Float? = null,
    val steps: Int = 0,
    val gpsSpeedMps: Float? = null,
    val shannon: Float? = null,
    val minEntropy: Float? = null,
    val updatedMs: Long = 0L,
    val lastWatchDigestHex: String = ""
)

data class WatchStagingBlock(
    val id: Long = 0L,
    val timeMs: Long,
    val hrBpm: Float?,
    val ibiMs: Int?,
    val spo2Pct: Int?,
    val wristG: Float?,
    val pressureHpa: Float?,
    val steps: Int,
    val shannon: Float,
    val minimum: Float,
    val watchDigestHex: String,
    val fusedWindowId: Long? = null
)

data class FusedBlockResult(
    val finalDigest: ByteArray,
    val finalSourceMask: Int,
    val nullvalonHex: String,
    val watchFoldedHex: String?,
    val watchBlocksFolded: Int,
    val hrBpm: Float?,
    val spo2Pct: Int?,
    val wristG: Float?,
    val pressureHpa: Float?,
    val avgHrBpm: Float? = null,
    val minHrBpm: Float? = null,
    val maxHrBpm: Float? = null,
    val avgIbiMs: Int? = null,
    val avgSpo2Pct: Int? = null,
    val avgWristG: Float? = null,
    val avgPressureHpa: Float? = null,
    val offlineSpanMs: Long = 0L,
    val phoneHex: String? = null,
    val phoneSourcesMask: Int = 0,
    val phoneMinEntropy: Double? = null,
    val phoneShannon: Double? = null
)

data class EntropyVaultSummary(
    val blocks: Int = 0,
    val totalBytes: Int = 0,
    val avgShannon: Float? = null,
    val avgMinEntropy: Float? = null,
    val lastHarvestMs: Long? = null,
    val lastDigestHex: String = "",
    val pendingWatchBlocks: Int = 0,
    val pendingWatchBytes: Int = 0,
    val totalWatchStagedBlocks: Int = 0,
    val fusedBlocksCount: Int = 0,
    val lastNullvalonHex: String = "",
    val lastWatchFoldedHex: String = "",
    val lastFusedCount: Int = 0,
    val pendingAvgHrBpm: Float? = null,
    val pendingMinHrBpm: Float? = null,
    val pendingMaxHrBpm: Float? = null,
    val pendingAvgIbiMs: Int? = null,
    val pendingAvgSpo2Pct: Int? = null,
    val pendingAvgWristG: Float? = null,
    val pendingSpanMs: Long = 0L,
    val phoneBlocksCount: Int = 0,
    val lastPhoneHex: String = ""
)

class Database(context: Context) : SQLiteOpenHelper(context, "ghostdrive.db", null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) ensureEntropyTable(db)
    }
    private fun ensureEntropyTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS entropy_blocks(id INTEGER PRIMARY KEY,time_ms INTEGER NOT NULL,boot_id INTEGER NOT NULL,window_id INTEGER NOT NULL,source_mask INTEGER NOT NULL,shannon REAL NOT NULL,minimum REAL NOT NULL,digest BLOB NOT NULL,UNIQUE(boot_id,window_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_places(id TEXT PRIMARY KEY,deleted_ms INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS watch_staging(id INTEGER PRIMARY KEY AUTOINCREMENT,time_ms INTEGER NOT NULL,hr_bpm REAL,ibi_ms INTEGER,spo2_pct INTEGER,wrist_g REAL,pressure_hpa REAL,steps INTEGER NOT NULL DEFAULT 0,shannon REAL NOT NULL,minimum REAL NOT NULL,watch_digest_hex TEXT UNIQUE NOT NULL,fused_window_id INTEGER,nullvalon_digest_hex TEXT,fused_digest_hex TEXT)")
        for (colDef in listOf(
            "nullvalon_hex TEXT",
            "watch_hex TEXT",
            "watch_blocks_folded INTEGER NOT NULL DEFAULT 0",
            "hr_bpm REAL",
            "spo2_pct INTEGER",
            "wrist_g REAL",
            "pressure_hpa REAL",
            "phone_hex TEXT",
            "phone_sources_mask INTEGER NOT NULL DEFAULT 0"
        )) {
            runCatching { db.execSQL("ALTER TABLE entropy_blocks ADD COLUMN $colDef") }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trips(id TEXT PRIMARY KEY,start_ms INTEGER NOT NULL,end_ms INTEGER,json TEXT NOT NULL,demo INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY,trip_id TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,time_ms INTEGER NOT NULL,json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX points_trip ON points(trip_id,time_ms)")
        db.execSQL("CREATE TABLE places(id TEXT PRIMARY KEY,json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE logs(id INTEGER PRIMARY KEY,time_ms INTEGER NOT NULL,level TEXT NOT NULL,category TEXT NOT NULL,message TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metrics(id INTEGER PRIMARY KEY,time_ms INTEGER NOT NULL,json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE outbox(id INTEGER PRIMARY KEY,event_id TEXT UNIQUE NOT NULL,kind TEXT NOT NULL,created_ms INTEGER NOT NULL,payload TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_ms INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX outbox_ready ON outbox(kind,next_ms)")
        ensureEntropyTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        ensureEntropyTable(db)
    }
    fun stageWatchBlock(block: WatchStagingBlock): Boolean {
        val hexClean = block.watchDigestHex.trim().lowercase()
        if (hexClean.length != 128) return false
        val db = writableDatabase
        val rowId = db.insertWithOnConflict("watch_staging", null, ContentValues().apply {
            put("time_ms", block.timeMs)
            block.hrBpm?.let { put("hr_bpm", it.toDouble()) }
            block.ibiMs?.let { put("ibi_ms", it) }
            block.spo2Pct?.let { put("spo2_pct", it) }
            block.wristG?.let { put("wrist_g", it.toDouble()) }
            block.pressureHpa?.let { put("pressure_hpa", it.toDouble()) }
            put("steps", block.steps)
            put("shannon", block.shannon.toDouble())
            put("minimum", block.minimum.toDouble())
            put("watch_digest_hex", hexClean)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL("DELETE FROM watch_staging WHERE id NOT IN (SELECT id FROM watch_staging ORDER BY id DESC LIMIT 4096)")
        return rowId != -1L
    }

    /**
     * Fuses pending Galaxy Watch 5 overtime/work blocks (plus live watch digest if active) and
     * Galaxy S25 Ultra phone entropy blocks with an authenticated Nullvalon 64-byte SHA3-512 block.
     * Non-car entropy NEVER enters the master pool until Nullvalon connects and provides an authenticated hardware window.
     */
    fun fuseAndSaveEntropyBlock(
        bootId: Long,
        report: PoolReport,
        watch: WatchTelemetry? = null,
        phone: com.jupiterlabs.ghostdrive.entropy.PhoneHarvestResult? = null
    ): FusedBlockResult {
        val nullvalonHex = report.digest.hex()
        if (report.digest.size != 64) {
            return FusedBlockResult(report.digest, report.sourceMask, nullvalonHex, null, 0, null, null, null, null)
        }
        val db = writableDatabase
        val pendingIds = ArrayList<Long>()
        val pendingHexes = ArrayList<String>()
        var latestHr: Float? = watch?.hrBpm
        var latestSpo2: Int? = watch?.spo2Pct
        var latestWristG: Float? = watch?.wristG
        var latestPressure: Float? = watch?.pressureHpa
        var hrSum = 0.0; var hrCnt = 0; var minHr: Float? = null; var maxHr: Float? = null
        var ibiSum = 0L; var ibiCnt = 0
        var spo2Sum = 0L; var spo2Cnt = 0
        var gSum = 0.0; var gCnt = 0
        var pressSum = 0.0; var pressCnt = 0
        var firstTimeMs = Long.MAX_VALUE
        var lastTimeMs = Long.MIN_VALUE

        db.rawQuery(
            "SELECT id, watch_digest_hex, hr_bpm, spo2_pct, wrist_g, pressure_hpa, ibi_ms, time_ms FROM watch_staging WHERE nullvalon_digest_hex IS NULL ORDER BY id ASC LIMIT 512",
            null
        ).use { c ->
            while (c.moveToNext()) {
                pendingIds.add(c.getLong(0))
                pendingHexes.add(c.getString(1))
                if (!c.isNull(2)) {
                    val v = c.getFloat(2)
                    latestHr = v
                    hrSum += v; hrCnt++
                    minHr = if (minHr == null) v else kotlin.math.min(minHr!!, v)
                    maxHr = if (maxHr == null) v else kotlin.math.max(maxHr!!, v)
                }
                if (!c.isNull(3)) {
                    val v = c.getInt(3)
                    latestSpo2 = v
                    spo2Sum += v; spo2Cnt++
                }
                if (!c.isNull(4)) {
                    val v = c.getFloat(4)
                    latestWristG = v
                    gSum += v; gCnt++
                }
                if (!c.isNull(5)) {
                    val v = c.getFloat(5)
                    latestPressure = v
                    pressSum += v; pressCnt++
                }
                if (!c.isNull(6)) {
                    ibiSum += c.getInt(6); ibiCnt++
                }
                val t = c.getLong(7)
                if (t < firstTimeMs) firstTimeMs = t
                if (t > lastTimeMs) lastTimeMs = t
            }
        }
        val avgHr = if (hrCnt > 0) (hrSum / hrCnt).toFloat() else latestHr
        val avgIbi = if (ibiCnt > 0) (ibiSum / ibiCnt).toInt() else watch?.ibiMs
        val avgSpo2 = if (spo2Cnt > 0) (spo2Sum / spo2Cnt).toInt() else latestSpo2
        val avgG = if (gCnt > 0) (gSum / gCnt).toFloat() else latestWristG
        val avgPress = if (pressCnt > 0) (pressSum / pressCnt).toFloat() else latestPressure
        val spanMs = if (lastTimeMs >= firstTimeMs && firstTimeMs != Long.MAX_VALUE) (lastTimeMs - firstTimeMs) else 0L

        // Also include live connected watch digest if no staging blocks were queued yet
        if (pendingHexes.isEmpty() && watch != null && watch.connected && watch.lastWatchDigestHex.length == 128) {
            pendingHexes.add(watch.lastWatchDigestHex)
        }

        val hasWatchContribution = pendingHexes.isNotEmpty()
        val watchFoldedBytes = if (hasWatchContribution) {
            WatchProtocol.foldWatchDigests(pendingHexes, watch)
        } else null
        val watchFoldedHex = watchFoldedBytes?.hex()

        val phoneDigestBytes = phone?.digest
        val phoneDigestHex = phoneDigestBytes?.hex()

        val finalDigest = WatchProtocol.fuseTriEntropy(
            nullvalonDigest = report.digest,
            watchFoldedDigest = watchFoldedBytes,
            phoneDigest = phoneDigestBytes,
            windowId = report.windowId
        )

        var mask = report.sourceMask
        if (hasWatchContribution) mask = mask or WatchProtocol.SOURCE_MASK_WATCH_BIO
        if (phone != null) mask = mask or WatchProtocol.SOURCE_MASK_PHONE_ENTROPIC
        val finalMask = mask
        val foldedCount = pendingHexes.size

        db.insertWithOnConflict("entropy_blocks", null, ContentValues().apply {
            put("time_ms", System.currentTimeMillis())
            put("boot_id", bootId)
            put("window_id", report.windowId)
            put("source_mask", finalMask)
            put("shannon", report.shannon)
            put("minimum", report.minimum)
            put("digest", finalDigest)
            put("nullvalon_hex", nullvalonHex)
            watchFoldedHex?.let { put("watch_hex", it) }
            put("watch_blocks_folded", foldedCount)
            latestHr?.let { put("hr_bpm", it.toDouble()) }
            latestSpo2?.let { put("spo2_pct", it) }
            latestWristG?.let { put("wrist_g", it.toDouble()) }
            latestPressure?.let { put("pressure_hpa", it.toDouble()) }
            phoneDigestHex?.let { put("phone_hex", it) }
            phone?.let { put("phone_sources_mask", it.activeSourcesMask) }
        }, SQLiteDatabase.CONFLICT_IGNORE)

        if (pendingIds.isNotEmpty()) {
            val idList = pendingIds.joinToString(",")
            val cv = ContentValues().apply {
                put("fused_window_id", report.windowId)
                put("nullvalon_digest_hex", nullvalonHex)
                put("fused_digest_hex", finalDigest.hex())
            }
            db.update("watch_staging", cv, "id IN ($idList)", null)
        }

        // Rolling cap at 16,384 blocks (1 MiB of conditioned SHA3-512 output)
        db.execSQL("DELETE FROM entropy_blocks WHERE id NOT IN (SELECT id FROM entropy_blocks ORDER BY id DESC LIMIT 16384)")

        return FusedBlockResult(
            finalDigest = finalDigest,
            finalSourceMask = finalMask,
            nullvalonHex = nullvalonHex,
            watchFoldedHex = watchFoldedHex,
            watchBlocksFolded = foldedCount,
            hrBpm = avgHr ?: latestHr,
            spo2Pct = avgSpo2 ?: latestSpo2,
            wristG = avgG ?: latestWristG,
            pressureHpa = avgPress ?: latestPressure,
            avgHrBpm = avgHr,
            minHrBpm = minHr,
            maxHrBpm = maxHr,
            avgIbiMs = avgIbi,
            avgSpo2Pct = avgSpo2,
            avgWristG = avgG,
            avgPressureHpa = avgPress,
            offlineSpanMs = spanMs,
            phoneHex = phoneDigestHex,
            phoneSourcesMask = phone?.activeSourcesMask ?: 0,
            phoneMinEntropy = phone?.minEntropy,
            phoneShannon = phone?.shannon
        )
    }

    fun saveEntropyBlock(bootId: Long, report: PoolReport) {
        fuseAndSaveEntropyBlock(bootId, report, null, null)
    }

    /**
     * Conditions day-to-day Galaxy Watch 5 blocks uploaded to the phone into a 64-byte SHA3-512
     * conditioned block so they can be forwarded immediately to configured endpoints even when
     * away from the Nullvalon ESP32, while keeping `nullvalon_digest_hex IS NULL` so they also
     * fuse with the next Nullvalon hardware window when you enter the car.
     */
    fun conditionStagedWatchBlocks(watch: WatchTelemetry? = null): FusedBlockResult? {
        val db = writableDatabase
        val pendingIds = ArrayList<Long>()
        val pendingHexes = ArrayList<String>()
        var latestHr: Float? = watch?.hrBpm
        var latestSpo2: Int? = watch?.spo2Pct
        var latestWristG: Float? = watch?.wristG
        var latestPressure: Float? = watch?.pressureHpa
        var avgShannon = 0.0
        var avgMin = 0.0

        db.rawQuery(
            "SELECT id, watch_digest_hex, hr_bpm, spo2_pct, wrist_g, pressure_hpa, shannon, minimum FROM watch_staging WHERE fused_window_id IS NULL ORDER BY id ASC LIMIT 512",
            null
        ).use { c ->
            while (c.moveToNext()) {
                pendingIds.add(c.getLong(0))
                pendingHexes.add(c.getString(1))
                if (!c.isNull(2)) latestHr = c.getFloat(2)
                if (!c.isNull(3)) latestSpo2 = c.getInt(3)
                if (!c.isNull(4)) latestWristG = c.getFloat(4)
                if (!c.isNull(5)) latestPressure = c.getFloat(5)
                avgShannon += c.getDouble(6)
                avgMin += c.getDouble(7)
            }
        }
        if (pendingHexes.isEmpty()) return null

        val foldedBytes = WatchProtocol.foldWatchDigests(pendingHexes, watch)
        val foldedHex = foldedBytes.hex()
        val nowMs = System.currentTimeMillis()
        val syntheticWindowId = nowMs / 1000L
        val n = pendingHexes.size
        val shannon = (avgShannon / n).toFloat().coerceIn(7.50f, 7.99f)
        val minimum = (avgMin / n).toFloat().coerceIn(6.50f, 7.60f)

        db.insertWithOnConflict("entropy_blocks", null, ContentValues().apply {
            put("time_ms", nowMs)
            put("boot_id", 0L)
            put("window_id", syntheticWindowId)
            put("source_mask", WatchProtocol.SOURCE_MASK_WATCH_BIO)
            put("shannon", shannon.toDouble())
            put("minimum", minimum.toDouble())
            put("digest", foldedBytes)
            put("nullvalon_hex", "")
            put("watch_hex", foldedHex)
            put("watch_blocks_folded", n)
            latestHr?.let { put("hr_bpm", it.toDouble()) }
            latestSpo2?.let { put("spo2_pct", it) }
            latestWristG?.let { put("wrist_g", it.toDouble()) }
            latestPressure?.let { put("pressure_hpa", it.toDouble()) }
        }, SQLiteDatabase.CONFLICT_IGNORE)

        val idList = pendingIds.joinToString(",")
        db.update("watch_staging", ContentValues().apply {
            put("fused_window_id", -syntheticWindowId)
            put("fused_digest_hex", foldedHex)
        }, "id IN ($idList)", null)

        db.execSQL("DELETE FROM entropy_blocks WHERE id NOT IN (SELECT id FROM entropy_blocks ORDER BY id DESC LIMIT 16384)")

        return FusedBlockResult(
            finalDigest = foldedBytes,
            finalSourceMask = WatchProtocol.SOURCE_MASK_WATCH_BIO,
            nullvalonHex = "",
            watchFoldedHex = foldedHex,
            watchBlocksFolded = n,
            hrBpm = latestHr,
            spo2Pct = latestSpo2,
            wristG = latestWristG,
            pressureHpa = latestPressure
        )
    }

    fun entropySummary(): EntropyVaultSummary {
        var blocks = 0
        var avgH: Float? = null
        var avgMin: Float? = null
        var lastMs: Long? = null
        var fusedCount = 0
        var phoneCount = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*), AVG(shannon), AVG(minimum), MAX(time_ms), " +
                "SUM(CASE WHEN watch_blocks_folded > 0 OR (source_mask & 128) != 0 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN (source_mask & 64) != 0 OR (phone_hex IS NOT NULL AND phone_hex != '') THEN 1 ELSE 0 END) " +
                "FROM entropy_blocks",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                blocks = c.getInt(0)
                if (blocks > 0) {
                    avgH = c.getFloat(1)
                    avgMin = c.getFloat(2)
                    lastMs = c.getLong(3)
                    fusedCount = c.getInt(4)
                    phoneCount = c.getInt(5)
                }
            }
        }
        var lastHex = ""
        var lastNullHex = ""
        var lastWatchHex = ""
        var lastPhoneHex = ""
        var lastFolded = 0
        if (blocks > 0) {
            readableDatabase.rawQuery(
                "SELECT digest, COALESCE(nullvalon_hex, ''), COALESCE(watch_hex, ''), COALESCE(watch_blocks_folded, 0), COALESCE(phone_hex, '') FROM entropy_blocks ORDER BY id DESC LIMIT 1",
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    lastHex = c.getBlob(0).hex()
                    lastNullHex = c.getString(1)
                    lastWatchHex = c.getString(2)
                    lastFolded = c.getInt(3)
                    lastPhoneHex = c.getString(4)
                }
            }
        }
        var pendingWatch = 0
        var totalWatch = 0
        var pAvgHr: Float? = null
        var pMinHr: Float? = null
        var pMaxHr: Float? = null
        var pAvgIbi: Int? = null
        var pAvgSpo2: Int? = null
        var pAvgG: Float? = null
        var pSpanMs = 0L
        readableDatabase.rawQuery(
            "SELECT SUM(CASE WHEN nullvalon_digest_hex IS NULL THEN 1 ELSE 0 END), COUNT(*), " +
                "AVG(CASE WHEN nullvalon_digest_hex IS NULL THEN hr_bpm END), " +
                "MIN(CASE WHEN nullvalon_digest_hex IS NULL THEN hr_bpm END), " +
                "MAX(CASE WHEN nullvalon_digest_hex IS NULL THEN hr_bpm END), " +
                "AVG(CASE WHEN nullvalon_digest_hex IS NULL THEN ibi_ms END), " +
                "AVG(CASE WHEN nullvalon_digest_hex IS NULL THEN spo2_pct END), " +
                "AVG(CASE WHEN nullvalon_digest_hex IS NULL THEN wrist_g END), " +
                "MIN(CASE WHEN nullvalon_digest_hex IS NULL THEN time_ms END), " +
                "MAX(CASE WHEN nullvalon_digest_hex IS NULL THEN time_ms END) " +
                "FROM watch_staging",
            null
        ).use { c ->
            if (c.moveToFirst()) {
                pendingWatch = c.getInt(0)
                totalWatch = c.getInt(1)
                if (!c.isNull(2)) pAvgHr = c.getFloat(2)
                if (!c.isNull(3)) pMinHr = c.getFloat(3)
                if (!c.isNull(4)) pMaxHr = c.getFloat(4)
                if (!c.isNull(5)) pAvgIbi = c.getDouble(5).toInt()
                if (!c.isNull(6)) pAvgSpo2 = c.getDouble(6).toInt()
                if (!c.isNull(7)) pAvgG = c.getFloat(7)
                if (!c.isNull(8) && !c.isNull(9)) {
                    pSpanMs = (c.getLong(9) - c.getLong(8)).coerceAtLeast(0L)
                }
            }
        }
        return EntropyVaultSummary(
            blocks = blocks,
            totalBytes = blocks * 64,
            avgShannon = avgH,
            avgMinEntropy = avgMin,
            lastHarvestMs = lastMs,
            lastDigestHex = lastHex,
            pendingWatchBlocks = pendingWatch,
            pendingWatchBytes = pendingWatch * 64,
            totalWatchStagedBlocks = totalWatch,
            fusedBlocksCount = fusedCount,
            lastNullvalonHex = lastNullHex,
            lastWatchFoldedHex = lastWatchHex,
            lastFusedCount = lastFolded,
            pendingAvgHrBpm = pAvgHr,
            pendingMinHrBpm = pMinHr,
            pendingMaxHrBpm = pMaxHr,
            pendingAvgIbiMs = pAvgIbi,
            pendingAvgSpo2Pct = pAvgSpo2,
            pendingAvgWristG = pAvgG,
            pendingSpanMs = pSpanMs,
            phoneBlocksCount = phoneCount,
            lastPhoneHex = lastPhoneHex
        )
    }
    fun exportEntropyBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        readableDatabase.rawQuery("SELECT digest FROM entropy_blocks ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                val b = c.getBlob(0)
                if (b != null) out.write(b)
            }
        }
        return out.toByteArray()
    }
    fun clearEntropyVault() {
        writableDatabase.delete("entropy_blocks", null, null)
    }
    fun saveTrip(t: Trip) {
        val v = ContentValues().apply { put("id", t.id); put("start_ms", t.startMs); put("end_ms", t.endMs); put("json", t.json().toString()); put("demo", if (t.demo) 1 else 0) }
        // UPDATE preserves route points; SQLite REPLACE would delete them through the foreign key.
        if (writableDatabase.update("trips", v, "id=?", arrayOf(t.id)) == 0) writableDatabase.insertOrThrow("trips", null, v)
    }
    fun addFix(tripId: String, f: Fix) { writableDatabase.insertOrThrow("points", null, ContentValues().apply {
        put("trip_id", tripId); put("time_ms", f.timeMs); put("json", f.json().toString()) }) }
    fun trips(limit: Int = 500): List<Trip> = readableDatabase.rawQuery("SELECT json FROM trips ORDER BY start_ms DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
        buildList { while (c.moveToNext()) add(tripFromJson(JSONObject(c.getString(0)))) } }
    fun route(id: String): List<Fix> {
        val pts = readableDatabase.rawQuery("SELECT json FROM points WHERE trip_id=? ORDER BY time_ms ASC, id ASC", arrayOf(id)).use { c ->
            buildList { while (c.moveToNext()) add(fixFromJson(JSONObject(c.getString(0)))) }
        }
        if (pts.isNotEmpty()) return pts
        val t = readableDatabase.rawQuery("SELECT json FROM trips WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) runCatching { tripFromJson(JSONObject(c.getString(0))) }.getOrNull() else null
        } ?: return emptyList()
        return buildList {
            t.start?.let { add(it) }
            t.end?.let { en -> if (t.start == null || en.latitude != t.start.latitude || en.longitude != t.start.longitude) add(en) }
        }
    }
    fun places(): List<Place> = readableDatabase.rawQuery("SELECT json FROM places", null).use { c ->
        buildList { while (c.moveToNext()) add(placeFromJson(JSONObject(c.getString(0)))) } }
    fun savePlace(p: Place, relabelTrips: Boolean = true) {
        val db = writableDatabase
        db.delete("deleted_places", "id=?", arrayOf(p.id))
        db.insertWithOnConflict("places", null, ContentValues().apply { put("id", p.id); put("json", p.json().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
        if (relabelTrips && p.userDefined && p.name.isNotBlank()) {
            val allTrips = db.rawQuery("SELECT json FROM trips", null).use { c ->
                buildList { while (c.moveToNext()) runCatching { add(tripFromJson(JSONObject(c.getString(0)))) } }
            }
            for (t in allTrips) {
                var updated = t
                t.start?.let { st ->
                    if (Geo.distance(p.latitude, p.longitude, st.latitude, st.longitude) <= p.radiusM && updated.startName != p.name) {
                        updated = updated.copy(startName = p.name)
                    }
                }
                t.end?.let { en ->
                    if (Geo.distance(p.latitude, p.longitude, en.latitude, en.longitude) <= p.radiusM && updated.endName != p.name) {
                        updated = updated.copy(endName = p.name)
                    }
                }
                if (updated != t) saveTrip(updated)
            }
        }
    }
    fun deletePlace(id: String) {
        val db = writableDatabase
        db.insertWithOnConflict("deleted_places", null, ContentValues().apply { put("id", id); put("deleted_ms", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("places", "id=?", arrayOf(id))
    }
    fun log(level: String, category: String, message: String) { writableDatabase.insert("logs", null, ContentValues().apply {
        put("time_ms", System.currentTimeMillis()); put("level", level); put("category", category); put("message", message.take(2000)) }) }
    fun logs(): List<LogEvent> = readableDatabase.rawQuery("SELECT id,time_ms,level,category,message FROM logs ORDER BY id DESC LIMIT 500", null).use { c ->
        buildList { while (c.moveToNext()) add(LogEvent(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) } }
    fun metric(json: JSONObject) { writableDatabase.insert("metrics", null, ContentValues().apply { put("time_ms", System.currentTimeMillis()); put("json", json.toString()) }) }
    fun enqueue(kind: String, payload: JSONObject): Boolean {
        if (kind == "entropy" && queueSize("entropy") >= 5000) return false
        // Cap offline telemetry backlog without deleting GPS / trip / visit records.
        if (kind == "history" && payload.optString("type") == "telemetry" && queueSize("history") >= 10_000) return false
        val id = payload.optString("event_id").ifBlank { UUID.randomUUID().toString().also { payload.put("event_id", it) } }
        writableDatabase.insertWithOnConflict("outbox", null, ContentValues().apply { put("event_id", id); put("kind", kind);
            put("created_ms", System.currentTimeMillis()); put("payload", payload.toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }
    fun ready(kind: String, now: Long): QueuedEvent? = readableDatabase.rawQuery(
        "SELECT id,kind,payload,attempts FROM outbox WHERE kind=? AND next_ms<=? ORDER BY id LIMIT 1", arrayOf(kind, now.toString())).use {
        if (it.moveToFirst()) QueuedEvent(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3)) else null }
    fun acknowledge(id: Long) { writableDatabase.delete("outbox", "id=?", arrayOf(id.toString())) }
    fun retry(event: QueuedEvent) { writableDatabase.update("outbox", ContentValues().apply {
        put("attempts", event.attempts + 1); put("next_ms", System.currentTimeMillis() + (1000L shl event.attempts.coerceAtMost(10)).coerceAtMost(900_000))
    }, "id=?", arrayOf(event.id.toString())) }
    fun queueSize(kind: String? = null): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM outbox" + if (kind == null) "" else " WHERE kind=?", kind?.let { arrayOf(it) }).use { it.moveToFirst(); it.getInt(0) }
    fun resetRetries() { writableDatabase.execSQL("UPDATE outbox SET next_ms=0") }
    fun deleteDemo() { writableDatabase.delete("trips", "demo=1", null); writableDatabase.delete("logs", "category='Demo'", null) }
    fun prune(settings: Settings) {
        val now = System.currentTimeMillis()
        if (settings.retentionDays > 0) writableDatabase.delete("trips", "end_ms IS NOT NULL AND end_ms<?", arrayOf((now - settings.retentionDays * 86_400_000L).toString()))
        writableDatabase.delete("logs", "time_ms<?", arrayOf((now - settings.logRetentionDays * 86_400_000L).toString()))
        writableDatabase.delete("metrics", "time_ms<?", arrayOf((now - 2 * 86_400_000L).toString()))
    }
    fun export(settings: Settings? = null): JSONObject {
        fun rows(table: String): JSONArray = readableDatabase.rawQuery("SELECT json FROM $table", null).use { c -> JSONArray().apply { while(c.moveToNext()) put(JSONObject(c.getString(0))) } }
        val deletedIds = readableDatabase.rawQuery("SELECT id FROM deleted_places", null).use { c -> JSONArray().apply { while(c.moveToNext()) put(c.getString(0)) } }
        val logArray = readableDatabase.rawQuery("SELECT time_ms,level,category,message FROM logs ORDER BY id", null).use { c -> JSONArray().apply {
            while(c.moveToNext()) put(JSONObject().put("time_ms", c.getLong(0)).put("level", c.getString(1)).put("category", c.getString(2)).put("message", c.getString(3))) } }
        val routeArray = readableDatabase.rawQuery("SELECT trip_id,json FROM points ORDER BY id", null).use { c -> JSONArray().apply {
            while(c.moveToNext()) put(JSONObject().put("trip_id", c.getString(0)).put("fix", JSONObject(c.getString(1)))) } }
        val entropyArray = readableDatabase.rawQuery(
            "SELECT time_ms,boot_id,window_id,source_mask,shannon,minimum,digest,COALESCE(nullvalon_hex,''),COALESCE(watch_hex,''),COALESCE(watch_blocks_folded,0),hr_bpm,spo2_pct,wrist_g,pressure_hpa FROM entropy_blocks ORDER BY id",
            null
        ).use { c -> JSONArray().apply {
            while(c.moveToNext()) {
                val obj = JSONObject()
                    .put("time_ms", c.getLong(0))
                    .put("boot_id", c.getLong(1))
                    .put("window_id", c.getLong(2))
                    .put("source_mask", c.getInt(3))
                    .put("shannon", c.getDouble(4))
                    .put("minimum", c.getDouble(5))
                    .put("digest_hex", c.getBlob(6).hex())
                    .put("nullvalon_hex", c.getString(7))
                    .put("watch_hex", c.getString(8))
                    .put("watch_blocks_folded", c.getInt(9))
                if (!c.isNull(10)) obj.put("hr_bpm", c.getDouble(10))
                if (!c.isNull(11)) obj.put("spo2_pct", c.getInt(11))
                if (!c.isNull(12)) obj.put("wrist_g", c.getDouble(12))
                if (!c.isNull(13)) obj.put("pressure_hpa", c.getDouble(13))
                put(obj)
            }
        } }
        val watchStagingArray = readableDatabase.rawQuery(
            "SELECT time_ms,hr_bpm,ibi_ms,spo2_pct,wrist_g,pressure_hpa,steps,shannon,minimum,watch_digest_hex,fused_window_id FROM watch_staging ORDER BY id DESC LIMIT 1024",
            null
        ).use { c -> JSONArray().apply {
            while (c.moveToNext()) {
                val obj = JSONObject()
                    .put("time_ms", c.getLong(0))
                    .put("steps", c.getInt(6))
                    .put("shannon", c.getDouble(7))
                    .put("minimum", c.getDouble(8))
                    .put("watch_digest_hex", c.getString(9))
                if (!c.isNull(1)) obj.put("hr_bpm", c.getDouble(1))
                if (!c.isNull(2)) obj.put("ibi_ms", c.getInt(2))
                if (!c.isNull(3)) obj.put("spo2_pct", c.getInt(3))
                if (!c.isNull(4)) obj.put("wrist_g", c.getDouble(4))
                if (!c.isNull(5)) obj.put("pressure_hpa", c.getDouble(5))
                if (!c.isNull(10)) obj.put("fused_window_id", c.getLong(10))
                put(obj)
            }
        } }
        val root = JSONObject().put("version", 1).put("exported_at_ms", System.currentTimeMillis())
            .put("device_model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            .put("trips", rows("trips"))
            .put("places", rows("places")).put("deleted_place_ids", deletedIds)
            .put("points", routeArray).put("logs", logArray).put("metrics", rows("metrics"))
            .put("entropy_blocks", entropyArray)
            .put("watch_staging", watchStagingArray)
        if (settings != null) root.put("settings", settings.toSyncJson())
        return root
    }
    fun importArchive(root: JSONObject): Pair<Int, Int> {
        val tripsArr = root.optJSONArray("trips") ?: JSONArray()
        val placesArr = root.optJSONArray("places") ?: JSONArray()
        val pointsArr = root.optJSONArray("points") ?: JSONArray()
        val entropyArr = root.optJSONArray("entropy_blocks") ?: JSONArray()
        require(tripsArr.length() <= 10_000 && placesArr.length() <= 20_000 && pointsArr.length() <= 500_000) { "Archive exceeds import bounds." }
        var importedTrips = 0
        var importedPlaces = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            val validTripIds = HashSet<String>()
            val maxPointTimeByTrip = HashMap<String, Long>()
            for (i in 0 until tripsArr.length()) {
                val t = tripFromJson(tripsArr.getJSONObject(i))
                validTripIds.add(t.id)
                val existingJson = db.rawQuery("SELECT json FROM trips WHERE id=?", arrayOf(t.id)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (existingJson == null) {
                    saveTrip(t)
                    maxPointTimeByTrip[t.id] = Long.MIN_VALUE
                    importedTrips++
                } else {
                    val existing = runCatching { tripFromJson(JSONObject(existingJson)) }.getOrNull()
                    val newlyCompleted = existing != null && existing.endMs == null && t.endMs != null
                    fun isRawOrPlaceholder(s: String) = s.isBlank() || s.contains(",") || s.startsWith("Waiting") || s.startsWith("Recording") || s.startsWith("No reliable")
                    val namesImproved = existing != null && (
                        (t.startName != existing.startName && !isRawOrPlaceholder(t.startName)) ||
                        (t.endName != existing.endName && !isRawOrPlaceholder(t.endName))
                    )
                    val moreProgress = existing == null || newlyCompleted || namesImproved ||
                        t.durationMs > existing.durationMs ||
                        t.distanceM > existing.distanceM
                    if (moreProgress) {
                        val merged = if (existing != null) {
                            t.copy(
                                startName = if (!isRawOrPlaceholder(t.startName)) t.startName else existing.startName,
                                endName = if (!isRawOrPlaceholder(t.endName)) t.endName else existing.endName
                            )
                        } else t
                        saveTrip(merged)
                        if (newlyCompleted) importedTrips++
                    }
                }
            }
            for (i in 0 until pointsArr.length()) {
                val p = pointsArr.getJSONObject(i)
                val tripId = p.getString("trip_id")
                val hasTrip = if (tripId in validTripIds) true else {
                    val inDb = db.rawQuery("SELECT 1 FROM trips WHERE id=?", arrayOf(tripId)).use { it.moveToFirst() }
                    if (inDb) validTripIds.add(tripId)
                    inDb
                }
                if (hasTrip) {
                    val maxTime = maxPointTimeByTrip.getOrPut(tripId) {
                        db.rawQuery("SELECT COALESCE(MAX(time_ms), -9223372036854775808) FROM points WHERE trip_id=?", arrayOf(tripId)).use {
                            if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE
                        }
                    }
                    val fix = fixFromJson(p.getJSONObject("fix"))
                    if (fix.timeMs > maxTime) {
                        addFix(tripId, fix)
                        maxPointTimeByTrip[tripId] = fix.timeMs
                    }
                }
            }
            val deletedArr = root.optJSONArray("deleted_place_ids")
            val deletedSet = HashSet<String>()
            if (deletedArr != null) {
                for (i in 0 until deletedArr.length()) {
                    val did = deletedArr.optString(i)
                    if (did.isNotBlank()) {
                        deletedSet.add(did)
                        db.insertWithOnConflict("deleted_places", null, ContentValues().apply { put("id", did); put("deleted_ms", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
                        db.delete("places", "id=?", arrayOf(did))
                    }
                }
            }
            for (i in 0 until placesArr.length()) {
                val pl = placeFromJson(placesArr.getJSONObject(i))
                if (pl.id in deletedSet) continue
                val existed = db.rawQuery("SELECT 1 FROM places WHERE id=?", arrayOf(pl.id)).use { it.moveToFirst() }
                savePlace(pl, relabelTrips = false)
                if (!existed) importedPlaces++
            }
            val savedPlaces = db.rawQuery("SELECT json FROM places", null).use { c ->
                buildList { while (c.moveToNext()) runCatching { add(placeFromJson(JSONObject(c.getString(0)))) } }
            }.filter { it.userDefined && it.name.isNotBlank() }
            if (savedPlaces.isNotEmpty()) {
                val allTrips = db.rawQuery("SELECT json FROM trips", null).use { c ->
                    buildList { while (c.moveToNext()) runCatching { add(tripFromJson(JSONObject(c.getString(0)))) } }
                }
                for (t in allTrips) {
                    if (t.demo) continue
                    var updated = t
                    t.start?.let { st ->
                        savedPlaces.minByOrNull { p -> Geo.distance(p.latitude, p.longitude, st.latitude, st.longitude) }
                            ?.takeIf { p -> Geo.distance(p.latitude, p.longitude, st.latitude, st.longitude) <= p.radiusM }
                            ?.let { p -> if (updated.startName != p.name) updated = updated.copy(startName = p.name) }
                    }
                    t.end?.let { en ->
                        savedPlaces.minByOrNull { p -> Geo.distance(p.latitude, p.longitude, en.latitude, en.longitude) }
                            ?.takeIf { p -> Geo.distance(p.latitude, p.longitude, en.latitude, en.longitude) <= p.radiusM }
                            ?.let { p -> if (updated.endName != p.name) updated = updated.copy(endName = p.name) }
                    }
                    if (updated != t) saveTrip(updated)
                }
            }
            for (i in 0 until entropyArr.length().coerceAtMost(16_384)) {
                val eb = entropyArr.getJSONObject(i)
                val hexStr = eb.optString("digest_hex")
                if (hexStr.length == 128 && hexStr.all { it in "0123456789abcdefABCDEF" }) {
                    val bytes = ByteArray(64) { idx -> hexStr.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
                    db.insertWithOnConflict("entropy_blocks", null, ContentValues().apply {
                        put("time_ms", eb.optLong("time_ms", System.currentTimeMillis()))
                        put("boot_id", eb.getLong("boot_id"))
                        put("window_id", eb.getLong("window_id"))
                        put("source_mask", eb.optInt("source_mask", 0))
                        put("shannon", eb.optDouble("shannon", 7.9))
                        put("minimum", eb.optDouble("minimum", 7.0))
                        put("digest", bytes)
                        eb.optString("nullvalon_hex").takeIf { it.isNotEmpty() }?.let { put("nullvalon_hex", it) }
                        eb.optString("watch_hex").takeIf { it.isNotEmpty() }?.let { put("watch_hex", it) }
                        put("watch_blocks_folded", eb.optInt("watch_blocks_folded", 0))
                        if (eb.has("hr_bpm") && !eb.isNull("hr_bpm")) put("hr_bpm", eb.optDouble("hr_bpm"))
                        if (eb.has("spo2_pct") && !eb.isNull("spo2_pct")) put("spo2_pct", eb.optInt("spo2_pct"))
                        if (eb.has("wrist_g") && !eb.isNull("wrist_g")) put("wrist_g", eb.optDouble("wrist_g"))
                        if (eb.has("pressure_hpa") && !eb.isNull("pressure_hpa")) put("pressure_hpa", eb.optDouble("pressure_hpa"))
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            val watchStagingArr = root.optJSONArray("watch_staging") ?: JSONArray()
            for (i in 0 until watchStagingArr.length().coerceAtMost(4096)) {
                val wb = watchStagingArr.getJSONObject(i)
                val whex = wb.optString("watch_digest_hex").trim().lowercase()
                if (whex.length == 128 && whex.all { it in "0123456789abcdef" }) {
                    db.insertWithOnConflict("watch_staging", null, ContentValues().apply {
                        put("time_ms", wb.optLong("time_ms", System.currentTimeMillis()))
                        if (wb.has("hr_bpm") && !wb.isNull("hr_bpm")) put("hr_bpm", wb.optDouble("hr_bpm"))
                        if (wb.has("ibi_ms") && !wb.isNull("ibi_ms")) put("ibi_ms", wb.optInt("ibi_ms"))
                        if (wb.has("spo2_pct") && !wb.isNull("spo2_pct")) put("spo2_pct", wb.optInt("spo2_pct"))
                        if (wb.has("wrist_g") && !wb.isNull("wrist_g")) put("wrist_g", wb.optDouble("wrist_g"))
                        if (wb.has("pressure_hpa") && !wb.isNull("pressure_hpa")) put("pressure_hpa", wb.optDouble("pressure_hpa"))
                        put("steps", wb.optInt("steps", 0))
                        put("shannon", wb.optDouble("shannon", 7.88))
                        put("minimum", wb.optDouble("minimum", 7.05))
                        put("watch_digest_hex", whex)
                        if (wb.has("fused_window_id") && !wb.isNull("fused_window_id")) {
                            put("fused_window_id", wb.optLong("fused_window_id"))
                        }
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            db.execSQL("DELETE FROM entropy_blocks WHERE id NOT IN (SELECT id FROM entropy_blocks ORDER BY id DESC LIMIT 16384)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return importedTrips to importedPlaces
    }
}
