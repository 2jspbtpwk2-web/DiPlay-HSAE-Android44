package local.airuize.receiver;
import java.nio.charset.Charset;
/** Public fields match Java's newer StandardCharsets without requiring API19. */
public final class LegacyCharsetFields {
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset US_ASCII = Charset.forName("US-ASCII");
    public static final Charset UTF_16 = Charset.forName("UTF-16");
    public static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    public static final Charset UTF_16LE = Charset.forName("UTF-16LE");
    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    private LegacyCharsetFields() {}
}
