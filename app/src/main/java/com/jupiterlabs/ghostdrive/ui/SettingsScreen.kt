@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jupiterlabs.ghostdrive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jupiterlabs.ghostdrive.data.*
import com.jupiterlabs.ghostdrive.sync.PrivateEndpoint
import com.jupiterlabs.ghostdrive.tracking.GEMINI_VOICES
import com.jupiterlabs.ghostdrive.tracking.MerlinOnboardEngine
import com.jupiterlabs.ghostdrive.util.DevicePlatform
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(
    repo: Repository,
    settings: Settings,
    importMap: () -> Unit,
    downloadMap: () -> Unit,
    importPlaces: () -> Unit,
    export: () -> Unit,
    importArchive: () -> Unit,
    shareArchive: () -> Unit,
    exportGpx: () -> Unit,
    appPermissions: () -> Unit,
    battery: () -> Unit,
    reconnect: () -> Unit,
    removeDemo: () -> Unit,
    pullSync: () -> Unit,
    discoveredDevices: List<DiscoveredDevice>,
    isScanningDevices: Boolean,
    onScanDevices: () -> Unit,
    mapStatus: String,
    notify: (String) -> Unit
) {
    var section by rememberSaveable { mutableIntStateOf(0) } // 0: Merlin & Look, 1: Entropy & Watch, 2: Car & GPS, 3: Sync & Data
    var draft by remember(settings) { mutableStateOf(settings) }
    var gps by remember(settings) { mutableStateOf(settings.gpsIntervalSeconds.toString()) }
    var accuracy by remember(settings) { mutableStateOf(settings.maxAccuracyM.toString()) }
    var movement by remember(settings) { mutableStateOf(settings.movingThresholdMps.toString()) }
    var interval by remember(settings) { mutableStateOf(settings.syncIntervalSeconds.toString()) }
    var retention by remember(settings) { mutableStateOf(settings.retentionDays.toString()) }
    var graphCount by remember(settings) { mutableStateOf(settings.graphSamples.toString()) }
    var historyToken by rememberSaveable { mutableStateOf("") }
    var entropyToken by rememberSaveable { mutableStateOf("") }
    var showTokenFields by rememberSaveable { mutableStateOf(false) }
    var geminiApiKey by rememberSaveable { mutableStateOf("") }
    var hasGeminiKey by remember { mutableStateOf(repo.vault.present("gemini_api_key")) }
    var editingGeminiKey by rememberSaveable { mutableStateOf(false) }
    val availableVoices = remember { repo.voice.getAvailableVoices() }
    val live by repo.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun saveAllSettings() {
        var savedKeysCount = 0
        if (geminiApiKey.isNotBlank()) {
            repo.vault.put("gemini_api_key", geminiApiKey.trim().toByteArray())
            hasGeminiKey = true
            editingGeminiKey = false
            geminiApiKey = ""
            savedKeysCount++
            repo.voice.gemini.prewarmVoiceAsync(draft.geminiVoice)
        }
        if (historyToken.isNotBlank()) {
            repo.vault.put("history_token", historyToken.trim().toByteArray())
            historyToken = ""
            savedKeysCount++
        }
        if (entropyToken.isNotBlank()) {
            repo.vault.put("entropy_token", entropyToken.trim().toByteArray())
            entropyToken = ""
            savedKeysCount++
        }

        try {
            require(draft.driver.isNotBlank() && draft.vehicle.isNotBlank()) { "Set driver and vehicle labels." }
            require(draft.deviceAddress.isBlank() || draft.deviceAddress.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) { "Use a Bluetooth MAC address or leave it blank." }
            val g = gps.toIntOrNull() ?: 1
            val a = accuracy.toFloatOrNull() ?: 75f
            val m = movement.toFloatOrNull() ?: 1.5f
            val i = interval.toIntOrNull() ?: 15
            val r = retention.toIntOrNull() ?: 90
            val n = graphCount.toIntOrNull() ?: 120
            require(g in 1..60 && a in 5f..200f && m in .5f..10f && i in 5..300 && r in 0..3650 && n in 30..600) { "One of the numeric settings is outside its shown range." }
            listOf(draft.historyEndpoint, draft.entropyEndpoint, draft.syncPullEndpoint).filter { it.isNotBlank() }.forEach { PrivateEndpoint.parse(it) }

            val updated = draft.copy(
                gpsIntervalSeconds = g,
                maxAccuracyM = a,
                movingThresholdMps = m,
                syncIntervalSeconds = i,
                retentionDays = r,
                graphSamples = n,
                historyEndpoint = draft.historyEndpoint.trim(),
                entropyEndpoint = draft.entropyEndpoint.trim(),
                syncPullEndpoint = draft.syncPullEndpoint.trim()
            )
            draft = updated
            repo.saveSettings(updated)
            repo.voice.applyVoiceConfig(updated)
            notify("Settings saved" + if (savedKeysCount > 0) " (with updated credentials)" else "")
        } catch (e: Exception) {
            notify(e.message ?: "Check settings")
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Clean Category Bar
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = section == 0,
                    onClick = { section = 0 },
                    label = { Text("✨ Merlin & Look") }
                )
                FilterChip(
                    selected = section == 1,
                    onClick = { section = 1 },
                    label = { Text("🎲 Entropy & Watch") }
                )
                FilterChip(
                    selected = section == 2,
                    onClick = { section = 2 },
                    label = { Text("🚗 Car & GPS") }
                )
                FilterChip(
                    selected = section == 3,
                    onClick = { section = 3 },
                    label = { Text("☁️ Sync & Data") }
                )
            }
        }

        // ── SECTION 0: MERLIN VOICE & APPEARANCE ────────────────────────────
        if (section == 0) {
            item {
                Panel("Appearance & Driver", "Toggles and chips save immediately.") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Theme", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(draft.theme == "midnight", { val next = draft.copy(theme = "midnight"); draft = next; repo.saveSettings(next) }, label = { Text("Midnight") })
                            FilterChip(draft.theme == "matrix", { val next = draft.copy(theme = "matrix"); draft = next; repo.saveSettings(next) }, label = { Text("Matrix") })
                            FilterChip(draft.theme == "merlin", { val next = draft.copy(theme = "merlin"); draft = next; repo.saveSettings(next) }, label = { Text("Merlin") })
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Map filter", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(draft.mapStyle == "auto", { val next = draft.copy(mapStyle = "auto"); draft = next; repo.saveSettings(next) }, label = { Text("Auto") })
                            FilterChip(draft.mapStyle == "night", { val next = draft.copy(mapStyle = "night"); draft = next; repo.saveSettings(next) }, label = { Text("Night") })
                            FilterChip(draft.mapStyle == "matrix", { val next = draft.copy(mapStyle = "matrix"); draft = next; repo.saveSettings(next) }, label = { Text("Matrix") })
                            FilterChip(draft.mapStyle == "day", { val next = draft.copy(mapStyle = "day"); draft = next; repo.saveSettings(next) }, label = { Text("Day") })
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Distance units", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(draft.displayUnits == "mi", { val next = draft.copy(displayUnits = "mi"); draft = next; repo.saveSettings(next) }, label = { Text("Miles (mi)") })
                            FilterChip(draft.displayUnits == "km", { val next = draft.copy(displayUnits = "km"); draft = next; repo.saveSettings(next) }, label = { Text("Kilometers (km)") })
                        }
                    }
                    Toggle("Show Merlin artwork", draft.showMerlin) { val next = draft.copy(showMerlin = it); draft = next; repo.saveSettings(next) }
                    Toggle("Keep screen awake while open", draft.keepScreenOn) { val next = draft.copy(keepScreenOn = it); draft = next; repo.saveSettings(next) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = draft.driver,
                            onValueChange = { draft = draft.copy(driver = it) },
                            label = { Text("Driver") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = draft.vehicle,
                            onValueChange = { draft = draft.copy(vehicle = it) },
                            label = { Text("Vehicle") },
                            singleLine = true,
                            modifier = Modifier.weight(1.3f)
                        )
                    }
                }
            }

            item {
                val onboardStatus by repo.voice.onboard.status.collectAsStateWithLifecycle()
                val cmdStatus by repo.voice.commander.status.collectAsStateWithLifecycle()
                var testingRouteId by remember { mutableStateOf<String?>(null) }
                var showAddNodeForm by rememberSaveable { mutableStateOf(false) }
                var newNodeName by rememberSaveable { mutableStateOf("") }
                var newNodeUrl by rememberSaveable { mutableStateOf("") }
                var newNodeType by rememberSaveable { mutableStateOf("OPENAI") }
                var newNodeModel by rememberSaveable { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    if (onboardStatus.routesStatus.isEmpty()) {
                        repo.voice.onboard.refreshStatus()
                    }
                }

                Panel(
                    "🧠 Onboard Brain & Tailnet / LAN Mesh (S25 Ultra ⇄ Pixel 11 Pro XL)",
                    "${DevicePlatform.editionBannerTitle} · ${DevicePlatform.npuFullName}"
                ) {
                    Toggle("🎙️ \"Hey Merlin\" Hands-Free Wake-Word & Voice Commands", draft.heyMerlinEnabled) {
                        val next = draft.copy(heyMerlinEnabled = it)
                        draft = next
                        repo.saveSettings(next)
                        repo.voice.commander.syncArmedState(it)
                        if (it && !repo.voice.commander.hasMicPermission()) {
                            notify("Tap the '🎙️ Hey Merlin' chip in the top bar once to grant Microphone permission!")
                        }
                    }
                    Toggle("Onboard / Mesh Brain Routing (${draft.geminiVoice} Voice)", draft.onboardBrainEnabled) {
                        val next = draft.copy(onboardBrainEnabled = it)
                        draft = next
                        repo.saveSettings(next)
                    }
                    ValueLine("Detected Hardware", DevicePlatform.editionBannerTitle)
                    ValueLine("Neural Accelerator", "${DevicePlatform.npuFullName} (${DevicePlatform.cpuSimdName})")
                    ValueLine("Wake-Word Spotter", cmdStatus.statusLabel)
                    ValueLine(
                        "On-Device Speech Pack",
                        if (cmdStatus.onDeviceModelInstalled) "🟢 Installed (${DevicePlatform.npuShortName} Offline Speech)"
                        else if (cmdStatus.onDeviceModelSupported) "🟡 Supported (Tap below to pre-download)"
                        else "⚡ Hybrid Android SpeechRecognizer"
                    )
                    ValueLine("Onboard NPU Status", onboardStatus.aiCoreStateLabel)
                    ValueLine("Last Active Route", onboardStatus.lastBackendUsed)
                    if (onboardStatus.activeMeshNode.isNotBlank()) {
                        ValueLine("Active Mesh Node", onboardStatus.activeMeshNode)
                    }
                    if (onboardStatus.discoveredGgufModels.isNotEmpty()) {
                        ValueLine("Local GGUF Models", onboardStatus.discoveredGgufModels.joinToString(", "))
                    }
                    if (cmdStatus.lastActionSummary.isNotBlank()) {
                        ValueLine("Last Voice Command", cmdStatus.lastActionSummary)
                    }
                    if (onboardStatus.lastResponseText.isNotBlank()) {
                        Text("Latest reply: \"${onboardStatus.lastResponseText}\"", style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text("Active Brain / Mesh Node Selection", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Choose 'Auto Failover' or pin any Onboard NPU, Local GGUF, or custom Tailnet / LAN LLM node:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val routeOptions = repo.voice.onboard.getRouteOptions(draft)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        routeOptions.forEach { (routeId, label) ->
                            FilterChip(
                                selected = draft.preferredBrainRoute == routeId,
                                onClick = {
                                    val next = draft.copy(preferredBrainRoute = routeId)
                                    draft = next
                                    repo.saveSettings(next)
                                    notify("Brain route set to: $label")
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    if (onboardStatus.routesStatus.isNotEmpty()) {
                        Text("Live Node Health, Latency & Direct Test", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        onboardStatus.routesStatus.forEach { node ->
                            val isSelected = draft.preferredBrainRoute == node.id
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                tonalElevation = if (isSelected) 4.dp else 1.dp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${if (node.online) "🟢" else "⚪"} ${node.name}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = node.detail,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        text = "${node.endpoint} · ${node.model}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val next = draft.copy(preferredBrainRoute = node.id)
                                                draft = next
                                                repo.saveSettings(next)
                                                notify("Pinned brain route: ${node.name}")
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(if (isSelected) "✓ Selected" else "Select")
                                        }
                                        FilledTonalButton(
                                            enabled = testingRouteId == null,
                                            onClick = {
                                                testingRouteId = node.id
                                                notify("Testing ${node.name}...")
                                                scope.launch {
                                                    val (ok, msg) = repo.voice.onboard.testSpecificRoute(node.id)
                                                    testingRouteId = null
                                                    notify(if (ok) "✅ $msg" else "⚠️ $msg")
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(if (testingRouteId == node.id) "⏳ Testing..." else "🔊 Test Node")
                                        }
                                        if (node.isCustomNode) {
                                            TextButton(
                                                onClick = {
                                                    val updatedNodes = draft.customMeshNodes.filterNot { it.id == node.id }
                                                    val nextRoute = if (draft.preferredBrainRoute == node.id) "auto" else draft.preferredBrainRoute
                                                    val next = draft.copy(customMeshNodes = updatedNodes, preferredBrainRoute = nextRoute)
                                                    draft = next
                                                    repo.saveSettings(next)
                                                    scope.launch { repo.voice.onboard.refreshStatus() }
                                                    notify("Removed node: ${node.name}")
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("✕ Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(onClick = { showAddNodeForm = !showAddNodeForm }) {
                        Text(if (showAddNodeForm) "▲ Hide Custom Tailnet / LAN LLM Form" else "➕ Add Custom Tailnet / LAN LLM Node")
                    }
                    if (showAddNodeForm) {
                        Field("Node Name (e.g. Desktop GPU / Home Ollama)", newNodeName, "Desktop GPU") { newNodeName = it }
                        Field("Endpoint URL (Tailscale 100.64.x.x or LAN IP)", newNodeUrl, "http://100.64.0.10:8080") { newNodeUrl = it }
                        Field("Model Name (e.g. qwen3:4b-instruct or local-model)", newNodeModel, "local-model") { newNodeModel = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = newNodeType == "OPENAI",
                                onClick = { newNodeType = "OPENAI" },
                                label = { Text("OpenAI / llama.cpp (/v1)") }
                            )
                            FilterChip(
                                selected = newNodeType == "OLLAMA",
                                onClick = { newNodeType = "OLLAMA" },
                                label = { Text("Ollama (/api/chat)") }
                            )
                        }
                        Button(onClick = {
                            val cleanUrl = newNodeUrl.trim().trimEnd('/')
                            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                                notify("Enter a valid http:// or https:// endpoint URL.")
                            } else {
                                val id = "node_" + System.currentTimeMillis()
                                val name = newNodeName.trim().ifBlank { cleanUrl.removePrefix("http://").removePrefix("https://") }
                                val model = newNodeModel.trim().ifBlank { "local-model" }
                                val added = CustomMeshNode(id = id, name = name, url = cleanUrl, type = newNodeType, model = model)
                                val next = draft.copy(customMeshNodes = draft.customMeshNodes + added, preferredBrainRoute = id)
                                draft = next
                                repo.saveSettings(next)
                                newNodeName = ""
                                newNodeUrl = ""
                                newNodeModel = ""
                                showAddNodeForm = false
                                scope.launch {
                                    repo.voice.onboard.refreshStatus()
                                    notify("Added & selected LLM node: $name")
                                }
                            }
                        }) {
                            Text("Save & Probe LLM Node")
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                notify("Pinging configured nodes & Onboard NPU...")
                                val st = repo.voice.onboard.refreshStatus()
                                repo.voice.commander.checkOnDeviceSpeechSupport()
                                val onlineCount = st.routesStatus.count { it.online }
                                notify("Probed ${st.routesStatus.size} routes ($onlineCount online) · ${st.aiCoreStateLabel}")
                            }
                        }) {
                            Text("📡 Ping All Nodes & ${DevicePlatform.npuShortName}")
                        }
                        if (cmdStatus.onDeviceModelSupported && !cmdStatus.onDeviceModelInstalled) {
                            OutlinedButton(onClick = {
                                repo.voice.commander.downloadOnDeviceSpeechModel { msg -> notify(msg) }
                            }) {
                                Text("📥 Download Speech Pack")
                            }
                        }
                        FilledTonalButton(onClick = {
                            repo.voice.askMerlinInteractive("Tell me what brain node or NPU you are running on right now, and give me a quick sitrep!") { reply, backend ->
                                notify("$backend: $reply")
                            }
                        }) {
                            Text("🧠 Test Active Brain + Voice")
                        }
                    }
                }
            }

            item {
                Panel("Merlin Voice Co-Pilot", "Single-channel audio with pre-warmed Gemini Studio voices.") {
                    Toggle("Voice announcements (starts, stops, bases)", draft.voiceEnabled) { val next = draft.copy(voiceEnabled = it); draft = next; repo.saveSettings(next) }
                    Toggle("Announce SHA3-512 entropy milestones", draft.voiceAnnounceEntropy) { val next = draft.copy(voiceAnnounceEntropy = it); draft = next; repo.saveSettings(next) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = draft.voiceSource == "gemini",
                            onClick = {
                                val next = draft.copy(voiceSource = "gemini", geminiVoiceEnabled = true)
                                draft = next
                                repo.saveSettings(next)
                            },
                            label = { Text("✨ Gemini Studio Voices") }
                        )
                        FilterChip(
                            selected = draft.voiceSource == "device",
                            onClick = {
                                val next = draft.copy(voiceSource = "device")
                                draft = next
                                repo.saveSettings(next)
                            },
                            label = { Text("📱 Device TTS") }
                        )
                    }

                    if (draft.voiceSource == "gemini") {
                        var expandedGemini by remember { mutableStateOf(false) }
                        val currentGeminiVoice = GEMINI_VOICES.firstOrNull { it.name.equals(draft.geminiVoice, ignoreCase = true) }
                            ?: GEMINI_VOICES.first()

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { expandedGemini = !expandedGemini },
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${currentGeminiVoice.name} · ${currentGeminiVoice.description}", maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(if (expandedGemini) "▲" else "▼")
                                }
                            }
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    notify("Playing ${draft.geminiVoice}…")
                                    val line = repo.voice.testVoiceGreeting()
                                    notify(line)
                                }
                            }) {
                                Text("🔊 Test")
                            }
                        }

                        if (expandedGemini) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                            ) {
                                LazyColumn(modifier = Modifier.padding(4.dp)) {
                                    items(GEMINI_VOICES.size) { idx ->
                                        val gv = GEMINI_VOICES[idx]
                                        val isSel = draft.geminiVoice.equals(gv.name, ignoreCase = true)
                                        TextButton(
                                            onClick = {
                                                val next = draft.copy(geminiVoice = gv.name, voiceSource = "gemini")
                                                draft = next
                                                repo.saveSettings(next)
                                                expandedGemini = false
                                                notify("Selected Gemini Voice: ${gv.name}")
                                                scope.launch {
                                                    repo.voice.testVoiceGreeting(gv.name)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    (if (isSel) "✓ " else "") + "${gv.name} (${gv.gender})",
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(gv.description, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Compact API Key Row
                        HorizontalDivider()
                        if (hasGeminiKey && !editingGeminiKey) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🔑 Gemini API Key Saved (Keystore)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                Row {
                                    TextButton(onClick = { editingGeminiKey = true }) { Text("Change") }
                                    TextButton(onClick = {
                                        repo.vault.put("gemini_api_key", ByteArray(0))
                                        hasGeminiKey = false
                                        notify("Gemini API key cleared")
                                    }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = geminiApiKey,
                                    onValueChange = { geminiApiKey = it },
                                    label = { Text("Paste Gemini API key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    if (geminiApiKey.isNotBlank()) {
                                        repo.vault.put("gemini_api_key", geminiApiKey.trim().toByteArray())
                                        hasGeminiKey = true
                                        editingGeminiKey = false
                                        geminiApiKey = ""
                                        repo.voice.gemini.prewarmVoiceAsync(draft.geminiVoice)
                                        notify("Gemini API key saved & voice pre-warming!")
                                    } else {
                                        notify("Paste an API key first.")
                                    }
                                }) {
                                    Text("Save")
                                }
                            }
                        }
                    } else {
                        // Device TTS compact controls
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = draft.voiceStyle == "bubbly",
                                onClick = {
                                    val next = draft.copy(voiceStyle = "bubbly", voicePitch = 1.15f, voiceRate = 1.08f)
                                    draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next)
                                    repo.voice.speakDeviceOnly("Hi Senpai! I'm ready to roll!")
                                },
                                label = { Text("Bubbly ✨") }
                            )
                            FilterChip(
                                selected = draft.voiceStyle == "merlin",
                                onClick = {
                                    val next = draft.copy(voiceStyle = "merlin", voicePitch = 0.96f, voiceRate = 1.03f)
                                    draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next)
                                    repo.voice.speakDeviceOnly("Wandering compass online, Senpai!")
                                },
                                label = { Text("Original 🧭") }
                            )
                            FilterChip(
                                selected = draft.voiceStyle == "gentle",
                                onClick = {
                                    val next = draft.copy(voiceStyle = "gentle", voicePitch = 0.90f, voiceRate = 0.96f)
                                    draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next)
                                    repo.voice.speakDeviceOnly("Safe route home charted, Senpai.")
                                },
                                label = { Text("Gentle 🌿") }
                            )
                        }

                        if (availableVoices.isNotEmpty()) {
                            var expandedVoices by remember { mutableStateOf(false) }
                            val currentVoiceLabel = availableVoices.firstOrNull { it.name == draft.voiceName }?.displayName
                                ?: if (draft.voiceName.isBlank()) "Device Default" else draft.voiceName

                            OutlinedButton(onClick = { expandedVoices = !expandedVoices }, modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("System Voice: $currentVoiceLabel", maxLines = 1, modifier = Modifier.weight(1f))
                                    Text(if (expandedVoices) "▲" else "▼")
                                }
                            }
                            if (expandedVoices) {
                                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                                    LazyColumn(modifier = Modifier.padding(4.dp)) {
                                        items(availableVoices.size) { idx ->
                                            val v = availableVoices[idx]
                                            TextButton(
                                                onClick = {
                                                    val next = draft.copy(voiceName = v.name)
                                                    draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next)
                                                    expandedVoices = false
                                                    repo.voice.speakDeviceOnly("Voice selected, Senpai.")
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text((if (draft.voiceName == v.name) "✓ " else "") + v.displayName, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Pitch: ${"%.2fx".format(java.util.Locale.US, draft.voicePitch)}", style = MaterialTheme.typography.bodySmall)
                            Text("Speed: ${"%.2fx".format(java.util.Locale.US, draft.voiceRate)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slider(
                                value = draft.voicePitch,
                                onValueChange = { p -> val next = draft.copy(voicePitch = p, voiceStyle = "custom"); draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next) },
                                valueRange = 0.70f..1.40f,
                                modifier = Modifier.weight(1f)
                            )
                            Slider(
                                value = draft.voiceRate,
                                onValueChange = { r -> val next = draft.copy(voiceRate = r, voiceStyle = "custom"); draft = next; repo.saveSettings(next); repo.voice.applyVoiceConfig(next) },
                                valueRange = 0.70f..1.40f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ── SECTION 1: ENTROPY & WATCH ──────────────────────────────────────
        if (section == 1) {
            item {
                val pe = live.phoneEntropy
                Panel("📱 Phone EntropicDroid Harvester (Tri-Fusion)", pe.status) {
                    Toggle("Harvest phone physical entropy during drives (0% parked drain)", draft.phoneEntropyEnabled) {
                        val next = draft.copy(phoneEntropyEnabled = it)
                        draft = next
                        repo.saveSettings(next)
                        notify(if (it) "Phone Tri-Fusion harvester armed" else "Phone harvester disabled")
                    }
                    ValueLine(
                        "Latest NIST SP 800-90B Score",
                        if (pe.scorePct > 0.0) "${"%.1f".format(java.util.Locale.US, pe.scorePct)} / 100 · H_min ${"%.3f".format(java.util.Locale.US, pe.minEntropy)} b/B (${pe.governingEstimator})"
                        else "Not yet sampled"
                    )
                    Text(
                        "Blends 5 physical sources (50Hz IMU vibrations, CPU timer drift, DRAM timing, SoC thermals, battery ripple) into the SHA3-512 sponge only while Nullvalon is connected.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        repo.phoneHarvester.sampleOneShotWindow()
                        notify("Sampling 1.5s phone physical entropy window…")
                    }) {
                        Text("⚡ Sample & Score Phone Now (1.5s)")
                    }
                }
            }

            item {
                Panel("⌚ Galaxy Watch 5 Harvester & Battery Saver", live.watch.status) {
                    Toggle("Enable Galaxy Watch 5 bio & RNG harvester", draft.watchEnabled) {
                        val next = draft.copy(watchEnabled = it)
                        draft = next
                        repo.saveSettings(next)
                        notify(if (it) "Watch 5 harvester armed" else "Watch 5 harvester stopped")
                    }
                    Toggle("Standby Batch Mode (harvest while away from car)", draft.watchStandbyBatchMode) {
                        val next = draft.copy(watchStandbyBatchMode = it, watchEnabled = if (it) true else draft.watchEnabled)
                        draft = next
                        repo.saveSettings(next)
                        notify(if (it) "Watch Standby ${draft.watchBatchIntervalSec}s Batch Mode enabled" else "Watch set to Nullvalon-Only Mode (0% parked drain)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Watch batch interval:", style = MaterialTheme.typography.labelMedium)
                        FilterChip(
                            selected = draft.watchBatchIntervalSec <= 30,
                            onClick = { val next = draft.copy(watchBatchIntervalSec = 30); draft = next; repo.saveSettings(next) },
                            label = { Text("30 seconds") }
                        )
                        FilterChip(
                            selected = draft.watchBatchIntervalSec >= 60,
                            onClick = { val next = draft.copy(watchBatchIntervalSec = 60); draft = next; repo.saveSettings(next) },
                            label = { Text("60 seconds") }
                        )
                    }
                }
            }
        }

        // ── SECTION 2: CAR BLE & GPS ────────────────────────────────────────
        if (section == 2) {
            item {
                Panel("Nullvalon BLE & Vehicle Fleet", "Auto-switches active vehicle when its registered ESP32 connects.") {
                    Field("Registered BLE MAC (blank = auto-learn)", draft.deviceAddress) { draft = draft.copy(deviceAddress = it.trim().uppercase()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onScanDevices) {
                            Text(if (isScanningDevices) "Scanning (10s)…" else "Scan Nearby ESP32s")
                        }
                        TextButton(onClick = reconnect) { Text("Reconnect") }
                        if (draft.deviceAddress.isNotBlank()) {
                            TextButton(onClick = { draft = draft.copy(deviceAddress = "") }) { Text("Clear") }
                        }
                    }
                    if (discoveredDevices.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            discoveredDevices.forEach { dev ->
                                Surface(
                                    onClick = {
                                        draft = draft.copy(deviceAddress = dev.address)
                                        notify("Selected ${dev.name} (${dev.address})")
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    color = if (dev.isNullvalon) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column {
                                            Text(dev.name, fontWeight = if (dev.isNullvalon) FontWeight.Bold else FontWeight.Normal)
                                            Text(dev.address, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("${dev.rssi} dBm", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (draft.vehicles.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Saved Fleet", style = MaterialTheme.typography.labelMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            draft.vehicles.forEach { v ->
                                val isActive = v.address.equals(draft.deviceAddress, ignoreCase = true)
                                Surface(
                                    onClick = {
                                        draft = draft.copy(deviceAddress = v.address, vehicle = v.name, driver = v.driver)
                                        notify("Selected ${v.name} (${v.address})")
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${v.name} · ${v.driver}", fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                                            Text(v.address, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = {
                                            val updated = draft.vehicles.filterNot { it.address.equals(v.address, ignoreCase = true) }
                                            draft = draft.copy(vehicles = updated)
                                            repo.saveSettings(repo.settings.value.copy(vehicles = updated))
                                            notify("Removed ${v.name}")
                                        }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        val addr = draft.deviceAddress.trim().uppercase()
                        if (!addr.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) || draft.vehicle.isBlank()) {
                            notify("Enter a valid BLE MAC address and Vehicle name first.")
                        } else {
                            val profile = VehicleProfile(addr, draft.vehicle.trim(), draft.driver.trim().ifBlank { "Driver" })
                            val updated = draft.vehicles.filterNot { it.address.equals(addr, ignoreCase = true) } + profile
                            draft = draft.copy(vehicles = updated)
                            repo.saveSettings(repo.settings.value.copy(vehicles = updated, deviceAddress = addr, vehicle = profile.name, driver = profile.driver))
                            notify("Saved ${profile.name} ($addr) to fleet")
                        }
                    }) { Text("+ Save Current Vehicle & MAC to Fleet") }
                }
            }

            item {
                Panel("GPS & Journey Recording", "GPS stays 100% off whenever Nullvalon is disconnected.") {
                    Toggle("Record connected car journeys", draft.gpsEnabled) { draft = draft.copy(gpsEnabled = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(gps, { gps = it }, label = { Text("GPS interval (1–60s)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(accuracy, { accuracy = it }, label = { Text("Max accuracy (5–200m)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(movement, { movement = it }, label = { Text("Moving (0.5–10 m/s)") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(retention, { retention = it }, label = { Text("Retention (days)") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(graphCount, { graphCount = it }, label = { Text("Live graph points (30–600)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // ── SECTION 3: SYNC, MAPS & DATA ────────────────────────────────────
        if (section == 3) {
            item {
                Panel("Optional Private Tailnet / LAN Sync", "Ghost Drive runs 100% standalone on-device. Optionally forward to your own private receiver.") {
                    Toggle("Enable private forwarding", draft.syncEnabled) { draft = draft.copy(syncEnabled = it) }
                    Toggle("Upload only while Nullvalon is connected", draft.syncOnlyConnected) { draft = draft.copy(syncOnlyConnected = it) }
                    Toggle("Auto-sync archive when trip ends", draft.autoSyncArchive) { draft = draft.copy(autoSyncArchive = it) }

                    Field("Events ingest URL", draft.historyEndpoint, "http://100.64.x.x:8790/v1/events") { draft = draft.copy(historyEndpoint = it) }
                    Field("Entropy ingest URL", draft.entropyEndpoint, "http://100.64.x.x:8790/v1/entropy") { draft = draft.copy(entropyEndpoint = it) }
                    Field("Archive sync URL", draft.syncPullEndpoint, "http://100.64.x.x:8790/v1/archive") { draft = draft.copy(syncPullEndpoint = it) }
                    OutlinedTextField(interval, { interval = it }, label = { Text("Forwarding interval (5–300s)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    TextButton(onClick = { showTokenFields = !showTokenFields }) {
                        Text(if (showTokenFields) "▲ Hide optional bearer tokens" else "▼ Configure optional bearer tokens")
                    }
                    if (showTokenFields) {
                        SecretField("Journey bearer token", historyToken) { historyToken = it }
                        SecretField("Entropy bearer token", entropyToken) { entropyToken = it }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = pullSync) { Text("Sync both ways now") }
                        TextButton(onClick = {
                            draft = draft.copy(
                                syncEnabled = false,
                                historyEndpoint = "",
                                entropyEndpoint = "",
                                syncPullEndpoint = ""
                            )
                            notify("Cleared remote sync endpoints (Standalone On-Device Mode)")
                        }) { Text("Clear endpoints") }
                    }
                }
            }

            item {
                Panel("Offline Maps, Permissions & Backups") {
                    Text(if (settings.mapPath.isBlank()) "No offline .map file installed yet." else "✓ Offline Mapsforge map installed", style = MaterialTheme.typography.bodySmall)
                    Field("Regional .map download URL", draft.offlineMapUrl) { draft = draft.copy(offlineMapUrl = it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = importMap) { Text("Import .map") }
                        OutlinedButton(onClick = { repo.saveSettings(repo.settings.value.copy(offlineMapUrl = draft.offlineMapUrl.trim())); downloadMap() }) { Text("Download .map") }
                        OutlinedButton(onClick = importPlaces) { Text("Import Places") }
                    }
                    if (mapStatus.isNotBlank()) Text(mapStatus, style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider()
                    Toggle("Auto-arm monitoring when phone boots", draft.autoStartOnBoot) { draft = draft.copy(autoStartOnBoot = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = appPermissions, modifier = Modifier.weight(1f)) { Text("Permissions") }
                        OutlinedButton(onClick = battery, modifier = Modifier.weight(1f)) { Text("Battery Saver") }
                    }

                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = shareArchive) { Text("Quick Share JSON") }
                        OutlinedButton(onClick = export) { Text("Export JSON") }
                        OutlinedButton(onClick = importArchive) { Text("Import JSON") }
                        OutlinedButton(onClick = exportGpx) { Text("Export GPX") }
                        TextButton(onClick = removeDemo) { Text("Clear Demo Trips") }
                    }
                }
            }
        }

        // Persistent Save Button across all tabs
        item {
            Button(
                onClick = ::saveAllSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save settings")
            }
        }
    }
}

@Composable private fun Field(label: String, value: String, placeholder: String = "", onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable private fun SecretField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
}
@Composable private fun Toggle(label: String, enabled: Boolean, onValue: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(enabled, onValue) }
}
