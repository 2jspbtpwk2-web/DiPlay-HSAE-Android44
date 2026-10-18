package local.airuize.receiver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.Parcelable
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/** USB wire descriptors retain configuration IDs and alternate settings absent from SDK18. */
object UsbDescriptorParser {
    data class Endpoint(val address:Int,val attributes:Int,val packet:Int,val interval:Int)
    data class Interface(val id:Int,val alternate:Int,val klass:Int,val subclass:Int,val protocol:Int,val endpoints:MutableList<Endpoint> = ArrayList())
    data class Configuration(val id:Int,val interfaces:MutableList<Interface> = ArrayList())
    fun parse(raw:ByteArray): List<Configuration> {
        val configs=ArrayList<Configuration>(); var current:Configuration?=null; var iface:Interface?=null; var offset=0
        fun value(i:Int)=raw[offset+i].toInt() and 255
        while(offset+2<=raw.size) {
            val length=value(0); val type=value(1)
            require(length>=2 && offset+length<=raw.size) { "Malformed USB descriptor" }
            when(type) {
                2 -> { require(length>=9); current=Configuration(value(5)); configs.add(current); iface=null }
                4 -> { require(length>=9); iface=Interface(value(2),value(3),value(5),value(6),value(7)); current?.interfaces?.add(iface) }
                5 -> { require(length>=7); iface?.endpoints?.add(Endpoint(value(2),value(3),value(4) or (value(5) shl 8),value(6))) }
            }
            offset+=length
        }
        require(offset==raw.size)
        return configs
    }
}
class LegacyUsbConfiguration(val id:Int, private val interfaces:List<UsbInterface>) {
    val interfaceCount get()=interfaces.size
    fun getInterface(index:Int)=interfaces[index]
}
object LegacyUsbPlatform {
    private val alternates=Collections.synchronizedMap(WeakHashMap<UsbInterface,Int>())
    private const val TAG="DiPlayUsb"
    @Volatile private var lastSelectionDiagnostic="(尚未尝试 USB alternate setting 切换)"
    fun alternate(iface:UsbInterface):Int=alternates[iface] ?: 0
    fun diagnosticSnapshot():String=lastSelectionDiagnostic
    fun resetDiagnostic() { lastSelectionDiagnostic="(本轮尚未尝试 USB alternate setting 切换)" }
    fun configurations(connection:UsbDeviceConnection):List<LegacyUsbConfiguration> {
        // Both constructors verified in the provided Android4.3 framework DEX.
        val endpoint=UsbEndpoint::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
        val iface=UsbInterface::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Array<Parcelable>::class.java).apply { isAccessible=true }
        return UsbDescriptorParser.parse(connection.rawDescriptors).map { config ->
            LegacyUsbConfiguration(config.id,config.interfaces.map { descriptor ->
                val endpoints=descriptor.endpoints.map { ep -> endpoint.newInstance(ep.address,ep.attributes,ep.packet,ep.interval) as Parcelable }.toTypedArray()
                (iface.newInstance(descriptor.id,descriptor.klass,descriptor.subclass,descriptor.protocol,endpoints) as UsbInterface).also { alternates[it]=descriptor.alternate }
            })
        }
    }
    fun selectConfiguration(connection:UsbDeviceConnection,id:Int):Boolean {
        val active=ByteArray(1)
        if(connection.controlTransfer(0x80,8,0,0,active,1,1000)==1 && active[0].toInt() and 255==id) return true
        return connection.controlTransfer(0,9,id,0,null,0,1000)>=0
    }
    /**
     * Selects an alternate setting with a REAL kernel-side switch.
     *
     * Preferred path: JNI ioctl USBDEVFS_SETINTERFACE on this connection's own fd, then verify
     * the kernel reports the target alt through sysfs. The hand-written control URB is kept only
     * as a fallback and is accepted solely when sysfs independently confirms the switch; the
     * previous "GET failed but return true" shortcut is removed.
     */
    fun selectInterface(connection:UsbDeviceConnection,iface:UsbInterface):Boolean {
        val target=alternate(iface)
        val context=App.appContext
        val before=readKernelAlt(connection,iface)
        var nativeStatus=if(context==null) "appContext=null" else "not-attempted"
        var ioctlResult="not-attempted"
        // 1) Preferred: kernel-aware ioctl on the same open file description.
        if(context!=null) {
            val loaded=UsbIfaceNative.ensureLoaded(context)
            nativeStatus=UsbIfaceNative.diagnosticSnapshot()
            if(loaded) {
                val rc=try { UsbIfaceNative.setInterface(connection.fileDescriptor,iface.id,target) }
                    catch(error:LinkageError) {
                        ioctlResult="linkage:${error.javaClass.simpleName}:${error.message}"
                        Log.e(TAG,"setInterface JNI linkage failed: $ioctlResult")
                        UsbIfaceNative.NOT_LOADED
                    }
                if(rc!=UsbIfaceNative.NOT_LOADED) ioctlResult=rc.toString()
                try { Thread.sleep(60) } catch(_:InterruptedException){ Thread.currentThread().interrupt(); return false }
                val after=readKernelAlt(connection,iface)
                if(rc==0 && after.alt!=null && after.alt==target) {
                    lastSelectionDiagnostic="iface=${iface.id} targetAlt=$target path=ioctl ioctlRc=$rc before={${before.detail}} after={${after.detail}} native={$nativeStatus}"
                    Log.i(TAG,"setInterface iface=${iface.id} alt=$target KERNEL verified (${before.alt}->${after.alt}) via ioctl")
                    return true
                }
                Log.w(TAG,"setInterface ioctl rc=$rc before=${before.alt} after=${after.alt} iface=${iface.id} target=$target; trying legacy URB")
            } else {
                ioctlResult="not-run"
            }
        }
        // 2) Legacy fallback: hand-written SET_INTERFACE control URB. It can move the device
        //    without moving the kernel, so success requires an independent sysfs confirmation.
        for(attempt in 1..3) {
            val set=connection.controlTransfer(1,11,target,iface.id,null,0,1000)
            try { Thread.sleep(80) } catch(_:InterruptedException){ Thread.currentThread().interrupt(); return false }
            val after=readKernelAlt(connection,iface)
            if(after.alt!=null && after.alt==target) {
                lastSelectionDiagnostic="iface=${iface.id} targetAlt=$target path=legacy-urb attempt=$attempt setRc=$set ioctlRc=$ioctlResult before={${before.detail}} after={${after.detail}} native={$nativeStatus}"
                Log.i(TAG,"setInterface iface=${iface.id} alt=$target KERNEL verified (${before.alt}->${after.alt}) via legacy URB set=$set")
                return true
            }
            // Device-side GET_INTERFACE is auxiliary evidence only; never sufficient on its own.
            val cur=ByteArray(1)
            val got=connection.controlTransfer(0x81,10,0,iface.id,cur,1,1000)
            lastSelectionDiagnostic="iface=${iface.id} targetAlt=$target path=legacy-urb attempt=$attempt setRc=$set getRc=$got deviceAlt=${cur.getOrNull(0)} kernelAlt=${after.alt} before={${before.detail}} after={${after.detail}} ioctlRc=$ioctlResult native={$nativeStatus}"
            Log.w(TAG,"legacy set attempt=$attempt set=$set get=$got deviceAlt=${cur.getOrNull(0)} kernelAlt=${after.alt} target=$target")
            try { Thread.sleep(120) } catch(_:InterruptedException){ Thread.currentThread().interrupt(); return false }
        }
        return false
    }

    /**
     * Reads the kernel's CURRENT alternate setting without hard-coding bus/device/iface.
     *
     * Resolves the connection fd via /proc/self/fd to /dev/bus/usb/BBB/DDD, locates the matching
     * sysfs device directory by busnum/devnum, then reads
     * <dev>:<bConfigurationValue>.<iface>/bAlternateSetting. Returns both the value and a
     * bounded explanation so a missing sysfs mapping can be diagnosed from SuperDiagnostics.
     */
    private data class KernelAltRead(val alt:Int?,val detail:String)

    private fun readKernelAlt(connection:UsbDeviceConnection,iface:UsbInterface):KernelAltRead {
        var fd=-1
        var resolved="unresolved"
        var busNum=-1
        var devNum=-1
        var unreadableCandidates=0
        try {
            fd=connection.fileDescriptor
            resolved=File("/proc/self/fd/$fd").canonicalPath
            val node=Regex("/dev/bus/usb/(\\d+)/(\\d+)$").find(resolved)
                ?: return KernelAltRead(null,"fd=$fd path=$resolved usbfsPath=unmatched")
            // usbfs formats bus numbers with leading zeroes (for example 002), while
            // sysfs writes the same value in decimal without padding (2). Compare the
            // numeric IDs so a valid kernel alternate setting is not lost to formatting.
            busNum=node.groupValues[1].toInt()
            devNum=node.groupValues[2].toInt()
            val root=File("/sys/bus/usb/devices")
            val devices=root.listFiles()
                ?: return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfs=list-failed")
            for(dev in devices) {
                val name=dev.name
                if(!dev.isDirectory || name.contains(':') || name.startsWith("usb")) continue
                val bFile=File(dev,"busnum"); if(!bFile.exists()) continue
                // A stale/unreadable sysfs entry should not prevent checking the rest.
                val candidate=runCatching {
                    bFile.readText().trim().toInt() to File(dev,"devnum").readText().trim().toInt()
                }.getOrNull()
                if(candidate==null) { unreadableCandidates++; continue }
                if(candidate.first==busNum && candidate.second==devNum) {
                    val cfgFile=File(dev,"bConfigurationValue")
                    val cfg=try { cfgFile.readText().trim().toInt() } catch(error:Throwable) {
                        return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfsDevice=${dev.name} config=read-failed:${error.javaClass.simpleName}")
                    }
                    val altFile=File(root,"$name:$cfg.${iface.id}/bAlternateSetting")
                    if(!altFile.exists()) return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfsDevice=${dev.name} config=$cfg iface=${iface.id} altPath=${altFile.absolutePath} altFile=missing")
                    val alt=try { altFile.readText().trim().toInt() } catch(error:Throwable) {
                        return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfsDevice=${dev.name} config=$cfg iface=${iface.id} altPath=${altFile.absolutePath} altRead=${error.javaClass.simpleName}:${error.message}")
                    }
                    return KernelAltRead(alt,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfsDevice=${dev.name} config=$cfg iface=${iface.id} altPath=${altFile.absolutePath}")
                }
            }
            return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum sysfsDevice=not-found scanned=${devices.size} unreadableCandidates=$unreadableCandidates")
        } catch(error:Throwable) {
            return KernelAltRead(null,"fd=$fd path=$resolved bus=$busNum dev=$devNum error=${error.javaClass.simpleName}:${error.message}")
        }
    }

    /** GET_CONFIGURATION (standard device request 8): returns the kernel/device's *active* config id, or -1. */
    fun activeConfiguration(connection:UsbDeviceConnection):Int {
        val active=ByteArray(1)
        return if(connection.controlTransfer(0x80,8,0,0,active,1,1000)==1) active[0].toInt() and 255 else -1
    }
}
val UsbInterface.alternateSetting:Int get()=LegacyUsbPlatform.alternate(this)
