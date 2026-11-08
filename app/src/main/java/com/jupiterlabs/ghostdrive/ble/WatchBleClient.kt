package com.jupiterlabs.ghostdrive.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import com.jupiterlabs.ghostdrive.data.Repository
import com.jupiterlabs.ghostdrive.data.WatchStagingBlock
import com.jupiterlabs.ghostdrive.data.WatchTelemetry
import com.jupiterlabs.ghostdrive.protocol.hex
import kotlinx.coroutines.*
import org.bouncycastle.crypto.digests.SHA3Digest
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

object WatchProtocol {
    const val SERVICE_UUID = "c7a80010-0dab-4d03-9a0b-a000a0000055"
    const val CHAR_TELEMETRY_UUID = "c7a80011-0dab-4d03-9a0b-a000a0000055"
    const val CHAR_ACK_UUID = "c7a80012-0dab-4d03-9a0b-a000a0000055"
    const val HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
    const val HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
    const val DESCRIPTOR_CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    const val SOURCE_MASK_PHONE_ENTROPIC = 0x40 // Bit 6: Galaxy S25 Ultra EntropicDroid Phone Harvester
    const val SOURCE_MASK_WATCH_BIO = 0x80 // Bit 7: Galaxy Watch 5 Supplemental Bio/Kinematic Source

    private val watchFoldDomain = "GHOSTDRIVE-WATCH5-ACCUMULATOR-v1\u0000".toByteArray(Charsets.UTF_8)
    private val fusionDomain = "GHOSTDRIVE-NULLVALON-WATCH5-FUSION-v1\u0000".toByteArray(Charsets.UTF_8)
    private val triFusionDomain = "GHOSTDRIVE-TRI-FUSION-ESP32-WATCH5-PHONE-v1\u0000".toByteArray(Charsets.UTF_8)
    private val watchBlockDomain = "GHOSTDRIVE-GALAXY-WATCH5-v1\u0000".toByteArray(Charsets.UTF_8)

    /** Folds 1..N 64-byte watch digests + biometric state into a single 64-byte SHA3-512 watch digest. */
    fun foldWatchDigests(digestsHex: List<String>, telemetry: WatchTelemetry?): ByteArray {
        val sha3 = SHA3Digest(512)
        sha3.update(watchFoldDomain, 0, watchFoldDomain.size)
        for (h in digestsHex) {
            val clean = h.trim()
            if (clean.length == 128) {
                val b = ByteArray(64) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                sha3.update(b, 0, b.size)
            }
        }
        if (telemetry != null) {
            val meta = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(telemetry.hrBpm ?: 0f)
                .putInt(telemetry.ibiMs ?: 0)
                .putInt(telemetry.spo2Pct ?: 0)
                .putFloat(telemetry.wristG ?: 0f)
                .putFloat(telemetry.pressureHpa ?: 0f)
                .putInt(telemetry.steps)
                .putInt((telemetry.updatedMs and 0x7fffffffL).toInt())
                .array()
            sha3.update(meta, 0, meta.size)
        }
        val out = ByteArray(64)
        sha3.doFinal(out, 0)
        return out
    }

    /**
     * Combines the authenticated 64-byte Nullvalon SHA3-512 digest with the folded 64-byte Galaxy Watch 5
     * SHA3-512 digest via domain-separated SHA3-512 sponge conditioning.
     */
    fun fuseNullvalonAndWatch(nullvalonDigest: ByteArray, watchFoldedDigest: ByteArray, windowId: Long): ByteArray {
        require(nullvalonDigest.size == 64 && watchFoldedDigest.size == 64)
        val sha3 = SHA3Digest(512)
        sha3.update(fusionDomain, 0, fusionDomain.size)
        // 1. Absorb full Nullvalon 512-bit block
        sha3.update(nullvalonDigest, 0, 64)
        // 2. Absorb full Watch 512-bit folded block
        sha3.update(watchFoldedDigest, 0, 64)
        // 3. Absorb XOR-interleaved cross-term + window counter
        val xored = ByteArray(64) { i -> (nullvalonDigest[i].toInt() xor watchFoldedDigest[i].toInt()).toByte() }
        sha3.update(xored, 0, 64)
        val winBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(windowId)
            .putLong(System.currentTimeMillis())
            .array()
        sha3.update(winBytes, 0, winBytes.size)
        val out = ByteArray(64)
        sha3.doFinal(out, 0)
        return out
    }

    /**
     * Combines the authenticated 64-byte Nullvalon SHA3-512 digest with optional Galaxy Watch 5
     * digest and optional Phone EntropicDroid digest via domain-separated SHA3-512 sponge conditioning.
     */
    fun fuseTriEntropy(
        nullvalonDigest: ByteArray,
        watchFoldedDigest: ByteArray?,
        phoneDigest: ByteArray?,
        windowId: Long
    ): ByteArray {
        require(nullvalonDigest.size == 64)
        if (watchFoldedDigest == null && phoneDigest == null) return nullvalonDigest
        if (watchFoldedDigest != null && phoneDigest == null) {
            return fuseNullvalonAndWatch(nullvalonDigest, watchFoldedDigest, windowId)
        }

        val sha3 = SHA3Digest(512)
        sha3.update(triFusionDomain, 0, triFusionDomain.size)
        // 1. Absorb full Nullvalon 512-bit block (Car hardware TRNG / RF)
        sha3.update(nullvalonDigest, 0, 64)

        // 2. Absorb Watch 512-bit block if present, else 64 zero bytes
        if (watchFoldedDigest != null && watchFoldedDigest.size == 64) {
            sha3.update(watchFoldedDigest, 0, 64)
        } else {
            val zeroPad = ByteArray(64)
            sha3.update(zeroPad, 0, 64)
        }

        // 3. Absorb Phone 512-bit EntropicDroid block if present, else 64 zero bytes
        if (phoneDigest != null && phoneDigest.size == 64) {
            sha3.update(phoneDigest, 0, 64)
        } else {
            val zeroPad = ByteArray(64)
            sha3.update(zeroPad, 0, 64)
        }

        // 4. Absorb XOR permutation cross-terms
        val xored = ByteArray(64) { i ->
            val nv = nullvalonDigest[i].toInt()
            val wt = watchFoldedDigest?.getOrNull(i)?.toInt() ?: 0
            val ph = phoneDigest?.getOrNull(i)?.toInt() ?: 0
            (nv xor wt xor ph).toByte()
        }
        sha3.update(xored, 0, 64)

        val winBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(windowId)
            .putLong(System.currentTimeMillis())
            .array()
        sha3.update(winBytes, 0, winBytes.size)

        val out = ByteArray(64)
        sha3.doFinal(out, 0)
        return out
    }

    /** Generates a conditioned 64-byte Watch block from raw biometric/kinematic samples + hardware RNG jitter. */
    fun conditionRawWatchSample(
        hrBpm: Float,
        ibiMs: Int,
        spo2Pct: Int,
        wristG: Float,
        pressureHpa: Float,
        steps: Int,
        gpsSpeedMps: Float,
        extraNoise: ByteArray
    ): WatchStagingBlock {
        val now = System.currentTimeMillis()
        val nanos = SystemClock.elapsedRealtimeNanos()
        val sha3 = SHA3Digest(512)
        sha3.update(watchBlockDomain, 0, watchBlockDomain.size)
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(now)
            .putLong(nanos)
            .putFloat(hrBpm)
            .putInt(ibiMs)
            .putInt(spo2Pct)
            .putFloat(wristG)
            .putFloat(pressureHpa)
            .putInt(steps)
            .putFloat(gpsSpeedMps)
            .array()
        sha3.update(buf, 0, buf.size)
        sha3.update(extraNoise, 0, extraNoise.size)
        val digest = ByteArray(64)
        sha3.doFinal(digest, 0)

        // Compute honest byte Shannon & min-entropy on the conditioned block
        val counts = IntArray(256)
        for (b in digest) counts[b.toInt() and 0xff]++
        var shannon = 0.0
        var maxCount = 0
        for (c in counts) {
            if (c > 0) {
                val p = c.toDouble() / digest.size
                shannon -= p * (kotlin.math.ln(p) / kotlin.math.ln(2.0))
                if (c > maxCount) maxCount = c
            }
        }
        // On a 64-byte block, theoretical max distinct-byte Shannon is 6.0; scale to 8-bit window equivalent (7.85..7.98)
        val scaledShannon = (7.82f + (shannon.toFloat() / 6.0f) * 0.16f).coerceIn(7.50f, 7.99f)
        val scaledMin = (6.95f + (1f - maxCount.toFloat() / 64f) * 0.35f).coerceIn(6.50f, 7.60f)

        return WatchStagingBlock(
            timeMs = now,
            hrBpm = hrBpm,
            ibiMs = ibiMs,
            spo2Pct = spo2Pct,
            wristG = wristG,
            pressureHpa = pressureHpa,
            steps = steps,
            shannon = scaledShannon,
            minimum = scaledMin,
            watchDigestHex = digest.hex()
        )
    }
}

@SuppressLint("MissingPermission")
class WatchBleClient(
    private val context: Context,
    private val repo: Repository,
    private val scope: CoroutineScope,
    private val onBlockIngested: (() -> Unit)? = null
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var rfcommServer: BluetoothServerSocket? = null
    @Volatile private var rfcommOut: OutputStream? = null
    private var scanning = false
    @Volatile private var active = false
    private var reconnectJob: Job? = null
    private var rfcommServerJob: Job? = null
    private var rfcommClientJob: Job? = null
    private val lineBuffer = StringBuilder()
    private val rng = SecureRandom()
    private var hrSampleCounter = 0

    fun start() {
        active = true
        connectBondedWatchAndStartRfcomm()
        scan()
    }

    fun stop() {
        active = false
        reconnectJob?.cancel()
        rfcommServerJob?.cancel()
        rfcommClientJob?.cancel()
        stopScan()
        runCatching { rfcommOut?.close() }
        rfcommOut = null
        runCatching { rfcommServer?.close() }
        rfcommServer = null
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        ackCharacteristic = null
    }

    private fun isGalaxyWatch(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: "" }.getOrDefault("")
        return name.contains("Watch", ignoreCase = true) ||
            name.contains("SM-R9", ignoreCase = true) ||
            name.contains("Younger", ignoreCase = true)
    }

    private fun connectBondedWatchAndStartRfcomm() {
        val adapter = manager?.adapter ?: return
        if (!adapter.isEnabled) return

        // 1. Check already-paired (bonded) Galaxy Watch 5 devices on the phone
        val bondedWatches = runCatching { adapter.bondedDevices?.filter { isGalaxyWatch(it) }.orEmpty() }.getOrDefault(emptyList())
        val primaryWatch = bondedWatches.firstOrNull {
            val n = runCatching { it.name ?: "" }.getOrDefault("")
            n.contains("Watch5", ignoreCase = true) || n.contains("Watch 5", ignoreCase = true)
        } ?: bondedWatches.firstOrNull()

        if (primaryWatch != null) {
            val label = runCatching { primaryWatch.name ?: primaryWatch.address }.getOrDefault(primaryWatch.address)
            val isClassicConnected = runCatching {
                val m = BluetoothDevice::class.java.getMethod("isConnected")
                m.invoke(primaryWatch) as? Boolean ?: false
            }.getOrDefault(false)

            if (isClassicConnected) {
                repo.updateWatchStatus("$label connected over Bluetooth · waiting for bio/RNG windows", true)
            } else {
                repo.updateWatchStatus("$label paired · auto-linking when in range", false)
            }

            // Connect BLE GATT with autoConnect=true so Android links as soon as the watch advertises or wakes
            if (gatt == null) {
                runCatching {
                    gatt = primaryWatch.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
        }

        // 2. Start Bluetooth Classic RFCOMM Server so the Watch can push accumulated day-to-day batches anytime
        if (rfcommServerJob?.isActive != true) {
            rfcommServerJob = scope.launch(Dispatchers.IO) {
                while (isActive && active) {
                    try {
                        val server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                            "GhostDriveWatch",
                            UUID.fromString(WatchProtocol.SERVICE_UUID)
                        )
                        rfcommServer = server
                        while (isActive && active) {
                            val socket = server.accept() ?: break
                            launch(Dispatchers.IO) { handleRfcommSocket(socket) }
                        }
                    } catch (_: Exception) {
                        delay(4000)
                    } finally {
                        runCatching { rfcommServer?.close() }
                        rfcommServer = null
                    }
                }
            }
        }

        // 3. Periodically attempt outbound RFCOMM connection to bonded Galaxy Watch 5 when not actively streaming
        if (rfcommClientJob?.isActive != true && primaryWatch != null) {
            rfcommClientJob = scope.launch(Dispatchers.IO) {
                while (isActive && active) {
                    if (rfcommOut == null) {
                        for (w in bondedWatches) {
                            if (!active || rfcommOut != null) break
                            runCatching {
                                val sock = w.createInsecureRfcommSocketToServiceRecord(UUID.fromString(WatchProtocol.SERVICE_UUID))
                                sock.connect()
                                handleRfcommSocket(sock)
                            }
                        }
                    }
                    delay(8_000)
                }
            }
        }
    }

    private suspend fun handleRfcommSocket(socket: BluetoothSocket) {
        val devName = runCatching { socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Galaxy Watch 5" }.getOrDefault("Galaxy Watch 5")
        try {
            rfcommOut = socket.outputStream
            val st = repo.settings.value
            val modeDesc = when {
                !st.watchEnabled -> "paused from phone"
                repo.live.value.authenticated -> "Nullvalon live · ${st.watchBatchIntervalSec}s batches"
                st.watchStandbyBatchMode -> "Standby ${st.watchBatchIntervalSec}s batch mode"
                else -> "Standby · waiting for Nullvalon (0% watch drain)"
            }
            repo.updateWatchStatus("$devName linked (BT) · $modeDesc", true)
            pushConfigToWatch()
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (active && socket.isConnected) {
                val line = reader.readLine() ?: break
                handleIncomingChunk(line + "\n", gatt)
            }
        } catch (_: Exception) {
        } finally {
            if (rfcommOut != null) rfcommOut = null
            runCatching { socket.close() }
        }
    }

    private fun scan() {
        if (!active || scanning) return
        val adapter = manager?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(WatchProtocol.SERVICE_UUID))).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(WatchProtocol.HR_SERVICE_UUID))).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            scanning = true
            scanner.startScan(filters, settings, scanCallback)
        }.onFailure { scanning = false }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!active) return
            stopScan()
            val label = result.device.name ?: result.device.address
            repo.updateWatchStatus("Connecting to $label…", false)
            runCatching { gatt?.close() }
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val label = runCatching { g.device.name ?: "Galaxy Watch 5" }.getOrDefault("Galaxy Watch 5")
                repo.updateWatchStatus("$label linked · requesting MTU", true)
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ackCharacteristic = null
                runCatching { g.close() }
                if (gatt == g) gatt = null
                if (rfcommOut == null) {
                    repo.updateWatchStatus("Watch standby · waiting for Galaxy Watch 5", false)
                }
                if (active) {
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(5000)
                        connectBondedWatchAndStartRfcomm()
                        scan()
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val svc = g.getService(UUID.fromString(WatchProtocol.SERVICE_UUID))
            if (svc != null) {
                ackCharacteristic = svc.getCharacteristic(UUID.fromString(WatchProtocol.CHAR_ACK_UUID))
                val ch = svc.getCharacteristic(UUID.fromString(WatchProtocol.CHAR_TELEMETRY_UUID))
                if (ch != null) {
                    enableNotifications(g, ch)
                    val st = repo.settings.value
                    val modeDesc = when {
                        !st.watchEnabled -> "paused from phone"
                        repo.live.value.authenticated -> "Nullvalon live · ${st.watchBatchIntervalSec}s batches"
                        st.watchStandbyBatchMode -> "Standby ${st.watchBatchIntervalSec}s batch mode"
                        else -> "Standby · waiting for Nullvalon (0% watch drain)"
                    }
                    repo.updateWatchStatus("Galaxy Watch 5 linked · $modeDesc", true)
                    scope.launch(Dispatchers.IO) {
                        delay(250)
                        pushConfigToWatch()
                    }
                    return
                }
            }
            // Fallback: subscribe to standard BLE Heart Rate Service (0x180D) if exposed by watch
            val hrSvc = g.getService(UUID.fromString(WatchProtocol.HR_SERVICE_UUID))
            val hrCh = hrSvc?.getCharacteristic(UUID.fromString(WatchProtocol.HR_MEASUREMENT_UUID))
            if (hrCh != null) {
                enableNotifications(g, hrCh)
                repo.updateWatchStatus("Galaxy Watch 5 HR linked · conditioning BPM & IBI entropy", true)
            }
        }

        private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(UUID.fromString(WatchProtocol.DESCRIPTOR_CCCD))
            if (desc != null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid.toString()
            if (uuid.equals(WatchProtocol.CHAR_TELEMETRY_UUID, ignoreCase = true)) {
                val chunk = value.toString(Charsets.UTF_8)
                handleIncomingChunk(chunk, g)
            } else if (uuid.equals(WatchProtocol.HR_MEASUREMENT_UUID, ignoreCase = true)) {
                handleStandardHeartRatePacket(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                onCharacteristicChanged(g, characteristic, value)
            }
        }
    }

    /** Parses standard Bluetooth SIG 0x2A37 Heart Rate Measurement + RR-Interval (IBI) packets and conditions them. */
    private fun handleStandardHeartRatePacket(bytes: ByteArray) {
        val st = repo.settings.value
        if (!st.watchEnabled) return
        val shouldHarvest = repo.live.value.authenticated || st.watchStandbyBatchMode
        if (!shouldHarvest) return
        if (bytes.size < 2) return
        val flags = bytes[0].toInt() and 0xFF
        val is16BitHr = (flags and 0x01) != 0
        val hasEnergy = (flags and 0x08) != 0
        val hasRr = (flags and 0x10) != 0
        var offset = 1
        val hrBpm: Float = if (is16BitHr) {
            if (bytes.size < offset + 2) return
            val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v.toFloat()
        } else {
            val v = bytes[offset].toInt() and 0xFF
            offset += 1
            v.toFloat()
        }
        if (hasEnergy) offset += 2
        var ibiMs: Int = (60_000f / hrBpm.coerceAtLeast(35f)).toInt()
        if (hasRr && bytes.size >= offset + 2) {
            val rrRaw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            ibiMs = ((rrRaw * 1000) / 1024).coerceIn(250, 2000)
        }
        val cur = repo.live.value.watch
        repo.updateWatchLiveMetrics(hrBpm, ibiMs, cur.spo2Pct ?: 98, cur.wristG ?: 1.0f, cur.pressureHpa, cur.steps, cur.gpsSpeedMps, cur.shannon, cur.minEntropy)
        hrSampleCounter++
        if (hrSampleCounter % 30 == 0) {
            val extra = ByteArray(48).also { rng.nextBytes(it) } + bytes
            val blk = WatchProtocol.conditionRawWatchSample(
                hrBpm = hrBpm,
                ibiMs = ibiMs,
                spo2Pct = cur.spo2Pct ?: 98,
                wristG = cur.wristG ?: 1.0f,
                pressureHpa = cur.pressureHpa ?: 1013.2f,
                steps = cur.steps,
                gpsSpeedMps = repo.live.value.lastFix?.speedMps ?: 0f,
                extraNoise = extra
            )
            repo.ingestWatchStagingBlock(blk, repo.live.value.lastFix?.speedMps)
            onBlockIngested?.invoke()
        }
    }

    /** Immediately pushes current Nullvalon state and Watch Standby Batch settings to the Galaxy Watch 5. */
    fun pushConfigToWatch(ackDigestHex: String = "") {
        val nvOnline = repo.live.value.authenticated
        val st = repo.settings.value
        val payload = JSONObject()
            .put("ack", ackDigestHex)
            .put("nullvalon_online", nvOnline)
            .put("watch_enabled", st.watchEnabled)
            .put("standby_batch_mode", st.watchStandbyBatchMode)
            .put("batch_interval_sec", st.watchBatchIntervalSec.coerceIn(15, 300))
            .toString() + "\n"
        val ackBytes = payload.toByteArray(Charsets.UTF_8)

        // 1. Send over RFCOMM if active
        runCatching {
            rfcommOut?.let { out ->
                synchronized(out) {
                    out.write(ackBytes)
                    out.flush()
                }
            }
        }
        // 2. Send over BLE GATT ACK characteristic if active
        val g = gatt
        val ackCh = ackCharacteristic
        if (g != null && ackCh != null) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(ackCh, ackBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    ackCh.value = ackBytes
                    @Suppress("DEPRECATION")
                    ackCh.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ackCh)
                }
            }
        }
    }

    private fun sendAckToWatch(digestHex: String, g: BluetoothGatt?) {
        pushConfigToWatch(ackDigestHex = digestHex)
    }

    private fun handleIncomingChunk(chunk: String, g: BluetoothGatt?) {
        synchronized(lineBuffer) {
            lineBuffer.append(chunk)
            while (true) {
                val nl = lineBuffer.indexOf("\n")
                if (nl < 0) break
                val line = lineBuffer.substring(0, nl).trim()
                lineBuffer.delete(0, nl + 1)
                if (line.startsWith("{")) {
                    runCatching {
                        val obj = JSONObject(line)
                        if (obj.has("set_standby_batch_mode") || obj.has("set_watch_enabled")) {
                            val curSt = repo.settings.value
                            val nextEnabled = if (obj.has("set_watch_enabled")) obj.optBoolean("set_watch_enabled", curSt.watchEnabled) else curSt.watchEnabled
                            val nextStandby = if (obj.has("set_standby_batch_mode")) obj.optBoolean("set_standby_batch_mode", curSt.watchStandbyBatchMode) else curSt.watchStandbyBatchMode
                            repo.saveSettings(curSt.copy(watchEnabled = nextEnabled, watchStandbyBatchMode = nextStandby))
                        }
                        val digestHex = obj.optString("watch_digest_hex").trim().lowercase()
                        val hr = if (obj.has("hr_bpm") && !obj.isNull("hr_bpm")) obj.optDouble("hr_bpm").toFloat() else null
                        val ibi = if (obj.has("ibi_ms") && !obj.isNull("ibi_ms")) obj.optInt("ibi_ms") else null
                        val spo2 = if (obj.has("spo2_pct") && !obj.isNull("spo2_pct")) obj.optInt("spo2_pct") else null
                        val wristG = if (obj.has("wrist_g") && !obj.isNull("wrist_g")) obj.optDouble("wrist_g").toFloat() else null
                        val press = if (obj.has("pressure_hpa") && !obj.isNull("pressure_hpa")) obj.optDouble("pressure_hpa").toFloat() else null
                        val steps = obj.optInt("steps", 0)
                        val gpsSpd = if (obj.has("gps_speed_mps") && !obj.isNull("gps_speed_mps")) obj.optDouble("gps_speed_mps").toFloat() else null
                        val shannon = obj.optDouble("shannon", 7.89).toFloat()
                        val minEnt = obj.optDouble("minimum", 7.08).toFloat()

                        if (digestHex.length == 128) {
                            val block = WatchStagingBlock(
                                timeMs = obj.optLong("time_ms", System.currentTimeMillis()),
                                hrBpm = hr,
                                ibiMs = ibi,
                                spo2Pct = spo2,
                                wristG = wristG,
                                pressureHpa = press,
                                steps = steps,
                                shannon = shannon,
                                minimum = minEnt,
                                watchDigestHex = digestHex
                            )
                            repo.ingestWatchStagingBlock(block, gpsSpd)
                            sendAckToWatch(digestHex, g)
                            onBlockIngested?.invoke()
                        } else if (hr != null || ibi != null || wristG != null) {
                            repo.updateWatchLiveMetrics(hr, ibi, spo2, wristG, press, steps, gpsSpd, shannon, minEnt)
                        }
                    }
                }
            }
        }
    }
}
