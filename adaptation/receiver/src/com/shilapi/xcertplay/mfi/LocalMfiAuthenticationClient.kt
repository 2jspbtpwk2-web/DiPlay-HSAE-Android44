package com.shilapi.xcertplay.mfi

import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory

/** API18: use lightweight P-256 primitives instead of installing a modern JCA provider. */
class LocalMfiAuthenticationClient private constructor(private val key: ECPrivateKeyParameters,
    private val certificate: ByteArray, private val onSignature: (Int) -> Unit) : MfiAuthenticator {
    override fun protocolMajor() = 3
    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        require(maximumOutputLength > 0 && certificate.size <= maximumOutputLength)
        return certificate.copyOf()
    }
    override fun signChallenge(challenge: ByteArray): ByteArray {
        require(challenge.size == 32)
        val signer = ECDSASigner()
        signer.init(true, ParametersWithRandom(key, SecureRandom()))
        val values = signer.generateSignature(challenge)
        onSignature(challenge.size)
        return fixed(values[0]) + fixed(values[1])
    }
    companion object {
        const val DIRECTORY = "offline-mfi"
        private fun read(file: File): ByteArray = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val size = input.read(buffer); if (size < 0) break
                require(output.size() + size <= 16384); output.write(buffer, 0, size)
            }
            output.toByteArray().also { require(it.isNotEmpty()) }
        }
        fun load(directory: File, onSignature: (Int) -> Unit = {}): LocalMfiAuthenticationClient {
            require(directory.isDirectory)
            val bytes = read(File(directory, "identity.pk8"))
            val key = try { PrivateKeyFactory.createKey(bytes) as ECPrivateKeyParameters } finally { bytes.fill(0) }
            require(key.parameters.n.toString(16) == "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")
            val certificate = read(File(directory, "certificate.p7b"))
            val certificates = CertificateFactory.getInstance("X.509").generateCertificates(certificate.inputStream())
            require(certificates.size == 1)
            val publicKey = PublicKeyFactory.createKey(certificates.single().publicKey.encoded) as ECPublicKeyParameters
            require(publicKey.parameters.n == key.parameters.n)
            val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val signer = ECDSASigner(); signer.init(true, ParametersWithRandom(key, SecureRandom()))
            val signature = signer.generateSignature(challenge)
            val verifier = ECDSASigner(); verifier.init(false, publicKey)
            require(verifier.verifySignature(challenge, signature[0], signature[1])) { "Local identity key/certificate mismatch" }
            return LocalMfiAuthenticationClient(key, certificate, onSignature)
        }
        private fun fixed(value: BigInteger): ByteArray {
            require(value.signum() > 0 && value.bitLength() <= 256)
            val bytes = value.toByteArray(); val offset = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
            val count = bytes.size - offset
            return ByteArray(32).also { bytes.copyInto(it, 32 - count, offset) }
        }
    }
}
