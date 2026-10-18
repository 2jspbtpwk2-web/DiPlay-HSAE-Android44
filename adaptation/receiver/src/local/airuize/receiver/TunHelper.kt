package local.airuize.receiver

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Builds the CarPlay IPv6 TUN through a tiny ROOT native helper, fully bypassing the
 * ROM's broken VpnService/ConnectivityService (whose establish() throws a remote NPE).
 *
 * Worker-thread flow:
 *  1. extract assets/native/tunhelper into filesDir
 *  2. bind a filesystem UNIX socket and wrap it as a LocalServerSocket (which listens)
 *  3. start the helper under ROOT: it opens /dev/tun, TUNSETIFF, brings the link up and
 *     assigns fe80::2/64 through rtnetlink, then hands the ready tun fd over SCM_RIGHTS
 *  4. receive, dup (detach from the LocalSocket lifecycle) and adopt the fd as a PFD
 *
 * Any failure after the socket is bound tears everything down (closing the client makes
 * the helper exit and drop its tun); nothing is leaked across retries. The helper is
 * session-only and nothing under /system is modified.
 */
object TunHelper {
    private const val ASSET_PATH = "native/tunhelper"

    class Tun(
        val pfd: ParcelFileDescriptor,
        val iface: String,
        private val client: LocalSocket,
        private val server: LocalServerSocket,
        private val bound: LocalSocket,
        private val sockFile: File,
        private val helperLog: File,
    ) : Closeable {
        override fun close() {
            runCatching { pfd.close() }
            runCatching { client.close() } // helper sees EOF and tears its tun fd down
            runCatching { server.close() }
            runCatching { bound.close() }
            runCatching { sockFile.delete() }
            runCatching { helperLog.delete() }
        }
    }

    fun establish(context: Context, log: (String) -> Unit): Tun {
        val files = context.filesDir
        val token = java.lang.Long.toHexString(System.nanoTime())
        val helper = extractHelper(context)
        val sockFile = File(files, "tun-$token.sock")
        val helperLog = File(files, "tun-$token.log")

        // 1) extract the native binary from assets (no socket resources yet)
        log("TUN helper 已释放 ${helper.absolutePath} bytes=${helper.length()}")
        runCatching { sockFile.delete() }

        var bound: LocalSocket? = null
        var server: LocalServerSocket? = null
        var client: LocalSocket? = null
        var adopted: ParcelFileDescriptor? = null
        val holder = arrayOfNulls<LocalSocket>(1)
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            // 2) bind a filesystem UNIX socket, then wrap the bound fd as a LocalServerSocket
            bound = LocalSocket()
            bound.bind(LocalSocketAddress(sockFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val srv = LocalServerSocket(bound.fileDescriptor)
            server = srv
            log("TUN 控制 socket 监听 ${sockFile.absolutePath}")

            // 3) launch the helper under ROOT (backgrounded); redirect its logs to a file
            val script =
                "#!/system/bin/sh\n" +
                "export PATH=/system/bin:/system/xbin:\$PATH\n" +
                "H=" + shellQuote(helper.absolutePath) + "\n" +
                "S=" + shellQuote(sockFile.absolutePath) + "\n" +
                "L=" + shellQuote(helperLog.absolutePath) + "\n" +
                "chmod 500 \"\$H\"\n" +
                "chmod 777 \"\$S\"\n" +
                "rm -f \"\$L\"\n" +
                "\"\$H\" \"\$S\" 3600 >\"\$L\" 2>&1 &\n" +
                "echo TUNHELPER_STARTED pid=\$!\n"
            val launched = RootShell.execScript(context, script, 12000L)
            log("TUN helper ROOT 启动: " + launched.text.trim())

            // 4) accept with a hard timeout (the helper retries connect for ~6 s)
            val acceptThread = Thread {
                try {
                    val socket = srv.accept()
                    synchronized(holder) { if (accepting.get()) holder[0] = socket else socket.close() }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }
            acceptThread.join(15000)
            val accepted = synchronized(holder) { accepting.set(false); holder[0] }
            if (accepted == null) {
                val tail = runCatching { if (helperLog.exists()) helperLog.readText() else "" }.getOrDefault("")
                throw IOException("TUN helper 15 秒内未连接（ROOT/二进制/SELinux 问题）。helper日志:\n$tail")
            }
            client = accepted
            accepted.soTimeout = 10000
            log("TUN helper 已连接，读取握手与 ancillary fd…")

            // 5) read the handshake line, then fetch the passed fd
            val line = try {
                accepted.inputStream.bufferedReader().readLine()
            } catch (e: IOException) {
                throw IOException("读取 helper 握手失败: ${e.message}", e)
            }
            log("TUN helper 握手: $line")
            val ancillary = accepted.ancillaryFileDescriptors
            if (ancillary.isNullOrEmpty()) {
                val tail = runCatching { helperLog.readText() }.getOrDefault("")
                throw IOException("helper 已连接但未携带 tun fd。helper日志:\n$tail")
            }

            // 6) dup the received fd (independent of the LocalSocket) and adopt it as a PFD
            val pfd = try {
                ParcelFileDescriptor.adoptFd(dupFileDescriptor(ancillary[0]))
            } catch (e: Throwable) {
                throw IOException("dup tun fd 失败: ${e.message}", e)
            } finally { closeReceivedFds(ancillary) }
            adopted = pfd
            // Keep only the adopted dup fd; close every fd received over SCM_RIGHTS so a
            // left-over received fd cannot pin the tun and stop the kernel removing it.
            val iface = Regex("name=([^ ]+)").find(line ?: "")?.groupValues?.get(1) ?: "tun"
            log("TUN fd 已接收并 adopt fdInt=${pfd.fd} iface=$iface")

            // 7) verify the link-local address is configured
            val ifInet6 = runCatching { File("/proc/net/if_inet6").readText() }.getOrDefault("")
            val hasLl = ifInet6.lines().any {
                it.trimStart().startsWith("fe800000000000000000000000000002")
            }
            log(
                "TUN fe80::2 校验=" + (if (hasLl) "存在" else "未发现(可能延迟)") +
                " | if_inet6=" + ifInet6.lineSequence().filter { it.isNotBlank() }
                    .joinToString(" | ") { it.trim() }
            )
            if (!hasLl) log("警告: /proc/net/if_inet6 暂未出现 fe80::2；iPhone 依赖该地址，详见 helper 日志")
            runCatching { if (helperLog.exists()) log("TUN helper 运行日志:\n" + helperLog.readText().trim()) }

            // 8) allow inbound CarPlay traffic on the new interface. No-op if the ROM has no
            // iptables; Android 4.4 default INPUT policy is ACCEPT anyway.
            openFirewall(context, iface, log)

            return Tun(pfd, iface, accepted, srv, bound, sockFile, helperLog)
        } catch (t: Throwable) {
            synchronized(holder) { accepting.set(false); runCatching { holder[0]?.close() } }
            runCatching { adopted?.close() }
            // Failure cleanup: closing the client makes a connected helper exit and drop its tun.
            runCatching { client?.close() }
            runCatching { server?.close() }
            runCatching { bound?.close() }
            runCatching { sockFile.delete() }
            runCatching { helperLog.delete() }
            throw t
        }
    }

    /** Content-addressed executable: a reconnect never truncates a running helper inode. */
    @Synchronized private fun extractHelper(context: Context): File {
        val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        val target = File(context.filesDir, "tunhelper-$hash")
        val matches = target.isFile && runCatching { MessageDigest.getInstance("SHA-256").digest(target.readBytes()).contentEquals(digest) }.getOrDefault(false)
        if (matches) return target
        val temporary = File.createTempFile("tunhelper-", ".tmp", context.filesDir)
        try {
            temporary.writeBytes(bytes)
            check(temporary.setReadable(true, true) && temporary.setWritable(false, false)) { "TUN helper permission failure" }
            if (!temporary.renameTo(target)) {
                val unique = File(context.filesDir, "tunhelper-$hash-${System.nanoTime()}")
                check(temporary.renameTo(unique)) { "TUN helper publish failure" }
                return unique
            }
            return target
        } finally { temporary.delete() }
    }

    private fun openFirewall(context: Context, iface: String, log: (String) -> Unit) {
        val script =
            "#!/system/bin/sh\n" +
            "export PATH=/system/bin:/system/xbin:\$PATH\n" +
            "IF=$iface\n" +
            "for IP in iptables ip6tables; do\n" +
            "  command -v \$IP >/dev/null 2>&1 || continue\n" +
            "  \$IP -I INPUT -i \$IF -j ACCEPT 2>/dev/null && echo FW_OPEN_\$IP\n" +
            "done\n" +
            "echo FW_DONE\n"
        val result = RootShell.execScript(context, script, 10000L)
        log("TUN 防火墙放行: " + result.text.trim())
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Close FileDescriptors received over SCM_RIGHTS (we retain only the adopted dup). */
    private fun closeReceivedFds(fds: Array<java.io.FileDescriptor>) {
        for (f in fds) runCatching {
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            os.javaClass.getMethod("close", java.io.FileDescriptor::class.java).invoke(os, f)
        }
    }

    /** Duplicate a java.io.FileDescriptor through libcore (Libcore.os.dup) and return the new int fd. */
    private fun dupFileDescriptor(fd: java.io.FileDescriptor): Int {
        val libcoreClass = Class.forName("libcore.io.Libcore")
        val os = libcoreClass.getField("os").get(null)
        val dupMethod = os.javaClass.getMethod("dup", java.io.FileDescriptor::class.java)
        val dupFd = dupMethod.invoke(os, fd) as java.io.FileDescriptor
        val descField = try {
            dupFd.javaClass.getDeclaredField("descriptor")
        } catch (_: NoSuchFieldException) {
            dupFd.javaClass.getDeclaredField("fd")
        }
        descField.isAccessible = true
        return descField.getInt(dupFd)
    }
}
