import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop validation only, run against the FINAL Android 4.4 APK through app_process:
 * CLASSPATH=<test.dex>:<final.apk> app_process /system/bin IPv6MediaBindSmoke ::1
 * The first argument may instead be an address actually assigned to a local interface,
 * including a scoped address such as fe80::1234%eth0. No product keep rules are needed.
 *
 * Exercises product socket binding and cleanup. Audio receives one empty datagram and
 * screen/iAP/settings clients transmit no media or encrypted records. This does not
 * validate authentication, audio decryption/playback, video decoding or a real iPhone.
 */
public final class IPv6MediaBindSmoke {
    private static final String PACKAGE = "com.shilapi.xcertplay.airplay.";
    private static final List<Throwable> UNCAUGHT = new CopyOnWriteArrayList<Throwable>();
    private static Inet6Address local;
    private static Object unit;
    private static int checks;

    public static void main(String[] args) throws Exception {
        InetAddress selected = InetAddress.getByName(args.length == 0 ? "::1" : args[0]);
        require(selected instanceof Inet6Address && !selected.isAnyLocalAddress() &&
            !selected.isMulticastAddress(), "test requires a concrete local IPv6 address");
        local = (Inet6Address) selected;
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) { UNCAUGHT.add(error); }
        });
        try {
            timingFailureCleanupAndRetry();
            audioUdpBinding();
            screenTcpBinding();
            iapTcpBinding();
            settingsTcpBinding();
            require(UNCAUGHT.isEmpty(), "uncaught product thread failures=" + UNCAUGHT.size());
            System.out.println("PASS IPv6MediaBindSmoke checks=" + checks +
                " components=5 family=IPv6 scope=" + local.getScopeId() +
                " timingBindFailures=20 media=not-exercised authentication=not-attempted");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }

    private static void timingFailureCleanupAndRetry() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "NtpClock");
        final Method listen = type.getMethod("listen", InetAddress.class);
        byte[] badBytes = new byte[16];
        badBytes[0] = (byte) 0xfe; badBytes[1] = (byte) 0x80; badBytes[15] = 0x7f;
        // Linux interface indexes are far below this on the target/emulator. No
        // invalid packet is sent: bind must fail before a receiver thread starts.
        Inet6Address unavailable = Inet6Address.getByAddress(null, badBytes, 65000);
        Object clock = construct(type, function(new Capture()));
        int port = 0;
        DatagramSocket peer = null;
        DatagramSocket timing = null;
        try {
            expectSocketBindFailure(listen, clock, unavailable);
            port = ((Number) listen.invoke(clock, local)).intValue();
            requirePort(port);
            timing = (DatagramSocket) field(clock, "socket");
            assertUdpBound(timing, port, "timing");
            peer = new DatagramSocket(new InetSocketAddress(local, 0));
            peer.setSoTimeout(5000);
            byte[] request = new byte[32];
            request[0] = (byte) 0x80; request[1] = (byte) 210; request[3] = 7;
            for (int index = 24; index < 32; index++) request[index] = (byte) (index + 1);
            peer.send(new DatagramPacket(request, request.length, local, port));
            DatagramPacket response = new DatagramPacket(new byte[64], 64);
            peer.receive(response);
            require(response.getLength() == 32 && (response.getData()[1] & 255) == 211,
                "same NtpClock retries successfully and answers IPv6 timing request");
            require(Arrays.equals(Arrays.copyOfRange(request, 24, 32),
                Arrays.copyOfRange(response.getData(), 8, 16)), "timing response echoes originate stamp");
        } finally {
            close(clock);
            close(clock);
            if (timing != null) require(timing.isClosed(), "timing close closes its UDP listener");
            if (peer != null) peer.close();
        }
        assertUdpReleased(port);

        // Warm up reflection, exception paths and diagnostic callbacks before the
        // measurement, so their one-time runtime allocations do not look like leaks.
        for (int index = 0; index < 4; index++) failedClock(type, listen, unavailable);
        fdCount();
        int before = fdCount();
        for (int index = 0; index < 20; index++) failedClock(type, listen, unavailable);
        int after = fdCount();
        require(after <= before + 2, "timing failed-bind descriptor count bounded before=" + before + " after=" + after);
        System.out.println("SMOKE timing failed-bind retry=true iterations=20 fdBefore=" + before + " fdAfter=" + after);
    }

    private static void failedClock(Class<?> type, Method listen, InetAddress unavailable) throws Exception {
        Object clock = construct(type, function(new Capture()));
        try { expectSocketBindFailure(listen, clock, unavailable); }
        finally { close(clock); }
    }

    private static void expectSocketBindFailure(Method listen, Object clock, InetAddress unavailable) throws Exception {
        try {
            listen.invoke(clock, unavailable);
            throw new AssertionError("timing bound an unavailable IPv6 scope");
        } catch (InvocationTargetException error) {
            require(error.getCause() instanceof SocketException,
                "unavailable IPv6 address produces a socket bind failure, got " + error.getCause().getClass().getSimpleName());
        }
    }

    private static void audioUdpBinding() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "AudioStream");
        Capture capture = new Capture();
        Object audio = construct(type, new byte[32], Integer.valueOf(100), function(capture), local);
        Class<?> listenerType = Class.forName(PACKAGE + "AudioStream$Listener");
        int dataPort = 0, controlPort = 0;
        DatagramSocket peer = null;
        DatagramSocket dataListener = null, controlListener = null;
        try {
            Object ports = type.getMethod("listen", listenerType).invoke(audio, proxy(listenerType, capture));
            dataPort = ((Number) ports.getClass().getMethod("getFirst").invoke(ports)).intValue();
            controlPort = ((Number) ports.getClass().getMethod("getSecond").invoke(ports)).intValue();
            requirePort(dataPort); requirePort(controlPort);
            require(dataPort != controlPort, "audio data/control use distinct ports");
            dataListener = (DatagramSocket) field(audio, "dataSocket");
            controlListener = (DatagramSocket) field(audio, "controlSocket");
            assertUdpBound(dataListener, dataPort, "audio-data");
            assertUdpBound(controlListener, controlPort, "audio-control");
            peer = new DatagramSocket(new InetSocketAddress(local, 0));
            peer.send(new DatagramPacket(new byte[0], 0, local, dataPort));
            require(capture.packet.await(5, TimeUnit.SECONDS), "audio receives IPv6 empty datagram");
            require(capture.rejectedPackets.get() == 1 && capture.mediaCallbacks.get() == 0,
                "short audio datagram rejected before decryption/playback");
            peer.send(new DatagramPacket(new byte[] {0}, 1, local, controlPort));
        } finally {
            close(audio); close(audio);
            if (dataListener != null) require(dataListener.isClosed(), "audio close closes data UDP listener");
            if (controlListener != null) require(controlListener.isClosed(), "audio close closes control UDP listener");
            if (peer != null) peer.close();
        }
        System.out.println("SMOKE audio listeners closed; checking bounded port release");
        assertUdpReleased(dataPort); assertUdpReleased(controlPort);
        System.out.println("SMOKE audio IPv6-data=true IPv6-control=true emptyPacket=controlled media=not-exercised");
    }

    private static void screenTcpBinding() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "ScreenStream");
        Class<?> listenerType = Class.forName(PACKAGE + "ScreenStream$Listener");
        Capture capture = new Capture();
        final Object screen = construct(type, new byte[32], function(capture), local);
        int port = 0;
        Socket client = null;
        try {
            port = ((Number) type.getMethod("listen", listenerType).invoke(screen, proxy(listenerType, capture))).intValue();
            requirePort(port);
            assertTcpBound((ServerSocket) field(screen, "server"), port, "screen");
            client = connect(port);
            awaitSocket(screen, "screen");
            require(capture.mediaCallbacks.get() == 0, "screen TCP accept does not fabricate media");
        } finally {
            close(screen); close(screen);
            if (client != null) client.close();
        }
        assertTcpReleased(port);
        System.out.println("SMOKE screen IPv6-TCP-accepted=true media=not-exercised");
    }

    private static void iapTcpBinding() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "IapTunnel");
        Class<?> listenerType = Class.forName(PACKAGE + "IapTunnel$Listener");
        Capture capture = new Capture();
        Object iap = construct(type, new byte[32], local);
        int port = 0;
        Socket client = null;
        try {
            port = ((Number) type.getMethod("listen", listenerType).invoke(iap, proxy(listenerType, capture))).intValue();
            requirePort(port);
            Object servers = field(iap, "servers");
            require(servers instanceof List && !((List<?>) servers).isEmpty(), "iAP publishes listener");
            for (Object server : (List<?>) servers) assertTcpBound((ServerSocket) server, port, "iap");
            client = connect(port);
            require(Boolean.TRUE.equals(type.getMethod("awaitPeerConnection", long.class).invoke(iap, Long.valueOf(5000))),
                "iAP detects IPv6 TCP peer");
            require(capture.open.await(5, TimeUnit.SECONDS), "iAP onOpen callback observed");
            require(capture.mediaCallbacks.get() == 0, "iAP accept emits no synthetic payload");
        } finally {
            close(iap); close(iap);
            if (client != null) client.close();
        }
        assertTcpReleased(port);
        System.out.println("SMOKE iap IPv6-TCP-accepted=true encryptedRecords=not-exercised");
    }

    private static void settingsTcpBinding() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "VideoSettingsChannel");
        Capture capture = new Capture();
        Object settings = construct(type, new byte[32], new byte[32], function(capture));
        int port = 0;
        Socket client = null;
        try {
            port = ((Number) type.getMethod("listen", InetAddress.class).invoke(settings, local)).intValue();
            requirePort(port);
            assertTcpBound((ServerSocket) field(settings, "server"), port, "settings");
            client = connect(port);
            require(capture.open.await(5, TimeUnit.SECONDS), "video settings accepts IPv6 TCP peer");
        } finally {
            close(settings); close(settings);
            if (client != null) client.close();
        }
        assertTcpReleased(port);
        System.out.println("SMOKE settings IPv6-TCP-accepted=true encryptedRecords=not-exercised");
    }

    private static Socket connect(int port) throws Exception {
        Socket client = new Socket();
        try {
            client.setSoLinger(true, 0);
            client.connect(new InetSocketAddress(local, port), 5000);
            client.setSoTimeout(5000);
            return client;
        } catch (Exception error) { client.close(); throw error; }
    }

    private static void awaitSocket(Object owner, String label) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            Object value = field(owner, "socket");
            if (value instanceof Socket && ((Socket) value).isConnected()) {
                checks++;
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(label + " did not accept TCP client");
    }

    private static void assertUdpBound(DatagramSocket socket, int port, String label) {
        require(socket != null && socket.isBound() && !socket.isClosed(), label + " UDP socket is bound");
        require(socket.getLocalPort() == port, label + " UDP published port matches socket");
        assertLocal(socket.getLocalAddress(), label);
    }

    private static void assertTcpBound(ServerSocket socket, int port, String label) {
        require(socket != null && socket.isBound() && !socket.isClosed(), label + " TCP socket is bound");
        require(socket.getLocalPort() == port, label + " TCP published port matches socket");
        assertLocal(socket.getInetAddress(), label);
    }

    private static void assertLocal(InetAddress address, String label) {
        require(address instanceof Inet6Address && Arrays.equals(local.getAddress(), address.getAddress()),
            label + " binds the selected concrete IPv6 address");
        require(((Inet6Address) address).getScopeId() == local.getScopeId(), label + " preserves local IPv6 scope");
    }

    private static void assertUdpReleased(int port) throws Exception {
        requirePort(port);
        long deadline = System.nanoTime() + 2_000_000_000L;
        int retries = 0;
        while (true) {
            DatagramSocket probe = new DatagramSocket(null);
            try {
                probe.bind(new InetSocketAddress(local, port));
                checks++;
                if (retries > 0) System.out.println("SMOKE UDP port released after close retries=" + retries);
                return;
            } catch (SocketException error) {
                if (!addressInUse(error) || System.nanoTime() >= deadline) throw error;
                retries++;
            } finally { probe.close(); }
            Thread.sleep(20);
        }
    }

    private static void assertTcpReleased(int port) throws Exception {
        requirePort(port);
        long deadline = System.nanoTime() + 2_000_000_000L;
        int retries = 0;
        while (true) {
            ServerSocket probe = new ServerSocket();
            try {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(local, port));
                checks++;
                if (retries > 0) System.out.println("SMOKE TCP port released after close retries=" + retries);
                return;
            } catch (SocketException error) {
                if (!addressInUse(error) || System.nanoTime() >= deadline) throw error;
                retries++;
            } finally { probe.close(); }
            Thread.sleep(20);
        }
    }

    private static boolean addressInUse(SocketException error) {
        if (error instanceof BindException) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("EADDRINUSE") || message.contains("Address already in use"))) return true;
            if (cause.getClass().getName().equals("libcore.io.ErrnoException")) {
                try { if (cause.getClass().getField("errno").getInt(cause) == 98) return true; }
                catch (Exception ignored) { /* Do not retry an unknown error. */ }
            }
        }
        return false;
    }

    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        if (entries == null) throw new AssertionError("cannot read /proc/self/fd");
        return entries.length;
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object construct(Class<?> type, Object... values) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != values.length) continue;
            if (parameters.length > 0 && parameters[parameters.length - 1].getName()
                    .equals("kotlin.jvm.internal.DefaultConstructorMarker")) continue;
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("test constructor shape: " + type.getSimpleName() + " args=" + values.length);
    }

    private static Object function(Capture capture) throws Exception {
        return proxy(Class.forName("kotlin.jvm.functions.Function1"), capture);
    }

    private static Object proxy(Class<?> type, Capture capture) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, capture);
    }

    private static final class Capture implements InvocationHandler {
        final CountDownLatch packet = new CountDownLatch(1);
        final CountDownLatch open = new CountDownLatch(1);
        final AtomicInteger rejectedPackets = new AtomicInteger();
        final AtomicInteger mediaCallbacks = new AtomicInteger();

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                return "IPv6MediaBindSmokeProxy";
            }
            if (name.equals("invoke")) {
                if (args != null && args.length == 1 && "video settings channel connected".equals(args[0])) open.countDown();
                return unit;
            }
            if (name.equals("onOpen")) open.countDown();
            if (name.equals("onPacket")) {
                if (args != null && args.length == 4 && args[0] instanceof byte[] &&
                        ((byte[]) args[0]).length == 0 && args[1] == null && args[3] instanceof java.io.IOException) {
                    rejectedPackets.incrementAndGet();
                }
                packet.countDown();
            }
            if (name.equals("onStarted") || name.equals("onRtp") || name.equals("onFrame") ||
                    name.equals("onConfig") || name.equals("onCodec") || name.equals("onIap")) mediaCallbacks.incrementAndGet();
            if (method.getReturnType() == boolean.class) return Boolean.FALSE;
            if (method.getReturnType() == int.class) return Integer.valueOf(0);
            if (method.getReturnType() == long.class) return Long.valueOf(0);
            return null;
        }
    }

    private static void close(Object value) throws Exception {
        value.getClass().getMethod("close").invoke(value);
    }

    private static void requirePort(int port) { require(port > 0 && port <= 65535, "valid listener port"); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
