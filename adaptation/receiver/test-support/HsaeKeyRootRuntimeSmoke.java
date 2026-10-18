import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Final APK private optional ROOT channel, actual bounded finite logcat. No OEM
 * events are injected; no settings, route, pairing, USB or kernel state is changed. */
public final class HsaeKeyRootRuntimeSmoke {
    private static int checks;
    private static final String P="local.airuize.receiver.";
    public static void main(String[] args) throws Exception {
        Class<?> type=Class.forName(P+"HsaeKeyRootChannel");
        final Object first=create(type),second=create(type);
        final Method snapshot=type.getMethod("snapshot"),close=type.getMethod("close");
        final Method command=type.getDeclaredMethod("command",String.class,long.class);command.setAccessible(true);
        Class<?> rootType=Class.forName(P+"RootShell");Object root=rootType.getField("INSTANCE").get(null);
        Method interactive=rootType.getDeclaredMethod("execInteractive",File.class,long.class);interactive.setAccessible(true);
        Field shared=rootType.getDeclaredField("channel");shared.setAccessible(true);
        File marker=File.createTempFile("wheel-owner-test-",".sh",new File("/data/local/tmp"));
        int before=-1;
        try {
            write(marker,"echo CORE_ROOT_HEALTHY");
            Object result=interactive.invoke(root,marker,3000L);
            require(result!=null && Boolean.TRUE.equals(result.getClass().getMethod("getOk").invoke(result)),"existing connection ROOT channel is healthy");
            Object original=shared.get(root);require(original!=null,"shared ROOT owner exists before optional channel");
            before=fd();
            require(snapshot.invoke(first) instanceof String,"private owner obtains verified ROOT and actual finite logcat");
            Object privateProcess=field(first,"process").get(first);
            require(privateProcess!=null,"private ROOT process is retained");
            require(snapshot.invoke(first) instanceof String,"second finite snapshot completes");
            require(field(first,"process").get(first)==privateProcess,"repeated snapshots reuse this owner's shell");
            require(shared.get(root)==original,"optional snapshots do not borrow or replace core channel");
            snapshot.invoke(second);Object otherProcess=field(second,"process").get(second);
            require(otherProcess!=privateProcess,"two optional owners never share a process");
            Object noisy=command.invoke(first,"dd if=/dev/zero bs=65536 count=8 2>/dev/null; echo WHEEL_OUTPUT_END",5000L);
            String text=(String)noisy.getClass().getMethod("getText").invoke(noisy);
            require(text.length()<=256*1024,"private output is bounded to 256KiB");
            require(text.contains("ROOT output truncated") && text.endsWith("WHEEL_OUTPUT_END\n"),"pressure retains newest tail and drains command completion");
            require(snapshot.invoke(first) instanceof String,"next snapshot is clean after bounded noisy command");

            long began=System.nanoTime();
            try { command.invoke(first,"while :; do :; done",100L);throw new AssertionError("timeout missing"); }
            catch(InvocationTargetException expected) { require(problem(expected).equals("TIMEOUT"),"deadline reports explicit timeout"); }
            require((System.nanoTime()-began)/1000000L<2500,"private timeout returns within bound");
            require(shared.get(root)==original,"optional timeout leaves core ROOT identity untouched");
            require(snapshot.invoke(second) instanceof String && field(second,"process").get(second)==otherProcess,"another optional owner remains healthy after timeout");
            result=interactive.invoke(root,marker,3000L);
            require(result!=null && Boolean.TRUE.equals(result.getClass().getMethod("getOk").invoke(result)) && shared.get(root)==original,"core ROOT still executes through original authorized channel");

            final Throwable[] cancelled=new Throwable[1];
            Thread running=new Thread(new Runnable(){public void run(){try {command.invoke(second,"while :; do :; done",5000L);}catch(Throwable error){cancelled[0]=error;}}});
            running.start();Thread.sleep(100);
            began=System.nanoTime();close.invoke(second);
            require((System.nanoTime()-began)/1000000L<500,"foreground close only signals and returns promptly");
            running.join(2500);require(!running.isAlive(),"private in-flight command ends after close");
            require(cancelled[0] instanceof InvocationTargetException && problem((InvocationTargetException)cancelled[0]).equals("CLOSED"),"cancel reports own channel closure");
            close.invoke(second);close.invoke(first);
            require(shared.get(root)==original,"repeated closes cannot touch global ROOT");
            Thread.sleep(500);int after=fd();
            require(before>=0 && after<=before+2,"all optional process pipe descriptors are released");
            System.out.println("PASS HsaeKeyRootRuntimeSmoke checks="+checks+" finiteLogcat=true reusablePrivateShell=true coreOwnerPreserved=true boundedChars=262144 timeoutAndCancel=true fdBefore="+before+" fdAfter="+after+" vehicleKeys=not-proven");
        } finally { close.invoke(first);close.invoke(second);if(!marker.delete()&&marker.exists())throw new AssertionError("marker cleanup failed"); }
    }
    private static String problem(InvocationTargetException error) throws Exception {
        Throwable cause=error.getCause();return String.valueOf(cause.getClass().getMethod("getProblem").invoke(cause));
    }
    private static Object create(Class<?> type) throws Exception {Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    private static Field field(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static int fd(){String[] a=new File("/proc/self/fd").list();return a==null?-1:a.length;}
    private static void write(File file,String value) throws Exception {FileOutputStream out=new FileOutputStream(file);try{out.write((value+"\n").getBytes("UTF-8"));}finally{out.close();}}
    private static void require(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
}
