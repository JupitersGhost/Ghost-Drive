package com.jupiterlabs.ghostdrive.tracking

import java.util.UUID
import kotlin.math.*

data class Fix(val latitude: Double, val longitude: Double, val timeMs: Long, val elapsedMs: Long,
    val accuracyM: Float, val speedMps: Float?, val bearingDeg: Float? = null,
    val entropyShannon: Float? = null, val rfScore: Int? = null, val radarEnergy: Int? = null,
    val hrBpm: Float? = null, val ibiMs: Int? = null, val spo2Pct: Int? = null,
    val wristG: Float? = null, val watchSteps: Int? = null)
data class Place(val id: String = UUID.randomUUID().toString(), val name: String, val latitude: Double,
    val longitude: Double, val radiusM: Double = 150.0, val userDefined: Boolean = true)
data class Trip(val id: String = UUID.randomUUID().toString(), val startMs: Long, val endMs: Long? = null,
    val durationMs: Long = 0, val movingMs: Long = 0, val distanceM: Double = 0.0,
    val start: Fix? = null, val end: Fix? = null, val startName: String = "Waiting for GPS",
    val endName: String = "Recording", val endReason: String? = null, val bootId: Long,
    val parkingBeforeMs: Long? = null, val demo: Boolean = false, val stationaryMs: Long = 0,
    val maxSpeedMps: Float = 0f, val vehicle: String = "") {
    val unobservedMs: Long get() = (durationMs - movingMs - stationaryMs).coerceAtLeast(0)
    val avgMovingSpeedMps: Float get() = if (movingMs >= 1000L) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
}

object Geo {
    fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val s = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(s.coerceIn(0.0, 1.0)), sqrt((1 - s).coerceIn(0.0, 1.0)))
    }
    fun bearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val lat1 = Math.toRadians(aLat); val lat2 = Math.toRadians(bLat)
        val dLon = Math.toRadians(bLon - aLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
    fun cardinalShort(deg: Float?): String {
        if (deg == null || !deg.isFinite()) return "—"
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8
        return dirs[idx]
    }
    fun cardinal(deg: Float?): String {
        if (deg == null || !deg.isFinite()) return "—"
        val normalized = ((deg % 360f + 360f) % 360f).toInt()
        return "${cardinalShort(deg)} ${normalized}°"
    }
    fun match(fix: Fix, places: List<Place>): Place? = places
        .filter { fix.accuracyM <= it.radiusM && distance(fix.latitude, fix.longitude, it.latitude, it.longitude) <= it.radiusM }
        .sortedWith(compareByDescending<Place> { it.userDefined }.thenBy { distance(fix.latitude, fix.longitude, it.latitude, it.longitude) })
        .firstOrNull()
    fun coordinate(f: Fix) = "%.5f, %.5f".format(java.util.Locale.US, f.latitude, f.longitude)
}

/** Pure session engine. Wall clock labels; monotonic clock durations; no extrapolated GPS gaps. */
class TripEngine {
    var active: Trip? = null; private set
    var startedElapsed = 0L; private set
    private var priorTrip: Trip? = null
    fun start(nowMs: Long, elapsedMs: Long, bootId: Long, prior: Trip?, demo: Boolean = false, vehicle: String = ""): Trip {
        check(active == null)
        startedElapsed = elapsedMs; priorTrip = prior
        return Trip(startMs = nowMs, bootId = bootId, demo = demo, vehicle = vehicle).also { active = it }
    }
    fun updateNames(startName: String? = null, endName: String? = null): Trip? {
        val t = active ?: return null
        return t.copy(
            startName = startName ?: t.startName,
            endName = endName ?: t.endName
        ).also { active = it }
    }
    fun snapshot(elapsedMs: Long): Trip? = active?.copy(durationMs = (elapsedMs - startedElapsed).coerceAtLeast(0))
    fun accept(fix: Fix, nowElapsedMs: Long, places: List<Place>, maxAccuracy: Float,
        movingThreshold: Float, maxGapMs: Long = 30_000): Trip? {
        val t = active ?: return null
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || abs(fix.latitude) > 90 || abs(fix.longitude) > 180 ||
            !fix.accuracyM.isFinite() || fix.accuracyM < 0 || fix.accuracyM > maxAccuracy ||
            (fix.speedMps != null && (!fix.speedMps.isFinite() || fix.speedMps < 0)) ||
            fix.elapsedMs < startedElapsed || nowElapsedMs - fix.elapsedMs !in 0..15_000) return null
        val previous = t.end
        if (previous != null && fix.elapsedMs <= previous.elapsedMs) return null
        val dt = previous?.let { fix.elapsedMs - it.elapsedMs } ?: 0
        val delta = previous?.let { Geo.distance(it.latitude, it.longitude, fix.latitude, fix.longitude) } ?: 0.0
        if (dt > 0 && dt <= maxGapMs && delta / (dt / 1000.0) > 80) return null
        val currentName = Geo.match(fix, places)?.let { if(it.userDefined) it.name else "Near ${it.name}" } ?: Geo.coordinate(fix)
        val usableInterval = dt in 1..maxGapMs
        val displacementBeyondUncertainty = (delta - fix.accuracyM - (previous?.accuracyM ?: 0f)).coerceAtLeast(0.0)
        val fallbackSpeed = if (usableInterval) (displacementBeyondUncertainty / (dt / 1000.0)).toFloat() else 0f
        val effectiveSpeed = if (fix.speedMps != null && fix.speedMps > 0f) fix.speedMps else fallbackSpeed
        val moving = usableInterval && effectiveSpeed >= movingThreshold
        val credibleDistance = if (moving && delta >= 0.8 && ((fix.speedMps != null && fix.speedMps > 0f) || displacementBeyondUncertainty > 0)) delta else 0.0
        val computedBearing = fix.bearingDeg ?: if (previous != null && delta >= 1.2) Geo.bearing(previous.latitude, previous.longitude, fix.latitude, fix.longitude) else previous?.bearingDeg
        val enrichedFix = if (computedBearing != fix.bearingDeg || (fix.speedMps == null && moving)) {
            fix.copy(speedMps = fix.speedMps ?: if (moving) effectiveSpeed else 0f, bearingDeg = computedBearing)
        } else fix
        val peakSpeed = max(t.maxSpeedMps, if (moving) effectiveSpeed else (fix.speedMps ?: 0f))
        val parking = if (t.start == null) priorTrip?.let { p ->
            if (p.demo != t.demo) null else p.end?.let { last ->
                val previousPlace = Geo.match(last, places)
                val currentPlace = Geo.match(fix, places)
                val sameArea = if(previousPlace != null && currentPlace != null) previousPlace.id == currentPlace.id
                    else Geo.distance(last.latitude, last.longitude, fix.latitude, fix.longitude) <= 150
                if (p.endMs != null && t.startMs >= p.endMs && p.endMs - last.timeMs in 0..15_000 &&
                    fix.timeMs - t.startMs in 0..15_000 && sameArea && last.accuracyM <= 75 && fix.accuracyM <= 75)
                    t.startMs - p.endMs else null
            }
        } else t.parkingBeforeMs
        return t.copy(start = t.start ?: enrichedFix, end = enrichedFix, startName = if (t.start == null) currentName else t.startName,
            endName = currentName, movingMs = t.movingMs + if (moving) dt else 0,
            stationaryMs = t.stationaryMs + if(usableInterval && !moving) dt else 0,
            distanceM = t.distanceM + credibleDistance, durationMs = (nowElapsedMs - startedElapsed).coerceAtLeast(0),
            parkingBeforeMs = parking, maxSpeedMps = peakSpeed).also { active = it }
    }
    fun stop(nowMs: Long, elapsedMs: Long, reason: String): Trip? {
        val t = active ?: return null
        active = null
        return t.copy(endMs = nowMs, durationMs = (elapsedMs - startedElapsed).coerceAtLeast(0), endReason = reason,
            endName = if (t.end == null) "No reliable GPS fix" else t.endName)
    }
}
