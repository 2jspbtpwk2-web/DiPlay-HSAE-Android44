package local.airuize.receiver

import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class SocketDiagnosticsTest {
    @Test fun bindFailureRetainsKnownErrnoAndOmitsPrivateMessage() {
        val result=SocketDiagnostics.failure(SocketException("bind failed: EINVAL (Invalid argument) fe80::secret%tun4 private-key=secret"))
        assertTrue(result.contains("operation=bind")); assertTrue(result.contains("errno=22"))
        assertTrue(result.contains("errnoSource=message-token")); assertFalse(result.contains("secret"))
        assertFalse(result.contains("Invalid argument"))
    }
    @Test fun unknownMessagesAreNotExported() {
        val result=SocketDiagnostics.failure(SocketException("secret-address secret-token"))
        assertTrue(result.contains("errno=unavailable")); assertFalse(result.contains("secret"))
    }
    @Test fun knownErrnoCanBeReadFromCauseMessage() {
        val result=SocketDiagnostics.failure(SocketException("wrapper").apply { initCause(java.io.IOException("socket failed: EAFNOSUPPORT detail")) })
        assertTrue(result.contains("errno=97")); assertTrue(result.contains("SocketException>IOException"))
    }
    @Test fun ipv6SummaryRecordsScopeWithoutAddress() {
        val bytes=ByteArray(16).apply { this[0]=0xfe.toByte(); this[1]=0x80.toByte(); this[15]=2 }
        val result=SocketDiagnostics.address(Inet6Address.getByAddress(null,bytes,7))
        assertTrue(result.contains("family=ipv6")); assertTrue(result.contains("scope=7"))
        assertTrue(result.contains("linkLocal=true")); assertFalse(result.contains("fe80"))
    }
    @Test fun ipv4AndNullHaveNoScope() {
        assertTrue(SocketDiagnostics.address(InetAddress.getByName("127.0.0.1")).contains("family=ipv4"))
        assertTrue(SocketDiagnostics.address(null).contains("family=unknown"))
    }
}
