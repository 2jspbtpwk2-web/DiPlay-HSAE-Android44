import javax.jmdns.ServiceInfo;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercise the reflection path after shrinking, not just unprocessed JmDNS. */
public final class CheckMdnsStartup {
    public static void main(String[] args) {
        ServiceInfo.Fields[] fields = ServiceInfo.Fields.class.getEnumConstants();
        if (fields == null || fields.length != 5) throw new AssertionError("EnumMap reflection lost enum constants");
        Map<String, String> text = new LinkedHashMap<>();
        text.put("deviceid", "02:00:00:00:00:01");
        text.put("features", "0x44540380,0x61");
        text.put("srcvers", "740.11");
        ServiceInfo service = ServiceInfo.create("_airplay._tcp.local.", "DiPlay Legacy", 7000, 0, 0, text);
        if (!"DiPlay Legacy._airplay._tcp.local.".equals(service.getQualifiedName())) throw new AssertionError("Invalid service name");
        if (service.getPort() != 7000) throw new AssertionError("Invalid port");
        Map<String, String> decoded = new LinkedHashMap<>();
        byte[] wire = service.getTextBytes();
        for (int cursor = 0; cursor < wire.length;) {
            int length = wire[cursor++] & 255;
            if (length == 0 || cursor + length > wire.length) throw new AssertionError("Invalid TXT wire length");
            String entry = new String(wire, cursor, length, java.nio.charset.Charset.forName("UTF-8"));
            int equals = entry.indexOf('=');
            if (equals < 1) throw new AssertionError("Invalid TXT entry");
            decoded.put(entry.substring(0, equals), entry.substring(equals + 1));
            cursor += length;
        }
        if (!decoded.equals(text)) throw new AssertionError("TXT roundtrip failed");
        System.out.println("PASS: post-shrink EnumMap reflection and AirPlay mDNS service/TXT creation");
    }
}
