package com.jupiterlabs.ghostdrive

import com.jupiterlabs.ghostdrive.protocol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val fixtures get() = JSONObject(javaClass.getResourceAsStream("/protocol-vectors.json")!!.bufferedReader().readText())
    @Test fun decodesAllRustSchemaVariants() {
        val vectors = fixtures; val key = vectors.getString("test_key_hex").hexBytes()
        val messages = vectors.getJSONObject("messages")
        val decoded = mutableMapOf<String, Message>()
        for(name in messages.keys()) {
            val chunks = messages.getJSONObject(name).getJSONArray("chunks"); val assembler = FragmentAssembler()
            var result: Pair<Long, ByteArray>? = null
            // Reversed fragments prove out-of-order reassembly without assuming notification order.
            for(i in chunks.length() - 1 downTo 0) assembler.accept(chunks.getString(i).hexBytes(), 1000)?.let { result = it }
            assertNotNull(result)
            decoded[name] = NullvalonProtocol.verify(result!!.second, result!!.first, key).message
        }
        assertEquals("NULLVALON", (decoded["boot"] as Message.Boot).device)
        assertEquals(SourceId.Thermal, (decoded["progress"] as Message.Progress).id)
        val s = (decoded["source"] as Message.Source).report
        assertEquals(SourceId.HardwareRng, s.id); assertEquals(Health.Accepted, s.health)
        assertEquals(8192, s.raw!!.samples); assertEquals(7.93f, s.raw!!.shannon, .001f)
        assertEquals(64, (decoded["pool"] as Message.Pool).report.digest.size)
        assertEquals(-62, (decoded["wifi"] as Message.WifiSummary).meanRssi)
        assertEquals(56, (decoded["wifi_motion"] as Message.WifiMotion).score)
        assertEquals(110, (decoded["radar"] as Message.RadarSummary).detectionCm)
    }
    @Test fun challengeMatchesIndependentSha3Hmac() {
        val v = fixtures
        assertEquals(v.getString("auth_proof_hex"), NullvalonProtocol.proof(v.getString("test_key_hex").hexBytes(), v.getString("nonce_hex").hexBytes(), 42).hex())
    }
    @Test fun rejectsTamperWrongKeyAndSequence() {
        val v = fixtures; val a = FragmentAssembler(); val chunks = v.getJSONObject("messages").getJSONObject("boot").getJSONArray("chunks")
        var packet: Pair<Long,ByteArray>? = null
        for(i in 0 until chunks.length()) a.accept(chunks.getString(i).hexBytes(), 0)?.let { packet = it }
        val p = packet!!; val key = v.getString("test_key_hex").hexBytes()
        assertThrows(IllegalArgumentException::class.java) { NullvalonProtocol.verify(p.second, p.first, ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { NullvalonProtocol.verify(p.second, p.first + 1, key) }
        val bad = p.second.copyOf(); bad[10] = (bad[10].toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { NullvalonProtocol.verify(bad, p.first, key) }
    }
    @Test fun rejectsFragmentBoundsAndConflicts() {
        val c = fixtures.getJSONObject("messages").getJSONObject("boot").getJSONArray("chunks").getString(0).hexBytes()
        val a = FragmentAssembler(); a.accept(c, 0)
        val conflict = c.copyOf(); conflict[9] = (conflict[9].toInt() xor 1).toByte()
        assertThrows(IllegalStateException::class.java) { a.accept(conflict, 1) }
        val invalid = c.copyOf(); invalid[7] = 0
        assertThrows(IllegalArgumentException::class.java) { a.accept(invalid, 1) }
    }
    @Test fun schemaMismatchAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Postcard(byteArrayOf(1,0,1,-1,-1,-1)).message() }
        val p = Postcard(byteArrayOf(0,1,42,0,99)); p.message()
        assertThrows(IllegalArgumentException::class.java) { p.finish() }
    }
}
