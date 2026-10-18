import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Final APK: actual authenticated UDP audio -> independent native bridge -> sink queue -> AudioTrack.
 * Synthetic silence only. No phone, microphone, hardware audio route or ARM timing is claimed. */
public final class AudioContinuityRuntimeSmoke {
    private static final String A="com.shilapi.xcertplay.airplay.", R="local.airuize.receiver.";
    private static int checks;
    private static Object unit,bc,sink,id,format,bridge;
    private static Method seal,rtp,started;
    private static byte[] key=new byte[32];
    private static final AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();
    private static final List<Throwable> failures=new CopyOnWriteArrayList<Throwable>();
    private static final List<String> bufferPlans=new CopyOnWriteArrayList<String>();

    public static void main(String[] args) throws Exception {
        require(args.length==1,"native path must be extracted from exact final APK");
        unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Class<?> bcType=Class.forName(A+"AirPlayCrypto");bc=bcType.getField("INSTANCE").get(null);
        seal=bcType.getMethod("chachaSeal",byte[].class,byte[].class,byte[].class,byte[].class);
        for(int i=0;i<key.length;i++) key[i]=(byte)(i*7+3);
        System.load(args[0]);
        Class<?> loader=Class.forName(R+"ScreenCryptoNative");Object loaderInstance=loader.getField("INSTANCE").get(null);
        for(String name:new String[]{"checkSelfTest","checkAudioSelfTest"}) {
            Method self=loader.getDeclaredMethod(name);self.setAccessible(true);self.invoke(loaderInstance);checks++;
        }
        Class<?> bridgeType=Class.forName(A+"AudioFrameCrypto");bridge=bridgeType.getField("INSTANCE").get(null);
        Class<?> accelerator=Class.forName(A+"AudioFrameCrypto$Accelerator");
        Class<?> actualAdapter=Class.forName(R+"ScreenCryptoNative$AudioNativeAccelerator");
        Field instance=actualAdapter.getDeclaredField("INSTANCE");instance.setAccessible(true);
        bridgeType.getMethod("install",accelerator).invoke(bridge,instance.get(null));
        Object screen=Class.forName(A+"ScreenFrameCrypto").getField("INSTANCE").get(null);
        Method screenStats=screen.getClass().getMethod("diagnosticSnapshot");
        String screenBefore=(String)screenStats.invoke(screen);
        final int workersBefore=workers();
        Object stream=null;
        DatagramSocket sender=new DatagramSocket();
        try {
            sink=construct(Class.forName(R+"LegacyMediaSink"),function(1),function(0),720,864,settings(),null);
            id=construct(Class.forName(A+"AudioStreamId"),100,"media");
            Class<?> kind=Class.forName(A+"AudioCodecKind");
            @SuppressWarnings({"unchecked","rawtypes"}) Object pcm=Enum.valueOf((Class)kind,"LPCM");
            format=construct(Class.forName(A+"AudioFormat"),pcm,44100,2,100,"media");
            started=sink.getClass().getMethod("onAudioStarted",id.getClass(),format.getClass(),int.class);
            rtp=sink.getClass().getMethod("onAudioRtp",id.getClass(),format.getClass(),byte[].class,int.class);
            stream=construct(Class.forName(A+"AudioStream"),key,100,function(1),InetAddress.getByName("127.0.0.1"));
            int port=listen(stream,true);
            long next=System.nanoTime();
            for(int index=0;index<220;index++) {
                if(index!=50) send(sender,port,wire(index,false)); // one known 330-frame PCM gap
                if(index==100) {send(sender,port,wire(100,false));send(sender,port,wire(99,false));}
                if(index==150) {
                    send(sender,port,wire(1000,true)); // tag
                    byte[] bad=wire(1001,false);bad[4]^=1;send(sender,port,bad); // authenticated timestamp/AAD
                    bad=wire(1002,false);bad[bad.length-1]^=1;send(sender,port,bad); // nonce
                    bad=wire(1003,false);bad[12]^=1;send(sender,port,bad); // ciphertext
                }
                if(index<120) {
                    next+=7_483_000L;
                    while(System.nanoTime()<next) {long left=next-System.nanoTime();if(left>0) Thread.sleep(left/1000000L,(int)(left%1000000L));}
                }
            }
            long deadline=System.nanoTime()+8_000_000_000L;
            Object diagnostics=sink.getClass().getMethod("getDiagnostics").invoke(sink);
            long expected=220L*1320L;
            while(System.nanoTime()<deadline && (accepted.get()<221 || counter(diagnostics,"audioWrittenBytes")<expected)) Thread.sleep(20);
            require(failures.isEmpty(),"all actual UDP listener callbacks complete without worker error");
            require(accepted.get()==221 && rejected.get()==4,"native RTP receives221 authenticated packets and rejects tag/AAD/nonce/ciphertext tampering");
            require(counter(diagnostics,"audioPackets")==221,"authenticated UDP forwards to actual sink");
            require(counter(diagnostics,"audioLateOrDuplicate")==2,"duplicate and late PCM are rejected rather than replayed");
            require(counter(diagnostics,"audioDropped")==0,"100-packet short PCM burst fits the bounded duration queue");
            require(counter(diagnostics,"audioConcealedFrames")==330,"exact one short timestamp gap gets explicit bounded silence");
            require(counter(diagnostics,"audioWrittenBytes")==expected,"complete authenticated PCM plus exact gap padding reaches AudioTrack");
            require(counter(diagnostics,"audioErrors")==0,"no product AudioTrack/decoder exception");
            deadline=System.nanoTime()+3_000_000_000L;
            while(System.nanoTime()<deadline && counter(diagnostics,"audioPlayedFrames")<44100) Thread.sleep(20);
            require(counter(diagnostics,"audioPlayedFrames")>=44100,"platform playback head advances through at least one media second");
            String metadata=(String)sink.getClass().getMethod("audioDiagnosticSummary").invoke(sink);
            require(metadata.length()<=8192 && metadata.contains("接收序号缺包=1") && metadata.contains("迟到或重复拒绝=2"),"bounded brief contains separate source loss and duplicate metadata");
            require(metadata.contains("AudioCrypto engine=native-rfc8439") && metadata.contains("authRejected=4"),"brief exposes actual independent native audio use and rejection");
            require(metadata.contains("音频待播估算：软件队列=") && metadata.contains("非端到端测量"),"software queue and AudioTrack delay are explicitly local estimates");
            require(bufferPlans.size()==1,"actual product emits one startup buffer plan");
            java.util.regex.Matcher plan=java.util.regex.Pattern.compile("Audio buffer requested=(\\d+) actual=(-?\\d+) start=(\\d+) mediaMs=300").matcher(bufferPlans.get(0));
            require(plan.find(),"actual worker exposes granted capacity and unchanged300ms preference");
            int requested=Integer.parseInt(plan.group(1)),capacity=Integer.parseInt(plan.group(2)),start=Integer.parseInt(plan.group(3));
            require(start>=52920 && requested>=start+4096,"actual requested media capacity retains the full300ms startup and safe write headroom");
            if(capacity>0) require(start<capacity,"actual granted AudioTrack capacity remains above startup threshold");
            require(screenBefore.equals(screenStats.invoke(screen)),"audio does not contaminate screen-only learning counters/backend");
            stream.getClass().getMethod("close").invoke(stream);stream=null;
            sink.getClass().getMethod("close").invoke(sink);sink.getClass().getMethod("close").invoke(sink);
            deadline=System.nanoTime()+4_000_000_000L;
            while(System.nanoTime()<deadline && workers()>workersBefore) Thread.sleep(20);
            require(workers()==workersBefore,"normal close releases product audio/video workers");
            require(((Map<?,?>)field(sink,"microphones").get(sink)).isEmpty(),"playback never opens a microphone stream");
            require(counter(diagnostics,"microphoneReadBytes")==0,"no microphone PCM captured");
            diagnosticSnapshotLockIsolation();
            failedOwnerCannotReceiveOrCloseReplacement();
            // The actual bridge disables only the unavailable audio backend, then uses original BC.
            Object missing=Proxy.newProxyInstance(accelerator.getClassLoader(),new Class<?>[]{accelerator},new InvocationHandler(){
                public Object invoke(Object p,Method m,Object[] a) {if(m.getName().equals("open")) throw new UnsatisfiedLinkError("synthetic unavailable backend");return null;}
            });
            bridgeType.getMethod("install",accelerator).invoke(bridge,missing);
            accepted.set(0);rejected.set(0);
            stream=construct(Class.forName(A+"AudioStream"),key,100,function(1),InetAddress.getByName("127.0.0.1"));
            port=listen(stream,false);
            send(sender,port,wire(300,false));send(sender,port,wire(301,false));send(sender,port,wire(302,true));
            deadline=System.nanoTime()+3_000_000_000L;
            while(System.nanoTime()<deadline && accepted.get()+rejected.get()<3) Thread.sleep(20);
            require(accepted.get()==2 && rejected.get()==1,"actual BC fallback authenticates good RTP and rejects bad tag");
            String snapshot=(String)bridgeType.getMethod("diagnosticSnapshot").invoke(bridge);
            require(snapshot.contains("engine=bouncycastle") && snapshot.contains("backendFailures=1") && snapshot.contains("fallbackFrames=3"),"one unavailable backend is disabled and current packet also falls back");
            require(screenBefore.equals(screenStats.invoke(screen)),"audio backend failure leaves screen behavior unchanged");
            require(failures.isEmpty(),"no late callback error");
            System.out.println("PASS AudioContinuityRuntimeSmoke checks="+checks+
                " actualFinalApk=true authenticatedUDP=true nativeAudio=true actualSinkTrack=true burst=true gap330=true duplicateReject=true badTagReject=true fallback=true snapshotLockIsolation=true failedOwnerRetired=true preserveCompletePcm=true unchangedStartup300ms=true microphone=not-opened vehicleAudio=not-proven ARM-timing=not-measured");
        } finally {
            if(stream!=null) stream.getClass().getMethod("close").invoke(stream);
            if(sink!=null) sink.getClass().getMethod("close").invoke(sink);
            sender.close();
        }
    }

    private static void diagnosticSnapshotLockIsolation() throws Exception {
        final int before=workers();
        final Object local=construct(Class.forName(R+"LegacyMediaSink"),function(1),function(0),720,864,settings(),null);
        Thread formatter=null,ingress=null;
        try {
            local.getClass().getMethod("onAudioStarted",id.getClass(),format.getClass(),int.class).invoke(local,id,format,0);
            Object owner=((Map<?,?>)field(local,"audio").get(local)).values().iterator().next();
            final Object continuity=field(owner,"continuity").get(owner);
            final java.util.concurrent.atomic.AtomicReference<Object> result=new java.util.concurrent.atomic.AtomicReference<Object>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            final java.util.concurrent.CountDownLatch began=new java.util.concurrent.CountDownLatch(1),done=new java.util.concurrent.CountDownLatch(1);
            final Object unknown=construct(Class.forName(A+"AudioStreamId"),999,"media");
            formatter=new Thread(new Runnable(){public void run(){began.countDown();try{result.set(local.getClass().getMethod("diagnosticSummaries").invoke(local));}catch(Throwable error){failure.set(error);}}},"test-audio-summary-blocked");
            ingress=new Thread(new Runnable(){public void run(){try{local.getClass().getMethod("onAudioRtp",id.getClass(),format.getClass(),byte[].class,int.class).invoke(local,unknown,format,new byte[1332],0);}catch(Throwable error){failure.set(error);}finally{done.countDown();}}},"test-audio-ingress-unblocked");
            synchronized(continuity) {
                formatter.start();require(began.await(2,java.util.concurrent.TimeUnit.SECONDS),"summary thread starts under controlled continuity contention");
                long deadline=System.nanoTime()+3_000_000_000L;
                while(formatter.getState()!=Thread.State.BLOCKED && System.nanoTime()<deadline) Thread.sleep(5);
                require(formatter.getState()==Thread.State.BLOCKED,"actual summary is blocked by held continuity monitor");
                ingress.start();require(done.await(2,java.util.concurrent.TimeUnit.SECONDS),"unknown-stream RTP completes before summary monitor is unlocked");
                Object diagnostics=local.getClass().getMethod("getDiagnostics").invoke(local);
                require(counter(diagnostics,"audioNoStream")==1,"unblocked actual ingress accounts unknown stream independently");
            }
            formatter.join(3000);ingress.join(3000);
            require(!formatter.isAlive() && !ingress.isAlive() && failure.get()==null,"snapshot and ingress complete after controlled contention ends");
            Object pair=result.get();String full=(String)pair.getClass().getMethod("getFirst").invoke(pair),brief=(String)pair.getClass().getMethod("getSecond").invoke(pair);
            int at=brief.indexOf("音频通道统计：");require(at>=0 && full.contains(brief.substring(at)),"full and brief reuse exactly the same frozen audio-summary text");
            final java.util.concurrent.CountDownLatch queried=new java.util.concurrent.CountDownLatch(1);
            Thread reader=new Thread(new Runnable(){public void run(){try{local.getClass().getMethod("isMediaPlaybackActive").invoke(local);local.getClass().getMethod("isVoicePlaybackActive").invoke(local);}catch(Throwable error){failure.set(error);}finally{queried.countDown();}}},"test-audio-readers-unblocked");
            synchronized(local) {reader.start();require(queried.await(2,java.util.concurrent.TimeUnit.SECONDS),"UI media/voice getters return while sink ingress monitor is held");}
            reader.join(3000);require(failure.get()==null,"lock-free published owner readers complete normally");
        } finally {local.getClass().getMethod("close").invoke(local);if(formatter!=null)formatter.join(3000);if(ingress!=null)ingress.join(3000);}
        long deadline=System.nanoTime()+4_000_000_000L;while(workers()>before && System.nanoTime()<deadline)Thread.sleep(10);
        require(workers()<=before,"controlled snapshot scenario releases actual stream and stop helper");
    }

    private static void failedOwnerCannotReceiveOrCloseReplacement() throws Exception {
        final int before=workers();
        Object local=construct(Class.forName(R+"LegacyMediaSink"),function(1),function(0),720,864,settings(),null);
        try {
            Object invalid=construct(format.getClass(),format.getClass().getMethod("getCodec").invoke(format),44100,3,100,"media");
            Method begin=local.getClass().getMethod("onAudioStarted",id.getClass(),format.getClass(),int.class);
            begin.invoke(local,id,invalid,0);
            Object failed=((Map<?,?>)field(local,"audio").get(local)).get(id);
            Thread worker=(Thread)field(failed,"worker").get(failed);worker.join(3000);
            require(!worker.isAlive(),"invalid output format ends the actual asynchronous audio worker");
            Object localDiagnostics=local.getClass().getMethod("getDiagnostics").invoke(local);
            require(counter(localDiagnostics,"audioErrors")==1,"asynchronous format failure is recorded once");
            require(((java.util.concurrent.atomic.AtomicBoolean)field(failed,"stopped").get(failed)).get(),"failed owner marks itself stopped without requiring later session teardown");
            Object queue=field(failed,"queue").get(failed);
            require(Boolean.TRUE.equals(field(queue,"closed").get(queue)),"failed owner closes its bounded queue");
            require(field(failed,"track").get(failed)==null && field(failed,"lastWriteNs").getLong(failed)==Long.MIN_VALUE,"failed owner exposes no track and no stale recent-write activity");
            long received=((AtomicLong)field(failed,"received").get(failed)).get();
            local.getClass().getMethod("onAudioRtp",id.getClass(),format.getClass(),byte[].class,int.class).invoke(local,id,invalid,new byte[1332],0);
            require(((AtomicLong)field(failed,"received").get(failed)).get()==received,"late RTP cannot refill an asynchronously failed owner");
            begin.invoke(local,id,format,0);
            Object replacement=((Map<?,?>)field(local,"audio").get(local)).get(id);
            require(replacement!=failed,"same stream id can own a fresh valid receiver after failure");
            Method close=failed.getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(failed);
            long deadline=System.nanoTime()+3_000_000_000L;
            while(field(replacement,"track").get(replacement)==null && System.nanoTime()<deadline) Thread.sleep(10);
            require(field(replacement,"track").get(replacement)!=null && ((Map<?,?>)field(local,"audio").get(local)).get(id)==replacement,"late old-owner close cannot release or replace new stream resources");
            require(!((java.util.concurrent.atomic.AtomicBoolean)field(replacement,"stopped").get(replacement)).get(),"replacement remains live after old cleanup");
            final Object receiver=local;
            final java.util.concurrent.CountDownLatch closeReturned=new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicReference<Throwable> closeFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread request=new Thread(new Runnable(){public void run(){try{receiver.getClass().getMethod("onAudioStopped",id.getClass()).invoke(receiver,id);}catch(Throwable error){closeFailure.set(error);}finally{closeReturned.countDown();}}},"test-audio-close-nonblocking");
            Object oldTrack=field(replacement,"track").get(replacement);
            synchronized(field(replacement,"resourceLock").get(replacement)) {
                request.start();require(closeReturned.await(2,java.util.concurrent.TimeUnit.SECONDS),"close signals without waiting for held native-resource cleanup lock");
                Method play=replacement.getClass().getDeclaredMethod("playIfOpen",Class.forName("android.media.AudioTrack"));play.setAccessible(true);
                require(Boolean.FALSE.equals(play.invoke(replacement,oldTrack)),"simulated late positive-write continuation cannot restart the actual closed owner Track");
                Method acquire=replacement.getClass().getDeclaredMethod("acquireIfOpen",Class.forName("android.media.AudioTrack"));acquire.setAccessible(true);
                require(Boolean.FALSE.equals(acquire.invoke(replacement,oldTrack)),"late initialization cannot reacquire focus for an ended owner");
            }
            request.join(3000);require(!request.isAlive() && closeFailure.get()==null,"native cleanup request completes without old/new resource confusion");
            Thread closedWorker=(Thread)field(replacement,"worker").get(replacement);closedWorker.join(3000);
            require(!closedWorker.isAlive() && field(replacement,"track").get(replacement)==null && ((Number)oldTrack.getClass().getMethod("getState").invoke(oldTrack)).intValue()==0,"real stopped Track is released after the controlled late-write gate check");
        } finally {local.getClass().getMethod("close").invoke(local);}
        long deadline=System.nanoTime()+4_000_000_000L;while(workers()>before && System.nanoTime()<deadline)Thread.sleep(10);
        require(workers()<=before,"failed/replacement audio cleanup releases all own stop helpers and workers");
    }

    private static int listen(Object stream,final boolean playback) throws Exception {
        Class<?> listener=Class.forName(A+"AudioStream$Listener");
        Object callbacks=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},new InvocationHandler(){
            public Object invoke(Object p,Method m,Object[] a) {
                try {
                    if(m.getName().equals("onStarted") && playback) started.invoke(sink,id,format,a[0]);
                    else if(m.getName().equals("onRtp")) {if(playback) rtp.invoke(sink,id,format,a[0],a[1]);accepted.incrementAndGet();}
                    else if(m.getName().equals("onPacket") && a[3]!=null) rejected.incrementAndGet();
                } catch(Throwable error) {failures.add(error);}
                return null;
            }
        });
        Object pair=stream.getClass().getMethod("listen",listener).invoke(stream,callbacks);
        return ((Number)pair.getClass().getMethod("getFirst").invoke(pair)).intValue();
    }
    private static byte[] wire(int index,boolean bad) throws Exception {
        byte[] header=new byte[12];header[0]=(byte)0x80;header[1]=100;header[2]=(byte)(index>>>8);header[3]=(byte)index;
        int sample=index*330;header[4]=(byte)(sample>>>24);header[5]=(byte)(sample>>>16);header[6]=(byte)(sample>>>8);header[7]=(byte)sample;header[11]=1;
        byte[] nonce=new byte[12];long counter=index+10000L;for(int i=4;i<12;i++){nonce[i]=(byte)counter;counter>>>=8;}
        byte[] aad=Arrays.copyOfRange(header,4,12),plain=new byte[1320];
        byte[] sealed=(byte[])seal.invoke(bc,key,nonce,plain,aad);if(bad) sealed[sealed.length-1]^=1;
        byte[] wire=new byte[12+sealed.length+8];System.arraycopy(header,0,wire,0,12);System.arraycopy(sealed,0,wire,12,sealed.length);System.arraycopy(nonce,4,wire,12+sealed.length,8);return wire;
    }
    private static void send(DatagramSocket sender,int port,byte[] bytes) throws Exception {sender.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("127.0.0.1"),port));}
    private static Object settings() throws Exception {
        Class<?> type=Class.forName(R+"ReceiverSettings");Object defaults=type.getConstructor().newInstance();
        for(Constructor<?> c:type.getDeclaredConstructors()) if(c.getParameterTypes().length==34) {
            Object[] values=new Object[34];for(int i=0;i<34;i++) values[i]=type.getMethod("component"+(i+1)).invoke(defaults);
            values[18]=Boolean.FALSE;c.setAccessible(true);return c.newInstance(values);
        }throw new NoSuchMethodException("settings constructor");
    }
    private static Object function(int arity) throws Exception {
        Class<?> function=Class.forName("kotlin.jvm.functions.Function"+arity);
        return Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},new InvocationHandler(){public Object invoke(Object p,Method m,Object[] a){if(m.getName().equals("invoke") && a!=null && a.length==1 && a[0] instanceof String && ((String)a[0]).startsWith("Audio buffer ")) bufferPlans.add((String)a[0]);return unit;}});
    }
    private static Object construct(Class<?> type,Object... args) throws Exception {
        for(Constructor<?> c:type.getDeclaredConstructors()) if(c.getParameterTypes().length==args.length) {c.setAccessible(true);return c.newInstance(args);}
        throw new NoSuchMethodException(type.getName()+" arity="+args.length);
    }
    private static Field field(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static long counter(Object object,String name) throws Exception {return ((AtomicLong)object.getClass().getMethod("get"+Character.toUpperCase(name.charAt(0))+name.substring(1)).invoke(object)).get();}
    private static int workers() {int count=0;for(Thread t:Thread.getAllStackTraces().keySet()) if(t.isAlive() && ("legacy-audio".equals(t.getName()) || "legacy-audio-stop".equals(t.getName()) || "legacy-avc".equals(t.getName()))) count++;return count;}
    private static void require(boolean ok,String text) {checks++;if(!ok) throw new AssertionError(text);}
}
