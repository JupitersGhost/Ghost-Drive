@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.jupiterlabs.ghostdrive.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jupiterlabs.ghostdrive.data.*
import com.jupiterlabs.ghostdrive.tracking.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MapsScreen(
    repo: Repository,
    live: LiveState,
    trips: List<Trip>,
    places: List<Place>,
    settings: Settings,
    selectedId: String?,
    select: (String?) -> Unit,
    import: () -> Unit,
    download: () -> Unit,
    external: (Fix) -> Unit,
    notify: (String) -> Unit,
    isFullscreen: Boolean = false,
    onSetFullscreen: (Boolean) -> Unit = {},
    onStartMonitoring: () -> Unit = {},
    onStopMonitoring: () -> Unit = {},
    onStartDemo: () -> Unit = {}
) {
    var route by remember { mutableStateOf<List<Fix>>(emptyList()) }
    var follow by remember { mutableStateOf(true) }
    var useMerlinAvatar by remember(settings.showMerlin) { mutableStateOf(settings.showMerlin) }
    var dialog by remember { mutableStateOf<Place?>(null) }
    var placeSearch by remember { mutableStateOf("") }

    // Route replay state
    var isReplaying by remember { mutableStateOf(false) }
    var replayIndex by remember { mutableIntStateOf(0) }
    var replaySpeedMultiplier by remember { mutableIntStateOf(1) }

    val effectiveMapFilter = resolveMapFilter(settings.mapStyle, settings.theme)
    val routeColorMode = settings.routeColorMode

    val dayKeyFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
    val recentDayKeys = remember(trips) {
        trips.filter { !it.demo }.map { dayKeyFormat.format(java.util.Date(it.startMs)) }.distinct().take(6)
    }
    val isDaySelection = selectedId?.startsWith("day:") == true
    val selectedDayKey = if (isDaySelection) selectedId?.removePrefix("day:") else null
    val selectedDayTrips = remember(trips, selectedDayKey) {
        if (selectedDayKey == null) emptyList()
        else trips.filter { !it.demo && dayKeyFormat.format(java.util.Date(it.startMs)) == selectedDayKey }.sortedBy { it.startMs }
    }
    val compositeDayTrip = remember(selectedDayTrips, selectedDayKey) {
        if (selectedDayKey != null && selectedDayTrips.isNotEmpty()) {
            val first = selectedDayTrips.first()
            val last = selectedDayTrips.last()
            Trip(
                id = "day:$selectedDayKey",
                startMs = first.startMs,
                endMs = last.endMs ?: last.startMs,
                durationMs = selectedDayTrips.sumOf { it.durationMs },
                movingMs = selectedDayTrips.sumOf { it.movingMs },
                distanceM = selectedDayTrips.sumOf { it.distanceM },
                start = first.start,
                end = last.end ?: first.end,
                startName = "📅 $selectedDayKey (${selectedDayTrips.size} legs): ${first.startName}",
                endName = last.endName,
                endReason = "Full Day Replay · ${selectedDayTrips.size} journeys",
                bootId = first.bootId,
                parkingBeforeMs = selectedDayTrips.drop(1).sumOf { it.parkingBeforeMs ?: 0L },
                demo = false,
                stationaryMs = selectedDayTrips.sumOf { it.stationaryMs },
                maxSpeedMps = selectedDayTrips.maxOfOrNull { it.maxSpeedMps } ?: 0f,
                vehicle = first.vehicle
            )
        } else null
    }

    val selected = compositeDayTrip ?: trips.find { it.id == selectedId }
    val fallbackTrip = live.trip ?: trips.firstOrNull { !it.demo && it.endMs != null } ?: trips.firstOrNull()
    val activeTrip = selected ?: fallbackTrip
    val currentId = activeTrip?.id
    val isLiveSession = (activeTrip?.endMs == null && (live.gpsActive || live.demo)) || isReplaying

    val visitClusters = remember(trips, places) {
        buildVisitClusters(trips, places.filter { it.userDefined })
    }
    val mapPlacesWithVisits = remember(visitClusters) {
        visitClusters.map { vc ->
            Place(
                id = vc.id,
                name = if (vc.visits > 0) "${vc.name} · ${vc.visits}x" else vc.name,
                latitude = vc.latitude,
                longitude = vc.longitude,
                radiusM = vc.radiusM,
                userDefined = vc.savedPlace != null
            )
        }
    }

    LaunchedEffect(currentId, activeTrip?.endMs, trips.size) {
        if (currentId == null) {
            route = emptyList()
            isReplaying = false
            replayIndex = 0
        } else if (currentId.startsWith("day:")) {
            val dayPts = withContext(Dispatchers.IO) {
                selectedDayTrips.flatMap { t -> repo.db.route(t.id) }
            }
            route = dayPts
            replayIndex = 0
            isReplaying = dayPts.size >= 2
        } else {
            val pts = withContext(Dispatchers.IO) { repo.db.route(currentId) }
            val lastDbElapsed = pts.lastOrNull()?.elapsedMs ?: Long.MIN_VALUE
            val inMemTail = if (currentId == live.trip?.id) {
                route.filter { it.elapsedMs > lastDbElapsed }
            } else emptyList()
            val basePts = if (inMemTail.isNotEmpty()) pts + inMemTail else pts
            val latestLive = live.lastFix?.takeIf { currentId == live.trip?.id }
            val merged = if (latestLive != null && (basePts.isEmpty() || basePts.last().elapsedMs < latestLive.elapsedMs)) {
                basePts + latestLive
            } else basePts
            route = merged
            isReplaying = false
            replayIndex = (merged.size - 1).coerceAtLeast(0)
        }
    }

    // High-performance streaming: incrementally append live fixes to route in memory without re-querying SQLite
    LaunchedEffect(live.lastFix) {
        val fix = live.lastFix
        if (fix != null && currentId != null && currentId == live.trip?.id) {
            if (route.isEmpty() || route.last().elapsedMs != fix.elapsedMs) {
                route = route + fix
                if (!isReplaying) {
                    replayIndex = (route.size - 1).coerceAtLeast(0)
                }
            }
        }
    }

    LaunchedEffect(isReplaying, route.size, replaySpeedMultiplier) {
        if (isReplaying && route.size >= 2) {
            if (replayIndex >= route.size - 1) replayIndex = 0
            while (isReplaying && replayIndex < route.size - 1) {
                delay((450L / replaySpeedMultiplier).coerceAtLeast(90L))
                replayIndex = (replayIndex + 1).coerceAtMost(route.size - 1)
            }
            if (replayIndex >= route.size - 1) isReplaying = false
        }
    }

    val activeFix: Fix? = when {
        isReplaying && route.isNotEmpty() -> route[replayIndex.coerceIn(0, route.size - 1)]
        !isReplaying && selectedId == null && live.lastFix != null -> live.lastFix
        route.isNotEmpty() && replayIndex in 0 until route.size - 1 -> route[replayIndex]
        route.isNotEmpty() -> route.last()
        selectedId == null -> live.lastFix
        else -> selected?.end
    }

    fun cycleColorMode() {
        val next = when (routeColorMode) {
            "speed" -> "entropy"
            "entropy" -> "rf"
            "rf" -> "solid"
            else -> "speed"
        }
        repo.saveSettings(settings.copy(routeColorMode = next))
    }

    fun cycleMapStyle() {
        val next = when (effectiveMapFilter) {
            "night" -> "matrix"
            "matrix" -> "day"
            else -> "night"
        }
        repo.saveSettings(settings.copy(mapStyle = next))
    }

    if (isFullscreen) {
        BackHandler { onSetFullscreen(false) }
        FullscreenNavigationCockpit(
            repo = repo,
            live = live,
            settings = settings,
            activeTrip = activeTrip,
            trips = trips,
            route = route,
            places = mapPlacesWithVisits,
            activeFix = activeFix,
            selectedId = selectedId,
            select = select,
            isLiveSession = isLiveSession,
            follow = follow,
            onToggleFollow = { follow = !follow },
            routeColorMode = routeColorMode,
            onCycleColorMode = ::cycleColorMode,
            mapFilter = effectiveMapFilter,
            onCycleMapStyle = ::cycleMapStyle,
            useMerlinAvatar = useMerlinAvatar,
            onToggleMerlinAvatar = {
                useMerlinAvatar = !useMerlinAvatar
                notify(if (useMerlinAvatar) "Merlin co-pilot avatar active" else "3D navigation arrow active")
            },
            onToggleVoice = {
                val next = !settings.voiceEnabled
                repo.saveSettings(settings.copy(voiceEnabled = next))
                if (next) repo.voice.speak("Merlin voice co-pilot online.", force = true)
            },
            isReplaying = isReplaying,
            onToggleReplay = {
                if (route.size < 2) {
                    notify("Record or select a route with at least 2 points to replay")
                } else {
                    if (!isReplaying && replayIndex >= route.size - 1) replayIndex = 0
                    isReplaying = !isReplaying
                }
            },
            replayIndex = replayIndex,
            onSeekReplay = { idx ->
                isReplaying = false
                replayIndex = idx.coerceIn(0, (route.size - 1).coerceAtLeast(0))
            },
            replaySpeedMultiplier = replaySpeedMultiplier,
            onCycleReplaySpeed = {
                replaySpeedMultiplier = when (replaySpeedMultiplier) {
                    1 -> 2
                    2 -> 4
                    else -> 1
                }
            },
            onSavePin = { a, b -> dialog = Place(name = "", latitude = a, longitude = b) },
            onExitFullscreen = { onSetFullscreen(false) },
            onStartMonitoring = {
                select(null)
                isReplaying = false
                follow = true
                onStartMonitoring()
            },
            onStopMonitoring = onStopMonitoring,
            onStartDemo = {
                select(null)
                isReplaying = false
                follow = true
                onStartDemo()
            }
        )
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Panel(
                    title = "Navigation & Cockpit Map",
                    subtitle = if (activeTrip != null) "${activeTrip.startName} → ${activeTrip.endName}" else "Recorded car positions · long-press map to save a base"
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onSetFullscreen(true) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Launch Fullscreen Cockpit Map", fontWeight = FontWeight.Bold)
                        }
                        MerlinAvatarBadge(
                            showMerlin = useMerlinAvatar,
                            active = live.authenticated || live.demo,
                            size = 42.dp,
                            onClick = {
                                useMerlinAvatar = !useMerlinAvatar
                                notify(if (useMerlinAvatar) "Merlin map avatar enabled" else "Navigation arrow avatar enabled")
                            }
                        )
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selectedId == null, { select(null); isReplaying = false }, label = { Text("Latest / live") })
                        recentDayKeys.firstOrNull()?.let { latestDay ->
                            FilterChip(
                                selected = selectedId == "day:$latestDay",
                                onClick = { select("day:$latestDay") },
                                label = { Text("📅 Replay Day ($latestDay)") }
                            )
                        }
                        FilterChip(follow, { follow = !follow }, label = { Text("Follow Merlin") })
                        FilterChip(useMerlinAvatar, { useMerlinAvatar = !useMerlinAvatar }, label = { Text("Merlin avatar") })
                        FilterChip(
                            selected = settings.voiceEnabled,
                            onClick = {
                                val next = !settings.voiceEnabled
                                repo.saveSettings(settings.copy(voiceEnabled = next))
                                if (next) repo.voice.speak("Merlin voice co-pilot online.", force = true)
                            },
                            label = { Text(if (settings.voiceEnabled) "Voice ON" else "Voice Muted") }
                        )
                    }

                    Text("Route overlay & map vision mode:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(routeColorMode == "speed", { repo.saveSettings(settings.copy(routeColorMode = "speed")) }, label = { Text("Speed Tiers") })
                        FilterChip(routeColorMode == "entropy", { repo.saveSettings(settings.copy(routeColorMode = "entropy")) }, label = { Text("Entropy Heat") })
                        FilterChip(routeColorMode == "rf", { repo.saveSettings(settings.copy(routeColorMode = "rf")) }, label = { Text("RF / Radar") })
                        FilterChip(effectiveMapFilter == "night", { repo.saveSettings(settings.copy(mapStyle = "night")) }, label = { Text("Night Map") })
                        FilterChip(effectiveMapFilter == "matrix", { repo.saveSettings(settings.copy(mapStyle = "matrix")) }, label = { Text("Matrix Green") })
                        FilterChip(effectiveMapFilter == "day", { repo.saveSettings(settings.copy(mapStyle = "day")) }, label = { Text("Day Map") })
                    }

                    if (settings.mapPath.isBlank()) {
                        Text(
                            "Using OpenStreetMap online & cached tiles. You can also import a regional .map file for 100% offline use.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = import) { Text("Import .map") }
                            TextButton(onClick = download) { Text("Download region") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Offline file: ${java.io.File(settings.mapPath).name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { repo.saveSettings(settings.copy(mapPath = "")) }) { Text("Switch to OSM") }
                            TextButton(onClick = import) { Text("Change .map") }
                        }
                    }

                    OfflineMap(
                        path = settings.mapPath,
                        route = route,
                        places = mapPlacesWithVisits,
                        last = activeFix,
                        selection = currentId ?: "none",
                        center = follow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .clip(RoundedCornerShape(22.dp)),
                        routeColorMode = routeColorMode,
                        mapFilter = effectiveMapFilter,
                        displayUnits = settings.displayUnits,
                        useMerlinAvatar = useMerlinAvatar,
                        isFullscreen = false,
                        isLiveSession = isLiveSession,
                        wifiMotion = live.wifiMotion,
                        radar = live.radar,
                        legendBottomPadding = 10.dp,
                        onToggleFullscreen = { onSetFullscreen(true) }
                    ) { a, b -> dialog = Place(name = "", latitude = a, longitude = b) }

                    // Interactive Route Replay & Scrubber bar when route has >= 2 points
                    if (route.size >= 2) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalIconButton(
                                            onClick = {
                                                if (!isReplaying && replayIndex >= route.size - 1) replayIndex = 0
                                                isReplaying = !isReplaying
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                if (isReplaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                contentDescription = if (isReplaying) "Pause Replay" else "Replay Route"
                                            )
                                        }
                                        Column {
                                            Text("Route Replay · Merlin Co-Pilot", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            Text(
                                                "Point ${replayIndex + 1} of ${route.size} · ${timestamp(activeFix?.timeMs)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    AssistChip(
                                        onClick = {
                                            replaySpeedMultiplier = when (replaySpeedMultiplier) {
                                                1 -> 2
                                                2 -> 4
                                                else -> 1
                                            }
                                        },
                                        label = { Text("${replaySpeedMultiplier}x") }
                                    )
                                }
                                Slider(
                                    value = replayIndex.toFloat(),
                                    onValueChange = {
                                        isReplaying = false
                                        replayIndex = it.toInt().coerceIn(0, route.size - 1)
                                    },
                                    valueRange = 0f..(route.size - 1).toFloat()
                                )
                            }
                        }
                    }

                    Text("Map data © OpenStreetMap contributors · Mapsforge rendering", style = MaterialTheme.typography.labelSmall)
                    activeFix?.let { f ->
                        ValueLine("Recorded coordinates", Geo.coordinate(f))
                        ValueLine("Speed / heading", "${speed(f.speedMps, settings.displayUnits)} · ${Geo.cardinal(f.bearingDeg)}")
                        f.entropyShannon?.let { ValueLine("Point entropy / RF score", "${number(it)} b/B · RF ${f.rfScore ?: "—"}") }
                        ValueLine("Fix time / accuracy", "${timestamp(f.timeMs)} · ±${f.accuracyM.toInt()} m")
                        Row {
                            TextButton(onClick = { dialog = Place(name = "", latitude = f.latitude, longitude = f.longitude) }) { Text("Save endpoint as a base") }
                            TextButton(onClick = { external(f) }) { Text("Open external map app") }
                        }
                    }
                }
            }
            item {
                Panel("Home bases and past visits", "Saved bases and automatically clustered stops (within 150 m) across your recorded trips.") {
                    Button(onClick = { dialog = Place(name = "", latitude = activeFix?.latitude ?: 44.481, longitude = activeFix?.longitude ?: -88.064) }) {
                        Text("Add a place / home base")
                    }
                    OutlinedTextField(placeSearch, { placeSearch = it }, label = { Text("Find saved bases or visited stops") }, modifier = Modifier.fillMaxWidth())
                }
            }
            items(visitClusters.filter { placeSearch.isBlank() || it.name.contains(placeSearch, true) }.take(30), key = { it.id }) { vc ->
                Panel(
                    title = "${if (vc.savedPlace != null) "★ " else "📍 "}${vc.name}",
                    subtitle = "${vc.visits} arrival(s) · ${if (vc.savedPlace != null) "Saved Home Base" else "Auto-discovered stop"} · radius ${vc.radiusM.toInt()} m"
                ) {
                    ValueLine("Coordinates", "%.5f, %.5f".format(java.util.Locale.US, vc.latitude, vc.longitude))
                    if (vc.lastVisitMs != null) ValueLine("Last visited", timestamp(vc.lastVisitMs))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vc.lastTripId?.let { tid ->
                            TextButton(onClick = { select(tid); isReplaying = false }) { Text("View latest route") }
                        }
                        if (vc.savedPlace != null) {
                            TextButton(onClick = { dialog = vc.savedPlace }) { Text("Edit") }
                            TextButton(onClick = { repo.deletePlace(vc.savedPlace) }) { Text("Remove") }
                        } else {
                            TextButton(onClick = {
                                dialog = Place(name = "", latitude = vc.latitude, longitude = vc.longitude, radiusM = 150.0, userDefined = true)
                            }) { Text("Save as Home Base") }
                        }
                    }
                }
            }
        }
    }

    dialog?.let { p ->
        PlaceDialog(p, { dialog = null }) { updated ->
            repo.savePlace(updated)
            dialog = null
            notify("Saved ${updated.name}")
        }
    }
}

private data class VisitClusterItem(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Double,
    val visits: Int,
    val lastVisitMs: Long?,
    val lastTripId: String?,
    val savedPlace: Place?
)

private fun buildVisitClusters(trips: List<Trip>, savedPlaces: List<Place>): List<VisitClusterItem> {
    data class MutableCluster(
        val id: String,
        var name: String,
        val latitude: Double,
        val longitude: Double,
        val radiusM: Double,
        var visits: Int = 0,
        var lastVisitMs: Long? = null,
        var lastTripId: String? = null,
        val savedPlace: Place? = null
    )
    val clusters = savedPlaces.map { p ->
        MutableCluster(
            id = "saved-${p.id}",
            name = p.name,
            latitude = p.latitude,
            longitude = p.longitude,
            radiusM = p.radiusM,
            savedPlace = p
        )
    }.toMutableList()

    var autoIndex = 1
    for (t in trips.filter { !it.demo }.sortedBy { it.startMs }) {
        val endFix = t.end ?: continue
        val match = clusters.minByOrNull { c -> Geo.distance(endFix.latitude, endFix.longitude, c.latitude, c.longitude) }
            ?.takeIf { c -> Geo.distance(endFix.latitude, endFix.longitude, c.latitude, c.longitude) <= maxOf(c.radiusM, 160.0) }
        if (match != null) {
            match.visits += 1
            match.lastVisitMs = t.endMs ?: t.startMs
            match.lastTripId = t.id
        } else {
            val stopLabel = t.endName.takeIf { !it.contains(",") && it.isNotBlank() } ?: "Stop #${autoIndex++}"
            clusters.add(
                MutableCluster(
                    id = "auto-${t.id}",
                    name = stopLabel,
                    latitude = endFix.latitude,
                    longitude = endFix.longitude,
                    radiusM = 150.0,
                    visits = 1,
                    lastVisitMs = t.endMs ?: t.startMs,
                    lastTripId = t.id,
                    savedPlace = null
                )
            )
        }
    }
    return clusters
        .sortedWith(compareByDescending<MutableCluster> { it.visits }.thenByDescending { it.savedPlace != null })
        .map { c ->
            VisitClusterItem(
                id = c.id,
                name = c.name,
                latitude = c.latitude,
                longitude = c.longitude,
                radiusM = c.radiusM,
                visits = c.visits,
                lastVisitMs = c.lastVisitMs,
                lastTripId = c.lastTripId,
                savedPlace = c.savedPlace
            )
        }
}

@Composable
private fun FullscreenNavigationCockpit(
    repo: Repository,
    live: LiveState,
    settings: Settings,
    activeTrip: Trip?,
    trips: List<Trip>,
    route: List<Fix>,
    places: List<Place>,
    activeFix: Fix?,
    selectedId: String?,
    select: (String?) -> Unit,
    isLiveSession: Boolean,
    follow: Boolean,
    onToggleFollow: () -> Unit,
    routeColorMode: String,
    onCycleColorMode: () -> Unit,
    mapFilter: String,
    onCycleMapStyle: () -> Unit,
    useMerlinAvatar: Boolean,
    onToggleMerlinAvatar: () -> Unit,
    onToggleVoice: () -> Unit,
    isReplaying: Boolean,
    onToggleReplay: () -> Unit,
    replayIndex: Int,
    onSeekReplay: (Int) -> Unit,
    replaySpeedMultiplier: Int,
    onCycleReplaySpeed: () -> Unit,
    onSavePin: (Double, Double) -> Unit,
    onExitFullscreen: () -> Unit,
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit,
    onStartDemo: () -> Unit
) {
    var showTripPicker by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val colorModeLabel = when (routeColorMode) {
        "entropy" -> "Overlay: Entropy"
        "rf" -> "Overlay: RF/Radar"
        "solid" -> "Overlay: Solid"
        else -> "Overlay: Speed"
    }
    val mapStyleLabel = when (mapFilter) {
        "matrix" -> "Map: Matrix"
        "night" -> "Map: Night"
        else -> "Map: Day"
    }

    if (isLandscape) {
        // LANDSCAPE / DASH-MOUNT SPLIT-SCREEN COCKPIT (62% Map Left / 38% Instrument Cluster Right)
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                OfflineMap(
                    path = settings.mapPath,
                    route = route,
                    places = places,
                    last = activeFix,
                    selection = activeTrip?.id ?: "none",
                    center = follow,
                    modifier = Modifier.fillMaxSize(),
                    routeColorMode = routeColorMode,
                    mapFilter = mapFilter,
                    displayUnits = settings.displayUnits,
                    useMerlinAvatar = useMerlinAvatar,
                    isFullscreen = true,
                    isLiveSession = isLiveSession,
                    wifiMotion = live.wifiMotion,
                    radar = live.radar,
                    legendBottomPadding = 12.dp,
                    onToggleFullscreen = onExitFullscreen,
                    onPin = onSavePin
                )
                // Top-left floating pill bar on landscape map
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(selected = follow, onClick = onToggleFollow, label = { Text("Follow", style = MaterialTheme.typography.labelSmall) })
                        FilterChip(
                            selected = settings.heyMerlinEnabled,
                            onClick = {
                                val next = !settings.heyMerlinEnabled
                                repo.saveSettings(settings.copy(heyMerlinEnabled = next))
                                repo.voice.commander.syncArmedState(next)
                            },
                            label = { Text(if (settings.heyMerlinEnabled) "🟢 Hey Merlin" else "⚪ Hey Merlin", style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = { repo.voice.commander.triggerDirectVoiceCommand() },
                            label = { Text("🎤 Speak", style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(onClick = onCycleColorMode, label = { Text(colorModeLabel, style = MaterialTheme.typography.labelSmall) })
                        AssistChip(onClick = onCycleMapStyle, label = { Text(mapStyleLabel, style = MaterialTheme.typography.labelSmall) })
                        FilterChip(selected = useMerlinAvatar, onClick = onToggleMerlinAvatar, label = { Text("Merlin", style = MaterialTheme.typography.labelSmall) })
                        if (trips.size > 1) {
                            AssistChip(onClick = { showTripPicker = true }, label = { Text("Trips", style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
            }

            // Right-Side Dedicated Instrument Cluster Dock
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                modifier = Modifier.weight(0.38f).fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                            MerlinAvatarBadge(
                                showMerlin = useMerlinAvatar,
                                active = live.authenticated || live.demo || isReplaying,
                                size = 42.dp,
                                onClick = onToggleMerlinAvatar
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        isReplaying -> "ROUTE REPLAY"
                                        live.demo -> "SIMULATOR HUD"
                                        live.gpsActive -> "LIVE NAVIGATION"
                                        else -> "WIDESCREEN COCKPIT"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = activeTrip?.let { "${it.startName} → ${it.endName}" } ?: settings.vehicle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = onToggleVoice) {
                            Icon(
                                if (settings.voiceEnabled) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                                contentDescription = "Toggle Voice Co-Pilot",
                                tint = if (settings.voiceEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalIconButton(onClick = onExitFullscreen, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit Fullscreen")
                        }
                    }

                    // Gauges Row: Speedometer + Compass
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpeedometerGauge(
                            speedMps = activeFix?.speedMps,
                            maxSpeedMps = activeTrip?.maxSpeedMps,
                            units = settings.displayUnits,
                            size = 132.dp,
                            compact = true
                        )
                        CompassDial(bearingDeg = activeFix?.bearingDeg, size = 76.dp)
                    }

                    // Replay Scrubber (if route >= 2 points)
                    if (route.size >= 2) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onToggleReplay, modifier = Modifier.size(30.dp)) {
                                    Icon(if (isReplaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Replay")
                                }
                                Slider(
                                    value = replayIndex.toFloat(),
                                    onValueChange = { onSeekReplay(it.toInt()) },
                                    valueRange = 0f..(route.size - 1).toFloat(),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onCycleReplaySpeed) { Text("${replaySpeedMultiplier}x") }
                            }
                        }
                    }

                    // Telemetry Grid
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ValueLine("Distance", distance(activeTrip?.distanceM ?: 0.0, settings.displayUnits))
                        ValueLine("Moving time", duration(activeTrip?.movingMs ?: 0L))
                        ValueLine("Peak / avg speed", "${speed(activeTrip?.maxSpeedMps, settings.displayUnits)} / ${speed(activeTrip?.avgMovingSpeedMps, settings.displayUnits)}")
                        ValueLine("Pool H / Vault", "${number(activeFix?.entropyShannon ?: live.pool?.shannon)} b/B · ${live.entropyVault.blocks} blk")
                    }

                    // Action Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = if (live.armed || live.demo) onStopMonitoring else onStartMonitoring,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (live.armed || live.demo) "Stop" else "Arm BLE")
                        }
                        if (!live.armed && !live.demo) {
                            OutlinedButton(onClick = onStartDemo) { Text("Sim") }
                        }
                        activeFix?.let { f ->
                            OutlinedButton(onClick = { onSavePin(f.latitude, f.longitude) }) { Text("+Base") }
                        }
                    }
                }
            }
        }
    } else {
        // PORTRAIT FULLSCREEN GOOGLE MAPS COCKPIT
        val savedPlacesOnly = remember(places) { places.filter { it.userDefined } }
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
            OfflineMap(
                path = settings.mapPath,
                route = route,
                places = savedPlacesOnly,
                last = activeFix,
                selection = activeTrip?.id ?: "none",
                center = follow,
                modifier = Modifier.fillMaxSize(),
                routeColorMode = routeColorMode,
                mapFilter = mapFilter,
                displayUnits = settings.displayUnits,
                useMerlinAvatar = useMerlinAvatar,
                isFullscreen = true,
                isLiveSession = isLiveSession,
                wifiMotion = live.wifiMotion,
                radar = live.radar,
                legendBottomPadding = if (route.size >= 2) 244.dp else 188.dp,
                onToggleFullscreen = onExitFullscreen,
                onPin = onSavePin
            )

            // Top Floating Google Maps–Style Navigation Header
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    tonalElevation = 8.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MerlinAvatarBadge(
                            showMerlin = useMerlinAvatar,
                            active = live.authenticated || live.demo || isReplaying,
                            size = 46.dp,
                            onClick = onToggleMerlinAvatar
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = when {
                                        isReplaying -> "ROUTE REPLAY"
                                        live.demo -> "SIMULATOR NAVIGATION"
                                        live.gpsActive -> "LIVE NAVIGATION"
                                        live.authenticated -> "ESP32 CONNECTED"
                                        else -> "COCKPIT MAP"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                activeFix?.let {
                                    Text(
                                        text = "· ±${it.accuracyM.toInt()}m",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = activeTrip?.let { "${it.startName} → ${it.endName}" } ?: "Merlin Co-Pilot · Ready on deck",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${activeTrip?.vehicle?.ifBlank { settings.vehicle } ?: settings.vehicle} · ${activeFix?.let { Geo.coordinate(it) } ?: "Waiting for GPS fix"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        CompassDial(bearingDeg = activeFix?.bearingDeg, size = 54.dp)
                        FilledTonalIconButton(
                            onClick = onExitFullscreen,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit Fullscreen")
                        }
                    }
                }

                // Floating Quick Control Pill Row (scrollable)
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = follow,
                            onClick = onToggleFollow,
                            label = { Text("Follow", style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = onCycleColorMode,
                            label = { Text(colorModeLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = onCycleMapStyle,
                            label = { Text(mapStyleLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = settings.voiceEnabled,
                            onClick = onToggleVoice,
                            label = { Text(if (settings.voiceEnabled) "🔊 Voice" else "🔇 Muted", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = settings.heyMerlinEnabled,
                            onClick = {
                                val next = !settings.heyMerlinEnabled
                                repo.saveSettings(settings.copy(heyMerlinEnabled = next))
                                repo.voice.commander.syncArmedState(next)
                            },
                            label = { Text(if (settings.heyMerlinEnabled) "🟢 Hey Merlin" else "⚪ Hey Merlin", style = MaterialTheme.typography.labelSmall) }
                        )
                        AssistChip(
                            onClick = { repo.voice.commander.triggerDirectVoiceCommand() },
                            label = { Text("🎤 Speak", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = useMerlinAvatar,
                            onClick = onToggleMerlinAvatar,
                            label = { Text("Merlin", style = MaterialTheme.typography.labelSmall) }
                        )
                        if (route.size >= 2) {
                            FilterChip(
                                selected = isReplaying,
                                onClick = onToggleReplay,
                                label = { Text(if (isReplaying) "Pause" else "Replay", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (trips.size > 1) {
                            AssistChip(
                                onClick = { showTripPicker = true },
                                label = { Text("Trips", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // Bottom Floating Instrument Cluster: Left Speedometer Pod + Right Telemetry & Replay Dock
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (route.size >= 2) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(onClick = onToggleReplay, modifier = Modifier.size(32.dp)) {
                                Icon(if (isReplaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause")
                            }
                            Slider(
                                value = replayIndex.toFloat(),
                                onValueChange = { onSeekReplay(it.toInt()) },
                                valueRange = 0f..(route.size - 1).toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCycleReplaySpeed) {
                                Text("${replaySpeedMultiplier}x", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, speedTierColor(activeFix?.speedMps).copy(alpha = 0.65f)),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            SpeedometerGauge(
                                speedMps = activeFix?.speedMps,
                                maxSpeedMps = activeTrip?.maxSpeedMps,
                                units = settings.displayUnits,
                                size = 116.dp,
                                compact = true
                            )
                            Text(
                                text = "MAX ${speed(activeTrip?.maxSpeedMps, settings.displayUnits)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF5B041),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                        tonalElevation = 8.dp,
                        shadowElevation = 10.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("DISTANCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        distance(activeTrip?.distanceM ?: 0.0, settings.displayUnits),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("MOVING TIME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        duration(activeTrip?.movingMs ?: 0L),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "Avg: ${speed(activeTrip?.avgMovingSpeedMps, settings.displayUnits)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Pool H: ${number(activeFix?.entropyShannon ?: live.pool?.shannon)} b/B",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = if (live.armed || live.demo) onStopMonitoring else onStartMonitoring,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(if (live.armed || live.demo) "Stop" else "Arm BLE", style = MaterialTheme.typography.labelMedium)
                                }
                                if (!live.armed && !live.demo) {
                                    OutlinedButton(
                                        onClick = onStartDemo,
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Sim", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                activeFix?.let { f ->
                                    OutlinedButton(
                                        onClick = { onSavePin(f.latitude, f.longitude) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("+Base", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTripPicker) {
        val pickerDayFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US) }
        val pickerDays = remember(trips) {
            trips.filter { !it.demo }.groupBy { pickerDayFormat.format(java.util.Date(it.startMs)) }.entries.take(7)
        }
        AlertDialog(
            onDismissRequest = { showTripPicker = false },
            title = { Text("Select journey or full day to replay") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 380.dp)) {
                    item {
                        Surface(
                            onClick = { select(null); showTripPicker = false },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedId == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Latest / Live Car Session", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    items(pickerDays.toList(), key = { "day:${it.key}" }) { (dayKey, dayTrips) ->
                        val dayId = "day:$dayKey"
                        Surface(
                            onClick = { select(dayId); showTripPicker = false },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedId == dayId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("📅 Full Day Replay · $dayKey (${dayTrips.size} legs)", fontWeight = FontWeight.ExtraBold)
                                Text(
                                    "${dayTrips.last().startName} → ${dayTrips.first().endName} · ${distance(dayTrips.sumOf { it.distanceM }, settings.displayUnits)}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    items(trips.take(40), key = { it.id }) { t ->
                        Surface(
                            onClick = { select(t.id); showTripPicker = false },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedId == t.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${if (t.demo) "DEMO · " else ""}${t.startName} → ${t.endName}", fontWeight = FontWeight.SemiBold)
                                Text("${timestamp(t.startMs)} · ${distance(t.distanceM, settings.displayUnits)}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTripPicker = false }) { Text("Close") }
            }
        )
    }
}

@Composable private fun PlaceDialog(place: Place, dismiss: () -> Unit, save: (Place) -> Unit) {
    var name by remember(place.id) { mutableStateOf(place.name) }; var latitude by remember(place.id) { mutableStateOf(place.latitude.toString()) }
    var longitude by remember(place.id) { mutableStateOf(place.longitude.toString()) }; var radius by remember(place.id) { mutableStateOf(place.radiusM.toInt().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Save a home base / place") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name · Home, Work, Garage…") }, singleLine = true)
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true)
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true)
            OutlinedTextField(radius, { radius = it }, label = { Text("Recognition area · meters") }, singleLine = true)
            Text("Use a radius large enough for parking areas, but small enough to keep neighboring places distinct.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val a = latitude.toDoubleOrNull(); val b = longitude.toDoubleOrNull(); val r = radius.toDoubleOrNull()
        if(name.isBlank() || a == null || b == null || r == null || a !in -90.0..90.0 || b !in -180.0..180.0 || r !in 10.0..5000.0) error = "Enter a name, valid coordinates, and a radius from 10 to 5000 m."
        else save(place.copy(name = name.trim(), latitude = a, longitude = b, radiusM = r, userDefined = true))
    }) { Text("Save place") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
