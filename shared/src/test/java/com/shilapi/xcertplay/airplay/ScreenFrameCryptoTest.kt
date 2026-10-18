package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class ScreenFrameCryptoTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val nonce = AirPlayCrypto.nonce64(0x0102030405060708L)
    private val header = ByteArray(128) { (it * 3).toByte() }
    private val plain = ByteArray(257) { (it * 7).toByte() }
    private fun sealed() = AirPlayCrypto.chachaSeal(key, nonce, plain, header)
    private fun backend(open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray) =
        object : ScreenFrameCrypto.Accelerator {
            override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = open.invoke(key, nonce, aad, sealed)
        }

    @Test fun absentAcceleratorUsesProvenBcAndPreservesInputs() {
        val input = sealed()
        val original = input.copyOf()
        val engine = ScreenFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        assertArrayEquals(plain, engine.open(key, nonce, header, input))
        assertArrayEquals(original, input)
        assertTrue(engine.diagnosticSnapshot().contains("engine=bouncycastle"))
        assertTrue(engine.diagnosticSnapshot().contains("fallbackFrames=1"))
    }

    @Test fun nativeSuccessUsesIdenticalNonceAadAndSealedBytes() {
        val input = sealed()
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> fail("Unexpected BC fallback"); ByteArray(0) }
        engine.install(backend { k, n, a, s ->
            assertSame(key, k); assertSame(nonce, n); assertSame(header, a); assertSame(input, s)
            plain.copyOf()
        })
        assertArrayEquals(plain, engine.open(key, nonce, header, input))
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=1"))
        assertTrue(engine.diagnosticSnapshot().contains("nativeBytes=257"))
    }

    @Test fun missingJniSymbolFallsBackAndDisablesOnlyOnce() {
        var nativeCalls = 0; var fallbackCalls = 0
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> fallbackCalls++; plain }
        engine.install(backend { _, _, _, _ -> nativeCalls++; throw UnsatisfiedLinkError("synthetic") })
        repeat(2) { assertSame(plain, engine.open(key, nonce, header, sealed())) }
        assertEquals(1, nativeCalls); assertEquals(2, fallbackCalls)
        assertTrue(engine.diagnosticSnapshot().contains("backendFailures=1"))
        assertTrue(engine.diagnosticSnapshot().contains("lastFailure=UnsatisfiedLinkError"))
    }

    @Test fun internalBackendFailureFallsBackForTheSameAuthenticatedFrame() {
        val engine = ScreenFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        engine.install(backend { _, _, _, _ -> throw IllegalStateException("synthetic") })
        assertArrayEquals(plain, engine.open(key, nonce, header, sealed()))
        assertTrue(engine.diagnosticSnapshot().contains("engine=bouncycastle"))
    }

    @Test fun authenticationRejectionNeverRunsFallbackOrDeliversPlaintext() {
        var fallbackCalls = 0
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> fallbackCalls++; plain }
        engine.install(backend { _, _, _, _ -> throw ScreenFrameAuthenticationException() })
        repeat(2) {
            try { engine.open(key, nonce, header, sealed()); fail("Expected tag rejection") }
            catch (_: ScreenFrameAuthenticationException) {}
        }
        assertEquals(0, fallbackCalls)
        assertTrue(engine.diagnosticSnapshot().contains("authRejected=2"))
        assertTrue(engine.diagnosticSnapshot().contains("engine=native-rfc8439"))
    }

    @Test fun wrongResultSizeDisablesBackendAndFallsBack() {
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> plain }
        engine.install(backend { _, _, _, _ -> ByteArray(plain.size + 1) })
        assertSame(plain, engine.open(key, nonce, header, sealed()))
        assertTrue(engine.diagnosticSnapshot().contains("backendFailures=1"))
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=0"))
    }

    @Test fun invalidLengthsFailBeforeInvokingEitherBackend() {
        var calls = 0
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> calls++; plain }
        engine.install(backend { _, _, _, _ -> calls++; plain })
        fun rejected(k: ByteArray, n: ByteArray, s: ByteArray) {
            try { engine.open(k, n, header, s); fail("Expected invalid input rejection") }
            catch (_: IllegalArgumentException) {}
        }
        rejected(ByteArray(31), nonce, sealed())
        rejected(ByteArray(33), nonce, sealed())
        rejected(key, ByteArray(11), sealed())
        rejected(key, ByteArray(13), sealed())
        rejected(key, nonce, ByteArray(15))
        assertEquals(0, calls)
    }

    @Test fun emptyAuthenticatedFrameIsAValidNativeResult() {
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> fail("Unexpected fallback"); ByteArray(0) }
        engine.install(backend { _, _, _, s -> assertEquals(16, s.size); ByteArray(0) })
        assertEquals(0, engine.open(key, nonce, header, AirPlayCrypto.chachaSeal(key, nonce, ByteArray(0), header)).size)
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=1"))
    }

    @Test fun failureOfOldBackendCannotDisableNewlyInstalledBackend() {
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> plain }
        val replacement = backend { _, _, _, _ -> plain }
        engine.install(backend { _, _, _, _ -> engine.install(replacement); throw IllegalStateException("old") })
        assertSame(plain, engine.open(key, nonce, header, sealed()))
        assertSame(plain, engine.open(key, nonce, header, sealed()))
        assertTrue(engine.diagnosticSnapshot().contains("engine=native-rfc8439"))
        assertTrue(engine.diagnosticSnapshot().contains("nativeFrames=1"))
    }

    @Test fun fallbackStillAuthenticatesAllHeaderAndPayloadBytes() {
        val engine = ScreenFrameCryptoEngine { k, n, a, s -> AirPlayCrypto.chachaOpen(k, n, s, a) }
        val input = sealed()
        val badHeader = header.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val badInput = input.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        fun rejected(aad: ByteArray, data: ByteArray) {
            try { engine.open(key, nonce, aad, data); fail("Expected BC authentication failure") }
            catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {}
        }
        rejected(badHeader, input); rejected(header, badInput)
    }

    @Test fun diagnosticSummaryContainsOnlyCountsAndErrorClass() {
        val secret = "DO_NOT_LOG_PRIVATE_MEDIA"
        val engine = ScreenFrameCryptoEngine { _, _, _, _ -> plain }
        engine.install(backend { _, _, _, _ -> throw IllegalStateException(secret) })
        engine.open(key, nonce, header, sealed())
        assertFalse(engine.diagnosticSnapshot().contains(secret))
        assertTrue(engine.diagnosticSnapshot().contains("lastFailure=IllegalStateException"))
    }
}
