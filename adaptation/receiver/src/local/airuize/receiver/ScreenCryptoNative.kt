package local.airuize.receiver

import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AudioFrameCrypto
import com.shilapi.xcertplay.airplay.AudioFrameAuthenticationException
import com.shilapi.xcertplay.airplay.ScreenFrameAuthenticationException
import com.shilapi.xcertplay.airplay.ScreenFrameCrypto
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Independent, optional screen-frame accelerator. It never replaces the working USB JNI shim. */
object ScreenCryptoNative {
    private const val TAG = "DiPlayScreenCrypto"
    private const val EXPECTED_API = 0x0104
    private const val MAX_ASSET_BYTES = 1024 * 1024
    private const val MAX_SEALED_BYTES = 8 * 1024 * 1024 + 16
    private const val MAX_AAD_BYTES = 1024 * 1024
    private const val PREFIX = "libscreencrypto-"
    @Volatile private var state = 0
    @Volatile private var assetName = "none"
    @Volatile private var selfTest = "not-run"
    @Volatile private var failure = "none"
    @Volatile private var audioSelfTest = "not-run"

    private object NativeAccelerator : ScreenFrameCrypto.Accelerator {
        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
            check(sealed.size <= MAX_SEALED_BYTES && aad.size <= MAX_AAD_BYTES) { "Screen accelerator input exceeds bound" }
            val output = ByteArray(sealed.size - 16)
            return when (val result = nativeOpen(key, nonce, aad, sealed, output)) {
                output.size -> output
                -1 -> throw ScreenFrameAuthenticationException()
                else -> throw IllegalStateException("Screen accelerator failed with code $result")
            }
        }
    }

    /** Separate audio state/counters and authentication exception; the same tested stateless JNI. */
    private object AudioNativeAccelerator : AudioFrameCrypto.Accelerator {
        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
            check(sealed.size <= 4096 && aad.size == 8) { "Audio accelerator input exceeds bound" }
            val output = ByteArray(sealed.size - 16)
            return when (val result = nativeOpen(key, nonce, aad, sealed, output)) {
                output.size -> output
                -1 -> throw AudioFrameAuthenticationException()
                else -> throw IllegalStateException("Audio accelerator failed with code $result")
            }
        }
    }

    /** Call on the connection worker before screen streams start, never on the UI thread. */
    @Synchronized fun ensureLoaded(context: Context): Boolean {
        if (state != 0) return state == 1
        try {
            val app = context.applicationContext
            val abi = (Build.CPU_ABI ?: "").toLowerCase(Locale.US)
            val directory = when (abi) {
                "armeabi-v7a", "armeabi" -> "armeabi-v7a"
                "x86" -> "x86"
                else -> throw IOException("Unsupported screen accelerator ABI")
            }
            assetName = "native/$directory/libscreencrypto.so"
            val bytes = ByteArrayOutputStream().use { output ->
                app.assets.open(assetName).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() <= MAX_ASSET_BYTES - count) { "Screen accelerator asset exceeds bound" }
                        output.write(buffer, 0, count)
                    }
                }
                output.toByteArray()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hash = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val cache = publishLibrary(app.filesDir, hash, digest, bytes)
            System.load(cache.absolutePath)
            check(nativeApiInfo() == EXPECTED_API) { "Screen accelerator ABI mismatch" }
            selfTest = "running"
            checkSelfTest()
            selfTest = "passed"
            ScreenFrameCrypto.install(NativeAccelerator)
            // Optional audio validation cannot turn a working screen accelerator off.
            try {
                audioSelfTest = "running"
                checkAudioSelfTest()
                AudioFrameCrypto.install(AudioNativeAccelerator)
                audioSelfTest = "passed"
            } catch (_: Exception) { audioSelfTest = "failed-bc-fallback"
            } catch (_: LinkageError) { audioSelfTest = "failed-bc-fallback" }
            state = 1
            Log.i(TAG, "screen accelerator loaded abi=$abi api=0x0104 hash=$hash selfTest=passed")
            return true
        } catch (error: Exception) {
            failed(error)
        } catch (error: LinkageError) {
            failed(error)
        }
        return false
    }

    fun diagnosticSnapshot(): String =
        "ScreenCryptoLoader state=${when (state) { 1 -> "loaded"; -1 -> "failed-bc-fallback"; else -> "not-attempted" }} " +
            "abi=${Build.CPU_ABI} asset=$assetName api=0x0104 selfTest=$selfTest error=$failure audioSelfTest=$audioSelfTest"

    /** Actual RTP uses eight AAD bytes and roughly 1320 PCM bytes, separate from screen vectors. */
    private fun checkAudioSelfTest() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        for (size in intArrayOf(0, 2, 660, 1320, 3528, 4060)) {
            val nonce = AirPlayCrypto.nonce64(size.toLong() + 0x104030201L)
            val aad = ByteArray(8) { (it * 11 + 9).toByte() }
            val plain = ByteArray(size) { (it * 13 + 5).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            check(AudioNativeAccelerator.open(key, nonce, aad, sealed).contentEquals(plain)) { "Audio accelerator self-test mismatch" }
            val rejected = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            var failed = false
            try { AudioNativeAccelerator.open(key, nonce, aad, rejected) } catch (_: AudioFrameAuthenticationException) { failed = true }
            check(failed) { "Audio accelerator authentication self-test failed" }
        }
    }

    private fun failed(error: Throwable) {
        failure = error.javaClass.simpleName
        if (selfTest == "running") selfTest = "failed"
        state = -1
        Log.w(TAG, "screen accelerator unavailable; BouncyCastle fallback: $failure selfTest=$selfTest")
    }

    /** Compare different AAD/payload boundaries and verify rejection never modifies output. */
    private fun checkSelfTest() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        for (size in intArrayOf(0, 1, 64, 257, 4097)) {
            val plain = ByteArray(size) { (it * 13 + 5).toByte() }
            val nonce = AirPlayCrypto.nonce64(size.toLong() + 0x102030405060708L)
            val aad = ByteArray(if (size == 0) 0 else 128) { (it * 11 + 9).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
            val output = ByteArray(size)
            check(nativeOpen(key, nonce, aad, sealed, output) == size && output.contentEquals(plain)) { "Screen accelerator self-test mismatch" }
            val rejected = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            assertRejectedUnchanged(key, nonce, aad, rejected, size)
            if (size == 257) {
                assertRejectedUnchanged(key.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }, nonce, aad, sealed, size)
                assertRejectedUnchanged(key, nonce.copyOf().apply { this[4] = (this[4].toInt() xor 1).toByte() }, aad, sealed, size)
                assertRejectedUnchanged(key, nonce, aad.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }, sealed, size)
                assertRejectedUnchanged(key, nonce, aad, sealed.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }, size)
            }
        }
    }

    private fun assertRejectedUnchanged(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray, size: Int) {
        val output = ByteArray(size) { 0x5a.toByte() }
        check(nativeOpen(key, nonce, aad, sealed, output) == -1 && output.all { it == 0x5a.toByte() }) { "Screen accelerator rejected-frame self-test failed" }
    }

    private fun publishLibrary(directory: File, hash: String, digest: ByteArray, bytes: ByteArray): File {
        val canonical = File(directory, "$PREFIX$hash.so")
        if (matches(canonical, digest)) {
            check(canonical.setReadable(true, true)) { "Screen accelerator cache unreadable" }
            canonical.setWritable(false, true)
            return canonical
        }
        // A path potentially mapped in another process is never overwritten or truncated.
        val target = if (canonical.exists()) File(directory, "$PREFIX$hash-${System.nanoTime()}.so") else canonical
        val temporary = File(directory, "$PREFIX$hash-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes) }
            check(temporary.setReadable(true, true) && temporary.setWritable(false, true)) { "Screen accelerator cache permission failure" }
            check(temporary.renameTo(target)) { "Screen accelerator cache publish failure" }
            check(matches(target, digest)) { "Screen accelerator cache hash mismatch" }
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun matches(file: File, expected: ByteArray): Boolean {
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
        return digest.digest().contentEquals(expected)
    }

    private external fun nativeApiInfo(): Int
    private external fun nativeOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray, output: ByteArray): Int
}
