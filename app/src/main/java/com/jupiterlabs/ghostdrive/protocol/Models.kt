package com.jupiterlabs.ghostdrive.protocol

enum class SourceId(val label: String, val wireName: String) {
    HardwareRng("Hardware RNG", "rng"), RadarMoving("Radar moving energy", "radar_moving"),
    RadarStationary("Radar stationary energy", "radar_stationary"), RadarDistance("Radar distances", "radar_distance"),
    Wifi("Wi-Fi observations", "wifi"), Timing("Timing variation", "timing"), Thermal("Chip temperature", "thermal")
}
enum class Health(val label: String) { Absent("No samples"), Collecting("Collecting"), Accepted("Accepted"), Quarantined("Quarantined") }
data class Stats(
    val samples: Long, val alphabet: String, val shannon: Float, val minimum: Float,
    val markov: Float, val chiSquared: Float, val degreesOfFreedom: Int, val pApprox: Float,
    val rctMaximum: Int, val rctPass: Boolean, val aptMaximum: Int, val aptPass: Boolean
)
data class SourceReport(
    val id: SourceId, val sampleKind: String, val health: Health, val windowId: Long, val uptimeMs: Long,
    val raw: Stats?, val conditioned: Stats?, val collected: Long, val accepted: Long,
    val rejected: Long, val creditedBits: Long, val sha3: Boolean
)
data class PoolReport(
    val health: Health, val windowId: Long, val digest: ByteArray,
    val shannon: Float, val minimum: Float, val markov: Float, val chiSquared: Float,
    val degreesOfFreedom: Int, val pApprox: Float, val sourceMask: Int,
    val freshSource: SourceId, val newBytes: Long, val creditedBits: Long
)
sealed interface Message {
    data class Boot(val version: Int, val bootId: Long, val device: String) : Message
    data class Progress(val id: SourceId, val health: Health, val filled: Int, val needed: Int) : Message
    data class Source(val report: SourceReport) : Message
    data class Pool(val report: PoolReport) : Message
    data class WifiSummary(val apCount: Int, val meanRssi: Int, val scanMs: Long) : Message
    data class Diagnostic(val uptimeMs: Long, val heap: Long, val radarFrames: Long, val radarErrors: Long, val connected: Boolean) : Message
    data class WifiMotion(val score: Int, val matched: Int, val previous: Int, val current: Int) : Message
    data class RadarSummary(val engineering: Boolean, val target: Int, val movingEnergy: Int, val stationaryEnergy: Int,
        val movingCm: Int, val stationaryCm: Int, val detectionCm: Int) : Message
}
data class Envelope(val bootId: Long, val sequence: Long, val message: Message, val signedBytes: ByteArray)

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
fun String.hexBytes(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0 && cleaned.all { it in "0123456789abcdefABCDEF" }) { "Use hexadecimal bytes." }
    return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
