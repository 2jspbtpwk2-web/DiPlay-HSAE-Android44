package com.shilapi.xcertplay.airplay

import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Failed RTP authentication must never become playable PCM or trigger another authentication path. */
class AudioFrameAuthenticationException : GeneralSecurityException("Audio frame authentication failed")

/** Optional audio-only RFC8439 accelerator. The RTP caller owns all nonce/AAD/key construction. */
object AudioFrameCrypto {
    interface Accelerator {
        @Throws(AudioFrameAuthenticationException::class)
        fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray
    }
    private val engine = AudioFrameCryptoEngine { key, nonce, aad, sealed ->
        AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
    }
    fun install(accelerator: Accelerator) { engine.install(accelerator) }
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
        engine.open(key, nonce, aad, sealed)
    fun diagnosticSnapshot(): String = engine.diagnosticSnapshot()
}

/** Independent from screen counters and fallback state; no payload or crypto material is retained. */
internal class AudioFrameCryptoEngine(private val fallback: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray) {
    private val accelerator = AtomicReference<AudioFrameCrypto.Accelerator?>()
    private val nativeCalls = AtomicLong()
    private val nativeFrames = AtomicLong()
    private val nativeBytes = AtomicLong()
    private val fallbackFrames = AtomicLong()
    private val authRejected = AtomicLong()
    private val backendFailures = AtomicLong()
    private val cryptoTotalNs = AtomicLong()
    private val cryptoMaxNs = AtomicLong()
    @Volatile private var lastFailure = "none"
    fun install(value: AudioFrameCrypto.Accelerator) { accelerator.set(value) }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12 && aad.size == 8 && sealed.size >= 16) { "Invalid audio frame lengths" }
        val start = System.nanoTime()
        try { return decrypt(key, nonce, aad, sealed) }
        finally {
            val elapsed = (System.nanoTime() - start).coerceAtLeast(0)
            cryptoTotalNs.addAndGet(elapsed)
            var previous = cryptoMaxNs.get()
            while (elapsed > previous && !cryptoMaxNs.compareAndSet(previous, elapsed)) previous = cryptoMaxNs.get()
        }
    }

    private fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        val backend = accelerator.get()
        if (backend != null) {
            nativeCalls.incrementAndGet()
            try {
                val plain = backend.open(key, nonce, aad, sealed)
                check(plain.size == sealed.size - 16) { "Invalid audio accelerator result length" }
                nativeFrames.incrementAndGet(); nativeBytes.addAndGet(plain.size.toLong())
                return plain
            } catch (rejected: AudioFrameAuthenticationException) {
                authRejected.incrementAndGet(); throw rejected
            } catch (unavailable: LinkageError) { disable(backend, unavailable.javaClass.simpleName)
            } catch (failed: RuntimeException) { disable(backend, failed.javaClass.simpleName) }
        }
        fallbackFrames.incrementAndGet()
        return fallback(key, nonce, aad, sealed)
    }

    private fun disable(backend: AudioFrameCrypto.Accelerator, reason: String) {
        backendFailures.incrementAndGet(); lastFailure = reason.take(64)
        accelerator.compareAndSet(backend, null)
    }
    fun diagnosticSnapshot(): String =
        "AudioCrypto engine=${if (accelerator.get() == null) "bouncycastle" else "native-rfc8439"} nativeCalls=${nativeCalls.get()} " +
            "nativeFrames=${nativeFrames.get()} nativeBytes=${nativeBytes.get()} fallbackFrames=${fallbackFrames.get()} " +
            "authRejected=${authRejected.get()} backendFailures=${backendFailures.get()} lastFailure=$lastFailure " +
            "cryptoTotalUs=${cryptoTotalNs.get() / 1000L} cryptoMaxUs=${cryptoMaxNs.get() / 1000L}（进程累计wall时间）"
}
