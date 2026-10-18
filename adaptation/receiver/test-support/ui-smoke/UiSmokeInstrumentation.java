package local.airuize.diplaylegacy.uismoke;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the installed production ReceiverActivity on API 18+. No product test hooks, coordinates,
 * USB connection, or permission changes. The fullscreen arrangement is synthetic: it exercises
 * the real menu/back widgets without claiming a CarPlay session or remote touch delivery.
 *
 * Sign with the same certificate as the product APK. Run:
 * am instrument -w [-e cycles 30] local.airuize.diplaylegacy.uismoke/.UiSmokeInstrumentation
 * After an abnormal process death, always run the same command with -e restoreOnly true.
 * A valid result requires PASS, successful preference restoration, and no process crash/restart.
 * Operation-body/dispatch-wait times are main-thread observations, never display or CarPlay FPS.
 */
public final class UiSmokeInstrumentation extends Instrumentation {
    private static final String PACKAGE = "local.airuize.diplaylegacy";
    private static final String ACTIVITY = "local.airuize.receiver.ReceiverActivity";
    private static final String STEERING = "local.airuize.receiver.SteeringVoiceService";
    private static final String WIRED = "local.airuize.receiver.LegacyWiredService";
    private static final String TAG = "DiPlayUiSmoke";
    private static final String[] PREF_KEYS = {
        "displayMode", "heroColor", "steeringVoice", "steeringMedia", "microphonePcm", "vehicleGps"
    };
    private static final String[] COLORS = {"gray", "black", "white", "red", "green", "brown", "blue"};
    private static final String[] COLOR_LABELS = {"墨云灰", "幻影黑", "极地白", "烈焰红", "苍松绿", "乌木棕", "行星蓝"};

    private final StringBuilder report = new StringBuilder();
    private final AtomicReference<Throwable> uncaught = new AtomicReference<Throwable>();
    private final List<Long> bodyNanos = new ArrayList<Long>();
    private final List<Long> dispatchNanos = new ArrayList<Long>();
    private final Map<String, Long> maximumByOperation = new HashMap<String, Long>();
    private int cycles = 30;
    private boolean restoreOnly;
    private int checks;
    private int initialPid;
    private int destroyedActivities;
    private Activity activity;
    private SharedPreferences prefs;
    private Map<String, ?> originalPrefs;
    private boolean prefsChanged;
    private boolean originalSteeringRunning;
    private boolean capturedServiceState;
    private Thread.UncaughtExceptionHandler previousHandler;
    private Thread.UncaughtExceptionHandler observingHandler;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.getString("cycles") != null) {
            cycles = Integer.parseInt(arguments.getString("cycles"));
        }
        restoreOnly = arguments != null && "true".equals(arguments.getString("restoreOnly"));
        initialPid = Process.myPid();
        start();
    }

    @Override public boolean onException(Object object, Throwable error) {
        uncaught.compareAndSet(null, error);
        return false; // Framework must report an actual crashing target process as failure.
    }

    @Override public void onStart() {
        boolean passed = false;
        try {
            if (restoreOnly) loadRestorationBackup(); else exercise();
            passed = true;
        } catch (Throwable error) {
            line("FAIL " + Log.getStackTraceString(error));
        } finally {
            try { closeActivity(); }
            catch (Throwable error) { passed = false; line("FAIL activity cleanup " + Log.getStackTraceString(error)); }
            try { restorePreferencesAndService(); }
            catch (Throwable error) { passed = false; line("FAIL preference/service restoration " + Log.getStackTraceString(error)); }
            if (observingHandler != null && Thread.getDefaultUncaughtExceptionHandler() == observingHandler) {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }
        if (uncaught.get() != null) {
            passed = false;
            line("FAIL uncaught target exception " + Log.getStackTraceString(uncaught.get()));
        }
        timings();
        if (passed) {
            if (restoreOnly) line("PASS UiSmokeRestoreOnly checks=" + checks);
            else line("PASS UiSmokeInstrumentation cycles=" + cycles + " checks=" + checks +
                " destroyedActivities=" + destroyedActivities + " pid=" + initialPid);
        }
        Bundle result = new Bundle();
        result.putString("stream", "\n" + report.toString());
        result.putInt("checks", checks);
        result.putInt("cycles", cycles);
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void exercise() throws Exception {
        require(cycles >= 30 && cycles <= 200, "cycle count is bounded and at least 30");
        require(!serviceRunning(WIRED), "no wired service/session is disturbed by the UI test");
        originalSteeringRunning = serviceRunning(STEERING);
        capturedServiceState = true;
        prefs = getTargetContext().getSharedPreferences("receiver-ui", Context.MODE_PRIVATE);
        originalPrefs = new HashMap<String, Object>(prefs.getAll());
        require(!backupFile().exists(), "no unfinished UI test backup; use restoreOnly before a new run");
        writeRestorationBackup();
        // Every settings save starts/stops this service. Suppress it only during the test, then
        // restore both its exact original preference and its observed original running state.
        prefsChanged = true;
        require(prefs.edit().putBoolean("steeringVoice", false).commit(), "temporary test preference committed");
        if (originalSteeringRunning) getTargetContext().stopService(new Intent().setComponent(new ComponentName(PACKAGE, STEERING)));
        openActivity();
        installExceptionObserver();
        line("Actual installed activity, text/tree clicks, 30+ repeated cycles; no USB session.");
        line("Main operation-body and dispatch-wait timings are not display FPS or a hardware benchmark.");

        for (int cycle = 0; cycle < cycles; cycle++) {
            checkProcess();
            main("settings-open", new MainAction() { public void run() throws Exception { clickText("设置", false); } });
            main("settings-cancel", new MainAction() { public void run() throws Exception { clickText("关闭", false); } });
            main("dashboard-scroll", new MainAction() { public void run() throws Exception {
                ScrollView scroll = (ScrollView) field(activity, "dashScroll");
                scroll.scrollTo(0, Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight()));
                scroll.scrollTo(0, 0);
            } });
            main("fullscreen-menu", new MainAction() { public void run() throws Exception {
                invoke(activity, "enterFullscreen");
                require(((View) field(activity, "fab")).getVisibility() == View.VISIBLE, "synthetic fullscreen has real menu");
                clickText("菜单", false);
                require(((View) field(activity, "dashScroll")).getVisibility() == View.VISIBLE, "menu returns to dashboard");
            } });
            main("fullscreen-back", new MainAction() { public void run() throws Exception {
                invoke(activity, "enterFullscreen");
                activity.onBackPressed();
                require(((View) field(activity, "dashScroll")).getVisibility() == View.VISIBLE, "fullscreen back returns to dashboard");
            } });

            int finalColor = -1;
            for (int step = 0; step < 3; step++) {
                final int color = (cycle * 3 + step + 1) % COLORS.length;
                finalColor = color;
                main("color-settings-open", new MainAction() { public void run() throws Exception { clickText("设置", false); } });
                main("color-picker-open", new MainAction() { public void run() throws Exception { clickText("外观", false); } });
                main("color-choice", new MainAction() { public void run() throws Exception { clickText(COLOR_LABELS[color], false); } });
                main("color-save", new MainAction() { public void run() throws Exception { clickText("关闭", false); } });
                require(COLORS[color].equals(prefs.getString("heroColor", "")), "chosen color preference saved");
            }
            awaitDisplayedColor(COLORS[finalColor]);

            if ((cycle + 1) % 5 == 0) {
                backgroundAndResume();
                line("CYCLE " + (cycle + 1) + " " + uiDiagnostic());
                recreateActivity(null, "periodic-cycle-" + (cycle + 1));
            }
        }
        checkProcess();
        require(!serviceRunning(WIRED), "UI operations never started the wired service");
        gpsStatusEnablesOnlyTheApplicationPreference();
        delayedUsbIntentDoesNotOutliveActivity();
        settingsDialogIsDisposedWithActivity();
    }

    private void gpsStatusEnablesOnlyTheApplicationPreference() throws Exception {
        final LocationManager location = (LocationManager) getTargetContext().getSystemService(Context.LOCATION_SERVICE);
        require(location != null && location.getAllProviders().contains(LocationManager.GPS_PROVIDER),
            "system GPS state is readable for the UI invariance check");
        final boolean systemGpsBefore = location.isProviderEnabled(LocationManager.GPS_PROVIDER);
        // Backed up before any test mutation. Force the explicit opt-in path even if the
        // original installation was already enabled; finally restores presence and value.
        require(prefs.edit().putBoolean("vehicleGps", false).commit(), "temporary GPS opt-in fixture committed");
        main("gps-status-open-disabled", new MainAction() { public void run() throws Exception {
            invoke(activity,"showGpsStatusDialog");
        } });
        awaitGpsStatusDialog(false, 5000);
        main("gps-status-disabled-content", new MainAction() { public void run() throws Exception {
            require(findText("车机GPS状态", false) != null, "actual GPS status dialog opens");
            String message = gpsDialogMessage();
            require(message.contains("应用GPS开关：已关闭"), "GPS status faithfully reports the disabled application preference");
            require(message.contains("尚无连接配置") && message.contains("连接后可在这里查看手机订阅和发送情况"),
                "unconnected GPS status asks for a connection rather than inventing usage");
            require(!message.contains("实际发送：") && !message.contains("手机定位订阅：") &&
                !message.contains("累计发送：") && !message.contains("手机曾订阅："),
                "unconnected GPS dialog does not claim an active subscription or send count");
            require(!prefs.getBoolean("vehicleGps", true), "opening the GPS dialog does not silently enable location forwarding");
        } });
        main("gps-status-explicit-enable", new MainAction() { public void run() throws Exception {
            clickText("开启车机GPS", false);
        } });
        awaitGpsStatusDialog(true, 5000);
        main("gps-status-enabled-result", new MainAction() { public void run() throws Exception {
            require(prefs.getBoolean("vehicleGps", false), "explicit enable button updates only the application GPS preference");
            require(findText("车机GPS状态", false) != null && gpsDialogMessage().contains("应用GPS开关：已开启"),
                "enable button refreshes the real dialog to the enabled state");
            require(findText("开启车机GPS", false) == null, "enabled GPS status no longer offers another enable action");
            require(location.isProviderEnabled(LocationManager.GPS_PROVIDER) == systemGpsBefore,
                "explicit application opt-in leaves the system GPS provider unchanged");
            clickText("关闭", false);
        } });
        awaitGpsStatusDismissed(5000);
        main("gps-status-dismissed", new MainAction() { public void run() throws Exception {
            require(findText("车机GPS状态", false) == null, "GPS status dialog dismisses normally");
            require(((java.util.Collection<?>) field(activity, "managedDialogs")).isEmpty(),
                "dismissed GPS status leaves no managed dialog behind");
        } });
        final Activity closing = activity;
        main("gps-status-reopen-before-destroy", new MainAction() { public void run() throws Exception {
            invoke(activity,"showGpsStatusDialog");
        } });
        awaitGpsStatusDialog(true, 5000);
        main("gps-status-managed-before-destroy", new MainAction() { public void run() throws Exception {
            require(gpsDialogMessage().contains("应用GPS开关：已开启"), "next GPS window reads the explicitly enabled preference");
            require(!((java.util.Collection<?>) field(closing, "managedDialogs")).isEmpty(), "GPS status is managed before system recreation");
        } });
        recreateActivity(null, "gps-status-dialog-open");
        main("gps-status-destroyed", new MainAction() { public void run() throws Exception {
            require(booleanField(closing, "destroyed") && ((java.util.Collection<?>) field(closing, "managedDialogs")).isEmpty(),
                "real Activity destruction disposes its open GPS dialog");
            require(location.isProviderEnabled(LocationManager.GPS_PROVIDER) == systemGpsBefore,
                "GPS status opt-in, dismiss and destruction never changed the system provider");
            require(!serviceRunning(WIRED), "GPS status UI does not start a wired session automatically");
        } });
        line("GPS_STATUS PASS actualDialog=true explicitAppPreferenceEnable=true systemProviderUnchanged=true " +
            "unconnectedDoesNotClaimSent=true dismissAndDestroyClean=true noPhoneLocationUseClaim=true");
    }

    private String gpsDialogMessage() throws Exception {
        View message = findText("应用GPS开关：", true);
        require(message instanceof TextView, "GPS dialog has the actual product status message");
        return ((TextView) message).getText().toString();
    }

    /** Dialog.show attaches a window synchronously, but visible tree state needs a later UI
     * traversal. Observe from separate main turns; never wait while blocking the UI thread. */
    private void awaitGpsStatusDialog(final boolean enabled, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] ready = new boolean[1];
            main("gps-status-visible-observe", new MainAction() { public void run() throws Exception {
                View title = findText("车机GPS状态", false);
                View message = findText("应用GPS开关：", true);
                ready[0] = title != null && message instanceof TextView &&
                    ((TextView) message).getText().toString().contains("应用GPS开关：" + (enabled ? "已开启" : "已关闭"));
            } });
            if (ready[0]) { require(true, "GPS status dialog becomes visible with expected preference state"); return; }
            SystemClock.sleep(20);
        }
        traceLifecycle(activity, "gps-status-visible-timeout enabled=" + enabled);
        throw new AssertionError("GPS status dialog did not become visible with expected application preference");
    }

    private void awaitGpsStatusDismissed(long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] absent = new boolean[1];
            main("gps-status-dismiss-observe", new MainAction() { public void run() throws Exception {
                absent[0] = findText("车机GPS状态", false) == null &&
                    ((java.util.Collection<?>) field(activity, "managedDialogs")).isEmpty();
            } });
            if (absent[0]) { require(true, "GPS dialog and managed registry finish normal dismissal"); return; }
            SystemClock.sleep(20);
        }
        throw new AssertionError("GPS status dialog did not complete dismissal");
    }

    private void settingsDialogIsDisposedWithActivity() throws Exception {
        if (activity == null) openActivity();
        final Activity closing = activity;
        main("settings-open-before-destroy", new MainAction() { public void run() throws Exception {
            clickText("设置", false);
            require(!((java.util.Collection<?>) field(closing, "managedDialogs")).isEmpty(), "open settings dialog is managed");
        } });
        recreateActivity(null, "settings-dialog-open");
        main("managed-dialog-after-destroy", new MainAction() { public void run() throws Exception {
            require(booleanField(closing, "destroyed"), "dialog disposal checked after onDestroy");
            require(((java.util.Collection<?>) field(closing, "managedDialogs")).isEmpty(), "destroyed activity retains no managed dialogs");
        } });
        line("MANAGED_DIALOG_DESTROY PASS settings dialog open at system recreate; old managed registry empty after real onDestroy.");
    }

    private void delayedUsbIntentDoesNotOutliveActivity() throws Exception {
        if (activity == null) openActivity();
        try {
            // Schedule and request system recreation in the same main-thread turn. An idle wait
            // these actions would let a slow emulator accidentally consume the 600 ms window.
            recreateActivity(new MainAction() { public void run() throws Exception {
                Method incoming = activity.getClass().getDeclaredMethod("onNewIntent", Intent.class);
                incoming.setAccessible(true);
                incoming.invoke(activity, new Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"));
                require(Intent.ACTION_MAIN.equals(activity.getIntent().getAction()), "USB attach intent consumed before delayed start");
            } }, "usb-delayed-intent");
            SystemClock.sleep(900);
            checkProcess();
            require(!serviceRunning(WIRED), "destroyed activity cancels delayed USB auto-connect");
            line("USB_DELAYED_INTENT PASS old instance really destroyed by system recreate; replacement intent MAIN; no wired service after 900ms.");
        } finally {
            // A failed regression must not leave the test-started service running. The original
            // exercise precondition required that this service was absent before any UI action.
            if (serviceRunning(WIRED)) getTargetContext().stopService(new Intent().setComponent(new ComponentName(PACKAGE, WIRED)));
        }
    }

    private void installExceptionObserver() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        observingHandler = new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                uncaught.compareAndSet(null, error);
                if (previousHandler != null) previousHandler.uncaughtException(thread, error);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(observingHandler);
    }

    private void openActivity() throws Exception {
        Intent intent = productIntent();
        activity = startActivitySync(intent);
        waitForIdleSync();
        checkActivityReady();
    }

    private void checkActivityReady() throws Exception {
        require(activity != null && ACTIVITY.equals(activity.getClass().getName()), "actual ReceiverActivity launched");
        awaitFlag("resumed", true, 5000);
        awaitStableWindowFocus(activity, true, 5000);
        main("activity-ready", new MainAction() { public void run() throws Exception {
            require(!activity.isFinishing() && !booleanField(activity, "destroyed"), "activity is usable");
            require(field(activity, "touchThread") instanceof Thread, "actual touch worker is present");
            require(((Thread) field(activity, "touchThread")).isAlive(), "actual touch worker is alive");
            require(findText("设置", false) != null, "dashboard settings control exists");
        } });
    }

    private Intent productIntent() {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PACKAGE, ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void backgroundAndResume() throws Exception {
        main("background", new MainAction() { public void run() throws Exception {
            require(activity.moveTaskToBack(true), "activity task moved to background");
        } });
        awaitFlag("resumed", false, 5000);
        awaitStableWindowFocus(activity, false, 5000);
        getTargetContext().startActivity(productIntent().addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        awaitFlag("resumed", true, 5000);
        awaitStableWindowFocus(activity, true, 5000);
        waitForIdleSync();
        main("resume-check", new MainAction() { public void run() throws Exception {
            require(!booleanField(activity, "destroyed"), "same activity resumes without destruction");
            require(findText("设置", false) != null, "dashboard is usable after resume");
        } });
    }

    private void closeActivity() throws Exception {
        if (activity == null) return;
        final Activity closing = activity;
        traceLifecycle(closing, "cleanup-before-ordinary-finish");
        main("activity-finish", new MainAction() { public void run() { if (!closing.isFinishing()) closing.finish(); } });
        SystemClock.sleep(350);
        traceLifecycle(closing, "cleanup-after-ordinary-finish");
        line("CLEANUP ordinary finish requested; immediate frameworkDestroyed=" + closing.isDestroyed() +
            "; cleanup finish is not counted as a resource-destruction check.");
        activity = null;
        waitForIdleSync();
    }

    /** Request the real platform relaunch path. A monitor captures its new activity instance;
     * never invoke lifecycle callbacks directly or count a merely finishing instance as destroyed. */
    private void recreateActivity(final MainAction beforeRecreate, final String reason) throws Exception {
        final Activity closing = activity;
        require(closing != null && !closing.isFinishing(), "recreate starts with a live activity");
        final Thread touchWorker = (Thread) field(closing, "touchThread");
        final ExecutorService artwork = (ExecutorService) field(closing, "artworkWorker");
        final ActivityMonitor monitor = addMonitor(ACTIVITY, null, false);
        traceLifecycle(closing, "before-recreate " + reason);
        try {
            main("activity-system-recreate", new MainAction() { public void run() throws Exception {
                if (beforeRecreate != null) beforeRecreate.run();
                closing.recreate();
            } });
            Activity replacement = waitForMonitorWithTimeout(monitor, 15000);
            require(replacement != null && replacement != closing, "system recreate yields a different actual activity instance");
            activity = replacement;
            awaitDestroyedResources(closing, touchWorker, artwork);
            checkActivityReady();
            traceLifecycle(activity, "replacement-ready " + reason);
        } finally { removeMonitor(monitor); }
    }

    private void awaitDestroyedResources(final Activity closing, Thread touchWorker, ExecutorService artwork) throws Exception {
        long began = SystemClock.elapsedRealtime();
        long deadline = began + 15000;
        boolean tracedDelay = false;
        while ((!booleanField(closing, "destroyed") || !closing.isDestroyed()) && SystemClock.elapsedRealtime() < deadline) {
            if (!tracedDelay && SystemClock.elapsedRealtime() - began >= 2000) {
                traceLifecycle(closing, "destroy-wait-2000ms");
                tracedDelay = true;
            }
            SystemClock.sleep(20);
        }
        traceLifecycle(closing, "after-destroy-wait");
        require(booleanField(closing, "destroyed") && closing.isDestroyed(), "onDestroy completes within 15000ms (product and framework flags)");
        if (touchWorker != null) touchWorker.join(5000);
        require(touchWorker != null && !touchWorker.isAlive(), "destroyed activity touch worker exits");
        require(artwork.isShutdown(), "destroyed activity stops artwork executor");
        require(artwork.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS), "destroyed artwork worker exits");
        destroyedActivities++;
        waitForIdleSync();
    }

    /** Resume and window focus are independent callbacks. Allow the actual task/window transition
     * to settle before another transition; this is test pacing, not a production lifecycle hook. */
    private void awaitStableWindowFocus(final Activity target, final boolean expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        long stableSince = -1;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] focused = new boolean[1];
            main("window-focus-observe", new MainAction() { public void run() { focused[0] = target.hasWindowFocus(); } });
            long now = SystemClock.elapsedRealtime();
            if (focused[0] == expected) {
                if (stableSince < 0) stableSince = now;
                if (now - stableSince >= 350) { require(true, "window focus stable for task transition"); return; }
            } else stableSince = -1;
            SystemClock.sleep(50);
        }
        traceLifecycle(target, "window-focus-timeout expected=" + expected);
        throw new AssertionError("window focus did not stabilize at " + expected);
    }

    private void traceLifecycle(final Activity target, final String label) {
        final String[] snapshot = new String[1];
        try {
            runOnMainSync(new Runnable() { public void run() {
                StringBuilder text = new StringBuilder("LIFECYCLE ").append(label)
                    .append(" activityIdentity=").append(Integer.toHexString(System.identityHashCode(target)))
                    .append(" finishing=").append(target.isFinishing()).append(" frameworkDestroyed=").append(target.isDestroyed())
                    .append(" focus=").append(target.hasWindowFocus());
                for (String name : new String[]{"destroyed", "resumed", "managedDialogs"}) {
                    try {
                        Object value = field(target, name);
                        text.append(' ').append(name).append('=');
                        if (value instanceof java.util.Collection<?>) text.append(((java.util.Collection<?>) value).size());
                        else text.append(String.valueOf(value));
                    } catch (Throwable error) { text.append(' ').append(name).append("=unavailable"); }
                }
                try {
                    Object token = fieldInHierarchy(target, "mToken");
                    text.append(" tokenIdentity=").append(Integer.toHexString(System.identityHashCode(token)));
                    Class<?> threadClass = Class.forName("android.app.ActivityThread");
                    Method current = threadClass.getDeclaredMethod("currentActivityThread"); current.setAccessible(true);
                    Object thread = current.invoke(null);
                    Object records = fieldInHierarchy(thread, "mActivities");
                    if (records instanceof Map<?, ?>) {
                        Map<?, ?> map = (Map<?, ?>) records;
                        text.append(" activityRecords=").append(map.size()).append(" containsToken=").append(map.containsKey(token));
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            Object record = entry.getValue();
                            Object owner = fieldInHierarchy(record, "activity");
                            text.append(" [recordActivity=").append(owner == null ? "null" : owner.getClass().getSimpleName())
                                .append('@').append(Integer.toHexString(System.identityHashCode(owner)))
                                .append(" target=").append(owner == target)
                                .append(" tokenMatch=").append(entry.getKey() == token);
                            for (String name : new String[]{"paused", "stopped", "hideForNow"}) {
                                try { text.append(' ').append(name).append('=').append(fieldInHierarchy(record, name)); }
                                catch (Throwable ignored) { text.append(' ').append(name).append("=unavailable"); }
                            }
                            text.append(']');
                        }
                    }
                } catch (Throwable error) { text.append(" recordsUnavailable=").append(error.getClass().getSimpleName()); }
                snapshot[0] = text.toString();
            } });
            line(snapshot[0]);
        } catch (Throwable error) { line("LIFECYCLE " + label + " unavailable=" + error.getClass().getSimpleName()); }
    }

    private static Object fieldInHierarchy(Object owner, String name) throws Exception {
        Class<?> type = owner.getClass();
        while (type != null) {
            try { Field item = type.getDeclaredField(name); item.setAccessible(true); return item.get(owner); }
            catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private void awaitFlag(final String name, final boolean expected, long timeoutMs) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] current = new boolean[1];
            main("lifecycle-observe", new MainAction() { public void run() throws Exception { current[0] = booleanField(activity, name); } });
            if (current[0] == expected) { require(true, name + " reached expected lifecycle state"); return; }
            SystemClock.sleep(20);
        }
        throw new AssertionError(name + " did not become " + expected);
    }

    private void awaitDisplayedColor(final String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] matched = new boolean[1];
            main("artwork-observe", new MainAction() { public void run() throws Exception {
                Drawable drawable = ((ImageView) field(activity, "hero")).getDrawable();
                matched[0] = expected.equals(field(activity, "displayedColor")) && drawable != null;
            } });
            if (matched[0]) { require(true, "latest asynchronous artwork wins"); return; }
            SystemClock.sleep(20);
        }
        throw new AssertionError("latest artwork failed to appear: " + expected);
    }

    private String uiDiagnostic() throws Exception {
        final String[] value = new String[1];
        main("diagnostic-read", new MainAction() { public void run() throws Exception {
            Field diagnostic = activity.getClass().getDeclaredField("interactionDiagnostic");
            diagnostic.setAccessible(true);
            value[0] = String.valueOf(diagnostic.get(null));
            require(value[0].contains("samples=") && value[0].contains("touchQueue"), "production UI/touch diagnostics available");
        } });
        return value[0];
    }

    /** Discover dialogs in this target process; API 18/19 mViews may be a List or an array. */
    private List<View> windowRoots() throws Exception {
        Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
        Method instanceMethod = globalClass.getDeclaredMethod("getInstance");
        instanceMethod.setAccessible(true);
        Object global = instanceMethod.invoke(null);
        Field viewsField = globalClass.getDeclaredField("mViews");
        viewsField.setAccessible(true);
        Object roots = viewsField.get(global);
        List<View> result = new ArrayList<View>();
        if (roots instanceof Iterable<?>) {
            for (Object root : (Iterable<?>) roots) if (root instanceof View) result.add((View) root);
        } else if (roots != null && roots.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(roots); i++) {
                Object root = Array.get(roots, i);
                if (root instanceof View) result.add((View) root);
            }
        }
        return result;
    }

    private View findText(String text, boolean prefix) throws Exception {
        List<View> roots = windowRoots();
        for (int i = roots.size() - 1; i >= 0; i--) {
            View found = findText(roots.get(i), text, prefix);
            if (found != null) return found;
        }
        return null;
    }

    private View findText(View view, String text, boolean prefix) {
        if (view.getWindowVisibility() != View.VISIBLE || !view.isShown()) return null;
        if (view instanceof TextView) {
            String actual = ((TextView) view).getText().toString();
            if (prefix ? actual.startsWith(text) : actual.equals(text)) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void clickText(String text, boolean prefix) throws Exception {
        View found = findText(text, prefix);
        require(found != null, "text/tree target exists: " + text);
        found.requestRectangleOnScreen(new android.graphics.Rect(0, 0, found.getWidth(), found.getHeight()), true);
        View candidate = found;
        for (ViewParent parent = candidate.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent instanceof AdapterView<?>) {
                AdapterView<?> adapter = (AdapterView<?>) parent;
                int position = adapter.getPositionForView(found);
                require(position != AdapterView.INVALID_POSITION, "choice position exists: " + text);
                require(adapter.performItemClick(candidate, position, adapter.getItemIdAtPosition(position)), "choice clicked: " + text);
                return;
            }
            if (candidate.isClickable()) break;
            candidate = (View) parent;
        }
        require(candidate.isEnabled() && candidate.performClick(), "control clicked: " + text);
    }

    private void restorePreferencesAndService() throws Exception {
        if (prefsChanged && prefs != null && originalPrefs != null) {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : PREF_KEYS) {
                if (!originalPrefs.containsKey(key)) editor.remove(key);
                else {
                    Object value = originalPrefs.get(key);
                    if (value instanceof Boolean) editor.putBoolean(key, ((Boolean) value).booleanValue());
                    else if (value instanceof String) editor.putString(key, (String) value);
                    else throw new AssertionError("unexpected original preference type: " + key);
                }
            }
            require(editor.commit(), "original modified preferences restored");
            Map<String, ?> restored = prefs.getAll();
            for (String key : PREF_KEYS) {
                require(originalPrefs.containsKey(key) == restored.containsKey(key), "original preference presence restored: " + key);
                if (originalPrefs.containsKey(key)) require(originalPrefs.get(key).equals(restored.get(key)), "original preference value restored: " + key);
            }
            line("RESTORE display/color/steering/microphone/GPS preferences exact; steeringMedia included; no clear-data or permission changes.");
        }
        if (capturedServiceState) {
            Intent steering = new Intent().setComponent(new ComponentName(PACKAGE, STEERING));
            if (originalSteeringRunning) getTargetContext().startService(steering);
            else getTargetContext().stopService(steering);
            line("RESTORE steering service originalRunning=" + originalSteeringRunning);
        }
        if (prefsChanged) require(!backupFile().exists() || backupFile().delete(), "completed test backup removed");
    }

    private File backupFile() {
        return new File(getTargetContext().getCacheDir(), "ui-smoke-original-preferences.properties");
    }

    private void writeRestorationBackup() throws Exception {
        Properties values = new Properties();
        values.setProperty("format", "2");
        values.setProperty("steeringServiceRunning", String.valueOf(originalSteeringRunning));
        for (String key : PREF_KEYS) {
            Object value = originalPrefs.get(key);
            values.setProperty(key + ".present", String.valueOf(originalPrefs.containsKey(key)));
            if (originalPrefs.containsKey(key)) {
                require(value instanceof Boolean || value instanceof String, "backup preference has expected type: " + key);
                values.setProperty(key + ".type", value instanceof Boolean ? "boolean" : "string");
                values.setProperty(key + ".value", String.valueOf(value));
            }
        }
        FileOutputStream output = new FileOutputStream(backupFile());
        try { values.store(output, "DiPlay UI regression: six preference keys only"); output.getFD().sync(); }
        finally { output.close(); }
        require(backupFile().isFile(), "recovery backup saved before temporary preference changes");
    }

    private void loadRestorationBackup() throws Exception {
        if (!backupFile().isFile()) { line("RESTORE no pending backup; no preference changes."); return; }
        Properties values = new Properties();
        FileInputStream input = new FileInputStream(backupFile());
        try { values.load(input); } finally { input.close(); }
        final boolean legacyBackup = "1".equals(values.getProperty("format"));
        require(legacyBackup || "2".equals(values.getProperty("format")), "recovery backup format recognized");
        Map<String, Object> restored = new HashMap<String, Object>();
        prefs = getTargetContext().getSharedPreferences("receiver-ui", Context.MODE_PRIVATE);
        for (String key : PREF_KEYS) {
            String present = values.getProperty(key + ".present");
            if (legacyBackup && "steeringMedia".equals(key) && present == null) {
                // Older five-key UI runs never touched this new preference. Preserve its
                // current value/presence instead of treating a missing backup as deletion.
                if (prefs.contains(key)) restored.put(key, prefs.getAll().get(key));
                continue;
            }
            require("true".equals(present) || "false".equals(present), "backup preference presence valid: " + key);
            if ("true".equals(present)) {
                String type = values.getProperty(key + ".type");
                String value = values.getProperty(key + ".value");
                require(value != null, "backup preference value exists: " + key);
                if ("boolean".equals(type)) {
                    require("true".equals(value) || "false".equals(value), "backup boolean valid: " + key);
                    restored.put(key, Boolean.valueOf(value));
                } else { require("string".equals(type), "backup string type valid: " + key); restored.put(key, value); }
            }
        }
        String service = values.getProperty("steeringServiceRunning");
        require("true".equals(service) || "false".equals(service), "backup steering service state valid");
        originalPrefs = restored;
        originalSteeringRunning = Boolean.parseBoolean(service);
        capturedServiceState = true;
        prefsChanged = true;
        line("RESTORE loaded interrupted test backup; finally restores it without running UI actions.");
    }

    private boolean serviceRunning(String name) {
        ActivityManager manager = (ActivityManager) getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(100);
        for (ActivityManager.RunningServiceInfo service : services) {
            if (PACKAGE.equals(service.service.getPackageName()) && name.equals(service.service.getClassName())) return true;
        }
        return false;
    }

    private void main(String name, final MainAction action) throws Exception {
        checkProcess();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final long[] time = new long[2];
        final long requested = System.nanoTime();
        runOnMainSync(new Runnable() { public void run() {
            time[0] = System.nanoTime();
            try { action.run(); } catch (Throwable error) { failure.set(error); }
            finally { time[1] = System.nanoTime(); }
        } });
        long body = Math.max(0, time[1] - time[0]);
        bodyNanos.add(Long.valueOf(body));
        dispatchNanos.add(Long.valueOf(Math.max(0, time[0] - requested)));
        Long previous = maximumByOperation.get(name);
        if (previous == null || previous.longValue() < body) maximumByOperation.put(name, Long.valueOf(body));
        Throwable error = failure.get();
        if (error != null) throw new RuntimeException("main operation failed: " + name, error);
        waitForIdleSync();
        checkProcess();
    }

    private void checkProcess() {
        require(Process.myPid() == initialPid, "same instrumented target process");
        if (uncaught.get() != null) throw new AssertionError("target thread had an uncaught exception: " + uncaught.get());
    }

    private void timings() {
        line("TIMING operations=" + bodyNanos.size() + " mainBodyUs.p50=" + percentile(bodyNanos, 50) / 1000L +
            " mainBodyUs.p95=" + percentile(bodyNanos, 95) / 1000L + " mainBodyUs.max=" + percentile(bodyNanos, 100) / 1000L +
            " dispatchWaitUs.p95=" + percentile(dispatchNanos, 95) / 1000L +
            " dispatchWaitUs.max=" + percentile(dispatchNanos, 100) / 1000L);
        List<String> names = new ArrayList<String>(maximumByOperation.keySet());
        Collections.sort(names);
        for (String name : names) line("TIMING mainBodyMaxUs " + name + "=" + maximumByOperation.get(name).longValue() / 1000L);
    }

    private static long percentile(List<Long> values, int percent) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<Long>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * percent / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))).longValue();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static boolean booleanField(Object owner, String name) throws Exception {
        return ((Boolean) field(owner, name)).booleanValue();
    }

    private static void invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }

    private void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private synchronized void line(String message) {
        report.append(message).append('\n');
        Log.i(TAG, message);
    }

    private interface MainAction { void run() throws Exception; }
}
