@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jupiterlabs.ghostdrive.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jupiterlabs.ghostdrive.R
import com.jupiterlabs.ghostdrive.data.*
import com.jupiterlabs.ghostdrive.protocol.*
import com.jupiterlabs.ghostdrive.tracking.*
import com.jupiterlabs.ghostdrive.util.DevicePlatform
import java.text.SimpleDateFormat
import java.util.Locale

@Composable fun HomeScreen(repo: Repository, live: LiveState, settings: Settings, trips: List<Trip>,
    start: () -> Unit, stop: () -> Unit, demo: () -> Unit, sync: () -> Unit, openFullscreenMap: () -> Unit = {}, onToggleHeyMerlin: () -> Unit = {}, openTab: (Int) -> Unit) {
    val recentLogs by repo.logs.collectAsStateWithLifecycle()
    val onboardStatus by repo.voice.onboard.status.collectAsStateWithLifecycle()
    val cmdStatus by repo.voice.commander.status.collectAsStateWithLifecycle()
    var showMerlinChat by rememberSaveable { mutableStateOf(false) }
    var merlinPrompt by rememberSaveable { mutableStateOf("") }
    var merlinBusy by remember { mutableStateOf(false) }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) {
                merlinPrompt = ""
                repo.voice.commander.executeVoiceCommand(spoken)
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Panel(if(live.demo) "A little test drive" else "Your journey, remembered", "${settings.vehicle} · ${settings.driver} · ${DevicePlatform.deviceShortName} (${DevicePlatform.npuShortName})") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if(live.demo) "SIMULATOR" else if(live.authenticated) "NULLVALON CONNECTED" else "READY WHEN YOU ARE",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(if(live.demo) "Synthetic data" else if(live.gpsActive) "On the road" else "GPS is off",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(live.bleStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    if(settings.showMerlin) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(100.dp)
                        ) {
                            Image(painterResource(R.drawable.merlin), "Merlin", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = if(live.armed || live.demo) stop else start) { Text(if(live.armed || live.demo) "Stop" else "Arm monitoring") }
                    if(!live.armed && !live.demo) OutlinedButton(onClick = demo) { Text("Try simulator") }
                    FilledTonalButton(onClick = openFullscreenMap) {
                        Icon(Icons.Outlined.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cockpit Map")
                    }
                    OutlinedButton(onClick = { repo.voice.speakSitrep() }) {
                        Text("🎙️ Ask Merlin")
                    }
                    FilterChip(
                        selected = settings.heyMerlinEnabled,
                        onClick = onToggleHeyMerlin,
                        label = { Text(if (settings.heyMerlinEnabled) "🟢 Hey Merlin: ON" else "⚪ Hey Merlin: OFF") }
                    )
                    FilterChip(
                        selected = showMerlinChat,
                        onClick = { showMerlinChat = !showMerlinChat },
                        label = { Text("🧠 Voice & Chat") }
                    )
                }
                if (settings.heyMerlinEnabled || showMerlinChat) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "🎙️ \"Hey Merlin\" Onboard Commander (${DevicePlatform.npuShortName})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (cmdStatus.listening) "🟢 Listening" else if (settings.heyMerlinEnabled) "🟡 Armed" else "⚪ Manual",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(cmdStatus.statusLabel, style = MaterialTheme.typography.labelSmall)
                            if (cmdStatus.partialTranscript.isNotBlank()) {
                                Text("Hearing: \"${cmdStatus.partialTranscript}…\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (cmdStatus.lastActionSummary.isNotBlank()) {
                                Text("Last command: ${cmdStatus.lastActionSummary}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                            }
                            Text(
                                "Say: \"Hey Merlin, sitrep\" · \"Hey Merlin, roll a d20\" · \"Hey Merlin, open cockpit map\" · \"Hey Merlin, save this place as [Name]\" · \"Hey Merlin, entropy score\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                AssistChip(
                                    onClick = { repo.voice.commander.executeVoiceCommand("sitrep") },
                                    label = { Text("🚗 Sitrep") }
                                )
                                AssistChip(
                                    onClick = { repo.voice.commander.executeVoiceCommand("roll a d20") },
                                    label = { Text("🎲 Roll d20") }
                                )
                                AssistChip(
                                    onClick = { repo.voice.commander.executeVoiceCommand("watch status") },
                                    label = { Text("⌚ Watch 5 Bio") }
                                )
                                AssistChip(
                                    onClick = { repo.voice.commander.executeVoiceCommand("entropy score") },
                                    label = { Text("⚡ Score Phone") }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = merlinPrompt,
                                    onValueChange = { merlinPrompt = it },
                                    label = { Text("Command or ask Merlin (${DevicePlatform.npuShortName} → ${settings.geminiVoice})") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    enabled = !merlinBusy,
                                    onClick = {
                                        if (repo.voice.commander.hasMicPermission()) {
                                            repo.voice.commander.triggerDirectVoiceCommand()
                                        } else {
                                            runCatching {
                                                val sttIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command or question for Merlin…")
                                                    putExtra("android.speech.extra.ENABLE_FORMATTING", "android.speech.formatting.optimize_quality")
                                                }
                                                speechLauncher.launch(sttIntent)
                                            }
                                        }
                                    }
                                ) {
                                    Text("🎤")
                                }
                                Button(
                                    enabled = !merlinBusy,
                                    onClick = {
                                        val q = merlinPrompt.trim()
                                        merlinPrompt = ""
                                        if (q.isNotBlank()) {
                                            repo.voice.commander.executeVoiceCommand(q)
                                        } else {
                                            repo.voice.speakSitrep()
                                        }
                                    }
                                ) {
                                    Text(if (merlinBusy) "…" else "Run")
                                }
                            }
                            if (onboardStatus.lastResponseText.isNotBlank()) {
                                Text(
                                    "Merlin (${onboardStatus.lastBackendUsed}): \"${onboardStatus.lastResponseText}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                Text(if(live.demo) "These route points and metrics are synthetic. Nothing is uploaded." else "Tracking starts only after your ESP32 is authenticated. You can read history with GPS off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CockpitHud(live, settings, onOpenFullscreenMap = openFullscreenMap)
        }
        item {
            val t = live.trip
            val parked = trips.firstOrNull { it.endMs != null && !it.demo }
            Panel(if(t != null) "Current car session" else "Last recorded stop") {
                if(t != null) {
                    Text("${t.startName} → ${t.endName}", style = MaterialTheme.typography.titleLarge)
                    if(t.vehicle.isNotBlank()) ValueLine("Vehicle", t.vehicle)
                    ValueLine("Session elapsed", duration(t.durationMs)); ValueLine("Moving estimate", duration(t.movingMs))
                    ValueLine("Stationary / unobserved", "${duration(t.stationaryMs)} / ${duration(t.unobservedMs)}")
                    ValueLine("Recorded distance", distance(t.distanceM, settings.displayUnits))
                    ValueLine("Current / peak / avg speed", "${speed(live.lastFix?.speedMps, settings.displayUnits)} · ${speed(t.maxSpeedMps, settings.displayUnits)} · ${speed(t.avgMovingSpeedMps, settings.displayUnits)}")
                    if (live.watch.hrBpm != null || live.watch.steps > 0) {
                        ValueLine("Galaxy Watch 5 bio", "❤️ ${live.watch.hrBpm?.toInt() ?: "—"} BPM (${live.watch.ibiMs ?: "—"} ms IBI) · 🩸 ${live.watch.spo2Pct ?: "—"}% · ${live.watch.steps} steps")
                    }
                    ValueLine("Latest fix", live.lastFix?.let { "±${it.accuracyM.toInt()} m · ${timestamp(it.timeMs)}" } ?: "Waiting for GPS")
                } else if(parked != null) {
                    Text(parked.endName, style = MaterialTheme.typography.titleLarge)
                    if(parked.vehicle.isNotBlank()) ValueLine("Vehicle", parked.vehicle)
                    ValueLine("Session ended", timestamp(parked.endMs)); ValueLine("Position recorded", timestamp(parked.end?.timeMs))
                    Text("Last observed position. A BLE disconnect alone does not confirm that the car remained parked.", style = MaterialTheme.typography.bodySmall)
                } else Text("Your first connected journey will appear here.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openTab(1) }) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(6.dp)); Text("Explore map") }
                    TextButton(onClick = openFullscreenMap) { Icon(Icons.Outlined.Fullscreen, null); Spacer(Modifier.width(6.dp)); Text("Fullscreen HUD") }
                }
            }
        }
        item {
            val pe = live.phoneEntropy
            Panel("Conditioned pool & Tri-Fusion", "SHA3-512 · Car + Watch 5 + Phone EntropicDroid") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(if(live.demo) "Demo" else if(live.pool == null) "Waiting" else if(live.authenticated) "Connected" else "Last received", live.pool?.health != Health.Quarantined)
                    Text("Window ${live.pool?.windowId ?: "—"}", style = MaterialTheme.typography.labelMedium)
                }
                ValueLine("Car Pool Shannon / min-H", "${number(live.pool?.shannon)} / ${number(live.pool?.minimum)} bits/B")
                ValueLine(
                    "Phone EntropicDroid Score",
                    if (pe.scorePct > 0.0) "${"%.1f".format(java.util.Locale.US, pe.scorePct)} / 100 · H_min ${"%.2f".format(java.util.Locale.US, pe.minEntropy)} b/B (${pe.governingEstimator})"
                    else "Calibrating…"
                )
                EntropyGraph(live.poolGraph, false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openTab(3) }) { Text("Open Tri-Fusion & Phone Scores") }
                    TextButton(onClick = { repo.phoneHarvester.sampleOneShotWindow() }) { Text("⚡ Score Phone Now") }
                }
            }
        }
        item {
            Panel("Sources at a glance", "Raw Shannon / minimum · bits per byte") {
                for(id in SourceId.entries) {
                    val state = live.sources.getValue(id)
                    Column(Modifier.fillMaxWidth().clickable { openTab(3) }) {
                        ValueLine(id.label, "${number(state.report?.raw?.shannon)} / ${number(state.report?.raw?.minimum)}")
                        Text("${state.health.label} · ${state.filled} / ${state.needed} B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val observatoryUrl = remember(settings.syncPullEndpoint) {
                if (settings.syncPullEndpoint.isBlank()) ""
                else runCatching {
                    val u = java.net.URI(settings.syncPullEndpoint)
                    "${u.scheme}://${u.host}${if (u.port != -1) ":${u.port}" else ""}/"
                }.getOrDefault("")
            }
            Panel("Optional Private Relay & Sync", live.syncStatus) {
                ValueLine("Queued records", live.queueCount.toString()); ValueLine("Last delivery", timestamp(live.lastSyncMs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = sync) { Text("Sync now") }
                    if (observatoryUrl.isNotBlank()) {
                        TextButton(onClick = { runCatching { uriHandler.openUri(observatoryUrl) } }) { Text("Open Web Observatory") }
                    }
                    TextButton(onClick = { openTab(5) }) { Text("Configure destinations") }
                }
            }
        }
        item {
            Panel("Recent events") { recentLogs.take(3).forEach { Text("${timestamp(it.timeMs)} · ${it.message}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { openTab(4) }) { Text("Open event log") }
            }
        }
    }
}

@Composable fun EntropyScreen(
    repo: Repository,
    settings: Settings,
    live: LiveState,
    exportEntropyBin: () -> Unit = {},
    clearEntropyVault: () -> Unit = {},
    stageWatchOvertimeSample: () -> Unit = {}
) {
    var category by rememberSaveable { mutableStateOf("all") } // "all", "phone", "watch", "vault", "car"
    var selected by remember { mutableStateOf<SourceId?>(null) }
    var full by remember { mutableStateOf(true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Panel("Tri-Fusion Entropy Observatory", "${if(live.demo) "Synthetic demo" else if(live.authenticated) "Authenticated Nullvalon + Watch + Phone" else "Standby · 0% parked drain"}") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(category == "all" && selected == null, { category = "all"; selected = null }, label = { Text("All") })
                    FilterChip(category == "phone", { category = "phone"; selected = null }, label = { Text("📱 Phone Score") })
                    FilterChip(category == "watch", { category = "watch"; selected = null }, label = { Text("⌚ Watch 5") })
                    FilterChip(category == "vault", { category = "vault"; selected = null }, label = { Text("🚗 Pool & Vault") })
                    FilterChip(category == "car", { category = "car"; selected = null }, label = { Text("🔌 Car Sources") })
                    FilterChip(full, { full = !full }, label = { Text(if(full) "Detailed" else "Compact") })
                }
                if (category == "car" || selected != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceId.entries.forEach { id ->
                            FilterChip(selected == id, { selected = if (selected == id) null else id; category = "car" }, label = { Text(id.label) })
                        }
                    }
                }
            }
        }
        if (selected == null && (category == "all" || category == "phone")) item {
            val pe = live.phoneEntropy
            Panel(
                title = DevicePlatform.entropicHeaderTitle,
                subtitle = "${DevicePlatform.editionBannerTitle} · ${pe.status}"
            ) {
                // Top 3-column EntropicDroid scoreboard
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ENTROPIC SCORE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (pe.scorePct > 0.0) "${"%.1f".format(java.util.Locale.US, pe.scorePct)} / 100" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Credit: ${pe.creditedBits}/512 b", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("RAW MIN-H (H∞)", style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (pe.minEntropy > 0.0) "${"%.3f".format(java.util.Locale.US, pe.minEntropy)} b/B" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Shannon: ${if (pe.shannon > 0.0) "%.3f".format(java.util.Locale.US, pe.shannon) else "—"}", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CONDITIONED", style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (pe.outMinEntropy > 0.0) "${"%.3f".format(java.util.Locale.US, pe.outMinEntropy)} b/B" else "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text("SHA3: ${if (pe.outShannon > 0.0) "%.3f".format(java.util.Locale.US, pe.outShannon) else "—"}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                ValueLine("Harvester state", if (pe.active) "🟢 Active (Nullvalon driving link)" else "⚪ Standby (0% drain while parked)")
                ValueLine("SP 800-90B MCV / Markov / t-Tuple", "${"%.3f".format(java.util.Locale.US, pe.mcvMinH)} / ${"%.3f".format(java.util.Locale.US, pe.markovMinH)} / ${"%.3f".format(java.util.Locale.US, pe.tTupleMinH)} b/B")
                ValueLine("Governing estimator", pe.governingEstimator)
                ValueLine("χ² goodness-of-fit (255 df)", "${"%.1f".format(java.util.Locale.US, pe.chiSquare)} · ${if (pe.chiSquareOk) "PASS" else "WARN"}")
                ValueLine("Continuous health (RCT / APT)", "${if (pe.rctTrips == 0L && pe.aptTrips == 0L) "PASS" else "WARN"} (RCT: ${pe.rctTrips} · APT: ${pe.aptTrips})")
                ValueLine("Conditioned blocks / Lifetime raw", "${pe.blocksConditioned} blk · ${pe.totalBytesHarvested} B (${pe.bufferedBytes} B buffered)")

                if (pe.sourceBytes.isNotEmpty() && full) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    Text("Per-source physical contribution (IMU, Timers, DRAM, Thermals, Power):", style = MaterialTheme.typography.labelSmall)
                    val totalSrc = pe.sourceBytes.values.sum().coerceAtLeast(1L)
                    pe.sourceBytes.forEach { (srcName, bCount) ->
                        val pct = (bCount.toDouble() * 100.0 / totalSrc).coerceIn(0.0, 100.0)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(srcName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(68.dp))
                            LinearProgressIndicator(
                                progress = { (pct / 100.0).toFloat() },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(6.dp)
                            )
                            Text("${bCount} B (${"%.0f".format(java.util.Locale.US, pct)}%)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(86.dp))
                        }
                    }
                }

                if (pe.lastBlockDigestHex.isNotBlank()) {
                    Text("Latest Phone SHA3-512 · ${pe.lastBlockDigestHex.take(36)}…", style = MaterialTheme.typography.labelSmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { repo.phoneHarvester.sampleOneShotWindow() }) {
                        Text("⚡ Sample & Score Phone Window (1.5s)")
                    }
                }
            }
        }
        if (selected == null && (category == "all" || category == "watch")) item {
            val w = live.watch
            val v = live.entropyVault
            Panel(
                title = "⌚ Galaxy Watch 5 · Duty-Cycled Batch Harvester",
                subtitle = w.status
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !settings.watchStandbyBatchMode && settings.watchEnabled,
                        onClick = { repo.saveSettings(settings.copy(watchEnabled = true, watchStandbyBatchMode = false)) },
                        label = { Text("🚗 Nullvalon-Only (0% Parked)") }
                    )
                    FilterChip(
                        selected = settings.watchStandbyBatchMode && settings.watchEnabled,
                        onClick = { repo.saveSettings(settings.copy(watchEnabled = true, watchStandbyBatchMode = true)) },
                        label = { Text("⌚ Standby ${settings.watchBatchIntervalSec}s Batch") }
                    )
                    FilterChip(
                        selected = !settings.watchEnabled,
                        onClick = { repo.saveSettings(settings.copy(watchEnabled = !settings.watchEnabled)) },
                        label = { Text(if (settings.watchEnabled) "Stop Watch" else "Arm Watch") }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                ValueLine("Pending overtime watch buffer", "${v.pendingWatchBlocks} block(s) · ${v.pendingWatchBytes} B")
                if (v.pendingWatchBlocks > 0) {
                    val mins = v.pendingSpanMs / 60_000L
                    val spanStr = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                    ValueLine("Over-time averaged HR (min–max)", "${v.pendingAvgHrBpm?.let { "${it.toInt()} BPM (${v.pendingMinHrBpm?.toInt() ?: "—"}–${v.pendingMaxHrBpm?.toInt() ?: "—"})" } ?: "—"} · $spanStr span")
                    ValueLine("Over-time averaged IBI / SpO2 / G", "${v.pendingAvgIbiMs?.let { "${it} ms" } ?: "—"} · ${v.pendingAvgSpo2Pct?.let { "$it%" } ?: "—"} · ${v.pendingAvgWristG?.let { "%.2f G".format(java.util.Locale.US, it) } ?: "—"}")
                }
                ValueLine("Fused master blocks (Nullvalon + Watch)", "${v.fusedBlocksCount} fused / ${v.totalWatchStagedBlocks} total watch blocks")
                ValueLine("Heart Rate / IBI", "${w.hrBpm?.let { "${it.toInt()} BPM" } ?: "—"} · ${w.ibiMs?.let { "${it} ms" } ?: "—"}")
                ValueLine("Blood Oxygen (SpO2) / Barometer", "${w.spo2Pct?.let { "$it%" } ?: "—"} · ${w.pressureHpa?.let { "%.1f hPa".format(java.util.Locale.US, it) } ?: "—"}")
                ValueLine("Wrist IMU G-force / Steps", "${w.wristG?.let { "%.2f G".format(java.util.Locale.US, it) } ?: "—"} · ${w.steps} steps")
                ValueLine("Watch window Shannon / min-entropy", "${number(w.shannon)} / ${number(w.minEntropy)} bits/B")
                if (w.lastWatchDigestHex.isNotBlank()) {
                    Text("Latest staged Watch SHA3-512 · ${w.lastWatchDigestHex.take(36)}…", style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = stageWatchOvertimeSample) {
                        Text("+ Stage Overtime Watch Window")
                    }
                }
            }
        }
        if (selected == null && (category == "all" || category == "vault")) item {
            val p = live.pool
            val v = live.entropyVault
            @Suppress("DEPRECATION")
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var drawnLabel by remember { mutableStateOf("") }
            var drawnValue by remember { mutableStateOf("") }
            var drawCounter by remember { mutableIntStateOf(0) }

            fun drawSpongeBytes(label: String): ByteArray {
                drawCounter++
                val sha3 = org.bouncycastle.crypto.digests.SHA3Digest(512)
                val domain = "GHOSTDRIVE-ONDEVICE-DRAW-v1\u0000".toByteArray(Charsets.UTF_8)
                sha3.update(domain, 0, domain.size)
                val seedHex = v.lastDigestHex.ifBlank { live.phoneEntropy.lastBlockDigestHex.ifBlank { live.watch.lastWatchDigestHex } }
                if (seedHex.length >= 64) {
                    val clean = seedHex.take(128)
                    val seedBytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                    sha3.update(seedBytes, 0, seedBytes.size)
                }
                val meta = java.nio.ByteBuffer.allocate(20)
                    .putLong(System.nanoTime())
                    .putLong(System.currentTimeMillis())
                    .putInt(drawCounter)
                    .array()
                sha3.update(meta, 0, meta.size)
                val lBytes = label.toByteArray(Charsets.UTF_8)
                sha3.update(lBytes, 0, lBytes.size)
                val out = ByteArray(64)
                sha3.doFinal(out, 0)
                return out
            }

            Panel("Master Tri-Fusion Vault & Conditioned Pool", "Authenticated SHA3-512 sponge · rolling 1 MiB cap") {
                EntropyGraph(live.poolGraph, false)
                ValueLine("Vault blocks / bytes", "${v.blocks} blocks · ${v.totalBytes} B (${"%.1f".format(java.util.Locale.US, v.totalBytes / 1024.0)} KiB)")
                ValueLine("Vault mean Shannon / min-H", "${number(v.avgShannon)} / ${number(v.avgMinEntropy)} bits/B")
                ValueLine("Live pool Shannon / min-H", "${number(p?.shannon)} / ${number(p?.minimum)} bits/B")
                if (full) {
                    ValueLine("Markov / χ² (dof)", "${number(p?.markov)} b/b · ${number(p?.chiSquared)} / ${p?.degreesOfFreedom ?: "—"}")
                    ValueLine("Fresh contribution", p?.let { "${it.freshSource.label} · ${it.newBytes} B" } ?: "—")
                }
                if (v.lastNullvalonHex.isNotBlank() && (v.lastWatchFoldedHex.isNotBlank() || v.lastPhoneHex.isNotBlank())) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Latest SHA3-512 Tri-Fusion Proof (${v.lastFusedCount} watch blk, ${v.phoneBlocksCount} phone blk):", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("1. Car Nullvalon TRNG/RF: ${v.lastNullvalonHex.take(32)}…", style = MaterialTheme.typography.labelSmall)
                    if (v.lastWatchFoldedHex.isNotBlank()) {
                        Text("2. + Body Watch Bio Fold:  ${v.lastWatchFoldedHex.take(32)}…", style = MaterialTheme.typography.labelSmall)
                    }
                    if (v.lastPhoneHex.isNotBlank()) {
                        Text("3. + Phone EntropicDroid:  ${v.lastPhoneHex.take(32)}…", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("4. = Master Vault Sponge:  ${v.lastDigestHex.take(32)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else if (v.lastDigestHex.isNotBlank()) {
                    Text("Latest 512-bit block · ${v.lastDigestHex.take(48)}…", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Draw conditioned bits from Tri-Fusion / Phone sponge:", style = MaterialTheme.typography.labelSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        val b = drawSpongeBytes("key256")
                        val hexKey = b.take(32).joinToString("") { "%02x".format(it) }
                        drawnLabel = "🔑 256-bit Key (copied to clipboard)"
                        drawnValue = hexKey
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(hexKey))
                    }) { Text("🔑 256-bit Key") }
                    OutlinedButton(onClick = {
                        val b = drawSpongeBytes("uuid").copyOf(16)
                        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
                        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
                        val h = b.joinToString("") { "%02x".format(it) }
                        val uuidStr = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}"
                        drawnLabel = "🆔 Conditioned UUIDv4 (copied)"
                        drawnValue = uuidStr
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(uuidStr))
                    }) { Text("🆔 UUIDv4") }
                    OutlinedButton(onClick = {
                        val b = drawSpongeBytes("d20")
                        val roll = ((java.nio.ByteBuffer.wrap(b, 0, 8).long and Long.MAX_VALUE) % 20L).toInt() + 1
                        val proof = b.take(6).joinToString("") { "%02x".format(it) }
                        drawnLabel = "🎲 Physical d20 Roll (SHA3 proof $proof)"
                        drawnValue = "Rolled $roll / 20"
                    }) { Text("🎲 Roll d20") }
                }
                if (drawnValue.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(drawnLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(drawnValue, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = exportEntropyBin, enabled = v.blocks > 0) { Text("Export bitstream · .bin") }
                    if(v.blocks > 0) OutlinedButton(onClick = clearEntropyVault) { Text("Clear vault") }
                }
            }
        }
        items(
            SourceId.entries.filter {
                if (selected != null) selected == it
                else category == "all" || category == "car"
            },
            key = { it.name }
        ) { id ->
            val s = live.sources.getValue(id); val r = s.report
            Panel(id.label, "${s.health.label} · last window ${timestamp(s.reportMs.takeIf { it > 0 })}") {
                LinearProgressIndicator(progress = { (s.filled.toFloat() / s.needed.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                ValueLine("Current window", "${s.filled} / ${s.needed} B")
                EntropyGraph(s.graph)
                ValueLine("Raw Shannon / minimum", "${number(r?.raw?.shannon)} / ${number(r?.raw?.minimum)} bits/B")
                ValueLine("Conditioned Shannon / minimum", "${number(r?.conditioned?.shannon)} / ${number(r?.conditioned?.minimum)} bits/B")
                if(full) {
                    StatsView("Raw samples", r?.raw); StatsView("Conditioned digest history", r?.conditioned)
                    ValueLine("Collected / accepted", "${r?.collected ?: "—"} / ${r?.accepted ?: "—"} B")
                    ValueLine("Rejected", "${r?.rejected ?: "—"} B")
                    val total = r?.let { it.accepted + it.rejected } ?: 0
                    ValueLine("Window acceptance", if(total > 0) "${(100.0 * r!!.accepted / total).toInt()}%" else "Waiting")
                    ValueLine("Conditioner", if(r == null) "—" else if(r.sha3) "SHA3-512" else "Not reported")
                    ValueLine("Credited bits", r?.creditedBits?.toString() ?: "—")
                    Text("Sample type: ${r?.sampleKind ?: "not yet reported"}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Panel("Vehicle environment", "Radar and RF observations describe the car and surroundings.") {
                ValueLine("Wi-Fi access points", live.wifi?.apCount?.toString() ?: "—")
                ValueLine("Mean RSSI", live.wifi?.let { "${it.meanRssi} dBm" } ?: "—")
                ValueLine("RF environment change", live.wifiMotion?.let { "${it.score} / 100" } ?: "—")
                ValueLine("Radar moving / stationary energy", "${live.radar?.movingEnergy ?: "—"} / ${live.radar?.stationaryEnergy ?: "—"}")
                ValueLine("Radar detection distance", live.radar?.let { "${it.detectionCm} cm" } ?: "—")
                ValueLine("Engineering frames", live.radar?.engineering?.let { if(it) "Enabled" else "Not active" } ?: "—")
                ValueLine("Radar valid / errors", "${live.diagnostic?.radarFrames ?: "—"} / ${live.diagnostic?.radarErrors ?: "—"}")
                ValueLine("Free ESP heap", live.diagnostic?.let { "${it.heap} B" } ?: "—")
                ValueLine("Boot / sequence", "${live.bootId ?: "—"} / ${live.sequence}")
                ValueLine("Missing packets", live.gaps.toString())
                Text("RF change is a heuristic, not vehicle speed. Slow sources may take longer to complete an 8 KiB window.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun StatsView(label: String, s: Stats?) {
    HorizontalDivider(); Text(label, style = MaterialTheme.typography.labelLarge)
    if(s == null) Text("No completed statistics", style = MaterialTheme.typography.bodySmall) else {
        ValueLine("Sample count", s.samples.toString()); ValueLine("Markov minimum", "${number(s.markov)} bits/bit")
        ValueLine("χ² / dof / p≈", "${number(s.chiSquared)} / ${s.degreesOfFreedom} / ${number(s.pApprox)}")
        ValueLine("Repetition count test", "${if(s.rctPass) "Pass" else "Fail"} · max run ${s.rctMaximum}")
        ValueLine("Adaptive proportion test", "${if(s.aptPass) "Pass" else "Fail"} · max ${s.aptMaximum} / 512")
    }
}

@Composable fun HistoryScreen(trips: List<Trip>, settings: Settings, places: List<Place>, onFullscreenTrip: (Trip) -> Unit = {}, onTrip: (Trip) -> Unit) {
    var search by remember { mutableStateOf("") }
    var showDemo by remember { mutableStateOf(false) }
    var journalTab by remember { mutableStateOf(0) } // 0 = Daily Review, 1 = All Trips

    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dayHeadingFormat = remember { SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val todayKey = remember { dayFormat.format(System.currentTimeMillis()) }
    val yesterdayKey = remember { dayFormat.format(System.currentTimeMillis() - 86_400_000L) }

    val filtered = trips.filter { (showDemo || !it.demo) && (search.isBlank() || (it.startName + " " + it.endName + " " + timestamp(it.startMs)).contains(search, true)) }

    val tripsByDay = remember(filtered) {
        filtered.groupBy { dayFormat.format(it.startMs) }
            .toList()
            .sortedByDescending { it.first }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Panel("Your journey journal", "Daily places visited, dwell times, and connected car sessions") {
                OutlinedTextField(search, { search = it }, label = { Text("Search places or dates") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(showDemo, { showDemo = it }); Text("Include simulator trips") }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = journalTab == 0,
                        onClick = { journalTab = 0 },
                        label = { Text("📅 Daily Review (${tripsByDay.size} days)") }
                    )
                    FilterChip(
                        selected = journalTab == 1,
                        onClick = { journalTab = 1 },
                        label = { Text("🛣️ All Journeys (${filtered.size})") }
                    )
                }

                ValueLine("Recorded journeys", filtered.size.toString())
                ValueLine("Distance in view", distance(filtered.sumOf { it.distanceM }, settings.displayUnits))
                ValueLine("Session time in view", duration(filtered.sumOf { it.durationMs }))
            }
        }

        if (filtered.isEmpty()) {
            item { Panel("No journeys yet") { Text("Arm monitoring in the main tab, or drive with Ghost Drive to generate daily reviews.") } }
        } else if (journalTab == 0) {
            // DAILY PLACES & TRIPS DAY-END REVIEW
            items(tripsByDay, key = { it.first }) { (dayKey, dayTrips) ->
                val sortedDayTrips = dayTrips.sortedBy { it.startMs }
                val dayDist = sortedDayTrips.sumOf { it.distanceM }
                val dayMoving = sortedDayTrips.sumOf { it.movingMs }
                val dayParked = sortedDayTrips.drop(1).sumOf { it.parkingBeforeMs ?: 0 }
                val dayMaxSpeed = sortedDayTrips.maxOfOrNull { it.maxSpeedMps } ?: 0f

                val heading = when (dayKey) {
                    todayKey -> "Today · ${dayHeadingFormat.format(sortedDayTrips.first().startMs)}"
                    yesterdayKey -> "Yesterday · ${dayHeadingFormat.format(sortedDayTrips.first().startMs)}"
                    else -> dayHeadingFormat.format(sortedDayTrips.first().startMs)
                }

                Panel(
                    title = "📅 $heading",
                    subtitle = "${sortedDayTrips.size} journey leg(s) · ${distance(dayDist, settings.displayUnits)} total"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            ValueLine("Active driving", duration(dayMoving))
                            if (dayParked > 0) ValueLine("Inferred parked", duration(dayParked))
                            ValueLine("Peak speed", speed(dayMaxSpeed, settings.displayUnits))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Day Timeline & Places Visited", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                    val firstTrip = sortedDayTrips.first()
                    Text("🌅 First Departure: ${firstTrip.startName} (${timeFormat.format(firstTrip.startMs)})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)

                    sortedDayTrips.forEachIndexed { idx, t ->
                        Surface(
                            onClick = { onTrip(t) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(
                                    "🚗 Leg ${idx + 1}: ${t.startName} → ${t.endName} · ${distance(t.distanceM, settings.displayUnits)} (${duration(t.durationMs)})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                val isLast = idx == sortedDayTrips.size - 1
                                val arrTime = timeFormat.format(t.end?.timeMs ?: t.endMs ?: t.startMs)
                                val nextTrip = sortedDayTrips.getOrNull(idx + 1)
                                val stopDwellMs = nextTrip?.parkingBeforeMs ?: nextTrip?.let { (it.startMs - (t.endMs ?: t.startMs)).coerceAtLeast(0) }
                                Text(
                                    if (isLast) "🌙 Day-End Destination: ${t.endName} (Arrived $arrTime · Parked for night)"
                                    else "📍 Stop #${idx + 1}: ${t.endName} (Arrived $arrTime${stopDwellMs?.takeIf { it > 0 }?.let { " · Parked ${duration(it)}" } ?: ""})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    val dayCompositeTrip = sortedDayTrips.first().copy(
                        id = "day:$dayKey",
                        endMs = sortedDayTrips.last().endMs ?: sortedDayTrips.last().startMs,
                        end = sortedDayTrips.last().end ?: sortedDayTrips.first().end,
                        endName = sortedDayTrips.last().endName,
                        distanceM = dayDist,
                        durationMs = sortedDayTrips.sumOf { it.durationMs },
                        movingMs = dayMoving,
                        maxSpeedMps = dayMaxSpeed
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { onTrip(dayCompositeTrip) }) {
                            Icon(Icons.Outlined.Route, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Replay Day (${sortedDayTrips.size} legs)")
                        }
                        OutlinedButton(onClick = { onFullscreenTrip(dayCompositeTrip) }) {
                            Icon(Icons.Outlined.Fullscreen, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Cockpit")
                        }
                    }
                }
            }
        } else {
            // ALL JOURNEYS TAB (RAW TRIP LIST)
            items(filtered, key = { it.id }) { t ->
                Panel("${if(t.demo) "DEMO · " else ""}${t.startName} → ${t.endName}", "${timestamp(t.startMs)}${if(t.vehicle.isNotBlank()) " · ${t.vehicle}" else ""}") {
                    ValueLine("Session duration", duration(t.durationMs)); ValueLine("Moving / stationary estimate", "${duration(t.movingMs)} / ${duration(t.stationaryMs)}")
                    ValueLine("Unobserved GPS time", duration(t.unobservedMs))
                    ValueLine("Recorded distance", distance(t.distanceM, settings.displayUnits))
                    ValueLine("Peak / avg moving speed", "${speed(t.maxSpeedMps, settings.displayUnits)} / ${speed(t.avgMovingSpeedMps, settings.displayUnits)}")
                    ValueLine("Start / end", "${timestamp(t.startMs)} / ${timestamp(t.endMs)}")
                    t.parkingBeforeMs?.let { ValueLine("Previous stop near this start", duration(it)) }
                    if(t.endReason != null) Text(t.endReason, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onTrip(t) }) { Icon(Icons.Outlined.Route, null); Spacer(Modifier.width(6.dp)); Text("View route") }
                        TextButton(onClick = { onFullscreenTrip(t) }) { Icon(Icons.Outlined.Fullscreen, null); Spacer(Modifier.width(6.dp)); Text("Fullscreen cockpit") }
                    }
                }
            }
        }

        item {
            Panel("Saved places") {
                places.filter { it.userDefined }.forEach { p ->
                    val visits = trips.count { !it.demo && it.endName == p.name && it.endMs != null }
                    val parkedMs = trips.filter { !it.demo && it.startName == p.name }.sumOf { it.parkingBeforeMs ?: 0 }
                    ValueLine(p.name, "$visits recorded stops · ${duration(parkedMs)} inferred parked")
                }
                if(places.none { it.userDefined }) Text("Save Home, Work, or custom bases in Maps to recognize future endpoints.")
            }
        }
    }
}

@Composable fun LogsScreen(logs: List<LogEvent>) {
    var query by remember { mutableStateOf("") }; var warnings by remember { mutableStateOf(false) }
    val filtered = logs.filter { (!warnings || it.level == "WARN" || it.level == "ERROR") && (it.message + it.category).contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Panel("Event log", "Most recent 500 events · full retained log available in export") {
            OutlinedTextField(query, { query = it }, label = { Text("Search events") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            FilterChip(warnings, { warnings = !warnings }, label = { Text("Warnings only") })
        } }
        if(filtered.isEmpty()) item { Text("No matching events", Modifier.padding(16.dp)) }
        items(filtered, key = { it.id }) { e ->
            Panel("${e.category} · ${e.level}", timestamp(e.timeMs)) { Text(e.message, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
