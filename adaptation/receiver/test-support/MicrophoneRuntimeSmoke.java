import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Execute with app_process and the FINAL APK on its class path. Reflection opens the actual
 * LegacyMicrophone worker with synthetic LPCM/session keys and a loopback UDP destination.
 * Android AudioRecord runs normally; no audio route, mode, gain, SCO, setting or effect is changed.
 * No product identity asset, phone or authentication is involved. No PCM or media packet is written
 * to disk or printed. A received payload is authenticated transiently and immediately cleared.
 *
 * A lifecycle PASS does not imply capture passed: the final RESULT explicitly distinguishes
 * capture=PASS, PARTIAL and UNAVAILABLE. A separate PASS line appears only after both normal
 * recording rounds produced and authenticated actual AudioRecord packets.
 */
public final class MicrophoneRuntimeSmoke {
    private static final String RECEIVER = "local.airuize.receiver.";
    private static final String AIRPLAY = "com.shilapi.xcertplay.airplay.";
    private static int checks;
    private static Object unit;
    private static int baselineThreads;
    private static final CopyOnWriteArrayList<Throwable> uncaught = new CopyOnWriteArrayList<Throwable>();

    public static void main(String[] args) throws Exception {
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        baselineThreads = microphoneThreads();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) { uncaught.add(error); }
        });
        try {
            Object diagnostics = Class.forName(RECEIVER + "MediaDiagnostics").getConstructor().newInstance();
            Object microphoneDiagnostics = diagnostics.getClass().getMethod("getMicrophone").invoke(diagnostics);
            int initialFd = fdCount();
            Round speech = round(diagnostics, microphoneDiagnostics, "speechrecognition", 6, false);
            int warmedFd = fdCount();
            Round phone = round(diagnostics, microphoneDiagnostics, "telephony", 7, false);
            Round cancelled = round(diagnostics, microphoneDiagnostics, "speechrecognition", 6, true);
            silentRound(diagnostics, microphoneDiagnostics);
            int finalFd = fdCount();
            require(microphoneThreads() == baselineThreads, "all actual microphone workers ended");
            require(uncaught.isEmpty(), "no uncaught product or receiver failure");
            require(finalFd <= warmedFd + 2, "no growing fd count after warmed repeated capture/close");
            String history = snapshot(microphoneDiagnostics);
            require(history.contains("保留=4/8"), "three capture records and one synthetic stream remain after close");
            require(occurrences(history, "阶段=FINISHED") == 4, "ended history retains all four finished streams");
            require(history.contains("source=6") && history.contains("source=7"), "Siri and telephony sources recorded separately");
            require(!history.contains("127.0.0.1"), "history does not export loopback peer address");
            String capture = speech.captured && phone.captured ? "PASS" : speech.observedAudio || phone.observedAudio ? "PARTIAL" : "UNAVAILABLE";
            System.out.println("RESULT MicrophoneRuntimeSmoke checks=" + checks + " lifecycle=PASS capture=" + capture +
                " speech=" + speech.status + " telephony=" + phone.status + " immediateClose=" + cancelled.status +
                " fdInitial=" + initialFd + " fdWarmed=" + warmedFd + " fdFinal=" + finalFd +
                " rawPCM=saved-never phone-auth=not-attempted hardware-mic=not-proven siri-recognition=not-tested");
            if ("PASS".equals(capture)) System.out.println("PASS MicrophoneRuntimeSmoke checks=" + checks +
                " realAudioRecord=true encryptedLoopback=true lifecycle=true sources=6,7 syntheticSilence=true disabledCaptureNeverOpened=true");
            else System.out.println("LIMIT MicrophoneRuntimeSmoke capture=" + capture +
                " actualAndroidCaptureNotProvenForAllSources=true see-round-state-and-read-codes");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }

    private static void silentRound(Object diagnostics, Object microphoneDiagnostics) throws Exception {
        InetAddress loopback=InetAddress.getByName("127.0.0.1");
        byte[] key=syntheticKey();
        Receiver receiver=new Receiver(loopback,key,true);
        Object worker=null,evidence=null;
        try {
            evidence=microphoneDiagnostics.getClass().getMethod("begin",String.class,int.class,int.class,
                int.class,String.class,int.class,int.class,int.class,Integer.class,boolean.class)
                .invoke(microphoneDiagnostics,"speechrecognition",-1,16000,1,"LPCM",20,640,320,null,false);
            Class<?> codecClass=Class.forName(AIRPLAY+"AudioCodecKind");
            @SuppressWarnings({"unchecked","rawtypes"}) Object lpcm=Enum.valueOf((Class)codecClass,"LPCM");
            Object config=construct(Class.forName(AIRPLAY+"MicrophoneConfig"),"speechrecognition",16000,1,100,20,
                loopback,receiver.port(),key,lpcm,null,loopback);
            receiver.start();
            long startedNs=System.nanoTime();
            worker=construct(Class.forName(RECEIVER+"LegacyMicrophone"),config,functionLogger(),diagnostics,evidence,false);
            long deadline=startedNs+1_200_000_000L;
            boolean observedRecord=false;
            while(System.nanoTime()<deadline && !finished(evidence)) {
                observedRecord |= field(worker,"record").get(worker)!=null;
                Thread.sleep(10);
            }
            closeMicrophone(worker);closeMicrophone(worker);
            deadline=System.nanoTime()+3_000_000_000L;
            while((!finished(evidence)||microphoneThreads()>baselineThreads)&&System.nanoTime()<deadline) Thread.sleep(10);
            long elapsedNs=System.nanoTime()-startedNs;
            Thread.sleep(150);receiver.close();receiver.awaitStopped();
            require(finished(evidence),"synthetic worker terminates after repeated close");
            require(microphoneThreads()==baselineThreads,"synthetic monotonic wait unblocks on close");
            require(!observedRecord && field(worker,"record").get(worker)==null,"disabled capture never publishes AudioRecord");
            require(field(evidence,"recordState").getInt(evidence)==-1 && field(evidence,"recordingState").getInt(evidence)==-1,
                "disabled capture never opens or starts AudioRecord");
            require(longField(evidence,"readCalls")==0 && longField(evidence,"readBytes")==0 && longField(evidence,"frames")==0,
                "synthetic silence does not masquerade as real reads or captured PCM");
            require(longField(evidence,"syntheticFrames")>5,"actual synthetic worker creates paced frames");
            require(receiver.packets>5 && receiver.packets<=elapsedNs/20_000_000L+3,
                "actual UDP stream is paced rather than spinning even if the observer is descheduled");
            require(receiver.error==null,"every synthetic UDP payload authenticates and contains only zeros");
            require(receiver.samples==receiver.packets*320L,"synthetic timestamps follow negotiated sample count");
            require(longField(evidence,"errors")==0 && longField(evidence,"cleanupErrors")==0,"synthetic stream has no error or cleanup failure");
            require(field(worker,"socket").get(worker)==null,"synthetic socket ownership is cleared on exit");
            String history=snapshot(microphoneDiagnostics);
            require(history.contains("capture=false syntheticSilence=true"),"export distinguishes silence from physical microphone");
            System.out.println("SILENCEROUND capture=false audioRecordOpened=false encryptedUDP=true zeroPayload=true packets="+
                receiver.packets+" closeComplete=true hardware-mic=not-used");
        } finally {
            if(worker!=null) closeMicrophone(worker);
            receiver.close();receiver.awaitStopped();Arrays.fill(key,(byte)0);
        }
    }

    private static Round round(Object diagnostics, Object microphoneDiagnostics, String purpose, int source,
            boolean immediateClose) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        byte[] key = syntheticKey();
        Receiver receiver = new Receiver(loopback, key);
        Object microphone = null, evidence = null, observedRecord = null;
        DatagramSocket observedSocket = null;
        boolean closed = false;
        try {
            evidence = microphoneDiagnostics.getClass().getMethod("begin", String.class, int.class, int.class,
                int.class, String.class, int.class, int.class, int.class, Integer.class).invoke(microphoneDiagnostics,
                    purpose, source, 16000, 1, "LPCM", 20, 640, 320, null);
            Class<?> codecClass = Class.forName(AIRPLAY + "AudioCodecKind");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object lpcm = Enum.valueOf((Class) codecClass, "LPCM");
            Object config = construct(Class.forName(AIRPLAY + "MicrophoneConfig"), purpose, 16000, 1, 100, 20,
                loopback, receiver.port(), key, lpcm, null, loopback);
            Object logger = functionLogger();
            receiver.start();
            microphone = construct(Class.forName(RECEIVER + "LegacyMicrophone"), config, logger, diagnostics, evidence);
            long until = System.nanoTime() + 4_000_000_000L;
            long recordingUntil = -1L;
            while (!immediateClose && System.nanoTime() < until && !finished(evidence)) {
                if (longField(evidence, "recordStartedMs") >= 0 && recordingUntil < 0)
                    recordingUntil = System.nanoTime() + 1_000_000_000L;
                if (recordingUntil >= 0 && System.nanoTime() >= recordingUntil) break;
                Object record = field(microphone, "record").get(microphone);
                Object socket = field(microphone, "socket").get(microphone);
                if (record != null) observedRecord = record;
                if (socket instanceof DatagramSocket) observedSocket = (DatagramSocket) socket;
                Thread.sleep(20);
            }
            closeMicrophone(microphone);
            closeMicrophone(microphone); // Must be idempotent while the actual read may still unblock.
            closed = true;
            long deadline = System.nanoTime() + 4_000_000_000L;
            while ((!finished(evidence) || microphoneThreads() > baselineThreads) && System.nanoTime() < deadline) Thread.sleep(10);
            require(finished(evidence), "actual worker records terminal state after close");
            require(microphoneThreads() == baselineThreads, "blocking AudioRecord read exits within close deadline");
            require(field(microphone, "record").get(microphone) == null, "worker clears owned AudioRecord reference");
            require(field(microphone, "socket").get(microphone) == null, "worker clears owned DatagramSocket reference");
            if (observedSocket != null) require(observedSocket.isClosed(), "published UDP socket is actually closed");
            if (observedRecord != null) {
                int state = ((Number) observedRecord.getClass().getMethod("getState").invoke(observedRecord)).intValue();
                require(state == 0, "observed AudioRecord returns UNINITIALIZED after release");
            }
            // Drain already enqueued loopback datagrams; no additional recording or retransmission.
            Thread.sleep(150);
            receiver.close(); receiver.awaitStopped();
            require(receiver.error == null, "loopback packets authenticate and have correct RTP/counter layout");
            require(uncaught.isEmpty(), "no microphone worker uncaught failure");
            long bytes = longField(evidence, "readBytes"), positives = longField(evidence, "positiveReads");
            long zero = longField(evidence, "zeroReads"), negatives = longField(evidence, "negativeReads");
            long readExceptions = longField(evidence, "readExceptions"), frames = longField(evidence, "frames");
            long packets = longField(evidence, "packets"), errors = longField(evidence, "errors");
            long start = longField(evidence, "recordStartedMs");
            int readCode = field(evidence, "lastReadCode").getInt(evidence);
            String failurePhase = String.valueOf(field(evidence, "failurePhase").get(evidence));
            String errorType = String.valueOf(field(evidence, "errorType").get(evidence));
            require(positives + zero + negatives + readExceptions == longField(evidence, "readCalls"), "real read categories add up exactly");
            require(receiver.packets <= packets, "received packet count cannot exceed successful sender count");
            if (packets > 0) {
                require(bytes >= frames * 640L, "complete captured frames are backed by actual read bytes");
                require(positives > 0 && frames > 0 && start >= 0, "uplink requires actual initialized and recording AudioRecord");
                require(receiver.packets > 0, "successful UDP sending reaches the real loopback peer");
                require(receiver.samples == receiver.packets * 320L, "all authenticated LPCM payloads contain 320 samples");
            }
            if (errors > 0 && !isCapturePhase(failurePhase))
                throw new AssertionError("unexpected product failure outside AudioRecord environment phase=" + failurePhase + " type=" + errorType);
            String state;
            boolean observedAudio = packets > 0 && receiver.packets > 0;
            boolean captured = observedAudio && errors == 0;
            if (immediateClose) state = "CANCELLED_AND_RELEASED";
            else if (captured) state = "CAPTURE_AND_AUTHENTICATED_UDP";
            else if (observedAudio) state = "CAPTURE_INTERRUPTED_AT_" + failurePhase;
            else if (errors > 0) state = "CAPTURE_UNAVAILABLE_AT_" + failurePhase;
            else if (start >= 0 && zero > 0) state = "RECORDING_BUT_ONLY_ZERO_BYTE_READS";
            else if (start >= 0) state = "RECORDING_NO_COMPLETE_FRAME";
            else state = "CAPTURE_NOT_STARTED_WITHIN_WINDOW";
            System.out.println("MICROUND purpose=" + purpose + " immediateClose=" + immediateClose + " status=" + state +
                " recordState=" + field(evidence, "recordState").getInt(evidence) +
                " actualRate=" + field(evidence, "actualRate").getInt(evidence) +
                " actualChannels=" + field(evidence, "actualChannels").getInt(evidence) +
                " readPositive=" + positives + " readZero=" + zero + " readNegative=" + negatives +
                " readExceptions=" + readExceptions + " lastReadCode=" + readCode + " readBytes=" + bytes +
                " pcmFrames=" + frames + " udpSent=" + packets + " udpAuthenticated=" + receiver.packets +
                " errorPhase=" + failurePhase + " errorType=" + errorType +
                " cleanupErrors=" + longField(evidence, "cleanupErrors") + " closeComplete=true");
            return new Round(state, captured, observedAudio);
        } finally {
            if (microphone != null && !closed) {
                closeMicrophone(microphone);
                long deadline = System.nanoTime() + 4_000_000_000L;
                while (evidence != null && (!finished(evidence) || microphoneThreads() > baselineThreads) &&
                    System.nanoTime() < deadline) Thread.sleep(10);
            }
            receiver.close(); receiver.awaitStopped();
            Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean isCapturePhase(String phase) {
        return "RECORD_BUFFER".equals(phase) || "RECORD_OPEN".equals(phase) ||
            "RECORD_START".equals(phase) || "RECORD_READ".equals(phase);
    }

    private static final class Round {
        final String status;
        final boolean captured, observedAudio;
        Round(String status, boolean captured, boolean observedAudio) {
            this.status = status; this.captured = captured; this.observedAudio = observedAudio;
        }
    }

    private static final class Receiver implements Runnable {
        private final DatagramSocket socket;
        private final byte[] key;
        private final boolean expectSilence;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private Thread worker;
        volatile Throwable error;
        volatile long packets, samples;
        Receiver(InetAddress loopback, byte[] key) throws Exception {
            this(loopback,key,false);
        }
        Receiver(InetAddress loopback, byte[] key, boolean expectSilence) throws Exception {
            this.expectSilence=expectSilence;
            this.key = key.clone();
            socket = new DatagramSocket(new InetSocketAddress(loopback, 0)); socket.setSoTimeout(100);
        }
        int port() { return socket.getLocalPort(); }
        void start() { worker = new Thread(this, "mic-loopback-observer"); worker.setDaemon(true); worker.start(); }
        @Override public void run() {
            byte[] buffer = new byte[4096];
            try {
                Class<?> cryptoClass = Class.forName(AIRPLAY + "AirPlayCrypto");
                Object crypto = cryptoClass.getField("INSTANCE").get(null);
                Method open = cryptoClass.getMethod("chachaOpen", byte[].class, byte[].class, byte[].class, byte[].class);
                while (!stopped.get()) {
                    DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                    try { socket.receive(incoming); }
                    catch (SocketTimeoutException timeout) { continue; }
                    catch (SocketException closed) { if(stopped.get()) break; throw closed; }
                    int size = incoming.getLength();
                    if (size != 676 || (buffer[0] & 255) != 128 || (buffer[1] & 127) != 100)
                        throw new AssertionError("unexpected LPCM RTP packet layout");
                    int sequence = ((buffer[2] & 255) << 8) | (buffer[3] & 255);
                    long timestamp = unsigned32(buffer, 4);
                    long nonceCount = little64(buffer, size - 8);
                    if (sequence != (int) (packets & 65535L) || timestamp != ((packets * 320L) & 0xffff_ffffL) || nonceCount != packets)
                        throw new AssertionError("microphone RTP sequence/timestamp/nonce did not advance exactly");
                    byte[] nonce = new byte[12]; System.arraycopy(buffer, size - 8, nonce, 4, 8);
                    byte[] sealed = Arrays.copyOfRange(buffer, 12, size - 8);
                    byte[] aad = Arrays.copyOfRange(buffer, 4, 12);
                    byte[] plaintext = null;
                    try {
                        plaintext = (byte[]) open.invoke(crypto, key, nonce, sealed, aad);
                        if (plaintext.length != 640) throw new AssertionError("authenticated microphone body has incorrect sample length");
                        if(expectSilence) for(byte value:plaintext) if(value!=0) throw new AssertionError("disabled capture sent nonzero PCM");
                        packets++; samples += plaintext.length / 2;
                    } finally {
                        if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
                        Arrays.fill(nonce, (byte) 0); Arrays.fill(sealed, (byte) 0); Arrays.fill(aad, (byte) 0);
                        Arrays.fill(buffer, (byte) 0);
                    }
                }
            } catch (Throwable failure) { error = failure; }
            finally { Arrays.fill(buffer, (byte) 0); Arrays.fill(key, (byte) 0); }
        }
        void close() { if(stopped.compareAndSet(false, true)) socket.close(); }
        void awaitStopped() throws Exception {
            if(worker != null) {
                worker.join(3000);
                if(worker.isAlive()) throw new AssertionError("loopback observer did not close");
            }
        }
    }

    private static Object functionLogger() throws Exception {
        Class<?> function = Class.forName("kotlin.jvm.functions.Function1");
        return Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[] {function}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
                    if ("equals".equals(method.getName())) return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                    return "MicrophoneRuntimeMetadataDiscardingLogger";
                }
                return unit; // Product messages never enter runtime output or a retained log list.
            }
        });
    }

    private static Object construct(Class<?> type, Object... arguments) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != arguments.length || parameters.length > 0 &&
                "kotlin.jvm.internal.DefaultConstructorMarker".equals(parameters[parameters.length - 1].getName())) continue;
            constructor.setAccessible(true); return constructor.newInstance(arguments);
        }
        throw new NoSuchMethodException(type.getName() + " argument-count=" + arguments.length);
    }

    private static void closeMicrophone(Object microphone) throws Exception {
        Method close = microphone.getClass().getDeclaredMethod("close"); close.setAccessible(true); close.invoke(microphone);
    }
    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static long longField(Object owner, String name) throws Exception { return field(owner, name).getLong(owner); }
    private static boolean finished(Object evidence) throws Exception { return longField(evidence, "endedMs") >= 0; }
    private static String snapshot(Object diagnostics) throws Exception { return (String) diagnostics.getClass().getMethod("snapshot").invoke(diagnostics); }
    private static int microphoneThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet())
            if (thread.isAlive() && "legacy-microphone".equals(thread.getName())) count++;
        return count;
    }
    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        if(entries == null) throw new AssertionError("cannot inspect process fd count"); return entries.length;
    }
    private static int occurrences(String text, String value) {
        int count = 0, at = 0;
        while ((at = text.indexOf(value, at)) >= 0) { count++; at += value.length(); }
        return count;
    }
    private static long unsigned32(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) value = (value << 8) | (bytes[offset + i] & 255L);
        return value;
    }
    private static long little64(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[offset + i] & 255L);
        return value;
    }
    private static byte[] syntheticKey() {
        byte[] key = new byte[32];
        for(int i = 0; i < key.length; i++) key[i] = (byte) (56 + i * 5); return key;
    }
    private static void require(boolean condition, String message) {
        if(!condition) throw new AssertionError(message); checks++;
    }
}
