package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class DiagnosticKeyCaptureScriptTest {
    @Test fun productionDefaultKeepsTheFullFortyTwoSecondCapture() {
        val script=DiagnosticKeyCaptureScript.build("/private/cache/owned-keys")
        assertTrue(script.contains("\"\${seconds}\" -lt 42") || script.contains("\"\$seconds\" -lt 42"))
        assertTrue(script.contains("getevent -lt -c 4"))
        for(marker in listOf("ROOT_KEY_CAPTURE_BEGIN","ROOT_KEY_CAPTURE_END","HSAE_HMI_CAPTURE_BEGIN","HSAE_HMI_LOGCAT_EXIT=","KEY_EVENT_LINES="))
            assertTrue(marker,script.contains(marker))
    }
    @Test fun finiteRuntimeWindowIsExplicitAndDoesNotChangeProductionDefault() {
        assertTrue(DiagnosticKeyCaptureScript.build("/private/cache/keys",2).contains("\"\$seconds\" -lt 2"))
        assertTrue(DiagnosticKeyCaptureScript.build("/private/cache/keys").contains("\"\$seconds\" -lt 42"))
        for(value in listOf(0,43)) try {DiagnosticKeyCaptureScript.build("/private/cache/keys",value);fail("Invalid capture window")}
            catch(_:IllegalArgumentException) {}
    }
    @Test fun onlyTheOwnedLoopAndChildAreStoppedAndSubshellCannotDeleteTheReport() {
        val script=DiagnosticKeyCaptureScript.build("/private/cache/keys")
        assertTrue(script.contains("trap 'cleanup_keys' EXIT"))
        assertTrue(script.contains("trap - EXIT HUP INT TERM"))
        assertTrue(script.contains("[ ! -e \"\$stop\" ] || exit 1"))
        assertTrue(script.contains("[ \"\$key_ppid\" = \"\$key_signal_parent\" ] && [ \"\$key_start\" = \"\$key_signal_start\" ]"))
        assertTrue(script.contains("key_signal_owned \"\$child\" \"\$child_start\" \"\$loop\""))
        assertTrue(script.contains("if [ -e \"\$stop\" ]; then key_signal_owned \"\$child\" \"\$child_start\" \"\$key_loop_pid\"; fi"))
        assertTrue(script.contains("kill -9 \"\$key_signal_pid\""))
        assertTrue(script.contains("KEY_CLEANUP=COMPLETE geteventLoopReaped=true"))
        assertTrue(script.contains("read -r key_loop_pid key_self_rest < /proc/self/stat"))
        assertTrue(script.contains("[ \"\$key_ppid\" = \"\$key_loop_pid\" ]"))
        assertFalse(script.contains("child_parent="))
        assertTrue(script.contains("[ \"\$key_loop_rc\" -ne 0 ] || key_loop_reaped=true"))
        assertTrue(script.contains("KEY_CLEANUP=INCOMPLETE geteventLoopReaped=false"))
        assertTrue(script.contains("if [ \"\$key_loop_reaped\" = true ]; then"))
        assertFalse(script.contains("killall"));assertFalse(script.contains("pkill"));assertFalse(script.contains("logcat -c"))
        assertTrue(script.contains("batches\" -ge 160"));assertTrue(script.contains("n\" -ge 320"))
    }
    @Test fun privatePathIsQuotedRatherThanInsertedAsShellCode() {
        val script=DiagnosticKeyCaptureScript.build("/private/cache/key ' ; echo bad")
        assertTrue(script.contains("base='/private/cache/key '\\'' ; echo bad'"))
    }
}
