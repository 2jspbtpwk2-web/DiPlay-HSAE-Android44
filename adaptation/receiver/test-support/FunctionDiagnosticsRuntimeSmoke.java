import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run with app_process and the FINAL APK on its class path. No extra product classes are bundled.
 * Synthetic identity and injected event keys only: this does not authenticate a phone or prove Siri
 * recognition. The normal product invokeSiri, sendCommand, ControlCipher and bplist code execute.
 * No microphone is opened, and no speech, user identifiers or product identity assets are read.
 */
public final class FunctionDiagnosticsRuntimeSmoke {
    private static final String AIRPLAY = "com.shilapi.xcertplay.airplay.";
    private static final InetAddress LOOPBACK;
    private static int checks;
    static {
        try { LOOPBACK = InetAddress.getByName("127.0.0.1"); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    public static void main(String[] args) throws Exception {
        siriRejectsUnreadyAndClosedChannels();
        siriSendsEncryptedDownThenUp();
        siriFirstWriteFailureReturnsFalse();
        siriSecondWriteFailureReturnsFalse();
        System.out.println("PASS FunctionDiagnosticsRuntimeSmoke checks=" + checks +
            " scenarios=4 synthetic-event-crypto=true phone-auth=not-attempted siri-recognition=not-tested microphone=not-opened");
    }

    private static void siriRejectsUnreadyAndClosedChannels() throws Exception {
        Fixture fixture = new Fixture();
        SocketPair events = new SocketPair();
        try {
            require(!invokeSiri(fixture.session), "missing event socket/cipher returns false");
            require(eventSequence(fixture.session) == 0, "unready command does not consume CSeq");
            field(fixture.session, "eventSocket").set(fixture.session, events.accepted);
            require(!invokeSiri(fixture.session), "event socket without cipher returns false");
            require(eventSequence(fixture.session) == 0, "missing cipher does not consume CSeq");
            field(fixture.session, "eventSocket").set(fixture.session, null);
            field(fixture.session, "eventCipher").set(fixture.session, cipher(key(17), key(61)));
            require(!invokeSiri(fixture.session), "cipher without event socket returns false");
            require(eventSequence(fixture.session) == 0, "missing socket does not consume CSeq");
            field(fixture.session, "eventSocket").set(fixture.session, events.accepted);
            fixture.closeSession();
            require(!invokeSiri(fixture.session), "closed session returns false");
            require(eventSequence(fixture.session) == 0, "closed session does not send actions");
            require(fixture.callbackErrors.isEmpty(), "no callback failure during unready/closed test");
        } finally { events.close(); fixture.close(); }
    }

    private static void siriSendsEncryptedDownThenUp() throws Exception {
        Fixture fixture = new Fixture();
        SocketPair events = new SocketPair();
        byte[] sessionRead = key(7), sessionWrite = key(97);
        try {
            Object receiverCipher = cipher(sessionRead, sessionWrite);
            Object peerCipher = cipher(sessionWrite, sessionRead);
            field(fixture.session, "eventSocket").set(fixture.session, events.accepted);
            field(fixture.session, "eventCipher").set(fixture.session, receiverCipher);
            require(invokeSiri(fixture.session), "two real socket writes return true");
            require(eventSequence(fixture.session) == 2, "two event commands consume exactly two CSeq values");
            InputStream network = events.client.getInputStream();
            Command first = command(decryptFrame(peerCipher, network));
            Command second = command(decryptFrame(peerCipher, network));
            require(first.action == 2, "first decrypted command is Siri action 2");
            require(second.action == 3, "second decrypted command is Siri action 3");
            require(first.sequence == 1 && second.sequence == 2, "event CSeq preserves 2-to-3 action ordering");
            events.client.setSoTimeout(200);
            try { int extra = network.read(); require(extra == -1, "no unexpected third event command"); }
            catch (SocketTimeoutException expected) { checks++; }
            fixture.closeSession();
            require(!invokeSiri(fixture.session), "closed previously-ready session returns false");
            require(eventSequence(fixture.session) == 2, "closed channel does not increment sequence");
            require(fixture.callbackErrors.isEmpty(), "no callback failure during encrypted test");
        } finally {
            Arrays.fill(sessionRead, (byte) 0); Arrays.fill(sessionWrite, (byte) 0);
            events.close(); fixture.close();
        }
    }

    private static void siriFirstWriteFailureReturnsFalse() throws Exception {
        siriWriteFailure(1);
    }

    private static void siriSecondWriteFailureReturnsFalse() throws Exception {
        siriWriteFailure(2);
    }

    private static void siriWriteFailure(int failAt) throws Exception {
        Fixture fixture = new Fixture();
        FailingEventSocket failing = new FailingEventSocket(failAt);
        try {
            field(fixture.session, "eventSocket").set(fixture.session, failing);
            field(fixture.session, "eventCipher").set(fixture.session, cipher(key(31), key(71)));
            require(!invokeSiri(fixture.session), "write failure on action " + failAt + " returns false");
            require(failing.writes == failAt, "failed send closes channel without retrying another action");
            require(eventSequence(fixture.session) == failAt, "only attempted writes increment sequence");
            require(failing.closed, "event channel closed on write failure");
            require(!invokeSiri(fixture.session), "send failure leaves the session closed");
            require(failing.writes == failAt, "closed failed channel receives no late write");
            require(fixture.callbackErrors.isEmpty(), "no callback failure during send failure test");
        } finally { failing.close(); fixture.close(); }
    }

    private static boolean invokeSiri(Object session) throws Exception {
        Method invoke = session.getClass().getMethod("invokeSiri");
        require(invoke.getReturnType() == boolean.class, "prepared invokeSiri exposes actual send result");
        return Boolean.TRUE.equals(invoke.invoke(session));
    }

    private static int eventSequence(Object session) throws Exception {
        return field(session, "eventCseq").getInt(session);
    }

    private static Object cipher(byte[] read, byte[] write) throws Exception {
        return Class.forName(AIRPLAY + "ControlCipher").getConstructor(byte[].class, byte[].class)
            .newInstance(read, write);
    }

    private static byte[] decryptFrame(Object peerCipher, InputStream network) throws Exception {
        byte[] header = readFully(network, 2);
        int length = (header[0] & 255) | ((header[1] & 255) << 8);
        require(length > 0 && length <= 16384, "encrypted event payload has a bounded frame size");
        byte[] cipherText = readFully(network, length + 16);
        byte[] frame = new byte[2 + cipherText.length];
        System.arraycopy(header, 0, frame, 0, 2);
        System.arraycopy(cipherText, 0, frame, 2, cipherText.length);
        Object decrypted = peerCipher.getClass().getMethod("decrypt", byte[].class).invoke(peerCipher, (Object) frame);
        byte[] rest = (byte[]) decrypted.getClass().getMethod("getRest").invoke(decrypted);
        require(rest.length == 0, "complete encrypted frame has no unconsumed tail");
        return (byte[]) decrypted.getClass().getMethod("getData").invoke(decrypted);
    }

    private static Command command(byte[] plaintext) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(plaintext);
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        final byte[] end = new byte[] {13, 10, 13, 10};
        while (matched < 4) {
            int value = input.read();
            if (value < 0) throw new AssertionError("decrypted command contains a complete header");
            headers.write(value);
            matched = value == (end[matched] & 255) ? matched + 1 : value == 13 ? 1 : 0;
            if (headers.size() > 4096) throw new AssertionError("decrypted command header is bounded");
        }
        require(headers.size() > 0, "decrypted command contains a complete bounded header");
        String[] lines = new String(headers.toByteArray(), "US-ASCII").split("\\r\\n");
        require(lines.length >= 4 && "POST /command RTSP/1.0".equals(lines[0]), "decrypted request uses upstream /command framing");
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int split = lines[i].indexOf(':');
            if (split > 0) fields.put(lines[i].substring(0, split).toLowerCase(java.util.Locale.US), lines[i].substring(split + 1).trim());
        }
        require("application/x-apple-binary-plist".equals(fields.get("content-type")), "Siri request is an Apple binary plist");
        int size = Integer.parseInt(fields.get("content-length"));
        require(size > 0 && size < 4096 && input.available() == size, "decrypted body matches declared length");
        byte[] body = readFully(input, size);
        Class<?> codec = Class.forName(AIRPLAY + "BplistCodec");
        Object decoded = codec.getMethod("decode", byte[].class).invoke(codec.getField("INSTANCE").get(null), (Object) body);
        require(decoded instanceof Map, "Siri bplist decodes to dictionary");
        Map<?, ?> map = (Map<?, ?>) decoded;
        require("requestSiri".equals(map.get("type")), "command type is requestSiri");
        require(map.get("params") instanceof Map, "Siri parameters are a dictionary");
        Object action = ((Map<?, ?>) map.get("params")).get("siriAction");
        require(action instanceof Number, "Siri action is numeric");
        return new Command(Integer.parseInt(fields.get("cseq")), ((Number) action).intValue());
    }

    private static final class Command {
        final int sequence, action;
        Command(int sequence, int action) { this.sequence = sequence; this.action = action; }
    }

    private static final class SocketPair {
        final Socket client, accepted;
        SocketPair() throws Exception {
            ServerSocket listener = new ServerSocket(0, 1, LOOPBACK);
            Socket clientSocket = null, serverSocket = null;
            try {
                clientSocket = new Socket();
                clientSocket.connect(new InetSocketAddress(LOOPBACK, listener.getLocalPort()), 3000);
                clientSocket.setSoTimeout(3000);
                serverSocket = listener.accept();
                client = clientSocket; accepted = serverSocket;
            } catch (Exception error) {
                if (serverSocket != null) serverSocket.close();
                if (clientSocket != null) clientSocket.close();
                throw error;
            } finally { listener.close(); }
        }
        void close() throws Exception { try { accepted.close(); } finally { client.close(); } }
    }

    private static final class Fixture implements InvocationHandler {
        final SocketPair control;
        final Object session;
        final List<String> callbackErrors = new ArrayList<String>();
        Fixture() throws Exception {
            control = new SocketPair();
            try {
                Class<?> sessionClass = Class.forName(AIRPLAY + "AirPlaySession");
                Class<?> listenerClass = Class.forName(AIRPLAY + "AirPlaySessionListener");
                Class<?> mediaClass = Class.forName(AIRPLAY + "AirPlayMediaHandler");
                Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] {listenerClass}, this);
                Object media = Proxy.newProxyInstance(mediaClass.getClassLoader(), new Class<?>[] {mediaClass}, this);
                Constructor<?> constructor = primaryConstructor(sessionClass);
                require(constructor.getParameterTypes().length == 7, "current AirPlaySession constructor shape");
                session = constructor.newInstance(control.accepted, config(),
                    Class.forName(AIRPLAY + "AirPlayIdentity").getConstructor(byte[].class, byte[].class, String.class)
                        .newInstance(new byte[32], new byte[32], "00000000-0000-4000-8000-000000000056"),
                    primaryConstructor(Class.forName(AIRPLAY + "PairingStore")).newInstance(new Object[] {null}),
                    null, listener, media);
                // Deliberately do not start the RTSP reader or perform phone authentication.
            } catch (Exception error) { control.close(); throw error; }
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
                if ("equals".equals(name)) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                return "FunctionDiagnosticsSyntheticProxy";
            }
            if ("onControlFailure".equals(name)) callbackErrors.add("control-failure");
            // Debug logs are intentionally discarded: no TRACE/plist/key data enters test output.
            if (method.getReturnType() == boolean.class) return Boolean.FALSE;
            if (method.getReturnType() == int.class) return Integer.valueOf(0);
            if (method.getReturnType() == long.class) return Long.valueOf(0);
            return null;
        }
        void closeSession() throws Exception { session.getClass().getMethod("close").invoke(session); }
        void close() throws Exception { try { closeSession(); } finally { control.close(); } }
    }

    private static final class FailingEventSocket extends Socket {
        final int failAt;
        int writes;
        boolean closed;
        final OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("synthetic single-byte write unsupported"); }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                writes++;
                if (writes == failAt) throw new IOException("synthetic event output failure");
            }
        };
        FailingEventSocket(int failAt) { this.failAt = failAt; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void close() { closed = true; }
    }

    private static Object config() throws Exception {
        Class<?> displayClass = Class.forName(AIRPLAY + "AirPlayDisplayConfig");
        Constructor<?> displayConstructor = primaryConstructor(displayClass);
        Class<?>[] displayTypes = displayConstructor.getParameterTypes();
        Object[] displayArgs = new Object[displayTypes.length];
        for (int i = 0; i < displayTypes.length; i++) {
            if (displayTypes[i] == int.class) displayArgs[i] = Integer.valueOf(i == 0 ? 720 : i == 1 ? 864 : i == 4 ? 30 : 1);
            else if (displayTypes[i] == boolean.class) displayArgs[i] = Boolean.FALSE;
        }
        Object display = displayConstructor.newInstance(displayArgs);
        Constructor<?> constructor = primaryConstructor(Class.forName(AIRPLAY + "AirPlayConfig"));
        Class<?>[] types = constructor.getParameterTypes();
        require(types.length >= 16 && types[4] == displayClass, "current AirPlayConfig constructor shape");
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String.class) values[i] = i == 1 || i == 2 ? "02:00:00:00:00:56" : "Synthetic";
            else if (types[i] == int.class) values[i] = Integer.valueOf(i == 7 ? 7000 : i == 8 ? 44100 : 0);
            else if (types[i] == boolean.class) values[i] = Boolean.FALSE;
            else if (List.class.isAssignableFrom(types[i])) values[i] = Collections.emptyList();
        }
        values[4] = display;
        return constructor.newInstance(values);
    }

    private static Constructor<?> primaryConstructor(Class<?> type) throws Exception {
        Constructor<?> best = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 0 || "kotlin.jvm.internal.DefaultConstructorMarker".equals(parameters[parameters.length - 1].getName())) continue;
            if (best == null || parameters.length > best.getParameterTypes().length) best = constructor;
        }
        if (best == null) throw new NoSuchMethodException(type.getName());
        best.setAccessible(true); return best;
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static byte[] key(int start) {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (start + i * 3);
        return key;
    }

    private static byte[] readFully(InputStream input, int count) throws Exception {
        byte[] result = new byte[count]; int offset = 0;
        while (offset < count) {
            int size = input.read(result, offset, count - offset);
            if (size <= 0) throw new IOException("synthetic event frame ended early");
            offset += size;
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
