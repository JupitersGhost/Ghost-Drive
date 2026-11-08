package com.jupiterlabs.ghostdrive.sync

import android.net.*
import com.jupiterlabs.ghostdrive.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.net.URI
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object PrivateEndpoint {
    fun isTailnet(address: InetAddress): Boolean {
        val a = address.address
        return (a.size == 4 && a[0].toInt() and 255 == 100 && a[1].toInt() and 255 in 64..127) ||
            (a.size == 16 && a.take(6).toByteArray().contentEquals(byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())))
    }
    fun isPrivateOrTailnet(address: InetAddress): Boolean {
        return isTailnet(address) || address.isSiteLocalAddress || address.isLoopbackAddress
    }
    fun parse(value: String): URI = URI(value.trim()).also {
        require(it.scheme == "https" || it.scheme == "http") { "Use an http:// or https:// private endpoint." }
        require(!it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null) { "Invalid private endpoint." }
    }
}
class PrivateSync(private val repo: Repository) {
    private val mutex = Mutex()
    private class ForwardingError(message: String) : Exception(message)
    private fun verify(condition: Boolean, message: String) { if(!condition) throw ForwardingError(message) }
    @Suppress("DEPRECATION")
    private fun resolvePrivateNetwork(cm: ConnectivityManager, host: String): Pair<Network, List<InetAddress>> {
        val vpn = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val lan = cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val primary = vpn ?: lan ?: cm.activeNetwork
            ?: throw ForwardingError("Connect to Tailscale / Headscale VPN or Wi-Fi before syncing.")
        val addresses = runCatching { primary.getAllByName(host) }.getOrElse {
            (lan ?: cm.activeNetwork)?.getAllByName(host) ?: throw it
        }
        verify(addresses.isNotEmpty() && addresses.all(PrivateEndpoint::isPrivateOrTailnet),
            "Destination must resolve to a Headscale/Tailscale (100.64.x.x) or local LAN address.")
        return if (addresses.all(PrivateEndpoint::isTailnet)) {
            val vpnNet = vpn ?: throw ForwardingError("Waiting for Tailscale / Headscale VPN")
            val routes = cm.getLinkProperties(vpnNet)?.routes.orEmpty()
            verify(addresses.all { a -> routes.any { it.destination.contains(a) } }, "VPN has no route to this destination.")
            vpnNet to addresses.toList()
        } else {
            val net = lan ?: cm.activeNetwork ?: primary
            net to addresses.toList()
        }
    }
    suspend fun flush(forcePostDrive: Boolean = false) {
        if(!mutex.tryLock()) return
        try { withContext(Dispatchers.IO) {
        val settings = repo.settings.value
        if(!settings.syncEnabled) { status("Forwarding disabled"); return@withContext }
        if(repo.live.value.demo) { status("Simulator never uploads data"); return@withContext }
        if(!forcePostDrive && settings.syncOnlyConnected && !repo.live.value.authenticated) { status("Queued until Nullvalon reconnects"); return@withContext }
        val cm = repo.context.getSystemService(ConnectivityManager::class.java)
        var sent = 0; var failure: String? = null
        for(kind in listOf("history", "entropy")) {
            val target = if(kind == "history") settings.historyEndpoint else settings.entropyEndpoint
            if(target.isBlank()) continue
            try {
                val uri = PrivateEndpoint.parse(target)
                val host = uri.host.removeSurrounding("[", "]")
                val (net, addresses) = resolvePrivateNetwork(cm, host)
                val tokenName = if(kind == "history") "history_token" else "entropy_token"
                val token = repo.vault.get(tokenName)?.toString(Charsets.UTF_8).orEmpty()
                // Pin exactly the addresses we validated. Preserve the hostname for HTTPS verification.
                val client = PrivateHttp.client(net.socketFactory, host, addresses)
                try { for(index in 0 until 200) {
                    val current = repo.settings.value
                    val currentTarget = if(kind == "history") current.historyEndpoint else current.entropyEndpoint
                    if(!current.syncEnabled || currentTarget != target || repo.live.value.demo ||
                        (!forcePostDrive && current.syncOnlyConnected && !repo.live.value.authenticated)) break
                    val event = repo.db.ready(kind, System.currentTimeMillis()) ?: break
                    try {
                        val reqBuilder = Request.Builder().url(uri.toString())
                            .header("User-Agent", "GhostDrive/0.3")
                            .post(event.payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        if(token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")
                        val request = reqBuilder.build()
                        client.newCall(request).execute().use { response ->
                            if(response.isSuccessful) { repo.db.acknowledge(event.id); sent++ }
                            else throw ForwardingError("$kind endpoint returned HTTP ${response.code}; event remains queued.")
                        }
                    } catch(e: Exception) { repo.db.retry(event); throw e }
                } } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            } catch(e: Exception) {
                // Never log URLs or tokens: the host path may itself contain a secret.
                failure = if(e is ForwardingError) e.message else "Private $kind forwarding failed (${e.javaClass.simpleName}); records remain queued"
                status(failure ?: "Private forwarding failed; records remain queued")
                repo.log("WARN", "Sync", repo.live.value.syncStatus)
            }
        }
        if(sent > 0) {
            repo.live.update { it.copy(syncStatus = "Delivered $sent queued records" + (failure?.let { f -> "; $f" } ?: ""), lastSyncMs = System.currentTimeMillis(), queueCount = repo.db.queueSize()) }
            repo.log("INFO", "Sync", "Delivered $sent record(s) over the private VPN.")
        } else if(settings.historyEndpoint.isBlank() && settings.entropyEndpoint.isBlank()) status("Choose private destinations in Settings")
        } } finally { mutex.unlock() }
    }
    /** Bidirectional cross-phone sync: pushes local archive and pulls/merges remote archive from local LAN or Headscale server. */
    suspend fun pullFromServer(pushFirst: Boolean = true): String = withContext(Dispatchers.IO) {
        val target = repo.settings.value.syncPullEndpoint.trim()
        if (target.isBlank()) return@withContext "Configure the Cross-Phone Sync Server URL in Settings first."
        val cm = repo.context.getSystemService(ConnectivityManager::class.java)
        try {
            val uri = PrivateEndpoint.parse(target)
            val host = uri.host.removeSurrounding("[", "]")
            val (activeNet, addresses) = resolvePrivateNetwork(cm, host)
            val token = repo.vault.get("history_token")?.toString(Charsets.UTF_8).orEmpty()
            val client = PrivateHttp.client(activeNet.socketFactory, host, addresses)
            try {
                if (pushFirst && !repo.live.value.demo) {
                    val pushReq = Request.Builder().url(uri.toString()).header("User-Agent", "GhostDrive/0.3")
                        .post(repo.db.export(repo.settings.value).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    if (token.isNotBlank()) pushReq.header("Authorization", "Bearer $token")
                    client.newCall(pushReq.build()).execute().use { pushResp ->
                        if (pushResp.isSuccessful) {
                            val body = pushResp.body?.string().orEmpty()
                            if (body.trimStart().startsWith("{")) {
                                val rootJson = JSONObject(body)
                                if (rootJson.has("trips")) {
                                    val (trips, places) = repo.db.importArchive(rootJson)
                                    rootJson.optJSONObject("settings")?.let { sJson ->
                                        repo.applySyncedSettings(repo.settings.value.mergeFromSyncJson(sJson))
                                    }
                                    repo.refresh()
                                    val msg = "Bidirectional sync complete · +$trips trip(s), $places place(s)"
                                    status(msg)
                                    repo.log("INFO", "Sync", msg)
                                    return@withContext msg
                                }
                            }
                        }
                    }
                }
                val reqBuilder = Request.Builder().url(uri.toString()).header("User-Agent", "GhostDrive/0.3")
                if (token.isNotBlank()) reqBuilder.header("Authorization", "Bearer $token")
                client.newCall(reqBuilder.get().build()).execute().use { resp ->
                    verify(resp.isSuccessful, "Sync server returned HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw ForwardingError("Empty sync response")
                    val rootJson = JSONObject(body)
                    val (trips, places) = repo.db.importArchive(rootJson)
                    rootJson.optJSONObject("settings")?.let { sJson ->
                        repo.applySyncedSettings(repo.settings.value.mergeFromSyncJson(sJson))
                    }
                    repo.refresh()
                    val msg = "Synced $trips new trip(s) & $places place(s) from server"
                    status(msg)
                    repo.log("INFO", "Sync", msg)
                    msg
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        } catch (e: Exception) {
            val msg = if (e is ForwardingError) e.message ?: "Sync failed" else "Sync server unreachable (${e.javaClass.simpleName})"
            status(msg)
            msg
        }
    }
    private fun status(s: String) { repo.live.update { it.copy(syncStatus = s, queueCount = repo.db.queueSize()) } }
}
