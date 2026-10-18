package local.airuize.receiver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log

/** Bulk OUT with precise Linux errors and no Java array pin held during the blocking ioctl. */
object LegacyNcmUsbIo {
    private const val TAG = "DiPlayUsb"
    private const val EPIPE = 32
    // Publish numbers only on the per-packet success path. Formatting is reserved for a
    // diagnostic read, first success, or a failure; readers see one immutable transfer.
    internal data class TransferSnapshot(
        val path: String,
        val fd: Int,
        val endpoint: Int,
        val bytes: Int,
        val timeoutMillis: Int,
        val transferred: Int,
        val elapsedMs: Long,
        val threw: String? = null,
    ) {
        val hasErrno: Boolean get() = path != "framework"

        fun summary(): String {
            val prefix = "path=$path fd=$fd ep=0x${endpoint.toString(16)} bytes=$bytes "
            if (threw != null) return prefix + "elapsedMs=$elapsedMs threw=$threw"
            return prefix + "timeoutMs=$timeoutMillis transferred=$transferred " +
                "${LegacyNcmUsbIo.describe(transferred, hasErrno)} elapsedMs=$elapsedMs"
        }
    }
    @Volatile private var lastTransfer: TransferSnapshot? = null
    @Volatile private var lastRecovery = "none"
    @Volatile private var loggedNativeSuccess = false
    @Volatile private var loggedFrameworkFallback = false
    @Volatile private var lastTransferHasErrno = false

    fun diagnosticSnapshot(): String = "last={${lastTransfer?.summary() ?: "not-attempted"}} recovery={$lastRecovery}"
    fun resetDiagnostic() { lastTransfer = null; lastRecovery = "none"; lastTransferHasErrno = false }

    /** Called immediately after transfer by the NCM bridge's serialized OUT writer. */
    fun isFatalResult(result: Int): Boolean = isFatalResultWithErrno(result, lastTransferHasErrno)

    internal fun isFatalResultWithErrno(result: Int, hasErrno: Boolean): Boolean {
        if (result >= 0 || result == UsbIfaceNative.NOT_LOADED || !hasErrno) return false
        return when (-result) {
            // A returned EPIPE has already exhausted the one clear-halt/retry in transfer().
            1, 9, 13, 19, 22, 25, EPIPE, 38, 104, 108 -> true
            // EAGAIN, EINTR and ETIMEDOUT remain retryable within the bridge's deadline.
            else -> false
        }
    }

    /**
     * UsbIfaceNative.ensureLoaded(context) is called during interface selection before this.
     * A native negative result is -errno. Only a missing native library/entry point uses the
     * framework fallback, whose -1 result has no recoverable errno information.
     */
    fun transfer(connection: UsbDeviceConnection, endpoint: UsbEndpoint, block: ByteArray, timeoutMillis: Int): Int {
        val fd = connection.fileDescriptor
        val address = endpoint.address
        val started = SystemClock.elapsedRealtime()
        val first = UsbIfaceNative.bulkOut(fd, address, block, timeoutMillis)
        if (first == UsbIfaceNative.NOT_LOADED) {
            lastTransferHasErrno = false
            if (!loggedFrameworkFallback) {
                loggedFrameworkFallback = true
                Log.w(TAG, "NCM OUT native unavailable; using framework bulkTransfer (errno unavailable)")
            }
            val transferred = try {
                connection.bulkTransfer(endpoint, block, block.size, timeoutMillis)
            } catch (error: RuntimeException) {
                val snapshot = TransferSnapshot("framework", fd, address, block.size, timeoutMillis,
                    0, SystemClock.elapsedRealtime() - started, error.javaClass.simpleName)
                lastTransfer = snapshot
                Log.w(TAG, "NCM OUT ${snapshot.summary()}")
                throw error
            }
            record("framework", fd, address, block.size, timeoutMillis, transferred, started)
            return transferred
        }
        record("native", fd, address, block.size, timeoutMillis, first, started)
        if (first != -EPIPE) return first

        // A stalled OUT endpoint can be cleared and retried once. Timeouts and partial NTBs
        // are returned unchanged: automatically resending them could duplicate a datagram.
        val clearStarted = SystemClock.elapsedRealtime()
        val cleared = UsbIfaceNative.clearHalt(fd, address)
        lastRecovery = "fd=$fd ep=0x${address.toString(16)} clearHalt=$cleared " +
            "${describe(cleared, true)} elapsedMs=${SystemClock.elapsedRealtime() - clearStarted}"
        Log.w(TAG, "NCM OUT EPIPE recovery $lastRecovery")
        if (cleared != 0) return first
        val retryStarted = SystemClock.elapsedRealtime()
        val retried = UsbIfaceNative.bulkOut(fd, address, block, timeoutMillis)
        record("native-after-clear-halt", fd, address, block.size, timeoutMillis, retried, retryStarted)
        return retried
    }

    private fun record(path: String, fd: Int, endpoint: Int, bytes: Int, timeoutMillis: Int, transferred: Int, started: Long) {
        val snapshot = TransferSnapshot(path, fd, endpoint, bytes, timeoutMillis, transferred,
            SystemClock.elapsedRealtime() - started)
        lastTransferHasErrno = snapshot.hasErrno
        lastTransfer = snapshot
        if (transferred < 0 || transferred != bytes) {
            Log.w(TAG, "NCM OUT ${snapshot.summary()}")
        } else if (!loggedNativeSuccess) {
            loggedNativeSuccess = true
            Log.i(TAG, "NCM OUT ${snapshot.summary()}")
        }
    }

    private fun describe(result: Int, hasErrno: Boolean): String {
        if (result == UsbIfaceNative.NOT_LOADED) return "errno=unavailable(native-missing)"
        if (result >= 0) return "errno=0"
        if (!hasErrno) return "errno=unavailable(framework)"
        val code = -result
        val name = when (code) {
            1 -> "EPERM"
            4 -> "EINTR"
            5 -> "EIO"
            9 -> "EBADF"
            11 -> "EAGAIN"
            12 -> "ENOMEM"
            13 -> "EACCES"
            14 -> "EFAULT"
            16 -> "EBUSY"
            19 -> "ENODEV"
            22 -> "EINVAL"
            25 -> "ENOTTY"
            28 -> "ENOSPC"
            EPIPE -> "EPIPE"
            38 -> "ENOSYS"
            71 -> "EPROTO"
            75 -> "EOVERFLOW"
            84 -> "EILSEQ"
            104 -> "ECONNRESET"
            108 -> "ESHUTDOWN"
            110 -> "ETIMEDOUT"
            else -> "UNKNOWN"
        }
        return "errno=$code($name)"
    }
}
