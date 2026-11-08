package com.jupiterlabs.ghostdrive.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.data.Settings
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

data class TtsVoiceInfo(
    val name: String,
    val displayName: String,
    val locale: String,
    val isNetwork: Boolean
)

class VoiceCopilot(context: Context, private val repo: Repository) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private var lastAnnouncedPlace: String? = null
    private var lastPlaceTimeMs: Long = 0L
    private var sessionStartTimeMs: Long = 0L
    private var focusRequest: AudioFocusRequest? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeSpeechJob: Job? = null
    val gemini = MerlinGeminiClient(repo)
    val onboard = MerlinOnboardEngine(repo)
    val commander by lazy { MerlinVoiceCommander(appContext, repo) }

    private var lastStartIdx = -1
    private var lastStopIdx = -1

    companion object {
        val START_LINES = listOf(
            "Engine running, Senpai! The %s is purring and ready to roll! Nobody gets left behind today.",
            "Nullvalon is linked! All systems green in the %s. Where are we exploring today, Senpai?",
            "We're rolling! Keep your eyes on the road, Senpai, and I'll watch the crossings from the dash. Let's go!",
            "Chaos Heart online! The %s is humming with good vibes. Ready when you are, Senpai!",
            "GPS locked in, Senpai! I've got our bearings charted. Time to make some great memories on the road!",
            "Nullvalon verified! Squeee, road trip time! I hope we find some really interesting shiny rocks today, Senpai.",
            "Trip tracking armed! The %s is in top shape. Let's have a safe, wonderful drive, Senpai!",
            "Wandering Compass online! I'll keep every mile accounted for. Ready to roll, Senpai!",
            "Connected and purring! Don't worry about getting lost, Senpai, I always remember the way home.",
            "Telemetry is streaming! Settle in, grab your coffee, Senpai, and let's glide down the road in the %s!",
            "Ooh, fresh journey! The sensors are happy and the road is wide open. Let's do this, Senpai!",
            "Pathfinder standing by! Full route recording engaged for the %s. Drive safe out there, Senpai!",
            "Signal crystal clear! I'm in the copilot seat and ready for whatever twists and turns come our way, Senpai!",
            "Nullvalon handshake complete! The spirit is alive and kicking. Rolling out, Senpai!",
            "Route logging started! I promise to keep quiet during tricky merges, Senpai... mostly!",
            "All green across the board! Car's moving, tires are turning, and the map is live. Let's ride, Senpai!",
            "Journey initiated in the %s! The road ahead looks lovely today. Onward, Senpai!",
            "Sensors linked! Remember Senpai, smooth turns and good music make the journey twice as fun.",
            "Here we go, Senpai! Nullvalon is locked and loaded. Let's chart another great drive together!",
            "Wandering mode active! I've got the compass, you've got the wheel, Senpai. Let's make it a great one!"
        )

        val STOP_LINES = listOf(
            "We've arrived safely, Senpai! Journey ended near %s. We recorded a total of %s! Great driving today!",
            "And stop, Senpai! Parked safely near %s. That was %s together in the books. The route stays ours!",
            "Whew, made it! Journey closed near %s after %s. Time to let the car take a well-deserved breather, Senpai!",
            "Safely at rest, Senpai! Parked near %s. We logged %s on this run. Nobody left behind!",
            "Destination reached near %s! That's %s charted and vaulted, Senpai. I'll keep an eye on things while we're parked.",
            "Journey complete, Senpai! Stopped near %s with %s on the clock. What a fun little trip!",
            "Car's shut down near %s! We covered %s today. Thanks for bringing me along for the ride, Senpai!",
            "Touchdown near %s, Senpai! %s successfully recorded. All route points are safely tucked into the journal!",
            "We made it, Senpai! Parked near %s after %s. Now, do you think there are any cool rocks around here?",
            "Session ended near %s. Total distance, %s! Another safe journey remembered by the Wandering Compass, Senpai.",
            "All parked up near %s, Senpai! %s of good honest road time. Everything's logged and secure.",
            "Endpoint locked near %s! That was a solid %s drive, Senpai. The %s did fantastic today!",
            "Journey wrapped up near %s, Senpai! We clocked %s. Time to stretch your legs!",
            "And cut, Senpai! Safely stopped near %s. %s tracked from start to finish without missing a beat!",
            "Welcome to %s, Senpai! %s logged in the journey vault. I'll watch the perimeter while you're out!",
            "Drive finished! Parked near %s after %s. Great job navigating out there today, Senpai!",
            "Journey concluded near %s! %s safely stored in local memory. The trail home is clear, Senpai.",
            "Parked and sound near %s, Senpai! Logged %s. Resting for now!",
            "Final fix saved near %s! That makes %s for this leg. Catch you on the next turn, Senpai!",
            "Safe and sound near %s, Senpai! We recorded %s today. Rest up, pathfinder!"
        )
    }

    init {
        runCatching {
            tts = TextToSpeech(appContext, this)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { onboard.refreshStatus() }
        }
        scope.launch {
            runCatching { commander.checkOnDeviceSpeechSupport() }
            repo.settings.collect { s ->
                applyVoiceConfig(s)
                if (s.voiceEnabled && s.voiceSource == "gemini" && repo.vault.present("gemini_api_key")) {
                    gemini.prewarmVoiceAsync(s.geminiVoice)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            runCatching {
                val res = engine.setLanguage(Locale.US)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                applyVoiceConfig(repo.settings.value)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                engine.setAudioAttributes(attrs)
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runCatching { audioManager?.requestAudioFocus(req) }
                    }
                    override fun onDone(utteranceId: String?) {
                        runCatching { audioManager?.abandonAudioFocusRequest(req) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runCatching { audioManager?.abandonAudioFocusRequest(req) }
                    }
                })
                ready = true
                while (true) {
                    val next = pendingQueue.poll() ?: break
                    speakDeviceOnly(next)
                }
            }
        }
    }

    fun isSpeaking(): Boolean {
        val ttsSpeaking = runCatching { tts?.isSpeaking == true }.getOrDefault(false)
        return ttsSpeaking || gemini.isPlaying()
    }

    fun stopDeviceTts() {
        runCatching { tts?.stop() }
    }

    fun stopAllSpeech() {
        stopDeviceTts()
        gemini.stopAudio()
    }

    fun applyVoiceConfig(s: Settings = repo.settings.value) {
        val engine = tts ?: return
        runCatching {
            engine.setPitch(s.voicePitch.coerceIn(0.5f, 2.0f))
            engine.setSpeechRate(s.voiceRate.coerceIn(0.5f, 2.0f))
            if (s.voiceName.isNotBlank()) {
                val targetVoice = engine.voices?.firstOrNull { it.name == s.voiceName }
                if (targetVoice != null) {
                    engine.voice = targetVoice
                }
            }
        }
    }

    fun getAvailableVoices(): List<TtsVoiceInfo> {
        val engine = tts ?: return emptyList()
        return runCatching {
            val voices = engine.voices ?: return emptyList()
            voices.filter {
                it.locale.language.equals("en", ignoreCase = true) || it.locale == Locale.getDefault()
            }.sortedBy { it.name }.map { v ->
                val label = buildVoiceLabel(v)
                TtsVoiceInfo(
                    name = v.name,
                    displayName = label,
                    locale = v.locale.toLanguageTag(),
                    isNetwork = v.isNetworkConnectionRequired
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildVoiceLabel(v: Voice): String {
        val country = v.locale.displayCountry.ifBlank { v.locale.displayName }
        val gender = when {
            v.name.contains("female", ignoreCase = true) || v.name.contains("woman", ignoreCase = true) -> "Female"
            v.name.contains("male", ignoreCase = true) || v.name.contains("man", ignoreCase = true) -> "Male"
            else -> ""
        }
        val identifier = v.name.substringAfterLast('#', v.name.substringAfterLast(':', v.name.substringAfterLast('-')))
        return listOf(country, gender, identifier).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun speak(text: String, force: Boolean = false) {
        val s = repo.settings.value
        if (!force && !s.voiceEnabled) return
        val clean = text.trim()
        if (clean.isEmpty()) return

        activeSpeechJob?.cancel()
        stopAllSpeech()

        if (s.voiceSource == "gemini" && repo.vault.present("gemini_api_key")) {
            activeSpeechJob = scope.launch {
                val ok = withTimeoutOrNull(7500L) {
                    gemini.speakPhrase(clean, s.geminiVoice)
                } ?: false
                if (!ok) {
                    speakDeviceOnly(clean)
                }
            }
            return
        }

        speakDeviceOnly(clean)
    }

    fun speakDeviceOnly(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        gemini.stopAudio()
        if (!ready) {
            pendingQueue.clear()
            pendingQueue.offer(clean)
            return
        }
        runCatching {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            // QUEUE_FLUSH guarantees we never stack two overlapping utterances
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
        }
    }

    fun pickStartLine(vehicle: String): String {
        var idx = (START_LINES.indices).random()
        if (idx == lastStartIdx) idx = (idx + 1) % START_LINES.size
        lastStartIdx = idx
        val v = vehicle.ifBlank { "Avalon" }
        val template = START_LINES[idx]
        return if (template.contains("%s")) String.format(Locale.US, template, v) else template
    }

    fun pickStopLine(place: String, distStr: String, vehicle: String): String {
        var idx = (STOP_LINES.indices).random()
        if (idx == lastStopIdx) idx = (idx + 1) % STOP_LINES.size
        lastStopIdx = idx
        val template = STOP_LINES[idx]
        val v = vehicle.ifBlank { "Avalon" }
        return if (template.countOccurrences("%s") >= 3) {
            String.format(Locale.US, template, place, distStr, v)
        } else {
            String.format(Locale.US, template, place, distStr)
        }
    }

    private fun String.countOccurrences(sub: String): Int {
        var count = 0
        var idx = 0
        while (indexOf(sub, idx).also { idx = it } != -1) {
            count++
            idx += sub.length
        }
        return count
    }

    fun onSessionStart(vehicle: String, isSimulator: Boolean) {
        if (!repo.settings.value.voiceEnabled) return
        lastAnnouncedPlace = null
        sessionStartTimeMs = System.currentTimeMillis()
        val v = vehicle.ifBlank { "Avalon" }
        val driver = repo.settings.value.driver.ifBlank { "driver" }
        if (isSimulator) {
            speak("Simulator engaged. Merlin co-pilot online, Senpai!")
            return
        }
        activeSpeechJob?.cancel()
        stopAllSpeech()
        activeSpeechJob = scope.launch {
            var spoken = false
            if (repo.settings.value.geminiVoiceEnabled && repo.vault.present("gemini_api_key")) {
                val voice = repo.settings.value.geminiVoice.ifBlank { "Aoede" }
                val dynamic = withTimeoutOrNull(8500L) {
                    gemini.speakStartGreeting(v, driver, voice)
                }
                if (!dynamic.isNullOrBlank()) {
                    spoken = true
                }
            }
            if (!spoken) {
                speakDeviceOnly(pickStartLine(v))
            }
        }
    }

    fun onSessionStop(endName: String, distanceM: Double, units: String, isSimulator: Boolean) {
        if (!repo.settings.value.voiceEnabled) return
        val distStr = if (units == "mi") {
            String.format(Locale.US, "%.1f miles", distanceM / 1609.344)
        } else {
            String.format(Locale.US, "%.1f kilometers", distanceM / 1000.0)
        }
        val placeSpoken = if (endName.contains(",") || endName.startsWith("Waiting") || endName.startsWith("No reliable")) {
            "recorded endpoint"
        } else {
            endName
        }
        val v = repo.settings.value.vehicle.ifBlank { "Avalon" }
        val driver = repo.settings.value.driver.ifBlank { "driver" }

        if (isSimulator) {
            speak("Simulator ended, Senpai. Recorded $distStr.")
            return
        }
        activeSpeechJob?.cancel()
        stopAllSpeech()
        activeSpeechJob = scope.launch {
            var spoken = false
            if (repo.settings.value.geminiVoiceEnabled && repo.vault.present("gemini_api_key")) {
                val voice = repo.settings.value.geminiVoice.ifBlank { "Aoede" }
                val dynamic = withTimeoutOrNull(8500L) {
                    gemini.speakStopCommentary(placeSpoken, distStr, v, driver, voice)
                }
                if (!dynamic.isNullOrBlank()) {
                    spoken = true
                }
            }
            if (!spoken) {
                speakDeviceOnly(pickStopLine(placeSpoken, distStr, v))
            }
        }
    }

    suspend fun testVoiceGreeting(voiceOverride: String? = null): String {
        activeSpeechJob?.cancel()
        stopAllSpeech()
        val apiKey = repo.vault.get("gemini_api_key")?.toString(Charsets.UTF_8)?.trim()
        val v = repo.settings.value.vehicle.ifBlank { "Avalon" }
        val targetVoice = voiceOverride ?: repo.settings.value.geminiVoice.ifBlank { "Aoede" }
        return if (!apiKey.isNullOrBlank() && repo.settings.value.voiceSource == "gemini") {
            gemini.testVoice(targetVoice)
        } else {
            val line = pickStartLine(v)
            speakDeviceOnly(line)
            line
        }
    }

    fun onFixMatchedPlace(place: Place?) {
        if (place == null || !place.userDefined) return
        val now = System.currentTimeMillis()
        // If we just started a session within the last 25s, record the starting place silently
        // so we don't interrupt the session start greeting with "Approaching <start place>".
        if (now - sessionStartTimeMs < 25_000L) {
            lastAnnouncedPlace = place.name
            lastPlaceTimeMs = now
            return
        }
        if (place.name != lastAnnouncedPlace && now - lastPlaceTimeMs > 45_000L) {
            lastAnnouncedPlace = place.name
            lastPlaceTimeMs = now
            speak("Approaching ${place.name}, Senpai.")
        }
    }

    fun onEntropyBlockHarvested(totalBlocks: Int) {
        if (!repo.settings.value.voiceEnabled || !repo.settings.value.voiceAnnounceEntropy) return
        if (totalBlocks == 1 || totalBlocks % 5 == 0) {
            speak("Conditioned entropy block $totalBlocks vaulted.")
        }
    }

    fun speakSitrep() {
        val s = repo.settings.value
        if (s.onboardBrainEnabled) {
            activeSpeechJob?.cancel()
            stopAllSpeech()
            activeSpeechJob = scope.launch {
                onboard.askMerlin(
                    "Give me a quick, cheerful status sitrep on our car, Watch 5 biometrics, and phone entropy score!",
                    speakAloud = true
                )
            }
            return
        }
        val l = repo.live.value
        val v = s.vehicle.ifBlank { "Avalon" }
        val pe = l.phoneEntropy
        val scoreStr = if (pe.scorePct > 0.0) String.format(Locale.US, "%.0f out of 100", pe.scorePct) else "ready"
        val hrPart = l.watch.hrBpm?.let { " Your Watch 5 heart rate is ${it.toInt()} BPM." } ?: ""
        val line = if (l.trip != null) {
            val dist = if (s.displayUnits == "mi") {
                String.format(Locale.US, "%.1f miles", l.trip.distanceM / 1609.344)
            } else {
                String.format(Locale.US, "%.1f kilometers", l.trip.distanceM / 1000.0)
            }
            "All systems green in the $v, Senpai! We've logged $dist toward ${l.trip.endName}.$hrPart Phone entropy score is $scoreStr, and the Tri-Fusion vault has ${l.entropyVault.blocks} blocks!"
        } else {
            val parkedAt = repo.trips.value.firstOrNull { !it.demo && it.endMs != null }?.endName ?: "home base"
            "Standing by near $parkedAt, Senpai! The $v is resting at zero battery drain.$hrPart Phone entropy scored $scoreStr, and we have ${l.entropyVault.blocks} Tri-Fusion blocks vaulted!"
        }
        speak(line, force = true)
    }

    fun askMerlinInteractive(prompt: String, onReply: (String, String) -> Unit = { _, _ -> }) {
        activeSpeechJob?.cancel()
        stopAllSpeech()
        activeSpeechJob = scope.launch {
            val (reply, backend) = onboard.askMerlin(prompt, speakAloud = true)
            onReply(reply, backend)
        }
    }

    fun shutdown() {
        runCatching {
            commander.shutdown()
            activeSpeechJob?.cancel()
            scope.cancel()
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            stopAllSpeech()
            tts?.shutdown()
        }
    }
}
