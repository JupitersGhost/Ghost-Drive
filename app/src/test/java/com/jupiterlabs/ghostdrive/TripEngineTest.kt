package com.jupiterlabs.ghostdrive

import com.jupiterlabs.ghostdrive.tracking.*
import org.junit.Assert.*
import org.junit.Test

class TripEngineTest {
    private val home = Place(name = "Home", latitude = 44.5, longitude = -88.0)
    private fun fix(a: Double = 44.5, mono: Long = 2000, accuracy: Float = 8f, time: Long = 10_000) = Fix(a, -88.0, time, mono, accuracy, 4f)
    @Test fun disconnectedNeverRecords() { assertNull(TripEngine().accept(fix(), 2000, listOf(home), 75f, 1.5f)) }
    @Test fun staleAndImpreciseRejected() {
        val e = TripEngine(); e.start(10_000, 1000, 56, null)
        assertNull(e.accept(fix(mono = 999), 2000, listOf(home), 75f, 1.5f))
        assertNull(e.accept(fix(accuracy = 100f), 2000, listOf(home), 75f, 1.5f))
        assertNull(e.accept(fix(), 30_000, listOf(home), 75f, 1.5f))
    }
    @Test fun correctTimesEvenIfWallClockChanges() {
        val e = TripEngine(); e.start(100_000, 1000, 56, null)
        e.accept(fix(), 2000, listOf(home), 75f, 1.5f)
        val t = e.stop(50_000, 5000, "BLE disconnected")!!
        assertEquals(4000L, t.durationMs); assertEquals("Home", t.startName); assertEquals(50_000L, t.endMs)
        assertNull(e.accept(fix(mono = 6000), 6000, listOf(home), 75f, 1.5f))
    }
    @Test fun longGapDoesNotCreateDistanceOrMovingTime() {
        val e = TripEngine(); e.start(10_000, 1000, 56, null)
        e.accept(fix(), 2000, listOf(home), 75f, 1.5f)
        val t = e.accept(fix(a = 44.55, mono = 200_000), 200_000, listOf(home), 75f, 1.5f)!!
        assertEquals(0.0, t.distanceM, .0001); assertEquals(0, t.movingMs)
    }
    @Test fun parkingOnlyInferredForSameObservedArea() {
        val prior = Trip(startMs = 1000, endMs = 5000, start = fix(time = 1000), end = fix(time = 5000), bootId = 1)
        val e = TripEngine(); e.start(10_000, 1000, 56, prior)
        assertEquals(5000L, e.accept(fix(), 2000, listOf(home), 75f, 1.5f)!!.parkingBeforeMs)
        val other = TripEngine(); other.start(10_000, 1000, 56, prior)
        assertNull(other.accept(fix(a = 44.6), 2000, listOf(home), 75f, 1.5f)!!.parkingBeforeMs)
    }
    @Test fun invalidCoordinateRejected() {
        val e = TripEngine(); e.start(10_000, 1000, 56, null)
        assertNull(e.accept(fix(a = Double.NaN), 2000, listOf(home), 75f, 1.5f))
    }
    @Test fun savedBasesTakePriorityOverPublicReferences() {
        val other = home.copy(id = "poi", name = "Public shop", userDefined = false, radiusM = 80.0)
        assertEquals("Home", Geo.match(fix(), listOf(other, home))!!.name)
    }
    @Test fun gapsStayUnknownAndTimersUseMonotonicTime() {
        val e = TripEngine(); e.start(10_000, 1000, 56, null)
        e.accept(fix(mono = 2000).copy(speedMps = 0f), 2000, listOf(home), 75f, 1.5f)
        val stationary = e.accept(fix(mono = 7000).copy(speedMps = 0f), 7000, listOf(home), 75f, 1.5f)!!
        assertEquals(5000L, stationary.stationaryMs); assertEquals(1000L, stationary.unobservedMs)
        val gap = e.accept(fix(mono = 200_000).copy(speedMps = 0f), 200_000, listOf(home), 75f, 1.5f)!!
        assertEquals(5000L, gap.stationaryMs); assertEquals(194_000L, gap.unobservedMs)
        assertEquals(299_000L, e.snapshot(300_000)!!.durationMs)
    }
    @Test fun rejectsInvalidSpeedAndDoesNotTreatGpsDriftAsMotion() {
        val e = TripEngine(); e.start(10_000, 1000, 56, null)
        assertNull(e.accept(fix().copy(speedMps = Float.NaN), 2000, listOf(home), 75f, 1.5f))
        e.accept(fix().copy(speedMps = null), 2000, listOf(home), 75f, 1.5f)
        val drift = e.accept(fix(a = 44.50004, mono = 3000).copy(speedMps = null), 3000, listOf(home), 75f, 1.5f)!!
        assertEquals(0L, drift.movingMs); assertEquals(0.0, drift.distanceM, .001)
    }
    @Test fun oldOrLateEndpointCannotInferParking() {
        val prior = Trip(startMs = 0, endMs = 50_000, end = fix(time = 10_000), bootId = 1)
        val e = TripEngine(); e.start(60_000, 1000, 56, prior)
        assertNull(e.accept(fix(time = 60_000), 2000, listOf(home), 75f, 1.5f)!!.parkingBeforeMs)
        val f = TripEngine(); f.start(60_000, 1000, 56, prior.copy(end = fix(time = 50_000)))
        assertNull(f.accept(fix(time = 80_000), 2000, listOf(home), 75f, 1.5f)!!.parkingBeforeMs)
    }
    @Test fun parkingUsesTheConfiguredBaseArea() {
        val base = home.copy(radiusM = 500.0)
        val prior = Trip(startMs = 0, endMs = 5000, end = fix(time = 5000), bootId = 1)
        val e = TripEngine(); e.start(10_000, 1000, 56, prior)
        assertEquals(5000L, e.accept(fix(a = 44.503), 2000, listOf(base), 75f, 1.5f)!!.parkingBeforeMs)
    }
}
