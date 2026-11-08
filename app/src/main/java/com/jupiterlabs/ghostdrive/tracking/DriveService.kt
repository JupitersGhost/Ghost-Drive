package com.jupiterlabs.ghostdrive.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jupiterlabs.ghostdrive.*
import com.jupiterlabs.ghostdrive.ble.NullvalonClient
import com.jupiterlabs.ghostdrive.ble.WatchBleClient
import com.jupiterlabs.ghostdrive.data.*
import com.jupiterlabs.ghostdrive.sync.PrivateSync
import com.jupiterlabs.ghostdrive.ui.distance
import com.jupiterlabs.ghostdrive.ui.duration
import com.jupiterlabs.ghostdrive.ui.number
import com.jupiterlabs.ghostdrive.ui.speed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class DriveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun io(block: () -> Unit) = repo.io(block)
    private lateinit var repo: Repository
    private lateinit var ble: NullvalonClient
    private lateinit var watchBle: WatchBleClient
    private lateinit var sync: PrivateSync
    private lateinit var locations: LocationManager
    private val engine = TripEngine()
    private var listening = false
    private var started = false
    private var lastLocationWarning = 0L
    private var lastHardwareGpsElapsedMs = 0L
    private var lastPlaceLookupElapsedMs = 0L
    private var appIcon: Bitmap? = null
    companion object {
        const val START = "com.jupiterlabs.ghostdrive.START"
        const val STOP = "com.jupiterlabs.ghostdrive.STOP"
        const val SYNC = "com.jupiterlabs.ghostdrive.SYNC"
        const val RECONNECT = "com.jupiterlabs.ghostdrive.RECONNECT"
    }
    override fun onCreate() {
        super.onCreate(); repo = (application as GhostApp).repository
        sync = (application as GhostApp).sync; locations = getSystemService(LocationManager::class.java)
        appIcon = runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.ghostdrive_launcher_foreground)?.let {
                Bitmap.createScaledBitmap(it, 192, 192, true)
            }
        }.getOrNull()
        ble = NullvalonClient(this, repo, ::beginSession, ::onBleLost) { env -> repo.record(env) }
        watchBle = WatchBleClient(this, repo, scope) { scope.launch { sync.flush() } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("monitor", "Ghost Drive monitoring", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == STOP) {
            endJob?.cancel(); endJob = null
            if (repo.settings.value.wasArmed) repo.saveSettings(repo.settings.value.copy(wasArmed = false))
            endSession("Monitoring stopped by you"); stopSelf(); return START_NOT_STICKY
        }
        if(!started) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(56, notification("Armed · GPS off until Nullvalon verifies"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(56, notification("Armed · GPS off until Nullvalon verifies"))
                }
            } catch(e: Exception) {
                repo.log("WARN", "Monitoring", "Android could not start monitoring: ${e.message}. Open Ghost Drive and grant permissions.")
                repo.live.update { it.copy(armed = false, gpsActive = false, bleStatus = "Open the app to resume monitoring") }
                stopSelf(); return START_NOT_STICKY
            }
            started = true
            if (!repo.settings.value.wasArmed) repo.saveSettings(repo.settings.value.copy(wasArmed = true))
            recoverInterruptedTrips()
            repo.live.update { it.copy(armed = true, demo = false, pool = null, poolGraph = emptyList(), sources = com.jupiterlabs.ghostdrive.protocol.SourceId.entries.associateWith { id -> SourceState(id) }) }
            ble.start(); watchBle.start(); repo.log("INFO", "Monitoring", "Monitoring armed. GPS starts only after Nullvalon identity verification; Galaxy Watch 5 overtime staging active.")
            scope.launch {
                var seconds = 0
                while(isActive) {
                    delay(1000); seconds++
                    val snap = engine.snapshot(SystemClock.elapsedRealtime())
                    repo.live.update { it.copy(nowMs = System.currentTimeMillis(), trip = snap) }
                    if(seconds % 5 == 0) io { val q = repo.db.queueSize(); repo.live.update { it.copy(queueCount = q) } }
                    if(seconds % repo.settings.value.syncIntervalSeconds == 0) scope.launch { sync.flush() }
                    if(seconds % 10 == 0 && engine.active != null && !listening && hasAnyLocationProvider()) startLocation()
                    if(seconds % 15 == 0 && listening) updateNotification()
                    if(seconds % 60 == 0) {
                        val s60 = engine.snapshot(SystemClock.elapsedRealtime()); val st = repo.settings.value
                        io { s60?.let { repo.db.saveTrip(it) }; repo.db.prune(st); repo.refresh() }
                        updateNotification()
                    }
                }
            }
            scope.launch {
                var previous = repo.settings.value
                repo.settings.collect { current ->
                    if(current.gpsEnabled != previous.gpsEnabled && repo.live.value.authenticated) {
                        if(current.gpsEnabled) beginSession(repo.live.value.bootId ?: 0) else { endJob?.cancel(); endJob = null; endSession("GPS disabled in Settings") }
                    } else if(current.gpsIntervalSeconds != previous.gpsIntervalSeconds && listening) { stopLocation(); startLocation() }
                    if (current.watchEnabled != previous.watchEnabled ||
                        current.watchStandbyBatchMode != previous.watchStandbyBatchMode ||
                        current.watchBatchIntervalSec != previous.watchBatchIntervalSec) {
                        val wConn = repo.live.value.watch.connected
                        val modeDesc = when {
                            !current.watchEnabled -> "Watch harvester paused from phone"
                            repo.live.value.authenticated -> "Nullvalon live · ${current.watchBatchIntervalSec}s batches"
                            current.watchStandbyBatchMode -> "Standby ${current.watchBatchIntervalSec}s batch mode active"
                            else -> "Nullvalon-only mode · 0% watch drain while parked"
                        }
                        repo.updateWatchStatus(modeDesc, wConn)
                        scope.launch(Dispatchers.IO) { watchBle.pushConfigToWatch() }
                    }
                    if (current.phoneEntropyEnabled != previous.phoneEntropyEnabled) {
                        if (current.phoneEntropyEnabled && repo.live.value.authenticated) {
                            repo.phoneHarvester.start()
                        } else {
                            repo.phoneHarvester.stop()
                        }
                    }
                    previous = current
                }
            }
        }
        if(intent?.action == SYNC) scope.launch { repo.db.resetRetries(); sync.flush(forcePostDrive = true) }
        if(intent?.action == RECONNECT) { ble.stop(); ble.start(); watchBle.stop(); watchBle.start() }
        return START_STICKY
    }
    private fun recoverInterruptedTrips() {
        val stale = repo.trips.value.filter { it.endMs == null && !it.demo }
        io {
            stale.forEach { t ->
                val closed = t.copy(endMs = t.end?.timeMs ?: t.startMs, endReason = "Android service interrupted; last saved fix", endName = t.end?.let { t.endName } ?: "No reliable GPS fix")
                repo.db.saveTrip(closed); repo.queue("trip_ended", closed.json())
            }
            repo.refresh()
        }
    }
    private var endJob: Job? = null
    private fun onBleLost(reason: String) {
        stopLocation()
        repo.phoneHarvester.stop()
        repo.live.update { it.copy(gpsActive = false) }
        scope.launch(Dispatchers.IO) { watchBle.pushConfigToWatch() }
        endJob?.cancel()
        endJob = scope.launch {
            repo.log("INFO", "Trips", "Nullvalon link interrupted ($reason). Waiting 30s before concluding session.")
            delay(30_000)
            endSession(reason)
        }
    }
    private fun beginSession(boot: Long) {
        endJob?.cancel()
        endJob = null
        if (repo.settings.value.phoneEntropyEnabled) {
            repo.phoneHarvester.start()
        }
        scope.launch(Dispatchers.IO) { watchBle.pushConfigToWatch() }
        if(engine.active != null) {
            repo.log("INFO", "Trips", "Reconnected to Nullvalon; continuing active session.")
            startLocation()
            updateNotification()
            return
        }
        if(!repo.settings.value.gpsEnabled) return
        val t = engine.start(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot, repo.trips.value.firstOrNull { !it.demo && it.endMs != null }, vehicle = repo.settings.value.vehicle)
        repo.live.update { it.copy(trip = t, lastFix = null) }
        io { repo.db.saveTrip(t); repo.queue("trip_started", t.json()); repo.refresh() }
        startLocation()
        repo.voice.onSessionStart(repo.settings.value.vehicle, isSimulator = false)
        repo.log("INFO", "Trips", "Car session started; waiting for a fresh GPS fix."); updateNotification()
    }
    private fun hasAnyLocationProvider(): Boolean {
        val hasGps = runCatching { locations.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val hasFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { locations.allProviders.contains(LocationManager.FUSED_PROVIDER) && locations.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false)
        val hasNetwork = runCatching { locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        return hasGps || hasFused || hasNetwork
    }
    private fun startLocation() {
        if(listening || !repo.live.value.authenticated || !repo.settings.value.gpsEnabled) return
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            repo.log("WARN", "GPS", "Precise location permission is required. Tracking remains paused."); return
        }
        try {
            val hasGps = locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    locations.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                    locations.isProviderEnabled(LocationManager.FUSED_PROVIDER)
            val hasNetwork = locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if(!hasGps && !hasFused && !hasNetwork) {
                repo.log("WARN", "GPS", "Phone location is switched off; turn it on to record routes."); return
            }

            val configuredMs = (repo.settings.value.gpsIntervalSeconds * 1000L).coerceIn(500L, 60_000L)
            // Sub-second (500 ms / 2 Hz) hardware GNSS streaming when set to 1s for Google Maps-grade responsiveness.
            val intervalMs = if (configuredMs <= 1000L) 500L else configuredMs
            val minIntervalMs = if (configuredMs <= 1000L) 200L else (configuredMs / 2)
            val request = LocationRequest.Builder(intervalMs)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .setMinUpdateDistanceMeters(0f)
                .setMaxUpdateDelayMillis(0L)
                .build()

            // Elevate foreground service type to include LOCATION now that session is active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    startForeground(56, notification("Recording journey · high-rate GNSS live"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                }
            }

            // Priority 1: Hardware GNSS (GPS_PROVIDER) for true Doppler speed, carrier bearing, and 2-4m road accuracy.
            // Priority 2: FUSED_PROVIDER registered alongside only as an indoor/garage warm-up fallback when GNSS is waiting for lock.
            var registered = false
            if (hasGps) {
                try {
                    locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, mainExecutor, listener)
                    registered = true
                } catch (e: Exception) {
                    repo.log("WARN", "GPS", "GPS provider request failed (${e.message}).")
                }
            }
            if (hasFused) {
                try {
                    locations.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, mainExecutor, listener)
                    registered = true
                } catch (e: Exception) {
                    if (!registered) repo.log("WARN", "GPS", "Fused location provider request failed (${e.message}).")
                }
            }
            if (!registered && hasNetwork) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, request, mainExecutor, listener)
                registered = true
            }

            if (!registered) {
                repo.log("WARN", "GPS", "No location providers could be registered.")
                return
            }

            listening = true; repo.live.update { it.copy(gpsActive = true) }

            // Prime position immediately with the freshest accurate fix so the map locks in 0 ms without cold-start delay.
            val candidates = listOfNotNull(
                if (hasGps) runCatching { locations.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull() else null,
                if (hasFused) runCatching { locations.getLastKnownLocation(LocationManager.FUSED_PROVIDER) }.getOrNull() else null,
                if (hasNetwork) runCatching { locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull() else null
            ).filter { System.currentTimeMillis() - it.time in -5_000L..60_000L }
            val bestWarm = candidates.minByOrNull { loc ->
                val agePenalty = ((System.currentTimeMillis() - loc.time).coerceAtLeast(0L) / 2000f)
                (if (loc.hasAccuracy()) loc.accuracy else 50f) + agePenalty
            }
            if (bestWarm != null) {
                val primed = Location(bestWarm).apply {
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                listener.onLocationChanged(primed)
            }
        } catch(e: Exception) { repo.log("WARN", "GPS", "Location collection unavailable: ${e.javaClass.simpleName}") }
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if(!repo.live.value.authenticated || !listening) return
            if(location.isMock) {
                if(SystemClock.elapsedRealtime() - lastLocationWarning > 30_000) { repo.log("WARN", "GPS", "Mock GPS fix rejected. Use the built-in simulator for testing."); lastLocationWarning = SystemClock.elapsedRealtime() }
                return
            }
            val nowElapsed = SystemClock.elapsedRealtime()
            val acc = if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY
            val isHardwareGps = location.provider == LocationManager.GPS_PROVIDER
            if (isHardwareGps && acc <= 45f) {
                lastHardwareGpsElapsedMs = nowElapsed
            } else if (!isHardwareGps) {
                // Suppress coarse/laggy Fused/Wi-Fi/Cell fixes whenever hardware GNSS is actively locked.
                if (nowElapsed - lastHardwareGpsElapsedMs < 3_500L) return
                val prevAcc = repo.live.value.lastFix?.accuracyM
                if (prevAcc != null && prevAcc <= 15f && acc > 25f) return
            }
            val currentLive = repo.live.value
            val watchSnap = currentLive.watch
            val currentEntropy = currentLive.pool?.shannon
                ?: currentLive.sources.values.mapNotNull { it.report?.conditioned?.shannon ?: it.report?.raw?.shannon }.maxOrNull()
                ?: watchSnap.shannon
            val rawElapsedMs = location.elapsedRealtimeNanos / 1_000_000
            val clampedElapsedMs = if (rawElapsedMs in (engine.startedElapsed - 2_000L)..engine.startedElapsed) engine.startedElapsed else rawElapsedMs
            val spd = if (location.hasSpeed()) location.speed else null
            // Ignore static 0.0° bearing noise when stationary/crawling so TripEngine uses course-over-ground or holds last heading.
            val brg = if (location.hasBearing() && (spd == null || spd >= 0.8f) && !(spd != null && spd < 1.5f && location.bearing == 0.0f)) location.bearing else null
            val fix = Fix(location.latitude, location.longitude, location.time, clampedElapsedMs,
                acc,
                spd,
                brg,
                entropyShannon = currentEntropy,
                rfScore = currentLive.wifiMotion?.score,
                radarEnergy = currentLive.radar?.movingEnergy,
                hrBpm = watchSnap.hrBpm,
                ibiMs = watchSnap.ibiMs,
                spo2Pct = watchSnap.spo2Pct,
                wristG = watchSnap.wristG,
                watchSteps = watchSnap.steps.takeIf { it > 0 })
            val s = repo.settings.value
            val t = engine.accept(fix, nowElapsed, repo.places.value, s.maxAccuracyM, s.movingThresholdMps) ?: run {
                // Even if accuracy is still converging above maxAccuracyM on the very first fix, show position on map.
                if (currentLive.lastFix == null && acc <= 150f) {
                    repo.live.update { it.copy(lastFix = fix) }
                }
                return
            }
            val savedFix = t.end ?: fix
            // Publish to the UI first so the map reacts this frame; persist and queue on the database thread.
            repo.live.update { it.copy(trip = t, lastFix = savedFix) }
            repo.voice.onFixMatchedPlace(Geo.match(savedFix, repo.places.value))
            io {
                repo.db.saveTrip(t); repo.db.addFix(t.id, savedFix)
                val posPayload = JSONObject().put("trip_id", t.id).put("fix", savedFix.json()).put("trip", t.json())
                if (watchSnap.lastWatchDigestHex.isNotBlank()) {
                    posPayload.put("watch_digest_hex", watchSnap.lastWatchDigestHex)
                }
                repo.queue("position", posPayload)
            }
            val isStartFix = t.start == savedFix
            if ((isStartFix || nowElapsed - lastPlaceLookupElapsedMs >= 15_000L) &&
                Geo.match(savedFix, repo.places.value) == null && s.mapPath.isNotBlank()) {
                lastPlaceLookupElapsedMs = nowElapsed
                val tripId = t.id
                scope.launch {
                    val label = withContext(Dispatchers.IO) { OfflinePlaceLookup.near(savedFix, s.mapPath) }
                    if (label != null && engine.active?.id == tripId) {
                        val named = engine.updateNames(
                            startName = if (isStartFix) label else null,
                            endName = label
                        )
                        if (named != null) {
                            repo.live.update { it.copy(trip = named) }
                            io { repo.db.saveTrip(named) }
                        }
                        if (isStartFix) repo.log("INFO", "Places", "Session began $label (local map candidate).")
                    }
                }
            }
        }
        override fun onProviderEnabled(provider: String) {
            if(engine.active != null) {
                if(listening) repo.live.update { it.copy(gpsActive = true) } else startLocation()
                updateNotification()
            }
        }
        override fun onProviderDisabled(provider: String) { repo.log("WARN", "GPS", "Location provider disabled. No route points are being recorded."); repo.live.update { it.copy(gpsActive = false) } }
    }
    private fun stopLocation() {
        if(listening) runCatching { locations.removeUpdates(listener) }
        listening = false
        repo.live.update { it.copy(gpsActive = false) }
        if (started && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(56, notification("Armed · GPS standby"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        }
    }
    private fun endSession(reason: String) {
        stopLocation()
        repo.phoneHarvester.stop()
        val closed = engine.stop(System.currentTimeMillis(), SystemClock.elapsedRealtime(), reason)
        if(closed != null) {
            val pullAfter = repo.settings.value.autoSyncArchive && repo.settings.value.syncPullEndpoint.isNotBlank()
            io {
                repo.db.saveTrip(closed)
                repo.db.enqueue("history", repo.event("trip_ended", closed.json()))
                repo.refresh()
                scope.launch {
                    sync.flush(forcePostDrive = true)
                    if (pullAfter) sync.pullFromServer(pushFirst = true)
                }
            }
            repo.voice.onSessionStop(closed.endName, closed.distanceM, repo.settings.value.displayUnits, isSimulator = false)
            repo.log("INFO", "Trips", "Session ended: ${closed.startName} → ${closed.endName}. $reason.")
            if((closed.end != null && Geo.match(closed.end, repo.places.value) == null) ||
                (closed.start != null && Geo.match(closed.start, repo.places.value) == null)) scope.launch {
                val endName = if(closed.end != null && Geo.match(closed.end, repo.places.value) == null) {
                    withContext(Dispatchers.IO) { OfflinePlaceLookup.near(closed.end, repo.settings.value.mapPath) }
                } else null
                val startName = if(closed.start != null && Geo.match(closed.start, repo.places.value) == null) {
                    withContext(Dispatchers.IO) { OfflinePlaceLookup.near(closed.start, repo.settings.value.mapPath) }
                } else null
                if(endName != null || startName != null) {
                    val named = closed.copy(startName = startName ?: closed.startName, endName = endName ?: closed.endName)
                    io {
                        repo.db.saveTrip(named)
                        repo.db.enqueue("history", repo.event("trip_updated", named.json()))
                        repo.refresh()
                        scope.launch { sync.flush(forcePostDrive = true) }
                    }
                }
            }
        }
        repo.live.update { it.copy(trip = null, lastFix = closed?.end ?: it.lastFix) }
        if(started) updateNotification()
    }
    private fun notification(text: String, detail: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "monitor").setSmallIcon(R.mipmap.ic_launcher).setLargeIcon(appIcon).setContentTitle("Ghost Drive · ${repo.settings.value.vehicle}")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Stop monitoring", stop)
        if (detail != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        return builder.build()
    }
    private fun updateNotification() {
        val l = repo.live.value
        val s = repo.settings.value
        val t = l.trip
        if (listening && t != null) {
            val summary = "${distance(t.distanceM, s.displayUnits)} · ${duration(t.durationMs)} · ${speed(l.lastFix?.speedMps, s.displayUnits)} · ${t.endName}"
            val poolText = l.pool?.let { "Pool win #${it.windowId} (${number(it.shannon)} b/B)" } ?: "Pool collecting"
            val expanded = "$summary\n$poolText · Vault: ${l.entropyVault.blocks} blk · Queue: ${l.queueCount}"
            getSystemService(NotificationManager::class.java).notify(56, notification(summary, expanded))
        } else {
            getSystemService(NotificationManager::class.java).notify(56, notification("Armed · ${l.bleStatus}"))
        }
    }
    override fun onDestroy() {
        endJob?.cancel(); endJob = null
        endSession("Monitoring service stopped"); ble.stop(); watchBle.stop(); repo.phoneHarvester.stop(); scope.cancel(); started = false
        repo.live.update { it.copy(armed = false, authenticated = false, gpsActive = false, bleStatus = "Monitoring stopped") }
        super.onDestroy()
    }
}
