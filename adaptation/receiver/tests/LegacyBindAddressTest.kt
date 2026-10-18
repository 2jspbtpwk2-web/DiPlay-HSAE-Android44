package local.airuize.receiver

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class LegacyBindAddressTest {
    private fun link(scope: Int): Inet6Address {
        val bytes = ByteArray(16)
        bytes[0] = 0xfe.toByte(); bytes[1] = 0x80.toByte(); bytes[15] = 2
        return Inet6Address.getByAddress(null, bytes, scope)
    }
    private fun global(scope: Int = 0): Inet6Address {
        val bytes = ByteArray(16)
        bytes[0] = 0x20; bytes[1] = 1; bytes[15] = 2
        return Inet6Address.getByAddress(null, bytes, scope)
    }
    private fun rejects(block: () -> Unit, expected: String) {
        try { block(); fail("Missing interface scope was accepted") }
        catch (error: IllegalArgumentException) { assertEquals(expected, error.message) }
    }

    @Test fun scopedWiredLocalKeepsTheExactAddressObject() {
        val local = link(7)
        assertSame(local, LegacyBindAddress.any(local))
        assertEquals(7, (LegacyBindAddress.any(local) as Inet6Address).scopeId)
        assertFalse(LegacyBindAddress.any(local).isAnyLocalAddress)
    }
    @Test fun globalIpv6AndLoopbackRetainTheirFamilyAndObject() {
        val global = global()
        val loopback = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
        assertSame(global, LegacyBindAddress.any(global))
        assertSame(loopback, LegacyBindAddress.any(loopback))
    }
    @Test fun ipv4KeepsExistingWildcardBindingPolicy() {
        val local = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), LegacyBindAddress.any(local).address)
        assertTrue(LegacyBindAddress.any(local).isAnyLocalAddress)
    }
    @Test fun missingLocalKeepsExistingIpv4Default() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), LegacyBindAddress.any(null).address)
    }
    @Test fun unscopedLinkLocalBindingFailsWithoutIncludingTheAddress() {
        rejects({ LegacyBindAddress.any(link(0)) }, "IPv6 link-local local address requires interface scope")
    }
    @Test fun peerMissingScopeUsesOnlyTheSameAcceptedLinkLocalInterface() {
        val peer = link(0)
        val repaired = LegacyBindAddress.peer(peer, link(7)) as Inet6Address
        assertEquals(7, repaired.scopeId)
        assertArrayEquals(peer.address, repaired.address)
        assertEquals(0, peer.scopeId)
    }
    @Test fun alreadyScopedPeerIsNotRewritten() {
        val peer = link(9)
        assertSame(peer, LegacyBindAddress.peer(peer, link(7)))
        assertSame(peer, LegacyBindAddress.peer(peer, null))
    }
    @Test fun ipv4GlobalAndNullPeersAreNotRewritten() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val ipv6 = global()
        assertSame(ipv4, LegacyBindAddress.peer(ipv4, link(7)))
        assertSame(ipv6, LegacyBindAddress.peer(ipv6, link(7)))
        assertNull(LegacyBindAddress.peer(null, link(7)))
    }
    @Test fun peerScopeCannotBeGuessedFromMissingDifferentOrUnscopedLocal() {
        for (local in listOf(null, InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2)), global(7), link(0))) {
            rejects({ LegacyBindAddress.peer(link(0), local) }, "IPv6 link-local peer requires a scoped link-local session address")
        }
    }
}
