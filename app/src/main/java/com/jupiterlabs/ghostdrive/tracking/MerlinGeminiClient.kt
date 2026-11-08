package com.jupiterlabs.ghostdrive.tracking

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.jupiterlabs.ghostdrive.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class GeminiVoiceOption(
    val name: String,
    val description: String,
    val gender: String
)

val GEMINI_VOICES = listOf(
    GeminiVoiceOption("Aoede", "Breezy · Signature Merlin ✨", "Female"),
    GeminiVoiceOption("Kore", "Firm & Confident", "Female"),
    GeminiVoiceOption("Leda", "Youthful & Bright", "Female"),
    GeminiVoiceOption("Zephyr", "Bright & Lively", "Female"),
    GeminiVoiceOption("Callirrhoe", "Easy-going & Relaxed", "Female"),
    GeminiVoiceOption("Autonoe", "Bright & Clear", "Female"),
    GeminiVoiceOption("Sulafat", "Warm & Gentle", "Female"),
    GeminiVoiceOption("Sadachbia", "Lively & Cheerful", "Female"),
    GeminiVoiceOption("Vindemiatrix", "Gentle & Calm", "Female"),
    GeminiVoiceOption("Puck", "Upbeat & Playful", "Male"),
    GeminiVoiceOption("Charon", "Informative & Steady", "Male"),
    GeminiVoiceOption("Fenrir", "Excitable & Bold", "Male"),
    GeminiVoiceOption("Orus", "Firm & Assertive", "Male"),
    GeminiVoiceOption("Achird", "Friendly & Casual", "Male")
)

class MerlinGeminiClient(private val repo: Repository) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(6500, TimeUnit.MILLISECONDS)
        .callTimeout(8000, TimeUnit.MILLISECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val playerLock = Any()
    @Volatile private var activePlayer: MediaPlayer? = null
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pre-warmed dynamic start greeting per voiceName -> Pair(text, wavBytes)
    private val prewarmedStart = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val prewarmingVoices = ConcurrentHashMap.newKeySet<String>()

    private val cacheDir: File by lazy {
        File(repo.context.cacheDir, "gemini_tts_cache").apply { mkdirs() }
    }

    fun isPlaying(): Boolean = synchronized(playerLock) {
        runCatching { activePlayer?.isPlaying == true }.getOrDefault(false)
    }

    fun stopAudio() {
        synchronized(playerLock) {
            activePlayer?.let { mp ->
                runCatching {
                    if (mp.isPlaying) mp.stop()
                }
                runCatching { mp.release() }
            }
            activePlayer = null
        }
    }

    /**
     * Pre-warms both a voice test clip and a dynamic start greeting in the background
     * so when the user taps Test or starts the car, playback begins with 0ms network lag.
     */
    fun prewarmVoiceAsync(voiceName: String = repo.settings.value.geminiVoice) {
        val v = voiceName.ifBlank { "Aoede" }
        if (!prewarmingVoices.add(v)) return
        prewarmScope.launch {
            try {
                val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
                if (apiKey.isNullOrBlank()) return@launch
                val vehicle = repo.settings.value.vehicle.ifBlank { "Vehicle" }
                val driver = repo.settings.value.driver.ifBlank { "Driver" }

                // 1. Ensure default test clip for this voice is cached on disk
                val testPhrase = testPhraseFor(v, vehicle)
                getOrSynthesizeWav(apiKey, testPhrase, v)

                // 2. Pre-generate & synthesize one dynamic start greeting for immediate 0ms car start
                if (!prewarmedStart.containsKey(v)) {
                    val prompt = "The driver ($driver, whom you affectionately address naturally as 'Senpai') just started the car ($vehicle). Say a short, cheerful, bubbly 1-sentence greeting to start our trip together in your natural voice!"
                    val onboardLine = if (repo.settings.value.onboardBrainEnabled) {
                        runCatching { repo.voice.onboard.generateOnboardText(prompt, maxTokens = 60)?.first }.getOrNull()
                    } else null
                    val line = onboardLine ?: callGeminiText(apiKey, prompt) ?: repo.voice.pickStartLine(vehicle)
                    val wav = getOrSynthesizeWav(apiKey, line, v)
                    if (wav != null) {
                        prewarmedStart[v] = line to wav
                    }
                }
            } catch (_: Exception) {
            } finally {
                prewarmingVoices.remove(v)
            }
        }
    }

    private fun testPhraseFor(voiceName: String, vehicle: String): String =
        "Hi Senpai! My $voiceName voice is online and ready for the $vehicle!"

    suspend fun speakStartGreeting(
        vehicle: String,
        driver: String,
        voiceName: String = repo.settings.value.geminiVoice
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        if (apiKey.isNullOrBlank() || !repo.settings.value.geminiVoiceEnabled) return@withContext null
        val v = voiceName.ifBlank { "Aoede" }

        if (repo.settings.value.voiceSource == "gemini") {
            // 1. Check if a pre-warmed dynamic greeting is ready for instant 0ms playback
            val warm = prewarmedStart.remove(v)
            if (warm != null) {
                val (warmText, warmWav) = warm
                if (playWavBytes(warmWav)) {
                    prewarmVoiceAsync(v) // Replenish in background for next drive
                    return@withContext warmText
                }
            }

            // 2. Single-pass fast synthesis (no double LLM wait!) using a canonical Merlin start line
            val fastLine = repo.voice.pickStartLine(vehicle)
            val wav = getOrSynthesizeWav(apiKey, fastLine, v)
            prewarmVoiceAsync(v) // Pre-warm dynamic line for next session
            if (wav != null && playWavBytes(wav)) {
                return@withContext fastLine
            }
            return@withContext null
        }

        // Device TTS mode with dynamic text
        val prompt = "The driver ($driver, whom you affectionately address naturally as 'Senpai') just started the car ($vehicle). Say a short, cheerful, bubbly 1-sentence greeting to start our trip together!"
        val greetingText = callGeminiText(apiKey, prompt) ?: repo.voice.pickStartLine(vehicle)
        repo.voice.speakDeviceOnly(greetingText)
        greetingText
    }

    suspend fun speakStopCommentary(
        endName: String,
        distanceStr: String,
        vehicle: String,
        driver: String,
        voiceName: String = repo.settings.value.geminiVoice
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        if (apiKey.isNullOrBlank() || !repo.settings.value.geminiVoiceEnabled) return@withContext null
        val v = voiceName.ifBlank { "Aoede" }

        if (repo.settings.value.voiceSource == "gemini") {
            // Single-pass TTS synthesis on our expressive Merlin stop line to eliminate double round-trip lag
            val fastStopLine = repo.voice.pickStopLine(endName, distanceStr, vehicle)
            val wav = getOrSynthesizeWav(apiKey, fastStopLine, v)
            if (wav != null && playWavBytes(wav)) {
                return@withContext fastStopLine
            }
            return@withContext null
        }

        val prompt = "The drive just ended near $endName in the $vehicle covering $distanceStr. Announce our safe arrival to $driver ('Senpai') in 1 short bubbly sentence!"
        val commentaryText = callGeminiText(apiKey, prompt) ?: repo.voice.pickStopLine(endName, distanceStr, vehicle)
        repo.voice.speakDeviceOnly(commentaryText)
        commentaryText
    }

    suspend fun speakPhrase(
        phrase: String,
        voiceName: String = repo.settings.value.geminiVoice
    ): Boolean = withContext(Dispatchers.IO) {
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        if (apiKey.isNullOrBlank()) return@withContext false
        val v = voiceName.ifBlank { "Aoede" }
        val wav = getOrSynthesizeWav(apiKey, phrase, v) ?: return@withContext false
        playWavBytes(wav)
    }

    suspend fun testVoice(voiceName: String = repo.settings.value.geminiVoice): String = withContext(Dispatchers.IO) {
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        val vehicle = repo.settings.value.vehicle.ifBlank { "Avalon" }
        val v = voiceName.ifBlank { "Aoede" }
        val phrase = testPhraseFor(v, vehicle)

        if (repo.settings.value.voiceSource == "gemini") {
            if (apiKey.isNullOrBlank()) {
                return@withContext "No Gemini API key stored. Paste your key in Settings first!"
            }
            val wav = getOrSynthesizeWav(apiKey, phrase, v)
            if (wav != null && playWavBytes(wav)) {
                // Also prewarm start greeting while we have key active
                prewarmVoiceAsync(v)
                return@withContext "Playing Gemini $v: \"$phrase\""
            }
            return@withContext "Gemini TTS unreachable; check connection or API key."
        }

        repo.voice.speakDeviceOnly(phrase)
        "Spoke via Device TTS: \"$phrase\""
    }

    private fun cacheFileFor(voiceName: String, text: String): File {
        val key = "${voiceName.trim().lowercase()}|${cleanForTts(text)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "tts_${voiceName.lowercase()}_$hex.wav")
    }

    private fun getOrSynthesizeWav(apiKey: String, textToSpeak: String, voiceName: String): ByteArray? {
        val cleanText = cleanForTts(textToSpeak)
        if (cleanText.isBlank()) return null

        val cached = cacheFileFor(voiceName, cleanText)
        if (cached.exists() && cached.length() > 100) {
            runCatching { return cached.readBytes() }
        }

        val rawAudio = synthesizeSpeech(apiKey, cleanText, voiceName) ?: return null
        val wavBytes = if (rawAudio.size >= 4 &&
            rawAudio[0] == 'R'.code.toByte() &&
            rawAudio[1] == 'I'.code.toByte() &&
            rawAudio[2] == 'F'.code.toByte() &&
            rawAudio[3] == 'F'.code.toByte()
        ) {
            rawAudio
        } else {
            pcmToWav(rawAudio, 24000, 1)
        }

        runCatching {
            cached.writeBytes(wavBytes)
            // Keep cache bounded to 40 most recent clips
            val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            if (files.size > 40) {
                files.drop(40).forEach { it.delete() }
            }
        }
        return wavBytes
    }

    private fun synthesizeSpeech(apiKey: String, cleanText: String, voiceName: String): ByteArray? {
        val models = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")

        val inputContent = JSONObject()
            .put("type", "text")
            .put("text", cleanText)
            .put("annotations", JSONArray().put(
                JSONObject().put("type", "speech_metadata").put("style", "cheerful and bubbly")
            ))

        val userInput = JSONObject()
            .put("type", "user_input")
            .put("content", JSONArray().put(inputContent))

        val speechConfigItem = JSONObject().put("voice", voiceName.ifBlank { "Aoede" })

        val basePayload = JSONObject()
            .put("input", JSONArray().put(userInput))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(speechConfigItem)))

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/interactions"
                val requestPayload = JSONObject(basePayload.toString())
                    .put("model", model)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Api-Revision", "2026-05-20")
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200)
                        Log.w("MerlinGemini", "$model TTS HTTP ${response.code}: $err")
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: return@use
                    val root = JSONObject(bodyStr)
                    if (root.has("output_audio")) {
                        val outAudio = root.getJSONObject("output_audio")
                        val b64 = outAudio.optString("data", "")
                        if (b64.isNotBlank()) {
                            return Base64.decode(b64, Base64.DEFAULT)
                        }
                    }
                    val steps = root.optJSONArray("steps")
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            if (step.optString("type") == "model_output") {
                                val content = step.optJSONArray("content")
                                if (content != null) {
                                    for (j in 0 until content.length()) {
                                        val c = content.getJSONObject(j)
                                        if (c.optString("type") == "audio") {
                                            val b64 = c.optString("data", "")
                                            if (b64.isNotBlank()) {
                                                return Base64.decode(b64, Base64.DEFAULT)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MerlinGemini", "TTS error $model: ${e.message}")
            }
        }

        // Mimi-AI cross-compatible generateContent audio modality fallback
        val genModels = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-2.5-flash")
        val genPayload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", cleanText))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName.ifBlank { "Aoede" })
                        })
                    })
                })
            })
        }
        for (modelName in genModels) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val httpReq = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(genPayload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(httpReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val candidates = root.optJSONArray("candidates") ?: return@use
                    val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return@use
                    val parts = content.optJSONArray("parts") ?: return@use
                    val inlineData = parts.optJSONObject(0)?.optJSONObject("inlineData")
                        ?: parts.optJSONObject(0)?.optJSONObject("inline_data")
                    val b64 = inlineData?.optString("data")
                    if (!b64.isNullOrBlank()) {
                        return Base64.decode(b64, Base64.DEFAULT)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun generateContextualReply(apiKey: String, userPrompt: String, telemetry: String): String? {
        return callGeminiText(apiKey, "Live Telemetry: $telemetry\nSenpai asks: $userPrompt")
    }

    private fun callGeminiText(apiKey: String, userPrompt: String): String? {
        val vehicle = repo.settings.value.vehicle.ifBlank { "Vehicle" }
        val systemText = "You are A-007 Merlin, the warm, bubbly, excitable pathfinder co-pilot of the $vehicle. You warmly address your driver naturally as 'Senpai'. Keep responses to 1 concise, energetic sentence. Do not use asterisks, markdown, quotes, emojis, or stage directions."
        val models = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/interactions"
                val requestPayload = JSONObject()
                    .put("model", model)
                    .put("system_instruction", systemText)
                    .put("input", userPrompt)
                    .put("generation_config", JSONObject().put("max_output_tokens", 60))

                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Api-Revision", "2026-05-20")
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200)
                        Log.w("MerlinGemini", "$model Text HTTP ${response.code}: $err")
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: return@use
                    val root = JSONObject(bodyStr)
                    val outText = root.optString("output_text", "")
                    if (outText.isNotBlank()) {
                        return cleanForTts(outText)
                    }
                    val steps = root.optJSONArray("steps")
                    if (steps != null) {
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            if (step.optString("type") == "model_output") {
                                val content = step.optJSONArray("content")
                                if (content != null) {
                                    for (j in 0 until content.length()) {
                                        val c = content.getJSONObject(j)
                                        if (c.optString("type") == "text") {
                                            val t = c.optString("text", "")
                                            val cleaned = cleanForTts(t)
                                            if (cleaned.isNotBlank()) return cleaned
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MerlinGemini", "Text call error $model: ${e.message}")
            }
        }
        return null
    }

    private fun playWavBytes(wavBytes: ByteArray): Boolean {
        return try {
            // Stop any Device TTS or previous Gemini MediaPlayer so dual voices NEVER happen
            repo.voice.stopDeviceTts()
            stopAudio()

            val temp = File(repo.context.cacheDir, "gemini_active_playback.wav")
            temp.writeBytes(wavBytes)
            synchronized(playerLock) {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(temp.absolutePath)
                    prepare()
                    setOnCompletionListener { player ->
                        synchronized(playerLock) {
                            if (activePlayer === player) activePlayer = null
                        }
                        runCatching { player.release() }
                    }
                    setOnErrorListener { player, _, _ ->
                        synchronized(playerLock) {
                            if (activePlayer === player) activePlayer = null
                        }
                        runCatching { player.release() }
                        true
                    }
                    start()
                }
                activePlayer = mp
            }
            true
        } catch (e: Exception) {
            Log.e("MerlinGemini", "Audio playback failed", e)
            false
        }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 24000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0
        header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        val out = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }

    private fun cleanForTts(raw: String): String {
        return raw.replace("*", "")
            .replace("\"", "")
            .replace("#", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
