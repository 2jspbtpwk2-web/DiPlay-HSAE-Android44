import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The final APK's actual LegacyMediaSink receives synthetic, already authenticated LPCM RTP.
 * Its normal audio queue, PCM conversion, buffer and Android AudioTrack worker execute.
 * This test does not exercise UDP, stream-key negotiation, decryption, a phone or OEM routing.
 * It uses null Context to leave AudioManager focus/mode/route/gain untouched. No microphone
 * callback is invoked; the settings passed to the sink explicitly have capture disabled.
 * Synthetic zero PCM is neither saved nor printed. Playback-head progress is not audible proof.
 */
public final class AudioPlaybackRuntimeSmoke {
    private static final String RECEIVER="local.airuize.receiver.";
    private static final String AIRPLAY="com.shilapi.xcertplay.airplay.";
    private static int checks;
    private static Object unit;
    private static final CopyOnWriteArrayList<Throwable> uncaught=new CopyOnWriteArrayList<Throwable>();

    public static void main(String[] args) throws Exception {
        unit=Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        final int initialWorkers=workers();
        Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread,Throwable failure) { uncaught.add(failure); }
        });
        Object sink=null;
        try {
            Object settings=settingsWithoutCapture();
            require(!((Boolean)settings.getClass().getMethod("getMicrophone").invoke(settings)).booleanValue(),
                "actual sink settings have microphone capture disabled");
            sink=construct(Class.forName(RECEIVER+"LegacyMediaSink"),function(1),function(0),720,864,settings,null);
            Object diagnostics=sink.getClass().getMethod("getDiagnostics").invoke(sink);
            Method mediaActive=sink.getClass().getMethod("isMediaPlaybackActive");
            require(!Boolean.TRUE.equals(mediaActive.invoke(sink)),"no stream is not treated as active CarPlay music");
            Object id=construct(Class.forName(AIRPLAY+"AudioStreamId"),100,"media");
            Class<?> codecClass=Class.forName(AIRPLAY+"AudioCodecKind");
            @SuppressWarnings({"unchecked","rawtypes"}) Object lpcm=Enum.valueOf((Class)codecClass,"LPCM");
            Object format=construct(Class.forName(AIRPLAY+"AudioFormat"),lpcm,44100,2,100,"media");
            Method started=sink.getClass().getMethod("onAudioStarted",id.getClass(),format.getClass(),int.class);
            Method rtp=sink.getClass().getMethod("onAudioRtp",id.getClass(),format.getClass(),byte[].class,int.class);
            started.invoke(sink,id,format,0);
            require(!Boolean.TRUE.equals(mediaActive.invoke(sink)),"stream setup without actual playback cannot forward steering buttons");
            Map<?,?> audio=(Map<?,?>)field(sink,"audio").get(sink);
            require(audio.size()==1,"one actual product audio worker is owned");
            Object stream=audio.values().iterator().next();
            final int packets=100,samplesPerPacket=882,payloadBytes=samplesPerPacket*2*2;
            long next=System.nanoTime();
            for(int index=0;index<packets;index++) {
                byte[] packet=new byte[12+payloadBytes];
                packet[0]=(byte)0x80;packet[1]=(byte)100;
                packet[2]=(byte)(index>>>8);packet[3]=(byte)index;
                int sample=index*samplesPerPacket;
                packet[4]=(byte)(sample>>>24);packet[5]=(byte)(sample>>>16);
                packet[6]=(byte)(sample>>>8);packet[7]=(byte)sample;
                rtp.invoke(sink,id,format,packet,sample);
                next+=20_000_000L;
                while(System.nanoTime()<next) {
                    long remaining=next-System.nanoTime();
                    if(remaining<=0) break;
                    Thread.sleep(remaining/1_000_000L,(int)(remaining%1_000_000L));
                }
            }
            long expectedBytes=(long)packets*payloadBytes;
            long deadline=System.nanoTime()+5_000_000_000L;
            while(System.nanoTime()<deadline &&
                (counter(diagnostics,"audioWrittenBytes")<expectedBytes || counter(diagnostics,"audioPlayedFrames")==0)) Thread.sleep(20);
            require(counter(diagnostics,"audioPackets")==packets,"every valid decrypted RTP packet reaches the actual sink");
            require(counter(diagnostics,"audioWrittenBytes")==expectedBytes,"actual AudioTrack writes the complete synthetic PCM stream");
            require(counter(diagnostics,"audioPlayedFrames")>0,"actual product playback-head sampler observes forward progress");
            require(counter(diagnostics,"audioDropped")==0,"paced LPCM playback has no queue overflow");
            require(counter(diagnostics,"audioRtpRejected")==0 && counter(diagnostics,"audioNoStream")==0,"valid stream has no short/no-stream rejection");
            require(counter(diagnostics,"audioErrors")==0 && counter(diagnostics,"audioHeadReadErrors")==0,"AudioTrack and playback sampling complete without errors");
            Object track=field(stream,"track").get(stream);
            require(track!=null,"actual Android AudioTrack is published by product worker");
            require(((Number)track.getClass().getMethod("getState").invoke(track)).intValue()==1,"actual AudioTrack is INITIALIZED");
            require(((Number)track.getClass().getMethod("getPlayState").invoke(track)).intValue()==3,"actual AudioTrack is PLAYING");
            require(((Number)track.getClass().getMethod("getPlaybackHeadPosition").invoke(track)).intValue()>0,"platform playback head advances independently of product counter");
            require(Boolean.TRUE.equals(mediaActive.invoke(sink)),"actual recent MEDIA playback enables the optional steering gate");
            long written=counter(diagnostics,"audioWrittenBytes"),played=counter(diagnostics,"audioPlayedFrames");
            sink.getClass().getMethod("close").invoke(sink);
            sink.getClass().getMethod("close").invoke(sink);
            deadline=System.nanoTime()+4_000_000_000L;
            while(workers()>initialWorkers && System.nanoTime()<deadline) Thread.sleep(10);
            require(workers()==initialWorkers,"audio and video workers exit within close deadline");
            require(((Map<?,?>)field(sink,"audio").get(sink)).isEmpty(),"closed sink owns no audio stream");
            require(!Boolean.TRUE.equals(mediaActive.invoke(sink)),"closed sink cannot enable optional steering forwarding");
            require(field(stream,"track").get(stream)==null,"closed worker clears AudioTrack ownership");
            require(((Number)track.getClass().getMethod("getState").invoke(track)).intValue()==0,"observed Android AudioTrack is released");
            started.invoke(sink,id,format,100*samplesPerPacket);
            rtp.invoke(sink,id,format,new byte[12+payloadBytes],100*samplesPerPacket);
            Thread.sleep(100);
            require(((Map<?,?>)field(sink,"audio").get(sink)).isEmpty() && workers()==initialWorkers,"late start/RTP cannot resurrect a closed sink");
            require(counter(diagnostics,"audioWrittenBytes")==written,"late RTP after close writes no audio");
            require(((Map<?,?>)field(sink,"microphones").get(sink)).isEmpty(),"playback never opens a microphone flow");
            require(counter(diagnostics,"microphoneReadBytes")==0 && counter(diagnostics,"microphoneSentPackets")==0,"playback test never captures or uploads microphone audio");
            require(uncaught.isEmpty(),"no uncaught product worker error");
            System.out.println("PASS AudioPlaybackRuntimeSmoke checks="+checks+
                " actualFinalApkSink=true realAudioTrack=true pcmRtp=true writeProgress=true playbackHeadProgress=true"+
                " packets="+packets+" writtenBytes="+written+" playedFrames="+played+
                " microphone=not-opened udpAuth=not-covered phone-audio=not-proven OEM-route=not-proven");
        } finally {
            if(sink!=null) sink.getClass().getMethod("close").invoke(sink);
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static Object settingsWithoutCapture() throws Exception {
        Class<?> type=Class.forName(RECEIVER+"ReceiverSettings");
        Object defaults=type.getConstructor().newInstance();
        for(Constructor<?> constructor:type.getDeclaredConstructors()) {
            Class<?>[] parameters=constructor.getParameterTypes();
            if(parameters.length!=34) continue;
            Object[] values=new Object[parameters.length];
            for(int index=0;index<values.length;index++) values[index]=type.getMethod("component"+(index+1)).invoke(defaults);
            values[18]=Boolean.FALSE; // ReceiverSettings primary constructor's nineteenth value is microphone.
            constructor.setAccessible(true);return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("ReceiverSettings primary constructor expected 34 values");
    }

    private static Object function(int arity) throws Exception {
        Class<?> type=Class.forName("kotlin.jvm.functions.Function"+arity);
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},new InvocationHandler() {
            @Override public Object invoke(Object proxy,Method method,Object[] args) {
                if(method.getDeclaringClass()==Object.class) {
                    if("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
                    if("equals".equals(method.getName())) return Boolean.valueOf(args!=null && args.length==1 && proxy==args[0]);
                    return "AudioPlaybackMetadataDiscardingCallback";
                }
                return unit;
            }
        });
    }
    private static Object construct(Class<?> type,Object... args) throws Exception {
        for(Constructor<?> constructor:type.getDeclaredConstructors()) {
            Class<?>[] parameters=constructor.getParameterTypes();
            if(parameters.length!=args.length || parameters.length>0 &&
                "kotlin.jvm.internal.DefaultConstructorMarker".equals(parameters[parameters.length-1].getName())) continue;
            constructor.setAccessible(true);return constructor.newInstance(args);
        }
        throw new NoSuchMethodException(type.getName()+" arguments="+args.length);
    }
    private static Field field(Object owner,String name) throws Exception {
        Field result=owner.getClass().getDeclaredField(name);result.setAccessible(true);return result;
    }
    private static long counter(Object diagnostics,String name) throws Exception {
        return ((AtomicLong)diagnostics.getClass().getMethod("get"+Character.toUpperCase(name.charAt(0))+name.substring(1)).invoke(diagnostics)).get();
    }
    private static int workers() {
        int count=0;
        for(Thread thread:Thread.getAllStackTraces().keySet()) if(thread.isAlive() &&
            ("legacy-audio".equals(thread.getName()) || "legacy-avc".equals(thread.getName()) || "legacy-microphone".equals(thread.getName()))) count++;
        return count;
    }
    private static void require(boolean condition,String label) {
        checks++;if(!condition) throw new AssertionError(label);
    }
}
