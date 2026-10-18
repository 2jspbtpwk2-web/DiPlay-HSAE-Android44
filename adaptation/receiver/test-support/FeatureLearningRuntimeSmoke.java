import android.Manifest;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Looper;
import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Final-APK optional calibration, actual Android AudioRecord/AudioTrack and platform preferences.
 * Preferences use an isolated /data/local/tmp fixture, never the installed application's files.
 * Context permission flags are controlled for gate coverage; platform record/track still use the
 * app_process caller's real privileges. No permission, mode, route, volume or GPS source is changed.
 * Synthetic numeric source evidence is labelled separately from actual recorder observations.
 * No PCM, location or phone data is printed/saved. This cannot prove vehicle MIC or speaker routing.
 */
public final class FeatureLearningRuntimeSmoke {
    private static final String R="local.airuize.receiver.";
    private static int checks;
    private static Object learning,unit;
    private static Class<?> function0,function1;
    private static final AtomicBoolean connected=new AtomicBoolean();
    private static String resultDetail,passDetail;

    public static void main(String[] args) {
        Throwable failure=null;
        try { run(args); } catch(Throwable error) { failure=error; }
        try { flushQueuedWorkBounded(); }
        catch(Throwable error) {
            if(failure==null) failure=error;
            else error.printStackTrace(System.err);
        }
        if(failure==null) {
            System.out.println("RESULT FeatureLearningRuntimeSmoke checks="+checks+resultDetail);
            if(passDetail!=null) System.out.println("PASS FeatureLearningRuntimeSmoke checks="+checks+passDetail);
            else System.out.println("LIMIT FeatureLearningRuntimeSmoke actualCaptureNotProvenForBothSources=true");
            System.out.println("EXIT FeatureLearningRuntimeSmoke queuedWorkFlushed=true exit=0");
        } else {
            failure.printStackTrace(System.err);
            System.err.println("FAIL FeatureLearningRuntimeSmoke checks="+checks+" exit=1");
        }
        System.out.flush();System.err.flush();
        // SharedPreferences QueuedWork owns a platform non-daemon executor in app_process.
        // Product workers and fixture cleanup were checked above; never wait forever for that pool.
        System.exit(failure==null?0:1);
    }
    private static void flushQueuedWorkBounded() throws Exception {
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        Thread flush=new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Method wait=Class.forName("android.app.QueuedWork").getDeclaredMethod("waitToFinish");
                    wait.setAccessible(true);wait.invoke(null);
                } catch(Throwable error) { failure.set(error); }
                finally { done.countDown(); }
            }
        },"feature-test-queued-work-flush");
        flush.setDaemon(true);flush.start();
        if(!done.await(5,TimeUnit.SECONDS)) throw new AssertionError("platform QueuedWork flush exceeded 5 seconds");
        if(failure.get()!=null) throw new IllegalStateException("platform QueuedWork flush failed",failure.get());
    }
    private static void run(String[] args) throws Exception {
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Object thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        final Fixture context=new Fixture(system);
        Class<?> type=Class.forName(R+"FeatureLearning");
        learning=type.getField("INSTANCE").get(null);
        unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        function0=Class.forName("kotlin.jvm.functions.Function0");
        function1=Class.forName("kotlin.jvm.functions.Function1");
        Object gateCallback=function(function0,null);
        AudioManager audio=(AudioManager)system.getSystemService(Context.AUDIO_SERVICE);
        int mode=audio.getMode(),volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int baseline=workers();
        Closeable first=null,second=null;
        try {
            require(mode==AudioManager.MODE_NORMAL,"emulator is idle; test never changes audio mode");
            SharedPreferences ui=context.getSharedPreferences("receiver-ui",0);
            ui.edit().putBoolean("microphonePcm",false).putBoolean("vehicleGps",false).putInt("bufferMillis",300).commit();
            require(startMic(context,gateCallback,6,false,new Completion())==null,"capture=false refuses recorder calibration before opening a recorder");
            require(!busy(),"disabled capture creates no calibration worker ownership");
            ui.edit().putBoolean("microphonePcm",true).commit();
            context.recordPermission=false;
            require(startMic(context,gateCallback,6,false,new Completion())==null,"denied permission refuses calibration without granting permission");
            context.recordPermission=true;
            connected.set(true);
            require(startMic(context,gateCallback,6,false,new Completion())==null,"in-progress/active session refuses local recorder calibration");
            connected.set(false);

            first=(Closeable)call("tryAcquireLiveMicrophone");second=(Closeable)call("tryAcquireLiveMicrophone");
            require(first!=null && second!=null,"multiple live MIC references retain original concurrency");
            require(startMic(context,gateCallback,6,false,new Completion())==null,"existing live references exclude local recorder calibration");
            first.close();first.close();first=null;
            require(startMic(context,gateCallback,6,false,new Completion())==null,"one remaining live owner still excludes calibration");
            second.close();second=null;

            boolean source6=realRound(context,gateCallback,6);
            boolean source7=realRound(context,gateCallback,7);
            Completion cancelled=new Completion();
            Closeable operation=startMic(context,gateCallback,6,false,cancelled);
            require(operation!=null,"immediate-cancel case acquires a calibration token");
            operation.close();operation.close();
            awaitIdle();require(!busy(),"immediate cancel releases recorder and calibration ownership");
            Closeable afterCancel=(Closeable)call("tryAcquireLiveMicrophone");
            require(afterCancel!=null,"live MIC can acquire immediately after completed cancellation cleanup");afterCancel.close();

            // Actual source selection persistence is tested with explicitly synthetic numeric
            // evidence. It is never counted as real capture or phone speech recognition.
            String prefix=deviceId()+".";
            SharedPreferences p=context.getSharedPreferences("receiver-feature-learning-v1",0);
            syntheticPcm(p,prefix,7,false,0);syntheticPcm(p,prefix,7,true,1000);
            require(Boolean.FALSE.equals(invoke("confirmMicrophone",new Class<?>[]{Context.class,int.class,String.class},context,7,"speechrecognition")),"untried source cannot be labelled vehicle-confirmed");
            require(Boolean.TRUE.equals(invoke("stageMicrophone",new Class<?>[]{Context.class,int.class,String.class},context,7,"speechrecognition")),"valid synthetic numeric evidence allows an explicit trial");
            require(p.getBoolean(prefix+"candidate-speechrecognition",false) && !p.getBoolean(prefix+"confirmed-speechrecognition",false),"trial is stored separately from vehicle confirmation");
            require(source(context,"speechrecognition",6)==7,"explicit pending trial is resolved for next stream");
            require(Boolean.TRUE.equals(invoke("confirmMicrophone",new Class<?>[]{Context.class,int.class,String.class},context,7,"speechrecognition")),"explicit fixture user confirmation accepts the staged source");
            require(!p.getBoolean(prefix+"candidate-speechrecognition",true) && p.getBoolean(prefix+"confirmed-speechrecognition",false),"confirmation retires only the pending trial marker");
            require(source(context,"speechrecognition",6)==7,"confirmed supported source is resolved for its purpose");
            require(source(context,"telephony",7)==7 && source(context,"default",1)==1,"other purposes keep original sources");
            invoke("microphoneSourceFailed",new Class<?>[]{Context.class,String.class,int.class},context,"speechrecognition",7);
            require(source(context,"speechrecognition",6)==6,"source open failure revokes only future learned override and restores default");
            require(Boolean.TRUE.equals(invoke("stageMicrophone",new Class<?>[]{Context.class,int.class,String.class},context,7,"speechrecognition")),"a fresh explicit source trial can be created after failure");
            invoke("microphoneSourceFailed",new Class<?>[]{Context.class,String.class,int.class},context,"speechrecognition",7);
            require(!p.getBoolean(prefix+"candidate-speechrecognition",true) && source(context,"speechrecognition",6)==6,"failed pending trial is revoked too");
            require(Boolean.FALSE.equals(invoke("stageMicrophone",new Class<?>[]{Context.class,int.class,String.class},context,99,"speechrecognition")),"unsupported candidate is never staged");
            for(String action:new String[]{"stageMicrophone","confirmMicrophone"}) {
                require(Boolean.FALSE.equals(invoke(action,new Class<?>[]{Context.class,int.class,String.class},context,-1,"speechrecognition")),"negative source is rejected before fallback comparison");
                require(Boolean.FALSE.equals(invoke(action,new Class<?>[]{Context.class,int.class,String.class},context,7,"unknown-purpose")),"unknown use cannot be staged or confirmed");
            }
            invoke("rememberBuffer",new Class<?>[]{Context.class,int.class},context,500);
            invoke("resetMicrophoneOverrides",new Class<?>[]{Context.class},context);
            require(buffer(context)==500,"restoring MIC defaults leaves music buffer memory untouched");
            invoke("rememberBuffer",new Class<?>[]{Context.class,int.class},context,500);
            require(buffer(context)==500,"finite explicit buffer selection is read for next connection");
            ui.edit().putInt("bufferMillis",1000).commit();
            require(buffer(context)==1000,"main setting manual change wins over saved original buffer");
            invoke("revokeAudioBuffer",new Class<?>[]{Context.class},context);
            ui.edit().putInt("bufferMillis",300).commit();
            require(buffer(context)==300,"manual override revocation prevents stale learning from reviving");

            Completion tone=new Completion();
            operation=(Closeable)invoke("startTone",new Class<?>[]{Context.class,function0,function1},context,gateCallback,function(function1,tone));
            require(operation!=null,"explicit local output audition starts without changing routing");
            require(tone.done.await(9,TimeUnit.SECONDS),"actual AudioTrack audition completes within its deadline");awaitIdle();
            long toneBytes=p.getLong(prefix+"tone-bytes",0);
            boolean toneHead=p.getBoolean(prefix+"tone-head",false);
            require(toneBytes>0,"actual calibration AudioTrack writes PCM");
            require(toneHead,"actual calibration AudioTrack playback head advances");
            require(!p.getBoolean(prefix+"tone-heard",false),"playback head alone never auto-confirms physical speaker sound");
            invoke("confirmTone",new Class<?>[]{Context.class,boolean.class},context,true);
            require(p.getBoolean(prefix+"tone-heard",false),"only explicit fixture user confirmation records audible result");
            retainedCalibrationToken(context,gateCallback);
            require(!((Boolean)invoke("gpsAllowed",new Class<?>[]{Context.class},context)).booleanValue(),"GPS-off fixture cannot start guided GPS observation");
            int before=context.locationQueries;
            invoke("observePassiveGps",new Class<?>[]{Context.class},context);
            invoke("observePassiveGps",new Class<?>[]{Context.class},context);
            require(context.locationQueries==before,"passive aggregation never requests a LocationManager or subscribes");
            require(audio.getMode()==mode && audio.getStreamVolume(AudioManager.STREAM_MUSIC)==volume,"mode and global music volume remain unchanged");
            require(workers()==baseline,"calibration, cancellation and deadline workers all exit");
            String capture=source6 && source7 ? "PASS" : source6 || source7 ? "PARTIAL" : "UNAVAILABLE";
            resultDetail=" lifecycle=PASS capture="+capture+
                " realAudioTrack=true writeBytes="+toneBytes+" playbackHead=true prefs=isolated-platform"+
                " syntheticSourceSelection=true stagedTrialSeparated=true seededOwnerTokenRegression=true noGlobalSettingChanges=true vehicleMIC=not-proven physicalSpeaker=not-proven phone=not-tested";
            if("PASS".equals(capture)) passDetail=
                " realAudioRecordSources=6,7 realAudioTrack=true cancellation=true micCAS=true manualOverride=true rawPCM=saved-never";
        } finally {
            connected.set(false);call("cancelCalibration");
            if(first!=null) first.close();if(second!=null) second.close();
            awaitIdle();context.cleanup();
        }
    }
    /** Explicitly seeds private lifecycle state around an actual, initialized AudioTrack owner.
     * It does NOT inject/fabricate a vendor release exception or count this as playback/capture.
     * This is the residue a failed release must keep protected; the real release is then executed.
     */
    private static void retainedCalibrationToken(Fixture context,Object gate) throws Exception {
        Class<?> jobType=Class.forName(R+"FeatureLearning$AudioJob");
        Constructor<?> constructor=jobType.getDeclaredConstructor(Context.class,function0,boolean.class,Closeable.class);
        constructor.setAccessible(true);
        Field active=learning.getClass().getDeclaredField("active");active.setAccessible(true);
        Field finished=jobType.getDeclaredField("finished");finished.setAccessible(true);
        Method attach=jobType.getDeclaredMethod("attach",AudioTrack.class);attach.setAccessible(true);
        Method release=jobType.getDeclaredMethod("releaseAudio");release.setAccessible(true);
        Method cleanup=learning.getClass().getDeclaredMethod("finishCleanup",jobType);cleanup.setAccessible(true);
        Class<?> stateType=Class.forName(R+"DiagnosticRunState");Object state=stateType.getField("INSTANCE").get(null);
        Method begin=stateType.getMethod("begin"),running=stateType.getMethod("isRunning"),end=stateType.getMethod("finish");
        Object old=null,newOwner=null;AudioTrack held=null;
        require(!busy(),"seeded-owner regression starts after all actual calibration jobs have exited");
        try {
            require(Boolean.TRUE.equals(begin.invoke(state)),"seeded old calibration obtains real diagnostic token");
            old=constructor.newInstance(context,gate,false,null);
            int minimum=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            require(minimum>0,"seeded-owner regression uses a supported actual AudioTrack format");
            held=new AudioTrack(AudioManager.STREAM_MUSIC,44100,AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum,8192),AudioTrack.MODE_STREAM);
            require(held.getState()==AudioTrack.STATE_INITIALIZED,"seeded old job holds a real initialized AudioTrack, never played");
            attach.invoke(old,held);((AtomicBoolean)finished.get(old)).set(true);active.set(learning,old);
            cleanup.invoke(learning,old);
            require(active.get(learning)==old && busy(),"unfinished native cleanup retains exact old job ownership");
            require(Boolean.TRUE.equals(running.invoke(state)),"retained AudioTrack owner keeps diagnostic token running");
            require(Boolean.FALSE.equals(begin.invoke(state)),"new AudioProbe/SuperDiag token is refused while resource remains");
            release.invoke(old);cleanup.invoke(learning,old);
            require(held.getState()==AudioTrack.STATE_UNINITIALIZED,"product releaseAudio actually releases the retained AudioTrack");
            require(!busy() && !Boolean.TRUE.equals(running.invoke(state)),"complete resource cleanup clears owner and its token together");
            require(Boolean.TRUE.equals(begin.invoke(state)),"a new diagnostic token can begin after complete cleanup");
            newOwner=constructor.newInstance(context,gate,false,null);
            ((AtomicBoolean)finished.get(newOwner)).set(true);active.set(learning,newOwner);
            cleanup.invoke(learning,old);
            require(active.get(learning)==newOwner && Boolean.TRUE.equals(running.invoke(state)),"late old cleanup cannot clear new owner or its token");
            require(Boolean.FALSE.equals(begin.invoke(state)),"late old cleanup does not admit a competing diagnostic");
            cleanup.invoke(learning,newOwner);
            require(!busy() && !Boolean.TRUE.equals(running.invoke(state)),"new seeded owner can release only its own token");
            require(Boolean.TRUE.equals(begin.invoke(state)),"token remains reusable after old/new owner regression");
            end.invoke(state);
            System.out.println("FEATURETOKEN seededPrivateLifecycle=true actualAudioTrackOwner=true retainedToken=true releasedToken=true lateOwnerIsolated=true vendorReleaseException=not-injected");
        } finally {
            if(old!=null) release.invoke(old);if(newOwner!=null) release.invoke(newOwner);
            Object owner=active.get(learning);
            if(owner==old || owner==newOwner) active.set(learning,null);
            if(held!=null) held.release();end.invoke(state);
        }
    }
    private static boolean realRound(Fixture context,Object gate,int source) throws Exception {
        Completion c=new Completion();Closeable operation=startMic(context,gate,source,false,c);
        require(operation!=null,"real AudioRecord source "+source+" calibration starts");
        require(call("tryAcquireLiveMicrophone")==null,"local calibration excludes a concurrent live MIC before its release");
        require(c.done.await(9,TimeUnit.SECONDS),"source "+source+" callback completes within deadline");awaitIdle();
        SharedPreferences p=context.getSharedPreferences("receiver-feature-learning-v1",0);
        String b=deviceId()+".mic"+source+"-quiet-";
        long bytes=p.getLong(b+"bytes",0),reads=p.getLong(b+"reads",0),errors=p.getLong(b+"errors",0);
        boolean captured=bytes>0 && reads>0 && errors==0;
        require(!busy(),"source "+source+" recorder and token finish before next serial source");
        Closeable live=(Closeable)call("tryAcquireLiveMicrophone");require(live!=null,"source "+source+" cleanup releases the live MIC gate");live.close();
        System.out.println("FEATUREMIC source="+source+" actualReadBytes="+bytes+" reads="+reads+" errors="+errors+" captured="+captured+" rawPCM=saved-never");
        return captured;
    }
    private static Closeable startMic(Context context,Object gate,int source,boolean speech,Completion c) throws Exception {
        return (Closeable)invoke("startMicrophone",new Class<?>[]{Context.class,function0,int.class,boolean.class,function1},context,gate,source,speech,function(function1,c));
    }
    private static int source(Context context,String purpose,int fallback) throws Exception {
        return ((Number)invoke("resolvedMicrophoneSource",new Class<?>[]{Context.class,String.class,int.class},context,purpose,fallback)).intValue();
    }
    private static int buffer(Context context) throws Exception {
        Class<?> settings=Class.forName(R+"ReceiverSettings");Object companion=settings.getField("Companion").get(null);
        Object current=companion.getClass().getMethod("read",Context.class).invoke(companion,context);
        return ((Number)invoke("resolvedAudioBuffer",new Class<?>[]{Context.class,settings},context,current)).intValue();
    }
    private static void syntheticPcm(SharedPreferences p,String prefix,int source,boolean speech,int value) {
        String b=prefix+"mic"+source+"-"+(speech?"speech-":"quiet-");
        p.edit().putLong(b+"reads",1).putLong(b+"bytes",3200).putLong(b+"samples",1600)
            .putInt(b+"peak",value).putLong(b+"squares",Double.doubleToLongBits(1600.0*value*value))
            .putLong(b+"zeros",value==0?1600:0).putLong(b+"clipped",0).putLong(b+"errors",0).putString(b+"error","").commit();
    }
    private static String deviceId() throws Exception {
        Class<?> t=Class.forName(R+"LearningDevice");return (String)t.getMethod("id").invoke(t.getField("INSTANCE").get(null));
    }
    private static Object call(String name) throws Exception { return invoke(name,new Class<?>[0]); }
    private static Object invoke(String name,Class<?>[] types,Object... args) throws Exception { return learning.getClass().getMethod(name,types).invoke(learning,args); }
    private static boolean busy() throws Exception { return Boolean.TRUE.equals(call("isCalibrating")); }
    private static void awaitIdle() throws Exception {
        long until=System.nanoTime()+4_000_000_000L;
        while((busy() || workers()>0) && System.nanoTime()<until) Thread.sleep(10);
        require(!busy() && workers()==0,"all optional calibration resources/deadline threads have returned");
    }
    private static int workers() {
        int count=0;for(Thread t:Thread.getAllStackTraces().keySet()) if(t.isAlive() && t.getName().startsWith("feature-audio-")) count++;
        return count;
    }
    private static Object function(Class<?> type,final Completion completion) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},new InvocationHandler() {
            @Override public Object invoke(Object proxy,Method method,Object[] args) {
                if("invoke".equals(method.getName())) {
                    if(completion==null) return Boolean.valueOf(connected.get());
                    completion.done.countDown();return unit;
                }
                if("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if("equals".equals(method.getName())) return args!=null && args.length==1 && args[0]==proxy;
                return "FeatureLearningControlledMetadataCallback";
            }
        });
    }
    private static void require(boolean ok,String message) { checks++;if(!ok) throw new AssertionError(message); }
    private static final class Completion { final CountDownLatch done=new CountDownLatch(1); }
    private static final class Fixture extends ContextWrapper {
        final File directory=new File("/data/local/tmp/diplay-feature-learning-v058-"+android.os.Process.myPid());
        final Map<String,SharedPreferences> preferences=new HashMap<String,SharedPreferences>();
        boolean recordPermission=true;int locationQueries;
        Fixture(Context base) { super(base);if(!directory.mkdirs() && !directory.isDirectory()) throw new IllegalStateException("fixture directory"); }
        @Override public Context getApplicationContext() { return this; }
        @Override public int checkCallingOrSelfPermission(String permission) {
            if(Manifest.permission.RECORD_AUDIO.equals(permission)) return recordPermission?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;
            return PackageManager.PERMISSION_DENIED;
        }
        @Override public Object getSystemService(String name) {
            if(Context.LOCATION_SERVICE.equals(name)) { locationQueries++;return null; }
            return super.getSystemService(name);
        }
        @Override public synchronized SharedPreferences getSharedPreferences(String name,int mode) {
            SharedPreferences p=preferences.get(name);if(p!=null) return p;
            try {
                if(!name.matches("[a-z0-9-]{1,80}")) throw new IllegalArgumentException("fixture pref name");
                Class<?> t=Class.forName("android.app.SharedPreferencesImpl");
                Constructor<?> constructor=t.getDeclaredConstructor(File.class,int.class);constructor.setAccessible(true);
                p=(SharedPreferences)constructor.newInstance(new File(directory,name+".xml"),0);preferences.put(name,p);return p;
            } catch(Exception e) { throw new IllegalStateException("isolated platform preferences unavailable",e); }
        }
        void cleanup() throws Exception {
            for(SharedPreferences p:preferences.values()) p.edit().clear().commit();
            File[] files=directory.listFiles();
            if(files!=null) for(File f:files) if(f.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) &&
                (f.getName().endsWith(".xml") || f.getName().endsWith(".xml.bak"))) f.delete();
            directory.delete();
        }
    }
}
