package local.airuize.receiver

import java.net.Inet6Address
import java.net.InetAddress

/** Export numeric socket failure facts without copying exception messages or addresses. */
object SocketDiagnostics {
    private val errors=linkedMapOf("EPERM" to 1,"EBADF" to 9,"EACCES" to 13,"ENODEV" to 19,
        "EINVAL" to 22,"ENOSYS" to 38,"EPROTONOSUPPORT" to 93,"EAFNOSUPPORT" to 97,
        "EADDRINUSE" to 98,"EADDRNOTAVAIL" to 99,"ENETUNREACH" to 101,"ECONNRESET" to 104,
        "ENOBUFS" to 105,"ETIMEDOUT" to 110,"ECONNREFUSED" to 111,"EHOSTUNREACH" to 113)
    private val operations=listOf("socket","setsockopt","bind","connect","sendto","recvfrom","listen","accept")
    fun address(address:InetAddress?):String = "family=${if(address==null) "unknown" else if(address is Inet6Address) "ipv6" else "ipv4"} " +
        "any=${address?.isAnyLocalAddress ?: false} linkLocal=${address?.isLinkLocalAddress ?: false} scope=${(address as? Inet6Address)?.scopeId ?: 0}"

    fun failure(error:Throwable):String {
        val chain=ArrayList<Throwable>(); var next:Throwable?=error
        while(next!=null && chain.size<6 && !chain.contains(next)) { chain.add(next); next=next.cause }
        var number:Int?=null; var source="unavailable"; var operation="unknown"
        for(cause in chain) {
            if(cause.javaClass.name in listOf("libcore.io.ErrnoException","android.system.ErrnoException")) {
                val value=runCatching { cause.javaClass.getField("errno").getInt(cause) }.getOrNull()
                if(value!=null && value in 1..4095) { number=value; source="cause-field" }
                val name=runCatching { cause.javaClass.getField("functionName").get(cause) as? String }.getOrNull()
                if(name in operations) operation=name!!
            }
        }
        if(number==null) for(cause in chain) {
            val message=cause.message ?: continue
            val token=errors.keys.firstOrNull { Regex("\\b$it\\b").containsMatchIn(message) }
            if(token!=null) { number=errors[token]; source="message-token"; break }
        }
        if(operation=="unknown") for(cause in chain) {
            val message=cause.message?.lowercase() ?: continue
            operations.firstOrNull { message.contains("$it failed") }?.let { operation=it }
            if(operation!="unknown") break
        }
        val name=errors.entries.firstOrNull { it.value==number }?.key ?: "unknown"
        val frames=chain.flatMap { it.stackTrace.take(8).toList() }.take(16).joinToString("|") {
            // Stack frame symbols and line numbers contain code locations, never request values.
            val type=it.className.take(100).replace(Regex("[^A-Za-z0-9_.$]"),"?")
            val method=it.methodName.take(60).replace(Regex("[^A-Za-z0-9_$<>]"),"?")
            "$type.$method:${it.lineNumber}"
        }
        return "class=${error.javaClass.simpleName.take(64)} causes=${chain.joinToString(">") { it.javaClass.simpleName.take(48) }} " +
            "operation=$operation errno=${number ?: "unavailable"} errnoName=$name errnoSource=$source frames=$frames"
    }
}
