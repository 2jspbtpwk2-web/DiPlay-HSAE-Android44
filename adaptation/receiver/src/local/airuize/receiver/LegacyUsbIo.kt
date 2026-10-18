package local.airuize.receiver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Bounded legacy USB reads without holding a Java array across the native wait. */
object LegacyUsbIo {
    private class Probe {
        val attempts=AtomicLong(); val bytes=AtomicLong(); val timeouts=AtomicLong()
        val errors=AtomicLong(); val fallback=AtomicLong(); val maxWaitMs=AtomicLong()
        @Volatile var lastErrno=0
        fun record(result:Int, native:Boolean, elapsed:Long) {
            attempts.incrementAndGet()
            if (!native) fallback.incrementAndGet()
            if (result>0) bytes.addAndGet(result.toLong())
            if (native && result==-110) timeouts.incrementAndGet()
            else if (result<0) errors.incrementAndGet()
            lastErrno=if(native && result<0) -result else 0
            while (true) { val old=maxWaitMs.get(); if(elapsed<=old || maxWaitMs.compareAndSet(old,elapsed)) break }
        }
        fun summary()="attempts=${attempts.get()} bytes=${bytes.get()} timeouts=${timeouts.get()} errors=${errors.get()} frameworkFallback=${fallback.get()} lastErrno=$lastErrno maxWaitMs=${maxWaitMs.get()}"
    }
    private val ncm=Probe(); private val iap=Probe(); private val status=Probe()
    fun snapshot():String="NCM_IN ${ncm.summary()}\nIAP_IN ${iap.summary()}\nNCM_STATUS ${status.summary()}"
    internal fun retryableRead(result:Int):Boolean = result==0 || result==-4 || result==-11 || result==-110

    fun read(role:String,connection:UsbDeviceConnection,endpoint:UsbEndpoint,buffer:ByteArray,timeoutMillis:Int):Int {
        require(timeoutMillis>0 && buffer.isNotEmpty())
        val probe=when(role) { "ncm"->ncm; "iap"->iap; "status"->status; else->error("Unknown USB read role") }
        val length=minOf(16384,buffer.size)
        val started=SystemClock.elapsedRealtime()
        val native=UsbIfaceNative.bulkIn(connection.fileDescriptor,endpoint.address,buffer,length,timeoutMillis)
        val result=if(native==UsbIfaceNative.NOT_LOADED) {
            if(probe.fallback.get()==0L) Log.w("DiPlayUsb","USB $role IN native unavailable; framework fallback")
            connection.bulkTransfer(endpoint,buffer,length,timeoutMillis)
        } else native
        probe.record(result,native!=UsbIfaceNative.NOT_LOADED,SystemClock.elapsedRealtime()-started)
        if(native!=UsbIfaceNative.NOT_LOADED && result<0 && !retryableRead(result))
            throw com.shilapi.xcertplay.transport.IphoneUsbException.DeviceUnavailable("USB $role IN failed errno=${-result}")
        return result
    }

    fun write(connection:UsbDeviceConnection,endpoint:UsbEndpoint,data:ByteArray,offset:Int,length:Int,timeoutMillis:Int):Int {
        require(offset>=0 && length>0 && length<=16384 && offset<=data.size-length && timeoutMillis>0)
        val packet=if(offset==0 && length==data.size) data else data.copyOfRange(offset,offset+length)
        val native=UsbIfaceNative.bulkOut(connection.fileDescriptor,endpoint.address,packet,timeoutMillis)
        return if(native==UsbIfaceNative.NOT_LOADED) connection.bulkTransfer(endpoint,packet,packet.size,timeoutMillis) else native
    }
}
