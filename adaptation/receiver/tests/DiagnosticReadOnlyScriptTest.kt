package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class DiagnosticReadOnlyScriptTest {
    @Test fun allOriginalNetworkSourcesRemainReadOnlyAndFinite() {
        val script=DiagnosticReadOnlyScript.networkPolicy("/private/cache/network")
        for(value in listOf("IPV6_POLICY_BEGIN","IPV6_POLICY_END","ip -6 rule show","ip -6 route show table all",
            "ip -6 neigh show","ip -6 addr show","/proc/sys/net/ipv6/conf/all/disable_ipv6",
            "/proc/sys/net/ipv6/conf/default/disable_ipv6","/proc/net/snmp6","ip6tables -n -v -L INPUT",
            "ip6tables -n -v -L OUTPUT","ip6tables -n -v -L FORWARD")) assertTrue(value,script.contains(value))
        assertFalse(script.contains("ip6tables -I"));assertFalse(script.contains("ip6tables -A"))
    }
    @Test fun allOriginalDeepSectionsRemainWithTraceablePackageManagerReplacement() {
        val script=DiagnosticReadOnlyScript.rootDeep("/private/cache/deep")
        val sections=listOf("ID","SVC_CHECK","SVC_LIST","PS","PM_PKGS","D_USB","D_CONN","D_AF","D_AP",
            "D_AUDIO","D_WINDOW","IP_RULE","USB_DEBUG","USB_ALT_STATE","INPUT_DEV","KEYLAYOUT","DMESG","LOG_CRASH","LOG_MAIN")
        assertEquals(sections,Regex("@@@SECTION:([A-Z_]+)@@@").findAll(script).map {it.groupValues[1]}.toList())
        assertTrue(script.contains("ROOT_BEGIN_9f3a"));assertTrue(script.contains("ROOT_END_9f3a"))
        assertTrue(script.contains("PackageManager.getInstalledPackages(0)"))
        assertTrue(script.contains(DiagnosticReadOnlyScript.PACKAGE_PLACEHOLDER))
        assertFalse(script.contains("pm list packages"))
        assertTrue(script.contains("diag_read \"\$i/bAlternateSetting\""))
        assertTrue(script.contains("diag_run getevent -lp"))
        assertTrue(script.contains("diag_run logcat -d -b crash -v time"))
        assertTrue(script.contains("diag_run logcat -d -v time"))
        assertFalse(script.contains("\$(")) // sysfs reads do not create a different command-owner PPid.
    }
    @Test fun cancellationRequiresPrivatePidParentAndStartIdentityAndReapsBeforeDone() {
        for(script in listOf(DiagnosticReadOnlyScript.networkPolicy("/cache/a"),DiagnosticReadOnlyScript.rootDeep("/cache/b"))) {
            assertTrue(script.contains("[ \"\$diag_seen_ppid\" = \"\$diag_owner\" ] && [ \"\$diag_seen_start\" = \"\$diag_kill_start\" ]"))
            assertTrue(script.contains("/proc/\$1/stat"))
            assertTrue(script.contains("trap - EXIT HUP INT TERM"))
            assertTrue(script.contains("[ ! -e \"\$diag_stop\" ] || { echo DIAG_CANCELLED_BEFORE_START"))
            val cleanup=script.substringAfter("diag_cleanup() {").substringBefore("trap 'diag_cleanup' EXIT")
            assertTrue(cleanup.indexOf("wait \"\$diag_child\"")<cleanup.indexOf("DIAG_CLEANUP=COMPLETE"))
            assertTrue(cleanup.indexOf("wait \"\$diag_watcher\"")<cleanup.indexOf("DIAG_CLEANUP=COMPLETE"))
            assertFalse(script.contains("killall"));assertFalse(script.contains("pkill"));assertFalse(script.contains("logcat -c"))
            assertTrue(script.contains("DIAG_RUN exit=\$diag_rc command=\$1"))
            assertTrue(script.contains("exit \"\$diag_failed\""))
            assertTrue(script.contains("kill -9 \"\$diag_kill_pid\""))
            assertTrue(script.substringAfter("sleep 1\n  diag_identity").contains("\$diag_seen_start\" = \"\$diag_kill_start"))
        }
    }
    @Test fun ownedPrivatePathIsQuoted() {
        val script=DiagnosticReadOnlyScript.networkPolicy("/cache/private ' ; unsafe")
        assertTrue(script.contains("diag_base='/cache/private '\\'' ; unsafe'"))
    }
}
