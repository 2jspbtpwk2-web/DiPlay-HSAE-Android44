import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.SystemClock;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual FINAL APK classes under app_process. Memory-only Context/SharedPreferences never touch
 * the installed application's preferences, pairing, providers, audio or files. Synthetic interval
 * metadata and explicitly seeded private monotonic state avoid waiting for a real driving session.
 * The real wrapper/parser/persistence/settings/translation code executes; this is not a vehicle
 * performance, microphone, ROOT-reader, iPhone or actual touch-to-display measurement.
 */
public final class LearningRuntimeSmoke {
    private static final String P = "local.airuize.receiver.";
    private static int checks;
    private static Class<?> settingsType, windowType, perfType;
    private static Object perf;
    private static final String[] SETTING_NAMES = {
        "width","height","fps","scale","physicalMm","physicalHeight","inset","bufferMillis","volume",
        "navigationDuck","audioFocus","networkMode","language","hevc","opus","reconnect","bootConnect",
        "background","microphone","gps","insetTop","insetBottom","insetLeft","insetRight","drawOutside",
        "rightHandDrive","fullScreen","mediaChannel","navigationChannel","displayMode",
        "availableWidth","availableHeight","hardwareWidth","hardwareHeight"
    };

    public static void main(String[] args) throws Exception {
        settingsType = Class.forName(P+"ReceiverSettings");
        windowType = Class.forName(P+"PerformanceLearningPolicy$Window");
        perfType = Class.forName(P+"PerformanceLearning");
        perf = perfType.getField("INSTANCE").get(null);
        MemoryContext context = new MemoryContext();
        require(SystemClock.elapsedRealtime()>120000L, "emulator monotonic epoch permits bounded synthetic past windows");
        Object cryptoBefore = cryptoCounters();
        initialize(context, "smoke-performance");
        Object base = settings(720,864,30,"auto",10,false);
        require(width(resolve(base))==720 && height(resolve(base))==864, "fresh profile uses original portrait AUTO canvas");
        require((Boolean)call(perf,"isAutomatic"), "automatic learning default is enabled");
        require(!(Boolean)call(perf,"isLocked"), "fresh profile is not manually locked");
        require(!(Boolean)call(perf,"confirmCurrent",true), "a created profile without actual interval output cannot be confirmed");
        String beforeMain = context.preferences("receiver-ui").getAll().toString();

        begin(42,base);
        require(!(Boolean)call(perf,"confirmCurrent",true), "beginSession alone is not connection or video success");
        touchDoesNotWaitForLearningMonitor();
        seedAndObserve(42,0,100,100,230,100,0);
        require(field(perf,"nextTier").getInt(perf)==1, "four fresh active native intervals recommend one lower tier");
        Object active = field(perf,"current").get(perf);
        require(width(field(active,"applied").get(active))==720, "recommendation cannot mutate current frozen profile");
        require(width(base)==720 && height(base)==864, "input ReceiverSettings remains immutable");
        Object next = resolve(base);
        require(width(next)==640 && height(next)==768, "next connection receives lower portrait resolution");
        Object advertised = call(next,"display");
        require(((Number)call(advertised,"getWidthPixels")).intValue()==640 &&
            ((Number)call(advertised,"getHeightPixels")).intValue()==768, "display advertises learned dimensions exactly once without double scaling");
        require(((Number)call(advertised,"getFps")).intValue()==30, "learning preserves original frame rate");
        require(field(perf,"pendingPipeline").get(perf)==null, "whitelisted crypto interval is consumed at most once");
        require(text().contains("cryptoTotalUs=100000"), "actual wrapper merges the matching parsed crypto metadata");

        call(perf,"setLocked",true);
        require(width(resolve(base))==720, "manual lock freezes actually applied tier rather than untried recommendation");
        call(perf,"setLocked",false);
        require(width(resolve(base))==640, "unlock restores next-session recommendation");
        call(perf,"setAutomatic",false);
        require(width(resolve(base))==720, "disabled automatic selection returns original AUTO choice on next connection");
        call(perf,"setAutomatic",true);
        require(width(resolve(base))==640, "explicitly reenabled automatic selection can use saved recommendation");
        for(Object custom : new Object[] {
            settings(1280,720,30,"manual",8,false), settings(640,768,30,"smooth",10,false),
            settings(896,1072,30,"clear",10,false), settings(720,864,60,"auto",10,false),
            settings(720,864,30,"auto",10,true), settings(720,864,30,"auto",8,false)
        }) require(resolve(custom)==custom, "manual modes, scale, high frame rate and HEVC choices stay unchanged");
        require((Boolean)call(perf,"confirmCurrent",true), "explicit user confirmation can mark the current observed tier");
        require(field(perf,"confirmedTier").getInt(perf)==0, "confirmation never marks untried future tier as vehicle-tested");
        end(42);

        begin(43,next);
        seedAndObserve(43,1,100,100,230,100,0);
        require(field(perf,"nextTier").getInt(perf)==2, "separate session can recommend at most one more bounded tier");
        require(width(resolve(base))==560 && height(resolve(base))==672, "second and final lower tier retains portrait proportion");
        require((Boolean)call(perf,"confirmCurrent",true), "second actually used tier can be explicitly confirmed");
        Object secondActive = field(perf,"current").get(perf);
        field(secondActive,"stable").setBoolean(secondActive,true);
        require((Boolean)call(perf,"confirmCurrent",false), "user can explicitly reject a formerly confirmed current tier");
        require(field(perf,"confirmedTier").getInt(perf)==0 && width(resolve(base))==720,
            "explicit rejection restores previous distinct confirmed tier and locks it");
        end(43);
        require(field(perf,"stableTier").getInt(perf)==0, "ending rejected old session cannot revive its local stability");

        call(perf,"reset");
        begin(44,next);
        Object resetActive = field(perf,"current").get(perf);
        field(resetActive,"stable").setBoolean(resetActive,true);
        call(perf,"reset"); end(44);
        require(field(perf,"stableTier").getInt(perf)==-1 && field(perf,"nextTier").getInt(perf)==0,
            "reset suppresses unfinished old-session stability and recommendations");
        require(!(Boolean)call(perf,"isLocked"), "explicit profile reset clears manual performance lock");

        begin(45,base);
        seedAndObserve(45,0,2,1,400,2,0);
        require(field(perf,"nextTier").getInt(perf)==0 && text().contains("STATIC_OR_INSUFFICIENT"),
            "static low-rate map cannot cause a resolution downgrade");
        end(45);
        begin(46,base);
        seedAndObserve(46,0,100,100,400,0,100);
        require(field(perf,"nextTier").getInt(perf)==0, "native backend absence cannot be hidden by lower quality");
        require(text().contains("NATIVE_NOT_VERIFIED"), "native absence still retains bounded anomaly evidence");
        end(46);

        begin(47,base);
        String beforeOld = text();
        call(perf,"observe",46L,window(SystemClock.elapsedRealtime(),100,100,400,0,100,100000));
        call(perf,"noteFailure",46L,"CONTROL_FAILURE");
        require(beforeOld.equals(text()), "stale generation cannot affect current samples or failure journal");
        call(perf,"pipelineDiagnostic",47L,pipeline()+" raw=PRIVATE_SECRET");
        require(field(perf,"pendingPipeline").get(perf)==null, "unrecognized pipeline suffix is rejected");
        end(47);
        call(perf,"noteFailure",48L,"CONNECTION_FAILURE PRIVATE_SECRET");
        call(perf,"noteFailure",48L,"CONNECTION_FAILURE");
        call(perf,"noteFailure",48L,"CONNECTION_FAILURE");
        require(text().contains("event=CONNECTION_FAILURE") && !text().contains("PRIVATE_SECRET"),
            "pre-media connection failures retain fixed stage only, never arbitrary text");

        begin(49,base);
        seedAndObserve(49,0,100,100,230,100,0);
        end(49);
        call(perf,"recordCapabilities","OMX.synthetic.AVC.hw",true);
        call(perf,"recordCapabilities","OMX.second.must.not.replace",false);
        require(text().contains("decoder=OMX.synthetic.AVC.hw") && !text().contains("OMX.second"),
            "static actual-decoder capability is recorded only once per firmware key");
        MemoryPreferences stored = context.preferences("performance-learning-v1");
        require(stored.getString("evidence","").length()<=32768 && stored.getString("failures","").length()<=4096,
            "persistent anomaly and failure journals stay bounded");
        require(beforeMain.equals(context.preferences("receiver-ui").getAll().toString()),
            "performance learning never writes main settings or pairing preferences");
        field(perf,"preferences").set(perf,null);
        initialize(context,"smoke-performance");
        require(width(resolve(base))==640, "new wrapper initialization reloads next-session recommendation from actual preferences API");
        require(text().contains("OMX.synthetic.AVC.hw"), "static capability survives profile reload");
        require(field(perf,"current").get(perf)==null, "restoring memory cannot claim an active connection");

        stored.edit().putString("evidence","PLE1\nPRIVATE_SECRET").putString("failures","PLF1\n1,2,PRIVATE_SECRET").apply();
        field(perf,"preferences").set(perf,null); initialize(context,"smoke-performance");
        require(!text().contains("PRIVATE_SECRET"), "corrupt journal text cannot pass through exported diagnostics");
        stored.edit().putString("nextTier","PRIVATE_SECRET").apply();
        field(perf,"preferences").set(perf,null); initialize(context,"smoke-performance");
        require(width(resolve(base))==720 && !text().contains("PRIVATE_SECRET"),
            "wrong-type profile values fail to the original canvas without throwing into connection");
        field(perf,"preferences").set(perf,null); initialize(context,"smoke-performance");
        require(width(resolve(base))==720, "corrupt optional profile is repairable without clearing main or pairing data");
        require(((String)call(perf,"exportText")).length()<=40960, "learning export has a fixed metadata size bound");

        telemetry();
        steering(context);
        require(sameCounters(cryptoBefore,cryptoCounters()), "metadata learning does not execute or alter actual crypto counters");
        MemoryContext denied = new MemoryContext() {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { throw new SecurityException("PRIVATE_SECRET"); }
        };
        initialize(denied,"smoke-storage-denied");
        require(width(resolve(base))==720 && !text().contains("PRIVATE_SECRET"),
            "unavailable storage does not block original settings or disclose exception message");
        require(context.hardwareCalls==0 && denied.hardwareCalls==0, "fixture and learning invoke no GPS, audio, ROOT, device or location service");
        System.out.println("PASS LearningRuntimeSmoke checks="+checks+
            " actualFinalApkClasses=true syntheticIntervalMetadata=true privateMonotonicStateSeeded=true"+
            " memoryPreferencesOnly=true nextSessionOnly=true noDoubleScale=true staleGenerationRejected=true"+
            " boundedEvidence=true persistenceRoundTrip=true corruptPreferencesSafe=true"+
            " steeringTranslate=true deviceStateUnchanged=true realTouchLatency=not-measured vehicleSmoothness=not-proven");
    }

    private static void seedAndObserve(long id,int tier,long frames,long outputs,long queue,long nativeFrames,long fallback) throws Exception {
        long epoch=SystemClock.elapsedRealtime();
        Object active=field(perf,"current").get(perf);
        Class<?> sessionType=Class.forName(P+"PerformanceLearningPolicy$Session");
        Object session=sessionType.getConstructor(long.class,int.class,int.class).newInstance(epoch-100000,tier,3);
        field(session,"firstVideoAtMs").setLong(session,epoch-90000);
        field(active,"policy").set(active,session);
        call(perf,"activity",true,false,false); call(perf,"touch");
        Object signals=field(perf,"touchSignals").get(perf);
        Object gate=((AtomicReference<?>)field(signals,"state").get(signals)).get();
        field(gate,"changedAtMs").setLong(gate,epoch-100000);
        for(int i=0;i<4;i++) {
            long at=epoch-14500+i*4800;
            call(field(perf,"touchSignals").get(perf),"record",at);
            if(i==3) call(perf,"pipelineDiagnostic",id,pipeline());
            call(perf,"observe",id,window(at,frames,outputs,queue,nativeFrames,fallback,i==3?0:100000));
        }
    }
    private static void touchDoesNotWaitForLearningMonitor() throws Exception {
        call(perf,"activity",true,false,false);
        final Object signals=field(perf,"touchSignals").get(perf);
        final long before=((Number)call(signals,"totalTouches")).longValue();
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicReference<Throwable> failure=new AtomicReference<Throwable>();
        Thread sender=new Thread(new Runnable(){public void run(){
            try {
                call(perf,"activity",false,true,false);
                for(int i=0;i<1000;i++)call(perf,"touch");
                require(((Number)call(signals,"totalTouches")).longValue()==before,
                    "pause metadata immediately refuses touch while the rule monitor remains held");
                call(perf,"activity",true,false,false);
                for(int i=0;i<1000;i++){call(perf,"activity",true,false,false);call(perf,"touch");}
            }
            catch(Throwable error){failure.set(error);}finally{done.countDown();}
        }},"actual-product-touch-lock-regression");
        boolean completed;
        // The same object monitor is held by actual observe/export/persistence methods.
        // Completion must happen before releasing it, not just become fast after it is free.
        synchronized(perf) {sender.start();completed=done.await(5,TimeUnit.SECONDS);}
        sender.join(5000);
        require(completed && failure.get()==null && !sender.isAlive(),
            "actual final APK activity+touch metadata never waits for a held observer/persistence monitor");
        require(!java.lang.reflect.Modifier.isSynchronized(perfType.getDeclaredMethod("touch").getModifiers()),
            "actual product touch entry is not synchronized on the rule owner");
        require(!java.lang.reflect.Modifier.isSynchronized(perfType.getDeclaredMethod("activity",boolean.class,boolean.class,boolean.class).getModifiers()),
            "actual product visibility entry is not synchronized on the rule owner");
        require(((Number)call(signals,"totalTouches")).longValue()==before+1000,
            "eligible touch counter advances exactly after atomic foreground publication");
    }
    private static String pipeline() {
        return "VideoPipeline windowMs=4800 messages=100 frames=100 frameBytes=300000 "+
            "configs=0 emptyMessages=0 emptyFrames=0 otherMessages=0 fpsMilli=20833 readMaxMs=100 "+
            "frameGapMaxMs=200 lastFrameAgeMs=5 cryptoTotalUs=100000 cryptoMaxUs=5000 "+
            "annexBTotalUs=2000 annexBMaxUs=50 handoffTotalUs=3000 handoffMaxUs=80 ended=false";
    }
    private static Object window(long at,long frames,long outputs,long queue,long nativeFrames,long fallback,long total) throws Exception {
        for(Constructor<?> c:windowType.getConstructors()) if(c.getParameterTypes().length==17)
            return c.newInstance(at,4800L,frames,outputs,300000L,queue,0L,0L,0L,nativeFrames,fallback,0L,0L,
                total,total==0?0L:5000L,true,true);
        throw new AssertionError("window constructor");
    }
    private static Object settings(int w,int h,int fps,String mode,int scale,boolean hevc) throws Exception {
        Object defaults=settingsType.getConstructor().newInstance();
        Object[] values=new Object[SETTING_NAMES.length];
        for(int i=0;i<values.length;i++) {
            String key=SETTING_NAMES[i];
            values[i]=call(defaults,"get"+Character.toUpperCase(key.charAt(0))+key.substring(1));
            if(key.equals("width"))values[i]=w;else if(key.equals("height"))values[i]=h;
            else if(key.equals("fps"))values[i]=fps;else if(key.equals("displayMode"))values[i]=mode;
            else if(key.equals("scale"))values[i]=scale;else if(key.equals("hevc"))values[i]=hevc;
        }
        for(Constructor<?> c:settingsType.getConstructors()) if(c.getParameterTypes().length==values.length) return c.newInstance(values);
        throw new AssertionError("settings constructor");
    }
    private static int width(Object o) throws Exception {return ((Number)call(o,"getWidth")).intValue();}
    private static int height(Object o) throws Exception {return ((Number)call(o,"getHeight")).intValue();}
    private static Object resolve(Object o) throws Exception {return call(perf,"resolveSettings",o);}
    private static void initialize(Context c,String id) throws Exception {call(perf,"initialize",c,id,"Synthetic head unit API19");}
    private static void begin(long id,Object s) throws Exception {call(perf,"beginSession",id,s);}
    private static void end(long id) throws Exception {call(perf,"endSession",id);}
    private static String text() throws Exception {return (String)call(perf,"snapshot");}

    private static void telemetry() throws Exception {
        Class<?> type=Class.forName(P+"LearningTelemetry"),counts=Class.forName(P+"LearningTelemetry$Counts");
        Class<?> counters=Class.forName("com.shilapi.xcertplay.airplay.ScreenFrameCrypto$Counters");
        Object o=type.getConstructor().newInstance();
        Constructor<?> cc=counts.getConstructor(long.class,long.class,long.class,long.class,long.class,long.class,long.class);
        Constructor<?> nc=counters.getConstructor(long.class,long.class,long.class,long.class);
        Object zero=cc.newInstance(0L,0L,0L,0L,0L,0L,0L),nzero=nc.newInstance(0L,0L,0L,0L);
        require(call(o,"sample",10000L,zero,nzero,true,true)==null, "first cumulative sample only establishes baseline");
        Object first=call(o,"sample",15000L,cc.newInstance(100L,80L,300000L,1L,2L,3L,230L),
            nc.newInstance(100L,2L,3L,4L),true,true);
        require(((Number)call(first,"getReceivedFrames")).longValue()==100L &&
            ((Number)call(first,"getNativeFrames")).longValue()==100L, "actual interval adapter computes media and native deltas");
        require(((Number)call(first,"getQueueAgeMaxMs")).longValue()==230L, "interval queue maximum is carried once");
        Object second=call(o,"sample",20000L,cc.newInstance(200L,180L,700000L,1L,2L,3L,4L),
            nc.newInstance(200L,2L,3L,4L),true,true);
        require(((Number)call(second,"getInputTimeouts")).longValue()==0L &&
            ((Number)call(second,"getQueueAgeMaxMs")).longValue()==4L, "old cumulative errors and queue peaks are not replayed as new bad windows");
        require(call(o,"sample",25000L,zero,nzero,true,true)==null, "counter reset invalidates interval");
        require(call(o,"sample",50000L,zero,nzero,true,true)==null, "long gap starts a new baseline");
        Class<?> diagnostics=Class.forName(P+"MediaDiagnostics");
        Object d=diagnostics.getConstructor().newInstance();
        call(d,"videoQueueAge",150000000L);
        Object a=callPrefix(d,"learningCounters"),b=callPrefix(d,"learningCounters");
        require(((Number)call(a,"getQueueAgeMs")).longValue()==150L &&
            ((Number)call(b,"getQueueAgeMs")).longValue()==0L, "actual MediaDiagnostics interval peak is consumed and cleared");
        require(((String)call(d,"summary")).contains("队列年龄MaxUs=150000"), "optional interval consumer preserves original cumulative diagnostic maximum");
    }

    private static void steering(MemoryContext c) throws Exception {
        Class<?> type=Class.forName(P+"SteeringLearning"),device=Class.forName(P+"LearningDevice");
        Object wheel=type.getField("INSTANCE").get(null),identity=device.getField("INSTANCE").get(null);
        String key=(String)call(identity,"id");
        MemoryPreferences prefs=c.preferences("receiver-steering-learning");
        require(Integer.valueOf(4).equals(call(wheel,"translate",c,4)) &&
            Integer.valueOf(5).equals(call(wheel,"translate",c,5)), "absent learned mapping preserves established manually-enabled mapping");
        prefs.edit().putString("boundDevice",key).putInt(key+".next",5).putInt(key+".previous",4).apply();
        require(Integer.valueOf(5).equals(call(wheel,"translate",c,4)) &&
            Integer.valueOf(4).equals(call(wheel,"translate",c,5)), "actual saved inverted mapping translates to existing next/previous HID values");
        require(call(wheel,"translate",c,7)==null, "unknown raw key cannot acquire an invented HID action");
        prefs.edit().putString("boundDevice","other-firmware").apply();
        require(call(wheel,"translate",c,4)==null, "mapping from another firmware is not applied");
        prefs.edit().putString("boundDevice",key).putInt(key+".next",4).putInt(key+".previous",4).apply();
        require(call(wheel,"translate",c,4)==null, "ambiguous persisted mapping is rejected");
        call(wheel,"restoreDefault",c);
        require(!c.preferences("receiver-ui").getBoolean("steeringMedia",true) && !prefs.contains("boundDevice"),
            "explicit restore clears learned binding and disables optional forwarding in memory-only preferences");
        require(!(Boolean)call(wheel,"isLearning"), "translation tests never start the real ROOT key reader");
    }

    private static Object cryptoCounters() throws Exception {
        Class<?> type=Class.forName("com.shilapi.xcertplay.airplay.ScreenFrameCrypto");
        return call(type.getField("INSTANCE").get(null),"counters");
    }
    private static boolean sameCounters(Object a,Object b) throws Exception {
        for(String name:new String[]{"getNativeFrames","getFallbackFrames","getAuthRejected","getBackendFailures"})
            if(!call(a,name).equals(call(b,name)))return false;
        return true;
    }
    private static Object callPrefix(Object owner,String prefix) throws Exception {
        for(Method m:owner.getClass().getDeclaredMethods()) if(m.getName().startsWith(prefix)&&m.getParameterTypes().length==0)
            return invoke(m,owner,new Object[0]);
        throw new NoSuchMethodException(prefix);
    }
    private static Object call(Object owner,String name,Object... args) throws Exception {
        for(Method m:owner.getClass().getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterTypes().length==args.length)
            return invoke(m,owner,args);
        throw new NoSuchMethodException(owner.getClass().getName()+"."+name);
    }
    private static Object invoke(Method m,Object owner,Object[] args) throws Exception {
        m.setAccessible(true);
        try{return m.invoke(owner,args);}
        catch(InvocationTargetException wrapped) {
            Throwable t=wrapped.getCause();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;throw wrapped;
        }
    }
    private static Field field(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void require(boolean ok,String label) {checks++;if(!ok)throw new AssertionError(label);}

    private static class MemoryContext extends ContextWrapper {
        final Map<String,MemoryPreferences> stores=new HashMap<String,MemoryPreferences>();
        int hardwareCalls;
        MemoryContext(){super(null);}
        @Override public Context getApplicationContext(){return this;}
        @Override public Resources getResources(){return Resources.getSystem();}
        @Override public Object getSystemService(String name){hardwareCalls++;throw new AssertionError("hardware service forbidden");}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences(name);}
        synchronized MemoryPreferences preferences(String name) {
            MemoryPreferences p=stores.get(name);if(p==null){p=new MemoryPreferences();stores.put(name,p);}return p;
        }
    }
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String,Object> data=new HashMap<String,Object>();
        @Override public synchronized Map<String,?> getAll(){return new HashMap<String,Object>(data);}
        @Override public synchronized String getString(String k,String d){Object o=data.get(k);return o==null?d:(String)o;}
        @SuppressWarnings("unchecked")
        @Override public synchronized Set<String> getStringSet(String k,Set<String> d){Object o=data.get(k);return o==null?d:new HashSet<String>((Set<String>)o);}
        @Override public synchronized int getInt(String k,int d){Object o=data.get(k);return o==null?d:(Integer)o;}
        @Override public synchronized long getLong(String k,long d){Object o=data.get(k);return o==null?d:(Long)o;}
        @Override public synchronized float getFloat(String k,float d){Object o=data.get(k);return o==null?d:(Float)o;}
        @Override public synchronized boolean getBoolean(String k,boolean d){Object o=data.get(k);return o==null?d:(Boolean)o;}
        @Override public synchronized boolean contains(String k){return data.containsKey(k);}
        @Override public Editor edit(){return new MemoryEditor();}
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
        private final class MemoryEditor implements Editor {
            private final Map<String,Object> pending=new HashMap<String,Object>();private boolean clear;
            @Override public Editor putString(String k,String v){pending.put(k,v);return this;}
            @Override public Editor putStringSet(String k,Set<String> v){pending.put(k,v==null?null:new HashSet<String>(v));return this;}
            @Override public Editor putInt(String k,int v){pending.put(k,v);return this;}
            @Override public Editor putLong(String k,long v){pending.put(k,v);return this;}
            @Override public Editor putFloat(String k,float v){pending.put(k,v);return this;}
            @Override public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
            @Override public Editor remove(String k){pending.put(k,null);return this;}
            @Override public Editor clear(){clear=true;return this;}
            @Override public boolean commit(){synchronized(MemoryPreferences.this){if(clear)data.clear();for(Map.Entry<String,Object> e:pending.entrySet()){if(e.getValue()==null)data.remove(e.getKey());else data.put(e.getKey(),e.getValue());}}return true;}
            @Override public void apply(){commit();}
        }
    }
}
