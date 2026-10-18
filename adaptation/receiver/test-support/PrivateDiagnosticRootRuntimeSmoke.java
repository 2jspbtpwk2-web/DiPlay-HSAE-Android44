import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Actual final APK private diagnostic executor, controlled scripts and a two-second
 * finite getevent attempt. No USB/config/TUN/firewall/pairing or vehicle state changes. */
public final class PrivateDiagnosticRootRuntimeSmoke {
    private static final String P="local.airuize.receiver.";
    private static int checks;
    private static Class<?> type;
    private static Method execute,close,await,started;
    public static void main(String[] args) throws Exception {
        type=Class.forName(P+"PrivateDiagnosticRootCommand");execute=type.getMethod("execute");close=type.getMethod("close");
        await=type.getMethod("awaitCleanup",long.class);started=type.getMethod("hasStartedScript");
        Class<?> rootType=Class.forName(P+"RootShell");Object root=rootType.getField("INSTANCE").get(null);
        Method interactive=rootType.getDeclaredMethod("execInteractive",File.class,long.class);interactive.setAccessible(true);
        Field channel=rootType.getDeclaredField("channel");channel.setAccessible(true);
        File coreScript=script("echo CORE_CONNECTION_STILL_HEALTHY");
        Object core=interactive.invoke(root,coreScript,3000L);
        require(core!=null && ok(core),"core ROOT established in controlled test process");
        Object original=channel.get(root);int before=fd();
        normalAndBounded();submissionDeadline();pendingLaunchOwnership();actualCaptureScript();keyPublicationRace();readOnlyInventories();identityGuards();
        final Object concurrent=create(script("echo PRIVATE_BEGIN; sleep 3; echo PRIVATE_FINISH"),5000,null);
        final Object[] concurrentResult=new Object[1];final Throwable[] error=new Throwable[1];
        Thread active=new Thread(new Runnable(){public void run(){try{concurrentResult[0]=execute.invoke(concurrent);}catch(Throwable t){error[0]=t;}}});
        try {
            active.start();waitStarted(concurrent,active,2000);
            long began=System.nanoTime();core=interactive.invoke(root,coreScript,3000L);
            long elapsed=(System.nanoTime()-began)/1000000L;
            require(core!=null && ok(core) && text(core).contains("CORE_CONNECTION_STILL_HEALTHY"),"core ROOT command succeeds during long diagnostic command");
            require(elapsed<2000L && active.isAlive(),"private sleep does not hold core ROOT command lock");
            require(channel.get(root)==original,"same core ROOT owner retained during private work");
            active.join(6000);require(!active.isAlive() && error[0]==null && ok(concurrentResult[0]),"private concurrent command also completes");
            require(Boolean.TRUE.equals(await.invoke(concurrent,3000L)),"completed private owner cleanup finishes");
            ownedChild(false,false);ownedChild(true,false);ownedChild(false,true);ownedChild(true,true);
            core=interactive.invoke(root,coreScript,3000L);
            require(core!=null && ok(core) && channel.get(root)==original,"private timeout/cancel never retire core ROOT owner");
            Thread.sleep(500);int after=fd();require(after<=before+2,"private commands leave no per-command descriptor growth");
            System.out.println("PASS PrivateDiagnosticRootRuntimeSmoke checks="+checks+
                " actualFinalApk=true privateOwner=true coreOwnerPreserved=true noCoreLockContention=true submissionDeadline=true"+
                " boundedChars=262144 childCleanup=true timeoutAndCancel=true noReplay=true fdBefore="+before+" fdAfter="+after+
                " pendingLaunchOwnership=seededPrivateLifecycle realGetevent=finite2sAttempt productionDefault42s=true fixedReadOnlyInventories=true"+
                " nativeTermIgnoredReaped=true pidParentStartGuards=true keyLatePidStopRace=controlledProductionScriptSubstitution deepBoundedChars=1048576 vehicleKeys=not-proven vehicleConnectTime=not-proven");
        } finally {close.invoke(concurrent);active.join(2000);coreScript.delete();}
    }
    private static void normalAndBounded() throws Exception {
        Object one=create(script("echo PRIVATE_OK; echo PRIVATE_STDERR >&2"),3000,null);
        Object result=execute.invoke(one);
        require(ok(result) && text(result).contains("PRIVATE_OK") && text(result).contains("PRIVATE_STDERR"),"private ROOT verified and stdout/stderr drained");
        require(text(result).contains("privateOwner=true") && text(result).contains("noReplay=true"),"bounded ownership and replay evidence retained");
        require(Boolean.TRUE.equals(await.invoke(one,3000L)),"success cleans private shell/script");
        Object again=execute.invoke(one);require(!ok(again) && text(again).contains("ALREADY_EXECUTED"),"same instance never replays command");
        Object noisy=create(script("dd if=/dev/zero bs=65536 count=8 2>/dev/null; echo DIAG_OUTPUT_END"),5000,null);
        result=execute.invoke(noisy);require(ok(result),"large finite output command completes");
        require(text(result).length()<=262144 && text(result).contains("ROOT output truncated") && text(result).endsWith("DIAG_OUTPUT_END\n"),"output bound includes metadata and preserves newest tail");
        require(Boolean.TRUE.equals(await.invoke(noisy,3000L)),"bounded output owner cleaned");
    }
    private static void submissionDeadline() throws Exception {
        Object command=create(script("echo MUST_NOT_EXECUTE"),50,null);Thread.sleep(100);
        Object result=execute.invoke(command);
        require(!ok(result) && text(result).contains("status=TIMEOUT"),"time spent before execute consumes total submitted deadline");
        Field process=type.getDeclaredField("process");process.setAccessible(true);
        require(process.get(command)==null && !text(result).contains("MUST_NOT_EXECUTE"),"expired submitted command never creates ROOT or runs script");
        require(Boolean.TRUE.equals(await.invoke(command,3000L)),"expired queued command releases private file");
    }
    private static void pendingLaunchOwnership() throws Exception {
        Object command=create(script("echo NOT_EXECUTED"),3000,null);
        Field gate=field("monitor"),running=field("executeRunning"),process=field("process");
        Object lock=gate.get(command);
        // Explicitly seeded private lifecycle state, not an injected vendor ProcessBuilder failure.
        synchronized(lock){running.setBoolean(command,true);}
        close.invoke(command);
        require(Boolean.FALSE.equals(await.invoke(command,80L)),"pending unpublished launch prevents false cleanup completion");
        Process late=new ProcessBuilder("/system/bin/sh").start();
        synchronized(lock){process.set(command,late);running.setBoolean(command,false);lock.notifyAll();}
        require(Boolean.TRUE.equals(await.invoke(command,3000L)),"late-published Process is disposed by the original cleanup owner");
        late.exitValue();require(true,"late process is actually terminated before cleanup returns true");
    }
    private static void actualCaptureScript() throws Exception {
        final File base=File.createTempFile("actual-key-script-",".owner",new File("/data/local/tmp"));base.delete();
        Class<?> builder=Class.forName(P+"DiagnosticKeyCaptureScript");Object instance=builder.getField("INSTANCE").get(null);
        String production=(String)builder.getMethod("build",String.class).invoke(instance,base.getAbsolutePath());
        require(production.contains("-lt 42"),"actual final APK default capture remains42seconds");
        String shell=(String)builder.getMethod("build",String.class,int.class).invoke(instance,base.getAbsolutePath(),2);
        require(new File(base.getAbsolutePath()+".done").createNewFile(),"key cleanup proof precreated by application owner");
        Object command=create(script(shell+"\necho PRIVATE_GETEVENT_CHILD=${child:-none}\n"),8000L,
            callback(new Runnable(){public void run(){try{write(new File(base.getAbsolutePath()+".stop"),"");}catch(Exception e){throw new RuntimeException(e);}}}));
        try {
            Object result=execute.invoke(command);String output=text(result);
            require(ok(result),"actual finite key-capture shell reaches normal exit");
            for(String token:new String[]{"ROOT_KEY_CAPTURE_BEGIN","ROOT_KEY_CAPTURE_END","KEY_EVENT_LINES=","HSAE_HMI_CAPTURE_BEGIN","HSAE_HMI_CAPTURE_END","HSAE_HMI_LOGCAT_EXIT="})
                require(output.contains(token),"actual key-capture output retains "+token);
            require(Boolean.TRUE.equals(await.invoke(command,3000L)),"actual finite key-capture private process and pipes released");
            java.util.regex.Matcher child=java.util.regex.Pattern.compile("PRIVATE_GETEVENT_CHILD=([0-9]+)").matcher(output);
            if(child.find())require(!new File("/proc/"+child.group(1)).exists(),"actual getevent child has been terminated/reaped");
            require(read(new File(base.getAbsolutePath()+".done")).startsWith("KEY_CLEANUP=COMPLETE geteventLoopReaped=true"),"actual getevent loop cleanup confirms wait/reap");
            for(String suffix:new String[]{".out",".pid",".stop",".hmi.status"})require(!new File(base.getAbsolutePath()+suffix).exists(),"actual key-capture temporary ownership cleaned "+suffix);
        } finally {close.invoke(command);await.invoke(command,5000L);for(String suffix:new String[]{".out",".pid",".stop",".hmi.status",".done"})new File(base.getAbsolutePath()+suffix).delete();}
    }
    private static void keyPublicationRace() throws Exception {
        final File base=base("private-key-late-pid-");File spawned=new File(base.getAbsolutePath()+".spawned");
        Class<?> builder=Class.forName(P+"DiagnosticKeyCaptureScript");Object instance=builder.getField("INSTANCE").get(null);
        String shell=(String)builder.getMethod("build",String.class,int.class).invoke(instance,base.getAbsolutePath(),1);
        require(shell.contains("getevent -lt -c 4") && shell.contains("child=$!\n"),"controlled race anchors match the actual generated production key script");
        // Only the native command and its publication timing are controlled. The actual
        // generated parent/loop cleanup, PPid/start checks and escalation functions run.
        shell=shell.replace("getevent -lt -c 4","sh -c 'trap \"\" TERM; exec sleep 30'")
            .replace("child=$!\n","child=$!\necho $child > "+quote(spawned.getAbsolutePath())+"\nsleep 3\n");
        require(new File(base.getAbsolutePath()+".done").createNewFile(),"late-pid proof belongs to the application before ROOT");
        Object command=create(script(shell),10000L,callback(new Runnable(){public void run(){try{write(new File(base.getAbsolutePath()+".stop"),"");}catch(Exception e){throw new RuntimeException(e);}}}));
        try {
            Object result=execute.invoke(command);
            require(ok(result) && text(result).contains("GETEVENT_LOOP_EXIT=0"),"parent stop before PID publication still lets the loop reap its TERM-ignoring native child");
            require(Boolean.TRUE.equals(await.invoke(command,5000L)),"late-pid production script owner is released");
            require(read(new File(base.getAbsolutePath()+".done")).startsWith("KEY_CLEANUP=COMPLETE geteventLoopReaped=true loopExit=0"),"only a successful loop wait produces complete child-chain evidence");
            int pid=Integer.parseInt(read(spawned).trim());require(!new File("/proc/"+pid).exists(),"late-published TERM-ignoring native child is KILLed and reaped");
        } finally {close.invoke(command);await.invoke(command,5000L);for(String suffix:new String[]{".out",".pid",".stop",".hmi.status",".done",".spawned"})new File(base.getAbsolutePath()+suffix).delete();}
    }
    private static void readOnlyInventories() throws Exception {
        Class<?> builder=Class.forName(P+"DiagnosticReadOnlyScript");Object instance=builder.getField("INSTANCE").get(null);
        for(String method:new String[]{"networkPolicy","rootDeep"}) {
            final File base=base("private-fixed-inventory-");
            String shell=(String)builder.getMethod(method,String.class).invoke(instance,base.getAbsolutePath());
            Object command=controlled(script(shell),method.equals("rootDeep")?90000L:15000L,base,method.equals("rootDeep")?1048576:262144);
            try {
                Object result=execute.invoke(command);String output=text(result);
                require(output.contains("DIAG_PRIVATE_WRAPPER_BEGIN") && output.contains("DIAG_PRIVATE_WRAPPER_END failedCommands="),"actual fixed inventory is fully drained with explicit per-command failures: "+method);
                require(output.contains(method.equals("rootDeep")?"ROOT_END_9f3a":"IPV6_POLICY_END"),"actual fixed inventory reaches its final data marker: "+method);
                require(output.contains("DIAG_RUN exit="),"actual native commands report their real status");
                require(output.length()<=(method.equals("rootDeep")?1048576:262144),"inventory result preserves its original bounded budget");
                require(Boolean.TRUE.equals(await.invoke(command,5000L)),"fixed inventory confirms both private shell and owned child cleanup");
                require(read(new File(base.getAbsolutePath()+".done")).contains("activeChildReaped=true watchdogReaped=true"),"production wrapper only records cleanup after waits");
                if(method.equals("rootDeep")) {
                    require(output.contains("@@@SECTION:PM_PKGS@@@") && output.contains("PM_PKGS_APPLICATION_SNAPSHOT"),"fixed shell marks PackageManager application-source insertion point; application API is tested by actual super diagnosis");
                    for(String section:new String[]{"ID","SVC_CHECK","SVC_LIST","PS","PM_PKGS","D_USB","D_CONN","D_AF","D_AP","D_AUDIO","D_WINDOW","IP_RULE","USB_DEBUG","USB_ALT_STATE","INPUT_DEV","KEYLAYOUT","DMESG","LOG_CRASH","LOG_MAIN"})
                        require(output.contains("@@@SECTION:"+section+"@@@"),"actual fixed deep inventory retains section "+section);
                }
            } finally {close.invoke(command);await.invoke(command,5000L);removeBase(base);}
        }
    }
    private static void ownedChild(boolean cancel,boolean ignoreTerm) throws Exception {
        final File base=base("private-diag-owned-");File active=new File(base.getAbsolutePath()+".active");
        // exec retains the same direct-child PID and ignored TERM disposition; no shell descendants.
        String body=ignoreTerm?"diag_run sh -c 'trap \"\" TERM; exec sleep 30'":"diag_run sleep 30";
        final Object command=controlled(script(wrap(base,body)),cancel?10000L:1500L,base,262144);
        final Object[] result=new Object[1];final Throwable[] error=new Throwable[1];
        Thread thread=new Thread(new Runnable(){public void run(){try{result[0]=execute.invoke(command);}catch(Throwable t){error[0]=t;}}});
        try {
            thread.start();long deadline=System.currentTimeMillis()+2000;
            while((!active.exists() || active.length()==0) && thread.isAlive() && System.currentTimeMillis()<deadline)Thread.sleep(20);
            require(active.exists() && active.length()>0,"production wrapper records its actual direct child PID and start identity");int child=Integer.parseInt(read(active).trim().split(" ")[0]);
            if(ignoreTerm)Thread.sleep(100); // Let sh install ignored TERM before the explicit-cancel case.
            if(cancel){long began=System.nanoTime();close.invoke(command);require((System.nanoTime()-began)/1000000L<500,"cancel returns without waiting for child or pipes");}
            thread.join(3000);require(!thread.isAlive() && error[0]==null && result[0]!=null && !ok(result[0]),"cancel/timeout returns explicit failure");
            require(text(result[0]).contains(cancel?"status=CANCELLED":"status=TIMEOUT"),"cancel and timeout are distinguished");
            require(Boolean.TRUE.equals(await.invoke(command,5000L)),"cooperative production wrapper child cleanup completes within bound");
            require(read(new File(base.getAbsolutePath()+".done")).contains("activeChildReaped=true watchdogReaped=true"),"timeout/cancel records actual child and watcher waits");
            require(!new File("/proc/"+child).exists(),ignoreTerm?"TERM-ignoring native direct child is KILLed then reaped":"known child is terminated and reaped");
        } finally {close.invoke(command);await.invoke(command,5000L);thread.join(2000);removeBase(base);}
    }
    private static void identityGuards() throws Exception {
        final File base=base("private-diag-identity-");File foreignPid=new File(base.getAbsolutePath()+".foreign");
        Process foreign=new ProcessBuilder("/system/bin/sh","-c","echo $$ > "+quote(foreignPid.getAbsolutePath())+"; exec sleep 30").start();
        Object command=null;
        try {
            long until=System.currentTimeMillis()+2000;while((!foreignPid.exists() || foreignPid.length()==0) && System.currentTimeMillis()<until)Thread.sleep(20);
            int pid=Integer.parseInt(read(foreignPid).trim());String stat=readAll(new File("/proc/"+pid+"/stat"));String start=stat.substring(stat.lastIndexOf(") ")+2).trim().split(" +")[19];
            String body="sleep 30 &\nowned=$!\ndiag_identity \"$owned\"\nowned_start=$diag_seen_start\n"+
                "diag_kill_owned \"$owned\" \"$((owned_start + 1))\"\nkill -0 \"$owned\" && echo WRONG_START_REFUSED\n"+
                "diag_kill_owned "+pid+" "+start+"\nkill -0 "+pid+" && echo FOREIGN_PARENT_REFUSED\n"+
                "diag_kill_owned \"$owned\" \"$owned_start\"\nwait \"$owned\" 2>/dev/null\necho IDENTITY_GUARDS_COMPLETE\n";
            command=controlled(script(wrap(base,body)),8000L,base,262144);Object result=execute.invoke(command);
            require(ok(result) && text(result).contains("WRONG_START_REFUSED") && text(result).contains("FOREIGN_PARENT_REFUSED") && text(result).contains("IDENTITY_GUARDS_COMPLETE"),"production wrapper refuses stale-start and foreign-parent processes");
            require(Boolean.TRUE.equals(await.invoke(command,5000L)),"identity guard script reaps its own controlled child and watcher");
            try{foreign.exitValue();throw new AssertionError("foreign process was killed");}catch(IllegalThreadStateException expected){require(true,"unrelated process survives wrapper cleanup");}
        } finally {if(command!=null){close.invoke(command);await.invoke(command,5000L);}foreign.destroy();foreign.waitFor();foreign.getInputStream().close();foreign.getErrorStream().close();foreign.getOutputStream().close();foreignPid.delete();removeBase(base);}
    }
    private static String wrap(File base,String body) throws Exception {
        Class<?> builder=Class.forName(P+"DiagnosticReadOnlyScript");Object instance=builder.getField("INSTANCE").get(null);
        Method method=builder.getDeclaredMethod("wrap",String.class,String.class);method.setAccessible(true);
        return (String)method.invoke(instance,base.getAbsolutePath(),body);
    }
    private static Object controlled(File file,long ms,final File base,int maxChars) throws Exception {
        require(new File(base.getAbsolutePath()+".done").createNewFile(),"cleanup proof precreated by application owner before ROOT");
        Object stop=callback(new Runnable(){public void run(){try{write(new File(base.getAbsolutePath()+".stop"),"");}catch(Exception e){throw new RuntimeException(e);}}});
        Object cleanup=callback(new Runnable(){public void run(){try{if(!read(new File(base.getAbsolutePath()+".done")).contains("activeChildReaped=true watchdogReaped=true"))throw new IllegalStateException("child cleanup not proven");}catch(Exception e){throw new RuntimeException(e);}}});
        for(Constructor<?> c:type.getConstructors())if(c.getParameterTypes().length==5)return c.newInstance(file,ms,stop,cleanup,maxChars);
        throw new NoSuchMethodException("private diagnostic bounded five-argument constructor");
    }
    private static File base(String prefix) throws Exception {File file=File.createTempFile(prefix,".owner",new File("/data/local/tmp"));file.delete();return file;}
    private static void removeBase(File base){for(String suffix:new String[]{".active",".done",".stop"})new File(base.getAbsolutePath()+suffix).delete();}
    private static Object callback(final Runnable task) throws Exception {
        Class<?> function=Class.forName("kotlin.jvm.functions.Function0");final Object unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        return Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},new InvocationHandler(){public Object invoke(Object p,Method m,Object[] a){if(m.getName().equals("invoke"))task.run();return unit;}});
    }
    private static Object create(File file,long ms,Object callback) throws Exception {
        for(Constructor<?> c:type.getConstructors())if(c.getParameterTypes().length==(callback==null?2:3)){c.setAccessible(true);return callback==null?c.newInstance(file,ms):c.newInstance(file,ms,callback);}
        throw new NoSuchMethodException("private diagnostic constructor");
    }
    private static void waitStarted(Object owner,Thread thread,long ms) throws Exception {long end=System.currentTimeMillis()+ms;while(!(Boolean)started.invoke(owner)&&thread.isAlive()&&System.currentTimeMillis()<end)Thread.sleep(20);require((Boolean)started.invoke(owner),"private script begins before concurrency probe");}
    private static boolean ok(Object result) throws Exception{return (Boolean)result.getClass().getMethod("getOk").invoke(result);}
    private static String text(Object result) throws Exception{return (String)result.getClass().getMethod("getText").invoke(result);}
    private static File script(String value) throws Exception{File f=File.createTempFile("private-diag-test-",".sh",new File("/data/local/tmp"));write(f,value+"\n");return f;}
    private static void write(File file,String value) throws Exception{FileOutputStream out=new FileOutputStream(file);try{out.write(value.getBytes("UTF-8"));}finally{out.close();}}
    private static String read(File file) throws Exception{java.io.FileInputStream in=new java.io.FileInputStream(file);try{byte[] b=new byte[128];int n=in.read(b);return new String(b,0,n,"UTF-8");}finally{in.close();}}
    private static String readAll(File file) throws Exception{java.io.FileInputStream in=new java.io.FileInputStream(file);try{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))>0)out.write(b,0,n);return new String(out.toByteArray(),"UTF-8");}finally{in.close();}}
    private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
    private static int fd(){String[] values=new File("/proc/self/fd").list();return values==null?-1:values.length;}
    private static Field field(String name) throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void require(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
}
