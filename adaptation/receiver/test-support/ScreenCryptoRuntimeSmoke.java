import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run using app_process with the FINAL APK on the class path and an exact extracted APK .so.
 * Reflection reaches the shipped JNI implementation, bridge and independent BC implementation;
 * no replacement product classes, identity keys, phone, media capture or hardware claims.
 * The RFC 8439 section 2.8.2 vector is a public synthetic interoperability test vector.
 * Benchmark byte counts and input sizes match for both implementations. Emulator timing is not
 * an ARM i.MX6 measurement and must not be reported as vehicle FPS or proven vehicle smoothness.
 */
public final class ScreenCryptoRuntimeSmoke {
    private static final String AIRPLAY = "com.shilapi.xcertplay.airplay.";
    private static int checks;
    private static Object bc, nativeObject;
    private static Method bcSeal, bcOpen, nativeOpen, nativeVersion;
    private static Class<?> acceleratorClass, authExceptionClass;
    private static volatile int checksum;

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "exact extracted final APK native library path is required");
        Class<?> crypto = Class.forName(AIRPLAY + "AirPlayCrypto");
        bc = crypto.getField("INSTANCE").get(null);
        bcSeal = crypto.getMethod("chachaSeal", byte[].class, byte[].class, byte[].class, byte[].class);
        bcOpen = crypto.getMethod("chachaOpen", byte[].class, byte[].class, byte[].class, byte[].class);
        acceleratorClass = Class.forName(AIRPLAY + "ScreenFrameCrypto$Accelerator");
        authExceptionClass = Class.forName(AIRPLAY + "ScreenFrameAuthenticationException");
        Class<?> nativeClass = Class.forName("local.airuize.receiver.ScreenCryptoNative");
        nativeObject = nativeClass.getField("INSTANCE").get(null);
        nativeVersion = nativeClass.getDeclaredMethod("nativeApiInfo");
        nativeVersion.setAccessible(true);
        nativeOpen = nativeClass.getDeclaredMethod("nativeOpen", byte[].class, byte[].class, byte[].class, byte[].class, byte[].class);
        nativeOpen.setAccessible(true);
        System.load(args[0]);
        require(((Number) invoke(nativeVersion, nativeObject)).intValue() == 0x0104, "actual JNI API identity 0x0104");
        Method startupSelfTest = nativeClass.getDeclaredMethod("checkSelfTest");
        startupSelfTest.setAccessible(true);
        invoke(startupSelfTest, nativeObject);
        checks++;
        rfc8439Vector();
        lengthsNoncesAndAad();
        authenticationRejectsWithoutOutput();
        nativeBoundaries();
        bridgeFallbackAndAuthentication();
        productionCounterBridge();
        benchmarks();
        System.out.println("PASS ScreenCryptoRuntimeSmoke checks=" + checks +
            " actualFinalApkJNI=true rfc8439=true independentBC=true tamperNoPlaintext=true" +
            " fallback=true screenTestScope=true checksum=" + checksum +
            " device=android-emulator vehicleFPS=not-measured phone-auth=not-attempted");
    }

    private static void rfc8439Vector() throws Exception {
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] plain = hex("4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e");
        byte[] sealed = hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691");
        require(Arrays.equals(seal(key, nonce, aad, plain), sealed), "BC seal equals published RFC 8439 vector");
        require(Arrays.equals(openBc(key, nonce, aad, sealed), plain), "BC open equals published RFC 8439 plaintext");
        byte[] out = filled(plain.length);
        require(openNative(key, nonce, aad, sealed, out) == plain.length, "native accepts published RFC 8439 vector");
        require(Arrays.equals(out, plain), "native output equals published RFC 8439 plaintext");
    }

    private static void lengthsNoncesAndAad() throws Exception {
        int[] lengths = {0, 1, 15, 16, 17, 31, 32, 63, 64, 65, 127, 128, 129, 1500, 24 * 1024, 100 * 1024, 300 * 1024};
        int[] aadLengths = {0, 1, 15, 16, 17, 128};
        Random random = new Random(0x57C4A20L);
        for (int length : lengths) for (int aadLength : aadLengths) {
            byte[] key = randomBytes(random, 32), nonce = randomBytes(random, 12);
            byte[] aad = randomBytes(random, aadLength), plain = randomBytes(random, length);
            byte[] keyBefore = key.clone(), nonceBefore = nonce.clone(), aadBefore = aad.clone();
            byte[] sealed = seal(key, nonce, aad, plain), sealedBefore = sealed.clone();
            byte[] out = filled(length);
            require(openNative(key, nonce, aad, sealed, out) == length, "native accepts boundary length " + length + "/" + aadLength);
            require(Arrays.equals(out, plain), "native equals independent BC plaintext at length " + length + "/" + aadLength);
            require(Arrays.equals(openBc(key, nonce, aad, sealed), out), "independent BC open equals actual JNI output");
            require(Arrays.equals(key, keyBefore) && Arrays.equals(nonce, nonceBefore) && Arrays.equals(aad, aadBefore) && Arrays.equals(sealed, sealedBefore),
                "native leaves authenticated input arrays unchanged");
        }
        for (long counter : new long[] {0L, 1L, 0xFFFFFFFFL, 0x100000000L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            byte[] nonce = nonce64(counter), key = randomBytes(random, 32), aad = randomBytes(random, 128), plain = randomBytes(random, 257);
            byte[] sealed = seal(key, nonce, aad, plain), out = new byte[plain.length];
            require(openNative(key, nonce, aad, sealed, out) == plain.length && Arrays.equals(out, plain), "native accepts full 64-bit nonce counter " + counter);
        }
    }

    private static void authenticationRejectsWithoutOutput() throws Exception {
        Random random = new Random(0xA17D57L);
        for (int length : new int[] {0, 1, 17, 1500, 24 * 1024, 300 * 1024}) {
            byte[] key = randomBytes(random, 32), nonce = randomBytes(random, 12), aad = randomBytes(random, 128);
            byte[] plain = randomBytes(random, length), sealed = seal(key, nonce, aad, plain);
            rejects(keyWithBit(key), nonce, aad, sealed, length, "key tamper");
            rejects(key, keyWithBit(nonce), aad, sealed, length, "nonce tamper");
            rejects(key, nonce, keyWithBit(aad), sealed, length, "AAD tamper");
            byte[] tag = sealed.clone(); tag[tag.length - 1] ^= 1;
            rejects(key, nonce, aad, tag, length, "tag tamper");
            if (length > 0) { byte[] cipher = sealed.clone(); cipher[length / 2] ^= 1; rejects(key, nonce, aad, cipher, length, "ciphertext tamper"); }
        }
    }

    private static void rejects(byte[] key, byte[] nonce, byte[] aad, byte[] sealed, int length, String label) throws Exception {
        byte[] out = filled(length), before = out.clone();
        require(openNative(key, nonce, aad, sealed, out) == -1, "native rejects " + label);
        require(Arrays.equals(out, before), "authentication failure exposes no plaintext for " + label);
        try { openBc(key, nonce, aad, sealed); throw new AssertionError("BC accepted " + label); }
        catch (Exception expected) { checks++; }
    }

    private static void nativeBoundaries() throws Exception {
        byte[] key = new byte[32], nonce = new byte[12], aad = new byte[128], plain = new byte[32];
        byte[] sealed = seal(key, nonce, aad, plain);
        invalid(null, nonce, aad, sealed, filled(32), "null key");
        invalid(key, null, aad, sealed, filled(32), "null nonce");
        invalid(key, nonce, null, sealed, filled(32), "null AAD");
        invalid(key, nonce, aad, null, filled(32), "null sealed");
        invalid(key, nonce, aad, sealed, null, "null output");
        for (int n : new int[] {0, 1, 31, 33, 64}) invalid(new byte[n], nonce, aad, sealed, filled(32), "key length " + n);
        for (int n : new int[] {0, 1, 11, 13, 24}) invalid(key, new byte[n], aad, sealed, filled(32), "nonce length " + n);
        for (int n : new int[] {0, 1, 15}) invalid(key, nonce, aad, new byte[n], filled(32), "short sealed " + n);
        invalid(key, nonce, aad, sealed, filled(31), "short output");
        invalid(key, nonce, aad, sealed, filled(33), "oversized output");
        invalid(key, nonce, new byte[1024 * 1024 + 1], sealed, filled(32), "AAD above maximum");
        invalid(key, nonce, aad, new byte[8 * 1024 * 1024 + 17], filled(8 * 1024 * 1024 + 1), "sealed above maximum");
        invalid(key, nonce, aad, sealed, key, "output aliases key");
        byte[] sealed12 = seal(key, nonce, aad, new byte[12]);
        invalid(key, nonce, aad, sealed12, nonce, "output aliases nonce");
        byte[] sealed128 = seal(key, nonce, aad, new byte[128]);
        invalid(key, nonce, aad, sealed128, aad, "output aliases AAD");
        invalid(key, nonce, aad, sealed, sealed, "output aliases sealed");
    }

    private static void invalid(byte[] key, byte[] nonce, byte[] aad, byte[] sealed, byte[] out, String label) throws Exception {
        byte[] before = out == null ? null : out.clone();
        require(openNative(key, nonce, aad, sealed, out) == -2, "invalid JNI boundary rejects " + label);
        require(out == null || Arrays.equals(out, before), "invalid JNI boundary leaves output unchanged: " + label);
    }

    private static void bridgeFallbackAndAuthentication() throws Exception {
        Random random = new Random(0xB41D6E57L);
        byte[] key = randomBytes(random, 32), nonce = randomBytes(random, 12), aad = randomBytes(random, 128), plain = randomBytes(random, 1500);
        byte[] sealed = seal(key, nonce, aad, plain);
        AtomicInteger fallback = new AtomicInteger();
        Object engine = engine(fallback);
        require(Arrays.equals(openEngine(engine, key, nonce, aad, sealed), plain), "uninstalled bridge retains BC fallback");
        require(fallback.get() == 1 && snapshot(engine).contains("fallbackFrames=1"), "fallback result is explicitly counted");
        install(engine, nativeAccelerator());
        require(Arrays.equals(openEngine(engine, key, nonce, aad, sealed), plain), "bridge routes successful frame through actual JNI");
        require(fallback.get() == 1 && snapshot(engine).contains("nativeFrames=1"), "successful JNI never calls BC fallback");
        byte[] bad = sealed.clone(); bad[bad.length - 1] ^= 1;
        try { openEngine(engine, key, nonce, aad, bad); throw new AssertionError("bridge accepted invalid tag"); }
        catch (Exception rejected) { require(authExceptionClass.isInstance(rejected), "bridge preserves authentication rejection type"); }
        require(fallback.get() == 1 && snapshot(engine).contains("authRejected=1"), "authentication rejection never falls back to BC");
        require(snapshot(engine).contains("engine=native-rfc8439"), "authentication failure does not downgrade backend");
        require(Arrays.equals(openEngine(engine, key, nonce, aad, sealed), plain), "valid frame still works after authentication rejection");
        for (final int kind : new int[] {0, 1, 2}) {
            final AtomicInteger backendCalls = new AtomicInteger();
            fallback = new AtomicInteger(); engine = engine(fallback);
            Object broken = Proxy.newProxyInstance(acceleratorClass.getClassLoader(), new Class<?>[] {acceleratorClass}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("open".equals(method.getName())) {
                        backendCalls.incrementAndGet();
                        if (kind == 0) throw new UnsatisfiedLinkError("synthetic unavailable backend");
                        if (kind == 1) throw new IllegalStateException("synthetic backend failure");
                        return new byte[1];
                    }
                    return objectMethod(proxy, method, args);
                }
            });
            install(engine, broken);
            require(Arrays.equals(openEngine(engine, key, nonce, aad, sealed), plain), "backend failure kind " + kind + " retains authenticated BC fallback");
            require(Arrays.equals(openEngine(engine, key, nonce, aad, sealed), plain), "disabled backend stays on BC fallback");
            require(backendCalls.get() == 1 && fallback.get() == 2, "backend is disabled after first implementation failure");
            require(snapshot(engine).contains("backendFailures=1") && snapshot(engine).contains("engine=bouncycastle"), "backend failure appears in metadata");
        }
        fallback = new AtomicInteger(); engine = engine(fallback);
        install(engine, nativeAccelerator());
        try { openEngine(engine, new byte[31], nonce, aad, sealed); throw new AssertionError("bridge accepted short key"); }
        catch (IllegalArgumentException expected) { checks++; }
        require(fallback.get() == 0 && snapshot(engine).contains("nativeCalls=0"), "bridge rejects invalid lengths before invoking either backend");
    }

    private static void productionCounterBridge() throws Exception {
        Class<?> singleton = Class.forName(AIRPLAY + "ScreenFrameCrypto");
        Object instance = singleton.getField("INSTANCE").get(null);
        singleton.getMethod("install", acceleratorClass).invoke(instance, nativeAccelerator());
        Method open = singleton.getMethod("open", byte[].class, long.class, byte[].class, byte[].class);
        Random random = new Random(0xC0017E57L);
        for (long counter : new long[] {0, 1, 0xFFFFFFFFL, 0x100000000L, Long.MIN_VALUE, -1}) {
            byte[] key = randomBytes(random, 32), aad = randomBytes(random, 128), plain = randomBytes(random, 24576);
            byte[] sealed = seal(key, nonce64(counter), aad, plain);
            require(Arrays.equals((byte[]) invoke(open, instance, key, counter, aad, sealed), plain), "shipped screen counter/AAD bridge equals independent BC " + counter);
        }
        String state = (String) singleton.getMethod("diagnosticSnapshot").invoke(instance);
        require(state.contains("nativeFrames=6") && state.contains("fallbackFrames=0"), "production singleton records native-only successful frames");
        System.out.println("BRIDGE " + state);
    }

    private static void benchmarks() throws Exception {
        for (int length : new int[] {25 * 1024, 100 * 1024, 300 * 1024}) {
            Random random = new Random(0xBEAC5700L + length);
            byte[] key = randomBytes(random, 32), aad = randomBytes(random, 128), plain = randomBytes(random, length);
            byte[][] nonces = new byte[4][], sealed = new byte[4][];
            for (int i = 0; i < 4; i++) { nonces[i] = nonce64(i + 0x100000000L); sealed[i] = seal(key, nonces[i], aad, plain); }
            for (int i = 0; i < 3; i++) { byte[] a = openBc(key, nonces[i], aad, sealed[i]); byte[] b = new byte[length]; require(openNative(key, nonces[i], aad, sealed[i], b) == length && Arrays.equals(a, b), "benchmark warmup byte identity"); }
            final int rounds = 12;
            long bcCpuStart = threadCpuNanos(), bcStart = System.nanoTime();
            for (int i = 0; i < rounds; i++) { byte[] out = openBc(key, nonces[i & 3], aad, sealed[i & 3]); checksum ^= out[length - 1] & 255; }
            long bcNs = System.nanoTime() - bcStart, bcCpuNs = threadCpuNanos() - bcCpuStart;
            long nativeCpuStart = threadCpuNanos(), nativeStart = System.nanoTime();
            for (int i = 0; i < rounds; i++) { byte[] out = new byte[length]; if (openNative(key, nonces[i & 3], aad, sealed[i & 3], out) != length) throw new AssertionError("benchmark native reject"); checksum ^= out[length - 1] & 255; }
            long nativeNs = System.nanoTime() - nativeStart, nativeCpuNs = threadCpuNanos() - nativeCpuStart;
            require(bcNs > 0 && nativeNs > 0, "benchmark captures positive wall intervals");
            System.out.println("BENCH ScreenCrypto size=" + length + " rounds=" + rounds + " bytesPerBackend=" + ((long) length * rounds) +
                " bcWallNs=" + bcNs + " nativeWallNs=" + nativeNs + " bcThreadCpuNs=" + bcCpuNs + " nativeThreadCpuNs=" + nativeCpuNs +
                " device=android-emulator vehicleSpeedup=not-measured");
        }
    }

    private static Object engine(final AtomicInteger fallbackCalls) throws Exception {
        final Class<?> fn = Class.forName("kotlin.jvm.functions.Function4");
        Object fallback = Proxy.newProxyInstance(fn.getClassLoader(), new Class<?>[] {fn}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("invoke".equals(method.getName())) { fallbackCalls.incrementAndGet(); return openBc((byte[]) args[0], (byte[]) args[1], (byte[]) args[2], (byte[]) args[3]); }
                return objectMethod(proxy, method, args);
            }
        });
        Constructor<?> c = Class.forName(AIRPLAY + "ScreenFrameCryptoEngine").getDeclaredConstructor(fn);
        c.setAccessible(true); return c.newInstance(fallback);
    }

    private static Object nativeAccelerator() throws Exception {
        // Use the shipped adapter so the test covers its exact native return-code mapping.
        // A Java Proxy cannot represent its checked Kotlin authentication exception faithfully.
        Class<?> adapter = Class.forName("local.airuize.receiver.ScreenCryptoNative$NativeAccelerator");
        Field instance = adapter.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        return instance.get(null);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("equals".equals(method.getName())) return proxy == args[0];
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("toString".equals(method.getName())) return "synthetic-test-proxy";
        throw new IllegalArgumentException("Unexpected proxy method " + method.getName());
    }

    private static void install(Object engine, Object backend) throws Exception { engine.getClass().getMethod("install", acceleratorClass).invoke(engine, backend); }
    private static String snapshot(Object engine) throws Exception { return (String) engine.getClass().getMethod("diagnosticSnapshot").invoke(engine); }
    private static byte[] openEngine(Object engine, byte[] key, byte[] nonce, byte[] aad, byte[] sealed) throws Exception { return (byte[]) invoke(engine.getClass().getMethod("open", byte[].class, byte[].class, byte[].class, byte[].class), engine, key, nonce, aad, sealed); }
    private static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plain) throws Exception { return (byte[]) invoke(bcSeal, bc, key, nonce, plain, aad); }
    private static byte[] openBc(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) throws Exception { return (byte[]) invoke(bcOpen, bc, key, nonce, sealed, aad); }
    private static int openNative(byte[] key, byte[] nonce, byte[] aad, byte[] sealed, byte[] out) throws Exception { return ((Number) invoke(nativeOpen, nativeObject, key, nonce, aad, sealed, out)).intValue(); }
    private static Object invoke(Method method, Object owner, Object... args) throws Exception {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException wrapped) { Throwable error = wrapped.getCause(); if (error instanceof Exception) throw (Exception) error; if (error instanceof Error) throw (Error) error; throw wrapped; }
    }
    private static byte[] randomBytes(Random random, int size) { byte[] x = new byte[size]; random.nextBytes(x); return x; }
    private static byte[] filled(int size) { byte[] x = new byte[size]; Arrays.fill(x, (byte) 0xA5); return x; }
    private static byte[] keyWithBit(byte[] input) { byte[] x = input.clone(); x[x.length / 2] ^= 1; return x; }
    private static byte[] nonce64(long counter) { byte[] n = new byte[12]; for (int i = 4; i < 12; i++) { n[i] = (byte) counter; counter >>>= 8; } return n; }
    private static byte[] hex(String text) { byte[] out = new byte[text.length() / 2]; for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16); return out; }
    private static long threadCpuNanos() throws Exception { return ((Number) Class.forName("android.os.Debug").getMethod("threadCpuTimeNanos").invoke(null)).longValue(); }
    private static void require(boolean pass, String message) { checks++; if (!pass) throw new AssertionError(message); }
}
