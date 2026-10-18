package com.shilapi.xcertplay.airplay

import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** An authenticated frame was rejected. It must never become a codec input or a fallback success. */
class ScreenFrameAuthenticationException : GeneralSecurityException("Screen frame authentication failed")

/**
 * Optional screen-only accelerator. Pairing, control, timing, audio and microphone keep their
 * existing cryptographic implementation and nonce ownership. No key or media data is retained.
 */
object ScreenFrameCrypto {
    interface Accelerator {
        /** Return plaintext only after validating the complete Poly1305 tag. */
        @Throws(ScreenFrameAuthenticationException::class)
        fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray
    }

    private val engine = ScreenFrameCryptoEngine { key, nonce, aad, sealed ->
        AirPlayCrypto.chachaOpen(key, nonce, sealed, aad)
    }

    fun install(accelerator: Accelerator) { engine.install(accelerator) }
    fun open(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        engine.open(key, AirPlayCrypto.nonce64(counter), header, body)
    fun diagnosticSnapshot(): String = engine.diagnosticSnapshot()
    data class Counters(val nativeFrames: Long, val fallbackFrames: Long,
        val authRejected: Long, val backendFailures: Long)
    fun counters(): Counters = engine.counters()
}

/** Separate instance for deterministic boundary tests without changing the process-wide backend. */
internal class ScreenFrameCryptoEngine(
    private val fallback: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
) {
    private val accelerator = AtomicReference<ScreenFrameCrypto.Accelerator?>()
    private val nativeCalls = AtomicLong()
    private val nativeFrames = AtomicLong()
    private val nativeBytes = AtomicLong()
    private val fallbackFrames = AtomicLong()
    private val authRejected = AtomicLong()
    private val backendFailures = AtomicLong()
    @Volatile private var lastFailure = "none"

    fun install(value: ScreenFrameCrypto.Accelerator) { accelerator.set(value) }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        require(key.size == 32) { "Invalid screen key length" }
        require(nonce.size == 12) { "Invalid screen nonce length" }
        require(sealed.size >= ScreenCodec.TAG_SIZE) { "Missing screen authentication tag" }
        val backend = accelerator.get()
        if (backend != null) {
            nativeCalls.incrementAndGet()
            try {
                val plaintext = backend.open(key, nonce, aad, sealed)
                check(plaintext.size == sealed.size - ScreenCodec.TAG_SIZE) { "Invalid accelerator result length" }
                nativeFrames.incrementAndGet()
                nativeBytes.addAndGet(plaintext.size.toLong())
                return plaintext
            } catch (rejected: ScreenFrameAuthenticationException) {
                authRejected.incrementAndGet()
                throw rejected
            } catch (unavailable: LinkageError) {
                disable(backend, unavailable.javaClass.simpleName)
            } catch (failed: RuntimeException) {
                // A JNI/backend implementation failure keeps the proven BC decoder available.
                // Authentication failure uses the separate exception above and cannot enter here.
                disable(backend, failed.javaClass.simpleName)
            }
        }
        fallbackFrames.incrementAndGet()
        return fallback(key, nonce, aad, sealed)
    }

    private fun disable(value: ScreenFrameCrypto.Accelerator, reason: String) {
        backendFailures.incrementAndGet()
        lastFailure = reason.take(64)
        accelerator.compareAndSet(value, null)
    }

    fun counters(): ScreenFrameCrypto.Counters = ScreenFrameCrypto.Counters(
        nativeFrames.get(), fallbackFrames.get(), authRejected.get(), backendFailures.get())

    fun diagnosticSnapshot(): String =
        "ScreenCrypto engine=${if (accelerator.get() == null) "bouncycastle" else "native-rfc8439"} " +
            "nativeCalls=${nativeCalls.get()} nativeFrames=${nativeFrames.get()} nativeBytes=${nativeBytes.get()} " +
            "fallbackFrames=${fallbackFrames.get()} authRejected=${authRejected.get()} " +
            "backendFailures=${backendFailures.get()} lastFailure=$lastFailure"
}
