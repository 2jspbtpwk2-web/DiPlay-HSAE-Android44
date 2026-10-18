import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** app_process checks of the final product fallback runner; no su or vehicle system changes. */
public final class RootShellRuntimeSmoke {
    private static int checks;
    private static Object root;
    private static Method exec;
    private static Method ok;
    private static Method text;

    private static Object run(String script, long timeoutMs) throws Exception {
        Object result = exec.invoke(root, new Object[]{new String[]{"/system/bin/sh", "-c", script}, timeoutMs});
        String captured = output(result);
        String preview = captured.substring(0, Math.min(200, captured.length())).replace("\u0000", "\\0").replace("\n", "\\n");
        System.out.println("SYNTHETIC_RESULT ok=" + successful(result) + " chars=" + captured.length() + " preview=" + preview);
        return result;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static String output(Object result) throws Exception { return (String) text.invoke(result); }
    private static boolean successful(Object result) throws Exception { return (Boolean) ok.invoke(result); }
    private static int fdCount() {
        String[] values = new File("/proc/self/fd").list();
        return values == null ? -1 : values.length;
    }
    private static void write(File file, String script) throws Exception {
        FileOutputStream stream = new FileOutputStream(file);
        try { stream.write((script + "\n").getBytes("UTF-8")); } finally { stream.close(); }
    }
    private static String interactiveChecks(Class<?> api) throws Exception {
        Method interactive = api.getDeclaredMethod("execInteractive", File.class, long.class); interactive.setAccessible(true);
        Field current = api.getDeclaredField("channel"); current.setAccessible(true);
        Field gate = api.getDeclaredField("lock"); gate.setAccessible(true);
        Method invalidate = null;
        for (Method candidate : api.getDeclaredMethods()) if (candidate.getName().equals("invalidate") && candidate.getParameterTypes().length == 2) {
            invalidate = candidate; invalidate.setAccessible(true); break;
        }
        if (invalidate == null) throw new AssertionError("channel cleanup method unavailable");
        File script = File.createTempFile("diplay-root-smoke-", ".sh", new File("/data/local/tmp"));
        try {
            write(script, "echo INTERACTIVE_OK");
            Object first = interactive.invoke(root, script, 3000L);
            if (first == null) return "SKIP(su-interactive-unavailable)";
            require(successful(first) && output(first).contains("INTERACTIVE_OK"), "interactive normal command succeeds");
            Object original = current.get(root);
            require(original != null, "healthy interactive channel retained");
            Object second = interactive.invoke(root, script, 3000L);
            require(successful(second) && current.get(root) == original, "healthy commands reuse the same su");

            write(script, "echo OLD_CHANNEL_STARTED; sleep 2; echo OLD_READER_LATE");
            Object timed = interactive.invoke(root, script, 100L);
            require(timed != null && !successful(timed), "interactive short deadline fails");
            require(output(timed).contains("TIMEOUT"), "interactive timeout explicitly reported");
            require(current.get(root) == null, "timed out interactive channel discarded");

            write(script, "echo NEW_CHANNEL_ONLY");
            Object replacement = interactive.invoke(root, script, 3000L);
            require(replacement != null && successful(replacement), "interactive recovers on next command");
            require(current.get(root) != null && current.get(root) != original, "interactive replacement has a new channel");
            require(output(replacement).contains("NEW_CHANNEL_ONLY") && !output(replacement).contains("OLD_"), "retired channel output does not contaminate replacement");
            Thread.sleep(2200);
            Object afterLateReader = interactive.invoke(root, script, 3000L);
            require(successful(afterLateReader) && !output(afterLateReader).contains("OLD_"), "late retired reader cannot contaminate another command");

            write(script, "echo EXPECTED_INTERACTIVE_FAILURE; exit 9");
            Object failed = interactive.invoke(root, script, 3000L);
            require(!successful(failed) && output(failed).contains("[ROOT exit=9]"), "interactive nonzero exit is reported");
            Object healthy = current.get(root);
            write(script, "echo AFTER_NONZERO_OK");
            Object recovered = interactive.invoke(root, script, 3000L);
            require(successful(recovered) && current.get(root) == healthy, "nonzero command does not discard a healthy authorized su");
            return "PASS";
        } finally {
            synchronized (gate.get(root)) {
                Object value = current.get(root);
                if (value != null) invalidate.invoke(root, value, false);
            }
            if (!script.delete() && script.exists()) throw new AssertionError("synthetic script cleanup failed");
        }
    }

    public static void main(String[] args) {
        try { checks(args); }
        catch (Throwable error) {
            System.out.println("FAIL RootShellRuntimeSmoke checks=" + checks + " type=" + error.getClass().getName());
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void checks(String[] args) throws Exception {
        Class<?> api = Class.forName("local.airuize.receiver.RootShell");
        root = api.getField("INSTANCE").get(null);
        exec = api.getDeclaredMethod("execProcess", String[].class, long.class); exec.setAccessible(true);
        Class<?> result = Class.forName("local.airuize.receiver.RootShell$Result");
        ok = result.getMethod("getOk"); text = result.getMethod("getText");
        Method withStatus = api.getDeclaredMethod("withStatus", String.class, String.class); withStatus.setAccessible(true);
        StringBuilder full = new StringBuilder("ROOT_BEGIN_9f3a\n@@@SECTION:USB@@@\n");
        while (full.length() < 1024 * 1024 - 16) full.append('x');
        full.append("LATEST_END\n");
        String statusOutput = (String) withStatus.invoke(root, "[ROOT exit=7]\n", full.toString());
        require(statusOutput.startsWith("[ROOT exit=7]\nROOT_BEGIN_9f3a\n"), "status prefix preserves report header");
        require(statusOutput.endsWith("LATEST_END\n"), "status prefix preserves latest tail");
        require(statusOutput.length() <= 1024 * 1024, "status and output share a bounded budget");

        Object normal = run("echo NORMAL_OK; echo STDERR_OK >&2", 3000);
        require(successful(normal), "zero exit succeeds");
        require(output(normal).contains("NORMAL_OK"), "stdout drained");
        require(output(normal).contains("STDERR_OK"), "stderr drained");
        int before = fdCount();

        Object failure = run("echo EXPECTED_FAILURE; exit 7", 3000);
        require(!successful(failure), "nonzero exit fails");
        require(output(failure).contains("[ROOT exit=7]"), "nonzero status retained");
        require(output(failure).contains("EXPECTED_FAILURE"), "failed output retained");

        long began = System.nanoTime();
        // No child process: destroying this exact sh ends the loop and closes its output pipe.
        Object timeout = run("while :; do :; done", 200);
        long durationMs = (System.nanoTime() - began) / 1000000L;
        require(!successful(timeout), "deadline fails");
        require(output(timeout).contains("TIMEOUT"), "deadline explicitly reported");
        require(durationMs < 2500, "deadline runner returns promptly");
        Object next = run("echo AFTER_TIMEOUT_OK", 3000);
        require(successful(next) && output(next).contains("AFTER_TIMEOUT_OK"), "next call succeeds after timeout");

        // Synthetic NULs exercise output pressure without printing any captured payload.
        Object noisy = run("dd if=/dev/zero bs=65536 count=24 2>/dev/null; echo END_OF_NOISE", 10000);
        require(successful(noisy), "oversized output reaches normal process exit");
        String bounded = output(noisy);
        require(bounded.length() <= 1024 * 1024, "fallback output bounded including marker");
        require(bounded.contains("[ROOT output truncated; discardedChars="), "truncation explicitly reported");
        require(bounded.endsWith("END_OF_NOISE\n"), "output keeps draining after reaching limit");

        for (int i = 0; i < 8; i++) require(successful(run("exit 0", 3000)), "repeated process closes");
        String interactive = interactiveChecks(api);
        int after = fdCount();
        require(before >= 0 && after <= before + 2, "no per-call file descriptor growth");
        System.out.println("PASS RootShellRuntimeSmoke checks=" + checks + " outputMaxChars=1048576" +
            " timeoutMs=" + durationMs + " fdBefore=" + before + " fdAfter=" + after +
            " interactiveChannel=" + interactive + " identity=not-used");
    }
}
