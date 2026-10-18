package local.airuize.receiver

import android.os.IBinder

/**
 * VPN 预授权器：由 ROOT 通过 `app_process` 以 system/root 身份运行（安卓4.4 SELinux 为
 * permissive，binder 权限放行），反射 ConnectivityManager 的隐藏 prepareVpn 完成本包 VPN 授权，
 * 使被车机 ROM 裁剪掉的 VpnService 确认弹窗不再阻断 TUN 建立。
 *
 * 仅在内存中设置“当前已准备的 VPN 包”，重启即恢复，不写 /system。
 * 运行时反射枚举 prepareVpn 的真实签名，兼容 (String,String,int) 与 (String,String,UserHandle)。
 */
object VpnAuthorizer {
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.firstOrNull() ?: "local.airuize.diplaylegacy"
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "connectivity") as? IBinder
            if (binder == null) {
                println("VPNAUTH_NOCONNSVC")
                return
            }
            val stub = Class.forName("android.net.IConnectivityManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val cm = asInterface.invoke(null, binder)
            if (cm == null) {
                println("VPNAUTH_NOCM")
                return
            }
            val methods = cm.javaClass.methods.filter { it.name == "prepareVpn" }
            if (methods.isEmpty()) {
                println("VPNAUTH_NOMETHOD")
                return
            }
            var anyOk = false
            for (m in methods) {
                val pt = m.parameterTypes
                println("VPNAUTH_METHOD ${m}")
                if (pt.size != 3 || pt[0] != String::class.java || pt[1] != String::class.java) continue
                val third: Any? = when {
                    pt[2].name == "int" -> 0
                    pt[2].name.contains("UserHandle") -> runCatching {
                        val c = pt[2].getDeclaredConstructor(Int::class.javaPrimitiveType)
                        c.isAccessible = true
                        c.newInstance(0)
                    }.getOrNull()
                    else -> null
                }
                if (third == null) {
                    println("VPNAUTH_SKIPTHIRD ${pt[2].name}")
                    continue
                }
                // 第一次以 oldPackage=null 请求准备本包，第二次以本包作为旧值完成确认，模拟系统授权对话框的“允许”。
                val r1 = runCatching { m.invoke(cm, null, pkg, third) }
                val r2 = runCatching { m.invoke(cm, pkg, pkg, third) }
                println("VPNAUTH_R1=${r1.getOrNull()} E1=${r1.exceptionOrNull()}")
                println("VPNAUTH_R2=${r2.getOrNull()} E2=${r2.exceptionOrNull()}")
                if (r1.getOrNull() == true || r2.getOrNull() == true) anyOk = true
            }
            println(if (anyOk) "VPNAUTH_OK" else "VPNAUTH_DONE")
        } catch (t: Throwable) {
            println("VPNAUTH_EXC ${t.javaClass.name}:${t.message}")
        }
    }
}
