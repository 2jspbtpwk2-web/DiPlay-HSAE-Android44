package local.airuize.receiver

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket

object LegacyListenerBinder {
    fun bind(address: InetSocketAddress, cancelled: () -> Boolean, onBusy: (Int) -> Unit,
        waitMillis: Long = 5000L): ServerSocket {
        require(waitMillis >= 0)
        val deadline = System.nanoTime() + waitMillis * 1_000_000
        var attempts = 0
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(address)
                if (cancelled()) throw InterruptedException()
                return socket
            } catch (error: Exception) {
                socket.close()
                if (error !is BindException || System.nanoTime() >= deadline) throw error
                attempts++
                onBusy(attempts)
                Thread.sleep(minOf(250L, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1L)))
            }
        }
    }
}
