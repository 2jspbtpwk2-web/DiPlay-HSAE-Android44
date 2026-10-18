package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioFrameCryptoTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val nonce = AirPlayCrypto.nonce64(0x0102030405060708L)
    private val aad = ByteArray(8) { (it * 3).toByte() }
    private val plain = ByteArray(1320) { (it * 7).toByte() }
    private fun sealed() = AirPlayCrypto.chachaSeal(key, nonce, plain, aad)
    private fun backend(action: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray) = object : AudioFrameCrypto.Accelerator {
        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = action(key, nonce, aad, sealed)
    }
    @Test fun absentBackendUsesOriginalAuthenticatedBc() {
        val engine = AudioFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        assertArrayEquals(plain, engine.open(key, nonce, aad, sealed()))
        assertTrue(engine.diagnosticSnapshot().contains("fallbackFrames=1"))
    }
    @Test fun backendReceivesIdenticalRtpCryptoInputs() {
        val engine = AudioFrameCryptoEngine { _, _, _, _ -> fail("Unexpected fallback"); plain }
        val sealed = sealed(); val copy = sealed.copyOf()
        engine.install(backend { k, n, a, s -> assertSame(key, k); assertSame(nonce, n); assertSame(aad, a); assertSame(sealed, s); plain.copyOf() })
        assertArrayEquals(plain, engine.open(key, nonce, aad, sealed)); assertArrayEquals(copy, sealed)
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=1 nativeBytes=1320"))
    }
    @Test fun badTagNeverFallsBackAndBackendRemainsEnabled() {
        var fallbacks = 0
        val engine = AudioFrameCryptoEngine { _, _, _, _ -> fallbacks++; plain }
        engine.install(backend { _, _, _, _ -> throw AudioFrameAuthenticationException() })
        repeat(2) { try { engine.open(key, nonce, aad, sealed()); fail("Expected rejection") } catch (_: AudioFrameAuthenticationException) {} }
        assertEquals(0, fallbacks); assertTrue(engine.diagnosticSnapshot().contains("authRejected=2"))
        assertTrue(engine.diagnosticSnapshot().contains("engine=native-rfc8439"))
    }
    @Test fun missingBackendUsesBcForCurrentFrameAndDisablesOnce() {
        var calls = 0
        val engine = AudioFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        engine.install(backend { _, _, _, _ -> calls++; throw UnsatisfiedLinkError("private-details") })
        repeat(2) { assertArrayEquals(plain, engine.open(key, nonce, aad, sealed())) }
        assertEquals(1, calls); assertTrue(engine.diagnosticSnapshot().contains("backendFailures=1"))
        assertFalse(engine.diagnosticSnapshot().contains("private-details"))
    }
    @Test fun wrongResultSizeIsARejectedBackendWithAuthenticatedFallback() {
        val engine = AudioFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        engine.install(backend { _, _, _, _ -> ByteArray(1) })
        assertArrayEquals(plain, engine.open(key, nonce, aad, sealed()))
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=0"))
        assertTrue(engine.diagnosticSnapshot().contains("backendFailures=1"))
    }
    @Test fun invalidRtpLengthsNeverInvokeBackendOrBc() {
        var calls = 0
        val engine = AudioFrameCryptoEngine { _, _, _, _ -> calls++; plain }
        engine.install(backend { _, _, _, _ -> calls++; plain })
        for (values in listOf(arrayOf(ByteArray(31), nonce, aad, sealed()), arrayOf(key, ByteArray(11), aad, sealed()),
            arrayOf(key, nonce, ByteArray(7), sealed()), arrayOf(key, nonce, aad, ByteArray(15)))) {
            try { engine.open(values[0], values[1], values[2], values[3]); fail("Expected input rejection") } catch (_: IllegalArgumentException) {}
        }
        assertEquals(0, calls)
    }
    @Test fun fallbackRejectsModifiedAadCiphertextNonceAndTag() {
        val engine = AudioFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        for (kind in 0..3) {
            val n = nonce.copyOf(); val a = aad.copyOf(); val s = sealed()
            when (kind) { 0 -> a[0] = (a[0].toInt() xor 1).toByte(); 1 -> s[0] = (s[0].toInt() xor 1).toByte()
                2 -> n[4] = (n[4].toInt() xor 1).toByte(); else -> s[s.lastIndex] = (s.last().toInt() xor 1).toByte() }
            try { engine.open(key, n, a, s); fail("Expected BC authentication failure") } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {}
        }
    }
    @Test fun failureOfOldBackendCannotDisableNewReplacement() {
        val engine = AudioFrameCryptoEngine { _, _, _, _ -> plain }
        val next = backend { _, _, _, _ -> plain }
        engine.install(backend { _, _, _, _ -> engine.install(next); throw IllegalStateException("old") })
        engine.open(key, nonce, aad, sealed()); engine.open(key, nonce, aad, sealed())
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=1"))
        assertTrue(engine.diagnosticSnapshot().contains("engine=native-rfc8439"))
    }
}
