package com.jupiterlabs.ghostdrive

import com.jupiterlabs.ghostdrive.sync.PrivateHttp
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class PrivateHttpTest {
    private class CountingSockets : SocketFactory() {
        val count = AtomicInteger()
        override fun createSocket(): Socket { count.incrementAndGet(); return Socket() }
        override fun createSocket(host: String, port: Int): Socket = error("Unvetted DNS bypass")
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("Unvetted DNS bypass")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Socket must bind before connecting")
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("Socket must bind before connecting")
    }
    private fun exchange(code: Int): String {
        // Loopback tests this transport in isolation. App preflight separately rejects loopback endpoints.
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5000
            val received = CompletableFuture<String>()
            val thread = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = mutableListOf<String>()
                    while(true) { val line = reader.readLine() ?: break; if(line.isEmpty()) break; headers += line }
                    val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(length); var at = 0
                    while(at < length) { val n = reader.read(body, at, length - at); check(n > 0); at += n }
                    val redirect = if(code == 307) "Location: http://public.invalid/leak\r\n" else ""
                    socket.getOutputStream().write("HTTP/1.1 $code Test\r\n${redirect}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    socket.getOutputStream().flush()
                    received.complete(headers.joinToString("\n") + "\n" + String(body))
                } } catch(e: Exception) { received.completeExceptionally(e) }
            }.apply { isDaemon = true; start() }
            val sockets = CountingSockets()
            val client = PrivateHttp.client(sockets, "private.invalid", listOf(loopback))
            try {
                val request = Request.Builder().url("http://private.invalid:${server.localPort}/v1/events")
                    .header("Authorization", "Bearer test-only-token").post("{}".toRequestBody()).build()
                client.newCall(request).execute().use { assertEquals(code, it.code) }
                assertEquals(1, sockets.count.get())
                return received.get(5, TimeUnit.SECONDS)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); thread.join(5000) }
        }
    }
    @Test fun usesPinnedDnsAndTheSuppliedSocketFactory() {
        val request = exchange(200)
        assertTrue(request.contains("POST /v1/events HTTP/1.1"))
        assertTrue(request.contains("Host: private.invalid:"))
        assertTrue(request.contains("Authorization: Bearer test-only-token"))
        assertTrue(request.endsWith("{}"))
    }
    @Test fun redirectsCannotMoveTheBearerTokenToAnotherHost() { exchange(307) }
}
