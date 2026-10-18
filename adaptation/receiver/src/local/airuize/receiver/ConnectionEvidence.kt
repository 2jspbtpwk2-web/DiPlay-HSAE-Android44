package local.airuize.receiver

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque

/** Bounded per-attempt records survive reconnect and normal log-ring rotation. */
class ConnectionEvidence {
    private val records = ArrayDeque<String>()
    private var characters = 0
    private var dropped = 0
    @Synchronized fun add(attempt: Int, phase: String, text: String) {
        val record = "attempt=$attempt phase=$phase capturedMono=${SystemClock.elapsedRealtime()}\n${text.take(10000)}"
        records.addLast(record); characters += record.length
        while (records.size > 96 || characters > 160000) {
            characters -= records.removeFirst().length; dropped++
        }
    }
    @Synchronized fun snapshot(): String = "连接证据归档：records=${records.size} olderRecordsDropped=$dropped；各记录保留连接代次。\n" +
        records.joinToString("\n---\n")
}

/** No subprocess or ROOT on the transport callbacks; the service invokes this on its own worker. */
object ConnectionRuntimeProbe {
    fun read(path: String, limit: Int = 16000): String = try {
        File(path).bufferedReader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(limit + 1)
            var size = 0
            while (size < chars.size) { val count=reader.read(chars, size, chars.size-size); if(count<0) break; size+=count }
            String(chars, 0, minOf(size, limit)) + if(size>limit) "\n[读取达到${limit}字符上限]" else ""
        }
    } catch(error: Exception) { "[不可读取:${error.javaClass.simpleName}]" }

    /** Parse only numeric socket metadata; omit addresses, inodes and unrelated app sockets. */
    fun sockets(table: String, uid: Int): String {
        val result = ArrayList<String>()
        for(line in table.lineSequence().drop(1)) {
            val f=line.trim().split(Regex("\\s+"))
            if(f.size<10) continue
            val owner=f[7].toIntOrNull() ?: continue
            if(owner!=uid) continue
            val local=f[1].substringAfterLast(':').toIntOrNull(16) ?: continue
            val peer=f[2].substringAfterLast(':').toIntOrNull(16) ?: continue
            val state=f[3].takeIf { it.matches(Regex("[0-9A-Fa-f]{2}")) } ?: continue
            val queues=f[4].takeIf { it.matches(Regex("[0-9A-Fa-f]{8}:[0-9A-Fa-f]{8}")) } ?: "unavailable"
            result.add("localPort=$local peerPort=$peer stateHex=$state txQueue:rxQueue=$queues")
            if(result.size==48) { result.add("[socket记录达到48条上限]"); break }
        }
        return if(result.isEmpty()) "[本应用无匹配记录，或表不可读/已截断]" else result.joinToString("\n")
    }

    fun snapshot(): String {
        val runtime=Runtime.getRuntime()
        val out=StringBuilder("采样mono=${SystemClock.elapsedRealtime()} processCpuMs=${Process.getElapsedCpuTime()} " +
            "javaUsed=${runtime.totalMemory()-runtime.freeMemory()} javaMax=${runtime.maxMemory()} nativeAllocated=${Debug.getNativeHeapAllocatedSize()}\n")
        val status=read("/proc/self/status", 8000)
        out.append(status.lineSequence().filter { it.startsWith("VmRSS:") || it.startsWith("Threads:") || it.startsWith("FDSize:") }.joinToString("\n"))
        out.append("\nopenFdCount=${runCatching { File("/proc/self/fd").list()?.size }.getOrNull() ?: -1}\n")
        for(name in listOf("tcp6", "udp6", "tcp", "udp")) {
            val table=read("/proc/net/$name", 65536)
            out.append("SOCKET_$name readTruncated=${table.contains("[读取达到")}\n${sockets(table, Process.myUid())}\n")
        }
        out.append("TUN IPv6 addresses:\n" + read("/proc/net/if_inet6").lineSequence().filter { it.substringAfterLast(' ').startsWith("tun") }.joinToString("\n"))
        out.append("\nTUN IPv6 routes:\n" + read("/proc/net/ipv6_route").lineSequence().filter { it.trim().substringAfterLast(' ').startsWith("tun") }.take(24).joinToString("\n"))
        File("/sys/class/net").listFiles()?.filter { it.name.matches(Regex("tun[0-9]+")) }?.take(4)?.forEach { iface ->
            out.append("\n${iface.name} mtu=${read("${iface.path}/mtu",32).trim()} state=${read("${iface.path}/operstate",32).trim()}")
            for(field in listOf("rx_packets","tx_packets","rx_errors","tx_errors","rx_dropped","tx_dropped"))
                out.append(" $field=${read("${iface.path}/statistics/$field",32).trim()}")
        }
        out.append("\n温度(固件原始单位):")
        File("/sys/class/thermal").listFiles()?.filter { it.name.matches(Regex("thermal_zone[0-9]+")) }?.take(4)?.forEach {
            out.append(" ${it.name}=${read("${it.path}/temp",32).trim()}")
        }
        return out.toString().take(10000)
    }
}
