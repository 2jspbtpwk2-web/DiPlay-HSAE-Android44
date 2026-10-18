package local.airuize.receiver

import com.shilapi.xcertplay.airplay.MicrophoneConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException

class LegacyMicrophoneSocketTest {
    private fun config(remote: String, local: InetAddress? = null) = MicrophoneConfig(
        "speechrecognition", 16000, 1, 100, 20, InetAddress.getByName(remote), 7001, ByteArray(32), localAddress = local,
    )
    private fun local(scope: Int): Inet6Address = Inet6Address.getByAddress(null,
        InetAddress.getByName("fe80::2").address, scope)
    private fun rejected(config: MicrophoneConfig) {
        try { LegacyMicrophoneSocket.bindAddress(config); fail("Missing or invalid local endpoint accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun oldIpv4ConstructorRemainsCompatibleWithoutLocalAddress() {
        val config = config("192.0.2.3")
        assertNull(config.localAddress)
        assertEquals("0.0.0.0", LegacyMicrophoneSocket.bindAddress(config).hostAddress)
    }
    @Test fun ipv4StillUsesWildcardRatherThanBindingThePhone() {
        val config = config("192.0.2.3", InetAddress.getByName("192.0.2.2"))
        assertTrue(LegacyMicrophoneSocket.bindAddress(config).isAnyLocalAddress)
        assertNotEquals(config.host, LegacyMicrophoneSocket.bindAddress(config))
    }
    @Test fun linkLocalBindsTheConcreteLocalAddressAndKeepsItsScope() {
        val local = local(7)
        val address = LegacyMicrophoneSocket.bindAddress(config("fe80::3", local))
        assertSame(local, address)
        assertEquals(7, (address as Inet6Address).scopeId)
        assertFalse(address.address.contentEquals(InetAddress.getByName("fe80::3").address))
    }
    @Test fun linkLocalRequiresScopedLocalAddressInsteadOfFallingBackToRemoteOrWildcard() {
        rejected(config("fe80::3"))
        rejected(config("fe80::3", local(0)))
        rejected(config("fe80::3", InetAddress.getByName("::")))
        rejected(config("fe80::3", InetAddress.getByName("2001:db8::2")))
        rejected(config("fe80::3", InetAddress.getByName("192.0.2.2")))
    }
    @Test fun mismatchedIpv4AndIpv6EndpointsAreRejected() {
        rejected(config("192.0.2.3", local(7)))
    }
    @Test fun nonLinkLocalIpv6AlsoUsesProvidedLocalEndpoint() {
        val local = InetAddress.getByName("2001:db8::2")
        assertSame(local, LegacyMicrophoneSocket.bindAddress(config("2001:db8::3", local)))
        rejected(config("2001:db8::3"))
    }
    @Test fun bindFailureClosesTheUnpublishedSocket() {
        val socket = ProbeSocket(failBind = true)
        try {
            try { LegacyMicrophoneSocket.open(config("192.0.2.3")) { socket }; fail("Bind failure swallowed") }
            catch (_: SocketException) {}
            assertEquals(1, socket.closeCalls)
            assertTrue(socket.isClosed)
        } finally { socket.close() }
    }
    @Test fun successfulBindReturnsOwnershipAndDoesNotCloseTheSocket() {
        val socket = ProbeSocket(failBind = false)
        try {
            assertSame(socket, LegacyMicrophoneSocket.open(config("192.0.2.3")) { socket })
            val bound = socket.boundAddress as InetSocketAddress
            assertTrue(bound.address.isAnyLocalAddress)
            assertEquals(0, bound.port)
            assertEquals(0, socket.closeCalls)
        } finally { socket.close() }
    }

    private class ProbeSocket(private val failBind: Boolean) : DatagramSocket(null as SocketAddress?) {
        var boundAddress: SocketAddress? = null
        var closeCalls = 0
        override fun bind(address: SocketAddress?) {
            boundAddress = address
            if (failBind) throw SocketException("test bind failure")
        }
        override fun close() { closeCalls++; super.close() }
    }
}
