package com.jupiterlabs.ghostdrive.tracking

import android.os.Environment
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.jupiterlabs.ghostdrive.data.CustomMeshNode
import com.jupiterlabs.ghostdrive.data.LiveState
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.data.Settings
import com.jupiterlabs.ghostdrive.util.DevicePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class BrainRouteStatus(
    val id: String,
    val name: String,
    val endpoint: String,
    val model: String,
    val online: Boolean,
    val latencyMs: Long = -1L,
    val detail: String = "",
    val isCustomNode: Boolean = false
)

data class OnboardBrainStatus(
    val platformBadge: String = DevicePlatform.editionBannerTitle,
    val npuName: String = DevicePlatform.npuFullName,
    val cpuSimd: String = DevicePlatform.cpuSimdName,
    val aiCoreReady: Boolean = false,
    val aiCoreStateLabel: String = "Checking ${DevicePlatform.npuShortName}…",
    val localGgufActive: Boolean = false,
    val discoveredGgufModels: List<String> = emptyList(),
    val activeMeshNode: String = "",
    val lastBackendUsed: String = "Onboard ${DevicePlatform.npuShortName}",
    val lastResponseText: String = "",
    val routesStatus: List<BrainRouteStatus> = emptyList()
)

/**
 * Cross-compatible Onboard Brain & User-Configurable Tailnet / LAN Mesh Engine for Ghost Drive
 * supporting Samsung Galaxy S25 Ultra (Snapdragon 8 Elite Hexagon NPU) and Google Pixel 11 Pro XL (Google Tensor G6 TPU).
 *
 * Inference hierarchy (selectable in Settings -> Preferred Brain Route):
 *   1. Android AICore / ML Kit GenAI Prompt (Gemini Nano on Hexagon NPU / Tensor TPU)
 *   2. Local Phone GGUF Loopback Server (127.0.0.1:18089)
 *   3. User-Configured Tailscale / LAN LLM Nodes (OpenAI-compatible llama-server/vLLM/LM Studio or Ollama)
 *   4. Cloud Gemini Flash Fallback + Onboard Telemetry Intelligence
 *
 * Every response is spoken through Merlin's configured voice (`Aoede` or offline system TTS).
 */
class MerlinOnboardEngine(private val repo: Repository) {
    @Volatile
    private var model: GenerativeModel? = null

    private val localHttp = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    private val ollamaHttp = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(16000, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    companion object {
        val BASE_ROUTE_OPTIONS = listOf(
            "auto" to "⚡ Auto Failover (Onboard → Mesh → Cloud)",
            "onboard_npu" to "📱 Phone NPU (${DevicePlatform.npuShortName} · Gemini Nano)",
            "local_gguf" to "📦 Phone Local GGUF (127.0.0.1:18089)",
            "gemini_cloud" to "☁️ Gemini Cloud Flash"
        )
    }

    fun getRouteOptions(settings: Settings = repo.settings.value): List<Pair<String, String>> {
        return buildList {
            addAll(BASE_ROUTE_OPTIONS)
            settings.customMeshNodes.forEach { node ->
                add(node.id to "🌐 ${node.name} (${node.url.removePrefix("http://").removePrefix("https://")})")
            }
        }
    }

    val status = MutableStateFlow(
        OnboardBrainStatus(
            discoveredGgufModels = scanLocalGgufModels()
        )
    )

    private fun getOrCreateModel(): GenerativeModel {
        return model ?: synchronized(this) {
            model ?: Generation.getClient().also { model = it }
        }
    }

    /**
     * Scans shared Downloads/GhostDrive-Models and app external files dir for GGUF weights on S25 Ultra or Pixel 11 Pro XL.
     */
    fun scanLocalGgufModels(): List<String> {
        return runCatching {
            val found = mutableListOf<String>()
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dirs = listOfNotNull(
                pubDownloads?.let { File(it, "GhostDrive-Models") },
                pubDownloads?.let { File(it, "Models") },
                pubDownloads,
                File("/sdcard/Download/GhostDrive-Models"),
                repo.context.getExternalFilesDir("models")
            )
            for (dir in dirs) {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !f.name.endsWith(".part") && f.length() > 10_000_000L) {
                            if (f.name !in found) found.add(f.name)
                        }
                    }
                }
            }
            found
        }.getOrDefault(emptyList())
    }

    /**
     * Checks whether a local GGUF llama-server is currently listening on 127.0.0.1:18089.
     */
    suspend fun checkLocalGgufServer(): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val ok = runCatching {
            val req = Request.Builder()
                .url("http://127.0.0.1:18089/health")
                .get()
                .build()
            localHttp.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        val ms = System.currentTimeMillis() - t0
        ok to (if (ok) ms else -1L)
    }

    private suspend fun probeSingleMeshNode(node: CustomMeshNode): BrainRouteStatus = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val cleanUrl = node.url.trim().trimEnd('/')
        val healthUrl = if (node.type == "OLLAMA") "$cleanUrl/api/tags" else "$cleanUrl/health"
        try {
            val req = Request.Builder().url(healthUrl).get().build()
            localHttp.newCall(req).execute().use { resp ->
                val ms = System.currentTimeMillis() - t0
                if (resp.isSuccessful) {
                    var resolvedModel = node.model
                    if (node.type == "OLLAMA") {
                        runCatching {
                            val tags = JSONObject(resp.body?.string().orEmpty()).optJSONArray("models")
                            val firstTag = tags?.optJSONObject(0)?.optString("name")
                            if ((resolvedModel.isBlank() || resolvedModel == "local-model") && !firstTag.isNullOrBlank()) {
                                resolvedModel = firstTag
                            }
                        }
                    }
                    BrainRouteStatus(
                        id = node.id,
                        name = node.name,
                        endpoint = cleanUrl.removePrefix("http://").removePrefix("https://"),
                        model = resolvedModel,
                        online = true,
                        latencyMs = ms,
                        detail = "🟢 Online (${ms} ms) · $resolvedModel",
                        isCustomNode = true
                    )
                } else {
                    BrainRouteStatus(
                        id = node.id,
                        name = node.name,
                        endpoint = cleanUrl.removePrefix("http://").removePrefix("https://"),
                        model = node.model,
                        online = false,
                        latencyMs = -1L,
                        detail = "🔴 HTTP ${resp.code}",
                        isCustomNode = true
                    )
                }
            }
        } catch (e: Exception) {
            BrainRouteStatus(
                id = node.id,
                name = node.name,
                endpoint = cleanUrl.removePrefix("http://").removePrefix("https://"),
                model = node.model,
                online = false,
                latencyMs = -1L,
                detail = "⚪ Unreachable (check Tailscale / LAN)",
                isCustomNode = true
            )
        }
    }

    /**
     * Refreshes the real-time AICore NPU, Local GGUF, and all user-configured Tailnet/LAN Mesh nodes in parallel,
     * recording exact latency and status for the Settings inspector.
     */
    suspend fun refreshStatus(): OnboardBrainStatus = withContext(Dispatchers.IO) {
        val ggufModels = scanLocalGgufModels()
        val (ggufUp, ggufMs) = checkLocalGgufServer()
        val npuShort = DevicePlatform.npuShortName
        val (aiReady, aiDetail) = try {
            when (getOrCreateModel().checkStatus()) {
                FeatureStatus.AVAILABLE ->
                    true to "🟢 Ready · Gemini Nano active on $npuShort"
                FeatureStatus.DOWNLOADABLE ->
                    true to "🟡 Downloadable · Gemini Nano ready on $npuShort"
                FeatureStatus.DOWNLOADING ->
                    false to "⏳ Downloading Gemini Nano weights to $npuShort…"
                FeatureStatus.UNAVAILABLE ->
                    false to "⚪ AICore Nano inactive on this build (using Mesh / Hybrid)"
                else ->
                    false to "⚪ Standby ($npuShort)"
            }
        } catch (e: Exception) {
            false to "⚪ Standby ($npuShort)"
        }

        val userNodes = repo.settings.value.customMeshNodes
        val meshStatuses = coroutineScope {
            userNodes.map { node -> async { probeSingleMeshNode(node) } }.awaitAll()
        }

        val firstOnlineMesh = meshStatuses.firstOrNull { it.online }
        val hasGeminiKey = repo.vault.present("gemini_api_key")

        val allRoutes = buildList {
            add(
                BrainRouteStatus(
                    id = "onboard_npu",
                    name = "${DevicePlatform.deviceShortName} ${DevicePlatform.npuShortName}",
                    endpoint = "On-Device AICore",
                    model = "gemini-nano",
                    online = aiReady,
                    latencyMs = if (aiReady) 1L else -1L,
                    detail = aiDetail
                )
            )
            add(
                BrainRouteStatus(
                    id = "local_gguf",
                    name = "Phone Local GGUF",
                    endpoint = "127.0.0.1:18089",
                    model = ggufModels.firstOrNull() ?: "local-gguf",
                    online = ggufUp,
                    latencyMs = ggufMs,
                    detail = if (ggufUp) "🟢 Running (${ggufMs} ms)" else if (ggufModels.isNotEmpty()) "🟡 ${ggufModels.size} GGUF file(s) found · server idle" else "⚪ No active 127.0.0.1:18089 server"
                )
            )
            addAll(meshStatuses)
            add(
                BrainRouteStatus(
                    id = "gemini_cloud",
                    name = "Gemini Cloud Flash",
                    endpoint = "generativelanguage.googleapis.com",
                    model = "gemini-3.8-flash",
                    online = hasGeminiKey,
                    latencyMs = if (hasGeminiKey) 0L else -1L,
                    detail = if (hasGeminiKey) "🟢 API Key Saved (+ ${repo.settings.value.geminiVoice} Studio Voice)" else "⚪ No API Key saved"
                )
            )
        }

        val summaryLabel = when {
            aiReady -> "🟢 Ready · Gemini Nano active on $npuShort"
            ggufUp -> "🟢 Local Phone GGUF active (127.0.0.1:18089)"
            firstOnlineMesh != null -> "🟢 Mesh active (${firstOnlineMesh.name} · ${firstOnlineMesh.latencyMs} ms)"
            else -> "⚡ Hybrid Mode ($npuShort Telemetry + Gemini ${repo.settings.value.geminiVoice} Voice)"
        }

        val updated = status.value.copy(
            platformBadge = DevicePlatform.editionBannerTitle,
            npuName = DevicePlatform.npuFullName,
            cpuSimd = DevicePlatform.cpuSimdName,
            aiCoreReady = aiReady,
            aiCoreStateLabel = summaryLabel,
            localGgufActive = ggufUp,
            discoveredGgufModels = ggufModels,
            activeMeshNode = firstOnlineMesh?.let { "${it.name} (${it.latencyMs} ms)" } ?: "",
            routesStatus = allRoutes
        )
        status.value = updated
        updated
    }

    /**
     * Tests a specific route ID ("onboard_npu", "local_gguf", "gemini_cloud", or any custom mesh node ID)
     * from Settings and speaks the result aloud in Merlin's voice.
     */
    suspend fun testSpecificRoute(routeId: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val l = repo.live.value
        val s = repo.settings.value
        val telemetry = buildTelemetryContext(l, s)
        val testPrompt = "Say a quick 1-sentence hello to Senpai confirming which compute node or NPU you are answering from right now!"
        val t0 = System.currentTimeMillis()

        val result: Pair<String, String>? = when (routeId) {
            "onboard_npu" -> {
                val sys = buildSystemInstruction(telemetry, "${DevicePlatform.deviceShortName} ${DevicePlatform.npuShortName}")
                tryAiCoreNano(sys, testPrompt, 70)
            }
            "local_gguf" -> {
                val sys = buildSystemInstruction(telemetry, "Phone Local GGUF (${DevicePlatform.cpuSimdName})")
                val reply = runCatching { callOpenAiCompatible("http://127.0.0.1:18089", "local-gguf", sys, testPrompt, 70) }.getOrNull()
                reply?.let { it to "Phone Local GGUF (127.0.0.1:18089)" }
            }
            "gemini_cloud" -> {
                val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
                if (!apiKey.isNullOrBlank()) {
                    val reply = repo.voice.gemini.generateContextualReply(apiKey, testPrompt, telemetry)
                    reply?.let { it to "Gemini Cloud Flash" }
                } else null
            }
            else -> {
                val customNode = s.customMeshNodes.firstOrNull { it.id == routeId }
                if (customNode != null) {
                    val sys = buildSystemInstruction(telemetry, "Mesh Node · ${customNode.name} (${customNode.model})")
                    val reply = runCatching {
                        if (customNode.type == "OLLAMA") {
                            callOllamaNode(customNode.url, customNode.model, sys, testPrompt, 70)
                        } else {
                            callOpenAiCompatible(customNode.url, customNode.model, sys, testPrompt, 70)
                        }
                    }.getOrNull()
                    reply?.let { it to "Mesh · ${customNode.name}" }
                } else {
                    generateOnboardText(testPrompt, 70)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        if (result != null) {
            val (replyText, backendLabel) = result
            val taggedBackend = "$backendLabel (${elapsed} ms)"
            status.value = status.value.copy(
                lastBackendUsed = taggedBackend,
                lastResponseText = replyText
            )
            repo.voice.speak(replyText, force = true)
            refreshStatus()
            true to "✓ $taggedBackend: \"$replyText\""
        } else {
            refreshStatus()
            false to "⚠️ Route '$routeId' did not respond. Check if that node is online or select Auto Failover."
        }
    }

    private fun buildSystemInstruction(
        telemetry: String,
        activeNodeLabel: String = "${DevicePlatform.deviceShortName} (${DevicePlatform.npuShortName})"
    ): String = buildString {
        val d = repo.settings.value.driver.ifBlank { "Driver" }
        val v = repo.settings.value.vehicle.ifBlank { "Vehicle" }
        append("/no_think You are A-007 Merlin, the warm, bubbly, affectionate pathfinder co-pilot of the ").append(v).append(" ")
        append("running on ").append(DevicePlatform.deviceShortName).append(" (").append(DevicePlatform.npuShortName)
        append(") and currently answering from compute node: ").append(activeNodeLabel).append(". ")
        append("You address your driver naturally as 'Senpai' or '").append(d).append("'. ")
        append("Keep your reply to 1 or 2 short, cheerful spoken sentences without markdown, asterisks, or emojis.\n")
        append("Live Telemetry: ").append(telemetry)
    }

    private fun buildTelemetryContext(l: LiveState, s: Settings): String {
        val v = s.vehicle.ifBlank { "Vehicle" }
        val d = s.driver.ifBlank { "Driver" }
        val tripInfo = l.trip?.let { t ->
            val dist = if (s.displayUnits == "mi") {
                String.format(Locale.US, "%.1f mi", t.distanceM / 1609.344)
            } else {
                String.format(Locale.US, "%.1f km", t.distanceM / 1000.0)
            }
            val spd = l.lastFix?.speedMps?.let { mps ->
                if (s.displayUnits == "mi") String.format(Locale.US, "%.0f mph", mps * 2.23694f)
                else String.format(Locale.US, "%.0f km/h", mps * 3.6f)
            } ?: "0 mph"
            "Driving $v ($dist logged toward ${t.endName}, speed $spd)"
        } ?: run {
            val lastStop = repo.trips.value.firstOrNull { !it.demo && it.endMs != null }?.endName ?: "home base"
            "Parked near $lastStop in $v (0% idle drain)"
        }
        val watchInfo = if (l.watch.hrBpm != null || l.watch.spo2Pct != null) {
            "Watch 5 HR: ${l.watch.hrBpm?.toInt() ?: "—"} BPM, SpO2: ${l.watch.spo2Pct ?: "—"}%, Steps: ${l.watch.steps}"
        } else {
            "Watch 5: standby (${l.entropyVault.pendingWatchBlocks} blocks queued)"
        }
        val pe = l.phoneEntropy
        val phoneScore = if (pe.scorePct > 0.0) String.format(Locale.US, "%.1f/100 (H_min %.2f b/B)", pe.scorePct, pe.minEntropy) else "ready"
        return "Hardware: ${DevicePlatform.deviceShortName} (${DevicePlatform.npuShortName}) | Driver: $d (Senpai) | Status: $tripInfo | $watchInfo | Phone EntropicDroid: $phoneScore | Tri-Fusion Vault: ${l.entropyVault.blocks} SHA3-512 blocks."
    }

    private suspend fun tryAiCoreNano(
        systemInstruction: String,
        userPrompt: String,
        maxTokens: Int
    ): Pair<String, String>? {
        return try {
            val genModel = getOrCreateModel()
            val featStatus = genModel.checkStatus()
            if (featStatus == FeatureStatus.DOWNLOADABLE) {
                withTimeoutOrNull(2500L) {
                    genModel.download().collect { dl ->
                        if (dl is DownloadStatus.DownloadCompleted) return@collect
                    }
                }
            }
            if (genModel.checkStatus() == FeatureStatus.AVAILABLE) {
                val fullPrompt = "$systemInstruction\n\nSenpai: $userPrompt\nMerlin:"
                val req = generateContentRequest(TextPart(fullPrompt)) {
                    temperature = 0.72f
                    topK = 40
                    maxOutputTokens = maxTokens
                }
                val resp = withTimeoutOrNull(4000L) { genModel.generateContent(req) }
                val text = resp?.candidates?.firstOrNull()?.text?.let { cleanSpoken(it) }
                if (!text.isNullOrBlank()) {
                    val backend = "Onboard ${DevicePlatform.npuShortName} (Gemini Nano)"
                    status.value = status.value.copy(
                        aiCoreReady = true,
                        aiCoreStateLabel = "🟢 Ready · Gemini Nano active on ${DevicePlatform.npuShortName}",
                        lastBackendUsed = backend,
                        lastResponseText = text
                    )
                    return text to backend
                }
            }
            null
        } catch (e: Exception) {
            Log.d("MerlinOnboard", "AICore NPU path skipped: ${e.message}")
            null
        }
    }

    /**
     * Generates text using the user's selected `preferredBrainRoute` first, then automatic failover across:
     *   1. Android AICore (Gemini Nano on S25 Ultra Hexagon NPU / Pixel 11 Pro XL Tensor TPU)
     *   2. Local Phone GGUF (127.0.0.1:18089)
     *   3. User-Configured Tailnet / LAN Mesh Nodes
     */
    suspend fun generateOnboardText(
        userPrompt: String,
        maxTokens: Int = 80
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val l = repo.live.value
        val s = repo.settings.value
        val telemetry = buildTelemetryContext(l, s)
        val systemInstruction = buildSystemInstruction(telemetry)
        val preferred = s.preferredBrainRoute.ifBlank { "auto" }

        // 0. If user pinned a specific custom mesh node, local GGUF, or Gemini Cloud, try that first
        val pinnedNode = s.customMeshNodes.firstOrNull { it.id == preferred }
        if (pinnedNode != null) {
            val nodeSys = buildSystemInstruction(telemetry, "Mesh · ${pinnedNode.name} (${pinnedNode.model})")
            val reply = runCatching {
                if (pinnedNode.type == "OLLAMA") {
                    callOllamaNode(pinnedNode.url, pinnedNode.model, nodeSys, userPrompt, maxTokens)
                } else {
                    callOpenAiCompatible(pinnedNode.url, pinnedNode.model, nodeSys, userPrompt, maxTokens)
                }
            }.getOrNull()
            if (!reply.isNullOrBlank()) {
                val backend = "Mesh · ${pinnedNode.name}"
                status.value = status.value.copy(
                    activeMeshNode = pinnedNode.name,
                    lastBackendUsed = backend,
                    lastResponseText = reply
                )
                return@withContext reply to backend
            }
        } else if (preferred == "local_gguf") {
            val ggufSys = buildSystemInstruction(telemetry, "Onboard Local GGUF (${DevicePlatform.cpuSimdName})")
            val reply = runCatching { callOpenAiCompatible("http://127.0.0.1:18089", "local-gguf", ggufSys, userPrompt, maxTokens) }.getOrNull()
            if (!reply.isNullOrBlank()) {
                val backend = "Onboard Local GGUF (${DevicePlatform.cpuSimdName})"
                status.value = status.value.copy(localGgufActive = true, lastBackendUsed = backend, lastResponseText = reply)
                return@withContext reply to backend
            }
        } else if (preferred == "gemini_cloud") {
            val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
            if (!apiKey.isNullOrBlank()) {
                val cloudText = repo.voice.gemini.generateContextualReply(apiKey, userPrompt, telemetry)
                if (!cloudText.isNullOrBlank()) {
                    val backend = "Gemini Cloud Flash"
                    status.value = status.value.copy(lastBackendUsed = backend, lastResponseText = cloudText)
                    return@withContext cloudText to backend
                }
            }
        }

        // 1. Try Android AICore (Gemini Nano on Hexagon NPU / Tensor TPU)
        tryAiCoreNano(systemInstruction, userPrompt, maxTokens)?.let { return@withContext it }

        // 2. Try Local GGUF Loopback Server (127.0.0.1:18089) if running on the phone
        try {
            if (checkLocalGgufServer().first) {
                val ggufSys = buildSystemInstruction(telemetry, "Onboard Local GGUF (${DevicePlatform.cpuSimdName})")
                val reply = callOpenAiCompatible("http://127.0.0.1:18089", "local-gguf", ggufSys, userPrompt, maxTokens)
                if (!reply.isNullOrBlank()) {
                    val backend = "Onboard Local GGUF (${DevicePlatform.cpuSimdName})"
                    status.value = status.value.copy(
                        localGgufActive = true,
                        lastBackendUsed = backend,
                        lastResponseText = reply
                    )
                    return@withContext reply to backend
                }
            }
        } catch (e: Exception) {
            Log.d("MerlinOnboard", "Local GGUF loopback skipped: ${e.message}")
        }

        // 3. Try User-Configured Tailnet / LAN Mesh Nodes in order
        for (node in s.customMeshNodes) {
            try {
                val nodeSys = buildSystemInstruction(telemetry, "Mesh · ${node.name} (${node.model})")
                val reply = if (node.type == "OLLAMA") {
                    callOllamaNode(node.url, node.model, nodeSys, userPrompt, maxTokens)
                } else {
                    callOpenAiCompatible(node.url, node.model, nodeSys, userPrompt, maxTokens)
                }
                if (!reply.isNullOrBlank()) {
                    val backend = "Mesh · ${node.name}"
                    status.value = status.value.copy(
                        activeMeshNode = node.name,
                        lastBackendUsed = backend,
                        lastResponseText = reply
                    )
                    return@withContext reply to backend
                }
            } catch (_: Exception) {
            }
        }

        null
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        modelName: String,
        systemInstruction: String,
        userPrompt: String,
        maxTokens: Int
    ): String? {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemInstruction))
            .put(JSONObject().put("role", "user").put("content", "/no_think $userPrompt"))
        val body = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        val req = Request.Builder()
            .url("$cleanBase/v1/chat/completions")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        localHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string().orEmpty())
            return root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.let { cleanSpoken(it) }
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun callOllamaNode(
        baseUrl: String,
        modelName: String,
        systemInstruction: String,
        userPrompt: String,
        maxTokens: Int
    ): String? {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemInstruction))
            .put(JSONObject().put("role", "user").put("content", "/no_think $userPrompt"))
        val body = JSONObject()
            .put("model", modelName)
            .put("messages", messages)
            .put("think", false)
            .put("keep_alive", "30m")
            .put("stream", false)
            .put("options", JSONObject().put("num_predict", maxTokens).put("temperature", 0.7))
        val req = Request.Builder()
            .url("$cleanBase/api/chat")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        ollamaHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string().orEmpty())
            return root.optJSONObject("message")
                ?.optString("content")
                ?.let { cleanSpoken(it) }
                ?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun askMerlin(
        userPrompt: String,
        speakAloud: Boolean = true
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Give me a quick status sitrep on our car, Watch 5 biometrics, and phone entropy score!"
        }

        // 1. Try Selected Route / Onboard NPU / Local GGUF / Mesh first if enabled
        if (repo.settings.value.onboardBrainEnabled) {
            val onboard = generateOnboardText(cleanPrompt, maxTokens = 90)
            if (onboard != null) {
                if (speakAloud) repo.voice.speak(onboard.first, force = true)
                return@withContext onboard
            }
        }

        // 2. Try Cloud Gemini with live telemetry context if API key is present
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        if (!apiKey.isNullOrBlank()) {
            val telemetry = buildTelemetryContext(repo.live.value, repo.settings.value)
            val cloudText = repo.voice.gemini.generateContextualReply(apiKey, cleanPrompt, telemetry)
            if (!cloudText.isNullOrBlank()) {
                val backend = "Hybrid (${DevicePlatform.deviceShortName} + Gemini Cloud)"
                status.value = status.value.copy(lastBackendUsed = backend, lastResponseText = cloudText)
                if (speakAloud) repo.voice.speak(cloudText, force = true)
                return@withContext cloudText to backend
            }
        }

        // 3. Always-available local telemetry intelligence on S25 Ultra / Pixel 11 Pro XL
        val fallback = buildSmartLocalReply(cleanPrompt, repo.live.value, repo.settings.value)
        val backend = "${DevicePlatform.deviceShortName} Onboard Telemetry"
        status.value = status.value.copy(lastBackendUsed = backend, lastResponseText = fallback)
        if (speakAloud) repo.voice.speak(fallback, force = true)
        fallback to backend
    }

    private fun buildSmartLocalReply(prompt: String, l: LiveState, s: Settings): String {
        val q = prompt.lowercase(Locale.US)
        val v = s.vehicle.ifBlank { "Vehicle" }
        val pe = l.phoneEntropy
        val scoreStr = if (pe.scorePct > 0.0) String.format(Locale.US, "%.1f out of 100", pe.scorePct) else "ready to sample"
        return when {
            q.contains("entropy") || q.contains("rng") || q.contains("score") || q.contains("sponge") ->
                "On your ${DevicePlatform.deviceShortName}, our EntropicDroid score is $scoreStr with ${pe.creditedBits} credited bits per block, and the Tri-Fusion vault holds ${l.entropyVault.blocks} SHA3-512 blocks, Senpai!"
            q.contains("watch") || q.contains("heart") || q.contains("bpm") || q.contains("pulse") -> {
                val hr = l.watch.hrBpm?.toInt()
                if (hr != null) "Your Galaxy Watch 5 is reading $hr BPM with ${l.watch.spo2Pct ?: 98}% blood oxygen and ${l.watch.steps} steps, Senpai!"
                else "Your Galaxy Watch 5 is in low-power staging mode with ${l.entropyVault.pendingWatchBlocks} blocks queued for the $v, Senpai!"
            }
            q.contains("where") || q.contains("trip") || q.contains("speed") || q.contains("drive") -> {
                if (l.trip != null) {
                    val dist = if (s.displayUnits == "mi") String.format(Locale.US, "%.1f miles", l.trip.distanceM / 1609.344)
                    else String.format(Locale.US, "%.1f kilometers", l.trip.distanceM / 1000.0)
                    "We're rolling in the $v with $dist logged toward ${l.trip.endName}, Senpai!"
                } else {
                    val lastStop = repo.trips.value.firstOrNull { !it.demo && it.endMs != null }?.endName ?: "home base"
                    "We're safely parked near $lastStop in the $v at zero battery drain, Senpai!"
                }
            }
            q.contains("pixel") || q.contains("s25") || q.contains("npu") || q.contains("phone") || q.contains("hardware") ->
                "I'm running on your ${DevicePlatform.editionBannerTitle} powered by ${DevicePlatform.npuFullName}, fully cross-compatible between the S25 Ultra and Pixel 11 Pro XL, Senpai!"
            else -> {
                val hrPart = l.watch.hrBpm?.let { " Your Watch 5 heart rate is ${it.toInt()} BPM." } ?: ""
                if (l.trip != null) {
                    val dist = if (s.displayUnits == "mi") String.format(Locale.US, "%.1f miles", l.trip.distanceM / 1609.344)
                    else String.format(Locale.US, "%.1f kilometers", l.trip.distanceM / 1000.0)
                    "All systems green on your ${DevicePlatform.deviceShortName}, Senpai! We've logged $dist in the $v.$hrPart Phone entropy score is $scoreStr and ${l.entropyVault.blocks} Tri-Fusion blocks are vaulted!"
                } else {
                    val parkedAt = repo.trips.value.firstOrNull { !it.demo && it.endMs != null }?.endName ?: "home base"
                    "Standing by on your ${DevicePlatform.deviceShortName} near $parkedAt, Senpai! The $v is resting at zero battery drain.$hrPart Phone entropy score is $scoreStr with ${l.entropyVault.blocks} Tri-Fusion blocks vaulted!"
                }
            }
        }
    }

    private fun cleanSpoken(raw: String): String {
        return raw
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace("*", "")
            .replace("\"", "")
            .replace("#", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
