package com.jupiterlabs.ghostdrive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jupiterlabs.ghostdrive.data.*
import com.jupiterlabs.ghostdrive.tracking.*
import com.jupiterlabs.ghostdrive.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.io.InputStream
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import com.jupiterlabs.ghostdrive.protocol.NullvalonProtocol
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val app get() = application as GhostApp
    private val repo get() = app.repository
    private var mapStatus by mutableStateOf("")
    private var fileBusy by mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<DiscoveredDevice>()
    private var isScanningDevices by mutableStateOf(false)
    private var deviceScanCallback: ScanCallback? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) arm()
        else notify("Grant precise location and nearby-device permissions to arm monitoring.")
    }
    private val bleScanPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) startBleDeviceScan()
        else notify("Allow Nearby devices to scan for Nullvalon.")
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            repo.saveSettings(repo.settings.value.copy(heyMerlinEnabled = true))
            repo.voice.commander.syncArmedState(true)
            repo.voice.speak("Hey Merlin voice wake-word armed, Senpai!", force = true)
            notify("🎙️ \"Hey Merlin\" wake-word armed!")
        } else {
            repo.saveSettings(repo.settings.value.copy(heyMerlinEnabled = false))
            repo.voice.commander.syncArmedState(false)
            notify("Allow Microphone permission to use \"Hey Merlin\" hands-free wake-word.")
        }
    }
    private fun toggleHeyMerlin() {
        val next = !repo.settings.value.heyMerlinEnabled
        if (next) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                repo.saveSettings(repo.settings.value.copy(heyMerlinEnabled = true))
                repo.voice.commander.syncArmedState(true)
                repo.voice.speak("Hey Merlin voice wake-word armed, Senpai!", force = true)
                notify("🎙️ \"Hey Merlin\" wake-word armed!")
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            repo.saveSettings(repo.settings.value.copy(heyMerlinEnabled = false))
            repo.voice.commander.syncArmedState(false)
            notify("⚪ \"Hey Merlin\" wake-word disarmed.")
        }
    }
    private val mapPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) lifecycleScope.launch {
        if(fileBusy) return@launch
        fileBusy = true; mapStatus = "Importing offline map…"
        try {
            val path = withContext(Dispatchers.IO) {
                val target = File(filesDir, "map-${System.currentTimeMillis()}.map")
                try {
                    contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                    MapFile(target).close(); target.absolutePath
                } catch(e: Exception) { target.delete(); throw e }
            }
            repo.saveSettings(repo.settings.value.copy(mapPath = path)); mapStatus = "Offline map ready"; notify(mapStatus)
        } catch(e: Exception) { mapStatus = "Import failed. Select a valid Mapsforge .map file."; notify(mapStatus) }
        finally { fileBusy = false }
    } }
    private val placesPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) lifecycleScope.launch {
        try {
            val count = withContext(Dispatchers.IO) {
                val text = contentResolver.openInputStream(uri)!!.use { readBounded(it, 16 * 1024 * 1024).toString(Charsets.UTF_8) }
                val places = parsePlaces(text)
                require(places.size <= 20_000) { "Import at most 20,000 local place references at once." }
                places.forEach { repo.db.savePlace(it) }; places.size
            }
            repo.refresh(); repo.log("INFO", "Places", "Imported $count local place references."); notify("Imported $count places")
        } catch(e: Exception) { notify(e.message ?: "Place import failed") }
    } }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) lifecycleScope.launch {
        try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(repo.db.export(repo.settings.value).toString(2)) } }; notify("Private JSON export saved") }
        catch(e: Exception) { notify("Export could not be saved") }
    } }
    private val entropyBinPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if(uri != null) lifecycleScope.launch {
        try {
            val bytes = withContext(Dispatchers.IO) {
                val data = repo.db.exportEntropyBytes()
                require(data.isNotEmpty()) { "Entropy vault is empty" }
                contentResolver.openOutputStream(uri)!!.use { it.write(data) }
                data.size
            }
            notify("Exported $bytes bytes (${bytes / 64} SHA3-512 blocks) of conditioned entropy")
        } catch(e: Exception) { notify(e.message ?: "Entropy export could not be saved") }
    } }
    private val importArchivePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) lifecycleScope.launch {
        try {
            val (tripsCount, placesCount) = withContext(Dispatchers.IO) {
                val text = contentResolver.openInputStream(uri)!!.use { readBounded(it, 64 * 1024 * 1024).toString(Charsets.UTF_8) }
                val rootJson = JSONObject(text)
                val res = repo.db.importArchive(rootJson)
                rootJson.optJSONObject("settings")?.let { sJson ->
                    repo.applySyncedSettings(repo.settings.value.mergeFromSyncJson(sJson))
                }
                res
            }
            repo.refresh(); repo.log("INFO", "Archive", "Merged $tripsCount trip(s) and $placesCount place(s) from archive.")
            notify("Merged $tripsCount trip(s) and $placesCount place(s)")
        } catch(e: Exception) { notify(e.message ?: "Archive import failed") }
    } }
    private val gpxPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> if(uri != null) lifecycleScope.launch {
        try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.bufferedWriter().use { out ->
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"Ghost Drive\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
            repo.db.trips(100_000).filter { !it.demo }.forEach { t ->
                out.write("<trk><name>${xml(t.startName + " to " + t.endName)}</name><trkseg>")
                var previous: Fix? = null
                repo.db.route(t.id).forEach { f ->
                    previous?.let { if(f.elapsedMs - it.elapsedMs > 30_000 || f.elapsedMs < it.elapsedMs) out.write("</trkseg><trkseg>") }
                    out.write("<trkpt lat=\"${f.latitude}\" lon=\"${f.longitude}\"><time>${Instant.ofEpochMilli(f.timeMs)}</time></trkpt>"); previous = f
                }
                out.write("</trkseg></trk>")
            }; out.write("</gpx>")
        } }; notify("GPX routes exported") } catch(e: Exception) { notify("GPX export could not be saved") }
    } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (intent?.getBooleanExtra("sync_now", false) == true) syncNow()
        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle()
            val live by repo.live.collectAsStateWithLifecycle()
            val trips by repo.trips.collectAsStateWithLifecycle()
            val places by repo.places.collectAsStateWithLifecycle()
            val logs by repo.logs.collectAsStateWithLifecycle()
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var selectedTrip by rememberSaveable { mutableStateOf<String?>(null) }
            var isMapFullscreen by rememberSaveable { mutableStateOf(false) }
            val cmdStatus by repo.voice.commander.status.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                repo.voice.commander.uiActions.collect { action ->
                    when (action) {
                        is VoiceUiAction.OpenCockpitMap -> {
                            tab = 1
                            isMapFullscreen = true
                        }
                        is VoiceUiAction.ExitCockpitMap -> {
                            isMapFullscreen = false
                        }
                        is VoiceUiAction.SwitchTab -> {
                            isMapFullscreen = false
                            tab = action.tabIndex
                        }
                        is VoiceUiAction.TriggerSync -> syncNow()
                        is VoiceUiAction.StartSimulator -> app.demo.start()
                        is VoiceUiAction.StopMonitoring -> stopMonitoring()
                    }
                }
            }
            LaunchedEffect(settings.keepScreenOn, isMapFullscreen) {
                if(settings.keepScreenOn || isMapFullscreen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            GhostTheme(settings.theme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        if (!(tab == 1 && isMapFullscreen)) {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        MerlinAvatarBadge(showMerlin = settings.showMerlin, active = live.authenticated || live.watch.connected || live.demo, size = 34.dp)
                                        Column {
                                            Text("Ghost Drive")
                                            Text(
                                                if (cmdStatus.listening) "🎙️ ${cmdStatus.statusLabel}"
                                                else if (live.demo) "SIMULATOR · no GPS or uploads"
                                                else if (live.watch.connected) "Nullvalon + Merlin · Watch 5 Linked"
                                                else "Nullvalon + Merlin",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (cmdStatus.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    FilterChip(
                                        selected = settings.heyMerlinEnabled,
                                        onClick = ::toggleHeyMerlin,
                                        label = { Text(if (settings.heyMerlinEnabled) "🎙️ Hey Merlin: ON" else "🎙️ Hey Merlin") }
                                    )
                                    IconButton(onClick = { tab = 1; isMapFullscreen = true }) { Icon(Icons.Outlined.Fullscreen, "Fullscreen Cockpit Map") }
                                    IconButton(onClick = { tab = 5 }) { Icon(Icons.Outlined.Tune, "Settings") }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                            )
                        }
                    },
                    bottomBar = {
                        if (!(tab == 1 && isMapFullscreen)) {
                            val labels = listOf("Main", "Maps", "Trips", "Entropy", "Logs", "Settings")
                            val icons: List<ImageVector> = listOf(Icons.Outlined.Dashboard, Icons.Outlined.Map, Icons.Outlined.Route, Icons.Outlined.Insights, Icons.AutoMirrored.Outlined.ReceiptLong, Icons.Outlined.Settings)
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) { labels.forEachIndexed { index, label ->
                                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], label) }, label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                            } }
                        }
                    }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when(tab) {
                            0 -> HomeScreen(repo, live, settings, trips, ::startMonitoring, ::stopMonitoring, { app.demo.start() }, ::syncNow,
                                openFullscreenMap = { tab = 1; isMapFullscreen = true }, onToggleHeyMerlin = ::toggleHeyMerlin) { tab = it }
                            1 -> MapsScreen(repo, live, trips, places, settings, selectedTrip, { selectedTrip = it },
                                { mapPicker.launch(arrayOf("*/*")) }, ::downloadMap, { f -> openExternalMap(f) }, ::notify,
                                isFullscreen = isMapFullscreen, onSetFullscreen = { isMapFullscreen = it },
                                onStartMonitoring = ::startMonitoring, onStopMonitoring = ::stopMonitoring, onStartDemo = { app.demo.start() })
                            2 -> HistoryScreen(trips, settings, places,
                                onFullscreenTrip = { selectedTrip = it.id; tab = 1; isMapFullscreen = true }) { selectedTrip = it.id; tab = 1; isMapFullscreen = false }
                            3 -> EntropyScreen(
                                repo = repo,
                                settings = settings,
                                live = live,
                                exportEntropyBin = { entropyBinPicker.launch("nullvalon-entropy.bin") },
                                clearEntropyVault = { repo.db.clearEntropyVault(); repo.refresh(); notify("Entropy vault cleared") },
                                stageWatchOvertimeSample = {
                                    val blk = repo.harvestOvertimeWatchSample()
                                    syncNow()
                                    notify("Conditioned & queued Galaxy Watch 5 block (HR ${blk.hrBpm?.toInt()} BPM · IBI ${blk.ibiMs} ms)")
                                }
                            )
                            4 -> LogsScreen(logs)
                            5 -> SettingsScreen(repo, settings, { mapPicker.launch(arrayOf("*/*")) }, ::downloadMap,
                                { placesPicker.launch(arrayOf("*/*")) }, { exportPicker.launch("ghost-drive-export.json") },
                                { importArchivePicker.launch(arrayOf("application/json", "*/*")) }, ::shareArchiveJson, { gpxPicker.launch("ghost-drive-routes.gpx") },
                                { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                                { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                                { if(live.armed) startService(Intent(this@MainActivity, DriveService::class.java).setAction(DriveService.RECONNECT)) else notify("Arm monitoring first") },
                                { if(repo.live.value.demo) app.demo.stop(); repo.db.deleteDemo(); repo.refresh(); repo.logs.value = repo.db.logs(); notify("Simulator trips removed") },
                                ::pullFromSyncServer, discoveredDevices, isScanningDevices, ::startBleDeviceScan, mapStatus, ::notify)
                        }
                    }
                }
            }
        }
    }
    private fun requiredPermissions() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    private fun startMonitoring() {
        if(repo.live.value.demo) app.demo.stop()
        val request = requiredPermissions() + if(Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        if(requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) arm()
        else permissions.launch(request.toTypedArray())
    }
    private fun arm() { ContextCompat.startForegroundService(this, Intent(this, DriveService::class.java).setAction(DriveService.START)) }
    private fun stopMonitoring() { if(repo.live.value.demo) app.demo.stop() else startService(Intent(this, DriveService::class.java).setAction(DriveService.STOP)) }
    private fun syncNow() {
        lifecycleScope.launch {
            repo.db.resetRetries()
            app.sync.flush()
            notify(app.sync.pullFromServer())
        }
    }
    private fun pullFromSyncServer() { lifecycleScope.launch { notify(app.sync.pullFromServer()) } }
    private fun shareArchiveJson() {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    val file = File(dir, "ghost-drive-archive.json")
                    file.writeText(repo.db.export(repo.settings.value).toString(2), Charsets.UTF_8)
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Quick Share Ghost Drive archive"))
            } catch(e: Exception) {
                notify("Could not prepare archive share: ${e.message}")
            }
        }
    }
    private fun notify(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun openExternalMap(f: Fix) {
        val uri = Uri.parse("geo:${f.latitude},${f.longitude}?q=${f.latitude},${f.longitude}")
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open recorded position")) }.onFailure { notify("Install a map app to open this position") }
    }
    private fun startBleDeviceScan() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bleScanPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if(isScanningDevices) return
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if(scanner == null || bluetoothManager.adapter?.isEnabled != true) {
            notify("Bluetooth is off or unavailable")
            return
        }
        discoveredDevices.clear()
        isScanningDevices = true
        notify("Scanning for nearby BLE devices (10s)…")
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if(ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    stopBleDeviceScan(); notify("Nearby devices permission was removed"); return
                }
                val dev = result.device
                val name = dev.name ?: result.scanRecord?.deviceName ?: "Unnamed Device"
                val addr = dev.address
                val rssi = result.rssi
                val isNullvalon = name.contains("NULLVALON", ignoreCase = true) ||
                    result.scanRecord?.serviceUuids?.any { it.uuid.toString().equals(NullvalonProtocol.SERVICE, ignoreCase = true) } == true
                val existing = discoveredDevices.indexOfFirst { it.address == addr }
                val item = DiscoveredDevice(name, addr, rssi, isNullvalon)
                if(existing >= 0) discoveredDevices[existing] = item
                else discoveredDevices.add(item)
            }
            override fun onScanFailed(errorCode: Int) {
                isScanningDevices = false
                deviceScanCallback = null
                notify("Scan failed (error $errorCode)")
            }
        }
        try {
            deviceScanCallback = scanCallback
            scanner.startScan(emptyList(), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            lifecycleScope.launch {
                delay(10_000)
                stopBleDeviceScan()
            }
        } catch(e: Exception) {
            isScanningDevices = false
            deviceScanCallback = null
            notify("Could not start scan: ${e.message}")
        }
    }
    private fun stopBleDeviceScan() {
        val callback = deviceScanCallback ?: run { isScanningDevices = false; return }
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        deviceScanCallback = null
        isScanningDevices = false
    }
    override fun onDestroy() {
        stopBleDeviceScan()
        super.onDestroy()
    }
    private fun downloadMap() {
        if(fileBusy) { notify("Map transfer already in progress"); return }
        fileBusy = true; mapStatus = "Downloading regional map…"
        lifecycleScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val uri = URI(repo.settings.value.offlineMapUrl)
                    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Use an HTTPS regional map URL." }
                    val target = File(filesDir, "map-${System.currentTimeMillis()}.map")
                    val connection = uri.toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                    connection.setRequestProperty("User-Agent", "GhostDrive/0.1 offline-map-download")
                    try {
                        require(connection.responseCode == 200) { "Map server returned HTTP ${connection.responseCode}." }
                        require(connection.contentLengthLong <= 1_000_000_000L) { "Map is too large; use a smaller region." }
                        connection.inputStream.use { input -> target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024); var total = 0L; var n: Int
                            while(input.read(buf).also { n = it } >= 0) {
                                total += n; require(total <= 1_000_000_000) { "Map exceeded size limit." }; out.write(buf, 0, n)
                                if(total % (1024 * 1024) < buf.size) withContext(Dispatchers.Main) { mapStatus = "Downloaded ${total / (1024 * 1024)} MiB…" }
                            }
                        } }; MapFile(target).close(); target.absolutePath
                    } catch(e: Exception) { target.delete(); throw e } finally { connection.disconnect() }
                }
                repo.saveSettings(repo.settings.value.copy(mapPath = path)); mapStatus = "Offline map ready"; notify(mapStatus)
            } catch(e: Exception) { mapStatus = e.message ?: "Map download failed. Import a .map file instead."; notify(mapStatus) }
            finally { fileBusy = false }
        }
    }
    private fun parsePlaces(text: String): List<Place> {
        if(text.trimStart().startsWith("{")) {
            val features = JSONObject(text).getJSONArray("features")
            return (0 until features.length()).mapNotNull { i ->
                val f = features.getJSONObject(i); val g = f.optJSONObject("geometry") ?: return@mapNotNull null
                if(g.optString("type") != "Point") return@mapNotNull null
                val c = g.getJSONArray("coordinates"); val props = f.optJSONObject("properties") ?: JSONObject()
                val name = props.optString("name").ifBlank { props.optString("label") }
                if(name.isBlank()) return@mapNotNull null
                validatedPlace(Place(id = props.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, name = name,
                    latitude = c.getDouble(1), longitude = c.getDouble(0), radiusM = props.optDouble("radius_m", 80.0), userDefined = props.optBoolean("user_defined", false)))
            }
        }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull()?.lowercase()?.startsWith("name,latitude,longitude") == true) { "CSV header: name,latitude,longitude,radius_m" }
        return lines.drop(1).map { line ->
            val columns = line.split(','); require(columns.size >= 3 && !columns[0].contains('"')) { "Use GeoJSON for names containing commas or quotes." }
            validatedPlace(Place(name = columns[0].trim(), latitude = columns[1].trim().toDouble(), longitude = columns[2].trim().toDouble(), radiusM = columns.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 80.0, userDefined = false))
        }
    }
    private fun validatedPlace(p: Place): Place { require(p.name.isNotBlank() && p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0 && p.radiusM in 10.0..5000.0) { "Invalid place coordinates / radius." }; return p }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size() + n <= limit) { "Selected file exceeds the import size limit." }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
