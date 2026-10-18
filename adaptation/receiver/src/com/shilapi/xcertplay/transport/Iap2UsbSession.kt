package com.shilapi.xcertplay.transport
import android.hardware.usb.*
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** SDK18 has no timed requestWait; bounded bulk reads avoid an uncancellable wait. */
class Iap2UsbSession internal constructor(private val connection:UsbDeviceConnection,
    private val outEndpoint:UsbEndpoint,private val inEndpoint:UsbEndpoint) : Closeable {
    private val closed=AtomicBoolean(false)
    private val readLock=Any(); private val writeLock=Any()
    private val readBuffer=ByteArray(16384)
    fun write(data:ByteArray,timeoutMillis:Int) = synchronized(writeLock) {
        check(!closed.get()); require(timeoutMillis>0)
        // Android 4.3 usbfs bulk transfer limit is 16KiB.
        var offset=0
        while(offset<data.size) {
            val size=minOf(16384,data.size-offset)
            val count=local.airuize.receiver.LegacyUsbIo.write(connection,outEndpoint,data,offset,size,timeoutMillis)
            if(count!=size) throw IphoneUsbException.DeviceUnavailable("USBMUX bulk write incomplete")
            offset+=count
        }
    }
    fun read(timeoutMillis:Long):ByteArray? = synchronized(readLock) {
        check(!closed.get()); require(timeoutMillis>0)
        val bytes=readBuffer
        val count=local.airuize.receiver.LegacyUsbIo.read("iap",connection,inEndpoint,bytes,timeoutMillis.coerceIn(1,1000).toInt())
        if(closed.get()) throw IphoneUsbException.DeviceUnavailable("USBMUX closed")
        if(count>0) bytes.copyOf(count) else null
    }
    override fun close() {
        if(closed.compareAndSet(false,true)) synchronized(readLock) { synchronized(writeLock) { connection.close() } }
    }
}
