import android.content.Context;
import android.content.ContextWrapper;
import android.location.Location;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Run with app_process and the FINAL APK on the class path. Synthetic Android Location objects
 * enter the actual shipped LocationListener and NMEA/freshness code. A context without a
 * LocationManager prevents GPS registration, provider changes, CAN binding or phone transmission.
 * The fixture controls private running/expiry state explicitly; this proves callback retention,
 * ordering and cleanup behavior, not a satellite fix, Android GPS permission or iPhone adoption.
 * No coordinate or NMEA content is printed or saved.
 */
public final class GpsProviderRuntimeSmoke {
    private static final String RECEIVER = "local.airuize.receiver.";
    private static int checks;
    private static final List<String> logs = new ArrayList<String>();
    private static Object unit;

    public static void main(String[] args) throws Exception {
        Class<?> providerClass = Class.forName(RECEIVER + "LegacyCarPlayLocationProvider");
        Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0");
        Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1");
        unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Object logger = Proxy.newProxyInstance(function1.getClassLoader(), new Class<?>[] {function1}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
                if ("invoke".equals(method.getName())) { logs.add(String.valueOf(arguments[0])); return unit; }
                return objectMethod(proxy, method, arguments);
            }
        });
        Object speed = Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[] {function0}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
                if ("invoke".equals(method.getName())) return null;
                return objectMethod(proxy, method, arguments);
            }
        });
        Context context = new ContextWrapper(null) {
            @Override public Object getSystemService(String name) { return null; }
        };
        Constructor<?> constructor = providerClass.getConstructor(Context.class, String.class, function1, function0);
        Object provider = constructor.newInstance(context, "USB", logger, speed);
        Object listener = field(provider, "listener").get(provider);
        Method changed = listener.getClass().getDeclaredMethod("onLocationChanged", Location.class); changed.setAccessible(true);
        Method disabled = listener.getClass().getDeclaredMethod("onProviderDisabled", String.class); disabled.setAccessible(true);
        Method latestNmea = providerClass.getMethod("latestNmea");
        Method stop = providerClass.getMethod("stop"), close = providerClass.getMethod("close");
        try {
            require(field(provider, "manager").get(provider) == null, "test context cannot subscribe or change any real GPS provider");
            require(latestNmea.invoke(provider) == null, "unstarted actual provider exposes no location");
            field(provider, "running").setBoolean(provider, true);
            long now = SystemClock.elapsedRealtimeNanos();
            require(now > 12_000_000_000L, "emulator monotonic epoch permits synthetic freshness boundaries");
            Location first = fix("gps", now - 1_000_000_000L, 1704067200000L, 30.13579, 110.24680);
            invoke(changed, listener, first);
            String firstNmea = (String) latestNmea.invoke(provider);
            require(firstNmea != null && firstNmea.startsWith("$GPGGA,") && firstNmea.contains("\r\n$GPRMC,"), "actual Android Location is encoded into the existing GGA/RMC pair");
            checksums(firstNmea);
            Location held = retained(provider);
            require(held != first, "actual provider owns a defensive Location copy");
            first.setLatitude(-40.0);
            require(firstNmea.equals(latestNmea.invoke(provider)), "mutating caller Location cannot change the retained fix");

            invoke(changed, listener, fix("network", now, 1704067201000L, 31.0, 111.0));
            require(firstNmea.equals(latestNmea.invoke(provider)), "rejected network callback preserves the newer valid GPS fix");
            invoke(changed, listener, fix("gps", now - 11_000_000_000L, 1704067201000L, 31.0, 111.0));
            require(firstNmea.equals(latestNmea.invoke(provider)), "stale GPS callback does not erase an unexpired current fix");
            invoke(changed, listener, fix("gps", now, 1704067201000L, Double.NaN, 111.0));
            require(firstNmea.equals(latestNmea.invoke(provider)), "invalid coordinates cannot erase an unexpired current fix");
            invoke(changed, listener, fix("gps", now, 0L, 31.0, 111.0));
            require(firstNmea.equals(latestNmea.invoke(provider)), "invalid wall-clock timestamp cannot erase an unexpired current fix");
            invoke(changed, listener, fix("gps", now - 2_000_000_000L, 1704067199000L, 31.0, 111.0));
            require(firstNmea.equals(latestNmea.invoke(provider)), "older but fresh GPS callback cannot overwrite the newest fix");

            Location second = fix("gps", now - 500_000_000L, 1704067202000L, 32.35791, 112.46802);
            invoke(changed, listener, second);
            String nextNmea = (String) latestNmea.invoke(provider);
            require(nextNmea != null && !nextNmea.equals(firstNmea), "a newer valid callback advances the actual NMEA output");
            checksums(nextNmea);
            retained(provider).setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos() - 11_000_000_000L);
            require(latestNmea.invoke(provider) == null && retained(provider) == null, "actual ten-second freshness guard expires and clears the retained position");

            invoke(changed, listener, fix("gps", SystemClock.elapsedRealtimeNanos(), 1704067203000L, 33.0, 113.0));
            require(latestNmea.invoke(provider) != null, "a fresh fix recovers after expiry without restarting the listener");
            invoke(disabled, listener, "gps");
            require(latestNmea.invoke(provider) == null && retained(provider) == null, "provider-disabled callback clears position immediately");
            invoke(changed, listener, fix("gps", SystemClock.elapsedRealtimeNanos(), 1704067204000L, 34.0, 114.0));
            stop.invoke(provider);
            require(latestNmea.invoke(provider) == null && retained(provider) == null, "STOP clears retained location");
            invoke(changed, listener, fix("gps", SystemClock.elapsedRealtimeNanos(), 1704067205000L, 35.0, 115.0));
            require(retained(provider) == null, "late callback after STOP cannot republish position");
            field(provider, "running").setBoolean(provider, true);
            invoke(changed, listener, fix("gps", SystemClock.elapsedRealtimeNanos(), 1704067206000L, 36.0, 116.0));
            close.invoke(provider); close.invoke(provider);
            invoke(changed, listener, fix("gps", SystemClock.elapsedRealtimeNanos(), 1704067207000L, 37.0, 117.0));
            require(latestNmea.invoke(provider) == null && retained(provider) == null, "repeated close and late callback preserve closed GPS ownership");
            String diagnostic = (String) providerClass.getMethod("diagnosticSnapshot").invoke(provider);
            require(diagnostic.contains("ORDER=1") && diagnostic.contains("closed=true"), "actual metadata records out-of-order rejection and terminal cleanup");
            String exported = diagnostic + logs.toString();
            require(!exported.contains("$GPGGA") && !exported.contains("$GPRMC") && !exported.contains("30.13579") && !exported.contains("110.2468"), "product metadata and callbacks export no synthetic coordinate or NMEA content");
            System.out.println("PASS GpsProviderRuntimeSmoke checks=" + checks +
                " syntheticAndroidLocation=true actualProductListener=true realGPSRegistration=false" +
                " providerSystemState=unchanged freshnessLimitMs=10000 invalidAndOlderFixRetained=true" +
                " phone-location-use=not-proven raw-location=saved-never");
        } finally { close.invoke(provider); }
    }

    private static Location fix(String provider, long elapsed, long utc, double latitude, double longitude) {
        Location fix = new Location(provider);
        fix.setElapsedRealtimeNanos(elapsed); fix.setTime(utc); fix.setLatitude(latitude); fix.setLongitude(longitude);
        fix.setAccuracy(2f); fix.setSpeed(5f); fix.setBearing(90f);
        return fix;
    }
    private static Location retained(Object provider) throws Exception {
        Object store = field(provider, "latest").get(provider);
        Method current = store.getClass().getDeclaredMethod("current"); current.setAccessible(true);
        return (Location) current.invoke(store);
    }
    private static void checksums(String nmea) {
        String[] sentences = nmea.trim().split("\r\n");
        require(sentences.length == 2, "real encoder emits exactly two checked sentences");
        for (String sentence : sentences) {
            int star = sentence.lastIndexOf('*'), value = 0;
            require(star > 0 && sentence.length() == star + 3, "encoded synthetic sentence has a two-digit checksum");
            for (int i = 1; i < star; i++) value ^= sentence.charAt(i);
            require(value == Integer.parseInt(sentence.substring(star + 1), 16), "actual NMEA checksum remains valid after GPS optimization");
        }
    }
    private static Field field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object invoke(Method method, Object owner, Object... args) throws Exception {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException wrapped) { Throwable error = wrapped.getCause(); if (error instanceof Exception) throw (Exception) error; if (error instanceof Error) throw (Error) error; throw wrapped; }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if ("equals".equals(method.getName())) return proxy == args[0];
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("toString".equals(method.getName())) return "SyntheticGpsFixtureCallback";
        throw new UnsupportedOperationException(method.getName());
    }
    private static void require(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
}
