package com.shilapi.xcertplay.transport

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.DefaultTlsCredentialedSigner
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientContext
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcTlsRSASigner

/**
 * Blocking plaintext stream carried by a client-mode TLS session over one owned byte stream.
 *
 * On Android 4.4 (API 18/19) the OEM JSSE SSLEngine lacks TLS 1.2 and the BouncyCastle high-level
 * JSSE provider (SSLEngine) hard-depends on javax.net.ssl types that only exist on API 24+
 * (X509ExtendedTrustManager, SNIHostName, ExtendedSSLSession), so it fails at class-load time. This
 * channel instead uses BouncyCastle's lightweight TLS API (TlsClientProtocol + BcTlsCrypto), which
 * never touches javax.net.ssl and performs every cipher/MAC/AEAD operation with the bundled
 * BouncyCastle crypto core (no dependence on the head unit's stripped JCE, no Cipher.updateAAD).
 *
 * [open] completes the handshake before returning. Calls must run on worker threads. This class
 * does not send Lockdown StartSession/StartService messages and does not persist records.
 */
class TlsDuplexChannel private constructor(
    private val underlying: BlockingDuplexByteStream,
) : BlockingDuplexByteStream {
    private val readLock = Any()
    private val writeLock = Any()
    private val stateLock = Any()

    // Bound for the transport's next read. Null while idle; set during handshake and each recv().
    @Volatile
    private var readDeadlineNanos: Long? = null
    private var closed = false
    private var failure: IphoneUsbException? = null

    private lateinit var protocol: TlsClientProtocol
    private lateinit var plainInput: InputStream
    private lateinit var plainOutput: OutputStream

    private val transportInput = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (true) {
                val deadline = readDeadlineNanos
                val remaining = if (deadline != null) deadline - System.nanoTime()
                else DEFAULT_READ_TIMEOUT_MS * NANOS_PER_MILLISECOND
                if (deadline != null && remaining <= 0) {
                    throw InterruptedIOException("TLS read timeout")
                }
                val waitMillis = (remaining / NANOS_PER_MILLISECOND).coerceIn(1L, MAXIMUM_TRANSPORT_READ_MS)
                val chunk = underlying.recv(minOf(length, RECEIVE_CHUNK_BYTES), waitMillis)
                when {
                    chunk == null -> {
                        // A bounded timeout expired. Inside a handshake/recv window that is a hard
                        // timeout; with no active window simply keep waiting for the next record.
                        if (deadline != null) throw InterruptedIOException("TLS read timeout")
                    }
                    chunk.isEmpty() -> return -1 // peer ended its output
                    else -> {
                        val count = minOf(length, chunk.size)
                        System.arraycopy(chunk, 0, buffer, offset, count)
                        return count
                    }
                }
            }
        }
    }

    private val transportOutput = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length > 0) {
                val out = ByteArray(length)
                System.arraycopy(buffer, offset, out, 0, length)
                underlying.send(out)
            }
        }
        override fun flush() {}
    }

    override fun send(data: ByteArray) = synchronized(writeLock) {
        checkWritable()
        if (data.isEmpty()) return@synchronized
        try {
            plainOutput.write(data)
            plainOutput.flush()
        } catch (error: IOException) {
            throw fail(error)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(stateLock) {
            failure?.let { throw it }
            if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
        }
        val buffer = ByteArray(maxBytes)
        readDeadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        try {
            var count = plainInput.read(buffer)
            while (count == 0) count = plainInput.read(buffer)
            when {
                count < 0 -> ByteArray(0)
                else -> buffer.copyOf(count)
            }
        } catch (error: InterruptedIOException) {
            null // bounded timeout expired, matching BlockingDuplexByteStream's contract
        } catch (error: IOException) {
            throw fail(error)
        } finally {
            readDeadlineNanos = null
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        try {
            if (this::protocol.isInitialized) protocol.close()
        } catch (_: Exception) {
            // close_notify is best effort
        } finally {
            try {
                underlying.close()
            } catch (_: Exception) {
                // local state stays closed
            }
        }
    }

    private fun checkWritable() = synchronized(stateLock) {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("TLS channel is closed")
    }

    private fun fail(error: IOException): IphoneUsbException {
        val mapped = IphoneUsbException.Protocol(
            "TLS channel failed (${error.javaClass.simpleName}: ${error.message})",
        )
        val shouldClose = synchronized(stateLock) {
            if (failure == null) failure = mapped
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) {
            try {
                underlying.close()
            } catch (_: Exception) {
                // preserve the original failure
            }
        }
        return synchronized(stateLock) { failure ?: mapped }
    }

    private fun handshake(identity: LockdownIdentity, handshakeTimeoutMillis: Long) {
        val handshakeStartedNanos = System.nanoTime()
        val crypto = BcTlsCrypto(SecureRandom())
        val chain = org.bouncycastle.tls.Certificate(
            arrayOf(BcTlsCertificate(crypto, identity.certificateDer)),
        )
        lateinit var tlsContext: TlsClientContext
        val client = object : DefaultTlsClient(crypto) {
            override fun init(context: TlsClientContext) {
                super.init(context)
                tlsContext = context
            }

            override fun getProtocolVersions(): Array<ProtocolVersion> = SUPPORTED_PROTOCOLS

            override fun getCipherSuites(): IntArray = RSA_CIPHER_SUITES

            override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                    // The Lockdown channel authenticates the pairing, not a public PKI. Accept the
                    // device certificate unconditionally, matching the previous trust-all engine.
                }

                override fun getClientCredentials(request: CertificateRequest): TlsCredentials {
                    val algorithm = pickRsaSignatureAlgorithm(crypto, request)
                    return DefaultTlsCredentialedSigner(
                        TlsCryptoParameters(tlsContext),
                        BcTlsRSASigner(crypto, identity.privateKey, identity.publicKey),
                        chain,
                        algorithm,
                    )
                }
            }
        }

        protocol = TlsClientProtocol(transportInput, transportOutput)
        readDeadlineNanos = System.nanoTime() + handshakeTimeoutMillis * NANOS_PER_MILLISECOND
        try {
            protocol.connect(client)
        } finally {
            readDeadlineNanos = null
        }
        plainInput = protocol.inputStream
        plainOutput = protocol.outputStream
        val cipher = tlsContext.securityParameters.cipherSuite
        val handshakeMillis = (System.nanoTime() - handshakeStartedNanos) / NANOS_PER_MILLISECOND
        Log.i(
            "xcertplay-usb",
            "Lockdown TLS negotiated ${tlsContext.serverVersion} cipher=0x${cipher.toString(16)} handshakeMs=$handshakeMillis",
        )
    }

    private class LockdownIdentity(
        val privateKey: RSAKeyParameters,
        val publicKey: RSAKeyParameters,
        val certificateDer: ByteArray,
    )

    companion object {
        private const val RECEIVE_CHUNK_BYTES = 16 * 1024
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
        private const val MAXIMUM_TRANSPORT_READ_MS = 60_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private val SUPPORTED_PROTOCOLS = arrayOf(ProtocolVersion.TLSv12)

        // RSA-compatible TLS 1.2 suites, modern AEAD first (GCM/ChaCha20, both run on the bundled
        // BouncyCastle crypto core without Cipher.updateAAD), CBC kept as a fallback for devices
        // whose iPhone Lockdown negotiates the classic suites. Static RSA kex is last.
        private val RSA_CIPHER_SUITES = intArrayOf(
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        )

        private val preloadStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Loads and verifies the BouncyCastle lightweight-TLS classes off the connection path.
         *
         * On a slow Dalvik head unit the first handshake otherwise pays a one-time cost (hundreds of
         * classes loaded/verified + JIT warm-up) that can rival the crypto itself. Call this once on
         * a background thread at app start; by the time the user plugs the phone in the classes are
         * warm and both handshakes run hot. Safe to call repeatedly; failures are swallowed because
         * preloading is purely an optimisation and the handshake still works without it.
         */
        @JvmStatic
        fun preloadInBackground() {
            if (!preloadStarted.compareAndSet(false, true)) return
            val thread = Thread({ preload() }, "bc-tls-preload")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY
            thread.start()
        }

        /** Synchronously forces class loading/verification of the handshake's hot classes. */
        @JvmStatic
        fun preload() {
            val loader = TlsDuplexChannel::class.java.classLoader
            val classes = arrayOf(
                "org.bouncycastle.tls.TlsClientProtocol",
                "org.bouncycastle.tls.DefaultTlsClient",
                "org.bouncycastle.tls.TlsUtils",
                "org.bouncycastle.tls.crypto.impl.TlsAEADCipher",
                "org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto",
                "org.bouncycastle.tls.crypto.impl.bc.BcTlsRSASigner",
                "org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate",
                "org.bouncycastle.crypto.util.PrivateKeyFactory",
                "org.bouncycastle.crypto.util.PublicKeyFactory",
                "org.bouncycastle.crypto.modes.ChaCha20Poly1305",
                "org.bouncycastle.crypto.modes.GCMBlockCipher",
                "org.bouncycastle.crypto.engines.AESEngine",
                "org.bouncycastle.crypto.engines.ChaCha7539Engine",
                "org.bouncycastle.asn1.bsi.BSIObjectIdentifiers",
                "org.bouncycastle.asn1.cms.GCMParameters",
                "org.bouncycastle.asn1.eac.EACObjectIdentifiers",
                "org.bouncycastle.asn1.edec.EdECObjectIdentifiers",
                "org.bouncycastle.asn1.oiw.OIWObjectIdentifiers",
                "org.bouncycastle.asn1.rosstandart.RosstandartObjectIdentifiers",
            )
            for (name in classes) {
                try {
                    Class.forName(name, true, loader)
                } catch (_: Throwable) {
                    // Optional/renamed class: the handshake loads exactly what it needs anyway.
                }
            }
            try {
                BcTlsCrypto(java.security.SecureRandom())
                Log.i("xcertplay-usb", "BouncyCastle lightweight TLS preloaded")
            } catch (error: Throwable) {
                Log.w("xcertplay-usb", "BouncyCastle TLS preload skipped: ${error.javaClass.simpleName}")
            }
        }

        /** Creates and fully handshakes a TLS client stream, taking ownership of [underlying]. */
        @JvmStatic
        @Throws(IphoneUsbException::class, GeneralSecurityException::class)
        fun open(
            underlying: BlockingDuplexByteStream,
            pairRecord: LockdownPairRecord,
            handshakeTimeoutMillis: Long = 30_000,
        ): TlsDuplexChannel {
            require(handshakeTimeoutMillis > 0) { "handshakeTimeoutMillis must be positive" }
            val channel = TlsDuplexChannel(underlying)
            try {
                channel.handshake(loadIdentity(pairRecord), handshakeTimeoutMillis)
                return channel
            } catch (error: Throwable) {
                try {
                    underlying.close()
                } catch (_: Exception) {
                    // preserve the handshake failure
                }
                when (error) {
                    is IphoneUsbException -> throw error
                    is GeneralSecurityException -> throw error
                    is IOException -> throw IphoneUsbException.Protocol(
                        "Lockdown TLS handshake failed (${error.javaClass.simpleName}: ${error.message})",
                    )
                    else -> throw IphoneUsbException.Protocol(
                        "Lockdown TLS fatal (${error.javaClass.simpleName}: ${error.message})",
                    )
                }
            }
        }

        private fun loadIdentity(pairRecord: LockdownPairRecord): LockdownIdentity {
            val pkcs8Der = decodePem(pairRecord.rootPrivateKeyPem, "PRIVATE KEY")
            val certificateDer = decodePem(pairRecord.rootCertificatePem, "CERTIFICATE")
            val privateKey = PrivateKeyFactory.createKey(pkcs8Der) as RSAKeyParameters
            val asn1Certificate = org.bouncycastle.asn1.x509.Certificate.getInstance(certificateDer)
            val subjectPublicKeyInfo = asn1Certificate.getTBSCertificate().getSubjectPublicKeyInfo()
            val publicKey = PublicKeyFactory.createKey(subjectPublicKeyInfo) as RSAKeyParameters
            return LockdownIdentity(privateKey, publicKey, certificateDer)
        }

        private fun decodePem(pem: ByteArray, label: String): ByteArray {
            val text = String(pem).trim()
            val begin = "-----BEGIN $label-----"
            val end = "-----END $label-----"
            val start = text.indexOf(begin)
            val finish = text.indexOf(end, start + begin.length)
            if (start < 0 || finish < 0) {
                throw GeneralSecurityException("Invalid PEM $label")
            }
            val body = text.substring(start + begin.length, finish)
                .filter { it != '\r' && it != '\n' && it != ' ' && it != '\t' }
            return Base64.decode(body, Base64.DEFAULT)
        }

        private fun pickRsaSignatureAlgorithm(
            crypto: BcTlsCrypto,
            request: CertificateRequest,
        ): SignatureAndHashAlgorithm? {
            val algorithms = request.supportedSignatureAlgorithms ?: return null
            val preferredHashes = intArrayOf(
                HashAlgorithm.sha256.toInt(),
                HashAlgorithm.sha384.toInt(),
                HashAlgorithm.sha512.toInt(),
                HashAlgorithm.sha1.toInt(),
            )
            for (hash in preferredHashes) {
                val elements = algorithms.elements()
                while (elements.hasMoreElements()) {
                    val candidate = elements.nextElement()
                    if (candidate is SignatureAndHashAlgorithm &&
                        candidate.signature.toInt() == SignatureAlgorithm.rsa.toInt() &&
                        candidate.hash.toInt() == hash &&
                        crypto.hasSignatureAndHashAlgorithm(candidate)
                    ) {
                        return candidate
                    }
                }
            }
            return null
        }
    }
}
