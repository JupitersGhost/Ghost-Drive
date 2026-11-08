package com.jupiterlabs.ghostdrive.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.util.DevicePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.digests.SHA3Digest
import java.nio.ByteBuffer
import java.util.Locale

sealed interface VoiceUiAction {
    data object OpenCockpitMap : VoiceUiAction
    data object ExitCockpitMap : VoiceUiAction
    data class SwitchTab(val tabIndex: Int) : VoiceUiAction
    data object TriggerSync : VoiceUiAction
    data object StartSimulator : VoiceUiAction
    data object StopMonitoring : VoiceUiAction
}

data class VoiceCommanderStatus(
    val armed: Boolean = false,
    val listening: Boolean = false,
    val directCommandMode: Boolean = false,
    val awaitingFollowUp: Boolean = false,
    val onDeviceModelInstalled: Boolean = false,
    val onDeviceModelSupported: Boolean = false,
    val partialTranscript: String = "",
    val lastHeard: String = "",
    val lastActionSummary: String = "",
    val statusLabel: String = "Hey Merlin standby (toggle ON to arm)"
)

/**
 * On-Device "Hey Merlin" Wake-Word & Voice Command Engine for S25 Ultra (Hexagon NPU) & Pixel 11 Pro XL (Tensor TPU).
 * Built on Mimi-AI's persistent on-device SpeechRecognizer architecture:
 *  - Listens for "Hey Merlin" / "Okay Merlin" / "Yo Merlin" when armed
 *  - Supports both single-breath ("Hey Merlin, roll a d20") and two-step ("Hey Merlin" -> "Yeah, Senpai?" -> command)
 *  - Pauses automatically while Merlin is speaking so she never triggers on her own voice
 *  - Executes instant hardware/UI/telemetry voice commands or routes open questions to the Onboard Brain + Aoede voice.
 */
class MerlinVoiceCommander(
    private val context: Context,
    private val repo: Repository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDeviceRecognizer: Boolean = false
    private var isListeningInternal: Boolean = false
    private var followUpUntilMs: Long = 0L
    private var directOneShotUntilMs: Long = 0L
    private var rollCounter: Int = 0

    private val _uiActions = MutableSharedFlow<VoiceUiAction>(extraBufferCapacity = 8)
    val uiActions = _uiActions.asSharedFlow()

    val status = MutableStateFlow(VoiceCommanderStatus())

    companion object {
        const val EXTRA_ENABLE_FORMATTING = "android.speech.extra.ENABLE_FORMATTING"
        const val FORMATTING_OPTIMIZE_QUALITY = "android.speech.formatting.optimize_quality"

        private val WAKE_PREFIXES = listOf(
            "hey merlin",
            "okay merlin",
            "ok merlin",
            "yo merlin",
            "hi merlin",
            "hello merlin",
            "merlin"
        )
    }

    init {
        checkOnDeviceSpeechSupport()
        scope.launch {
            repo.settings.collect { s ->
                syncArmedState(s.heyMerlinEnabled)
            }
        }
        // Watchdog loop on main thread to keep "Hey Merlin" listening when armed & Merlin is not currently speaking
        scope.launch {
            while (true) {
                delay(1400L)
                val now = System.currentTimeMillis()
                val inFollowUp = now < followUpUntilMs
                val inDirect = now < directOneShotUntilMs
                if (status.value.awaitingFollowUp != inFollowUp || status.value.directCommandMode != inDirect) {
                    status.value = status.value.copy(
                        awaitingFollowUp = inFollowUp,
                        directCommandMode = inDirect
                    )
                }
                val shouldListen = (repo.settings.value.heyMerlinEnabled || inDirect || inFollowUp) && hasMicPermission()
                if (!shouldListen) {
                    if (isListeningInternal) stopListeningInternal()
                    continue
                }
                if (repo.voice.isSpeaking()) {
                    if (isListeningInternal) {
                        stopListeningInternal()
                        status.value = status.value.copy(
                            listening = false,
                            statusLabel = "⏸ Paused while Merlin speaks…"
                        )
                    }
                    continue
                }
                if (!isListeningInternal) {
                    startListeningCycle()
                }
            }
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun checkOnDeviceSpeechSupport() {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            status.value = status.value.copy(onDeviceModelInstalled = false, onDeviceModelSupported = false)
            return
        }
        mainHandler.post {
            runCatching {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                }
                val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val exec = ContextCompat.getMainExecutor(context)
                rec.checkRecognitionSupport(intent, exec, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = support.installedOnDeviceLanguages.any { it.startsWith("en", ignoreCase = true) }
                        val supported = support.supportedOnDeviceLanguages.any { it.startsWith("en", ignoreCase = true) }
                        status.value = status.value.copy(
                            onDeviceModelInstalled = installed,
                            onDeviceModelSupported = supported
                        )
                        runCatching { rec.destroy() }
                    }

                    override fun onError(errorCode: Int) {
                        runCatching { rec.destroy() }
                    }
                })
            }
        }
    }

    fun downloadOnDeviceSpeechModel(onProgressMsg: (String) -> Unit = {}) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onProgressMsg("Using standard Android speech recognizer on ${DevicePlatform.deviceShortName}")
            return
        }
        mainHandler.post {
            runCatching {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                }
                val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val exec = ContextCompat.getMainExecutor(context)
                rec.triggerModelDownload(intent, exec, object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {
                        onProgressMsg("Downloading ${DevicePlatform.npuShortName} speech pack: $completedPercent%")
                    }

                    override fun onSuccess() {
                        status.value = status.value.copy(onDeviceModelInstalled = true, onDeviceModelSupported = true)
                        onProgressMsg("✓ On-device ${DevicePlatform.npuShortName} speech model ready!")
                        runCatching { rec.destroy() }
                    }

                    override fun onScheduled() {
                        onProgressMsg("Speech model download scheduled by Android AICore")
                        runCatching { rec.destroy() }
                    }

                    override fun onError(error: Int) {
                        onProgressMsg("Using hybrid speech recognizer ($error)")
                        runCatching { rec.destroy() }
                    }
                })
            }
        }
    }

    fun syncArmedState(enabled: Boolean) {
        mainHandler.post {
            if (enabled && hasMicPermission()) {
                status.value = status.value.copy(
                    armed = true,
                    statusLabel = "🟢 Listening for \"Hey Merlin\" (${DevicePlatform.npuShortName})"
                )
                if (!isListeningInternal && !repo.voice.isSpeaking()) {
                    startListeningCycle()
                }
            } else {
                followUpUntilMs = 0L
                directOneShotUntilMs = 0L
                stopListeningInternal()
                status.value = status.value.copy(
                    armed = false,
                    listening = false,
                    awaitingFollowUp = false,
                    directCommandMode = false,
                    partialTranscript = "",
                    statusLabel = if (enabled && !hasMicPermission()) "⚠️ Grant Microphone permission to arm Hey Merlin"
                    else "⚪ Hey Merlin standby (toggle ON to arm)"
                )
            }
        }
    }

    /**
     * Triggers an immediate 10-second direct command window (no "Hey Merlin" wake-word required)
     * when the user taps the 🎤 button.
     */
    fun triggerDirectVoiceCommand() {
        mainHandler.post {
            if (!hasMicPermission()) {
                status.value = status.value.copy(statusLabel = "⚠️ Grant Microphone permission first")
                return@post
            }
            repo.voice.stopAllSpeech()
            directOneShotUntilMs = System.currentTimeMillis() + 10_000L
            status.value = status.value.copy(
                directCommandMode = true,
                statusLabel = "🎤 Speak your command for Merlin now…"
            )
            stopListeningInternal()
            startListeningCycle()
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer != null) return recognizer
        val preferOnDevice = Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
            status.value.onDeviceModelInstalled

        val created = runCatching {
            if (preferOnDevice) {
                usingOnDeviceRecognizer = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                usingOnDeviceRecognizer = false
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()

        recognizer = created
        return created
    }

    private fun startListeningCycle() {
        mainHandler.post {
            if (isListeningInternal || repo.voice.isSpeaking() || !hasMicPermission()) return@post
            val rec = ensureRecognizer() ?: return@post

            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    val now = System.currentTimeMillis()
                    val label = when {
                        now < directOneShotUntilMs -> "🎤 Listening for direct command…"
                        now < followUpUntilMs -> "🟢 Listening for follow-up command, Senpai…"
                        else -> "🟢 Listening for \"Hey Merlin\" (${DevicePlatform.npuShortName})"
                    }
                    status.value = status.value.copy(listening = true, statusLabel = label)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListeningInternal = false
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val part = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (part.isNotBlank()) {
                        status.value = status.value.copy(partialTranscript = part)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListeningInternal = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    status.value = status.value.copy(listening = false, partialTranscript = "")
                    if (text.isNotBlank()) {
                        handleRecognizedSpeech(text)
                    }
                }

                override fun onError(error: Int) {
                    isListeningInternal = false
                    status.value = status.value.copy(listening = false, partialTranscript = "")
                    if (error == 11 /* ERROR_SERVER_DISCONNECTED */ ||
                        error == SpeechRecognizer.ERROR_CLIENT ||
                        error == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ ||
                        error == 13 /* ERROR_LANGUAGE_UNAVAILABLE */
                    ) {
                        runCatching { recognizer?.destroy() }
                        recognizer = null
                        if (usingOnDeviceRecognizer) {
                            status.value = status.value.copy(onDeviceModelInstalled = false)
                        }
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
                putExtra(EXTRA_ENABLE_FORMATTING, FORMATTING_OPTIMIZE_QUALITY)
                putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            }

            isListeningInternal = true
            runCatching {
                rec.startListening(intent)
            }.onFailure { e ->
                Log.w("MerlinVoiceCmd", "startListening failed: ${e.message}")
                isListeningInternal = false
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
        }
    }

    private fun stopListeningInternal() {
        mainHandler.post {
            isListeningInternal = false
            runCatching { recognizer?.cancel() }
            status.value = status.value.copy(listening = false, partialTranscript = "")
        }
    }

    /**
     * Parses a recognized utterance for "Hey Merlin" wake-word (or direct/follow-up state)
     * and dispatches the matching voice command or onboard chat query.
     */
    fun handleRecognizedSpeech(rawUtterance: String) {
        val now = System.currentTimeMillis()
        val inDirect = now < directOneShotUntilMs
        val inFollowUp = now < followUpUntilMs

        val normalized = rawUtterance.trim()
        val lower = normalized.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        var matchedWake = false
        var commandBody = normalized

        for (wake in WAKE_PREFIXES) {
            val idx = lower.indexOf(wake)
            if (idx != -1 && (idx <= 12 || inDirect || inFollowUp)) {
                matchedWake = true
                // Extract the portion after the wake phrase
                val rawIdx = normalized.lowercase(Locale.US).indexOf(wake.split(" ").last())
                if (rawIdx != -1) {
                    val after = normalized.substring((rawIdx + wake.split(" ").last().length).coerceAtMost(normalized.length))
                        .trimStart(',', '.', '!', '?', ' ', '-')
                    commandBody = after
                } else {
                    commandBody = ""
                }
                break
            }
        }

        if (!matchedWake && !inDirect && !inFollowUp) {
            // Ignored ambient speech that did not say "Hey Merlin"
            return
        }

        directOneShotUntilMs = 0L
        status.value = status.value.copy(lastHeard = normalized)

        // If user just said "Hey Merlin" with no command yet, acknowledge and open 12s follow-up window!
        if (commandBody.isBlank()) {
            followUpUntilMs = System.currentTimeMillis() + 12_000L
            status.value = status.value.copy(
                awaitingFollowUp = true,
                lastActionSummary = "Wake word detected · awaiting command",
                statusLabel = "🟢 Yes, Senpai? Speak your command…"
            )
            repo.voice.speak("Yeah, Senpai? I'm listening!", force = true)
            return
        }

        followUpUntilMs = 0L
        executeVoiceCommand(commandBody)
    }

    /**
     * Executes a parsed voice command on-device, or routes open-ended questions to MerlinOnboardEngine.
     */
    fun executeVoiceCommand(rawCommand: String) {
        val clean = rawCommand.trim()
        val q = clean.lowercase(Locale.US)

        when {
            // 1. Sitrep / Status Report
            q.contains("sitrep") || q.contains("status report") || q == "status" || q.contains("how are we doing") -> {
                recordAction("🎙️ Voice Command: Sitrep")
                repo.voice.speakSitrep()
            }

            // 2. Roll a d20 / Physical Entropy Roll
            q.contains("roll") && (q.contains("d20") || q.contains("20") || q.contains("dice") || q.contains("die")) -> {
                val (roll, proof) = drawPhysicalD20()
                val flair = when (roll) {
                    20 -> "Critical success! Natural 20, Senpai!"
                    1 -> "Oof, natural 1! Careful on the road, Senpai!"
                    else -> "Rolled a $roll out of 20, Senpai!"
                }
                val msg = "$flair Conditioned SHA3-512 proof $proof."
                recordAction("🎲 Rolled $roll/20 (proof $proof)")
                repo.log("INFO", "VoiceCmd", "Hey Merlin d20 roll: $roll/20 (SHA3-512 proof $proof)")
                repo.voice.speak(msg, force = true)
            }

            // 3. Save / Bookmark Current Location as a Place
            (q.startsWith("save this place as ") || q.startsWith("mark this spot as ") ||
                q.startsWith("save location as ") || q.startsWith("bookmark this as ") ||
                q.startsWith("save place as ")) -> {
                val placeName = clean.replace(
                    Regex("^(save this place as|mark this spot as|save location as|bookmark this as|save place as)\\s+", RegexOption.IGNORE_CASE),
                    ""
                ).trim().trim('.', '!', '"')
                val fix = repo.live.value.lastFix ?: repo.trips.value.firstOrNull()?.end
                if (placeName.isNotBlank() && fix != null) {
                    val newPlace = Place(
                        name = placeName,
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        radiusM = 150.0,
                        userDefined = true
                    )
                    repo.savePlace(newPlace)
                    recordAction("📍 Saved Place: $placeName")
                    repo.voice.speak("Saved $placeName to our map with a 150 meter recognition zone, Senpai!", force = true)
                } else if (placeName.isBlank()) {
                    repo.voice.speak("Tell me what name to save this place as, Senpai!", force = true)
                } else {
                    repo.voice.speak("I need a GPS fix before I can mark this spot, Senpai!", force = true)
                }
            }

            // 4. Open Fullscreen Cockpit Map
            (q.contains("cockpit") || q.contains("fullscreen map") || q.contains("full screen map") || q == "open map") &&
                !q.contains("close") && !q.contains("exit") -> {
                recordAction("🗺️ Opened Fullscreen Cockpit HUD")
                _uiActions.tryEmit(VoiceUiAction.OpenCockpitMap)
                repo.voice.speak("Opening fullscreen cockpit HUD, Senpai!", force = true)
            }

            // 5. Close Fullscreen Map / Go Home
            q.contains("close map") || q.contains("exit fullscreen") || q.contains("exit cockpit") || q.contains("go home") || q.contains("home screen") -> {
                recordAction("🏠 Returned to Home Screen")
                _uiActions.tryEmit(VoiceUiAction.ExitCockpitMap)
                _uiActions.tryEmit(VoiceUiAction.SwitchTab(0))
                repo.voice.speak("Back on the main dashboard, Senpai!", force = true)
            }

            // 6. Open Entropy Tab / Score Phone Now
            q.contains("open entropy") || q.contains("show entropy") -> {
                recordAction("🎲 Opened Tri-Fusion Entropy Tab")
                _uiActions.tryEmit(VoiceUiAction.SwitchTab(3))
                repo.voice.speak("Opening the Tri-Fusion entropy observatory, Senpai!", force = true)
            }

            q.contains("score phone") || q.contains("sample phone") || q.contains("entropy score") || q.contains("phone score") -> {
                repo.phoneHarvester.sampleOneShotWindow()
                val pe = repo.live.value.phoneEntropy
                val scoreMsg = if (pe.scorePct > 0.0) {
                    String.format(Locale.US, "Sampling fresh window on your %s! Current EntropicDroid score is %.1f out of 100 with %d credited bits, Senpai!", DevicePlatform.deviceShortName, pe.scorePct, pe.creditedBits)
                } else {
                    "Sampling a 1.5 second EntropicDroid window on your ${DevicePlatform.deviceShortName} right now, Senpai!"
                }
                recordAction("⚡ Sampled & Scored Phone Entropy")
                repo.voice.speak(scoreMsg, force = true)
            }

            // 7. Watch 5 Biometrics
            q.contains("heart rate") || q.contains("pulse") || q.contains("blood oxygen") || q.contains("spo2") || q.contains("watch status") -> {
                val w = repo.live.value.watch
                val v = repo.live.value.entropyVault
                val msg = if (w.hrBpm != null) {
                    "Your Galaxy Watch 5 heart rate is ${w.hrBpm.toInt()} BPM with ${w.spo2Pct ?: 98}% blood oxygen and ${w.steps} steps, Senpai!"
                } else {
                    "Your Galaxy Watch 5 is in low-power staging mode with ${v.pendingWatchBlocks} blocks queued for Nullvalon fusion, Senpai!"
                }
                recordAction("⌚ Read Watch 5 Biometrics")
                repo.voice.speak(msg, force = true)
            }

            // 8. Map Style / Theme Switching
            q.contains("matrix mode") || q.contains("matrix map") || q.contains("matrix theme") -> {
                val s = repo.settings.value
                repo.saveSettings(s.copy(theme = "matrix", mapStyle = "matrix"))
                recordAction("🟢 Switched to Matrix HUD Mode")
                repo.voice.speak("Matrix HUD engaged, Senpai!", force = true)
            }

            q.contains("night mode") || q.contains("night map") || q.contains("midnight theme") -> {
                val s = repo.settings.value
                repo.saveSettings(s.copy(theme = "midnight", mapStyle = "night"))
                recordAction("🌙 Switched to Night HUD Mode")
                repo.voice.speak("Night cockpit mode active, Senpai!", force = true)
            }

            q.contains("merlin theme") || q.contains("merlin mode") -> {
                val s = repo.settings.value
                repo.saveSettings(s.copy(theme = "merlin"))
                recordAction("✨ Switched to Merlin Theme")
                repo.voice.speak("Merlin signature theme active, Senpai!", force = true)
            }

            q.contains("day mode") || q.contains("day map") -> {
                val s = repo.settings.value
                repo.saveSettings(s.copy(mapStyle = "day"))
                recordAction("☀️ Switched to Day Map")
                repo.voice.speak("Switched map to daylight filter, Senpai!", force = true)
            }

            // 9. Private Sync
            q.contains("sync now") || q.contains("push sync") || q.contains("upload trips") -> {
                recordAction("☁️ Triggered Private Sync")
                _uiActions.tryEmit(VoiceUiAction.TriggerSync)
                repo.voice.speak("Syncing our trips and Tri-Fusion entropy vault now, Senpai!", force = true)
            }

            // 10. Simulator Start / Stop
            q.contains("start simulator") || q.contains("try simulator") -> {
                recordAction("🚗 Started Simulator")
                _uiActions.tryEmit(VoiceUiAction.StartSimulator)
            }

            q.contains("stop simulator") || q.contains("stop monitoring") -> {
                recordAction("⏹ Stopped Session")
                _uiActions.tryEmit(VoiceUiAction.StopMonitoring)
            }

            // 11. General Conversational Query -> Onboard Brain + Aoede Voice
            else -> {
                recordAction("🧠 Onboard Reply: \"$clean\"")
                repo.voice.askMerlinInteractive(clean)
            }
        }
    }

    private fun recordAction(summary: String) {
        status.value = status.value.copy(
            lastActionSummary = summary,
            statusLabel = if (repo.settings.value.heyMerlinEnabled) {
                "🟢 Listening for \"Hey Merlin\" (${DevicePlatform.npuShortName})"
            } else {
                "⚪ Hey Merlin standby"
            }
        )
    }

    private fun drawPhysicalD20(): Pair<Int, String> {
        rollCounter++
        val sha3 = SHA3Digest(512)
        val domain = "GHOSTDRIVE-VOICE-D20-v1\u0000".toByteArray(Charsets.UTF_8)
        sha3.update(domain, 0, domain.size)
        val v = repo.live.value.entropyVault
        val seedHex = v.lastDigestHex.ifBlank {
            repo.live.value.phoneEntropy.lastBlockDigestHex.ifBlank { repo.live.value.watch.lastWatchDigestHex }
        }
        if (seedHex.length >= 64) {
            val clean = seedHex.take(128)
            val seedBytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            sha3.update(seedBytes, 0, seedBytes.size)
        }
        val meta = ByteBuffer.allocate(20)
            .putLong(System.nanoTime())
            .putLong(System.currentTimeMillis())
            .putInt(rollCounter)
            .array()
        sha3.update(meta, 0, meta.size)
        val out = ByteArray(64)
        sha3.doFinal(out, 0)
        val roll = ((ByteBuffer.wrap(out, 0, 8).long and Long.MAX_VALUE) % 20L).toInt() + 1
        val proof = out.take(4).joinToString("") { "%02x".format(it) }
        return roll to proof
    }

    fun shutdown() {
        mainHandler.post {
            isListeningInternal = false
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }
}
