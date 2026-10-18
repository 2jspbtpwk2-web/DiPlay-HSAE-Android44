"""Verify legacy DEX format and reject platform classes missing from SDK18."""
from pathlib import Path
from zipfile import ZipFile
import struct
import hashlib
import sys
import os

root = Path(__file__).resolve().parents[2]
apk = Path(sys.argv[1]) if len(sys.argv)>1 else root / 'adaptation/output/DiPlay-Android43-Wireless-Experimental.apk'
sdk = Path(os.environ.get('DIPLAY_PLATFORM_JAR', root / 'tools/android/android-4.3.1/android.jar'))
with ZipFile(sdk) as platform:
    available = {'L' + name[:-6] + ';' for name in platform.namelist() if name.endswith('.class')}
    platform_bytes = {name[:-6]: platform.read(name) for name in platform.namelist() if name.endswith('.class')}
with ZipFile(apk) as package:
    assert package.testzip() is None
    assert not any(name.startswith('lib/') for name in package.namelist())
    dex_names = [name for name in package.namelist() if name.endswith('.dex')]
    assert dex_names == ['classes.dex'], 'API18 build must be single-DEX'
    data = package.read('classes.dex')
    assert data[:8] == b'dex\n035\0'
    def u32(offset): return struct.unpack_from('<I', data, offset)[0]
    strings = []
    for i in range(u32(56)):
        cursor = u32(u32(60) + i * 4)
        while data[cursor] & 128: cursor += 1
        cursor += 1
        end = data.index(0, cursor)
        strings.append(data[cursor:end].decode('utf-8', errors='replace'))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    defined = {types[u32(u32(100) + i * 32)] for i in range(u32(96))}
    def uleb(cursor):
        value = 0; shift = 0
        while True:
            byte = data[cursor]; cursor += 1
            value |= (byte & 127) << shift
            if byte < 128: return value, cursor
            shift += 7
    declared_methods = {}
    for i in range(u32(96)):
        entry = u32(100) + i * 32
        cursor = u32(entry + 24)
        if not cursor: continue
        counts = []
        for _ in range(4):
            count, cursor = uleb(cursor); counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, cursor = uleb(cursor); _, cursor = uleb(cursor)
        for count in counts[2:]:
            method_id = 0
            for _ in range(count):
                delta, cursor = uleb(cursor); method_id += delta
                access, cursor = uleb(cursor); code, cursor = uleb(cursor)
                declared_methods[method_id] = (access, code)
    # SDK18 EnumMap reflects values(); API member checks alone cannot catch its removal.
    fields_type = 'Ljavax/jmdns/ServiceInfo$Fields;'
    fields_values_found = False
    startup_debug_found = False
    for method_id, (access, code) in declared_methods.items():
        owner_id, proto_id, name_id = struct.unpack_from('<HHI', data, u32(92) + method_id * 8)
        if types[owner_id] == fields_type and strings[name_id] == 'values':
            assert access & 9 == 9 and code, 'Reflective values() must be public static with code'
            fields_values_found = True
        if types[owner_id] == 'Llocal/airuize/receiver/LegacyWiredService;' and strings[name_id] == 'onStartCommand':
            startup_debug_found = bool(code and u32(code + 8))
    assert fields_values_found, 'JmDNS EnumMap reflection broken: Fields.values() was stripped'
    assert startup_debug_found, 'Startup stack line information missing'
    # DEX VM annotations are encoded metadata, not classes applications load from android.jar.
    vm_annotations = {'Ldalvik/annotation/' + name + ';' for name in
                      ['EnclosingClass', 'EnclosingMethod', 'InnerClass', 'MemberClasses', 'Signature', 'Throws']}
    # BouncyCastle JSSE (bctls-jdk15to18) officially supports JDK 1.5+. The Java 7/8-only JSSE/JCA
    # types below are referenced ONLY by its version-gated bridge classes (JsseUtils_7/_8,
    # Import/ExportX509TrustManager_7, Import/ExportSSLSession_7/_8, ProvSSLEngine_8,
    # ProvSSLSocket*_8). At runtime BC reflectively probes the JVM and, on Android 4.4 / API18,
    # takes the Java 5 "_5" path and never links those classes, so the references stay unresolved
    # (Dalvik soft-verification, load-on-first-use). Core handshake classes (ProvSSLContextSpi,
    # ProvSSLEngine, ProvX509TrustManager, ProvX509KeyManager, ProvSSLSession, ProvTlsClient)
    # extend BC's own BCX509*/BCExtended* abstractions and do NOT touch these types. Verified by
    # scanning every bctls .class for the missing type internal names (19 hits, all _7/_8 bridges).
    # Do NOT add entries here to silence first-party use of a higher API; that must still fail.
    bctls_bridge_types = set()
    allowed = vm_annotations | bctls_bridge_types
    missing = sorted(t for t in types if t.startswith(('Ljava/', 'Ljavax/', 'Landroid/', 'Ldalvik/')) and t not in available and t not in defined and t not in allowed)
    assert not missing, 'Platform types unavailable on SDK18: ' + str(missing)
    cache = {}
    def class_members(name):
        if name in cache: return cache[name]
        raw = platform_bytes.get(name)
        if raw is None: return ([], set(), set())
        cursor = 8
        def short():
            nonlocal cursor
            result = struct.unpack_from('>H', raw, cursor)[0]; cursor += 2; return result
        def integer():
            nonlocal cursor
            result = struct.unpack_from('>I', raw, cursor)[0]; cursor += 4; return result
        cp = [None] * short(); i = 1
        while i < len(cp):
            tag = raw[cursor]; cursor += 1
            if tag == 1:
                length = short(); cp[i] = raw[cursor:cursor+length].decode('utf-8', errors='replace'); cursor += length
            elif tag in [7, 8, 16, 19, 20]: cp[i] = short()
            elif tag in [3, 4, 9, 10, 11, 12, 17, 18]: cursor += 4
            elif tag in [5, 6]: cursor += 8; i += 1
            elif tag == 15: cursor += 3
            else: raise ValueError(tag)
            i += 1
        short(); short(); superclass = short()
        parents = [cp[cp[superclass]]] if superclass else []
        parents += [cp[cp[short()]] for _ in range(short())]
        members = []
        for _ in range(2):
            group = set()
            for _ in range(short()):
                short(); member_name = cp[short()]; descriptor = cp[short()]; group.add((member_name, descriptor))
                for _ in range(short()):
                    short(); size = integer(); cursor += size
            members.append(group)
        result = (parents, members[0], members[1]); cache[name] = result; return result
    def exists(owner, name, descriptor, kind, seen=None):
        seen = set() if seen is None else seen
        if owner in seen: return False
        seen.add(owner)
        parents, fields, methods = class_members(owner)
        if (name, descriptor) in (methods if kind == 'method' else fields): return True
        if name == '<init>': return False
        return any(exists(parent, name, descriptor, kind, seen) for parent in parents)
    unavailable = []
    for i in range(u32(88)):
        offset = u32(92) + i * 8
        owner_id, proto_id, name_id = struct.unpack_from('<HHI', data, offset)
        owner = types[owner_id]
        if owner not in available or owner in defined: continue
        proto = u32(76) + proto_id * 12
        parameter_offset = u32(proto + 8)
        parameters = [types[struct.unpack_from('<H', data, parameter_offset+4+j*2)[0]] for j in range(u32(parameter_offset))] if parameter_offset else []
        descriptor = '(' + ''.join(parameters) + ')' + types[u32(proto+4)]
        if not exists(owner[1:-1], strings[name_id], descriptor, 'method'):
            unavailable.append(owner + '->' + strings[name_id] + descriptor)
    for i in range(u32(80)):
        owner_id, type_id, name_id = struct.unpack_from('<HHI', data, u32(84)+i*8)
        owner = types[owner_id]
        if owner not in available or owner in defined: continue
        if not exists(owner[1:-1], strings[name_id], types[type_id], 'field'):
            unavailable.append(owner + '->' + strings[name_id] + ':' + types[type_id])
    # bctls-jdk15to18 members that resolve only on newer JREs, proven unreachable on the API18 path:
    #  - Map.putIfAbsent, CertPathBuilder.getRevocationChecker: referenced only in JsseUtils_8
    #    (Java 8 bridge, never loaded on Android 4.4).
    #  - SSLSocketFactory.createSocket(Socket,InputStream,boolean): Java 7 JSSE Socket bridge; the
    #    receiver drives SSLEngine only and never obtains an SSLSocketFactory, so the method body
    #    stays soft-unverified and is never entered.
    #  - Cipher.updateAAD(byte[]): used solely by JceAEADCipherImpl for AEAD (GCM/CCM/ChaCha20)
    #    suites. LockdownTlsEngineFactory forces TLS 1.2 + CBC cipher suites on API18 (TLS 1.3 is
    #    all-AEAD and has no CBC option), so the AEAD path is never selected and updateAAD never
    #    runs. If the cipher restriction is ever removed this MUST be re-evaluated.
    allowed_members = set()
    unavailable = [u for u in unavailable if u not in allowed_members]
    assert not unavailable, 'Platform members unavailable on SDK18: ' + str(unavailable)
    auth = os.environ.get('DIPLAY_AUTH_ASSETS_DIR')
    names = ['identity.pk8', 'certificate.p7b']
    if auth:
        for name in names:
            assert package.read('assets/offline-mfi/' + name) == (Path(auth)/'offline-mfi'/name).read_bytes()
    else:
        assert not any(name.startswith('assets/offline-mfi/') for name in package.namelist()), 'Unexpected runtime identity'
    print('PASS: SDK18 platform references, DEX035, single DEX, CRC, explicit runtime assets, mDNS reflection, stack line metadata')
    print('DEX method references:', u32(88))
print('APK SHA256:', hashlib.sha256(apk.read_bytes()).hexdigest())
