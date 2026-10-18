package local.airuize.receiver

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import java.io.Closeable
import java.io.File
import java.net.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Upstream USBMUX -> Lockdown -> carkit iAP2, with NCM -> IPv6 VPN AirPlay. */
class LegacyWiredService : VpnService() {
    @Volatile var activeSession:AirPlaySession?=null; private set
    @Volatile var advertisedDisplay:AirPlayDisplayConfig?=null; private set
    @Volatile private var frozenSettings:ReceiverSettings?=null
    @Volatile private var advertisedGps:Boolean?=null
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"legacy-wired-lifecycle").apply { isDaemon=true } }
    private val evidenceWorker=Executors.newSingleThreadExecutor { Thread(it,"legacy-diagnostic-snapshot").apply { isDaemon=true } }
    private val evidencePending=java.util.concurrent.atomic.AtomicInteger()
    private val periodicSnapshotPending=AtomicBoolean()
    private val learningSamplePending=AtomicBoolean()
    private val evidence=ConnectionEvidence()
    private val milestones=ConnectionMilestones()
    private val mfiCache=ValidatedSessionCache<LocalMfiAuthenticationClient>()
    private val running=AtomicBoolean(false)
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    private val lifecycle=AttemptLifecycleGate(generation,running)
    @Volatile private var workingGeneration=0
    private data class OwnedResource(val generation: Int, val value: Closeable)
    private val resources=Collections.synchronizedList(ArrayList<OwnedResource>())
    private val cleanupState=Object()
    private val cleanupCloseLock=Any()
    private var pendingCleanups=0
    private lateinit var usb:UsbManager
    @Volatile private var media:LegacyMediaSink?=null
    @Volatile private var surface:Surface?=null
    private val surfaceApplyLock=Any()
    @Volatile private var selectedName:String?=null
    private var pendingPermission:String?=null
    private var denied=false
    private var transitionDevice:String?=null
    private var transitionTime=0L
    private val nextFrame=AtomicBoolean(true)
    @Volatile private var usbFailStreak=0
    @Volatile var attemptStartedAt=0L; private set
    private val logHistory=StringBuilder("USB CarPlay ${ReceiverActivity.VERSION}\n")
    @Volatile private var lastAudioSnapshot="音频尚无采样；请先在CarPlay播放音乐。"
    @Volatile var connectionStage:Int=-1; private set
    data class LogEvent(val sequence:Long,val monotonicMs:Long,val message:String)
    private val logEvents=java.util.ArrayDeque<LogEvent>()
    private var eventSequence=0L
    private var eventCharacters=0
    fun report()=synchronized(logHistory) { logHistory.toString() }
    fun eventsAfter(sequence:Long)=synchronized(logHistory) { logEvents.filter { it.sequence>sequence } }
    fun diagnosticEvidence():String = "连接阶段独立归档（最多8轮；不受日志滚动影响）：\n" +
        milestones.history().joinToString("\n") { "attempt=${it.attempt} startMono=${it.startedMs} firstMs=${it.first}" } + "\n" + evidence.snapshot()
    internal fun latestMilestones():ConnectionMilestones.Snapshot? = milestones.latest()
    fun mediaDiagnosticSnapshot():String = (media?.diagnosticSummary() ?: "媒体链路尚未建立或已清理") +
        "\n" + (activeSession?.audioEndpointDiagnosticSnapshot() ?: "CarPlay音频端点：当前无活动会话") +
        "\n" + SteeringMediaDiagnostics.snapshot()
    fun featureConfigurationSnapshot():String {
        val current=ReceiverSettings.read(this)
        val frozen=lifecycle.mutate { frozenSettings to advertisedGps }
        return FeatureConfigurationDiagnostics.describe(current,frozen.first,frozen.second) +
            "\n方向盘切歌偏好当前=${getSharedPreferences("receiver-ui",MODE_PRIVATE).getBoolean("steeringMedia",false)}；" +
            "启用需重连，关闭后停止只读轮询；默认关闭，不自动接管原厂按键。"
    }

    fun gpsConnectionConfiguration():Pair<Boolean?,Boolean?> = lifecycle.mutate { frozenSettings?.gps to advertisedGps }
    internal fun currentSettingsSnapshot():ReceiverSettings? = lifecycle.mutate { frozenSettings }
    fun audioDiagnosticSnapshot():String {
        val current=media
        return if(current!=null) runCatching {current.audioDiagnosticSummary()}.getOrDefault(lastAudioSnapshot)
            else "最近音频采样（当前没有媒体接收器）：\n$lastAudioSnapshot"
    }
    fun learningVoiceActive():Boolean = media?.isVoicePlaybackActive() == true
    fun isConnectionInProgress():Boolean = running.get() && (selectedName!=null || activeSession!=null)
    private fun queueLearning(id:Int,message:String) {
        if(id!=generation.get() || !learningSamplePending.compareAndSet(false,true)) return
        val sampled=media
        try { evidenceWorker.execute {
            try {
                if(id==generation.get() && sampled!=null && media===sampled) {
                    PerformanceLearning.pipelineDiagnostic(id.toLong(),message)
                    sampled.learningWindow()?.let { PerformanceLearning.observe(id.toLong(),it) }
                    PerformanceLearning.recordCapabilities(sampled.diagnostics.decoderName,
                        ScreenCryptoNative.diagnosticSnapshot().contains("selfTest=passed"))
                    FeatureLearning.observePassiveGps(applicationContext)
                }
            } catch(_:Exception) { /* Optional learning cannot stop a connection. */ }
            finally { learningSamplePending.set(false) }
        } } catch(_:java.util.concurrent.RejectedExecutionException) { learningSamplePending.set(false) }
    }
    private fun checkpoint(phase:String,id:Int=workingGeneration) {
        synchronized(logHistory) {
        if(id!=generation.get()) return
        val requested=android.os.SystemClock.elapsedRealtime()
        val sampledMedia=media.takeIf { id==workingGeneration }
        // Freeze transport counters before cleanup or a later attempt resets the global counters.
        evidence.add(id,phase,"requestedMono=$requested active=${activeSession!=null} ncm=${NcmStats.snapshot()}\n" +
            "alt=${LegacyUsbPlatform.diagnosticSnapshot()} native=${UsbIfaceNative.diagnosticSnapshot()} out=${LegacyNcmUsbIo.diagnosticSnapshot()}\nreads=${LegacyUsbIo.snapshot()}")
        if(evidencePending.incrementAndGet()>2) { evidencePending.decrementAndGet(); evidence.add(id,"$phase-runtime","采样队列已满，跳过；即时计数已保留"); return }
        try { evidenceWorker.execute {
            try {
                evidence.add(id,"$phase-runtime","requestedMono=$requested currentAttempt=${id==workingGeneration}；系统表为后台采样时状态，失败后接口可能已清理\n" +
                    ConnectionRuntimeProbe.snapshot()+"\n媒体对象计数（可能已关闭）="+(sampledMedia?.diagnosticSummary() ?: "未建立"))
            } catch(error:Exception) { evidence.add(id,"$phase-runtime","采样失败:${error.javaClass.simpleName}") }
            finally { evidencePending.decrementAndGet() }
        } } catch(_:java.util.concurrent.RejectedExecutionException) { evidencePending.decrementAndGet() }
        }
    }
    private fun log(message:String) = logForAttempt(message,null)
    private fun logForAttempt(message:String,expected:Int?) { synchronized(logHistory) {
        if(expected!=null && expected!=generation.get()) return@synchronized
        val logGeneration=expected ?: workingGeneration
        val now=android.os.SystemClock.elapsedRealtime()
        val bounded=if(message.length>16000) message.take(16000)+" [message truncated]" else message
        connectionStage=ConnectionStages.advance(connectionStage,bounded)
        milestones.record(logGeneration,now,bounded)
        logHistory.append("[mono=$now] ").append(bounded).append('\n')
        if(logHistory.length>60000) logHistory.delete(0,logHistory.length-48000)
        logEvents.addLast(LogEvent(++eventSequence,now,bounded)); eventCharacters+=bounded.length
        while(logEvents.size>256 || eventCharacters>60000) eventCharacters-=logEvents.removeFirst().message.length
        if(message.startsWith("USB first ") || message.startsWith("USB audio playback progressed"))
            evidence.add(logGeneration,"media-milestone",bounded)
    } }
    /** Only call within lifecycle: publication and invalidation must share the same lock. */
    private fun clearPublished() { activeSession=null; media=null; advertisedDisplay=null; frozenSettings=null; advertisedGps=null }
    fun setSurface(value:Surface?,force:Boolean=false) {
        lifecycle.mutate {
            if((surface !== value || force) && value != null) nextFrame.set(true)
            surface=value
        }
        applySurface(force=force)
    }
    /** Serialize application outside lifecycle so a worker's initial Surface cannot undo a newer UI update. */
    private fun applySurface(expected:Int?=null,force:Boolean=false) = synchronized(surfaceApplyLock) {
        val target=lifecycle.mutate {
            if(expected!=null && !lifecycle.isCurrent(expected)) null
            else media?.let { it to surface }
        }
        if(target!=null) target.first.setSurface(target.second,force)
    }
    private fun <T:Closeable> own(value:T, expected:Int=workingGeneration):T {
        val accepted=lifecycle.withCurrent(expected) { synchronized(resources) { resources.add(OwnedResource(expected,value)) } }
        if(!accepted) { runCatching { value.close() }; error("USB stopped") }
        return value
    }
    private val poll=object:Runnable { override fun run() {
        try {
            if(!running.get()) return
            if(selectedName==null && !denied) discover()
            val sampledMedia=media
            val id=workingGeneration
            if(sampledMedia!=null && periodicSnapshotPending.compareAndSet(false,true)) {
                try { evidenceWorker.execute {
                    try {
                        val summaries=sampledMedia.diagnosticSummaries()
                        if(running.get() && id==workingGeneration && media===sampledMedia) {
                            lastAudioSnapshot="attempt=$id monoMs=${android.os.SystemClock.elapsedRealtime()}\n"+summaries.second
                            logForAttempt(summaries.first,id)
                        }
                    } catch(error:Exception) {
                        if(running.get() && id==workingGeneration) logForAttempt("媒体周期采样失败(已忽略):${error.javaClass.simpleName}",id)
                    } finally { periodicSnapshotPending.set(false) }
                } } catch(_:java.util.concurrent.RejectedExecutionException) { periodicSnapshotPending.set(false) }
            }
        } catch(e:Throwable) { log("USB轮询异常(已拦截):${e.javaClass.simpleName}:${e.message}") }
        if(running.get()) main.postDelayed(this,5000)
    } }
    private val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) {
        val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if(device.vendorId!=0x05ac) return
        when(intent.action) {
            permissionAction -> { pendingPermission=null
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usb.hasPermission(device)) queue(device)
                else { denied=true; log("USB permission denied; reconnect cable or select USB to retry") }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> if(selectedName==null && !denied) discover()
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val oldGeneration=lifecycle.mutate { if(device.deviceName==selectedName) generation.get() else null }
                if(oldGeneration!=null) {
                    log("USB detached"); checkpoint("usb-detached",oldGeneration)
                    val invalidated=lifecycle.mutate {
                        if(generation.get()!=oldGeneration || device.deviceName!=selectedName) false
                        else { lifecycle.advance { clearPublished() }; selectedName=null; true }
                    }
                    if(invalidated) cleanupAsync(oldGeneration)
                }
            }
        }
    } }
    private val permissionAction get()="$packageName.USB_PERMISSION"
    override fun onCreate() {
        super.onCreate(); CrashGuard.install(this); instance=this
        runCatching { PerformanceLearning.initialize(applicationContext) }
        lifecycle.mutate { surface=videoSurface }
        com.shilapi.xcertplay.transport.TlsDuplexChannel.preloadInBackground()
        try {
            usb=getSystemService(USB_SERVICE) as UsbManager
            registerReceiver(receiver,IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
            val su=listOf("/system/bin/su","/system/xbin/su","/sbin/su","/su/bin/su","/vendor/bin/su","/data/local/xbin/su").filter { File(it).exists() }
            log("USB服务初始化：usb.host=${packageManager.hasSystemFeature("android.hardware.usb.host")} /dev/tun=${File("/dev/tun").exists()} su=$su")
        } catch(e:Throwable) { log("USB服务初始化异常(已拦截):${e.javaClass.simpleName}:${e.message}"); stopSelf() }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        try {
            if(!::usb.isInitialized) { log("USB服务未正确初始化，停止"); stopSelf(); return START_NOT_STICKY }
            if(!lifecycle.mutate { running.compareAndSet(false,true) }) { denied=false; runCatching { discover() }; return START_NOT_STICKY }
            log("TUN 由 ROOT 原生 helper 直接建立（绕过该ROM裁坏的VpnService/ConnectivityService），无需VPN授权弹窗")
            val open=PendingIntent.getActivity(this,0,Intent(this,ReceiverActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT)
            try { startForeground(44,Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DiPlay USB CarPlay").setContentText("USB / NCM session").setContentIntent(open).setOngoing(true).build()) }
            catch(e:Throwable) { log("前台通知建立失败(忽略继续):${e.javaClass.simpleName}") }
            log("Firmware path: USB Host / descriptor configuration / NCM NTB16 / IPv6 TUN")
            main.post(poll)
        } catch(e:Throwable) {
            log("USB onStartCommand异常(已拦截):${e.javaClass.simpleName}:${e.message}")
            lifecycle.mutate { running.set(false); lifecycle.advance { clearPublished() } }
            runCatching { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun discover() { try {
        val devices=usb.deviceList.values.filter { it.vendorId==0x05ac }
        log("USB enumeration: hostFeature=${packageManager.hasSystemFeature("android.hardware.usb.host")} total=${usb.deviceList.size} apple=${devices.size}")
        devices.forEach { log("USB Apple设备: name=${it.deviceName} pid=0x${it.productId.toString(16)} 已授权=${usb.hasPermission(it)}") }
        val device=devices.firstOrNull() ?: return
        if(!usb.hasPermission(device)) {
            if(pendingPermission==device.deviceName) return
            pendingPermission=device.deviceName
            usb.requestPermission(device,PendingIntent.getBroadcast(this,0,Intent(permissionAction).setPackage(packageName),PendingIntent.FLAG_UPDATE_CURRENT))
            log("USB权限请求：请在车机弹窗勾选“默认用于此设备/始终”再点允许，以后插入将直接交给DiPlay，避免其它程序抢占CarPlay通道"); return
        }
        queue(device)
    } catch(e:Throwable) { log("USB枚举异常(已拦截):${e.javaClass.simpleName}:${e.message}") } }
    private fun claimWithRetry(connection: UsbDeviceConnection, iface: UsbInterface): Boolean {
        for(attempt in 1..5) {
            try { if(connection.claimInterface(iface,true)) { log("USB接口${iface.id}已由DiPlay独占 claim成功(force=true) 尝试=$attempt"); return true } }
            catch(e:Throwable){ log("USB接口${iface.id} claim异常(已拦截) 尝试=$attempt:${e.javaClass.simpleName}:${e.message}") }
            if(attempt<5) try { Thread.sleep(500) } catch(_:InterruptedException){ Thread.currentThread().interrupt(); return false }
        }
        return false
    }
    /** Run a shell script via ROOT through the persistent RootShell (one authorization for the whole app). Read-only of system apart from switching the iPhone USB configuration / VPN consent. */
    private fun rootShell(script:String,timeoutMs:Long=12000L):String {
        return try {
            RootShell.execScript(this,script,timeoutMs).text
        } catch(e:Throwable) { "ROOT_EXEC_FAIL ${e.javaClass.simpleName}:${e.message}\n" }
    }
    /**
     * Make the KERNEL actually activate the iPhone's CarPlay USB configuration.
     * On this i.MX6/Android4.4 ROM a userspace controlTransfer(SET_CONFIGURATION) does not update the
     * kernel's active config (the device stays in Cfg#1 PTP), so later bulk URBs fail. Writing the sysfs
     * bConfigurationValue as ROOT goes through the kernel's usb_set_configuration() path, the equivalent
     * of the proper USBDEVFS_SETCONFIGURATION ioctl that API18 does not expose. Session-only; unplug or
     * set back to 1 restores the phone. Nothing under /system is modified and the 4G module/U-disk/charging
     * live on the other bus/ports and are untouched (we match idVendor=05ac only).
     */
    private fun kernelSetConfiguration(target:Int):Boolean {
        val script =
            "#!/system/bin/sh\n" +
            "T=\"\"\n" +
            "for d in /sys/bus/usb/devices/*; do\n" +
            "  [ -f \"\$d/idVendor\" ] || continue\n" +
            "  V=\$(cat \$d/idVendor 2>/dev/null)\n" +
            "  if [ \"\$V\" = \"05ac\" ] || [ \"\$V\" = \"05AC\" ]; then if [ -f \"\$d/bConfigurationValue\" ]; then T=\$d; break; fi; fi\n" +
            "done\n" +
            "if [ -z \"\$T\" ]; then echo SETCFG_NO_TARGET; exit 0; fi\n" +
            "B=\$(cat \$T/bConfigurationValue 2>/dev/null)\n" +
            "echo $target > \$T/bConfigurationValue 2>/dev/null\n" +
            "sleep 1\n" +
            "A=\$(cat \$T/bConfigurationValue 2>/dev/null)\n" +
            "if [ \"\$A\" != \"$target\" ]; then echo SETCG DIRECT_FAIL_RESET; echo 0 > \$T/bConfigurationValue 2>/dev/null; sleep 1; echo $target > \$T/bConfigurationValue 2>/dev/null; sleep 2; A=\$(cat \$T/bConfigurationValue 2>/dev/null); fi\n" +
            "echo SETCG TARGET=\$T BEFORE=\$B AFTER=\$A\n" +
            "if [ \"\$A\" = \"$target\" ]; then echo SETCFG_CONFIRMED; else echo SETCFG_NOTCONFIRMED; fi\n"
        val out=rootShell(script)
        log("USB ROOT内核切配置返回：\n"+out.trim())
        return out.contains("SETCFG_CONFIRMED")
    }
    private fun queue(device:UsbDevice) {
        val id=lifecycle.mutate {
            if(!running.get() || selectedName!=null) null
            else { selectedName=device.deviceName; lifecycle.advance { clearPublished() } }
        } ?: return
        worker.execute {
            if(!awaitCleanups()) return@execute
            if(!lifecycle.withCurrent(id) { workingGeneration=id }) return@execute
            try { startStack(device,id) }
            catch(error:Throwable) {
                if(id==generation.get() && running.get()) {
                    log("USB stage failed: ${error.javaClass.simpleName}: ${error.message}")
                    runCatching { val sw=java.io.StringWriter(); error.printStackTrace(java.io.PrintWriter(sw)); log(sw.toString().trim()) }
                    (error as? Exception)?.let { FailureDiagnostics.lines("USB",it).forEach(::log) }
                    log("USB attempt=$id failure snapshot: alt=${LegacyUsbPlatform.diagnosticSnapshot()} native=${UsbIfaceNative.diagnosticSnapshot()} out=${LegacyNcmUsbIo.diagnosticSnapshot()} ncm=${NcmStats.snapshot()}")
                    checkpoint("stack-failure",id)
                    runCatching { PerformanceLearning.noteFailure(id.toLong(),"CONNECTION_FAILURE") }
                }
                if(id==generation.get() && running.get()) {
                    val backoff=2000L+2000L*usbFailStreak.coerceAtMost(6); usbFailStreak++
                    log("USB链路失败，退避${backoff}ms后重试（首次也退避，避免密集重置iPhone/日志刷屏）")
                    try{Thread.sleep(backoff)}catch(_:InterruptedException){Thread.currentThread().interrupt()}
                }
                cleanup(id)
            } finally {
                // Keep discovery from queueing a new token before failure reporting/backoff ends.
                // A physical detach may still invalidate the token immediately.
                lifecycle.mutate { if(id==generation.get()) selectedName=null }
            }
        }
    }
    private fun startStack(device:UsbDevice,id:Int) {
        runCatching { FeatureLearning.cancelCalibration() }
        synchronized(logHistory) {
            connectionStage=-1
            attemptStartedAt=android.os.SystemClock.elapsedRealtime()
            milestones.begin(id,attemptStartedAt)
        }
        log("USB enumeration selected attempt=$id")
        NcmStats.reset()
        LegacyUsbPlatform.resetDiagnostic()
        LegacyNcmUsbIo.resetDiagnostic()
        UsbIfaceNative.ensureLoaded(this)
        ScreenCryptoNative.ensureLoaded(this)
        log(ScreenCryptoNative.diagnosticSnapshot())
        checkpoint("attempt-start",id)
        var first=usb.openDevice(device) ?: error("USB open failed"); var live=device
        var transferred=false
        try {
        val power=getSystemService(POWER_SERVICE) as android.os.PowerManager
        val wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"DiPlay:USB").apply { setReferenceCounted(false); acquire() }
        FunctionRunDiagnostics.event("wake","acquired attempt=$id held=${wake.isHeld}")
        own(Closeable { if(wake.isHeld) wake.release(); FunctionRunDiagnostics.event("wake","released attempt=$id held=${wake.isHeld}") })
            val descriptors=LegacyUsbPlatform.configurations(first)
            var configuration=IphoneCarPlayConfiguration.find(descriptors)
            log("USB descriptors configurations=${descriptors.map { it.id }} CarPlay=${configuration?.id}")
            if(configuration==null) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(transitionDevice==device.deviceName && now-transitionTime<30000) { log("USB waiting for CarPlay re-enumeration"); return }
                transitionDevice=device.deviceName; transitionTime=now
                val response=ByteArray(1)
                val result=first.controlTransfer(0xc0,0x52,0,4,response,1,1000)
                log("USB Apple vendor transition request result=$result; waiting for re-enumeration")
                check(result>=0) { "USB vendor transition failed" }
                return
            }
            val targetId=configuration?.id ?: error("CarPlay target config id missing")
            var active=LegacyUsbPlatform.activeConfiguration(first)
            log("USB 当前活动配置=$active 目标CarPlay配置=$targetId（内核必须真正切到$targetId，USBMUX bulk端点才存在）")
            if(active!=targetId) {
                // Release our handle first so the kernel's set-configuration is not refused EBUSY.
                runCatching { first.close() }
                log("USB 经ROOT写 sysfs bConfigurationValue 让内核真正切换到配置$targetId（仅本次连接生效，拔线即恢复；不改/system，不影响U盘/充电/4G模块）")
                val rootOk=kernelSetConfiguration(targetId)
                Thread.sleep(1500)
                live=usb.deviceList.values.firstOrNull { it.vendorId==0x05ac } ?: live
                val reopened=usb.openDevice(live)
                if(reopened==null) { log("USB 切配置后重新打开失败"); error("USB reopen failed after kernel setcfg") }
                first=reopened
                active=LegacyUsbPlatform.activeConfiguration(first)
                log("USB ROOT切配置完成 rootOk=$rootOk 读回活动配置=$active")
                if(active!=targetId) {
                    val fb=runCatching { LegacyUsbPlatform.selectConfiguration(first,targetId) }.getOrDefault(false)
                    Thread.sleep(800)
                    log("USB sysfs后活动配置仍非$targetId，controlTransfer回退=$fb 最终读回=${runCatching { LegacyUsbPlatform.activeConfiguration(first) }.getOrDefault(-1)}")
                }
                val again=LegacyUsbPlatform.configurations(first)
                configuration=IphoneCarPlayConfiguration.find(again)
                    ?: run { log("USB 切配置后CarPlay配置消失，等待下一轮重枚举"); error("CarPlay config missing after setcfg") }
            } else {
                log("USB 活动配置已是$targetId，无需内核切换")
            }
            val cfg=configuration ?: error("CarPlay config unavailable after activation")
            val tunDevice=TunHelper.establish(this,::log)
            val tun=own(tunDevice)
            log("本地 IPv6 TUN 已就绪 iface=${tunDevice.iface}；随后 USBMUX/TLS/iAP2，iPhone 通告时 TUN 已可用")
            val muxInterface=IphoneCarPlayConfiguration.usbMuxInterface(cfg) ?: error("USBMUX interface absent")
            val endpoints=IphoneCarPlayConfiguration.usbMuxEndpoints(muxInterface) ?: error("USBMUX endpoints absent")
            check(claimWithRetry(first,muxInterface)) { "USBMUX接口被其它程序或驱动占用：请在系统USB弹窗选DiPlay并勾“始终”，或暂停/卸载其它占用USB的CarPlay/串口程序后重插数据线" }
            log("USB configuration=${cfg.id}; USBMUX interface=${muxInterface.id}; bulk endpoints selected")
            log("USB独占：USBMUX接口已由DiPlay强制claim并在会话期间持续持有，其它程序无法同时使用CarPlay数据通道（U盘/充电/ADB不受影响）")
            val pipe=own(Iap2UsbSession(first,endpoints.first,endpoints.second)); transferred=true
            val mux=own(Iap2UsbMuxHost.open(pipe)); log("USBMUX ready")
            val ncmConnection=usb.openDevice(live) ?: error("NCM open failed")
            val function=NcmFunctionDiscovery.find(cfg)
            if(function==null) { ncmConnection.close(); error("CDC NCM function absent") }
            val ncm=own(NcmUsbBridge.open(ncmConnection,function))
            log("KERNEL verified ${LegacyUsbPlatform.diagnosticSnapshot()}")
            log("NCM interfaces claimed; data alternate setting selected")
            checkpoint("ncm-interfaces-ready",id)
            val prefs=getSharedPreferences("receiver-usb",MODE_PRIVATE)
            val hostId=prefs.getString("hostId",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("hostId",it).commit() }
            val buid=prefs.getString("buid",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("buid",it).commit() }
            log("Lockdown pairing: unlock iPhone and confirm Trust if prompted")
            val records=LegacyPairRecords(this)
            fun pair():LockdownPairRecord = LockdownPairingClient(mux).pair("DiPlay Legacy",hostId,buid,120000) { !running.get() || id!=generation.get() }.pairRecord.also { records.save(it) }
            val saved=records.load()
            val pairRecord=saved ?: pair()
            val attemptLog:(String)->Unit={ logForAttempt(it,id) }
            val carkit=own(try { LockdownCarKitClient(mux, attemptLog).open(pairRecord,"DiPlay Legacy") } catch(error:IphoneUsbException) {
                // Upstream retries only explicit saved-record rejection, never generic TLS/I/O failure.
                val rejected=listOf("InvalidHostID", "InvalidPairRecord", "InvalidPairingRecord", "MissingPairRecord").any { error.message?.contains(it)==true }
                if(saved==null || !rejected) throw error
                records.clear(); log("Saved USB pair record rejected; pairing again")
                LockdownCarKitClient(mux, attemptLog).open(pair(),"DiPlay Legacy")
            }); log("Lockdown carkit ready")
            val iap=own(Iap2Session.open(carkit)); log("Wired iAP2 link ready")
            val requested=ReceiverSettings.read(this)
            val audioAdjusted=runCatching { requested.copy(bufferMillis=FeatureLearning.resolvedAudioBuffer(this,requested)) }.getOrDefault(requested)
            val settings=runCatching { PerformanceLearning.resolveSettings(audioAdjusted) }.getOrDefault(audioAdjusted)
            val display=settings.display()
            check(lifecycle.withCurrent(id) { advertisedDisplay=display; frozenSettings=settings; advertisedGps=null; nextFrame.set(true) }) { "USB stopped" }
            log(settings.displaySummary())
            val hostMac=ncm.hostMac ?: byteArrayOf(2,0,0,0,0,43)
            val literal="fe80::2"
            own(Ipv6NcmBridge(ncm,tun.pfd,hostMac, { error ->
                if(id==generation.get()) {
                attemptLog("NCM IPv6 failure=${error.javaClass.simpleName}:${error.message?.take(180)} alt=${LegacyUsbPlatform.diagnosticSnapshot()} native=${UsbIfaceNative.diagnosticSnapshot()} out=${LegacyNcmUsbIo.diagnosticSnapshot()} ncm=${NcmStats.snapshot()}")
                checkpoint("ncm-failure",id)
                main.post {
                    val stoppedGeneration=lifecycle.mutate {
                        if(!lifecycle.isCurrent(id)) null else lifecycle.advance { clearPublished() }
                    }
                    if(stoppedGeneration!=null) cleanupAsync(id) { main.post {
                        lifecycle.mutate { if(generation.get()==stoppedGeneration) selectedName=null }
                    } }
                }
                }
            }, { message -> if(id==generation.get()) { attemptLog(message); if(message.startsWith("ncm bulk-out became ready")) checkpoint("ncm-first-out",id) } })).start()
            log("NCM IPv6 TUN 桥接已启动（ROOT helper TUN，fe80::2）")
            val identity=IdentityStore.load(this)
            // APK assets are immutable for this service lifetime. Reconnect reuses only an identity
            // that has already passed the original certificate/key/signature validation.
            val mfi=mfiCache.getOrLoad {
                val authDir=File(filesDir,"offline-mfi").apply { mkdirs() }
                for(name in listOf("identity.pk8","certificate.p7b")) assets.open("offline-mfi/$name").use { input -> File(authDir,name).outputStream().use { input.copyTo(it) } }
                LocalMfiAuthenticationClient.load(authDir)
            }
            val deviceId=hostMac.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val config=AirPlayConfig("DiPlay Legacy",deviceId,deviceId,"950.7.1",display,
                rightHandDrive=settings.rightHandDrive,
                // This head unit's primary output is fixed at 44100 Hz (audio_policy.conf). Advertise
                // 44.1 kHz entertainment AAC/PCM so the phone streams 44.1 kHz and AudioTrack runs at the
                // native rate with no AudioFlinger resample: most reliable, in tune and lowest CPU on A9.
                entertainmentSampleRate=44100,
                // A complete CarPlay audio endpoint includes an input. Capture remains governed by
                // settings.microphone inside the sink; disabled capture produces synthetic silence.
                hevc=settings.hevc&&CodecCapabilities.hevc(), opus=settings.opus&&CodecCapabilities.opus(),microphone=true,icons=LegacyOem.icons(),oemLabel="DiPlay",manufacturer="DiPlay Legacy",model="iMX6-Android43")
            val sink=own(LegacyMediaSink(attemptLog,{
                var firstFrame=false
                lifecycle.withCurrent(id) { firstFrame=nextFrame.compareAndSet(true,false) }
                if(firstFrame) attemptLog("USB first video frame rendered")
            },display.widthPixels,display.heightPixels,settings,this))
            check(lifecycle.withCurrent(id) { media=sink }) { "USB stopped" }
            runCatching { PerformanceLearning.beginSession(id.toLong(),settings) }
            own(Closeable { runCatching { PerformanceLearning.endSession(id.toLong()) } },id)
            applySurface(id)
            val engine=CarPlayMediaEngine(sink,microphoneEnabled=true)
            if(getSharedPreferences("receiver-ui",MODE_PRIVATE).getBoolean("steeringMedia",false)) {
                runCatching { own(HsaeSteeringMediaAdapter(this,
                    { activeSession.takeIf { id==generation.get() && running.get() } },
                    { id==generation.get() && sink.isMediaPlaybackActive() }),id) }
                    .onFailure { error ->
                        SteeringMediaDiagnostics.failure(error.javaClass.simpleName)
                        log("方向盘切歌适配不可用，已忽略：${error.javaClass.simpleName}")
                    }
            }
            val pairPrefs=getSharedPreferences("receiver-pairings",MODE_PRIVATE)
            val pairings=PairingStore { id,key -> pairPrefs.edit().putString(id,android.util.Base64.encodeToString(key,2)).commit() }
            pairPrefs.all.forEach { (id,value)-> if(value is String) runCatching { pairings.save(id,android.util.Base64.decode(value,2)) } }
            val airPlayBind=tunBindAddress(tunDevice.iface)
            val server=ServerSocket()
            own(Closeable { server.close() },id)
            server.reuseAddress=true; server.bind(InetSocketAddress(airPlayBind,config.port))
            log("AirPlay \u76d1\u542c [${airPlayBind.hostAddress}]:${config.port}\uff08\u4f7f\u7528 TUN \u672c\u5730\u5730\u5740\u53ca\u63a5\u53e3 scope\uff09")
            log("USB AirPlay listener ready port=${config.port} scope=${airPlayBind.scopeId}")
            checkpoint("airplay-listener",id)
            Thread({ try { while(running.get() && id==generation.get()) {
                val socket=server.accept()
                var candidate:AirPlaySession?=null
                try {
                    if(id!=generation.get()) { socket.close(); break }
                    socket.tcpNoDelay=true
                    attemptLog("USB AirPlay TCP accepted"); checkpoint("airplay-tcp-accepted",id)
                    attemptLog("USB AirPlay socket local ${SocketDiagnostics.address(socket.localAddress)} peer ${SocketDiagnostics.address(socket.inetAddress)}")
                    val session=AirPlaySession(socket,config,identity,pairings,mfi,object:AirPlaySessionListener {
                    override fun onSessionActive(session:AirPlaySession) {
                        if(lifecycle.withCurrent(id) { activeSession=session; usbFailStreak=0 }) {
                            attemptLog("USB AirPlay active"); checkpoint("airplay-record",id)
                        }
                    }
                    override fun onSessionEnded(session:AirPlaySession) {
                        if(!lifecycle.withCurrent(id) { if(activeSession===session) activeSession=null }) return
                        checkpoint("airplay-ended",id); attemptLog("USB AirPlay ended ncm=${NcmStats.snapshot()}")
                    }
                    override fun onDebugLog(message:String) { if(id==generation.get()) AirPlayDiagnostics.summary(message)?.let {
                        attemptLog(it)
                        if(it.startsWith("VideoPipeline ")) queueLearning(id,it)
                    } }
                    override fun onHostUiRequested(session:AirPlaySession) { if(id==generation.get()) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    override fun onControlFailure(stage:String,error:Throwable) { if(id==generation.get()) {
                        val details="USB AirPlay failure stage=$stage ${SocketDiagnostics.failure(error)} local ${SocketDiagnostics.address(socket.localAddress)} peer ${SocketDiagnostics.address(socket.inetAddress)}"
                        attemptLog(details); evidence.add(id,"airplay-error-details",details); checkpoint("airplay-control-failure",id)
                        runCatching { PerformanceLearning.noteFailure(id.toLong(),"CONTROL_FAILURE") }
                    } }
                    },engine)
                    candidate=session
                    own(session,id).start()
                } catch(error:Throwable) {
                    runCatching { candidate?.close() }
                    runCatching { socket.close() }
                    if(id==generation.get()) {
                        val details="USB AirPlay session open failed ${SocketDiagnostics.failure(error)}"
                        attemptLog(details); evidence.add(id,"airplay-session-open-failure",details); checkpoint("airplay-session-open-failure",id)
                    }
                }
            } } catch(error:Throwable) { if(running.get() && id==generation.get()) {
                val details="USB AirPlay listener ended ${SocketDiagnostics.failure(error)}"
                attemptLog(details); evidence.add(id,"airplay-listener-failure",details); checkpoint("airplay-listener-failure",id)
            } } },"legacy-wired-airplay").apply { isDaemon=true; start() }
            val location=if(settings.gps) runCatching { own(LegacyVehicleLocationProvider(this,"USB",attemptLog)) }.onFailure { log("车辆位置/方向盘服务不可用(已忽略，不影响CarPlay连接):${it.javaClass.simpleName}:${it.message}") }.getOrNull() else null
            val gpsOk=settings.gps && location!=null
            lifecycle.withCurrent(id) { advertisedGps=gpsOk }
            val identification=Iap2IdentificationConfig("DiPlay Legacy","iMX6-Android43","DiPlay Legacy",identity.pairingId,"0.21","iMX6", carPlayUsbInterfaceNumber=muxInterface.id).copy(locationInformationEnabled=gpsOk,vehicleSpeedEnabled=gpsOk && VehicleSpeedPolicy.gearMap(getSharedPreferences("receiver-ui",0).getString("vehicleGearMap", "") ?: "").isNotEmpty())
            val endpoint=Iap2WiredCarPlayEndpoint(listOf(literal),config.port,identity.publicKeyHex,config.sourceVersion,deviceId)
            log("Wired iAP2 authentication / StartCarPlaySession")
            Iap2WiredControlClient(iap,Iap2MfiAuthenticationClient(mfi)).run(identification,endpoint,availableCurrentMilliAmps=500,
                timeoutMillis=24*60*60*1000L,locationProvider=location,onProgress={ attemptLog(FailureDiagnostics.progress(it)) })
        } finally {
            if(!transferred) runCatching { first.close() }
            if(id==workingGeneration) checkpoint("stack-exit-before-cleanup",id)
            cleanup(id)
        }
    }
    private fun tunBindAddress(iface:String):java.net.Inet6Address {
        val nif=java.net.NetworkInterface.getByName(iface) ?: error("TUN \u63a5\u53e3 $iface \u4e0d\u5b58\u5728\uff0c\u65e0\u6cd5\u7ed1\u5b9a AirPlay")
        val found=nif.inetAddresses.toList().filterIsInstance<java.net.Inet6Address>().firstOrNull { addr ->
            val b=addr.address
            b.size==16 && (b[0].toInt() and 255)==0xfe && (b[1].toInt() and 255)==0x80 && (b[15].toInt() and 255)==0x02 && addr.scopeId!=0
        }
        if(found==null) error("TUN $iface \u4e0a\u672a\u627e\u5230\u5e26 scope \u7684 fe80::2\uff0cAirPlay \u65e0\u6cd5\u7ed1\u5b9a\uff08helper \u662f\u5426\u6210\u529f\u914d\u5730\u5740\uff1f\uff09")
        return found
    }
    private fun cleanup(expected:Int?=null) = synchronized(cleanupCloseLock) {
        val values=lifecycle.mutate {
            val target=expected ?: generation.get()
            lifecycle.retire(target) { clearPublished() }
            synchronized(resources) {
                resources.filter { expected==null || it.generation==expected }.also { resources.removeAll(it) }.map { it.value }.reversed()
            }
        }
        values.forEach { runCatching { it.close() } }
    }
    private fun cleanupAsync(expected:Int?=null, after:()->Unit={}) {
        synchronized(cleanupState) { pendingCleanups++ }
        Thread({
            try { cleanup(expected) } finally {
                synchronized(cleanupState) { pendingCleanups--; cleanupState.notifyAll() }
                after()
            }
        },"legacy-usb-cleanup").apply { isDaemon=true; start() }
    }
    private fun awaitCleanups():Boolean = try {
        synchronized(cleanupState) { while(pendingCleanups>0 && running.get()) cleanupState.wait(250) }
        synchronized(cleanupCloseLock) { }
        running.get()
    } catch(_:InterruptedException) { Thread.currentThread().interrupt(); false }
    override fun onRevoke() { log("VPN permission revoked"); stopSelf(); super.onRevoke() }
    override fun onDestroy() {
        lifecycle.mutate { running.set(false); lifecycle.advance { clearPublished() } }
        main.removeCallbacks(poll)
        runCatching { unregisterReceiver(receiver) }
        cleanupAsync()
        worker.shutdownNow(); evidenceWorker.shutdownNow(); instance=null; super.onDestroy()
    }
    companion object {
        @Volatile var instance:LegacyWiredService?=null
        @Volatile private var videoSurface:Surface?=null
        fun setVideoSurface(value:Surface?,force:Boolean=false) { videoSurface=value; instance?.setSurface(value,force) }
    }
}
