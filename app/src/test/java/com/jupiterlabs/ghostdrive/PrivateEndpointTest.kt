package com.jupiterlabs.ghostdrive

import com.jupiterlabs.ghostdrive.sync.PrivateEndpoint
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class PrivateEndpointTest {
    @Test fun privateTailnetAddressesOnly() {
        assertTrue(PrivateEndpoint.isTailnet(InetAddress.getByName("100.64.0.1")))
        assertTrue(PrivateEndpoint.isTailnet(InetAddress.getByName("100.127.255.254")))
        assertTrue(PrivateEndpoint.isTailnet(InetAddress.getByName("fd7a:115c:a1e0::56")))
        assertFalse(PrivateEndpoint.isTailnet(InetAddress.getByName("100.63.255.255")))
        assertFalse(PrivateEndpoint.isTailnet(InetAddress.getByName("100.128.0.1")))
        assertFalse(PrivateEndpoint.isTailnet(InetAddress.getByName("127.0.0.1")))
        assertFalse(PrivateEndpoint.isTailnet(InetAddress.getByName("8.8.8.8")))
        assertFalse(PrivateEndpoint.isTailnet(InetAddress.getByName("fd7a:115c:a1e1::1")))
    }
    @Test fun endpointRejectsEmbeddedCredentialsAndUnsupportedSchemes() {
        assertEquals("100.64.0.1", PrivateEndpoint.parse("http://100.64.0.1:8790/v1/events").host)
        assertThrows(IllegalArgumentException::class.java) { PrivateEndpoint.parse("ftp://100.64.0.1/data") }
        assertThrows(IllegalArgumentException::class.java) { PrivateEndpoint.parse("http://user:secret@100.64.0.1/data") }
        assertThrows(IllegalArgumentException::class.java) { PrivateEndpoint.parse("http://100.64.0.1/data#fragment") }
    }
}
