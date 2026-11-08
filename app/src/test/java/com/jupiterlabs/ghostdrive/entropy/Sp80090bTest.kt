package com.jupiterlabs.ghostdrive.entropy

import com.jupiterlabs.ghostdrive.ble.WatchProtocol
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class Sp80090bTest {

    @Test
    fun testRctPassesOnDynamicData() {
        val rct = Sp80090b.Rct(0.5)
        for (i in 0 until 500) {
            assertTrue("RCT should pass on alternating data", rct.feed(i and 0xFF))
        }
    }

    @Test
    fun testRctFailsOnStuckBitstream() {
        val rct = Sp80090b.Rct(0.5)
        var failed = false
        for (i in 0 until 100) {
            if (!rct.feed(42)) {
                failed = true
                break
            }
        }
        assertTrue("RCT must trip on stuck constant value", failed)
    }

    @Test
    fun testAptPassesOnUniformDistribution() {
        val apt = Sp80090b.Apt(0.5)
        val rng = SecureRandom()
        var passed = true
        for (i in 0 until 1024) {
            if (!apt.feed(rng.nextInt(256))) {
                passed = false
                break
            }
        }
        assertTrue("APT should pass on high entropy data", passed)
    }

    @Test
    fun testAssessProducesValidEstimates() {
        val bytes = ByteArray(1024) { i -> ((i * 37) xor (i shr 2)).toByte() }
        val score = Sp80090b.assess(bytes)
        assertTrue("Shannon entropy must be > 6.0", score.shannon > 6.0)
        assertTrue("Min-entropy must be > 4.0", score.assessedMinH > 4.0)
        assertTrue("Governing estimator must be populated", score.governingEstimator.isNotBlank())
    }

    @Test
    fun testTriFusionConditioning() {
        val nullvalon = ByteArray(64) { 0x11 }
        val watch = ByteArray(64) { 0x22 }
        val phone = ByteArray(64) { 0x33 }

        val fusedNoExtras = WatchProtocol.fuseTriEntropy(nullvalon, null, null, 1L)
        assertArrayEquals("Nullvalon unchanged if no extra sources", nullvalon, fusedNoExtras)

        val fusedWatchOnly = WatchProtocol.fuseTriEntropy(nullvalon, watch, null, 1L)
        assertEquals(64, fusedWatchOnly.size)
        assertFalse("Fused digest must not match raw nullvalon", nullvalon.contentEquals(fusedWatchOnly))

        val fusedTri1 = WatchProtocol.fuseTriEntropy(nullvalon, watch, phone, 1L)
        assertEquals(64, fusedTri1.size)
        assertFalse("Tri-fused digest must differ from dual-fused digest", fusedWatchOnly.contentEquals(fusedTri1))

        // Avalanche test: 1 bit flip in phone digest flips ~50% of bits in master digest
        val phoneFlipped = phone.clone().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val fusedTri2 = WatchProtocol.fuseTriEntropy(nullvalon, watch, phoneFlipped, 1L)
        assertFalse("1-bit phone change must produce distinct sponge digest", fusedTri1.contentEquals(fusedTri2))
    }
}
