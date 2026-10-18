import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop validation only: execute with app_process and the FINAL APK on the class path.
 * Uses real product RTSP, plist, timing and event code; no product assets are opened.
 * The synthetic identity is deliberately unusable for pairing, and MFi is null.
 * Media handlers deliberately reject streams: this is not an authentication/media test.
 *
 * javac -source 7 -target 7 -d <classes> AirPlaySessionSmoke.java
 * jar cf <smoke.jar> -C <classes> .
 * D8 --min-api 18 --output <dex-dir> <smoke.jar>
 * CLASSPATH=<classes.dex>:<final.apk> app_process /system/bin AirPlaySessionSmoke [127.0.0.1|::1|fe80::...%eth0]
 * An optional second argument --legacy-bind-metadata records the historical bind
 * helper result without requiring its IPv6 fix, for comparison with an older APK.
 *
 * Reflection avoids adding Kotlin runtime methods or a second copy of product code to
 * the test dex. With current dontobfuscate/dontoptimize rules all accessed members are
 * also reached by the product. No additional broad keep rule should be required.
 */
public final class AirPlaySessionSmoke {
    private static final String PACKAGE = "com.shilapi.xcertplay.airplay.";
    private static InetAddress LOOPBACK;
    private static int checks;
    private static final List<Throwable> uncaught = new CopyOnWriteArrayList<Throwable>();

    public static void main(String[] args) throws Exception {
        LOOPBACK = InetAddress.getByName(args.length == 0 ? "127.0.0.1" : args[0]);
        boolean requireFixedBind = args.length < 2 || !"--legacy-bind-metadata".equals(args[1]);
        System.out.println("SMOKE network family=" + (LOOPBACK instanceof Inet6Address ? "IPv6" : "IPv4") +
            " scoped=" + (LOOPBACK instanceof Inet6Address && ((Inet6Address) LOOPBACK).getScopeId() != 0));
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) { uncaught.add(error); }
        });
        try {
            legacyBindPreservesIpv6(requireFixedBind);
            normalControlAndTiming();
            malformedSetupIsControlled();
            unauthenticatedEventIsControlled();
            if (requireFixedBind) {
                setupListenerFailureRollsBack(false);
                setupListenerFailureRollsBack(true);
                currentEventListenerFailureClosesSession();
            } else {
                System.out.println("SMOKE setup-rollback skipped=legacy-bind-metadata");
            }
            require(uncaught.isEmpty(), "uncaught product thread failure count=" + uncaught.size());
            System.out.println("PASS AirPlaySessionSmoke checks=" + checks +
                " scenarios=" + (requireFixedBind ? 6 : 3) + " auth=not-attempted media=deliberately-rejected");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static void legacyBindPreservesIpv6(boolean enforce) throws Exception {
        Class<?> helper = Class.forName("local.airuize.receiver.LegacyBindAddress");
        Object instance = helper.getField("INSTANCE").get(null);
        Method any = helper.getMethod("any", InetAddress.class);
        byte[] linkLocalBytes = new byte[16];
        linkLocalBytes[0] = (byte) 0xfe; linkLocalBytes[1] = (byte) 0x80; linkLocalBytes[15] = 2;
        // A synthetic scope verifies preservation even when the selected route is
        // loopback. It is never bound or used to transmit a packet.
        Inet6Address scoped = Inet6Address.getByAddress(null, linkLocalBytes, 7);
        assertIpv6Bind(any, instance, scoped, enforce, "synthetic-scoped");
        if (LOOPBACK instanceof Inet6Address) {
            assertIpv6Bind(any, instance, (Inet6Address) LOOPBACK, enforce, "selected-local");
        }
    }

    private static void assertIpv6Bind(Method any, Object instance, Inet6Address local,
            boolean enforce, String label) throws Exception {
        Object result = any.invoke(instance, local);
        boolean sameObject = result == local;
        boolean sameAddress = result instanceof Inet6Address &&
            Arrays.equals(local.getAddress(), ((Inet6Address) result).getAddress());
        boolean sameScope = result instanceof Inet6Address &&
            local.getScopeId() == ((Inet6Address) result).getScopeId();
        System.out.println("SMOKE legacy-bind label=" + label + " inputScope=" + local.getScopeId() +
            " outputFamily=" + (result instanceof Inet6Address ? "IPv6" : "other") +
            " sameObject=" + sameObject + " sameAddress=" + sameAddress + " sameScope=" + sameScope +
            " enforced=" + enforce);
        if (enforce) {
            require(sameObject, "IPv6 bind retains original InetAddress object: " + label);
            require(sameAddress, "IPv6 bind retains address bytes: " + label);
            require(sameScope, "IPv6 bind retains interface scope: " + label);
        }
    }

    private static void normalControlAndTiming() throws Exception {
        Fixture fixture = new Fixture("control-timing");
        DatagramSocket peerTiming = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        peerTiming.setSoTimeout(5000);
        int timingPort = 0, eventPort = 0, keepAlivePort = 0;
        try {
            fixture.expect("OPTIONS", "*", new byte[0], 200);
            Response info = fixture.expect("GET", "/info", new byte[0], 200);
            Map<?, ?> infoBody = plistMap(info.body);
            require(infoBody.get("displays") instanceof List, "info has display declaration");
            require(infoBody.get("audioFormats") instanceof List, "info has audio formats");

            Response setup = fixture.expect("SETUP", "/stream", plist(map(
                "timingPort", peerTiming.getLocalPort(), "keepAliveLowPower", Boolean.TRUE)), 200);
            Map<?, ?> setupBody = plistMap(setup.body);
            timingPort = port(setupBody.get("timingPort"));
            eventPort = port(setupBody.get("eventPort"));
            keepAlivePort = port(setupBody.get("keepAlivePort"));

            // Answer the real NtpClock's outbound request. Echoing t1 into t2/t3 yields
            // a valid, nonnegative local RTT sample without reading wall-clock identity.
            byte[] requestBytes = new byte[64];
            DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
            peerTiming.receive(request);
            require(request.getLength() == 32 && (requestBytes[1] & 255) == 210,
                "timing emitted a bounded RTCP request");
            byte[] responseBytes = new byte[32];
            responseBytes[0] = (byte) 0x80; responseBytes[1] = (byte) 211; responseBytes[3] = 7;
            System.arraycopy(requestBytes, 24, responseBytes, 8, 8);
            System.arraycopy(requestBytes, 24, responseBytes, 16, 8);
            System.arraycopy(requestBytes, 24, responseBytes, 24, 8);
            peerTiming.send(new DatagramPacket(responseBytes, responseBytes.length,
                request.getAddress(), request.getPort()));
            fixture.capture.awaitDiagnostic("airplay diag timing first-valid-sample");

            peerTiming.send(new DatagramPacket(new byte[] {0}, 1, LOOPBACK, keepAlivePort));
            fixture.capture.awaitDiagnostic("airplay diag keepalive first-packet");

            List<Object> streams = new ArrayList<Object>();
            streams.add(map("type", 110, "width", 960, "height", 1280, "fps", 30));
            streams.add(map("type", 100, "sampleRate", 48000, "channels", 2, "audioType", "media"));
            Response rejectedStreams = fixture.expect("SETUP", "/stream", plist(map("streams", streams)), 200);
            Object accepted = plistMap(rejectedStreams.body).get("streams");
            require(accepted instanceof List && ((List<?>) accepted).isEmpty(),
                "null media callbacks reject streams without crashing");
            require(fixture.capture.screenRequests.get() == 1, "screen callback observed");
            require(fixture.capture.audioRequests.get() == 1, "audio callback observed");
            fixture.capture.awaitDiagnostic("airplay diag setup result kind=screen type=110 accepted=false");
            fixture.capture.awaitDiagnostic("airplay diag setup result kind=audio type=100 accepted=false");

            fixture.expect("RECORD", "/stream", new byte[0], 200);
            require(fixture.capture.active.get() == 1, "RECORD session active callback once");
            fixture.expect("TEARDOWN", "/stream", plist(map()), 200);
            fixture.client.shutdownOutput();
            fixture.awaitEnded();
            fixture.capture.awaitDiagnostic("airplay diag control closed reason=eof lastStage=TEARDOWN");
            fixture.capture.awaitDiagnostic("airplay diag timing summary");
            fixture.capture.awaitDiagnostic("validSamples=1");
            fixture.capture.requireRequestStage("OPTIONS", 200);
            fixture.capture.requireRequestStage("info", 200);
            fixture.capture.requireRequestStage("SETUP", 200);
            fixture.capture.requireRequestStage("RECORD", 200);
            fixture.capture.requireRequestStage("TEARDOWN", 200);
            fixture.closeSession();
            fixture.closeSession();
            require(fixture.capture.ended.get() == 1, "idempotent end notification");
            require(fixture.capture.mediaClosed.get() == 1, "idempotent media close notification");
            assertTcpPortReleased(eventPort);
            assertUdpPortReleased(timingPort);
            assertUdpPortReleased(keepAlivePort);
            fixture.capture.printDiagnostics();
        } finally {
            peerTiming.close();
            fixture.close();
        }
    }

    private static void malformedSetupIsControlled() throws Exception {
        Fixture fixture = new Fixture("malformed-setup");
        try {
            fixture.expect("SETUP", "/stream", new byte[] {0}, 400);
            fixture.capture.awaitDiagnostic("airplay diag setup rejected reason=plist-decode");
            fixture.expect("OPTIONS", "*", new byte[0], 200);
            fixture.client.shutdownOutput();
            fixture.awaitEnded();
            fixture.capture.requireRequestStage("SETUP", 400);
            require(fixture.capture.active.get() == 0, "invalid SETUP does not activate a session");
            fixture.capture.printDiagnostics();
        } finally { fixture.close(); }
    }

    private static void unauthenticatedEventIsControlled() throws Exception {
        Fixture fixture = new Fixture("event-without-pairing");
        Socket event = null;
        try {
            Response setup = fixture.expect("SETUP", "/stream", plist(map("timingPort", 0)), 200);
            int eventPort = port(plistMap(setup.body).get("eventPort"));
            event = new Socket();
            event.connect(new InetSocketAddress(LOOPBACK, eventPort), 5000);
            event.setSoTimeout(5000);
            fixture.capture.awaitDiagnostic("airplay diag event accepted");
            fixture.capture.awaitDiagnostic("airplay diag error stage=SETUP error=event-no-secret");
            fixture.awaitEnded();
            fixture.capture.awaitDiagnostic("airplay diag control closed");
            require(fixture.capture.active.get() == 0, "unauthenticated event never activates session");
            boolean closed = false;
            try { closed = fixture.client.getInputStream().read() == -1; }
            catch (SocketException expected) { closed = true; }
            require(closed, "unauthenticated event closes control socket");
            require(fixture.capture.mediaClosed.get() == 1, "controlled event failure tears down media once");
            fixture.capture.printDiagnostics();
        } finally {
            if (event != null) event.close();
            fixture.close();
        }
    }

    private static void setupListenerFailureRollsBack(boolean afterEvent) throws Exception {
        Fixture fixture = new Fixture(afterEvent ? "rollback-keepalive-bind" : "rollback-event-bind");
        SetupBindFailure injection = new SetupBindFailure(fixture.session, afterEvent);
        int timingPort = 0, eventPort = 0, keepAlivePort = 0;
        try {
            fixture.capture.bindFailure = injection;
            fixture.expect("SETUP", "/stream", plist(map("timingPort", 0, "keepAliveLowPower", Boolean.TRUE)), 500);
            fixture.capture.bindFailure = null;
            injection.restore();
            fixture.capture.printDiagnostics();
            require(injection.fired, "listener failure injected after the expected real bind");
            require(injection.hookFailure == null, "reflection failure injection completed");
            require(injection.timing != null && injection.timing.isClosed(), "failed SETUP closes its earlier timing socket");
            assertUdpPortReleased(injection.timingPort);
            require(field(fixture.session, "eventServer") == null, "failed SETUP clears event listener publication");
            require(field(fixture.session, "keepAliveSocket") == null, "failed SETUP clears keepalive publication");
            if (afterEvent) {
                require(injection.event != null && injection.event.isClosed(), "keepalive bind failure closes earlier event listener");
                assertTcpPortReleased(injection.eventPort);
            }

            // Reuse this same live control connection. A stale running NtpClock
            // or published listener would make the following request fail.
            Response retry = fixture.expect("SETUP", "/stream", plist(map("timingPort", 0,
                "keepAliveLowPower", Boolean.TRUE)), 200);
            Map<?, ?> body = plistMap(retry.body);
            timingPort = port(body.get("timingPort"));
            eventPort = port(body.get("eventPort"));
            keepAlivePort = port(body.get("keepAlivePort"));
            DatagramSocket timing = (DatagramSocket) field(field(fixture.session, "ntp"), "socket");
            ServerSocket retryEvent = (ServerSocket) field(fixture.session, "eventServer");
            DatagramSocket retryKeepAlive = (DatagramSocket) field(fixture.session, "keepAliveSocket");
            require(timing != null && !timing.isClosed() && timing.getLocalPort() == timingPort,
                "same-session retry opens timing after rollback");
            fixture.expect("OPTIONS", "*", new byte[0], 200);
            require(fixture.capture.active.get() == 0, "failed and retried SETUP do not fabricate RECORD activation");
            fixture.client.shutdownOutput();
            fixture.awaitEnded();
            fixture.closeSession();
            fixture.capture.printDiagnostics();
            require(timing.isClosed(), "final close closes retried timing listener");
            require(retryEvent != null && retryEvent.isClosed(), "final close closes retried event listener");
            require(retryKeepAlive != null && retryKeepAlive.isClosed(), "final close closes retried keepalive listener");
            assertUdpPortReleased(timingPort);
            assertTcpPortReleased(eventPort);
            assertUdpPortReleased(keepAlivePort);
        } finally {
            fixture.capture.bindFailure = null;
            injection.restore();
            fixture.close();
        }
    }

    private static void currentEventListenerFailureClosesSession() throws Exception {
        Fixture fixture = new Fixture("current-event-listener-failure");
        try {
            Response setup = fixture.expect("SETUP", "/stream", plist(map("timingPort", 0,
                "keepAliveLowPower", Boolean.TRUE)), 200);
            Map<?, ?> body = plistMap(setup.body);
            int timingPort = port(body.get("timingPort"));
            int eventPort = port(body.get("eventPort"));
            int keepAlivePort = port(body.get("keepAlivePort"));
            ServerSocket currentEvent = (ServerSocket) field(fixture.session, "eventServer");
            DatagramSocket timing = (DatagramSocket) field(field(fixture.session, "ntp"), "socket");
            DatagramSocket keepAlive = (DatagramSocket) field(fixture.session, "keepAliveSocket");
            require(currentEvent != null && !currentEvent.isClosed() && currentEvent.getLocalPort() == eventPort,
                "current event listener is published before failure injection");
            require(timing != null && !timing.isClosed(), "current event failure begins with timing listener open");
            require(keepAlive != null && !keepAlive.isClosed(), "current event failure begins with keepalive open");

            // This is the active listener from a successful SETUP, with no
            // rollback or replacement. Closing it must fail its blocking accept
            // and terminate this control session through the current-listener path.
            currentEvent.close();
            fixture.awaitEnded();
            fixture.capture.printDiagnostics();
            fixture.capture.awaitDiagnostic("airplay diag error stage=SETUP error=event-accept");
            require(fixture.capture.ended.get() == 1, "current event failure ends session once");
            require(fixture.capture.mediaClosed.get() == 1, "current event failure closes media once");
            boolean controlClosed = false;
            try { controlClosed = fixture.client.getInputStream().read() == -1; }
            catch (SocketException expected) { controlClosed = true; }
            require(controlClosed, "current event failure closes control TCP connection");
            require(currentEvent.isClosed() && timing.isClosed() && keepAlive.isClosed(),
                "current event failure closes all general SETUP listeners");
            fixture.closeSession();
            fixture.closeSession();
            require(fixture.capture.ended.get() == 1 && fixture.capture.mediaClosed.get() == 1,
                "later explicit closes retain exactly-once end and media callbacks");
            assertUdpPortReleased(timingPort);
            assertTcpPortReleased(eventPort);
            assertUdpPortReleased(keepAlivePort);
        } finally { fixture.close(); }
    }

    /**
     * Test-only reflection changes the next listener's local address after a real
     * preceding bind has completed. The next product socket therefore fails in
     * the OS, without a fake socket, a product test seam or global socket factory.
     * Only this fixture's address field changes; its actual control socket stays
     * connected. The field is restored before a same-session retry and on error.
     */
    private static final class SetupBindFailure {
        final Object session;
        final Field localField;
        final Object originalAddress;
        final Inet6Address unavailable;
        final String after;
        volatile boolean fired;
        volatile Throwable hookFailure;
        volatile DatagramSocket timing;
        volatile ServerSocket event;
        volatile int timingPort;
        volatile int eventPort;

        SetupBindFailure(Object session, boolean afterEvent) throws Exception {
            this.session = session;
            localField = session.getClass().getDeclaredField("localAddress");
            localField.setAccessible(true);
            originalAddress = localField.get(session);
            byte[] bytes = new byte[16];
            bytes[0] = (byte) 0xfe; bytes[1] = (byte) 0x80; bytes[15] = 0x7f;
            unavailable = Inet6Address.getByAddress(null, bytes, 65000);
            after = afterEvent ? "airplay diag event listening " : "airplay diag timing listening ";
        }

        void onDiagnostic(String message) {
            if (fired || !message.startsWith(after)) return;
            fired = true;
            try {
                timing = (DatagramSocket) field(field(session, "ntp"), "socket");
                timingPort = timing.getLocalPort();
                if (after.startsWith("airplay diag event")) {
                    event = (ServerSocket) field(session, "eventServer");
                    eventPort = event.getLocalPort();
                }
                localField.set(session, unavailable);
            } catch (Throwable error) { hookFailure = error; }
        }

        void restore() throws Exception { localField.set(session, originalAddress); }
    }

    private static final class Fixture {
        final Socket client;
        final Object session;
        final Capture capture;
        final InputStream input;
        final OutputStream output;
        int cseq;

        Fixture(String label) throws Exception {
            capture = new Capture(label);
            ServerSocket listener = new ServerSocket(0, 1, LOOPBACK);
            Socket accepted = null;
            Socket createdClient = null;
            try {
                createdClient = new Socket();
                createdClient.connect(new InetSocketAddress(LOOPBACK, listener.getLocalPort()), 5000);
                createdClient.setSoTimeout(5000);
                accepted = listener.accept();
                Class<?> sessionClass = Class.forName(PACKAGE + "AirPlaySession");
                Class<?> listenerClass = Class.forName(PACKAGE + "AirPlaySessionListener");
                Class<?> mediaClass = Class.forName(PACKAGE + "AirPlayMediaHandler");
                Object listenerProxy = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] {listenerClass}, capture);
                Object mediaProxy = Proxy.newProxyInstance(mediaClass.getClassLoader(), new Class<?>[] {mediaClass}, capture);
                Constructor<?> constructor = primaryConstructor(sessionClass);
                require(constructor.getParameterTypes().length == 7, "AirPlaySession constructor shape");
                session = constructor.newInstance(accepted, config(),
                    Class.forName(PACKAGE + "AirPlayIdentity").getConstructor(byte[].class, byte[].class, String.class)
                        .newInstance(new byte[32], new byte[32], "00000000-0000-4000-8000-000000000001"),
                    primaryConstructor(Class.forName(PACKAGE + "PairingStore")).newInstance(new Object[] {null}),
                    null, listenerProxy, mediaProxy);
                client = createdClient;
                input = client.getInputStream();
                output = client.getOutputStream();
                sessionClass.getMethod("start").invoke(session);
            } catch (Exception error) {
                if (accepted != null) accepted.close();
                if (createdClient != null) createdClient.close();
                throw error;
            } finally { listener.close(); }
        }

        Response expect(String method, String path, byte[] body, int status) throws Exception {
            cseq++;
            String header = method + " " + path + " RTSP/1.0\r\nCSeq: " + cseq +
                "\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: " + body.length + "\r\n\r\n";
            output.write(header.getBytes("US-ASCII")); output.write(body); output.flush();
            Response response = response(input);
            require(response.status == status, method + " returned " + response.status + " instead of " + status);
            require(Integer.toString(cseq).equals(response.headers.get("cseq")), "response CSeq echoed");
            return response;
        }

        void awaitEnded() throws Exception {
            require(capture.endLatch.await(5, TimeUnit.SECONDS), "session ended within deadline");
        }

        void closeSession() throws Exception { session.getClass().getMethod("close").invoke(session); }
        void close() throws Exception { try { closeSession(); } finally { client.close(); } }
    }

    private static final class Capture implements InvocationHandler {
        final String label;
        final List<String> diagnostic = new CopyOnWriteArrayList<String>();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger ended = new AtomicInteger();
        final AtomicInteger mediaClosed = new AtomicInteger();
        final AtomicInteger screenRequests = new AtomicInteger();
        final AtomicInteger audioRequests = new AtomicInteger();
        final CountDownLatch endLatch = new CountDownLatch(1);
        volatile SetupBindFailure bindFailure;

        Capture(String label) { this.label = label; }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if (name.equals("hashCode")) return System.identityHashCode(proxy);
                if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                return "AirPlaySessionSmokeProxy";
            }
            if (name.equals("onDebugLog") && args != null && args.length == 1) {
                String message = String.valueOf(args[0]);
                // Only the new metadata allowlist is retained, never TRACE/body/plist output.
                if (message.startsWith("airplay diag ") && diagnostic.size() < 128) diagnostic.add(message);
                SetupBindFailure injection = bindFailure;
                if (injection != null) injection.onDiagnostic(message);
            } else if (name.equals("onSessionActive")) active.incrementAndGet();
            else if (name.equals("onSessionEnded")) { ended.incrementAndGet(); endLatch.countDown(); }
            else if (name.equals("onSessionClosed")) mediaClosed.incrementAndGet();
            else if (name.equals("onScreen")) screenRequests.incrementAndGet();
            else if (name.equals("onAudio")) audioRequests.incrementAndGet();
            if (method.getReturnType() == boolean.class) return Boolean.FALSE;
            if (method.getReturnType() == int.class) return Integer.valueOf(0);
            if (method.getReturnType() == long.class) return Long.valueOf(0);
            return null;
        }

        void awaitDiagnostic(String text) throws Exception {
            long deadline = System.nanoTime() + 5_000_000_000L;
            do {
                for (String message : diagnostic) if (message.contains(text)) { checks++; return; }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            throw new AssertionError(label + " missing diagnostic: " + text);
        }

        void requireRequestStage(String stage, int status) throws Exception {
            String expected = "airplay diag request stage=" + stage + " status=" + status;
            awaitDiagnostic(expected);
            for (String message : diagnostic) if (message.contains(expected)) {
                require(message.contains("durationMs=") && message.contains("elapsedMs="), "stage includes timing metadata");
                return;
            }
        }

        void printDiagnostics() {
            for (String message : diagnostic) System.out.println("SMOKE " + label + " " + message);
        }
    }

    private static Object config() throws Exception {
        Class<?> displayClass = Class.forName(PACKAGE + "AirPlayDisplayConfig");
        Constructor<?> displayConstructor = primaryConstructor(displayClass);
        Class<?>[] displayTypes = displayConstructor.getParameterTypes();
        Object[] displayArgs = new Object[displayTypes.length];
        for (int index = 0; index < displayTypes.length; index++) {
            if (displayTypes[index] == int.class) displayArgs[index] = index == 0 ? 960 : index == 1 ? 1280 : index == 4 ? 30 : 1;
            else if (displayTypes[index] == boolean.class) displayArgs[index] = Boolean.FALSE;
        }
        Object display = displayConstructor.newInstance(displayArgs);
        Constructor<?> constructor = primaryConstructor(Class.forName(PACKAGE + "AirPlayConfig"));
        Class<?>[] types = constructor.getParameterTypes();
        require(types.length >= 16 && types[4] == displayClass, "AirPlayConfig constructor shape");
        Object[] values = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            if (types[index] == String.class) values[index] = (index == 1 || index == 2) ? "02:00:00:00:00:01" : "Smoke";
            else if (types[index] == int.class) values[index] = index == 7 ? 7000 : index == 8 ? 48000 : 0;
            else if (types[index] == boolean.class) values[index] = Boolean.FALSE;
            else if (List.class.isAssignableFrom(types[index])) values[index] = Collections.emptyList();
        }
        values[4] = display;
        return constructor.newInstance(values);
    }

    private static Constructor<?> primaryConstructor(Class<?> type) throws Exception {
        Constructor<?> candidate = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 0) continue;
            if (parameters[parameters.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) continue;
            if (candidate == null || parameters.length > candidate.getParameterTypes().length) candidate = constructor;
        }
        if (candidate == null) throw new NoSuchMethodException("primary constructor: " + type.getName());
        candidate.setAccessible(true);
        return candidate;
    }

    private static byte[] plist(Object value) throws Exception {
        Class<?> codec = Class.forName(PACKAGE + "BplistCodec");
        return (byte[]) codec.getMethod("encode", Object.class).invoke(codec.getField("INSTANCE").get(null), value);
    }

    private static Map<?, ?> plistMap(byte[] bytes) throws Exception {
        Class<?> codec = Class.forName(PACKAGE + "BplistCodec");
        Object decoded = codec.getMethod("decode", byte[].class).invoke(codec.getField("INSTANCE").get(null), new Object[] {bytes});
        require(decoded instanceof Map, "response is a plist dictionary");
        return (Map<?, ?>) decoded;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static int port(Object value) {
        require(value instanceof Number, "response includes numeric port");
        int port = ((Number) value).intValue();
        require(port > 0 && port <= 65535, "response port is valid");
        return port;
    }

    private static final class Response {
        int status;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body;
    }

    private static Response response(InputStream input) throws Exception {
        Response response = new Response();
        String[] status = line(input).split(" ", 3);
        require(status.length >= 2 && status[0].startsWith("RTSP/"), "RTSP response framing");
        response.status = Integer.parseInt(status[1]);
        int headerCount = 0;
        while (true) {
            String header = line(input);
            if (header.length() == 0) break;
            require(++headerCount <= 64, "response header count bounded");
            int colon = header.indexOf(':');
            require(colon > 0, "response header framing");
            response.headers.put(header.substring(0, colon).toLowerCase(Locale.US), header.substring(colon + 1).trim());
        }
        String lengthValue = response.headers.get("content-length");
        int length = lengthValue == null ? 0 : Integer.parseInt(lengthValue);
        require(length >= 0 && length <= 256 * 1024, "response body length bounded");
        response.body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(response.body, offset, length - offset);
            if (count < 0) throw new AssertionError("truncated RTSP response body");
            offset += count;
        }
        return response;
    }

    private static String line(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (bytes.size() < 8192) {
            int value = input.read();
            if (value < 0) throw new AssertionError("unexpected RTSP EOF");
            if (previous == '\r' && value == '\n') {
                byte[] raw = bytes.toByteArray();
                return new String(raw, 0, raw.length - 1, "US-ASCII");
            }
            bytes.write(value); previous = value;
        }
        throw new AssertionError("RTSP response line exceeds limit");
    }

    private static void assertTcpPortReleased(int port) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        int retries = 0;
        while (true) {
            ServerSocket socket = new ServerSocket();
            try {
                socket.bind(new InetSocketAddress(LOOPBACK, port));
                checks++;
                if (retries > 0) System.out.println("SMOKE TCP port released after close retries=" + retries);
                return;
            } catch (SocketException error) {
                if (!addressInUse(error) || System.nanoTime() >= deadline) throw error;
                retries++;
            } finally { socket.close(); }
            Thread.sleep(20);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void assertUdpPortReleased(int port) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        int retries = 0;
        while (true) {
            DatagramSocket socket = new DatagramSocket(null);
            try {
                socket.bind(new InetSocketAddress(LOOPBACK, port));
                checks++;
                if (retries > 0) System.out.println("SMOKE UDP port released after close retries=" + retries);
                return;
            } catch (SocketException error) {
                if (!addressInUse(error) || System.nanoTime() >= deadline) throw error;
                retries++;
            } finally { socket.close(); }
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
