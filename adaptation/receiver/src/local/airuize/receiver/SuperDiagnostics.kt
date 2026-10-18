package local.airuize.receiver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import com.shilapi.xcertplay.transport.NcmStats
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 途胜L 终极一键诊断（只读，绝不修改 /system、不改设置）。
 * 一次点击完成：ROOT 授权与主外壳复用 → 系统/硬件/USB/网络/媒体/权限/系统脚本 →
 * 自动有线连接尝试并记录每阶段精确耗时 → 音频出声实测 → 视频解码器实测 →
 * ROOT 深层(service/dumpsys/ps/logcat/dmesg) → 崩溃记录 → 自动结论。
 */
class SuperDiagnostics(private val context: Context, private val progress: (String) -> Unit) {
    private val sb = StringBuilder()
    private val diagDir: File get() = File(Environment.getExternalStorageDirectory(), "DiPlay").apply { mkdirs() }
    private var rootOk = false
    private var connectSuccess = false
    private var connectRan = false
    private var sessionWasActive = false
    private var observationSummary = "本次未观察会话"
    private val lastStages = LinkedHashMap<String, Long>()

    private fun header(t: String) { sb.append("\n\n==================== ").append(t).append(" ====================\n") }
    private fun p(s: String) { sb.append(s).append('\n') }
    private inline fun section(t: String, body: () -> Unit) {
        header(t); try { body() } catch (e: Throwable) { p("  [本节采集异常] ${e.javaClass.simpleName}: ${e.message}") }
    }
    private fun readFile(path: String, max: Int = 4000): String = try {
        val f = File(path); if (f.exists()) f.readText(Charsets.UTF_8).take(max) else "<不存在:$path>"
    } catch (e: Throwable) { "<读取失败:${e.javaClass.simpleName}>" }
    private fun readFile(dir: File, name: String): String = try {
        val f = File(dir, name); if (f.exists()) f.readText(Charsets.UTF_8).trim() else ""
    } catch (e: Throwable) { "" }
    private fun sysprop(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String? ?: ""
    } catch (e: Throwable) { "<${e.javaClass.simpleName}>" }
    private fun serviceBinder(name: String): Any? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
    } catch (e: Throwable) { null }

    fun run(autoConnect: Boolean): File {
        sb.setLength(0)
        p("############################################################")
        p("# DiPlay 途胜L 终极一键诊断（纯有线版，只读，不修改系统）")
        p("# 生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        p("############################################################")
        section("1. ROOT 授权（主外壳复用，长诊断独立外壳）") { rootSection() }
        section("2. 设备与系统") { device() }
        section("3. 硬件特性 / TUN") { features() }
        section("4. USB 设备（连接前快照）") { usb() }
        section("5. 网络") { network() }
        section("6. 音视频能力 / 音频 / 麦克风") { mediaCaps() }
        section("6A. GPS / 功能配置（只读）") { p(LegacyGpsProbe.snapshot(context)); functionSnapshot("连接前") }
        section("7. 方向盘按键 / 输入设备 / 语音键") { steeringProbe() }
        section("8. 应用权限") { permissions() }
        section("9. 系统脚本（无需 ROOT：getprop/mounts/ifconfig/库）") { appShellSection() }
        section("9A. 连接前 IPv6 策略 / socket / 资源快照") { networkPolicy(); p(ConnectionRuntimeProbe.snapshot()) }
        if (autoConnect) section("10. 自动有线连接尝试（每阶段精确耗时）") { autoConnectAttempt() }
        else p("\n[10] 已按选择跳过自动连接。")
        if (autoConnect && connectSuccess) section("10A. CarPlay 已连接后的方向盘按键复查") {
            progress("CarPlay 已连接，再采集一次方向盘按键路由；按窗口提示操作即可。")
            steeringLiveCapture()
        }
        section("10A2. GPS / 麦克风 / 其它功能联合观察") { functionalObservation() }
        section("10B. 连接观察后的 IPv6 策略与状态") {
            networkPolicy(); p(ConnectionRuntimeProbe.snapshot())
            p(ReceiverActivity.interactionDiagnostic)
            functionSnapshot("连接观察结束")
            LegacyWiredService.instance?.let { p("本节采样时会话活动=${it.activeSession!=null}"); p(it.mediaDiagnosticSnapshot()); p(it.diagnosticEvidence()) }
        }
        section("11. 音频出声实测（实际播放，验证播放头推进）") {
            if(LegacyWiredService.instance?.activeSession != null) p("当前会话仍活动，跳过测试音，避免干扰手机音频；实际会话统计见第10节。")
            else AudioProbe.run(context) { p(it) }
        }
        section("12. 视频解码器实测（H264 硬解初始化）") {
            if(LegacyWiredService.instance?.activeSession != null) p("当前会话仍活动，跳过额外硬解初始化；实际接收/解码输出见第10节。") else videoProbe()
        }
        section("13. ROOT 深层（service/dumpsys/ps/输入设备/logcat/dmesg）") { rootDeep() }
        section("14. 崩溃记录（若闪退，此处为完整堆栈）") { p(CrashGuard.recent().ifBlank { "(无崩溃记录)" }) }
        section("14A. 自学习档案 / 自动异常留证") { p(LearningReports.snapshot(context)) }
        section("15. 自动结论") { verdict() }

        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(diagDir, "super-diag-$ts.txt")
        out.writeText(sb.toString().replace("\u0000", "\\0"), Charsets.UTF_8)
        progress("诊断完成，已保存：${out.absolutePath}")
        return out
    }

    // ---------------- 1) ROOT ----------------
    private fun rootSection() {
        progress("请求 ROOT（请点“允许”并勾“记住/永久”；后续按超级用户策略授权）…")
        val r = RootShell.execScript(context, "#!/system/bin/sh\necho ROOT_READY\nid\n", 20000)
        rootOk = r.text.contains("uid=0")
        p(if (rootOk) "ROOT 已取得（主连接外壳复用；长诊断用独立外壳，可能按超级用户策略再次询问）：\n${r.text.trim().take(300)}"
          else "未取得 ROOT：${r.text.trim().take(200)}\n（功能 ROOT 与第13节深层采集会受限；请重跑并在弹窗点允许）")
    }

    // ---------------- 2) device ----------------
    private fun device() {
        val pm = context.packageManager
        runCatching { val pi = pm.getPackageInfo(context.packageName, 0); p("App: ${pi.packageName} versionCode=${pi.versionCode} versionName=${pi.versionName}") }
        p("Build.MANUFACTURER=${Build.MANUFACTURER}  BRAND=${Build.BRAND}")
        p("Build.MODEL=${Build.MODEL}  DEVICE=${Build.DEVICE}  PRODUCT=${Build.PRODUCT}  BOARD=${Build.BOARD}  HARDWARE=${Build.HARDWARE}")
        p("Build.FINGERPRINT=${Build.FINGERPRINT}")
        p("Build.DISPLAY=${Build.DISPLAY}")
        p("Android RELEASE=${Build.VERSION.RELEASE}  SDK_INT=${Build.VERSION.SDK_INT}  INCREMENTAL=${Build.VERSION.INCREMENTAL}")
        p("ro.product.manufacturer=${sysprop("ro.product.manufacturer")} model=${sysprop("ro.product.model")} name=${sysprop("ro.product.name")}")
        p("ro.board.platform=${sysprop("ro.board.platform")} ro.hardware=${sysprop("ro.hardware")} ro.arch=${sysprop("ro.product.cpu.abi")}")
        p("ro.build.type=${sysprop("ro.build.type")} ro.debuggable=${sysprop("ro.debuggable")} ro.secure=${sysprop("ro.secure")}")
        p("ro.sf.lcd_density=${sysprop("ro.sf.lcd_density")} dalvik.vm.heapsize=${sysprop("dalvik.vm.heapsize")}")
        val dm = context.resources.displayMetrics
        p("显示: ${dm.widthPixels}x${dm.heightPixels} densityDpi=${dm.densityDpi} density=${dm.density} xdpi=${dm.xdpi.toInt()} ydpi=${dm.ydpi.toInt()}")
        val rt = Runtime.getRuntime()
        p("Java heap 上限=${rt.maxMemory() / 1048576}MB 当前=${rt.totalMemory() / 1048576}MB")
        p("--- /proc/meminfo(前) ---\n${readFile("/proc/meminfo", 600)}")
        val cpu = readFile("/proc/cpuinfo", 4000)
        p("CPU processor 核数=${cpu.lineSequence().count { it.startsWith("processor") }}")
        p(cpu.lineSequence().filter { it.startsWith("Processor") || it.startsWith("model name") || it.startsWith("Hardware") || it.startsWith("BogoMIPS") || it.startsWith("CPU implementer") || it.startsWith("CPU part") }.take(12).joinToString("\n"))
        runCatching {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            p("外部存储: 可用=${stat.availableBlocks.toLong() * stat.blockSize.toLong() / 1048576L}MB / 总=${stat.blockCount.toLong() * stat.blockSize.toLong() / 1048576L}MB")
        }
        p("filesDir=${context.filesDir}")
    }

    // ---------------- 3) features ----------------
    private fun features() {
        p("USB native shim: loaded=${UsbIfaceNative.ensureLoaded(context)} ${UsbIfaceNative.diagnosticSnapshot()}")
        val pm = context.packageManager
        listOf("android.hardware.usb.host", "android.hardware.usb.accessory",
            "android.hardware.wifi", "android.hardware.wifi.direct", "android.hardware.microphone", "android.hardware.location.gps",
            "android.hardware.touchscreen", "android.hardware.touchscreen.multitouch", "android.hardware.camera.any",
            "android.hardware.telephony", "android.hardware.audio.low_latency", "android.hardware.audio.output").forEach { p("feature $it = ${pm.hasSystemFeature(it)}") }
        p("本版为纯有线：无线/蓝牙连接功能已整体移除（系统 bluetooth_manager 未注册，标准蓝牙栈不可用）。")
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sd/xbin/su",
            "/data/local/xbin/su", "/vendor/bin/su", "/sbin/magisk", "/data/adb/magisk")
        p("su/magisk 存在路径: ${suPaths.filter { File(it).exists() }}")
        p("/dev/tun 存在=${File("/dev/tun").exists()} 可读=${File("/dev/tun").canRead()} 可写=${File("/dev/tun").canWrite()}")
        p("/dev/bus/usb 存在=${File("/dev/bus/usb").exists()}")
        p("VpnService.prepare 结果: ${try { if (android.net.VpnService.prepare(context) == null) "null=无需授权(或已授权)" else "待系统弹窗授权" } catch (e: Throwable) { "异常:${e.javaClass.simpleName}（ROM裁剪VPN授权）" }}")
    }

    // ---------------- 4) USB ----------------
    private fun usb() {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devs = um.deviceList
        p("UsbManager 设备数=${devs.size}  (Apple VID=0x05ac 数=${devs.values.count { it.vendorId == 0x05ac }})")
        devs.values.forEach { d ->
            p("设备 ${d.deviceName} VID=0x${d.vendorId.toString(16)} PID=0x${d.productId.toString(16)} 已授权=${um.hasPermission(d)} 接口数=${d.interfaceCount}")
            for (ii in 0 until d.interfaceCount) {
                val itf = d.getInterface(ii)
                val eps = (0 until itf.endpointCount).joinToString(",") { e -> val ep = itf.getEndpoint(e); "t${ep.type}/0x${ep.address.toString(16)}" }
                p("   iface${itf.id} class=${itf.interfaceClass}/sub=${itf.interfaceSubclass}/proto=${itf.interfaceProtocol} endpoints=[$eps]")
            }
        }
        p("--- /sys/bus/usb/devices（内核枚举，世界可读）---")
        val nodes = File("/sys/bus/usb/devices").listFiles() ?: emptyArray()
        p("sysfs 节点数=${nodes.size}")
        nodes.forEach { f ->
            val vid = readFile(f, "idVendor"); val pid = readFile(f, "idProduct")
            val prod = readFile(f, "product"); val cls = readFile(f, "bDeviceClass")
            if (vid.isNotEmpty() || prod.isNotEmpty()) p("   ${f.name} vid=$vid pid=$pid class=$cls product='$prod'")
            if (vid.equals("05ac", ignoreCase = true)) {
                p("     Apple USB kernel state: busnum=${readFile(f, "busnum")} devnum=${readFile(f, "devnum")} bConfigurationValue=${readFile(f, "bConfigurationValue")}")
                nodes.filter { it.name.startsWith(f.name + ":") }.forEach { intf ->
                    p("     ${intf.name} interface=${readFile(intf, "bInterfaceNumber")} alt=${readFile(intf, "bAlternateSetting")} class=${readFile(intf, "bInterfaceClass")} sub=${readFile(intf, "bInterfaceSubClass")} proto=${readFile(intf, "bInterfaceProtocol")} endpoints=${readFile(intf, "bNumEndpoints")}")
                }
            }
        }
        p("--- /dev/bus/usb 节点权限（app 视角）---")
        val bus = File("/dev/bus/usb")
        if (bus.exists()) bus.listFiles()?.forEach { b -> b.listFiles()?.forEach { dv -> p("   /dev/bus/usb/${b.name}/${dv.name} 可读=${dv.canRead()} 可写=${dv.canWrite()}") } }
        else p("   /dev/bus/usb 不存在")
    }

    // ---------------- 5) network ----------------
    private fun intToIp(v: Int): String = "${v and 255}.${v shr 8 and 255}.${v shr 16 and 255}.${v shr 24 and 255}"
    private fun network() {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        p("WiFi isEnabled=${wm.isWifiEnabled}")
        runCatching {
            val info = wm.connectionInfo
            p("SSID=${info.ssid} link=${info.linkSpeed}Mbps rssi=${info.rssi} ip=${intToIp(info.ipAddress)}")
            val d = wm.dhcpInfo
            p("Dhcp ip=${intToIp(d.ipAddress)} gateway=${intToIp(d.gateway)} mask=${intToIp(d.netmask)}")
        }
        p("--- 网卡（Java NetworkInterface）---")
        val en = NetworkInterface.getNetworkInterfaces()
        while (en.hasMoreElements()) {
            val n = en.nextElement()
            val ips = StringBuilder()
            val ae = n.inetAddresses
            while (ae.hasMoreElements()) { ips.append(ae.nextElement().hostAddress).append(' ') }
            val mac = runCatching { n.hardwareAddress?.joinToString(":") { "%02X".format(it.toInt() and 255) } }.getOrNull()
            p("  ${n.name} up=${n.isUp} lo=${n.isLoopback} mtu=${runCatching { n.mtu }.getOrNull()} mac=$mac addr=[$ips]")
        }
        p("--- /proc/net/if_inet6 ---"); p(readFile("/proc/net/if_inet6", 1500))
        p("--- /proc/net/route (前) ---"); p(readFile("/proc/net/route", 1000))
    }

    // ---------------- 6) mediaCaps ----------------
    private fun mediaCaps() {
        val n = MediaCodecList.getCodecCount()
        p("MediaCodec 总数=$n")
        for (i in 0 until n) {
            val c = MediaCodecList.getCodecInfoAt(i)
            p("  [${if (c.isEncoder) "ENC" else "DEC"}] ${c.name}  {${c.supportedTypes.joinToString(",")}}")
            if(!c.isEncoder) for(type in c.supportedTypes.filter { it in listOf("video/avc","audio/mp4a-latm") }) {
                runCatching { c.getCapabilitiesForType(type) }.onSuccess { caps ->
                    p("    $type profile/level=${caps.profileLevels.take(24).joinToString { "${it.profile}/${it.level}" }} colorFormats=${caps.colorFormats.take(24).joinToString()}")
                }.onFailure { p("    $type 能力查询失败:${it.javaClass.simpleName}") }
            }
        }
        val settings=ReceiverSettings.read(context); val advertised=settings.display()
        p("当前设置预览（下次连接采用）：${advertised.widthPixels}x${advertised.heightPixels} fps=${advertised.fps} HEVC=${settings.hevc && CodecCapabilities.hevc()} Opus=${settings.opus && CodecCapabilities.opus()} CarPlay输入端点=true 采集车机麦克风=${settings.microphone} 娱乐采样率=44100 bufferMs=${settings.bufferMillis}；实际本轮配置见连接冻结证据")
        p(settings.displaySummary())
        p(ReceiverActivity.viewportDiagnostic)
        p(ReceiverActivity.interactionDiagnostic)
        LegacyWiredService.instance?.advertisedDisplay?.let {
            p("当前会话冻结通告=${it.widthPixels}x${it.heightPixels} fps=${it.fps}；设置更改在重新连接后生效")
        }
        p("关键能力: H264解码=${CodecCapabilities.names("video/avc").isNotEmpty()} HEVC=${CodecCapabilities.hevc()} AAC解码=${CodecCapabilities.names("audio/mp4a-latm").isNotEmpty()} MP3=${CodecCapabilities.names("audio/mpeg").isNotEmpty()} Opus=${CodecCapabilities.opus()}")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        listOf(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER).forEach {
            p("AudioProp[$it]=${runCatching { am.getProperty(it) }.getOrNull()}")
        }
        p("AudioManager: mode=${am.mode} musicVol=${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} micMute=${am.isMicrophoneMute} speaker=${am.isSpeakerphoneOn}")
        micProbe()
        p("--- media_codecs.xml(前2600) ---\n${readFile("/system/etc/media_codecs.xml", 2600)}")
        p("--- media_profiles.xml(前1000) ---\n${readFile("/system/etc/media_profiles.xml", 1000)}")
        p("--- audio_policy.conf(前2600) ---\n${readFile("/system/etc/audio_policy.conf", 2600)}")
    }

    private fun micProbe() {
        val granted=context.packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO,context.packageName)==PackageManager.PERMISSION_GRANTED
        p("麦克风只读能力：RECORD_AUDIO=$granted；自动采集不另开AudioRecord、不抢占Siri/通话录音、不保存声音")
        for(rate in intArrayOf(8000,16000,24000,44100,48000)) {
            p("  rate=$rate minBufferMono=${AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)} minBufferStereo=${AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT)}")
        }
        p("录音效果能力（仅查存在，不启用）：AEC=${runCatching { android.media.audiofx.AcousticEchoCanceler.isAvailable() }.getOrNull()} NS=${runCatching { android.media.audiofx.NoiseSuppressor.isAvailable() }.getOrNull()} AGC=${runCatching { android.media.audiofx.AutomaticGainControl.isAvailable() }.getOrNull()}")
        p("请在功能观察阶段通过Siri/通话触发真实上行；非零/音量峰值/UDP写入均不证明手机已识别。旧版0字节自检漏记负错误码，不能作为MIC损坏证据。")
    }

    private fun functionSnapshot(label:String) {
        p("---- 功能快照：$label ----")
        val elapsedBefore=SystemClock.elapsedRealtimeNanos()
        val nano=System.nanoTime()
        val elapsedAfter=SystemClock.elapsedRealtimeNanos()
        p("时钟对应 elapsedRealtimeNanosBefore=$elapsedBefore nanoTime=$nano elapsedRealtimeNanosAfter=$elapsedAfter uncertaintyNs=${elapsedAfter-elapsedBefore}；仅用于本快照附近事件对时")
        p(FeatureEnvironmentDiagnostics.snapshot(context))
        p(LegacyWiredService.instance?.featureConfigurationSnapshot() ?: FeatureConfigurationDiagnostics.describe(ReceiverSettings.read(context),null,null))
        p(LocationDiagnostics.snapshot())
        p(com.shilapi.xcertplay.transport.Iap2LocationDiagnostics.snapshot())
        p(AudioFocusDiagnostics.snapshot())
        p(FunctionRunDiagnostics.snapshot())
        p(com.shilapi.xcertplay.airplay.ScreenFrameCrypto.diagnosticSnapshot())
        p(ScreenCryptoNative.diagnosticSnapshot())
        p(ReceiverActivity.viewportDiagnostic); p(ReceiverActivity.interactionDiagnostic)
        p(LegacyWiredService.instance?.mediaDiagnosticSnapshot() ?: "本次未建立媒体接收器；音频/麦克风实际数据未观察到")
    }

    private fun functionalObservation() {
        val probe=LegacyGpsProbe(context) { progress(it) }
        try {
            p("一次功能观察60秒：可收起诊断操作CarPlay，采集继续。建议播放音乐15秒→Siri安静3秒再说测试短句5秒→地图拖动缩放；未执行的项目只记未观察到。通话可自行测试，软件不自动拨号。")
            progress("功能观察开始：60秒。点“收起后操作”可测试音乐/地图；点“Siri采集”后先安静3秒，再正常说测试短句。")
            p(LegacyGpsProbe.snapshot(context)); functionSnapshot("观察前")
            probe.start(60000L)
            val started=SystemClock.elapsedRealtime()
            var nextSample=15000L
            var siriGuided=false
            var mapGuided=false
            while(SystemClock.elapsedRealtime()-started<60000L) {
                Thread.sleep(1000)
                val elapsed=SystemClock.elapsedRealtime()-started
                if(elapsed>=nextSample) {
                    nextSample+=15000L
                    p("功能观察采样 elapsedMs=$elapsed；会话活动=${LegacyWiredService.instance?.activeSession!=null}")
                    p(probe.snapshot())
                    p(LegacyWiredService.instance?.mediaDiagnosticSnapshot() ?: "媒体未建立")
                }
                if(elapsed>=20000L && !siriGuided) { siriGuided=true; progress("功能观察：可点“Siri采集”，安静3秒后说话；没有启动Siri不判定麦克风坏。") }
                if(elapsed>=40000L && !mapGuided) { mapGuided=true; progress("功能观察：可拖动/缩放地图、打开菜单返回；GPS无新鲜定位请结合权限、卫星和手机订阅判断。") }
            }
        } finally { probe.close(); p(probe.snapshot()); functionSnapshot("观察后") }
    }

    // ---------------- 7) steering wheel / input devices ----------------
    private fun steeringProbe() {
        try {
            val ids = android.view.InputDevice.getDeviceIds()
            p("InputDevice 数量=${ids.size}")
            for (id in ids) {
                val dev = android.view.InputDevice.getDevice(id) ?: continue
                val isKey = (dev.sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
                val isDpad = (dev.sources and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD
                p("  id=$id name='${dev.name}' kbType=${dev.keyboardType} src=0x${dev.sources.toString(16)} keyboard=$isKey dpad=$isDpad")
            }
            val keyCodes = intArrayOf(
                android.view.KeyEvent.KEYCODE_HEADSETHOOK, android.view.KeyEvent.KEYCODE_MEDIA_STOP,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND, android.view.KeyEvent.KEYCODE_CALL,
                android.view.KeyEvent.KEYCODE_ENDCALL, 231 /*KEYCODE_VOICE_ASSIST*/)
            for (id in ids) {
                val dev = android.view.InputDevice.getDevice(id) ?: continue
                if ((dev.sources and android.view.InputDevice.SOURCE_KEYBOARD) != android.view.InputDevice.SOURCE_KEYBOARD) continue
                // hasKeys 是 API19 方法，编译 jar 为 API18，用反射调用（运行时 API19 存在）
                val have: BooleanArray = runCatching {
                    val m = dev.javaClass.getMethod("hasKeys", IntArray::class.java)
                    m.invoke(dev, keyCodes) as? BooleanArray
                }.getOrNull() ?: BooleanArray(keyCodes.size)
                val supported = keyCodes.zip(have.toList()).filter { it.second }.map {
                    runCatching { android.view.KeyEvent.keyCodeToString(it.first) }.getOrNull() ?: ("KEYCODE_" + it.first)
                }
                p("  键盘设备 '${dev.name}' 支持的媒体/语音键: ${if (supported.isEmpty()) "无常见媒体/语音键（可能为自定义键，见第13节）" else supported.joinToString()}")
            }
        } catch (e: Throwable) { p("输入设备采集异常: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val running = am.getRunningServices(64).any { it.service.className == SteeringVoiceService::class.java.name }
            val prefs = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
            p("方向盘语音键: 开关=${prefs.getBoolean("steeringVoice", true)} 服务运行中=$running")
            p("语音键 intent: SIRI=${SteeringVoiceService.SIRI_ACTION} VOICE=${SteeringVoiceService.VOICE_ACTION}")
        } catch (e: Throwable) { p("方向盘服务状态异常: ${e.javaClass.simpleName}") }
        p("物理方向盘按键扫描码 / /dev/input 设备见第13节 ROOT 深层 INPUT_DEV。静态 hasKeys 不能代替实际按键测试。")
        p(HsaeKeyDiagnostics.schema(context))
        steeringLiveCapture()
    }

    /** Observe all three possible routes without registering a media-button receiver or changing key settings. */
    private fun steeringLiveCapture() {
        val wasRunning = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(64).any { it.service.className == SteeringVoiceService::class.java.name }
        }.getOrDefault(false)
        val prefEnabled = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE).getBoolean("steeringVoice", true)
        val connected = LegacyWiredService.instance?.activeSession != null
        p("--- 现场按键采集（一次报告同时检查内核、MCU、前台应用）---")
        p("采集开始时: CarPlay会话=$connected；方向盘语音偏好=$prefEnabled；MCU服务原先运行=$wasRunning；未预设方向盘键码；epochMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.uptimeMillis()}。")
        p("仅记录 EV_KEY/MSC_SCAN 与本应用收到的 KeyEvent，不记录触摸坐标或 CAN 数据。若无某个按钮，该阶段无需操作。")
        val rootBase = File(context.cacheDir, "diplay-keys-${SystemClock.elapsedRealtime()}")
        val rootResult = arrayOfNulls<PrivateDiagnosticRootCommand.Result>(1)
        var rootThread: Thread? = null
        var privateRoot:PrivateDiagnosticRootCommand?=null
        var privateScript:File?=null
        var keyCleanupEvidence="KEY_CLEANUP=not-observed"
        var tempStarted = false
        val captureStart = VoiceKeyJournal.beginCapture(75_000L)
        try {
            if (rootOk) {
                val script=DiagnosticKeyCaptureScript.build(rootBase.absolutePath)
                val scriptFile=File.createTempFile("private-key-diag-",".sh",context.cacheDir)
                privateScript=scriptFile
                check(scriptFile.setReadable(false,false) && scriptFile.setWritable(false,false) &&
                    scriptFile.setReadable(true,true) && scriptFile.setWritable(true,true)) {"Private key diagnostic script permission failed"}
                scriptFile.writeText(script,Charsets.UTF_8)
                privateProofFile(File(rootBase.absolutePath+".done"))
                val command=PrivateDiagnosticRootCommand(scriptFile,65_000L,
                    signalCancel={File(rootBase.absolutePath+".stop").writeText("")},
                    afterCleanup={
                        val done=File(rootBase.absolutePath+".done")
                        if(privateRoot?.hasStartedScript()==true) {
                            keyCleanupEvidence=if(done.exists()) done.readText(Charsets.UTF_8).take(256) else "KEY_CLEANUP=missing"
                            check(keyCleanupEvidence.startsWith("KEY_CLEANUP=COMPLETE geteventLoopReaped=true")) {"Private key child cleanup not proven"}
                        } else keyCleanupEvidence="KEY_CLEANUP=no-script-started"
                        for(suffix in listOf(".out",".pid",".stop",".hmi.status",".done")) {
                        val file=File(rootBase.absolutePath+suffix)
                        check(!file.exists() || file.delete()) {"Private key diagnostic temporary cleanup incomplete"}
                    }})
                privateRoot=command
                rootThread = Thread({ rootResult[0] = command.execute() }, "steering-raw-capture").apply { isDaemon = true; start() }
                val preparationStart=SystemClock.elapsedRealtime()
                while(!command.hasStartedScript() && rootThread?.isAlive==true && SystemClock.elapsedRealtime()-preparationStart<10_000L)
                    Thread.sleep(50)
                p("内核通道: 私有ROOT getevent采集，窗口42秒；脚本${if(command.hasStartedScript()) "已提交" else "准备未完成，结果见下方"}；不占连接ROOT锁；65秒总期限包含排队及授权，失败不重放。")
            } else p("内核通道: 未取得 ROOT，无法读取 /dev/input 实时原始事件；MCU 与应用通道继续采集。")

            if (!wasRunning) {
                val started = runCatching { context.startService(Intent(context, SteeringVoiceService::class.java)) }.getOrNull()
                tempStarted = started != null
                p(if (started != null) "MCU通道: 临时启动回调服务；采集后恢复原运行状态，不修改用户开关。"
                  else "MCU通道: 回调服务启动失败；内核与应用通道继续采集。")
            } else p("MCU通道: 使用原有回调服务。")

            val windows = listOf(
                "准备：不要按键，等待采集器就绪" to 4_000L,
                "现在按【下一曲】短按两次，间隔至少1秒" to 8_000L,
                "现在按【上一曲】短按两次，间隔至少1秒" to 8_000L,
                "若有【播放/暂停】，按两次；没有就等待" to 6_000L,
                "若有【音量+ / 音量-】，各按一次；没有就等待" to 6_000L,
                "若有【语音】键，短按一次；没有就等待" to 6_000L)
            for ((label, duration) in windows) {
                p("[+${SystemClock.elapsedRealtime() - captureStart}ms epochMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.uptimeMillis()}] $label (${duration / 1000}秒)")
                progress("方向盘采集：$label（${duration / 1000}秒；请保持诊断窗口在前台）")
                Thread.sleep(duration)
            }
            p("[+${SystemClock.elapsedRealtime() - captureStart}ms epochMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.uptimeMillis()}] 按键提示结束；等待内核采集器收尾。")
            rootThread?.join(50_000L)
            val raw = rootResult[0]
            p("--- 内核原始按键事件（EV_KEY / MSC_SCAN）---")
            when {
                !rootOk -> p("未采集：ROOT 未授权。")
                rootThread?.isAlive == true -> p("采集器未及时结束；本次原始事件不可判定。")
                raw == null -> p("采集器没有返回结果；本次原始事件不可判定。")
                else -> p(raw.text.take(256*1024))
            }
            privateRoot?.let { command ->
                p("私有ROOT采集器资源回收完成=${command.awaitCleanup(5000L)}（仅本次外壳；不影响主连接外壳）\n$keyCleanupEvidence")
            }
        } finally {
            privateRoot?.close()
            if(privateRoot==null) runCatching {privateScript?.delete();File(rootBase.absolutePath+".done").delete()}
            val stopRequested = if (tempStarted) runCatching { context.stopService(Intent(context, SteeringVoiceService::class.java)) }.getOrDefault(false) else false
            val observed = VoiceKeyJournal.finishCapture()
            p("--- MCU 回调与 Android KeyEvent（相对时间可对照上面各按键窗口）---")
            observed.forEach(::p)
            if (observed.none { it.contains(" MCU raw=") }) p("MCU回调未见按键；请结合上面的服务注册状态判断，不能据此认定方向盘没有按键。")
            if (observed.none { it.contains(" KEY @") }) p("本应用前台未见 KeyEvent；按键可能只经过 MCU、被系统接管或采集窗口未收到事件。")
            val runningAfter = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getRunningServices(64).any { it.service.className == SteeringVoiceService::class.java.name }
            }.getOrNull()
            p("MCU服务采集后恢复: 原先运行=$wasRunning；临时启动=$tempStarted；停止请求返回=$stopRequested；结束时运行=$runningAfter")
        }
    }

    // ---------------- 7) permissions ----------------
    private fun permissions() {
        val pm = context.packageManager
        listOf(android.Manifest.permission.INTERNET, android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE, android.Manifest.permission.CHANGE_WIFI_STATE, android.Manifest.permission.CHANGE_NETWORK_STATE,
            android.Manifest.permission.ACCESS_NETWORK_STATE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS, "android.permission.ACCESS_SUPERUSER", android.Manifest.permission.WAKE_LOCK,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED, android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE, android.Manifest.permission.BROADCAST_STICKY,
            android.Manifest.permission.VIBRATE).forEach {
            p("  $it = ${if (pm.checkPermission(it, context.packageName) == PackageManager.PERMISSION_GRANTED) "已授予" else "未授予"}")
        }
        runCatching { p("Manifest 声明: ${pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.joinToString()}") }
    }

    // ---------------- shell framework ----------------
    private data class ShellResult(val ok: Boolean, val text: String)
    private fun exec(argv: Array<String>, timeoutMs: Long): ShellResult = try {
        val proc = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        val drain = Thread { val b = ByteArray(4096); try { while (true) { val r = proc.inputStream.read(b); if (r < 0) break; out.write(b, 0, r) } } catch (io: IOException) { /* 进程退出/destroy 关流，正常收尾 */ } }
        drain.isDaemon = true; drain.start()
        val waiter = Thread { proc.waitFor() }; waiter.isDaemon = true
        waiter.start(); waiter.join(timeoutMs)
        var timedOut = false
        if (waiter.isAlive) { timedOut = true; runCatching { proc.destroy() }; waiter.join(2000) }
        drain.join(3000)
        var text = out.toString("UTF-8"); if (timedOut) text += "\n[TIMEOUT after ${timeoutMs}ms]"
        ShellResult(!timedOut, text)
    } catch (e: Throwable) { ShellResult(false, "[exec 异常] ${e.javaClass.simpleName}: ${e.message}") }

    private fun writeScript(name: String, content: String): File {
        val f = File.createTempFile(name.removeSuffix(".sh") + "-", ".sh", context.cacheDir)
        f.writeText(content, Charsets.UTF_8)
        return f
    }

    private fun parseSections(raw: String): LinkedHashMap<String, String> {
        val res = LinkedHashMap<String, StringBuilder>(); var cur = "_PREAMBLE_"; res[cur] = StringBuilder()
        val re = Regex("@@@SECTION:(.+?)@@@")
        raw.lineSequence().forEach { line ->
            val m = re.find(line)
            if (m != null) { cur = m.groupValues[1]; res.getOrPut(cur) { StringBuilder() } }
            else res.getOrPut(cur) { StringBuilder() }.append(line).append('\n')
        }
        return LinkedHashMap(res.mapValues { it.value.toString() })
    }
    private data class Cfg(val keywords: List<String>? = null, val cap: Int = 260)
    private fun filterKeep(text: String, kw: List<String>): String {
        val k = kw.map { it.lowercase() }
        return text.lineSequence().filter { l -> val s = l.lowercase(); k.any { s.contains(it) } }.joinToString("\n")
    }
    private fun capLines(text: String, max: Int): String {
        val a = text.trim('\n').lineSequence().toList()
        if (a.size <= max) return text.trim('\n')
        return a.take(max / 2).joinToString("\n") + "\n...[省略 ${a.size - max} 行]...\n" + a.takeLast(max / 2).joinToString("\n")
    }
    private fun emitSections(raw: String, cfg: Map<String, Cfg>, defaultCap: Int = 260) {
        val map = parseSections(raw)
        for ((name, body0) in map) {
            if (name == "_PREAMBLE_" && body0.isBlank()) continue
            header("CMD: $name")
            var body = (if(name=="LOG_MAIN") ProtocolLogFilter.filter(body0) else body0).trim('\n')
            val c = cfg[name]
            if (c?.keywords != null) body = filterKeep(body, c.keywords)
            val limit = c?.cap ?: defaultCap
            body = capLines(body, limit)
            p(body.ifEmpty { "(空 / 无权限 / 无匹配)" })
        }
    }

    // ---------------- 8) app shell (no root) ----------------
    private fun appScript(): String = """
echo APP_BEGIN_7c2
echo "@@@SECTION:GETPROP@@@"; getprop
echo "@@@SECTION:MOUNTS@@@"; cat /proc/mounts
echo "@@@SECTION:IFCONFIG@@@"; ifconfig 2>&1
echo "@@@SECTION:LIB_ALL@@@"; ls -l /system/lib 2>&1
echo "@@@SECTION:LIB_HW@@@"; ls -l /system/lib/hw 2>&1
echo "@@@SECTION:VENDOR_LIB@@@"; ls -l /vendor/lib 2>&1
echo "@@@SECTION:SU_BIN@@@"; ls -l /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su 2>&1; echo '--id--'; id
echo "@@@SECTION:WHICH@@@"; which su busybox iptables ip logcat service dumpsys ifconfig 2>&1
echo APP_END_7c2
""".trimIndent()

    private fun appShellSection() {
        var f: File? = null
        try {
            f = writeScript("diag_app.sh", appScript())
            val r = exec(arrayOf("sh", f.absolutePath), 30000)
            if (!r.text.contains("APP_BEGIN_7c2")) { p("app shell 无输出: ${r.text.take(300)}"); return }
            val cfg = mapOf(
                "GETPROP" to Cfg(listOf("bluetooth","usb","wlan","wifi","tun","audio","board","platform","hardware","sdk","dalvik","heap","ro.product","ro.build","debuggable","secure","freescale","imx","nxp","lcd","display","gps","persist"), 420),
                "MOUNTS" to Cfg(listOf("system","sdcard","usb","vfat","block","tun","vendor"), 120),
                "LIB_ALL" to Cfg(listOf("audio","usb","wifi","wlan","omx","vpu","codec"), 220),
                "LIB_HW" to Cfg(listOf("audio","display","gpu","vpu","sensors"), 160),
                "VENDOR_LIB" to Cfg(listOf("audio","omx","vpu","wifi"), 160),
                "SU_BIN" to Cfg(null, 40),
                "WHICH" to Cfg(null, 40),
                "IFCONFIG" to Cfg(null, 120))
            emitSections(r.text, cfg, defaultCap = 160)
        } catch (e: Throwable) { p("app shell 采集异常: ${e.javaClass.simpleName}: ${e.message}") }
        finally { runCatching { f?.delete() } }
    }

    // ---------------- 9) auto connect attempt ----------------
    private fun autoConnectAttempt() {
        connectRan = true
        val alreadyActive=LegacyWiredService.instance?.activeSession != null
        p(if(alreadyActive) "已有活动会话，继续观察60秒。" else "启动有线服务，启动观察${WATCH_MS/1000}秒；若会话激活，再观察60秒媒体和稳定性 …")
        if(!alreadyActive) context.startService(Intent(context, LegacyWiredService::class.java))
        val start = SystemClock.elapsedRealtime()
        val observation=ConnectionObservation(start)
        var lastEvent = 0L
        var observedService: LegacyWiredService? = null
        val observedServices=ArrayList<LegacyWiredService>()
        var attemptStart = 0L
        val stageFirst = LinkedHashMap<String, Long>()
        var lastSample=0L
        while (observation.shouldContinue(SystemClock.elapsedRealtime())) {
            Thread.sleep(1000)
            val now=SystemClock.elapsedRealtime()
            val svc = LegacyWiredService.instance
            val firstActive=observation.firstActiveMs==null
            observation.update(now,svc?.activeSession!=null)
            if(firstActive && observation.firstActiveMs!=null) progress("AirPlay 会话激活；继续观察60秒首帧、音频和断连情况。")
            if(svc==null) continue
            if (observedService !== svc) {
                if(stageFirst.isNotEmpty()) p("前一服务阶段结果=$stageFirst")
                observedService=svc; lastEvent=0L; attemptStart=0L; stageFirst.clear()
                if(observedServices.size<8) observedServices.add(svc)
            }
            if (svc.attemptStartedAt != 0L && svc.attemptStartedAt != attemptStart) {
                if(stageFirst.isNotEmpty()) p("前一连接代次阶段结果=$stageFirst")
                attemptStart = svc.attemptStartedAt
                stageFirst.clear()
                p("  [本轮连接开始 mono=$attemptStart；阶段耗时使用事件发生时间，切换重试时重新计时]")
            }
            val events = svc.eventsAfter(lastEvent)
            if (lastEvent>0 && events.isNotEmpty() && events.first().sequence>lastEvent+1) p("  [服务日志缓冲已滚动，省略部分旧事件；按序号继续]")
            for (event in events) {
                lastEvent=event.sequence
                if (attemptStart == 0L || event.monotonicMs >= attemptStart) {
                    val ms = event.monotonicMs - (if (attemptStart != 0L) attemptStart else start)
                    p("  [+${ms}ms #${event.sequence}] ${event.message}")
                    for ((kw, nm) in STAGE_KEYS) if (!stageFirst.containsKey(nm) && event.message.contains(kw)) stageFirst[nm] = ms
                }
            }
            // The rolling log can lose startup events before diagnostics starts. Prefer the
            // producer's bounded numeric table, whose timestamps belong to this exact attempt.
            svc.latestMilestones()?.let { captured ->
                if(captured.startedMs==attemptStart) stageFirst.putAll(captured.first)
            }
            if(now-lastSample>=5000) {
                lastSample=now
                p("[运行采样 observationMs=${now-start} attemptMono=$attemptStart] ${svc.mediaDiagnosticSnapshot()}")
                p(ConnectionRuntimeProbe.snapshot())
            }
        }
        sessionWasActive=observation.firstActiveMs!=null
        connectSuccess=LegacyWiredService.instance?.activeSession!=null
        observationSummary=observation.summary(SystemClock.elapsedRealtime())
        p(observationSummary)
        observedService?.latestMilestones()?.let { captured ->
            if(captured.startedMs!=attemptStart) { attemptStart=captured.startedMs; stageFirst.clear() }
            stageFirst.putAll(captured.first)
        }
        observedServices.forEach { p(it.diagnosticEvidence()) }
        p("")
        p("---- 各阶段首次到达耗时（相对连接开始）----")
        STAGE_ORDER.forEach { nm -> p("  $nm: ${stageFirst[nm]?.let { it.toString() + "ms" } ?: "本轮未记录（可选阶段可能未启用）"}") }
        lastStages.putAll(stageFirst)
        p("NCM 数据面统计：" + NcmStats.snapshot())
        p("USB alt-setting 运行时证据：${LegacyUsbPlatform.diagnosticSnapshot()}")
        p("USB JNI 加载状态：${UsbIfaceNative.diagnosticSnapshot()}")
        p("USB NCM OUT 运行时证据：${LegacyNcmUsbIo.diagnosticSnapshot()}")
        p("USB IN 原生读取聚合（等待时不持有Java数组；等待耗时不等于画面延迟）：\n${LegacyUsbIo.snapshot()}")
        p("连接结果：${if (connectSuccess) "观察结束时会话仍活动；首帧与播放进展需结合媒体统计" else if(sessionWasActive) "曾激活但观察结束时已断开" else "未激活（最远阶段见上，深层日志见第13节）"}")
    }

    // ---------------- 11) video probe ----------------
    private fun videoProbe() {
        val candidates = CodecCapabilities.names("video/avc")
        p("H264 解码器候选(硬解优先): $candidates")
        val name = candidates.firstOrNull()
        if (name == null) { p("无 H264 解码器"); return }
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(name)
            val fmt = MediaFormat().apply {
                setString(MediaFormat.KEY_MIME, "video/avc")
                val display=ReceiverSettings.read(context).display()
                setInteger(MediaFormat.KEY_WIDTH, display.widthPixels); setInteger(MediaFormat.KEY_HEIGHT, display.heightPixels)
            }
            codec.configure(fmt, null, null, 0)
            codec.start()
            Thread.sleep(200)
            p("视频解码器实测：$name configure+start 成功（按当前通告尺寸初始化；实际渲染在连接后）")
            val c = codec
            if (c != null) { runCatching { c.flush(); c.stop() }; runCatching { c.release() } }
            codec = null
            p("结论：H264 解码链路可初始化。")
        } catch (t: Throwable) { p("视频解码器实测失败：${t.javaClass.simpleName}:${t.message}") }
        finally { runCatching { codec?.release() } }
    }

    /** App-owned proof survives ROOT umask077: ROOT only overwrites this existing file. */
    private fun privateProofFile(file:File) {
        check(file.createNewFile()) {"Private diagnostic proof path already exists"}
        check(file.setReadable(false,false) && file.setWritable(false,false) &&
            file.setReadable(true,true) && file.setWritable(true,true)) {"Private diagnostic proof permission failed"}
    }

    /** Long inventories own a private shell and direct-child watcher, never RootShell's lock. */
    private fun privateReadOnlyRoot(timeoutMillis:Long,maximumOutputChars:Int=256*1024,
                                    build:(String)->String):PrivateDiagnosticRootCommand.Result {
        val base=File(context.cacheDir,"read-only-diag-${java.util.UUID.randomUUID()}")
        var scriptFile:File?=null
        var command:PrivateDiagnosticRootCommand?=null
        var cleanupEvidence="DIAG_CLEANUP=not-observed"
        try {
            val file=File.createTempFile("private-read-diag-",".sh",context.cacheDir)
            scriptFile=file
            check(file.setReadable(false,false) && file.setWritable(false,false) &&
                file.setReadable(true,true) && file.setWritable(true,true)) {"Private diagnostic script permission failed"}
            file.writeText(build(base.absolutePath),Charsets.UTF_8)
            privateProofFile(File(base.absolutePath+".done"))
            val owner=PrivateDiagnosticRootCommand(file,timeoutMillis,
                signalCancel={File(base.absolutePath+".stop").writeText("")},
                afterCleanup={
                    val done=File(base.absolutePath+".done")
                    if(command?.hasStartedScript()==true) {
                        cleanupEvidence=if(done.exists()) done.readText(Charsets.UTF_8).take(512) else "DIAG_CLEANUP=missing"
                        check(cleanupEvidence.startsWith("DIAG_CLEANUP=COMPLETE ") &&
                            cleanupEvidence.contains("activeChildReaped=true watchdogReaped=true")) {"Private child cleanup not proven"}
                    } else cleanupEvidence="DIAG_CLEANUP=no-script-started"
                    for(suffix in listOf(".stop",".active",".done")) {
                        val owned=File(base.absolutePath+suffix)
                        check(!owned.exists() || owned.delete()) {"Private diagnostic temporary cleanup incomplete"}
                    }
                },maximumOutputChars=maximumOutputChars)
            command=owner
            val result=owner.execute()
            val cleaned=owner.awaitCleanup(5000L)
            return PrivateDiagnosticRootCommand.Result(result.ok && cleaned,result.text+
                "\nPRIVATE_DIAG_CHILD_CLEANUP complete=$cleaned privateOwner=true\n$cleanupEvidence\n")
        } finally {
            command?.close()
            // Until construction succeeds, script ownership has not transferred.
            if(command==null) runCatching {scriptFile?.delete();File(base.absolutePath+".done").delete()}
        }
    }

    private fun networkPolicy() {
        if(!rootOk) { p("未取得ROOT，IPv6策略/过滤表不可完整读取；本应用socket和TUN快照仍保留。"); return }
        val result=privateReadOnlyRoot(15000L,build=DiagnosticReadOnlyScript::networkPolicy)
        p(capLines(result.text,300).take(24000))
        p(result.text.lineSequence().filter {it.startsWith("PRIVATE_DIAG_CHILD_CLEANUP ") || it.startsWith("DIAG_CLEANUP=") || it.contains("ROOT output truncated;")}.joinToString("\n"))
        p("策略采样返回ok=${result.ok}；私有ROOT含授权总期限15秒，不占连接ROOT锁；命令缺失/权限拒绝保留原文，不能视为网络正常。")
    }

    // ---------------- 12) root deep ----------------
    private fun rootDeep() {
        if (!rootOk) { p("未取得 ROOT，本节跳过（重跑并在首个弹窗点允许即可）。"); return }
        progress("以独立 ROOT 外壳读取 service/dumpsys/ps/logcat/dmesg（约20-40秒，只读）…")
        val packages=try {
            val all=context.packageManager.getInstalledPackages(0)
            val relevant=all.map {it.packageName}.filter {name->
                listOf("car","hsae","airuize","diplay","audio","media").any {name.contains(it,true)}
            }.distinct().sorted()
            "PM_PKGS source=PackageManager.getInstalledPackages(0) total=${all.size} relevant=${relevant.size} cap=150\n"+
                relevant.take(150).joinToString("\n") {"package:${it.take(256).replace('\n','_').replace('\r','_')}"}+
                if(relevant.size>150) "\n[PM_PKGS relevant packages truncated]" else ""
        } catch(error:Exception) {"PM_PKGS PackageManager.getInstalledPackages failed=${error.javaClass.simpleName}:${error.message?.take(200)}"}
        val r=privateReadOnlyRoot(90000L,1024*1024,DiagnosticReadOnlyScript::rootDeep)
        p("深层采样返回ok=${r.ok}；私有ROOT含授权总期限90秒，不占连接ROOT锁；1048576字符总预算。")
        p(r.text.lineSequence().filter {it.startsWith("PRIVATE_ROOT ") || it.startsWith("PRIVATE_DIAG_CHILD_CLEANUP ") || it.startsWith("DIAG_CLEANUP=") || it.startsWith("DIAG_PRIVATE_WRAPPER_END ") || it.contains("ROOT output truncated;")}.joinToString("\n"))
        if (!r.text.contains("ROOT_BEGIN_9f3a")) { p("ROOT 脚本无输出或被拒绝:\n${r.text.take(500)}"); return }
        val cfg = mapOf(
            "SVC_CHECK" to Cfg(null, 30),
            "SVC_LIST" to Cfg(listOf("blue", "bt", "usb", "wifi", "audio", "connectivity", "tun", "vpn", "package", "activity", "mount", "key", "mcu", "skypine", "hsae", "input"), 180),
            "PS" to Cfg(listOf("blue", "bt", "usb", "airuize", "diplay", "hsae", "wpa", "wifi", "audio", "media", "car"), 160),
            "PM_PKGS" to Cfg(null, 160),
            "D_USB" to Cfg(null, 200),
            "D_CONN" to Cfg(listOf("network", "interface", "wlan", "usb", "tun", "ip", "active", "connected"), 140),
            "D_AF" to Cfg(listOf("sample", "rate", "frame", "output", "input", "device", "stream", "standby", "pcm", "aac", "session"), 180),
            "D_AP" to Cfg(listOf("device", "output", "input", "sample", "rate", "stream", "mic", "speaker", "card"), 180),
            "D_AUDIO" to Cfg(listOf("media button", "media_button", "receiver", "focus", "remote control", "stream music"), 180),
            "D_WINDOW" to Cfg(listOf("mcurrentfocus", "mfocusedapp", "focus", "window #"), 80),
            "IP_RULE" to Cfg(null, 120),
            "USB_DEBUG" to Cfg(null, 320),
            "USB_ALT_STATE" to Cfg(null, 240),
            "INPUT_DEV" to Cfg(null, 800),
            "KEYLAYOUT" to Cfg(null, 6000),
            "DMESG" to Cfg(listOf("usb", "tun", "audio", "ncm", "cdc", "ipv6", "error", "fail", "denied", "fsl", "vpu"), 300),
            "ID" to Cfg(null, 10),
            "LOG_CRASH" to Cfg(null, 400),
            "LOG_MAIN" to Cfg(listOf("airuize", "diplay", "xcertplay", "usb", "vpn", "carplay", "iap", "ncm", "lockdown", "usbmux", "androidruntime", "fatal", "tun", "omx", "freescale", "audiotrack", "aflinger", "denied", "permission", "handshake", "skypine", "mcukey", "hsaeio", "mediabutton", "keyevent"), 900))
        emitSections(r.text.replace(DiagnosticReadOnlyScript.PACKAGE_PLACEHOLDER,packages), cfg, defaultCap = 200)
    }

    // ---------------- 14) verdict ----------------
    private fun verdict() {
        val v = StringBuilder()
        v.append("【ROOT】").append(if (rootOk) "已取得（主外壳复用；独立长诊断按超级用户策略授权）" else "未取得，功能/深层受限，建议授权后重跑").append("\n")
        v.append("【USB 有线】")
        if (connectRan) {
            if (connectSuccess) v.append("观察结束时 AirPlay 会话仍活动；首帧/音频单独核对媒体统计。")
            else if(sessionWasActive) v.append("AirPlay 曾激活，但观察结束时已断开。")
            else {
                val order = STAGE_ORDER.reversed()
                val farthest = order.firstOrNull { lastStages.containsKey(it) }
                v.append("自动连接未成功；最远阶段=").append(farthest ?: "（服务可能未启动/未枚举到设备）")
                if (farthest == null) v.append("：多为数据线只充电、插了充电口、或 iPhone 未解锁/未信任；请换原装数据线插数据口、手机解锁并信任。")
                v.append("。各阶段耗时见第10节。")
            }
        } else v.append("本次跳过自动连接。")
        v.append("\n")
        v.append("【会话观察】").append(observationSummary).append("\n")
        v.append("【NCM 数据面】").append(NcmStats.snapshot()).append("\n")
        v.append("【方向盘】第7节为现场下一曲/上一曲/播放/音量/语音按键的内核、MCU、前台事件；第13节为键盘能力、键位映射、媒体路由与日志。空事件需结合通道状态判断。\n")
        v.append("【音频】会话的RTP接收、AudioTrack写入和播放头推进见第10节；播放头推进只证明软件播放进展，实际听感需实车确认。独立测试见第11节。\n")
        v.append("【视频】H264 硬解=${CodecCapabilities.names("video/avc").firstOrNull() ?: "无"}；AAC=${CodecCapabilities.names("audio/mp4a-latm").isNotEmpty()}；HEVC=${CodecCapabilities.hevc()}；Opus=${CodecCapabilities.opus()}。\n")
        p(v.toString())
    }

    companion object {
        private const val WATCH_MS = 95_000L
        private val STAGE_KEYS = ConnectionMilestones.keys
        private val STAGE_ORDER = STAGE_KEYS.map { it.second }
    }
}
