package com.jupiterlabs.ghostdrive.protocol

import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object NullvalonProtocol {
    const val SERVICE = "c7a80000-0dab-4d03-9a0b-a000a0000001"
    const val TELEMETRY = "c7a80001-0dab-4d03-9a0b-a000a0000001"
    const val CHALLENGE = "c7a80002-0dab-4d03-9a0b-a000a0000001"
    const val PROOF = "c7a80003-0dab-4d03-9a0b-a000a0000001"
    private val domain = "NULLVALON-telemetry-v1\u0000".toByteArray(Charsets.UTF_8)
    fun mac(key: ByteArray, domain: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "Nullvalon needs a 32-byte device key." }
        val h = HMac(SHA3Digest(256))
        h.init(KeyParameter(key)); h.update(domain, 0, domain.size); h.update(data, 0, data.size)
        val output = ByteArray(h.macSize); h.doFinal(output, 0)
        return output.copyOf(16)
    }
    fun proof(key: ByteArray, nonce: ByteArray, bootId: Long): ByteArray {
        require(nonce.size == 16)
        return mac(key, "NULLVALON-auth-v1\u0000".toByteArray(), nonce + le32(bootId))
    }
    fun verify(packet: ByteArray, headerSequence: Long, key: ByteArray): Envelope {
        require(packet.size in 25..2805) { "Invalid authenticated packet length." }
        val body = packet.copyOfRange(0, packet.size - 16)
        require(MessageDigest.isEqual(mac(key, domain, body), packet.takeLast(16).toByteArray())) { "Telemetry authentication failed." }
        val boot = u32(body, 0); val sequence = u32(body, 4)
        require(sequence == headerSequence) { "Fragment/body sequence mismatch." }
        val reader = Postcard(body.copyOfRange(8, body.size))
        val message = reader.message(); reader.finish()
        if (message is Message.Boot) require(message.bootId == boot && message.version == 1 && message.device == "NULLVALON") { "Unexpected boot schema." }
        return Envelope(boot, sequence, message, packet)
    }
    fun le32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    fun u32(bytes: ByteArray, offset: Int): Long = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
}

/** Bounded reassembly: rejects malformed/conflicting chunks and expires partial packets. */
class FragmentAssembler {
    private data class Pending(val count: Int, val createdMs: Long, val pieces: Array<ByteArray?>)
    private val pending = linkedMapOf<Long, Pending>()
    fun reset() = pending.clear()
    fun accept(bytes: ByteArray, nowMs: Long): Pair<Long, ByteArray>? {
        pending.entries.removeAll { nowMs - it.value.createdMs > 15_000 }
        require(bytes.size >= 9 && bytes[0].toInt() and 255 == 0xa7 && bytes[1].toInt() == 1) { "Invalid BLE fragment header." }
        val sequence = NullvalonProtocol.u32(bytes, 2)
        val index = bytes[6].toInt() and 255; val count = bytes[7].toInt() and 255; val length = bytes[8].toInt() and 255
        require(count in 1..255 && index < count && length in 1..11 && bytes.size >= 9 + length && (index == count - 1 || length == 11)) { "Invalid BLE fragment bounds." }
        val item = pending.getOrPut(sequence) {
            if (pending.size >= 8) pending.remove(pending.keys.first())
            Pending(count, nowMs, arrayOfNulls(count))
        }
        if (item.count != count) { pending.remove(sequence); error("Conflicting fragment count.") }
        val payload = bytes.copyOfRange(9, 9 + length)
        item.pieces[index]?.let { if (!it.contentEquals(payload)) { pending.remove(sequence); error("Conflicting duplicate fragment.") } }
        item.pieces[index] = payload
        if (item.pieces.any { it == null }) return null
        pending.remove(sequence)
        return sequence to item.pieces.fold(ByteArray(0)) { a, b -> a + b!! }
    }
}

/** Source enums use zero-based Serde variant indices, NOT their repr(u8) values 1..7. */
class Postcard(private val bytes: ByteArray) {
    private var at = 0
    fun u8(): Int { require(at < bytes.size) { "Truncated Postcard." }; return bytes[at++].toInt() and 255 }
    private fun variable(bits: Int): Long {
        var value = 0L
        for (i in 0 until (bits + 6) / 7) {
            val b = u8(); val shift = i * 7; val remaining = bits - shift
            require(remaining >= 7 || b and 127 < (1 shl remaining)) { "Postcard integer overflow." }
            value = value or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) { require(i == 0 || b != 0) { "Noncanonical varint." }; return value }
        }
        error("Postcard integer overflow.")
    }
    fun u16() = variable(16).toInt()
    fun u32() = variable(32)
    fun u64(): Long = variable(64).also { require(it >= 0) { "Counter exceeds supported signed 64-bit range." } }
    fun bool(): Boolean = when (u8()) { 0 -> false; 1 -> true; else -> error("Invalid boolean.") }
    fun f32(): Float {
        require(at + 4 <= bytes.size)
        val f = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).float; at += 4
        require(f.isFinite()) { "Nonfinite metric." }; return f
    }
    fun blob(max: Int): ByteArray {
        val size = u32(); require(size <= max && at + size <= bytes.size) { "Invalid field length." }
        val output = bytes.copyOfRange(at, at + size.toInt()); at += size.toInt(); return output
    }
    fun string() = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(blob(256))).toString()
    private fun source() = SourceId.entries.getOrNull(u32().toInt()) ?: error("Unknown source enum.")
    private fun health() = Health.entries.getOrNull(u32().toInt()) ?: error("Unknown health enum.")
    private fun stats(): Stats = Stats(u32(), string(), f32(), f32(), f32(), f32(), u16(), f32(), u16(), bool(), u16(), bool())
    private fun optionalStats(): Stats? = when (u8()) { 0 -> null; 1 -> stats(); else -> error("Invalid Option.") }
    fun message(): Message = when (val variant = u32()) {
        0L -> Message.Boot(u8(), u32(), string())
        1L -> Message.Progress(source(), health(), u16(), u16()).also { require(it.needed > 0 && it.filled <= it.needed) }
        2L -> Message.Source(SourceReport(source(), string(), health(), u32(), u64(), optionalStats(), optionalStats(), u64(), u64(), u64(), u64(), bool()))
        3L -> Message.Pool(PoolReport(health(), u32(), blob(64).also { require(it.size == 64) }, f32(), f32(), f32(), f32(), u16(), f32(), u8(), source(), u32(), u64()))
        4L -> Message.WifiSummary(u8(), u8().toByte().toInt(), u32())
        5L -> Message.Diagnostic(u64(), u32(), u64(), u64(), bool())
        6L -> Message.WifiMotion(u8(), u8(), u8(), u8()).also { require(it.score <= 100) }
        7L -> Message.RadarSummary(bool(), u8(), u8(), u8(), u16(), u16(), u16())
        else -> error("Unsupported message variant $variant. Update decoder for this firmware.")
    }
    fun finish() { require(at == bytes.size) { "Schema mismatch: trailing Postcard bytes." } }
}
