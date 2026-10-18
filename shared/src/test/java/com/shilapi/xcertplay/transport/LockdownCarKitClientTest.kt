package com.shilapi.xcertplay.transport

import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Deterministic ownership checks; these do not simulate an actual iPhone TLS handshake. */
class LockdownCarKitClientTest {
    @Test fun startServiceTextRejectionClosesLockdownWithoutConnecting() {
        val fixture = Fixture(response("Error" to LockdownPlistValue.Text("InvalidService")))
        assertProtocolFailure(fixture, "StartService failed: InvalidService")
        assertEquals(0, fixture.connectCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun startServiceIntegerRejectionKeepsErrorString() {
        val fixture = Fixture(response(
            "Error" to LockdownPlistValue.Integer(7),
            "ErrorString" to LockdownPlistValue.Text("ServiceUnavailable"),
        ))
        assertProtocolFailure(fixture, "StartService failed: ServiceUnavailable")
        assertEquals(0, fixture.connectCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun malformedErrorClosesLockdown() {
        val fixture = Fixture(response("Error" to LockdownPlistValue.Boolean(true)))
        assertProtocolFailure(fixture, "Error was not text or integer")
        assertEquals(0, fixture.connectCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun missingOrWrongPortClosesLockdown() {
        for (reply in listOf(response(), response("Port" to LockdownPlistValue.Text("1234")))) {
            val fixture = Fixture(reply)
            assertProtocolFailure(fixture, "omitted integer Port")
            assertEquals(0, fixture.connectCount)
            assertEquals(1, fixture.lockdown.closeCount)
        }
    }

    @Test fun outOfRangePortsCloseLockdownWithoutConnecting() {
        for (port in listOf(-1L, 0L, 65536L, Long.MAX_VALUE)) {
            val fixture = Fixture(response("Port" to LockdownPlistValue.Integer(port)))
            assertProtocolFailure(fixture, "invalid Port")
            assertEquals(0, fixture.connectCount)
            assertEquals(1, fixture.lockdown.closeCount)
        }
    }

    @Test fun requestFailureClosesLockdownAndPreservesCause() {
        val fixture = Fixture()
        val failure = IOException("request failed")
        fixture.requestFailure = failure
        assertSame(failure, failureFrom { fixture.open() })
        assertEquals(0, fixture.connectCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun connectFailureClosesLockdownAndPreservesCause() {
        val fixture = Fixture()
        val failure = IOException("connect failed")
        fixture.connectFailure = failure
        assertSame(failure, failureFrom { fixture.open() })
        assertEquals(1, fixture.connectCount)
        assertEquals(0, fixture.tlsCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun tlsFailureBeforeTakingInputClosesBothOwners() {
        val fixture = Fixture(secureResponse())
        val failure = IOException("TLS callback failed before handshake")
        fixture.tlsFailure = failure
        assertSame(failure, failureFrom { fixture.open() })
        assertEquals(1, fixture.tlsCount)
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun tlsFailureAfterClosingInputDoesNotDoubleCloseRawStream() {
        val fixture = Fixture(secureResponse())
        val failure = IOException("TLS handshake failed")
        fixture.tlsFailure = failure
        fixture.tlsClosesInputOnFailure = true
        assertSame(failure, failureFrom { fixture.open() })
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun cleanupErrorsDoNotMaskOpeningFailureOrSkipLockdownCleanup() {
        val fixture = Fixture(secureResponse())
        val failure = IOException("original TLS failure")
        fixture.tlsFailure = failure
        fixture.raw.closeFailure = IOException("raw close failed")
        fixture.lockdown.closeFailure = IOException("lockdown close failed")
        assertSame(failure, failureFrom { fixture.open() })
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun plaintextServiceRetainsLockdownUntilCallerCloses() {
        val fixture = Fixture()
        val stream = fixture.open()
        assertEquals(listOf("request", "connect:1234"), fixture.events)
        assertEquals(0, fixture.lockdown.closeCount)
        assertEquals(0, fixture.raw.closeCount)
        assertEquals(0, fixture.tlsCount)
        stream.send(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.raw.sent)
        assertArrayEquals(byteArrayOf(7, 8), stream.recv(2, 321))
        assertEquals(2, fixture.raw.lastMaxBytes)
        assertEquals(321L, fixture.raw.lastTimeout)
        stream.close()
        stream.close()
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
        assertEquals(listOf("request", "connect:1234", "raw-close", "lockdown-close"), fixture.events)
    }

    @Test fun tlsServiceRetainsBothStreamsUntilCallerCloses() {
        val fixture = Fixture(secureResponse())
        val stream = fixture.open()
        assertEquals(listOf("request", "connect:1234", "tls"), fixture.events)
        assertEquals(0, fixture.raw.closeCount)
        assertEquals(0, fixture.tlsStream!!.closeCount)
        assertEquals(0, fixture.lockdown.closeCount)
        stream.send(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), fixture.raw.sent)
        assertArrayEquals(byteArrayOf(7, 8), stream.recv(2, 123))
        stream.close()
        stream.close()
        assertEquals(1, fixture.tlsStream!!.closeCount)
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
        assertEquals(listOf("request", "connect:1234", "tls", "tls-close", "raw-close", "lockdown-close"), fixture.events)
    }

    @Test fun successfulServiceCloseFailureStillClosesLockdownExactlyOnce() {
        val fixture = Fixture()
        val stream = fixture.open()
        val failure = IOException("raw close failed")
        fixture.raw.closeFailure = failure
        assertSame(failure, failureFrom { stream.close() })
        stream.close()
        assertEquals(1, fixture.raw.closeCount)
        assertEquals(1, fixture.lockdown.closeCount)
    }

    @Test fun validBoundaryPortsArePassedToConnectUnchanged() {
        for (port in listOf(1L, 65535L)) {
            val fixture = Fixture(response("Port" to LockdownPlistValue.Integer(port)))
            fixture.open().close()
            assertEquals(port.toInt(), fixture.connectedPort)
            assertEquals(1, fixture.lockdown.closeCount)
        }
    }

    private fun assertProtocolFailure(fixture: Fixture, message: String) {
        val failure = failureFrom { fixture.open() }
        assertTrue(failure is IphoneUsbException.Protocol)
        assertTrue(failure.message.orEmpty().contains(message))
    }

    private fun failureFrom(action: () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected opening or close failure")
    }

    private class Fixture(val reply: LockdownPlistValue.Dictionary = response("Port" to LockdownPlistValue.Integer(1234))) {
        val events = ArrayList<String>()
        val lockdown = FakeCloseable("lockdown", events)
        val raw = FakeStream(events)
        var requestFailure: Throwable? = null
        var connectFailure: Throwable? = null
        var tlsFailure: Throwable? = null
        var tlsClosesInputOnFailure = false
        var connectCount = 0
        var connectedPort = -1
        var tlsCount = 0
        var tlsStream: FakeTlsStream? = null

        fun open(): BlockingDuplexByteStream = LockdownServiceOpen.open(
            lockdown,
            request = {
                events.add("request")
                requestFailure?.let { throw it }
                reply
            },
            connect = { port ->
                events.add("connect:$port")
                connectCount++
                connectedPort = port
                connectFailure?.let { throw it }
                raw
            },
            openTls = { input ->
                events.add("tls")
                tlsCount++
                tlsFailure?.let {
                    if (tlsClosesInputOnFailure) input.close()
                    throw it
                }
                FakeTlsStream(input, events).also { tlsStream = it }
            },
        )
    }

    private class FakeCloseable(val name: String, val events: MutableList<String>) : Closeable {
        var closeCount = 0
        var closeFailure: Throwable? = null
        override fun close() {
            closeCount++
            events.add("$name-close")
            closeFailure?.let { throw it }
        }
    }

    private class FakeStream(val events: MutableList<String>) : BlockingDuplexByteStream {
        var closeCount = 0
        var closeFailure: Throwable? = null
        var sent = byteArrayOf()
        var lastMaxBytes = 0
        var lastTimeout = 0L
        override fun send(data: ByteArray) { sent = data.copyOf() }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray {
            lastMaxBytes = maxBytes
            lastTimeout = timeoutMillis
            return byteArrayOf(7, 8)
        }
        override fun close() {
            closeCount++
            events.add("raw-close")
            closeFailure?.let { throw it }
        }
    }

    private class FakeTlsStream(val input: BlockingDuplexByteStream, val events: MutableList<String>) : BlockingDuplexByteStream {
        var closeCount = 0
        override fun send(data: ByteArray) = input.send(data)
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = input.recv(maxBytes, timeoutMillis)
        override fun close() {
            closeCount++
            events.add("tls-close")
            input.close()
        }
    }

    private companion object {
        fun response(vararg values: Pair<String, LockdownPlistValue>) = LockdownPlistValue.Dictionary(linkedMapOf(*values))
        fun secureResponse() = response(
            "Port" to LockdownPlistValue.Integer(1234),
            "EnableServiceSSL" to LockdownPlistValue.Boolean(true),
        )
    }
}
