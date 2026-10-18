package local.airuize.receiver

import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** Select only the session's local endpoint; never treat the remote phone as a bind address. */
internal object LegacyMicrophoneSocket {
    fun bindAddress(config: MicrophoneConfig): InetAddress {
        val local = config.localAddress
        if (config.host is Inet6Address) {
            require(local is Inet6Address && !local.isAnyLocalAddress) {
                "IPv6 microphone requires a concrete local session address"
            }
            require(!config.host.isLinkLocalAddress || (local.isLinkLocalAddress && local.scopeId > 0)) {
                "Link-local microphone requires a scoped local session address"
            }
            return LegacyBindAddress.any(local)
        }
        require(config.host is Inet4Address && (local == null || local is Inet4Address)) {
            "Microphone local and remote address families differ"
        }
        // Preserve legacy IPv4 callers that do not yet supply a local address.
        return LegacyBindAddress.any(local)
    }

    fun open(config: MicrophoneConfig, create: () -> DatagramSocket = { DatagramSocket(null) }): DatagramSocket {
        val address = bindAddress(config)
        val socket = create()
        try {
            socket.bind(InetSocketAddress(address, 0))
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
}
