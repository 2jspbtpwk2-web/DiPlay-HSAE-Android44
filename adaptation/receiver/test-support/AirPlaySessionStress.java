import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Desktop validation only. Compile with AirPlaySessionSmoke.java and run both test
 * classes against the FINAL APK in one Android 4.4 app_process:
 * CLASSPATH=<test.dex>:<final.apk> app_process /system/bin AirPlaySessionStress fe80::1234%eth0
 *
 * One six-scenario warmup is followed by ten measured six-scenario rounds. All
 * underlying protocol/socket assertions remain active. No real iPhone pairing or
 * media playback is performed. The selected IPv6 address must exist on this host.
 */
public final class AirPlaySessionStress {
    private static final int ROUNDS = 10;
    private static final int SCENARIOS_PER_ROUND = 6;
    private static final int FD_TOLERANCE = 2;
    private static final long SETTLE_NS = 2_000_000_000L;
    private static final List<Throwable> UNCAUGHT = new CopyOnWriteArrayList<Throwable>();

    public static void main(String[] args) throws Exception {
        final String address = args.length == 0 ? "::1" : args[0];
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                UNCAUGHT.add(error);
                System.err.println("STRESS uncaught thread=" + thread.getName() +
                    " error=" + error.getClass().getSimpleName());
            }
        });
        try {
            int initialThreads = airplayThreads();
            // Initialize /proc and thread-snapshot paths before the measured baseline.
            fdCount();
            int warmupChecksBefore = smokeChecks();
            AirPlaySessionSmoke.main(new String[] {address});
            Snapshot warmup = awaitSettled(Integer.MAX_VALUE, initialThreads, "warmup");
            int warmupChecks = smokeChecks() - warmupChecksBefore;
            require(warmupChecks > 0, "warmup executed its protocol assertions");
            int baselineFd = warmup.fds;
            int baselineThreads = warmup.airplayThreads;
            int measuredChecksBefore = smokeChecks();
            System.out.println("STRESS warmup rounds=1 scenarios=" + SCENARIOS_PER_ROUND +
                " checks=" + warmupChecks + " fdBaseline=" + baselineFd +
                " airplayThreadsBaseline=" + baselineThreads);

            Snapshot last = warmup;
            for (int round = 1; round <= ROUNDS; round++) {
                requireNoUncaught();
                int checksBefore = smokeChecks();
                AirPlaySessionSmoke.main(new String[] {address});
                last = awaitSettled(baselineFd + FD_TOLERANCE, baselineThreads, "round " + round);
                int roundChecks = smokeChecks() - checksBefore;
                require(roundChecks > 0, "round " + round + " executed its protocol assertions");
                System.out.println("STRESS round=" + round + "/" + ROUNDS +
                    " scenarios=" + SCENARIOS_PER_ROUND + " checks=" + roundChecks +
                    " fd=" + last.fds + " airplayThreads=" + last.airplayThreads);
            }
            requireNoUncaught();
            require(last.fds <= baselineFd + FD_TOLERANCE,
                "descriptor growth exceeds tolerance: before=" + baselineFd + " after=" + last.fds);
            require(last.airplayThreads <= baselineThreads,
                "live airplay thread count grew: before=" + baselineThreads + " after=" + last.airplayThreads);
            System.out.println("PASS AirPlaySessionStress warmupRounds=1 measuredRounds=" + ROUNDS +
                " scenariosPerRound=" + SCENARIOS_PER_ROUND +
                " measuredScenarios=" + (ROUNDS * SCENARIOS_PER_ROUND) +
                " measuredChecks=" + (smokeChecks() - measuredChecksBefore) +
                " fdBefore=" + baselineFd + " fdAfter=" + last.fds +
                " fdAllowedGrowth=" + FD_TOLERANCE +
                " airplayThreadsBefore=" + baselineThreads + " airplayThreadsAfter=" + last.airplayThreads +
                " settleDeadlineMs=2000 auth=not-attempted media=deliberately-rejected");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
    }

    private static Snapshot awaitSettled(int maximumFd, int maximumThreads, String phase) throws Exception {
        long deadline = System.nanoTime() + SETTLE_NS;
        int stableSamples = 0;
        Snapshot sample;
        do {
            requireNoUncaught();
            sample = new Snapshot(fdCount(), airplayThreads());
            if (sample.fds <= maximumFd && sample.airplayThreads <= maximumThreads) {
                // Require two samples so an immediately returning close does not
                // hide a listener thread still leaving its blocked system call.
                if (++stableSamples >= 2) return sample;
            } else stableSamples = 0;
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(20);
        } while (true);
        throw new AssertionError(phase + " did not settle within 2000 ms: fd=" + sample.fds +
            " allowed=" + maximumFd + " airplayThreads=" + sample.airplayThreads +
            " allowed=" + maximumThreads);
    }

    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        if (entries == null) throw new AssertionError("cannot read /proc/self/fd");
        return entries.length;
    }

    private static int airplayThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("airplay-")) count++;
        }
        return count;
    }

    private static int smokeChecks() throws Exception {
        Field field = AirPlaySessionSmoke.class.getDeclaredField("checks");
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void requireNoUncaught() {
        if (!UNCAUGHT.isEmpty()) {
            AssertionError failure = new AssertionError("uncaught stress background failure count=" + UNCAUGHT.size());
            failure.initCause(UNCAUGHT.get(0));
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Snapshot {
        final int fds;
        final int airplayThreads;
        Snapshot(int fds, int airplayThreads) { this.fds = fds; this.airplayThreads = airplayThreads; }
    }
}
