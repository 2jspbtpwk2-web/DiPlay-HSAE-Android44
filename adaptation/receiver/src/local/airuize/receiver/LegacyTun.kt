package local.airuize.receiver
import java.net.InetAddress
import java.net.Inet6Address
import java.io.IOException
object LegacyBindAddress {
    /** The accepted TCP socket proves the wired interface; do not discard its IPv6 scope. */
    fun any(address:InetAddress?):InetAddress {
        if(address !is Inet6Address) return InetAddress.getByName("0.0.0.0")
        require(!address.isLinkLocalAddress || address.scopeId>0) { "IPv6 link-local local address requires interface scope" }
        return address
    }

    /** Repair only a link-local peer of this same accepted link-local control connection. */
    fun peer(address:InetAddress?, local:InetAddress?):InetAddress? {
        if(address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId>0) return address
        require(local is Inet6Address && local.isLinkLocalAddress && local.scopeId>0) {
            "IPv6 link-local peer requires a scoped link-local session address"
        }
        return Inet6Address.getByAddress(null,address.address,(local as Inet6Address).scopeId)
    }
}
object LegacyTun {
    // VpnService SDK18 produces O_NONBLOCK descriptors; only EAGAIN/EWOULDBLOCK is retried.
    fun isWouldBlock(error:IOException):Boolean {
        var cause:Throwable?=error
        while(cause!=null) {
            if(cause.javaClass.name=="libcore.io.ErrnoException") {
                val errno=runCatching { cause!!.javaClass.getField("errno").getInt(cause) }.getOrNull()
                if(errno==11) return true
            }
            cause=cause.cause
        }
        return false
    }
}
