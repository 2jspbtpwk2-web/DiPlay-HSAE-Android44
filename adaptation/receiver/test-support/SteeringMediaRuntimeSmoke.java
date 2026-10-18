import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;

/** Actual final APK sendMedia HID commands over synthetic encrypted event sockets. No phone/OEM/ROOT. */
public final class SteeringMediaRuntimeSmoke {
    private static int checks;
    public static void main(String[] args) throws Exception {
        unavailableReturnsFalse();
        failedWriteReturnsFalse(1);failedWriteReturnsFalse(2);
        Class<?> helper=Class.forName("FunctionDiagnosticsRuntimeSmoke");
        Class<?> fixtureClass=Class.forName("FunctionDiagnosticsRuntimeSmoke$Fixture");
        Class<?> pairClass=Class.forName("FunctionDiagnosticsRuntimeSmoke$SocketPair");
        Object fixture=create(fixtureClass),events=create(pairClass);
        Object session=field(fixture,"session").get(fixture);
        Socket client=(Socket)field(events,"client").get(events),accepted=(Socket)field(events,"accepted").get(events);
        byte[] read=key(17),write=key(83);
        try {
            Class<?> cipher=Class.forName("com.shilapi.xcertplay.airplay.ControlCipher");
            Object local=cipher.getConstructor(byte[].class,byte[].class).newInstance(read,write);
            Object peer=cipher.getConstructor(byte[].class,byte[].class).newInstance(write,read);
            field(session,"eventSocket").set(session,accepted);field(session,"eventCipher").set(session,local);
            Method decrypt=helper.getDeclaredMethod("decryptFrame",Object.class,InputStream.class);decrypt.setAccessible(true);
            Method send=session.getClass().getMethod("sendMedia",int.class);
            require(send.getReturnType()==boolean.class,"prepared sendMedia exposes actual press/release result");
            require(Boolean.TRUE.equals(send.invoke(session,4)),"next reports both real socket writes succeeded");
            require(Boolean.TRUE.equals(send.invoke(session,5)),"previous reports both real socket writes succeeded");
            for(int expected:new int[]{4,0,5,0}) {
                byte[] plain=(byte[])decrypt.invoke(null,peer,client.getInputStream());
                Map<?,?> command=decode(plain);
                require("hidSendReport".equals(command.get("type")),"actual media action uses HID command");
                require("2a2a2a2c".equals(command.get("uuid")),"actual media HID device matches advertised UID");
                byte[] report=(byte[])command.get("hidReport");
                require(report.length==1 && (report[0]&255)==expected,"one press followed by exactly one zero release");
            }
            require(field(session,"eventCseq").getInt(session)==4,"two momentary track buttons consume exactly four event writes");
            client.setSoTimeout(200);
            try { require(client.getInputStream().read()==-1,"no fifth HID report"); } catch(SocketTimeoutException expected) { checks++; }
            invoke(fixture,"closeSession");require(Boolean.FALSE.equals(send.invoke(session,4)),"closed old session reports false rather than successful send");
            require(field(session,"eventCseq").getInt(session)==4,"closed previous session cannot send a delayed track action");
            System.out.println("PASS SteeringMediaRuntimeSmoke checks="+checks+
                " actualFinalApkHID=true encryptedSyntheticSocket=true nextPressRelease=4,0 previousPressRelease=5,0"+
                " duplicateWrites=false writeFailuresVerified=true unavailableReturnsFalse=true rootLogReader=not-covered vehicleKeys=not-proven phone-track-change=not-proven");
        } finally {
            Arrays.fill(read,(byte)0);Arrays.fill(write,(byte)0);invoke(events,"close");invoke(fixture,"close");
        }
    }
    private static void unavailableReturnsFalse() throws Exception {
        Object fixture=create(Class.forName("FunctionDiagnosticsRuntimeSmoke$Fixture"));
        try {
            Object session=field(fixture,"session").get(fixture);
            require(Boolean.FALSE.equals(session.getClass().getMethod("sendMedia",int.class).invoke(session,4)),"unready channel reports false");
            require(field(session,"eventCseq").getInt(session)==0,"unready channel sends no HID command");
        } finally { invoke(fixture,"close"); }
    }
    private static void failedWriteReturnsFalse(int failAt) throws Exception {
        Object fixture=create(Class.forName("FunctionDiagnosticsRuntimeSmoke$Fixture"));
        Class<?> type=Class.forName("FunctionDiagnosticsRuntimeSmoke$FailingEventSocket");
        Constructor<?> constructor=type.getDeclaredConstructor(int.class);constructor.setAccessible(true);
        Object socket=constructor.newInstance(failAt);
        try {
            Object session=field(fixture,"session").get(fixture);
            Class<?> cipher=Class.forName("com.shilapi.xcertplay.airplay.ControlCipher");
            field(session,"eventSocket").set(session,socket);
            field(session,"eventCipher").set(session,cipher.getConstructor(byte[].class,byte[].class).newInstance(key(7),key(71)));
            require(Boolean.FALSE.equals(session.getClass().getMethod("sendMedia",int.class).invoke(session,4)),"write failure at action "+failAt+" reports false");
            require(field(socket,"writes").getInt(socket)==failAt,"write failure does not replay a press or release");
            require(field(socket,"closed").getBoolean(socket),"failed media write closes only the owned event socket");
            require(Boolean.FALSE.equals(session.getClass().getMethod("sendMedia",int.class).invoke(session,4)),"failed session rejects later track action");
            require(field(socket,"writes").getInt(socket)==failAt,"closed failed session does not issue extra HID");
        } finally { invoke(fixture,"close"); }
    }
    private static Map<?,?> decode(byte[] data) throws Exception {
        int start=-1;
        for(int i=0;i+3<data.length;i++) if(data[i]==13&&data[i+1]==10&&data[i+2]==13&&data[i+3]==10) {start=i+4;break;}
        require(start>0,"decrypted command has RTSP header boundary");
        Class<?> codec=Class.forName("com.shilapi.xcertplay.airplay.BplistCodec");
        return (Map<?,?>)codec.getMethod("decode",byte[].class).invoke(codec.getField("INSTANCE").get(null),(Object)Arrays.copyOfRange(data,start,data.length));
    }
    private static Object create(Class<?> type) throws Exception { Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);return c.newInstance(); }
    private static Field field(Object value,String name) throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static void invoke(Object value,String method) throws Exception { Method m=value.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(value); }
    private static byte[] key(int first) {byte[] value=new byte[32];for(int i=0;i<32;i++)value[i]=(byte)(first+i);return value;}
    private static void require(boolean value,String label) {checks++;if(!value)throw new AssertionError(label);}
}
