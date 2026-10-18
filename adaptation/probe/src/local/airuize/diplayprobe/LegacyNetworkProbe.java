package local.airuize.diplayprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.Collections;

/** Explicit interface binding avoids assuming Android's default multicast interface is usable. */
final class LegacyNetworkProbe {
    interface Reporter { void line(String text); }

    static void run(Context context, Reporter report) {
        report.line("\n=== 网络专项检测 v0.2 ===");
        WifiManager wifi = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
        try {
            WifiInfo info = wifi.getConnectionInfo();
            report.line("Wi-Fi supplicant=" + (info == null ? "unknown" : info.getSupplicantState()));
            if (info != null) report.line("Wi-Fi station IPv4=" + ipv4(info.getIpAddress()));
            DhcpInfo dhcp = wifi.getDhcpInfo();
            if (dhcp != null) report.line("DHCP IPv4=" + ipv4(dhcp.ipAddress) + "; gateway=" + ipv4(dhcp.gateway));
            ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            report.line("Wi-Fi network state=" + (network == null ? "unknown" : network.getDetailedState()));
        } catch (Exception e) { report.line("Wi-Fi connection information=FAIL: " + error(e)); }

        WifiManager.MulticastLock lock = null;
        try {
            lock = wifi.createMulticastLock("diplay-interface-probe");
            lock.setReferenceCounted(false);
            lock.acquire();
            report.line("MulticastLock acquire=PASS");
        } catch (Exception e) { report.line("MulticastLock acquire=FAIL: " + error(e)); }
        try {
            int addressCount = 0;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                report.line("interface=" + ni.getName() + "; multicast=" + ni.supportsMulticast());
                int interfaceAddressCount = 0;
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    report.line("  address=" + address.getHostAddress());
                    interfaceAddressCount++;
                    if (address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress()) {
                        addressCount++;
                        testTcp(address, ni.getName(), report);
                        if (ni.supportsMulticast()) testMulticast(address, ni, report);
                    }
                }
                if (interfaceAddressCount == 0) report.line("  interface has no address; skip bound network tests");
            }
            if (addressCount == 0) report.line("usable IPv4=NONE; connect Wi-Fi or enable the head-unit hotspot, then rerun");
        } catch (Exception e) { report.line("Interface enumeration=FAIL: " + error(e)); }
        finally {
            if (lock != null && lock.isHeld()) {
                try { lock.release(); } catch (Exception e) { report.line("MulticastLock release=FAIL: " + error(e)); }
            }
        }
    }

    private static void testTcp(InetAddress address, String name, Reporter report) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(address, 0));
            report.line("TCP bind interface=" + name + "=PASS");
        } catch (Exception e) { report.line("TCP bind interface=" + name + "=FAIL: " + error(e)); }
        finally { if (socket != null) try { socket.close(); } catch (Exception ignored) {} }
    }

    private static void testMulticast(InetAddress address, NetworkInterface ni, Reporter report) {
        MulticastSocket socket = null;
        InetSocketAddress group = null;
        boolean joined = false;
        String stage = "create";
        try {
            socket = new MulticastSocket(null);
            stage = "bind";
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(address, 0));
            stage = "setNetworkInterface";
            socket.setNetworkInterface(ni);
            socket.setTimeToLive(255);
            group = new InetSocketAddress(InetAddress.getByName("224.0.0.251"), 5353);
            stage = "joinGroup";
            socket.joinGroup(group, ni);
            joined = true;
            report.line("IPv4 multicast join interface=" + ni.getName() + "=PASS");
            stage = "send mDNS query";
            byte[] query = mdnsQuery();
            socket.send(new DatagramPacket(query, query.length, group));
            report.line("mDNS query send interface=" + ni.getName() + "=PASS");
            stage = "receive mDNS response";
            socket.setSoTimeout(1800);
            DatagramPacket response = new DatagramPacket(new byte[4096], 4096);
            try {
                socket.receive(response);
                report.line("mDNS UDP packet received interface=" + ni.getName() + "; bytes=" + response.getLength()
                    + " (not parsed; not proof of CarPlay discovery)");
            } catch (SocketTimeoutException expected) {
                report.line("mDNS response=TIMEOUT (no responding service is not a multicast failure)");
            }
        } catch (Exception e) {
            report.line("IPv4 multicast interface=" + ni.getName() + "; stage=" + stage + "; FAIL: " + error(e));
        } finally {
            if (socket != null) {
                if (joined) try { socket.leaveGroup(group, ni); } catch (Exception ignored) {}
                socket.close();
            }
        }
    }

    /** PTR query for the DNS-SD service-type enumeration, requesting a unicast reply. */
    private static byte[] mdnsQuery() {
        String[] labels = {"_services", "_dns-sd", "_udp", "local"};
        int length = 12 + 1 + 4;
        for (String label : labels) length += 1 + label.length();
        byte[] bytes = new byte[length];
        bytes[5] = 1; // QDCOUNT = 1
        int cursor = 12;
        for (String label : labels) {
            bytes[cursor++] = (byte)label.length();
            for (int i = 0; i < label.length(); i++) bytes[cursor++] = (byte)label.charAt(i);
        }
        bytes[cursor++] = 0;
        bytes[cursor++] = 0;
        bytes[cursor++] = 12; // QTYPE PTR
        bytes[cursor++] = (byte)0x80;
        bytes[cursor] = 1; // QCLASS IN + unicast response bit
        return bytes;
    }

    private static String ipv4(int value) {
        return (value & 255) + "." + ((value >>> 8) & 255) + "."
            + ((value >>> 16) & 255) + "." + ((value >>> 24) & 255);
    }

    /** Error messages can contain identifiers; report only exception types and errno numbers. */
    static String error(Throwable exception) {
        StringBuilder result = new StringBuilder();
        for (int depth = 0; exception != null && depth < 6; depth++) {
            if (depth > 0) result.append(" <- ");
            result.append(exception.getClass().getSimpleName());
            try {
                Field field = exception.getClass().getField("errno");
                int errno = field.getInt(exception);
                result.append("(errno=").append(errno).append(", ").append(errnoName(errno)).append(')');
            } catch (Exception ignored) {}
            Throwable cause = exception.getCause();
            if (cause == exception) break;
            exception = cause;
        }
        return result.toString();
    }

    private static String errnoName(int errno) {
        switch (errno) {
            case 1: return "EPERM";
            case 13: return "EACCES";
            case 19: return "ENODEV";
            case 22: return "EINVAL";
            case 92: return "ENOPROTOOPT";
            case 93: return "EPROTONOSUPPORT";
            case 97: return "EAFNOSUPPORT";
            case 98: return "EADDRINUSE";
            case 99: return "EADDRNOTAVAIL";
            case 100: return "ENETDOWN";
            case 101: return "ENETUNREACH";
            default: return "OTHER";
        }
    }
}
