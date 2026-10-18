package local.airuize.receiver

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Loads the tiny JNI shim `libusbiface` and exposes a real kernel-side USB alternate-setting
 * switch (ioctl USBDEVFS_SETINTERFACE) on the live connection's own file descriptor.
 *
 * The hand-written controlTransfer(SET_INTERFACE) used on API < 21 changes the device but does
 * not update Linux usbfs' cur_altsetting, so the NCM data interface stays on alt 0 with no bulk
 * endpoints. This object's [setInterface] is the framework-API21-equivalent operation, run in
 * the app process on the connection's own fd (no ROOT needed; the fd is never closed here).
 */
object UsbIfaceNative {
    private const val TAG = "DiPlayUsb"
    private const val LEGACY_ARM_ASSET = "native/libusbiface.so"
    private const val FILE_PREFIX = "libusbiface-"

    // 0 = not attempted, 1 = loaded, -1 = failed
    @Volatile
    private var loadState = 0
    private var loadMessage: String? = null
    private var loadedAsset = "none"
    private var nativeApiInfo = "unknown"
    @Volatile private var bulkSymbolMissing = false
    @Volatile private var bulkInSymbolMissing = false
    @Volatile private var clearHaltSymbolMissing = false

    fun isLoaded(): Boolean = loadState == 1

    /** Safe diagnostic state: loader status only, never native payload or credentials. */
    fun diagnosticSnapshot(): String {
        val state = when (loadState) {
            1 -> "loaded"
            -1 -> "failed"
            else -> "not-attempted"
        }
        return "state=$state abi=${Build.CPU_ABI} asset=$loadedAsset api=$nativeApiInfo " +
            "bulkOut=${if (bulkSymbolMissing) "missing" else "available-if-loaded"} " +
            "bulkIn=${if (bulkInSymbolMissing) "missing" else "available-if-loaded"} " +
            "error=${loadMessage ?: "none"}"
    }

    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (loadState == 1) return true
        if (loadState == -1) return false
        val appContext = context.applicationContext
        try {
            // Use the asset content hash in the filename. System.load may keep the old
            // inode mapped across process restarts, so never truncate or rewrite a path
            // that may already have been loaded.
            val asset = readAsset(appContext)
            loadedAsset = asset.first
            val assetBytes = asset.second
            val digest = MessageDigest.getInstance("SHA-256").digest(assetBytes)
            val hash = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val soFile = File(appContext.filesDir, "$FILE_PREFIX$hash.so")

            if (!matches(soFile, digest)) {
                val temporary = File(appContext.filesDir, "$FILE_PREFIX$hash-${System.nanoTime()}.tmp")
                try {
                    FileOutputStream(temporary).use { it.write(assetBytes) }
                    if (!temporary.setReadable(true, true) || !temporary.setWritable(false, true)) {
                        throw IllegalStateException("Could not set private native library permissions")
                    }
                    if (!temporary.renameTo(soFile)) {
                        // A concurrent process or filesystem may refuse replacing an
                        // existing hash path. Keep the old file intact and use a unique
                        // immutable path for this load.
                        val fallback = File(appContext.filesDir, "$FILE_PREFIX$hash-${System.nanoTime()}.so")
                        if (!temporary.renameTo(fallback)) {
                            throw IllegalStateException("Could not publish native library cache")
                        }
                        System.load(fallback.absolutePath)
                        recordLoaded(hash, fallback)
                        return true
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
            }

            if (!soFile.setReadable(true, true)) {
                throw IllegalStateException("Could not make native library readable")
            }
            // Never make a loaded immutable library writable again.
            soFile.setWritable(false, true)
            if (!matches(soFile, digest)) {
                throw IllegalStateException("Native library cache hash mismatch")
            }
            System.load(soFile.absolutePath)
            recordLoaded(hash, soFile)
            return true
        } catch (t: Throwable) {
            loadState = -1
            loadMessage = t.javaClass.simpleName + ":" + t.message
            Log.e(TAG, "libusbiface load failed: $loadMessage")
            return false
        }
    }

    /**
     * Performs USBDEVFS_SETINTERFACE on the connection's borrowed fd.
     *
     * @return 0 on success; a negative `-errno` on native failure; [NOT_LOADED] if the native
     * library could not be loaded. The fd is borrowed and never closed.
     */
    fun setInterface(fd: Int, interfaceId: Int, altSetting: Int): Int {
        if (loadState != 1) return NOT_LOADED
        return nativeSetInterface(fd, interfaceId, altSetting)
    }

    /** Copies the packet before the blocking USB ioctl and returns its count or negative errno. */
    fun bulkOut(fd: Int, endpoint: Int, bytes: ByteArray, timeoutMillis: Int): Int {
        if (loadState != 1 || bulkSymbolMissing) return NOT_LOADED
        return try {
            nativeBulkOut(fd, endpoint, bytes, timeoutMillis)
        } catch (error: UnsatisfiedLinkError) {
            bulkSymbolMissing = true
            Log.w(TAG, "libusbiface has no bulk OUT entry point; framework fallback: ${error.javaClass.simpleName}")
            NOT_LOADED
        }
    }

    /** Wait on native storage, then copy only the completed bytes into the Java buffer. */
    fun bulkIn(fd: Int, endpoint: Int, bytes: ByteArray, length: Int, timeoutMillis: Int): Int {
        if (loadState != 1 || bulkInSymbolMissing) return NOT_LOADED
        return try { nativeBulkIn(fd, endpoint, bytes, length, timeoutMillis) }
        catch (_: UnsatisfiedLinkError) { bulkInSymbolMissing = true; NOT_LOADED }
    }

    /** Used only to recover an observed EPIPE stall; it never changes the selected interface. */
    fun clearHalt(fd: Int, endpoint: Int): Int {
        if (loadState != 1 || clearHaltSymbolMissing) return NOT_LOADED
        return try {
            nativeClearHalt(fd, endpoint)
        } catch (_: UnsatisfiedLinkError) {
            clearHaltSymbolMissing = true
            NOT_LOADED
        }
    }

    /** Native ABI version/pointer size probe; safe to call without a USB device. */
    fun abiInfo(): Int = if (loadState != 1) NOT_LOADED else try {
        nativeAbiInfo()
    } catch (_: UnsatisfiedLinkError) {
        NOT_LOADED
    }

    private external fun nativeSetInterface(fd: Int, interfaceId: Int, altSetting: Int): Int
    private external fun nativeBulkOut(fd: Int, endpoint: Int, bytes: ByteArray, timeoutMillis: Int): Int
    private external fun nativeBulkIn(fd: Int, endpoint: Int, bytes: ByteArray, length: Int, timeoutMillis: Int): Int
    private external fun nativeClearHalt(fd: Int, endpoint: Int): Int
    private external fun nativeAbiInfo(): Int

    const val NOT_LOADED = Int.MIN_VALUE

    private fun recordLoaded(hash: String, file: File) {
        // The v3 shim adds native-memory IN reads on both supported 32-bit ABIs.
        // Reject an old cache/library before advertising its missing entry points as usable.
        val info = nativeAbiInfo()
        nativeApiInfo = "0x${info.toString(16)}"
        check(info == 0x0304) { "USB shim ABI mismatch: expected 0x0304, got $nativeApiInfo" }
        loadState = 1
        Log.i(TAG, "libusbiface loaded abi=${Build.CPU_ABI} asset=$loadedAsset api=$nativeApiInfo hash=$hash path=${file.absolutePath}")
    }

    private fun readAsset(context: Context): Pair<String, ByteArray> {
        val abi = (Build.CPU_ABI ?: "").toLowerCase(Locale.US)
        val candidates = when (abi) {
            "armeabi-v7a", "armeabi" -> listOf("native/armeabi-v7a/libusbiface.so", LEGACY_ARM_ASSET)
            "x86" -> listOf("native/x86/libusbiface.so")
            else -> throw IOException("Unsupported USB shim ABI: $abi")
        }
        var lastError: IOException? = null
        for (path in candidates) {
            try {
                val bytes = ByteArrayOutputStream().use { buffer ->
                    context.assets.open(path).use { input -> input.copyTo(buffer) }
                    buffer.toByteArray()
                }
                return path to bytes
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw IOException("USB shim asset missing for $abi", lastError)
    }

    private fun matches(file: File, expectedDigest: ByteArray): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().contentEquals(expectedDigest)
    }
}
