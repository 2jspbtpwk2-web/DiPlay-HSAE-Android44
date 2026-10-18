import android.content.ContextWrapper;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * app_process regression against production classes loaded from the final APK on CLASSPATH.
 * Constructs Service without onCreate/Context attachment, USB access, or a real phone session.
 * The logHistory monitor deterministically suspends an old callback until a newer attempt exists.
 */
public final class ConnectionMilestoneRuntimeSmoke {
    private static int checks;
    private static Object service;
    private static Class<?> serviceClass;
    private static Object logGate;
    private static Object milestones;
    private static Method begin;
    private static Method latest;
    private static Method history;
    private static Method logForAttempt;
    private static Method checkpoint;
    private static final String STALE = "USB first video packet STALE_CALLBACK_ATTEMPT_1";
    private static final String CURRENT = "USB first video packet CURRENT_CALLBACK_ATTEMPT_2";
    private static final String NEW_CURRENT = "USB first video packet CURRENT_CALLBACK_ATTEMPT_3";

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        require(Looper.getMainLooper() != null, "Android main Looper is available");
        serviceClass = Class.forName("local.airuize.receiver.LegacyWiredService");
        service = serviceClass.getConstructor().newInstance();
        try {
            require(((ContextWrapper) service).getBaseContext() == null,
                "Service has no attached Context and onCreate is not called");
            logGate = value(service, "logHistory");
            milestones = value(service, "milestones");
            begin = milestones.getClass().getMethod("begin", int.class, long.class);
            latest = milestones.getClass().getMethod("latest");
            history = milestones.getClass().getMethod("history");
            logForAttempt = serviceClass.getDeclaredMethod("logForAttempt", String.class, Integer.class);
            checkpoint = serviceClass.getDeclaredMethod("checkpoint", String.class, int.class);
            logForAttempt.setAccessible(true);
            checkpoint.setAccessible(true);
            rejectBlockedOldCallback();
            acceptCurrentCallback();
            rejectBlockedOldCheckpoint();
            acceptCallbackAfterSecondRollover();
            System.out.println("PASS ConnectionMilestoneRuntimeSmoke checks=" + checks +
                " oldCallback=blocked-then-rejected oldCheckpoint=blocked-then-rejected Context=unattached");
        } finally {
            // Constructors create lazy executors; no Service lifecycle method or Context is needed.
            ((ExecutorService) value(service, "worker")).shutdownNow();
            ((ExecutorService) value(service, "evidenceWorker")).shutdownNow();
        }
    }

    private static void rejectBlockedOldCallback() throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread old;
        synchronized (logGate) {
            beginAttempt(1);
            old = invokeOnThread("old-milestone-callback", failure, logForAttempt, STALE, Integer.valueOf(1));
            awaitBlocked(old);
            beginAttempt(2);
        }
        old.join(5000);
        require(!old.isAlive(), "old callback exits after the log monitor opens");
        require(failure.get() == null, "old callback returns without throwing");
        require(attempt(latest.invoke(milestones)) == 2, "latest milestone table belongs to attempt 2");
        require(first(latest.invoke(milestones)).isEmpty(), "old first-video callback cannot populate new milestone table");
        require(!report().contains(STALE), "old callback is absent from the new service log");
        require(field("eventSequence").getLong(service) == 0, "old callback creates no rolling log event");
        require(!evidence().contains(STALE), "old callback creates no media evidence record");
    }

    private static void acceptCurrentCallback() throws Exception {
        logForAttempt.invoke(service, CURRENT, Integer.valueOf(2));
        Object current = latest.invoke(milestones);
        require(report().contains(CURRENT), "current attempt callback remains in the service log");
        require(field("eventSequence").getLong(service) == 1, "current callback creates one rolling log event");
        require(first(current).containsKey("首个视频数据"), "current first-video callback records the actual milestone");
        require(((Number) first(current).get("首个视频数据")).longValue() >= 0,
            "current first-video milestone has a nonnegative relative time");
        require(evidence().contains(CURRENT), "current callback retains its media evidence");
    }

    private static void rejectBlockedOldCheckpoint() throws Exception {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        String before = evidence();
        Thread old;
        synchronized (logGate) {
            old = invokeOnThread("old-milestone-checkpoint", failure, checkpoint, "STALE_CHECKPOINT_2", Integer.valueOf(2));
            awaitBlocked(old);
            beginAttempt(3);
        }
        old.join(5000);
        require(!old.isAlive(), "old checkpoint exits after the log monitor opens");
        require(failure.get() == null, "old checkpoint safely returns with no attached Context");
        require(attempt(latest.invoke(milestones)) == 3, "latest milestone table belongs to attempt 3");
        require(first(latest.invoke(milestones)).isEmpty(), "old checkpoint cannot populate attempt 3 milestones");
        require(evidence().equals(before), "old checkpoint freezes no stale transport evidence");
        require(((AtomicInteger) value(service, "evidencePending")).get() == 0,
            "old checkpoint schedules no runtime probe");
        require(((ContextWrapper) service).getBaseContext() == null, "stale checkpoint needs no Context attachment");
    }

    private static void acceptCallbackAfterSecondRollover() throws Exception {
        logForAttempt.invoke(service, NEW_CURRENT, Integer.valueOf(3));
        require(report().contains(NEW_CURRENT), "new current callback is still accepted after rollover");
        require(first(latest.invoke(milestones)).containsKey("首个视频数据"), "attempt 3 gets its own first-video milestone");
        List<?> saved = (List<?>) history.invoke(milestones);
        require(saved.size() == 3 && attempt(saved.get(1)) == 2 && first(saved.get(1)).containsKey("首个视频数据"),
            "attempt 2 milestone history survives the new attempt");
        require(field("eventSequence").getLong(service) == 2, "only the two current callbacks produce log events");
    }

    /** Called while the same monitor used by producer log/checkpoint and attempt begin is held. */
    private static void beginAttempt(int id) throws Exception {
        long now = SystemClock.elapsedRealtime();
        ((AtomicInteger) value(service, "generation")).set(id);
        field("workingGeneration").setInt(service, id);
        field("attemptStartedAt").setLong(service, now);
        field("connectionStage").setInt(service, -1);
        begin.invoke(milestones, Integer.valueOf(id), Long.valueOf(now));
    }

    private static Thread invokeOnThread(String name, final AtomicReference<Throwable> failure,
        final Method method, final Object... arguments) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try { method.invoke(service, arguments); }
                catch (InvocationTargetException error) { failure.set(error.getCause()); }
                catch (Throwable error) { failure.set(error); }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(1);
        require(thread.getState() == Thread.State.BLOCKED,
            thread.getName() + " reaches the producer monitor before attempt rollover");
    }

    private static int attempt(Object snapshot) throws Exception {
        return ((Number) snapshot.getClass().getMethod("getAttempt").invoke(snapshot)).intValue();
    }
    private static Map<?, ?> first(Object snapshot) throws Exception {
        return (Map<?, ?>) snapshot.getClass().getMethod("getFirst").invoke(snapshot);
    }
    private static String report() throws Exception { return (String) serviceClass.getMethod("report").invoke(service); }
    private static String evidence() throws Exception {
        Object value = value(service, "evidence");
        return (String) value.getClass().getMethod("snapshot").invoke(value);
    }
    private static Field field(String name) throws Exception {
        Field field = serviceClass.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object value(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void require(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
        System.out.println("CHECK " + label);
    }
}
