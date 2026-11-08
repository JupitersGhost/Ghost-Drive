package com.jupiterlabs.ghostdrive.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.protocol.*
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** GATT work is serialized on main looper. Temporary test mode uses a public zero key. */
@SuppressLint("MissingPermission")
class NullvalonClient(private val context: Context, private val repo: Repository,
    private val onAuthenticated: (Long) -> Unit, private val onLost: (String) -> Unit,
    private val onMessage: (Envelope) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private val assembler = FragmentAssembler()
    private var running = false; private var scanning = false; private var authenticated = false
    private var key: ByteArray? = null; private var provisionedKey: ByteArray? = null; private var usingProvisionedKey = false
    private var nonce: ByteArray? = null; private var boot: Long? = null
    private var challengeStarted = false; private var subscriptionReady = false; private var highestSequence = -1L
    private var lastTelemetryElapsed = 0L
    private var connectingAddress = ""
    private var lastProtocolWarning = 0L
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val timeout = Runnable { fail("Nullvalon connection/authentication timed out.") }
    private val scanAgain = Runnable { scan() }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            // 45s timeout to tolerate momentary Wi-Fi scan or starter voltage drop
            if (authenticated && SystemClock.elapsedRealtime() - lastTelemetryElapsed > 45_000) fail("Authenticated telemetry stopped; GPS paused.")
            handler.postDelayed(this, 5000)
        }
    }
    fun start() {
        if (running) return
        // Dual-Key Auto-Negotiation: supports both the car's current zero-key firmware (without reflashing)
        // and any provisioned 32-byte Keystore key if the ESP32 is upgraded later.
        key = ByteArray(32)
        provisionedKey = repo.vault.get("protocol_key")?.takeIf { it.size == 32 }
        usingProvisionedKey = false
        running = true
        handler.post(watchdog); scan()
    }
    fun stop() {
        running = false; handler.removeCallbacksAndMessages(null)
        stopScan(); close("Monitoring stopped"); key?.fill(0); key = null; provisionedKey?.fill(0); provisionedKey = null
    }
    private fun status(text: String) { repo.live.update { it.copy(bleStatus = text, authenticated = authenticated, bootId = boot) } }
    private fun scan() {
        if (!running || gatt != null || scanning) return
        if (adapter?.isEnabled != true) { status("Bluetooth is off"); handler.postDelayed(scanAgain, 10_000); return }
        val currentSettings = repo.settings.value
        val address = currentSettings.deviceAddress
        // Android ORs this filter list. Keep the saved address and vehicle fleet as preferences, while
        // still allowing a reflashed/replaced ESP32 to prove the shared app key.
        val filters = mutableListOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(NullvalonProtocol.SERVICE)).build(),
            ScanFilter.Builder().setDeviceName("NULLVALON").build()
        )
        currentSettings.vehicles.forEach { vp ->
            if (vp.address.isNotBlank()) runCatching { filters.add(0, ScanFilter.Builder().setDeviceAddress(vp.address).build()) }
        }
        if (address.isNotBlank()) runCatching { filters.add(0, ScanFilter.Builder().setDeviceAddress(address).build()) }
        try {
            adapter.bluetoothLeScanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanning = true; status("Waiting for your Nullvalon · GPS off")
            handler.postDelayed({ if (scanning) { stopScan(); handler.postDelayed(scanAgain, 5000) } }, 60_000)
        } catch(e: Exception) { status("Bluetooth scan unavailable"); repo.log("WARN", "Bluetooth", e.javaClass.simpleName); handler.postDelayed(scanAgain, 10_000) }
    }
    private fun stopScan() { if(scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }; scanning = false }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if(!running || gatt != null) return@post
            stopScan(); assembler.reset(); highestSequence = -1; boot = null; challengeStarted = false; subscriptionReady = false
            connectingAddress = result.device.address; status("Connecting to Nullvalon · GPS off")
            gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(timeout, 60_000)
        } }
        override fun onScanFailed(code: Int) { handler.post { scanning = false; status("BLE scan delayed (code $code)"); handler.postDelayed(scanAgain, 30_000) } }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) { handler.post {
            if (g !== gatt) return@post
            if(state == BluetoothProfile.STATE_DISCONNECTED) { fail("Nullvalon disconnected"); return@post }
            if(status != BluetoothGatt.GATT_SUCCESS) {
                fail("Nullvalon disconnected (status $status)"); return@post
            }
            if(state == BluetoothProfile.STATE_CONNECTED) { this@NullvalonClient.status("Discovering Nullvalon telemetry · GPS off"); g.discoverServices() }
        } }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) { handler.post {
            if (g !== gatt) return@post
            val service = g.getService(UUID.fromString(NullvalonProtocol.SERVICE))
            if(code != BluetoothGatt.GATT_SUCCESS || service == null ||
                listOf(NullvalonProtocol.TELEMETRY, NullvalonProtocol.CHALLENGE, NullvalonProtocol.PROOF).any { service.getCharacteristic(UUID.fromString(it)) == null }) {
                fail("Nullvalon service/schema not found"); return@post
            }
            status("Subscribing to Nullvalon · GPS off")
            subscribe()
        } }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int) { handler.post {
            if (g !== gatt || descriptor.uuid != cccd) return@post
            if(code != BluetoothGatt.GATT_SUCCESS) {
                fail("Notification subscription failed (Bluetooth code $code)")
                return@post
            }
            when(descriptor.characteristic.uuid.toString()) {
                NullvalonProtocol.PROOF -> writeNotificationDescriptor(g, NullvalonProtocol.TELEMETRY, "telemetry")
                NullvalonProtocol.TELEMETRY -> {
                    subscriptionReady = true
                    status("Checking Nullvalon test protocol · GPS off")
                    challengeIfReady()
                }
            }
        } }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handler.post {
            if(g !== gatt) return@post
            when(characteristic.uuid.toString()) {
                NullvalonProtocol.TELEMETRY -> receive(value)
                NullvalonProtocol.PROOF -> verifyProof(value)
            }
        } }
        @Suppress("DEPRECATION")
        @Deprecated("Legacy callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if(android.os.Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, characteristic, characteristic.value ?: return)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) { handler.post {
            if(g !== gatt || c.uuid.toString() != NullvalonProtocol.CHALLENGE) return@post
            if(code != BluetoothGatt.GATT_SUCCESS) { fail("Identity challenge write failed ($code)"); return@post }
            // Small pause ensures peripheral handles write and computes proof before explicit read fallback
            handler.postDelayed({
                if(gatt === g && !authenticated) {
                    val proof = g.getService(UUID.fromString(NullvalonProtocol.SERVICE))?.getCharacteristic(UUID.fromString(NullvalonProtocol.PROOF))
                    if(proof != null && !g.readCharacteristic(proof)) repo.log("WARN", "Bluetooth", "Direct proof read fallback failed; awaiting notification.")
                }
            }, 60)
        } }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, code: Int) { handler.post {
            if(g !== gatt || c.uuid.toString() != NullvalonProtocol.PROOF) return@post
            if(code != BluetoothGatt.GATT_SUCCESS) { fail("Identity proof read failed (code $code)"); return@post }
            verifyProof(value)
        } }
        @Suppress("DEPRECATION")
        @Deprecated("Legacy callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) {
            if(android.os.Build.VERSION.SDK_INT < 33) onCharacteristicRead(g, c, c.value ?: ByteArray(0), code)
        }
    }
    private fun verifyProof(value: ByteArray) {
        if(authenticated) return
        val n = nonce
        val b = boot
        if(n == null || b == null || value.size != 16) {
            fail("Nullvalon protocol check failed; GPS remains off"); return
        }
        val zeroKey = key ?: ByteArray(32)
        val provKey = provisionedKey
        val matchesProv = provKey != null && MessageDigest.isEqual(NullvalonProtocol.proof(provKey, n, b), value)
        val matchesZero = MessageDigest.isEqual(NullvalonProtocol.proof(zeroKey, n, b), value)
        if(!matchesProv && !matchesZero) {
            fail("Nullvalon protocol check failed; GPS remains off"); return
        }
        usingProvisionedKey = matchesProv
        authenticated = true; lastTelemetryElapsed = SystemClock.elapsedRealtime(); handler.removeCallbacks(timeout)
        val s = repo.settings.value
        val matchedVehicle = s.vehicles.find { it.address.equals(connectingAddress, ignoreCase = true) }
        val updatedVehicles = if (matchedVehicle == null && connectingAddress.isNotBlank()) {
            s.vehicles + com.jupiterlabs.ghostdrive.data.VehicleProfile(connectingAddress, s.vehicle, s.driver)
        } else s.vehicles
        val activeVehicle = matchedVehicle?.name ?: s.vehicle
        val activeDriver = matchedVehicle?.driver ?: s.driver
        if (s.deviceAddress != connectingAddress || s.vehicle != activeVehicle || s.driver != activeDriver || updatedVehicles != s.vehicles) {
            repo.saveSettings(s.copy(deviceAddress = connectingAddress, vehicle = activeVehicle, driver = activeDriver, vehicles = updatedVehicles))
        }
        val modeLabel = if (usingProvisionedKey) "Verified HMAC" else "Car Link Active"
        status("Nullvalon ($activeVehicle) · $modeLabel")
        repo.log("INFO", "Security", "Nullvalon ($activeVehicle) connected ($modeLabel); boot $b.")
        onAuthenticated(b)
    }
    private fun subscribe() {
        val g = gatt ?: return
        val service = g.getService(UUID.fromString(NullvalonProtocol.SERVICE)) ?: run { fail("Nullvalon service is unavailable"); return }
        val telemetry = service.getCharacteristic(UUID.fromString(NullvalonProtocol.TELEMETRY))
        val proof = service.getCharacteristic(UUID.fromString(NullvalonProtocol.PROOF))
        if(telemetry?.getDescriptor(cccd) == null || proof?.getDescriptor(cccd) == null ||
            !g.setCharacteristicNotification(telemetry, true) || !g.setCharacteristicNotification(proof, true)) {
            fail("Nullvalon notification characteristics are unavailable"); return
        }
        // Serialize descriptor writes: proof first, telemetry second. Only then send
        // the challenge, so the proof notification cannot race its CCCD subscription.
        writeNotificationDescriptor(g, NullvalonProtocol.PROOF, "identity proof")
    }
    @Suppress("DEPRECATION")
    private fun writeNotificationDescriptor(g: BluetoothGatt, characteristicUuid: String, label: String) {
        val descriptor = g.getService(UUID.fromString(NullvalonProtocol.SERVICE))
            ?.getCharacteristic(UUID.fromString(characteristicUuid))?.getDescriptor(cccd)
            ?: run { fail("$label notification descriptor is unavailable"); return }
        if(android.os.Build.VERSION.SDK_INT >= 33) {
            if(g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) fail("Could not subscribe to $label")
        } else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; if(!g.writeDescriptor(descriptor)) fail("Could not subscribe to $label") }
    }
    private fun receive(value: ByteArray) {
        try {
            val (seq, packet) = assembler.accept(value, SystemClock.elapsedRealtime()) ?: return
            val zeroKey = key ?: return
            val provKey = provisionedKey
            val env = if (usingProvisionedKey && provKey != null) {
                runCatching { NullvalonProtocol.verify(packet, seq, provKey) }
                    .getOrElse { NullvalonProtocol.verify(packet, seq, zeroKey) }
            } else if (provKey != null) {
                runCatching { NullvalonProtocol.verify(packet, seq, zeroKey) }
                    .getOrElse { NullvalonProtocol.verify(packet, seq, provKey) }
            } else {
                NullvalonProtocol.verify(packet, seq, zeroKey)
            }
            if(boot != null && boot != env.bootId) { fail("Nullvalon rebooted; reauthentication required"); return }
            if(seq <= highestSequence) return // no duplicate pool forwarding
            if(highestSequence >= 0 && seq > highestSequence + 1) {
                val missing = seq - highestSequence - 1
                repo.live.update { it.copy(gaps = it.gaps + missing) }
                repo.log("WARN", "Protocol", "$missing telemetry packet(s) missing; recorded as a gap.")
            }
            highestSequence = seq; boot = env.bootId
            challengeIfReady()
            if(authenticated) { lastTelemetryElapsed = SystemClock.elapsedRealtime(); onMessage(env) }
        } catch(e: Exception) {
            val now = SystemClock.elapsedRealtime()
            if(now - lastProtocolWarning > 5000) { repo.log("WARN", "Protocol", e.message ?: "Invalid packet rejected"); lastProtocolWarning = now }
        }
    }
    @Suppress("DEPRECATION")
    private fun challengeIfReady() {
        // Notifications can arrive before the CCCD-write callback. Do not overlap GATT operations.
        if(authenticated || challengeStarted || !subscriptionReady || boot == null) return
        val g = gatt ?: return
        val char = g.getService(UUID.fromString(NullvalonProtocol.SERVICE))?.getCharacteristic(UUID.fromString(NullvalonProtocol.CHALLENGE))
            ?: run { fail("Identity challenge characteristic is unavailable"); return }
        challengeStarted = true; nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        if(android.os.Build.VERSION.SDK_INT >= 33) {
            if(g.writeCharacteristic(char, nonce!!, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS) fail("Could not write identity challenge")
        } else { char.value = nonce; char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; if(!g.writeCharacteristic(char)) fail("Could not write identity challenge") }
    }
    private fun close(reason: String) {
        val wasConnected = authenticated; authenticated = false; challengeStarted = false; subscriptionReady = false; boot = null; nonce?.fill(0); nonce = null
        val old = gatt; gatt = null; runCatching { old?.disconnect(); old?.close() }; assembler.reset()
        status(reason); if(wasConnected) onLost(reason)
    }
    private fun fail(reason: String) {
        handler.removeCallbacks(timeout); close(reason); repo.log("WARN", "Bluetooth", reason)
        if(running) handler.postDelayed(scanAgain, 8000)
    }
}
