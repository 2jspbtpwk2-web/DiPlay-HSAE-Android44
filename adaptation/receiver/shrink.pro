-dontoptimize
-dontobfuscate
-keep class local.airuize.receiver.** { *; }
-keepattributes InnerClasses,EnclosingMethod,Signature,Exceptions,SourceFile,LineNumberTable
# SDK18 EnumMap obtains enum constants by reflecting on values(). R8 full mode
# cannot infer that library-side access; removing it breaks JmDNS ServiceInfo.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JmDNS is reached only via reflection / EnumMap on the NCM link. After removing the
# wireless controller, keep the whole library so ServiceInfo$Fields.values() and ServiceInfo stay intact.
-keep class javax.jmdns.** { *; }
# Unused standard-library/JCA adapters target newer Android versions; they must be removed by shrinking.
-dontwarn java.nio.file.**
-dontwarn java.util.function.**
-dontwarn java.util.stream.**
-dontwarn java.security.cert.PKIXRevocationChecker
-dontwarn java.lang.ClassValue
-dontwarn java.lang.invoke.**
-dontwarn javax.naming.**
-dontwarn org.jetbrains.annotations.**
# BouncyCastle lightweight TLS only. The Lockdown TLS client uses TlsClientProtocol +
# BcTlsCrypto, which perform every key exchange / cipher / MAC / AEAD operation through the
# bundled crypto core and never touch javax.net.ssl or the head unit's JCE. The high-level JSSE
# provider (org.bouncycastle.jsse.**) hard-requires API24 javax.net.ssl types and the JCE TLS
# backend (org.bouncycastle.tls.crypto.impl.jcajce.**) needs Cipher.updateAAD (API24); both are
# deliberately left unkept so R8 shrinks them out of the API18 dex. Keep only the lightweight
# protocol classes, the BouncyCastle (bc) crypto backend, and the crypto/asn1/util cores
# (the latter also covers the ASN1 OID classes that live in the bcutil artifact).
-keep class org.bouncycastle.tls.* { *; }
-keep class org.bouncycastle.tls.crypto.* { *; }
-keep class org.bouncycastle.tls.crypto.impl.bc.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn org.bouncycastle.**
