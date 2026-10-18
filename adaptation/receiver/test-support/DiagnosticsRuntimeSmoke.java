import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Desktop-only checks against the actual final APK; run under app_process for Android JNI. */
public final class DiagnosticsRuntimeSmoke {
    private static int checks;
    private static Class<?> evidenceClass;
    private static Method add;
    private static Method snapshot;
    private static Field characters;
    private static Field records;

    public static void main(String[] args) throws Exception {
        runtimeSnapshot();
        socketMetadata();
        socketFailureDiagnostics();
        evidenceClass = Class.forName("local.airuize.receiver.ConnectionEvidence");
        add = evidenceClass.getMethod("add", int.class, String.class, String.class);
        snapshot = evidenceClass.getMethod("snapshot");
        characters = evidenceClass.getDeclaredField("characters"); characters.setAccessible(true);
        records = evidenceClass.getDeclaredField("records"); records.setAccessible(true);
        recordCountBound();
        characterBoundAndTruncation();
        concurrentGenerations();
        System.out.println("PASS DiagnosticsRuntimeSmoke checks=" + checks +
            " snapshotMaxChars=10000 storedEvidenceMaxChars=160000 recordMax=96");
    }

    private static void runtimeSnapshot() throws Exception {
        Class<?> probe = Class.forName("local.airuize.receiver.ConnectionRuntimeProbe");
        Object instance = probe.getField("INSTANCE").get(null);
        String value = (String) probe.getMethod("snapshot").invoke(instance);
        require(value.length() > 0 && value.length() <= 10000, "runtime snapshot bounded");
        String[] markers = {"processCpuMs=", "javaUsed=", "javaMax=", "nativeAllocated=",
            "openFdCount=", "SOCKET_tcp6", "SOCKET_udp6", "SOCKET_tcp", "SOCKET_udp",
            "TUN IPv6 addresses:", "TUN IPv6 routes:"};
        for (String marker : markers) require(value.contains(marker), "runtime marker " + marker);
        require(Pattern.compile("processCpuMs=[0-9]+").matcher(value).find(), "CPU numeric value");
        require(Pattern.compile("javaUsed=[0-9]+").matcher(value).find(), "heap numeric value");
        require(Pattern.compile("openFdCount=[0-9]+").matcher(value).find(), "fd count readable");
        System.out.println("RUNTIME snapshotChars=" + value.length() + " cpu/memory/fd/socketMarkers=present");
    }

    private static void socketMetadata() throws Exception {
        Class<?> probe = Class.forName("local.airuize.receiver.ConnectionRuntimeProbe");
        Object instance = probe.getField("INSTANCE").get(null);
        Method method = probe.getMethod("sockets", String.class, int.class);
        String addressA = "ABCDEF00112233445566778899AABBCC";
        String addressB = "1234567890ABCDEF1122334455667788";
        String table = "header\n0: " + addressA + ":1B58 " + addressB +
            ":C000 01 00000001:00000002 00:00000000 00000000 12345 0 987654321\n" +
            "1: " + addressA + ":1B59 " + addressB +
            ":C001 01 00000000:00000000 00:00000000 00000000 54321 0 123456789\n";
        String value = (String) method.invoke(instance, table, 12345);
        require(value.contains("localPort=7000 peerPort=49152 stateHex=01"), "numeric socket ports/state");
        require(value.contains("txQueue:rxQueue=00000001:00000002"), "numeric queue sizes");
        require(!value.contains("7001"), "other uid omitted");
        require(!value.contains(addressA) && !value.contains(addressB) && !value.contains("987654321"),
            "socket addresses and inode omitted");
        require(((String) method.invoke(instance, table, 55555)).contains("无匹配记录"), "missing uid handled");
    }

    private static void socketFailureDiagnostics() throws Exception {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xfe; bytes[1] = (byte) 0x80; bytes[15] = 2;
        Inet6Address local = Inet6Address.getByAddress(null, bytes, 65000);
        DatagramSocket socket = new DatagramSocket(null);
        SocketException failure = null;
        try {
            // No interface with this scope exists in the Android smoke environment.
            socket.bind(new InetSocketAddress(local, 0));
        } catch (SocketException error) {
            failure = error;
        } finally {
            socket.close();
        }
        require(socket.isClosed(), "failed runtime bind socket closed");
        require(failure != null, "invalid IPv6 scope produces a real SocketException");

        Class<?> diagnostics = Class.forName("local.airuize.receiver.SocketDiagnostics");
        Object instance = diagnostics.getField("INSTANCE").get(null);
        Method describeFailure = diagnostics.getMethod("failure", Throwable.class);
        Method describeAddress = diagnostics.getMethod("address", InetAddress.class);
        Integer expectedErrno = runtimeErrno(failure);
        require(expectedErrno != null, "real Android failure has a readable errno field or known token");
        String value = (String) describeFailure.invoke(instance, failure);
        System.out.println("SOCKET_BIND_FAILURE " + value);
        checkSocketFailure(value, expectedErrno.intValue(), local, failure);
        String platformMessage = failure.getMessage();
        require(platformMessage == null || platformMessage.length() < 16 || !value.contains(platformMessage),
            "complete Android exception message omitted");

        // Keep the actual Android failure as the cause while adding private wrapper text.
        // The formatter must export numeric/code metadata rather than either message.
        String privateMarker = "PRIVATE_SOCKET_DIAGNOSTIC_MESSAGE_76351";
        SocketException wrapped = new SocketException(privateMarker + " " + local.getHostAddress());
        wrapped.initCause(failure);
        String wrappedValue = (String) describeFailure.invoke(instance, wrapped);
        System.out.println("SOCKET_WRAPPED_BIND_FAILURE " + wrappedValue);
        checkSocketFailure(wrappedValue, expectedErrno.intValue(), local, wrapped);
        require(!wrappedValue.contains(privateMarker), "arbitrary wrapper message omitted");
        require(wrappedValue.contains("causes=SocketException>" + failure.getClass().getSimpleName()),
            "wrapper retains the actual Android failure class");

        String address = (String) describeAddress.invoke(instance, local);
        require(address.equals("family=ipv6 any=false linkLocal=true scope=65000"),
            "local metadata retains family scope and link-local flags");
        require(!address.contains(local.getHostAddress()) && !address.toLowerCase(java.util.Locale.US).contains("fe80"),
            "local address bytes omitted");
        System.out.println("SOCKET_BIND_METADATA " + address + " closed=" + socket.isClosed());
    }

    private static void checkSocketFailure(String value, int expectedErrno, Inet6Address address, Throwable actual) {
        require(value.length() > 0 && value.length() <= 4096, "socket failure metadata bounded");
        String expectedClass = actual.getClass().getSimpleName();
        if (expectedClass.length() > 64) expectedClass = expectedClass.substring(0, 64);
        require(value.startsWith("class=" + expectedClass + " causes=" + causeNames(actual) + " operation="),
            "actual exception class and ordered cause chain retained");
        require(value.contains(" operation=bind "), "real failed operation is bind");
        require(number(value, "errno") == expectedErrno, "reported errno agrees with actual Android failure");
        if (expectedErrno == 19) require(value.contains(" errnoName=ENODEV "), "missing interface errno name retained");
        if (expectedErrno == 22) require(value.contains(" errnoName=EINVAL "), "invalid bind errno name retained");
        if (expectedErrno == 99) require(value.contains(" errnoName=EADDRNOTAVAIL "), "unavailable bind address errno name retained");
        require(Pattern.compile(" errnoName=(?:[A-Z][A-Z0-9_]*|unknown) errnoSource=(?:cause-field|message-token) frames=").matcher(value).find(),
            "errno and stack metadata have the documented form");
        require(!value.contains(address.getHostAddress()) && !value.toLowerCase(java.util.Locale.US).contains("fe80"),
            "raw bind address omitted from failure summary");
        require(!value.contains("No such device") && !value.contains("Invalid argument") &&
            !value.contains("Cannot assign requested address"), "raw platform error message omitted");
    }

    private static String causeNames(Throwable error) {
        Collection<Throwable> seen = new ArrayList<Throwable>();
        StringBuilder names = new StringBuilder();
        while (error != null && seen.size() < 6 && !seen.contains(error)) {
            seen.add(error);
            if (names.length() > 0) names.append('>');
            String name = error.getClass().getSimpleName();
            names.append(name.length() > 48 ? name.substring(0, 48) : name);
            error = error.getCause();
        }
        return names.toString();
    }

    /** Android 4.4 may expose errno in libcore's cause or only its stable message token. */
    private static Integer runtimeErrno(Throwable error) {
        Collection<Throwable> chain = new ArrayList<Throwable>();
        while (error != null && chain.size() < 6 && !chain.contains(error)) {
            chain.add(error); error = error.getCause();
        }
        for (Throwable cause : chain) {
            String name = cause.getClass().getName();
            if (name.equals("libcore.io.ErrnoException") || name.equals("android.system.ErrnoException")) {
                try {
                    int value = cause.getClass().getField("errno").getInt(cause);
                    if (value > 0 && value <= 4095) return Integer.valueOf(value);
                } catch (Exception ignored) {}
            }
        }
        String[] names = {"EPERM", "EBADF", "EACCES", "ENODEV", "EINVAL", "ENOSYS", "EPROTONOSUPPORT",
            "EAFNOSUPPORT", "EADDRINUSE", "EADDRNOTAVAIL", "ENETUNREACH", "ECONNRESET", "ENOBUFS",
            "ETIMEDOUT", "ECONNREFUSED", "EHOSTUNREACH"};
        int[] numbers = {1, 9, 13, 19, 22, 38, 93, 97, 98, 99, 101, 104, 105, 110, 111, 113};
        for (Throwable cause : chain) {
            String message = cause.getMessage();
            if (message == null) continue;
            for (int index = 0; index < names.length; index++)
                if (Pattern.compile("\\b" + names[index] + "\\b").matcher(message).find())
                    return Integer.valueOf(numbers[index]);
        }
        return null;
    }

    private static void recordCountBound() throws Exception {
        Object evidence = evidenceClass.getConstructor().newInstance();
        require(((String) snapshot.invoke(evidence)).contains("records=0 olderRecordsDropped=0"), "empty archive");
        for (int index = 1; index <= 300; index++)
            add.invoke(evidence, index % 7 + 1, "small", "record-" + index);
        String value = (String) snapshot.invoke(evidence);
        require(number(value, "records") == 96, "96 record cap reached");
        require(number(value, "olderRecordsDropped") == 204, "record cap reports old drops");
        require(!value.contains("\nrecord-1\n") && value.endsWith("record-300"), "oldest removed and latest preserved");
        for (int attempt = 1; attempt <= 7; attempt++)
            require(value.contains("attempt=" + attempt + " phase=small"), "retained generation " + attempt);
        checkBounds(evidence, 300);
        System.out.println("EVIDENCE recordCap=96 olderRecordsDropped=204 generations=7");
    }

    private static void characterBoundAndTruncation() throws Exception {
        Object evidence = evidenceClass.getConstructor().newInstance();
        String large = repeat('X', 15000);
        for (int index = 1; index <= 60; index++)
            add.invoke(evidence, index % 4 + 11, "large", "entry-" + index + "|" + large);
        String value = (String) snapshot.invoke(evidence);
        int retained = number(value, "records");
        require(retained > 0 && retained < 96, "character cap trims before record cap");
        require(number(value, "olderRecordsDropped") == 60 - retained, "character cap reports drops");
        require(value.contains("entry-60|"), "latest large record retained");
        for (Object record : (Collection<?>) records.get(evidence)) {
            String text = (String) record;
            int newline = text.indexOf('\n');
            require(newline >= 0 && text.length() - newline - 1 == 10000, "per record text capped at 10000");
        }
        for (int attempt = 11; attempt <= 14; attempt++)
            require(value.contains("attempt=" + attempt + " phase=large"), "large record generation retained " + attempt);
        checkBounds(evidence, 60);
        System.out.println("EVIDENCE characterCapStored=" + characters.getInt(evidence) +
            " retained=" + retained + " olderRecordsDropped=" + (60 - retained));
    }

    private static void concurrentGenerations() throws Exception {
        final Object evidence = evidenceClass.getConstructor().newInstance();
        final Collection<Throwable> errors = new CopyOnWriteArrayList<Throwable>();
        Thread[] threads = new Thread[4];
        for (int worker = 0; worker < threads.length; worker++) {
            final int attempt = 20 + worker;
            threads[worker] = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int index = 0; index < 200; index++) {
                            add.invoke(evidence, attempt, "concurrent", "entry=" + index + "|" + repeat('Y', 6000));
                            snapshot.invoke(evidence);
                        }
                    } catch (Throwable error) { errors.add(error); }
                }
            }, "runtime-smoke-" + worker);
            threads[worker].start();
        }
        for (Thread thread : threads) {
            thread.join(15000);
            require(!thread.isAlive(), "concurrent writer completed");
        }
        require(errors.isEmpty(), "concurrent add/snapshot exception count=" + errors.size());
        checkBounds(evidence, 800);
        for (Object record : (Collection<?>) records.get(evidence)) {
            String text = (String) record;
            require(text.matches("(?s)attempt=2[0-3] phase=concurrent capturedMono=[0-9]+\\nentry=[0-9]+\\|Y+"),
                "record generation and payload remain intact");
        }
        String value = (String) snapshot.invoke(evidence);
        System.out.println("EVIDENCE concurrentAdds=800 retained=" + number(value, "records") +
            " olderRecordsDropped=" + number(value, "olderRecordsDropped"));
    }

    private static void checkBounds(Object evidence, int totalAdded) throws Exception {
        String value = (String) snapshot.invoke(evidence);
        int stored = characters.getInt(evidence);
        Collection<?> entries = (Collection<?>) records.get(evidence);
        require(entries.size() <= 96, "stored record count within bound");
        require(stored >= 0 && stored <= 160000, "stored character count within bound");
        int sum = 0;
        for (Object entry : entries) sum += ((String) entry).length();
        require(sum == stored, "stored character accounting exact");
        require(number(value, "records") == entries.size(), "snapshot count matches storage");
        require(number(value, "olderRecordsDropped") == totalAdded - entries.size(), "drop accounting exact");
        // The 160000 cap covers stored records. The human header and separators are
        // added only when formatting, so include their small bounded overhead here.
        require(value.length() <= 160000 + 96 * 5 + 200, "formatted archive including bounded separators");
    }

    private static int number(String text, String key) {
        Matcher matcher = Pattern.compile("(?:^|[^A-Za-z])" + key + "=([0-9]+)").matcher(text);
        if (!matcher.find()) throw new AssertionError("missing archive metadata " + key);
        return Integer.parseInt(matcher.group(1));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
