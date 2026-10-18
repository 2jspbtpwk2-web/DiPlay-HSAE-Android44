package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class RootCommandBufferTest {
    private fun ShellOutputBuffer.add(text: String) { append(text.toCharArray(), text.length) }
    @Test fun markerAndStatusMayBeSplitAcrossReads() {
        val buffer = ShellOutputBuffer(256)
        buffer.add("@@S\nhello\n@@E")
        assertNull(buffer.complete("@@S", "@@E"))
        buffer.add(":0")
        assertNull(buffer.complete("@@S", "@@E"))
        buffer.add("\n")
        val result = buffer.complete("@@S", "@@E")!!
        assertEquals("hello\n", result.text)
        assertEquals(0, result.exitCode)
    }
    @Test fun oversizedOutputStillKeepsCompletionAndReportsTruncation() {
        val buffer = ShellOutputBuffer(256)
        buffer.add("@@S\n" + "a".repeat(2048) + "LATEST\n@@E:17\n")
        val result = buffer.complete("@@S", "@@E")!!
        assertEquals(17, result.exitCode)
        assertTrue(result.text.contains("[ROOT output truncated; discardedChars="))
        assertTrue(result.text.endsWith("LATEST\n"))
        assertTrue(result.text.length <= 256)
    }
    @Test fun incrementalOutputAndSnapshotsStayBounded() {
        val buffer = ShellOutputBuffer(256)
        repeat(500) { buffer.add("0123456789abcdef") }
        val text = buffer.snapshot()
        assertTrue(text.length <= 256)
        assertTrue(text.contains("discardedChars=7744"))
        assertTrue(text.endsWith("89abcdef"))
    }
    @Test fun clearSeparatesCommandsAndResetsTruncation() {
        val buffer = ShellOutputBuffer(256)
        buffer.add("old".repeat(200)); buffer.clear()
        buffer.add("@@S\nnew\n@@E:0\n")
        assertEquals("new\n", buffer.complete("@@S", "@@E")!!.text)
    }
    @Test fun completionConsumesStatusLineButPreservesFollowingOutput() {
        val buffer = ShellOutputBuffer(256)
        buffer.add("@@S\r\nbody\r\n@@E:3\r\nfollowing")
        val result = buffer.complete("@@S", "@@E")!!
        assertEquals(3, result.exitCode)
        assertEquals("body\r\n", result.text)
        assertEquals("following", buffer.snapshot())
    }
    @Test fun wrongMarkerOrMalformedStatusIsNotACompletion() {
        val buffer = ShellOutputBuffer(256)
        buffer.add("@@S\n@@OTHER:0\n")
        assertNull(buffer.complete("@@S", "@@E"))
        buffer.add("@@E:not-a-status\n")
        assertNull(buffer.complete("@@S", "@@E"))
    }
    @Test fun noisyReportPreservesRootAndSectionHeadersAndLatestTail() {
        val buffer = ShellOutputBuffer(1024)
        val headers = "ROOT_BEGIN_9f3a\n### SVC\nfirst service evidence\n### USB\nUSB evidence\n### NETWORK\n"
        buffer.add("@@S\n" + headers)
        repeat(100) { buffer.add("filler middle details ".repeat(4)) }
        buffer.add("LATEST_TAIL\nROOT_END_9f3a\n@@E:0\n")
        val result = buffer.complete("@@S", "@@E")!!
        assertEquals(0, result.exitCode)
        assertTrue(result.text.length <= 1024)
        assertTrue(result.text.startsWith(headers))
        assertTrue(result.text.contains("ROOT_BEGIN_9f3a"))
        assertTrue(result.text.contains("### SVC"))
        assertTrue(result.text.contains("### USB"))
        assertTrue(result.text.contains("### NETWORK"))
        assertTrue(result.text.contains("output truncated"))
        assertTrue(result.text.endsWith("LATEST_TAIL\nROOT_END_9f3a\n"))
    }
}

