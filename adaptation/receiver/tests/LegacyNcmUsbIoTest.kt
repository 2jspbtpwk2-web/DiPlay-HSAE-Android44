package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class LegacyNcmUsbIoTest {
    @Test fun successfulSnapshotPreservesDiagnosticFields() {
        val transfer = snapshot()
        assertTrue(transfer.hasErrno)
        assertEquals("path=native fd=17 ep=0x2 bytes=1442 timeoutMs=2000 transferred=1442 errno=0 elapsedMs=3",
            transfer.summary())
    }

    @Test fun nativeFailureAndRecoveryKeepPreciseErrno() {
        assertEquals("path=native fd=17 ep=0x2 bytes=1442 timeoutMs=2000 transferred=-110 errno=110(ETIMEDOUT) elapsedMs=3",
            snapshot(transferred = -110).summary())
        assertEquals("path=native-after-clear-halt fd=17 ep=0x2 bytes=1442 timeoutMs=2000 transferred=-32 errno=32(EPIPE) elapsedMs=3",
            snapshot(path = "native-after-clear-halt", transferred = -32).summary())
        assertTrue(snapshot(transferred = -1234).summary().contains("errno=1234(UNKNOWN)"))
    }

    @Test fun frameworkResultsNeverInventErrno() {
        val transfer = snapshot(path = "framework", transferred = -1)
        assertFalse(transfer.hasErrno)
        assertEquals("path=framework fd=17 ep=0x2 bytes=1442 timeoutMs=2000 transferred=-1 errno=unavailable(framework) elapsedMs=3",
            transfer.summary())
        assertTrue(snapshot(path = "framework").summary().contains("errno=0"))
        assertTrue(snapshot(transferred = UsbIfaceNative.NOT_LOADED).summary().contains("errno=unavailable(native-missing)"))
    }

    @Test fun frameworkExceptionPreservesPreviousDiagnosticShape() {
        assertEquals("path=framework fd=17 ep=0x2 bytes=1442 elapsedMs=3 threw=IllegalStateException",
            snapshot(path = "framework").copy(threw = "IllegalStateException").summary())
    }

    @Test fun nativeFatalClassificationIsUnchanged() {
        val fatal = intArrayOf(1, 9, 13, 19, 22, 25, 32, 38, 104, 108)
        for (errno in fatal) {
            assertTrue("native errno $errno", LegacyNcmUsbIo.isFatalResultWithErrno(-errno, true))
            assertFalse("framework errno $errno", LegacyNcmUsbIo.isFatalResultWithErrno(-errno, false))
        }
        for (result in intArrayOf(0, 1442, -4, -11, -110, -5, UsbIfaceNative.NOT_LOADED)) {
            assertFalse("result $result", LegacyNcmUsbIo.isFatalResultWithErrno(result, true))
        }
    }

    @Test fun immutableTransferSnapshotCannotChangeWithLaterTransfer() {
        val first = snapshot()
        val before = first.summary()
        val later = first.copy(transferred = -19, elapsedMs = 250)
        assertEquals(before, first.summary())
        assertTrue(later.summary().contains("transferred=-19 errno=19(ENODEV) elapsedMs=250"))
    }

    private fun snapshot(path: String = "native", transferred: Int = 1442) =
        LegacyNcmUsbIo.TransferSnapshot(path, 17, 2, 1442, 2000, transferred, 3)
}
