import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.File;

/** Desktop JVM comparison of the exact 0.54/0.55 compiled codecs, not a vehicle FPS benchmark. */
public final class NcmAllocationBenchmark {
    public static void main(String[] args) throws Exception {
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        byte[] packet=new byte[14+40+1440];
        packet[12]=(byte)0x86; packet[13]=(byte)0xdd; packet[14]=0x60;
        packet[18]=(byte)(1440>>8); packet[19]=(byte)1440; packet[20]=6; packet[21]=64;
        packet[6]=2;
        Object[] values={packet,new byte[]{2,0,0,0,0,43},new byte[16]};
        long[] old=measure(args[0],args[2],bean,values);
        long[] current=measure(args[1],args[2],bean,values);
        if(current[0]>=old[0]) throw new AssertionError("ordinary IPv6 allocation did not decrease");
        System.out.println("PASS NcmAllocationBenchmark calls=100000 payloadBytes=1480 oldAllocatedBytes="+old[0]+
            " newAllocatedBytes="+current[0]+" oldCpuNs="+old[1]+" newCpuNs="+current[1]+
            " environment=desktop-JDK11 allocation-only-no-vehicle-fps-claim");
    }
    private static long[] measure(String jar,String kotlin,ThreadMXBean bean,Object[] values) throws Exception {
        try(URLClassLoader loader=new URLClassLoader(new URL[]{new File(jar).toURI().toURL(),new File(kotlin).toURI().toURL()},null)) {
            Class<?> type=loader.loadClass("com.shilapi.xcertplay.transport.EthernetIpv6Codec");
            Object codec=type.getField("INSTANCE").get(null);
            Method method=type.getMethod("neighborAdvertisement",byte[].class,byte[].class,byte[].class);
            for(int i=0;i<20000;i++) if(method.invoke(codec,values)!=null) throw new AssertionError("TCP accepted as NS");
            long thread=Thread.currentThread().getId(),allocated=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime();
            for(int i=0;i<100000;i++) if(method.invoke(codec,values)!=null) throw new AssertionError("TCP accepted as NS");
            return new long[]{bean.getThreadAllocatedBytes(thread)-allocated,bean.getCurrentThreadCpuTime()-cpu};
        }
    }
}
