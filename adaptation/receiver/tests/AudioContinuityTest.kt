package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class AudioContinuityTest {
    private fun packet(frames: Int = 330) = ByteArray(12 + frames * 4)
    @Test fun musicBurstBudgetAcceptsMoreThanTheOld64ShortPackets() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        repeat(100) { assertTrue(q.offer(packet(), it * 330, 0, it.toLong())) }
        assertEquals(100, q.size); assertEquals(748L, q.queuedMillis)
        assertEquals(1000, q.budgetMillis)
    }
    @Test fun musicDurationBoundRejectsRatherThanGrowingWithoutLimit() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        repeat(133) { assertTrue(q.offer(packet(), it * 330, 0, it.toLong())) }
        assertFalse(q.offer(packet(), 133 * 330, 0, 133))
        assertEquals(995L, q.queuedMillis); assertTrue(q.queuedBytes < 256 * 1024)
        assertNotNull(q.poll(0)); assertTrue(q.offer(packet(), 133 * 330, 0, 134))
    }
    @Test fun otherAudioKeepsOriginal64PacketLimitAndNoAddedStartBuffer() {
        val q = LegacyAudioPacketQueue(false, true, 44100, 2, 1000)
        repeat(64) { assertTrue(q.offer(packet(), it * 330, 0, 0)) }
        assertFalse(q.offer(packet(), 64 * 330, 0, 0)); assertEquals(0, q.budgetMillis)
    }
    @Test fun queuedPacketOwnsOneCopyAndCloseRejectsLateOffers() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        val bytes = packet(); bytes[12] = 42
        assertTrue(q.offer(bytes, 99, 6, 7)); bytes[12] = 9
        val p = q.poll(0)!!
        assertEquals(42.toByte(), p.bytes[12]); assertEquals(99, p.sample); assertEquals(6, p.ssrc)
        assertEquals(0L, q.queuedBytes); q.close(); q.close()
        assertFalse(q.offer(packet(), 0, 0, 0)); assertNull(q.poll(0))
    }
    @Test fun malformedAndExcessivePacketsNeverEnterTheQueue() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        assertFalse(q.offer(ByteArray(12), 0, 0, 0)); assertFalse(q.offer(ByteArray(65537), 0, 0, 0))
        assertEquals(0, q.size)
    }
    @Test fun compressedMusicUsesTimestampBudgetAndHandlesWrap() {
        val q = LegacyAudioPacketQueue(true, false, 48000, 2, 300)
        assertTrue(q.offer(ByteArray(100), -1000, 0, 0))
        assertTrue(q.offer(ByteArray(100), 1000, 0, 0))
        assertFalse(q.offer(ByteArray(100), 48001, 0, 0))
        assertEquals(41L, q.queuedMillis)
    }
    @Test fun closeWakesABlockedConsumerWithoutRetainingPacket() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        val ready = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread { try { ready.countDown(); assertNull(q.poll(1000)) } catch (error: Throwable) { failure.set(error) } }
        thread.start(); ready.await(); q.close(); thread.join(500)
        assertFalse(thread.isAlive); assertNull(failure.get())
    }
    @Test fun latencySnapshotIsReadOnlyAndSeparatesQueuedDurationFromArrivalAge() {
        val q = LegacyAudioPacketQueue(true, true, 44100, 2, 300)
        assertTrue(q.offer(packet(), 99, 0, 1_000_000_000L))
        val backlog = q.backlog(1_500_000_000L)
        assertEquals(7L, backlog.queuedMillis); assertEquals(500L, backlog.oldestAgeMillis)
        assertEquals(1, q.size); assertEquals(99, q.poll(0)!!.sample)
    }
    @Test fun contiguousPcmIsUnchangedAndMissingOnePacketGetsExactSilenceLength() {
        val repair = PcmGapRepair(44100, 2)
        val first = ByteArray(1320) { if (it % 2 == 0) 42 else 0 }
        assertNull(repair.process(0, first.copyOf()).silence)
        val plain = first.copyOf()
        assertArrayEquals(plain, repair.process(330, plain.copyOf()).pcm)
        val gap = repair.process(990, first.copyOf())
        assertEquals(330L, gap.missingFrames); assertEquals(330, gap.concealedFrames)
        assertEquals(1320, gap.silence!!.size); assertFalse(gap.discontinuity)
        assertTrue(gap.silence!!.drop(88 * 4).all { it == 0.toByte() })
    }
    @Test fun timestampWrapRemainsContiguous() {
        val repair = PcmGapRepair(44100, 2)
        repair.process(-330, ByteArray(1320))
        val next = repair.process(0, ByteArray(1320))
        assertEquals(0L, next.missingFrames); assertNull(next.silence); assertFalse(next.discontinuity)
    }
    @Test fun hundredMillisecondBoundaryIsBoundedAndLargerGapsAllocateNoPadding() {
        val repair = PcmGapRepair(44100, 2)
        repair.process(0, ByteArray(1320))
        assertEquals(4410, repair.process(4740, ByteArray(1320)).concealedFrames)
        val huge = repair.process(5070 + 4411, ByteArray(1320))
        assertNull(huge.silence); assertEquals(0, huge.concealedFrames); assertTrue(huge.discontinuity)
        val backwards = repair.process(0, ByteArray(1320))
        assertNull(backwards.silence); assertEquals(0L, backwards.missingFrames); assertTrue(backwards.discontinuity)
    }
    @Test fun sourceRestartDoesNotConcealAnUnrelatedTimeline() {
        val repair = PcmGapRepair(44100, 2)
        repair.process(0, ByteArray(1320), 1)
        val restart = repair.process(660, ByteArray(1320), 2)
        assertNull(restart.silence); assertEquals(0L, restart.missingFrames)
    }
    @Test fun continuousShortHolesHaveAtMostHundredMillisecondsRepairPerRollingMediaSecond() {
        val repair = PcmGapRepair(8000, 1)
        repair.process(0, ByteArray(160)) // eighty PCM frames
        val accepted = ArrayList<Pair<Int, Int>>()
        var total = 0; var limited = 0
        for (index in 1..100) {
            val sample = index * 160 // eighty present frames followed by eighty missing frames
            val result = repair.process(sample, ByteArray(160))
            total += result.concealedFrames
            if (result.budgetLimited) {
                limited++; assertEquals(0, result.concealedFrames); assertNull(result.silence); assertTrue(result.discontinuity)
            }
            if (result.concealedFrames > 0) accepted.add(sample to result.concealedFrames)
            assertTrue(accepted.filter { sample - it.first < 8000 }.sumOf { it.second } <= 800)
        }
        assertEquals(1600, total); assertTrue(limited > 0)
        repair.process(16080, ByteArray(16000)) // a real second of contiguous PCM replenishes the budget
        assertEquals(80, repair.process(24160, ByteArray(160)).concealedFrames)
    }
    @Test fun burstDeliveryDoesNotResetRepairBudgetAndNewSourceExplicitlyDoes() {
        val repair = PcmGapRepair(8000, 1)
        repair.process(0, ByteArray(160), 1)
        val full = repair.process(880, ByteArray(160), 1)
        assertEquals(800, full.concealedFrames)
        val over = repair.process(1040, ByteArray(160), 1)
        assertTrue(over.budgetLimited); assertEquals(0, over.concealedFrames)
        repair.process(0, ByteArray(160), 2)
        val fresh = repair.process(160, ByteArray(160), 2)
        assertEquals(80, fresh.concealedFrames); assertFalse(fresh.budgetLimited)
    }
    @Test fun tinyHolesCannotGrowTheRepairLedgerWithoutBound() {
        val repair = PcmGapRepair(44100, 1)
        repair.process(0, ByteArray(2))
        var accepted = 0; var limited = 0
        for (index in 1..1000) {
            val result = repair.process(index * 2, ByteArray(2))
            accepted += result.concealedFrames; if (result.budgetLimited) limited++
        }
        assertEquals(128, accepted); assertEquals(872, limited)
    }
    @Test fun monoFadeIsFrameAlignedAndWithinSignedPcmRange() {
        val repair = PcmGapRepair(8000, 1)
        val loud = ByteArray(160) { if (it % 2 == 0) 0xff.toByte() else 0x7f.toByte() }
        repair.process(0, loud.copyOf())
        val gap = repair.process(160, loud.copyOf())
        assertEquals(80, gap.concealedFrames); assertEquals(160, gap.silence!!.size)
        assertArrayEquals(byteArrayOf(0, 0), gap.silence!!.copyOfRange(30, 32))
        assertTrue(gap.pcm[1].toInt() in 0..127)
    }
    @Test(expected = IllegalArgumentException::class) fun incompletePcmFrameIsRejected() {
        PcmGapRepair(44100, 2).process(0, ByteArray(3))
    }
    @Test fun sourceLossLocalDropLatePacketsAndWrapAreSeparate() {
        val d = AudioContinuityDiagnostics(44100, 4)
        assertTrue(d.arrived(65535, -330, 330, 0))
        assertTrue(d.arrived(0, 0, 330, 10_000_000))
        assertFalse(d.arrived(0, 0, 330, 11_000_000))
        assertFalse(d.arrived(65535, -330, 330, 12_000_000))
        assertTrue(d.arrived(3, 990, 330, 20_000_000)); d.dropped()
        val text = d.summary(5_000_000_000)
        assertTrue(text.contains("接收序号缺包=2")); assertTrue(text.contains("迟到或重复拒绝=2"))
        assertTrue(text.contains("本地队列拒绝=1")); assertTrue(text.contains("时间戳缺失帧=660"))
        assertTrue(text.contains("sourceMissing=2 localDrops=1"))
    }
    @Test fun ssrcChangeAcceptsANewSequenceWithoutBillionsOfMissingSamples() {
        val d = AudioContinuityDiagnostics(44100, 4)
        assertTrue(d.arrived(30000, 100000, 330, 0, 1))
        assertTrue(d.arrived(0, 0, 330, 1, 2))
        assertFalse(d.arrived(30001, 100330, 330, 2, 1))
        assertTrue(d.arrived(1, 330, 330, 3, 2))
        assertTrue(d.summary(2).contains("SSRC重启=1")); assertTrue(d.summary(2).contains("接收序号缺包=0"))
    }
    @Test fun intervalPeaksResetAndUnderrunEstimateDoesNotCountEveryEmptyPoll() {
        val d = AudioContinuityDiagnostics(44100, 4)
        d.arrived(0, 0, 330, 0); d.dequeued(100_000_000); d.wrote(1320, 50_000_000)
        d.output(true, 330, 0); d.output(true, 0, 0); d.waitedEmpty()
        d.repaired(0, true, true)
        val first = d.summary(5_000_000_000)
        assertTrue(first.contains("queueAgeMaxMs=100 writeBlockMaxMs=50"))
        assertTrue(first.contains("播放头估计断粮=1"))
        assertTrue(first.contains("修补预算不足次数=1"))
        val next = d.summary(10_000_000_000)
        assertTrue(next.contains("queueAgeMaxMs=0 writeBlockMaxMs=0"))
    }
}
