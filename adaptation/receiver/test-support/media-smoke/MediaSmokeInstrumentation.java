package local.airuize.diplaylegacy.mediasmoke;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import dalvik.system.DexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads unmodified production classes from the final APK and drives their actual MediaCodec
 * worker into a real Surface. All AVC data is synthetic. This checks emulator compatibility
 * and pipeline recovery; it does not measure the Freescale VPU or prove physical display FPS.
 *
 * Required asset: synthetic-720x864.h264 (Annex B, AUD, SPS/PPS, baseline AVC, 30 FPS, 8 s).
 * Run: am instrument -w [-e burst true] [-e nativeCrypto true] [-e apk /data/local/tmp/candidate.apk]
 *      package/.MediaSmokeInstrumentation
 * The readable final production APK defaults to /data/local/tmp/diplay-v055.apk.
 */
public final class MediaSmokeInstrumentation extends Instrumentation {
    private static final String DEFAULT_PRODUCT_APK = "/data/local/tmp/diplay-v055.apk";
    private static final String TAG = "DiPlayMediaSmoke";
    private static final int STREAM_TYPE = 110;
    private static final long FRAME_NS = 1000000000L / 30L;
    private final StringBuilder report = new StringBuilder();
    private final AtomicBoolean requestKeyFrame = new AtomicBoolean();
    private final AtomicLong callbackOutputs = new AtomicLong();
    private final CountDownLatch blockedRecoveryRelease = new CountDownLatch(1);
    private final AtomicReference<Thread> blockedRecoveryThread = new AtomicReference<Thread>();
    private final AtomicBoolean blockedRecoveryExited = new AtomicBoolean();
    private int checks;
    private boolean runBurst;
    private boolean nativeCrypto;
    private String nativePipelineEvidence;
    private String productApk = DEFAULT_PRODUCT_APK;
    private MediaSmokeActivity activity;
    private Object sink;
    private Object diagnostics;
    private Class<?> sinkClass;
    private Method feed;
    private Thread videoWorker;
    private int frameCursor;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        runBurst = arguments != null && "true".equals(arguments.getString("burst"));
        nativeCrypto = arguments != null && "true".equals(arguments.getString("nativeCrypto"));
        if (arguments != null && arguments.getString("apk") != null) productApk = arguments.getString("apk");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            exercise();
            status = Activity.RESULT_OK;
            line("PASS MediaSmokeInstrumentation checks=" + checks + " optionalBurst=" + runBurst + " nativeCrypto=" + nativeCrypto);
        } catch (Throwable error) {
            line("FAIL " + Log.getStackTraceString(error));
        } finally {
            blockedRecoveryRelease.countDown();
            if (sink != null) {
                try { ((Closeable) sink).close(); } catch (Throwable error) { line("CLEANUP " + error); }
            }
            if (videoWorker != null && videoWorker.isAlive()) {
                try { videoWorker.join(10000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            Thread recoveryThread = blockedRecoveryThread.get();
            if (recoveryThread != null && recoveryThread.isAlive()) {
                try { recoveryThread.join(5000); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (activity != null) {
                final Activity toFinish = activity;
                try { runOnMainSync(new Runnable() { public void run() { toFinish.finish(); } }); }
                catch (Throwable error) { line("ACTIVITY_CLEANUP " + error); }
            }
        }
        result.putString("stream", "\n" + report.toString());
        result.putInt("checks", checks);
        finish(status, result);
    }

    private void exercise() throws Exception {
        line("Synthetic AVC regression; emulator codec is not Freescale hardware evidence.");
        Intent intent = new Intent(getTargetContext(), MediaSmokeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MediaSmokeActivity) startActivitySync(intent);
        Surface surface = activity.awaitSurface();
        require(surface != null, "actual activity Surface becomes valid");

        Clip clip;
        InputStream input = getContext().getAssets().open("synthetic-720x864.h264");
        try { clip = Clip.parse(readAll(input)); } finally { input.close(); }
        require(clip.frames.size() >= 180, "synthetic fixture contains at least 180 access units");
        require(clip.firstIdr >= 0, "synthetic fixture has IDR access unit");
        line("FIXTURE accessUnits=" + clip.frames.size() + " firstIdr=" + clip.firstIdr +
            " avcCBytes=" + clip.avcC.length);

        File apk = new File(productApk);
        require(apk.isFile() && apk.canRead(), "final APK is readable at selected test path");
        line("PRODUCT_APK " + apk.getAbsolutePath());
        File optimized = getTargetContext().getDir("product-dex", 0);
        final DexClassLoader loader = new DexClassLoader(apk.getAbsolutePath(),
            optimized.getAbsolutePath(), null, getTargetContext().getClassLoader());
        sinkClass = loader.loadClass("local.airuize.receiver.LegacyMediaSink");
        require(sinkClass.getClassLoader() == loader, "MediaSink class comes from final product APK");
        // Transform the complete synthetic clip before creating the Sink or starting paced
        // playback. Test-only BC encryption must not consume the paced 30 FPS scheduling budget.
        if (nativeCrypto) clip = nativeCryptoClip(loader, apk, clip);
        Class<?> settingsClass = loader.loadClass("local.airuize.receiver.ReceiverSettings");
        Object settings = settingsClass.getConstructor().newInstance();
        Class<?> function0 = loader.loadClass("kotlin.jvm.functions.Function0");
        Class<?> function1 = loader.loadClass("kotlin.jvm.functions.Function1");
        final Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Object logger = callback(loader, function1, unit, new Callback() {
            public void invoke(Object[] args) {
                // Product log strings contain only synthetic test setup and numeric counters.
                if (args != null && args.length > 0) line("PRODUCT " + String.valueOf(args[0]));
            }
        });
        Object rendered = callback(loader, function0, unit, new Callback() {
            public void invoke(Object[] args) { callbackOutputs.incrementAndGet(); }
        });
        Constructor<?> constructor = sinkClass.getConstructor(function1, function0,
            int.class, int.class, settingsClass, android.content.Context.class);
        sink = constructor.newInstance(logger, rendered, 720, 864, settings, getTargetContext());
        diagnostics = sinkClass.getMethod("getDiagnostics").invoke(sink);
        Field worker = sinkClass.getDeclaredField("worker"); worker.setAccessible(true);
        videoWorker = (Thread) worker.get(sink);
        require(videoWorker.isAlive(), "actual legacy-avc worker is alive");
        Object recovery = callback(loader, function0, unit, new Callback() {
            public void invoke(Object[] args) { requestKeyFrame.set(true); }
        });
        sinkClass.getMethod("setVideoRecoveryHandler", int.class, function0).invoke(sink, STREAM_TYPE, recovery);
        sinkClass.getMethod("setSurface", Surface.class, boolean.class).invoke(sink, surface, false);
        Class<?> codecClass = loader.loadClass("com.shilapi.xcertplay.airplay.VideoCodec");
        Object avc = codecClass.getField("H264").get(null);
        sinkClass.getMethod("onVideoCodec", int.class, codecClass).invoke(sink, STREAM_TYPE, avc);
        sinkClass.getMethod("onVideoConfig", int.class, byte[].class).invoke(sink, STREAM_TYPE, clip.avcC);
        feed = sinkClass.getMethod("onVideoFrame", int.class, byte[].class);
        require(waitForDecoder(10000), "actual MediaCodec initializes on the Surface");
        frameCursor = clip.firstIdr;
        long before = counter("videoRendered");
        feedPaced(clip, 90);
        long halfway = counter("videoRendered");
        feedPaced(clip, 90);
        waitForProgress(halfway, 5000);
        require(counter("decoderStarts") > 0, "decoder start counter advances");
        require(halfway > before, "first paced half releases actual decoder output");
        require(counter("videoRendered") > halfway, "second paced half continues releasing output");
        require(callbackOutputs.get() > 0, "actual Surface release callback advances");
        require(counter("videoReceived") >= 180, "production receiver consumes 180 nonempty access units");
        require(counter("decoderErrors") == 0, "paced playback has no decoder exceptions");
        require(counter("videoInputTimeouts") == 0, "paced playback has no input buffer timeouts");
        require(!flag("WaitingForKeyFrame"), "paced playback finishes outside IDR recovery");
        decodedClassification("PACED", false);
        line("PACED " + summary());
        if (runBurst) {
            long dropsBefore = counter("videoDropped");
            for (int i = 0; i < 100; i++) feedNext(clip, false);
            long outputBeforeRecovery = counter("videoRendered");
            requestKeyFrame.set(true);
            feedPaced(clip, 90);
            waitForProgress(outputBeforeRecovery, 5000);
            require(counter("videoRendered") > outputBeforeRecovery, "burst is followed by decoder output progress");
            require(!flag("WaitingForKeyFrame"), "burst recovery accepts a new IDR chain");
            require(counter("decoderErrors") == 0, "burst recovery has no decoder exceptions");
            // Overflow itself is scheduling dependent; record it without assuming software speed.
            decodedClassification("BURST", false);
            line("BURST extraQueueRejects=" + (counter("videoDropped") - dropsBefore) + " " + summary());
        }
        String metrics = summary();
        require(metrics.contains("720x864"), "production diagnostic reports portrait target size");
        require(metrics.contains("视频性能") && metrics.contains("输入dequeueMaxUs=") &&
            metrics.contains("输出releaseMaxUs="), "actual pipeline timing metrics remain readable");
        Matcher decodedAge = Pattern.compile("解码\\[样本=(\\d+) 均值Us=(\\d+) 最大Us=(\\d+) 无效PTS=(\\d+)").matcher(metrics);
        Matcher submittedAge = Pattern.compile("提交\\[样本=(\\d+) 均值Us=(\\d+) 最大Us=(\\d+) 无效PTS=(\\d+)").matcher(metrics);
        require(decodedAge.find() && submittedAge.find(), "actual decoder and Surface-submit local age distributions are exported");
        long decodedAgeSamples=Long.parseLong(decodedAge.group(1)), submittedAgeSamples=Long.parseLong(submittedAge.group(1));
        require(decodedAgeSamples>0 && submittedAgeSamples>0 && decodedAgeSamples>=submittedAgeSamples,
            "real paced decoder/Surface callbacks produce distinct nonempty local age samples");
        require(Long.parseLong(decodedAge.group(4))==0 && Long.parseLong(submittedAge.group(4))==0,
            "real codec output PTS stays in the current local epoch");
        require(Long.parseLong(decodedAge.group(3))>=Long.parseLong(decodedAge.group(2)) &&
            Long.parseLong(submittedAge.group(3))>=Long.parseLong(submittedAge.group(2)),
            "recorded local age maxima bound the real means");
        require(metrics.contains("不含手机处理") && metrics.contains("不是端到端触摸延迟") &&
            metrics.contains("视频线程DISPLAY优先级请求="), "local age and best-effort scheduling are accurately labelled");

        // Simulate an event socket write which does not unblock on Thread.interrupt(). The
        // latch is released in every cleanup path, so this never leaves a permanent worker.
        final CountDownLatch recoveryEntered = new CountDownLatch(1);
        Object blockedRecovery = callback(loader, function0, unit, new Callback() {
            public void invoke(Object[] args) {
                blockedRecoveryThread.set(Thread.currentThread());
                recoveryEntered.countDown();
                boolean interrupted = false;
                while (blockedRecoveryRelease.getCount() != 0) {
                    try { blockedRecoveryRelease.await(); }
                    catch (InterruptedException error) { interrupted = true; }
                }
                blockedRecoveryExited.set(true);
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
        sinkClass.getMethod("setVideoRecoveryHandler", int.class, function0).invoke(sink, STREAM_TYPE, blockedRecovery);
        sinkClass.getMethod("setSurface", Surface.class, boolean.class).invoke(sink, surface, true);
        require(recoveryEntered.await(10, TimeUnit.SECONDS), "synthetic blocking keyframe handler starts");
        require(blockedRecoveryThread.get() != videoWorker, "network recovery runs outside legacy-avc worker");
        ((Closeable) sink).close();
        videoWorker.join(10000);
        require(!videoWorker.isAlive(), "close terminates actual legacy-avc worker");
        require(!flag("DecoderActive"), "closed decoder is inactive");
        require(!blockedRecoveryExited.get(), "video worker exits while network handler remains blocked");
        // Strict conservation is checked after joining the only output worker, so snapshots cannot
        // catch an index between dequeue and release. This counts buffers rather than BufferInfo.size:
        // an API19 Surface decoder may legitimately report size=0 for a non-EOS picture.
        decodedClassification("CLOSED", true);
        checkLateCallbacks(loader, codecClass);
        blockedRecoveryRelease.countDown();
        Thread recoveryThread = blockedRecoveryThread.get();
        recoveryThread.join(5000);
        require(!recoveryThread.isAlive(), "released recovery worker exits after sink close");
        require(counter("recoveryRequestErrors") == 0, "closed recovery interrupt is not a decoder or network failure");
        line("FINAL " + summary());
        if (nativeCrypto) line("NATIVE_SCREEN_PIPELINE PASS " + nativePipelineEvidence);
    }

    /**
     * Real installed product assets plus test-owned writable cache. The independent smoke APK
     * has a different UID, so it cannot write the product application's private files directory.
     * Only that cache directory and application-context identity are wrapped; AssetManager still
     * belongs to the installed product APK whose exact bytes are compared with the selected APK.
     */
    private Clip nativeCryptoClip(ClassLoader loader, File selectedApk, Clip original) throws Exception {
        final Context product = getTargetContext().createPackageContext("local.airuize.diplaylegacy", Context.CONTEXT_IGNORE_SECURITY);
        File installedApk = new File(product.getApplicationInfo().sourceDir);
        require(installedApk.isFile() && Arrays.equals(sha256(selectedApk), sha256(installedApk)),
            "native pipeline uses assets from the exact installed final APK");
        final File cache = getTargetContext().getDir("product-native-crypto", 0);
        Context assetContext = new ContextWrapper(product) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return cache; }
        };
        Class<?> nativeClass = loader.loadClass("local.airuize.receiver.ScreenCryptoNative");
        require(nativeClass.getClassLoader() == loader, "native loader class comes from final product APK");
        Object nativeLoader = nativeClass.getField("INSTANCE").get(null);
        boolean loaded = ((Boolean) nativeClass.getMethod("ensureLoaded", Context.class).invoke(nativeLoader, assetContext)).booleanValue();
        String loadState = (String) nativeClass.getMethod("diagnosticSnapshot").invoke(nativeLoader);
        require(loaded && loadState.contains("state=loaded") && loadState.contains("selfTest=passed"),
            "actual APK asset extraction, System.load and startup self-test pass");
        line("NATIVE_SCREEN_LOADER " + loadState);
        Class<?> bridgeClass = loader.loadClass("com.shilapi.xcertplay.airplay.ScreenFrameCrypto");
        Object bridge = bridgeClass.getField("INSTANCE").get(null);
        Method bridgeSnapshot = bridgeClass.getMethod("diagnosticSnapshot");
        String before = (String) bridgeSnapshot.invoke(bridge);
        require(metric(before, "nativeFrames") == 0 && metric(before, "nativeBytes") == 0 &&
            metric(before, "fallbackFrames") == 0 && metric(before, "authRejected") == 0 && metric(before, "backendFailures") == 0,
            "native fixture starts with an unused, successful installed backend");
        Class<?> cryptoClass = loader.loadClass("com.shilapi.xcertplay.airplay.AirPlayCrypto");
        Object bc = cryptoClass.getField("INSTANCE").get(null);
        Method seal = cryptoClass.getMethod("chachaSeal", byte[].class, byte[].class, byte[].class, byte[].class);
        Method nonce64 = cryptoClass.getMethod("nonce64", long.class);
        Class<?> screenCodecClass = loader.loadClass("com.shilapi.xcertplay.airplay.ScreenCodec");
        Object screenCodec = screenCodecClass.getField("INSTANCE").get(null);
        Method decrypt = screenCodecClass.getMethod("decryptFrame", byte[].class, long.class, byte[].class, byte[].class);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 17 + 9);
        List<AccessUnit> authenticated = new ArrayList<AccessUnit>();
        long plaintextBytes = 0;
        try {
            for (int index = 0; index < original.frames.size(); index++) {
                AccessUnit frame = original.frames.get(index);
                long counter = 0x1020304050607000L + index;
                byte[] nonce = (byte[]) nonce64.invoke(bc, Long.valueOf(counter));
                byte[] header = new byte[128];
                for (int i = 8; i < header.length; i++) header[i] = (byte) (index * 7 + i * 11);
                int bodyLength = frame.bytes.length + 16;
                for (int i = 0; i < 4; i++) header[i] = (byte) (bodyLength >>> (i * 8));
                // Opcode 0 at bytes 4..7 identifies a normal screen frame. The entire 128-byte
                // synthetic header is authenticated as AAD exactly as the production bridge does.
                byte[] sealed = (byte[]) seal.invoke(bc, key, nonce, frame.bytes, header);
                byte[] plaintext = (byte[]) decrypt.invoke(screenCodec, key, Long.valueOf(counter), header, sealed);
                if (!Arrays.equals(plaintext, frame.bytes)) throw new AssertionError("native ScreenCodec plaintext differs at synthetic frame " + index);
                authenticated.add(new AccessUnit(plaintext, frame.idr));
                plaintextBytes += plaintext.length;
            }
        } finally { Arrays.fill(key, (byte) 0); }
        require(authenticated.size() == original.frames.size(), "every synthetic Annex B access unit passes BC seal and actual native ScreenCodec byte comparison");
        String after = (String) bridgeSnapshot.invoke(bridge);
        require(metric(after, "nativeFrames") == authenticated.size() && metric(after, "nativeBytes") == plaintextBytes,
            "actual native frame and byte counters equal the complete authenticated fixture");
        require(after.contains("engine=native-rfc8439") && metric(after, "fallbackFrames") == 0 &&
            metric(after, "authRejected") == 0 && metric(after, "backendFailures") == 0,
            "whole fixture uses native authentication with no fallback, rejection or implementation failure");
        nativePipelineEvidence = "frames=" + authenticated.size() + " plaintextBytes=" + plaintextBytes +
            " exactFrameBytes=true nativeFrames=" + metric(after, "nativeFrames") + " nativeBytes=" + metric(after, "nativeBytes") +
            " fallbackFrames=0 authRejected=0 backendFailures=0 precomputedBeforePaced=true installedApkMatchesFinal=true";
        line("NATIVE_SCREEN_PIPELINE PREPARED " + nativePipelineEvidence);
        return new Clip(authenticated, original.avcC, original.firstIdr);
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream stream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384]; int read;
            while ((read = stream.read(buffer)) != -1) digest.update(buffer, 0, read);
            return digest.digest();
        } finally { stream.close(); }
    }

    private static long metric(String diagnostic, String name) {
        Matcher found = Pattern.compile("(?:^| )" + Pattern.quote(name) + "=(\\d+)(?: |$)").matcher(diagnostic);
        if (!found.find()) throw new AssertionError("missing numeric crypto metric " + name + " in " + diagnostic);
        return Long.parseLong(found.group(1));
    }

    private void decodedClassification(String phase, boolean complete) throws Exception {
        long decoded = counter("videoDecodedOutputs");
        long rendered = counter("videoRendered");
        long superseded = counter("videoSupersededOutputs");
        long recovery = counter("videoRecoveryDiscardedOutputs");
        long metadata = counter("videoMetadataOutputs");
        long cancelled = counter("videoCancelledOutputs");
        long errors = counter("videoOutputReleaseErrors");
        long classified = rendered + superseded + recovery + metadata + cancelled;
        line("DECODED_CLASSIFICATION phase=" + phase + " quiescent=" + complete +
            " decoded=" + decoded + " rendered=" + rendered + " superseded=" + superseded +
            " recovery=" + recovery + " metadata=" + metadata + " cancelled=" + cancelled +
            " releaseErrors=" + errors + " inFlightOrSamplingDelta=" + (decoded - classified));
        if (complete) {
            require(decoded > 0, "actual decoder produced classified output buffers");
            require(errors == 0, "decoded output release has no native exceptions");
            require(decoded == classified, "all acquired decoded output indices have exactly one successful release classification");
            require(rendered == callbackOutputs.get(), "rendered callback count equals successful Surface render releases");
        }
    }

    private void checkLateCallbacks(ClassLoader loader, Class<?> codecClass) throws Exception {
        Class<?> idClass = loader.loadClass("com.shilapi.xcertplay.airplay.AudioStreamId");
        Class<?> audioFormatClass = loader.loadClass("com.shilapi.xcertplay.airplay.AudioFormat");
        Class<?> microphoneClass = loader.loadClass("com.shilapi.xcertplay.airplay.MicrophoneConfig");
        Class<?> audioCodecClass = loader.loadClass("com.shilapi.xcertplay.airplay.AudioCodecKind");
        Object lpcm = audioCodecClass.getField("LPCM").get(null);
        Object id = idClass.getConstructor(int.class, String.class).newInstance(100, "media");
        Object audioFormat = audioFormatClass.getConstructor(audioCodecClass, int.class, int.class,
            int.class, String.class).newInstance(lpcm, 16000, 1, 100, "media");
        Object microphone = microphoneClass.getConstructor(String.class, int.class, int.class,
            int.class, int.class, InetAddress.class, int.class, byte[].class, audioCodecClass,
            Integer.class, InetAddress.class).newInstance("speechrecognition", 16000, 1,
                100, 20, InetAddress.getByName("127.0.0.1"), 7001, new byte[32], lpcm, null, null);
        int mediaThreads = mediaWorkerCount();
        Object configuration = field(sink, "configuration");
        Object configBefore = configuration.getClass().getMethod("getSnapshot").invoke(configuration);
        Object codecBefore = field(sink, "videoCodec");
        long receivedBefore = counter("videoReceived");
        sinkClass.getMethod("onAudioStarted", idClass, audioFormatClass, int.class).invoke(sink, id, audioFormat, 0);
        sinkClass.getMethod("onMicrophoneStarted", idClass, microphoneClass).invoke(sink, id, microphone);
        sinkClass.getMethod("onVideoConfig", int.class, byte[].class).invoke(sink, STREAM_TYPE, new byte[] {1, 2, 3});
        sinkClass.getMethod("onVideoCodec", int.class, codecClass).invoke(sink, STREAM_TYPE, codecClass.getField("H265").get(null));
        feed.invoke(sink, STREAM_TYPE, new byte[] {0, 0, 0, 1, 0x65, 0});
        require(((Map<?, ?>) field(sink, "audio")).isEmpty(), "late audio start after close creates no output stream");
        require(((Map<?, ?>) field(sink, "microphones")).isEmpty(), "late microphone start after close creates no capture stream");
        require(mediaWorkerCount() == mediaThreads, "late callbacks create no audio or microphone workers");
        require(configuration.getClass().getMethod("getSnapshot").invoke(configuration) == configBefore,
            "late video config after close leaves decoder configuration unchanged");
        require(field(sink, "videoCodec") == codecBefore, "late video codec callback leaves closed sink unchanged");
        require(counter("videoReceived") == receivedBefore, "late video frame after close is rejected");
        require(!videoWorker.isAlive(), "late media callbacks do not restart legacy-avc worker");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static int mediaWorkerCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            if (thread.isAlive() && ("legacy-audio".equals(name) || "legacy-microphone".equals(name))) count++;
        }
        return count;
    }

    private boolean waitForDecoder(long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (flag("DecoderActive")) return true;
            Thread.sleep(20);
        }
        return false;
    }
    private void waitForProgress(long previous, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline && counter("videoRendered") <= previous) Thread.sleep(20);
    }
    private void feedPaced(Clip clip, int count) throws Exception {
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            long due = started + i * FRAME_NS;
            while (System.nanoTime() < due) {
                long left = due - System.nanoTime();
                if (left > 0) Thread.sleep(left / 1000000L, (int) (left % 1000000L));
            }
            feedNext(clip, true);
        }
    }
    private void feedNext(Clip clip, boolean honorRecovery) throws Exception {
        if (honorRecovery && requestKeyFrame.getAndSet(false)) frameCursor = clip.firstIdr;
        if (frameCursor >= clip.frames.size()) frameCursor = clip.firstIdr;
        feed.invoke(sink, STREAM_TYPE, clip.frames.get(frameCursor++).bytes);
    }
    private long counter(String name) throws Exception {
        Object value = diagnostics.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)).invoke(diagnostics);
        return ((AtomicLong) value).get();
    }
    private boolean flag(String name) throws Exception {
        return ((Boolean) diagnostics.getClass().getMethod("get" + name).invoke(diagnostics)).booleanValue();
    }
    private String summary() throws Exception { return (String) sinkClass.getMethod("diagnosticSummary").invoke(sink); }
    private void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
        line("CHECK " + label);
    }
    private synchronized void line(String text) {
        Log.i(TAG, text);
        if (report.length() < 48000) report.append(text).append('\n');
    }
    private interface Callback { void invoke(Object[] args); }
    private static Object callback(ClassLoader loader, Class<?> type, final Object unit, final Callback callback) {
        return Proxy.newProxyInstance(loader, new Class<?>[] { type }, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("invoke".equals(method.getName())) { callback.invoke(args); return unit; }
                if ("toString".equals(method.getName())) return "SyntheticMediaCallback";
                if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
                if ("equals".equals(method.getName())) return Boolean.valueOf(args != null && proxy == args[0]);
                throw new UnsupportedOperationException(method.getName());
            }
        });
    }
    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (output.size() > 24 * 1024 * 1024) throw new IllegalArgumentException("synthetic fixture too large");
        }
        return output.toByteArray();
    }

    private static final class AccessUnit {
        final byte[] bytes;
        final boolean idr;
        AccessUnit(byte[] bytes, boolean idr) { this.bytes = bytes; this.idr = idr; }
    }
    private static final class Clip {
        final List<AccessUnit> frames;
        final byte[] avcC;
        final int firstIdr;
        Clip(List<AccessUnit> frames, byte[] avcC, int firstIdr) {
            this.frames = frames; this.avcC = avcC; this.firstIdr = firstIdr;
        }
        static Clip parse(byte[] data) throws Exception {
            List<AccessUnit> frames = new ArrayList<AccessUnit>();
            byte[] sps = null, pps = null;
            ByteArrayOutputStream access = new ByteArrayOutputStream();
            boolean hasVcl = false, idr = false, sawAud = false;
            int start = findStart(data, 0);
            if (start < 0) throw new IllegalArgumentException("fixture is not Annex B");
            while (start >= 0) {
                int prefix = prefix(data, start);
                int body = start + prefix;
                int next = findStart(data, body);
                int end = next < 0 ? data.length : next;
                if (end > body) {
                    int type = data[body] & 31;
                    if (type == 9) {
                        sawAud = true;
                        if (hasVcl) frames.add(new AccessUnit(access.toByteArray(), idr));
                        access.reset(); hasVcl = false; idr = false;
                    }
                    if (type == 7 && sps == null) sps = copy(data, body, end);
                    if (type == 8 && pps == null) pps = copy(data, body, end);
                    access.write(new byte[] {0, 0, 0, 1});
                    access.write(data, body, end - body);
                    if (type == 1 || type == 5) hasVcl = true;
                    if (type == 5) idr = true;
                }
                start = next;
            }
            if (hasVcl) frames.add(new AccessUnit(access.toByteArray(), idr));
            if (!sawAud || sps == null || sps.length < 4 || pps == null ||
                sps.length > 65535 || pps.length > 65535) throw new IllegalArgumentException("AUD/SPS/PPS missing or invalid");
            ByteArrayOutputStream config = new ByteArrayOutputStream();
            config.write(new byte[] {1, sps[1], sps[2], sps[3], (byte) 255, (byte) 225});
            writeU16(config, sps.length); config.write(sps); config.write(1);
            writeU16(config, pps.length); config.write(pps);
            int firstIdr = -1;
            for (int i = 0; i < frames.size(); i++) if (frames.get(i).idr) { firstIdr = i; break; }
            return new Clip(frames, config.toByteArray(), firstIdr);
        }
        private static int findStart(byte[] data, int from) {
            for (int i = from; i + 2 < data.length; i++) if (prefix(data, i) != 0) return i;
            return -1;
        }
        private static int prefix(byte[] data, int offset) {
            if (offset + 2 >= data.length || data[offset] != 0 || data[offset + 1] != 0) return 0;
            if (data[offset + 2] == 1) return 3;
            return offset + 3 < data.length && data[offset + 2] == 0 && data[offset + 3] == 1 ? 4 : 0;
        }
        private static byte[] copy(byte[] data, int from, int until) {
            byte[] value = new byte[until - from]; System.arraycopy(data, from, value, 0, value.length); return value;
        }
        private static void writeU16(ByteArrayOutputStream output, int value) {
            output.write(value >>> 8); output.write(value & 255);
        }
    }
}
