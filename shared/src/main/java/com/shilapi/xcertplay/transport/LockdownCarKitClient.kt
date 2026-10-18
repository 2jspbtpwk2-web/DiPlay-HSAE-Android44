package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking minimum Lockdown path that opens the `com.apple.carkit.service` byte stream.
 *
 * Connect and plist request/response budgets are short (the phone answers Lockdown locally over
 * USB), but the two TLS handshakes get a separate, generous budget: on a slow dual-core Cortex-A9
 * head unit running Dalvik, the first lightweight-TLS handshake also loads and verifies hundreds of
 * BouncyCastle classes, so a five-second budget can expire even on a healthy phone. The returned
 * stream is owned by the caller. This client never closes its caller-owned [Iap2UsbMuxHost],
 * persists a record, sends iAP2, or performs UI work.
 */
class LockdownCarKitClient(
    private val host: Iap2UsbMuxHost,
    private val onStep: (String) -> Unit = {},
) {
    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairedRecord: PairedRecord, label: String): BlockingDuplexByteStream =
        open(pairedRecord.pairRecord, label)

    @Throws(IphoneUsbException::class, GeneralSecurityException::class)
    fun open(pairRecord: LockdownPairRecord, label: String): BlockingDuplexByteStream =
        openService(pairRecord, label, CARKIT_SERVICE)

    fun openService(pairRecord: LockdownPairRecord, label: String, serviceName: String): BlockingDuplexByteStream {
        require(label.isNotBlank()) { "label must not be blank" }
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val lockdownConnection = host.connect(
            destinationPort = Iap2UsbMuxHost.LOCKDOWN_PORT,
            timeoutMillis = STEP_TIMEOUT_MILLIS,
        )
        val sessionTls = LockdownPlistChannel(lockdownConnection).use { plaintext ->
            val response = plaintext.request(
                LockdownPlistValue.Dictionary(
                    linkedMapOf(
                        "Label" to LockdownPlistValue.Text(label),
                        "Request" to LockdownPlistValue.Text("StartSession"),
                        "HostID" to LockdownPlistValue.Text(pairRecord.hostId),
                        "SystemBUID" to LockdownPlistValue.Text(pairRecord.systemBuid),
                    ),
                ),
                STEP_TIMEOUT_MILLIS,
            )
            LockdownServiceOpen.rejectError(response, "StartSession")
            val enableSessionSsl = (response.entries["EnableSessionSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: throw IphoneUsbException.Protocol("StartSession response omitted boolean EnableSessionSSL")
            if (!enableSessionSsl) {
                throw IphoneUsbException.Protocol("StartSession did not enable TLS")
            }
            val tls1Start = System.nanoTime()
            onStep("TLS#1(StartSession) handshake begin timeout=$HANDSHAKE_TIMEOUT_MILLIS")
            TlsDuplexChannel.open(
                underlying = plaintext.detach(),
                pairRecord = pairRecord,
                handshakeTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS,
            ).also { ready ->
                try {
                    onStep("TLS#1(StartSession) handshake done in ${(System.nanoTime() - tls1Start) / 1_000_000L}ms")
                } catch (error: Throwable) {
                    runCatching { ready.close() }
                    throw error
                }
            }
        }

        val secureLockdown = LockdownPlistChannel(sessionTls)
        return LockdownServiceOpen.open(
            lockdown = secureLockdown,
            request = {
                secureLockdown.request(
                    LockdownPlistValue.Dictionary(
                        linkedMapOf(
                            "Request" to LockdownPlistValue.Text("StartService"),
                            "Service" to LockdownPlistValue.Text(serviceName),
                        ),
                    ),
                    STEP_TIMEOUT_MILLIS,
                )
            },
            connect = { port ->
                host.connect(destinationPort = port, timeoutMillis = STEP_TIMEOUT_MILLIS)
            },
            openTls = { connection ->
                val tls2Start = System.nanoTime()
                onStep("TLS#2(StartService) handshake begin timeout=$HANDSHAKE_TIMEOUT_MILLIS")
                TlsDuplexChannel.open(
                    underlying = connection,
                    pairRecord = pairRecord,
                    handshakeTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS,
                ).also { ready ->
                    try {
                        onStep("TLS#2(StartService) handshake done in ${(System.nanoTime() - tls2Start) / 1_000_000L}ms")
                    } catch (error: Throwable) {
                        runCatching { ready.close() }
                        throw error
                    }
                }
            }
        )
    }

    private companion object {
        // Local USB Lockdown plist/connect round-trips answer quickly; modest headroom for jitter.
        const val STEP_TIMEOUT_MILLIS = 8_000L
        // Two full TLS 1.2 handshakes (ECDHE + RSA) plus first-run BouncyCastle class loading on a
        // slow A9/Dalvik head unit. Generous on purpose; a healthy handshake finishes far sooner and
        // only a genuinely stalled phone waits the full budget.
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
        const val CARKIT_SERVICE = "com.apple.carkit.service"
    }
}

/** Owns the already-secured Lockdown channel until a returned service takes both streams. */
internal object LockdownServiceOpen {
    fun open(
        lockdown: Closeable,
        request: () -> LockdownPlistValue.Dictionary,
        connect: (Int) -> BlockingDuplexByteStream,
        openTls: (BlockingDuplexByteStream) -> BlockingDuplexByteStream,
    ): BlockingDuplexByteStream {
        var service: BlockingDuplexByteStream? = null
        var transferred = false
        try {
            val response = request()
            rejectError(response, "StartService")
            val port = (response.entries["Port"] as? LockdownPlistValue.Integer)?.value
                ?: throw IphoneUsbException.Protocol("StartService response omitted integer Port")
            if (port !in 1..0xffff) {
                throw IphoneUsbException.Protocol("StartService returned an invalid Port")
            }
            val enableSsl = (response.entries["EnableServiceSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: false
            // TLS.open owns and closes its input when negotiation fails. Retain an idempotent
            // owner as well, so a failure before TLS takes ownership also closes the raw stream.
            val connection = CloseOnceStream(connect(port.toInt()))
            service = connection
            val ready = if (enableSsl) openTls(connection).also { service = it } else connection
            val result = CarkitServiceStream(ready, lockdown)
            transferred = true
            return result
        } finally {
            if (!transferred) {
                // Preserve the opening failure even if a best-effort teardown also throws.
                runCatching { service?.close() }
                runCatching { lockdown.close() }
            }
        }
    }

    private class CloseOnceStream(private val underlying: BlockingDuplexByteStream) : BlockingDuplexByteStream {
        private val closed = AtomicBoolean(false)
        override fun send(data: ByteArray) = underlying.send(data)
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = underlying.recv(maxBytes, timeoutMillis)
        override fun close() { if (closed.compareAndSet(false, true)) underlying.close() }
    }

    /** Keeps the Lockdown session that created carkit alive for the service stream's lifetime. */
    private class CarkitServiceStream(
        private val service: BlockingDuplexByteStream,
        private val lockdown: Closeable,
    ) : BlockingDuplexByteStream {
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) = service.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            service.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                service.close()
            } finally {
                lockdown.close()
            }
        }
    }

    fun rejectError(response: LockdownPlistValue.Dictionary, request: String) {
        val code = when (val error = response.entries["Error"] ?: return) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (response.entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw IphoneUsbException.Protocol("$request response Error was not text or integer")
        }
        throw IphoneUsbException.Protocol("Lockdown $request failed: $code")
    }
}
