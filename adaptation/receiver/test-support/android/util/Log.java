package android.util;

/** JVM-only logger shim. This directory is never an APK build input. */
public final class Log {
    public static int i(String tag, String text) { return 0; }
    public static int w(String tag, String text) { return 0; }
    public static int w(String tag, String text, Throwable error) { return 0; }
    public static int e(String tag, String text) { return 0; }
    public static int e(String tag, String text, Throwable error) { return 0; }
}
