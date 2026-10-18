import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Run only by the desktop validation process, outside the product UI. */
public final class NativeUsbSmoke {
    private static Object receiver;
    private static Class<?> api;
    private static int checks;
    private static int call(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = api.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return ((Number) method.invoke(receiver, args)).intValue();
    }
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected=" + expected + " actual=" + actual);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        api = Class.forName("local.airuize.receiver.UsbIfaceNative");
        receiver = api.getField("INSTANCE").get(null);
        Class<?>[] bulk = {int.class, int.class, byte[].class, int.class};
        Class<?>[] iface = {int.class, int.class, int.class};
        Class<?>[] halt = {int.class, int.class};
        Class<?>[] input = {int.class, int.class, byte[].class, int.class, int.class};
        equal(0x0304, call("nativeAbiInfo", new Class<?>[0]));
        equal(-9, call("nativeBulkIn", input, -1, 0x81, new byte[1], 1, 1));
        equal(-9, call("nativeBulkOut", bulk, -1, 6, new byte[]{1}, 1));
        equal(-9, call("nativeSetInterface", iface, -1, 4, 1));
        try (FileInputStream stream = new FileInputStream("/dev/null")) {
            FileDescriptor descriptor = stream.getFD();
            Field value = FileDescriptor.class.getDeclaredField("descriptor");
            value.setAccessible(true);
            int fd = value.getInt(descriptor);
            equal(-22, call("nativeBulkOut", bulk, fd, 0, new byte[]{1}, 1));
            equal(-22, call("nativeBulkOut", bulk, fd, 6, new byte[16385], 1));
            equal(-25, call("nativeBulkOut", bulk, fd, 6, new byte[]{1}, 1));
            equal(-25, call("nativeSetInterface", iface, fd, 4, 1));
            equal(-25, call("nativeClearHalt", halt, fd, 6));
            byte[] readBuffer=new byte[32768]; java.util.Arrays.fill(readBuffer,(byte)0x5a);
            equal(-22, call("nativeBulkIn", input, fd, 1, readBuffer, 1, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x80, readBuffer, 1, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x90, readBuffer, 1, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x81, null, 1, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x81, readBuffer, 0, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x81, readBuffer, 16385, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x81, new byte[1], 2, 1));
            equal(-22, call("nativeBulkIn", input, fd, 0x81, readBuffer, 1, 0));
            equal(-25, call("nativeBulkIn", input, fd, 0x81, readBuffer, 16384, 1));
            for(byte b:readBuffer) if(b!=(byte)0x5a) throw new AssertionError("failed read modified target");
            checks++;
            if (!descriptor.valid()) throw new AssertionError("borrowed fd was closed");
            equal(-1, stream.read());
            equal(fd, value.getInt(descriptor));
        }
        System.out.println("PASS NativeUsbSmoke checks=" + checks);
    }
}
