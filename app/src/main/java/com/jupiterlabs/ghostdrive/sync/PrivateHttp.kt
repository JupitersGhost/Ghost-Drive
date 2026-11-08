package com.jupiterlabs.ghostdrive.sync

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** One flush uses only its vetted DNS results and the selected VPN's socket factory. */
internal object PrivateHttp {
    fun client(socketFactory: SocketFactory, host: String, addresses: List<InetAddress>): OkHttpClient =
        OkHttpClient.Builder().socketFactory(socketFactory).proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    require(hostname.equals(host, true)) { "Unexpected forwarding host" }
                    return addresses
                }
            })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
}
