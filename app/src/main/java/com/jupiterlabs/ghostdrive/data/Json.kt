package com.jupiterlabs.ghostdrive.data

import com.jupiterlabs.ghostdrive.protocol.*
import com.jupiterlabs.ghostdrive.tracking.*
import org.json.JSONObject

fun Fix.json() = JSONObject().put("latitude", latitude).put("longitude", longitude).put("time_ms", timeMs)
    .put("elapsed_ms", elapsedMs).put("accuracy_m", accuracyM).put("speed_mps", speedMps ?: JSONObject.NULL)
    .put("bearing_deg", bearingDeg ?: JSONObject.NULL)
    .put("entropy_shannon", entropyShannon ?: JSONObject.NULL)
    .put("rf_score", rfScore ?: JSONObject.NULL)
    .put("radar_energy", radarEnergy ?: JSONObject.NULL)
    .put("hr_bpm", hrBpm?.toDouble() ?: JSONObject.NULL)
    .put("ibi_ms", ibiMs ?: JSONObject.NULL)
    .put("spo2_pct", spo2Pct ?: JSONObject.NULL)
    .put("wrist_g", wristG?.toDouble() ?: JSONObject.NULL)
    .put("watch_steps", watchSteps ?: JSONObject.NULL)
fun fixFromJson(o: JSONObject) = Fix(
    latitude = o.getDouble("latitude"),
    longitude = o.getDouble("longitude"),
    timeMs = o.getLong("time_ms"),
    elapsedMs = o.getLong("elapsed_ms"),
    accuracyM = o.getDouble("accuracy_m").toFloat(),
    speedMps = if (o.isNull("speed_mps")) null else o.getDouble("speed_mps").toFloat(),
    bearingDeg = if (!o.has("bearing_deg") || o.isNull("bearing_deg")) null else o.getDouble("bearing_deg").toFloat(),
    entropyShannon = if (!o.has("entropy_shannon") || o.isNull("entropy_shannon")) null else o.getDouble("entropy_shannon").toFloat(),
    rfScore = if (!o.has("rf_score") || o.isNull("rf_score")) null else o.getInt("rf_score"),
    radarEnergy = if (!o.has("radar_energy") || o.isNull("radar_energy")) null else o.getInt("radar_energy"),
    hrBpm = if (!o.has("hr_bpm") || o.isNull("hr_bpm")) null else o.getDouble("hr_bpm").toFloat(),
    ibiMs = if (!o.has("ibi_ms") || o.isNull("ibi_ms")) null else o.getInt("ibi_ms"),
    spo2Pct = if (!o.has("spo2_pct") || o.isNull("spo2_pct")) null else o.getInt("spo2_pct"),
    wristG = if (!o.has("wrist_g") || o.isNull("wrist_g")) null else o.getDouble("wrist_g").toFloat(),
    watchSteps = if (!o.has("watch_steps") || o.isNull("watch_steps")) null else o.getInt("watch_steps")
)
fun Trip.json(): JSONObject = JSONObject().put("id", id).put("start_ms", startMs).put("end_ms", endMs ?: JSONObject.NULL)
    .put("duration_ms", durationMs).put("moving_ms", movingMs).put("stationary_ms", stationaryMs).put("unobserved_ms", unobservedMs).put("distance_m", distanceM)
    .put("start", start?.json() ?: JSONObject.NULL).put("end", end?.json() ?: JSONObject.NULL)
    .put("start_name", startName).put("end_name", endName).put("end_reason", endReason ?: JSONObject.NULL)
    .put("boot_id", bootId).put("parking_before_ms", parkingBeforeMs ?: JSONObject.NULL).put("demo", demo)
    .put("max_speed_mps", maxSpeedMps.toDouble()).put("vehicle", vehicle)
fun tripFromJson(o: JSONObject) = Trip(o.getString("id"), o.getLong("start_ms"), if (o.isNull("end_ms")) null else o.getLong("end_ms"),
    o.getLong("duration_ms"), o.getLong("moving_ms"), o.getDouble("distance_m"), o.optJSONObject("start")?.let(::fixFromJson),
    o.optJSONObject("end")?.let(::fixFromJson), o.getString("start_name"), o.getString("end_name"),
    if (o.isNull("end_reason")) null else o.getString("end_reason"), o.getLong("boot_id"),
    if (o.isNull("parking_before_ms")) null else o.getLong("parking_before_ms"), o.optBoolean("demo"), o.optLong("stationary_ms", 0),
    o.optDouble("max_speed_mps", 0.0).toFloat(), o.optString("vehicle", ""))
fun Place.json() = JSONObject().put("id", id).put("name", name).put("latitude", latitude).put("longitude", longitude)
    .put("radius_m", radiusM).put("user_defined", userDefined)
fun placeFromJson(o: JSONObject) = Place(
    o.optString("id", java.util.UUID.randomUUID().toString()),
    o.optString("name", "Saved Base"),
    o.optDouble("latitude", 0.0),
    o.optDouble("longitude", 0.0),
    o.optDouble("radius_m", o.optDouble("radius", 150.0)),
    o.optBoolean("user_defined", true)
)
fun Stats.json() = JSONObject().put("sample_count", samples).put("alphabet", alphabet)
    .put("shannon_bits_per_byte", shannon).put("min_entropy_bits_per_byte", minimum).put("markov_min_bits_per_bit", markov)
    .put("chi_squared_uniform", chiSquared).put("chi_squared_dof", degreesOfFreedom).put("chi_squared_p_approx", pApprox)
    .put("rct_max_run_bytes", rctMaximum).put("rct_pass", rctPass).put("apt_max_frequency_in_512", aptMaximum).put("apt_pass", aptPass)
fun Message.json(): JSONObject = when (this) {
    is Message.Boot -> JSONObject().put("type", "boot").put("version", version).put("boot_id", bootId).put("device", device)
    is Message.Progress -> JSONObject().put("type", "progress").put("source", id.wireName).put("status", health.name).put("filled", filled).put("needed", needed)
    is Message.Source -> report.run { JSONObject().put("type", "source").put("source", id.wireName).put("sample_kind", sampleKind)
        .put("status", health.name).put("window_id", windowId).put("uptime_ms", uptimeMs).put("raw", raw?.json() ?: JSONObject.NULL)
        .put("conditioned", conditioned?.json() ?: JSONObject.NULL).put("collected_bytes", collected).put("accepted_bytes", accepted)
        .put("rejected_bytes", rejected).put("credited_bits", creditedBits).put("conditioner_sha3_512", sha3) }
    is Message.Pool -> report.run { JSONObject().put("type", "pool").put("status", health.name).put("window_id", windowId)
        .put("conditioned_digest_hex", digest.hex()).put("shannon_bits_per_byte", shannon).put("min_entropy_bits_per_byte", minimum)
        .put("markov_min_bits_per_bit", markov).put("chi_squared_uniform", chiSquared).put("chi_squared_dof", degreesOfFreedom)
        .put("chi_squared_p_approx", pApprox).put("accepted_sources_mask", sourceMask).put("fresh_source", freshSource.wireName)
        .put("newly_accepted_source_bytes", newBytes).put("credited_bits", creditedBits) }
    is Message.WifiSummary -> JSONObject().put("type", "wifi_summary").put("ap_count", apCount).put("mean_rssi", meanRssi).put("scan_duration_ms", scanMs)
    is Message.Diagnostic -> JSONObject().put("type", "diagnostic").put("uptime_ms", uptimeMs).put("free_heap", heap).put("radar_frames", radarFrames).put("radar_errors", radarErrors).put("ble_connected", connected)
    is Message.WifiMotion -> JSONObject().put("type", "wifi_motion").put("score", score).put("matched_aps", matched).put("previous_aps", previous).put("current_aps", current)
    is Message.RadarSummary -> JSONObject().put("type", "radar_summary").put("engineering", engineering).put("target_state", target)
        .put("moving_energy", movingEnergy).put("stationary_energy", stationaryEnergy).put("moving_distance_cm", movingCm)
        .put("stationary_distance_cm", stationaryCm).put("detection_distance_cm", detectionCm)
}
