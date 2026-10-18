package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM ownership tests: synthetic metadata, no AudioTrack, AudioRecord, Android Log or sockets. */
class CarPlayMediaLifecycleTest {
    private class RecordingSink : MediaSink {
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        var failMicrophoneStop = false
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { events.add("audio-start:${id.type}:${id.audioType}:$firstSample") }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events.add("rtp:${id.type}:${id.audioType}:$sample") }
        override fun onAudioStopped(id: AudioStreamId) { events.add("audio-stop:${id.type}:${id.audioType}") }
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { events.add("mic-start:${id.type}:${id.audioType}") }
        override fun onMicrophoneStopped(id: AudioStreamId) {
            events.add("mic-stop:${id.type}:${id.audioType}")
            if (failMicrophoneStop) throw IllegalStateException("synthetic stop failure")
        }
    }
    private data class Registration(val key: CarPlayMediaEngine.StreamKey, val meta: Any, val microphone: MicrophoneConfig?)
    private class Fixture(val sink: RecordingSink = RecordingSink()) {
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        private val stateLock = field("audioStateLock")
        @Suppress("UNCHECKED_CAST") fun map(name: String): MutableMap<CarPlayMediaEngine.StreamKey, Any> = field(name) as MutableMap<CarPlayMediaEngine.StreamKey, Any>
        private fun field(name: String): Any = CarPlayMediaEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(engine)
        fun size(name: String): Int = (field(name) as Map<*, *>).size
        fun register(session: AirPlaySession, type: Int = 100, purpose: String = "telephony", microphone: Boolean = true,
            stream: Closeable? = Closeable {}): Registration {
            val key = CarPlayMediaEngine.StreamKey(session, type, purpose)
            val format = AudioFormat(AudioCodecKind.LPCM, 16000, 1, type, purpose)
            val metaType = CarPlayMediaEngine::class.java.declaredClasses.first { it.simpleName == "AudioMeta" }
            val constructor = metaType.declaredConstructors.first { it.parameterTypes.size == 6 }.apply { isAccessible = true }
            val meta = constructor.newInstance(type, format, 42L, 0, null, null)
            val config = if (microphone) MicrophoneConfig(purpose, 16000, 1, type, 20,
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 7001, ByteArray(32)) else null
            synchronized(stateLock) {
                map("audioMeta")[key] = meta
                if (config != null) map("pendingMicrophone")[key] = config
                if (stream != null) map("streams")[key] = stream
            }
            return Registration(key, meta, config)
        }
        fun start(registration: Registration, sample: Int = 1) {
            call("deliverAudioStarted", registration.key, registration.meta, sample, registration.microphone)
        }
        fun rtp(registration: Registration, sample: Int) { call("deliverAudioRtp", registration.key, registration.meta, byteArrayOf(1, 2), sample) }
        fun cleanupExpected(registration: Registration): Any? = call("cleanupAudioStreams", listOf(registration.key), registration.meta)
        private fun call(name: String, vararg arguments: Any?): Any? = try {
            CarPlayMediaEngine::class.java.declaredMethods.first { it.name == name }.apply { isAccessible = true }.invoke(engine, *arguments)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun sessionCloseStopsAudioAndMicrophoneOnceAndRemovesTheirOwners() {
        val f = Fixture(); val session = session(); val closed = AtomicInteger()
        try {
            val registration = f.register(session, stream = Closeable { closed.incrementAndGet() })
            f.start(registration); f.engine.onSessionClosed(session); f.engine.onSessionClosed(session)
            assertEquals(1, closed.get())
            assertEquals(1, f.sink.events.count { it == "mic-stop:100:telephony" })
            assertEquals(1, f.sink.events.count { it == "audio-stop:100:telephony" })
            for (name in listOf("streams", "audioMeta", "pendingMicrophone", "audioSinkOwners", "microphoneSinkOwners")) assertEquals(name, 0, f.size(name))
        } finally { session.close() }
    }

    @Test fun sessionCloseLeavesEveryOtherSessionMapEntryIntact() {
        val f = Fixture(); val old = session(); val current = session(); val currentClosed = AtomicInteger()
        try {
            val a = f.register(old, purpose = "telephony")
            val b = f.register(current, purpose = "speechrecognition", stream = Closeable { currentClosed.incrementAndGet() })
            f.start(a); f.start(b)
            // A map sentinel proves namespace preservation without creating a diagnostic media file.
            val captureSentinel = Any(); f.map("audioCaptures")[b.key] = captureSentinel
            f.engine.onSessionClosed(old)
            assertEquals(0, currentClosed.get())
            assertSame(b.meta, f.map("audioMeta")[b.key])
            assertSame(b.microphone, f.map("pendingMicrophone")[b.key])
            assertSame(captureSentinel, f.map("audioCaptures")[b.key])
            assertTrue(f.map("streams").containsKey(b.key))
            assertFalse(f.sink.events.contains("mic-stop:100:speechrecognition"))
            assertFalse(f.sink.events.contains("audio-stop:100:speechrecognition"))
            f.map("audioCaptures").remove(b.key)
            f.engine.onSessionClosed(current)
        } finally { f.map("audioCaptures").clear(); old.close(); current.close() }
    }

    @Test fun closingOldSessionCannotStopNewOwnerOfTheSameSinkId() {
        val f = Fixture(); val old = session(); val current = session()
        try {
            val a = f.register(old); val b = f.register(current)
            f.start(a, 10); f.start(b, 20); f.sink.events.clear()
            f.engine.onSessionClosed(old)
            assertTrue(f.sink.events.isEmpty())
            f.rtp(a, 11); f.rtp(b, 21)
            assertEquals(listOf("rtp:100:telephony:21"), f.sink.events.toList())
            assertSame(b.meta, f.map("audioMeta")[b.key])
            f.engine.onSessionClosed(current)
            assertEquals(1, f.sink.events.count { it == "mic-stop:100:telephony" })
            assertEquals(1, f.sink.events.count { it == "audio-stop:100:telephony" })
        } finally { old.close(); current.close() }
    }

    @Test fun oldTypeTeardownAlsoPreservesAnotherSessionsCurrentOwner() {
        val f = Fixture(); val old = session(); val current = session()
        try {
            val a = f.register(old); val b = f.register(current)
            f.start(a); f.start(b); f.sink.events.clear()
            f.engine.onTeardown(old, 100)
            assertTrue(f.sink.events.isEmpty())
            assertTrue(f.map("streams").containsKey(b.key))
            f.engine.onTeardown(current, 100)
            assertEquals(listOf("mic-stop:100:telephony", "audio-stop:100:telephony"), f.sink.events.toList())
        } finally { f.engine.onSessionClosed(old); f.engine.onSessionClosed(current); old.close(); current.close() }
    }

    @Test fun lateCallbacksAfterCloseNeverRestartRecordingOrDeliverRtp() {
        val f = Fixture(); val session = session()
        try {
            val a = f.register(session); f.start(a)
            f.engine.onSessionClosed(session); f.sink.events.clear()
            f.start(a, 2); f.rtp(a, 3)
            assertTrue(f.sink.events.isEmpty())
            // This returns before Android Log or any listener/socket work can execute.
            assertNull(f.engine.onAudio(session, 100, emptyMap()))
            assertEquals(0, f.size("audioSinkOwners")); assertEquals(0, f.size("microphoneSinkOwners"))
        } finally { session.close() }
    }

    @Test fun typeTeardownRemovesEveryPurposeButKeepsOtherTypes() {
        val f = Fixture(); val session = session()
        try {
            val media = f.register(session, purpose = "media", microphone = false)
            val navigation = f.register(session, purpose = "default", microphone = false)
            val phone = f.register(session)
            val otherType = f.register(session, type = 102, purpose = "media", microphone = false)
            listOf(media, navigation, phone, otherType).forEach { f.start(it) }
            f.sink.events.clear(); f.engine.onTeardown(session, 100)
            assertEquals(setOf(otherType.key), f.map("streams").keys)
            assertEquals(setOf(otherType.key), f.map("audioMeta").keys)
            assertTrue(f.map("pendingMicrophone").isEmpty())
            assertEquals(setOf("audio-stop:100:media", "audio-stop:100:default", "audio-stop:100:telephony"),
                f.sink.events.filter { it.startsWith("audio-stop") }.toSet())
            assertEquals(1, f.sink.events.count { it.startsWith("mic-stop") })
        } finally { f.engine.onSessionClosed(session); session.close() }
    }

    @Test fun blockedNetworkCloseHoldsNeitherStateNorSinkCallbackLock() {
        blockedCloseTakeover(sameSession = false)
    }

    @Test fun sameKeyReplacementDuringBlockedCloseKeepsNewGenerationAlive() {
        blockedCloseTakeover(sameSession = true)
    }

    private fun blockedCloseTakeover(sameSession: Boolean) {
        val f = Fixture(); val old = session(); val current = if (sameSession) old else session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var closeThread: Thread? = null
        try {
            val a = f.register(old, stream = Closeable { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
            f.start(a); f.sink.events.clear()
            closeThread = Thread {
                try { if (sameSession) f.engine.onTeardown(old, 100) else f.engine.onSessionClosed(old) }
                catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue("network close entered", entered.await(2, TimeUnit.SECONDS))
            val takeoverDone = CountDownLatch(1)
            val takeover = Thread {
                try {
                    val b = f.register(current)
                    f.start(b, 20); f.rtp(b, 21); f.start(a, 12); f.rtp(a, 13)
                } catch (error: Throwable) { failure.set(error) }
                finally { takeoverDone.countDown() }
            }.apply { isDaemon = true; start() }
            assertTrue("takeover must progress while old network close is blocked", takeoverDone.await(2, TimeUnit.SECONDS))
            takeover.join(100)
            release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("worker failed").apply { initCause(it) } }
            assertEquals(listOf("audio-start:100:telephony:20", "mic-start:100:telephony", "rtp:100:telephony:21"), f.sink.events.toList())
            assertEquals(1, f.size("audioSinkOwners")); assertEquals(1, f.size("microphoneSinkOwners"))
            f.engine.onSessionClosed(current)
        } finally { release.countDown(); closeThread?.join(1000); old.close(); if (!sameSession) current.close() }
    }

    @Test fun microphoneStopFailureStillStopsAudioAndClosesAllTransportStreams() {
        val f = Fixture(); val session = session(); val closed = AtomicInteger()
        try {
            val phone = f.register(session, stream = Closeable { closed.incrementAndGet() })
            val media = f.register(session, purpose = "media", microphone = false, stream = Closeable { closed.incrementAndGet() })
            f.start(phone); f.start(media); f.sink.failMicrophoneStop = true
            try { f.engine.onSessionClosed(session); fail("stop failure must remain visible") }
            catch (expected: IllegalStateException) { assertEquals("synthetic stop failure", expected.message) }
            assertEquals(2, closed.get())
            assertTrue(f.sink.events.contains("audio-stop:100:telephony"))
            assertTrue(f.sink.events.contains("audio-stop:100:media"))
            for (name in listOf("streams", "audioMeta", "pendingMicrophone", "audioSinkOwners", "microphoneSinkOwners")) assertEquals(name, 0, f.size(name))
            f.engine.onSessionClosed(session)
        } finally { session.close() }
    }

    @Test fun orphanMetadataWithoutPublishedNetworkStreamIsCleanedBySessionClose() {
        val f = Fixture(); val session = session()
        try {
            val orphan = f.register(session, stream = null)
            f.engine.onSessionClosed(session)
            assertFalse(f.map("audioMeta").containsKey(orphan.key))
            assertFalse(f.map("pendingMicrophone").containsKey(orphan.key))
            assertEquals(listOf("mic-stop:100:telephony", "audio-stop:100:telephony"), f.sink.events.toList())
        } finally { session.close() }
    }

    @Test fun delayedFailedSetupCleanupCannotRemoveReplacementMetadata() {
        val f = Fixture(); val session = session()
        try {
            val old = f.register(session)
            val replacement = f.register(session)
            f.start(replacement, 20); f.sink.events.clear()
            assertNull(f.cleanupExpected(old))
            assertSame(replacement.meta, f.map("audioMeta")[replacement.key])
            assertTrue(f.sink.events.isEmpty())
            f.rtp(replacement, 21)
            assertEquals(listOf("rtp:100:telephony:21"), f.sink.events.toList())
        } finally { f.engine.onSessionClosed(session); session.close() }
    }

    @Test fun feedbackOnlyContainsTheRequestingSessionsMetadata() {
        val f = Fixture(); val a = session(); val b = session(); val empty = session()
        try {
            // Leave firstSample absent: feedback only emits metadata, with no Android/NTP calls.
            f.register(a, type = 100, purpose = "media", microphone = false)
            f.register(b, type = 102, purpose = "media", microphone = false)
            @Suppress("UNCHECKED_CAST")
            val aStreams = f.engine.onFeedback(a)?.get("streams") as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val bStreams = f.engine.onFeedback(b)?.get("streams") as List<Map<String, Any?>>
            assertEquals(listOf(100), aStreams.map { it["type"] })
            assertEquals(listOf(102), bStreams.map { it["type"] })
            assertNull(f.engine.onFeedback(empty))
            f.engine.onSessionClosed(a)
            assertNull(f.engine.onFeedback(a))
            @Suppress("UNCHECKED_CAST")
            val after = f.engine.onFeedback(b)?.get("streams") as List<Map<String, Any?>>
            assertEquals(listOf(102), after.map { it["type"] })
        } finally {
            f.engine.onSessionClosed(a); f.engine.onSessionClosed(b); f.engine.onSessionClosed(empty)
            a.close(); b.close(); empty.close()
        }
    }

    private fun session(): AirPlaySession = AirPlaySession(
        socket = Socket(), config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01", sourceVersion = "1.0", main = AirPlayDisplayConfig(800, 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
    )
}
