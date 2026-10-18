#!/bin/sh
set -eu
umask 077
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
build_tools=${DIPLAY_BUILD_TOOLS_DIR:?Set DIPLAY_BUILD_TOOLS_DIR to Android build-tools 34.0.0}
platform_jar=${DIPLAY_PLATFORM_JAR:?Set DIPLAY_PLATFORM_JAR to the API18 android.jar}
android_min_sdk=${DIPLAY_MIN_SDK:-18}
android_target_sdk=${DIPLAY_TARGET_SDK:-$android_min_sdk}
case "$android_min_sdk:$android_target_sdk" in
    18:18) default_apk_filename=DiPlay-HSAE-Android44-v0.60-source-only.apk ;;
    21:21) default_apk_filename=DiPlay-API21-source-only.apk ;;
    *) echo 'Supported SDK variants: 18:18 or 21:21' >&2; exit 1 ;;
esac
apk_filename=${DIPLAY_APK_FILENAME:-$default_apk_filename}
case "$apk_filename" in ''|.|..|*/*|*\\*) echo 'DIPLAY_APK_FILENAME must be a plain filename' >&2; exit 1 ;; esac
build_dir="$project_dir/build"
delivery_dir="$workspace_dir/adaptation/output"
kotlin_dir=${DIPLAY_KOTLIN_DIR:?Set DIPLAY_KOTLIN_DIR to Kotlin compiler 1.9.24}
dependencies=${DIPLAY_DEPS_DIR:-"$workspace_dir/downloads"}
mkdir -p "$build_dir/dex" "$delivery_dir"
mkdir -p "$build_dir/java" "$build_dir/build-tools"
mkdir -p "$build_dir/tmp"
TMPDIR="$build_dir/tmp"; TEMP="$TMPDIR"; TMP="$TMPDIR"
export TMPDIR TEMP TMP DIPLAY_PLATFORM_JAR
for required in "$platform_jar" "$build_tools/aapt" "$build_tools/zipalign" "$build_tools/lib/d8.jar" "$build_tools/lib/apksigner.jar" "$jdk_dir/bin/java" "$jdk_dir/bin/javac" "$jdk_dir/bin/jar" "$kotlin_dir/lib/kotlin-compiler.jar"; do
    [ -f "$required" ] || { echo 'Missing configured build tool/platform; see BUILD.md' >&2; exit 1; }
done
python3 - "$project_dir" "$dependencies" <<'PYDEPS'
from pathlib import Path
import hashlib,json,sys
project,deps=map(Path,sys.argv[1:])
hashes=json.loads((project/'dependency-sha256.json').read_text())
for name in ['bcprov-jdk15to18-1.79.jar','bctls-jdk15to18-1.79.jar','bcutil-jdk15to18-1.79.jar',
             'jmdns-3.5.9.jar','slf4j-api-1.7.36.jar','slf4j-nop-1.7.36.jar']:
    file=deps/name
    if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=hashes[name]:
        raise SystemExit('Missing or mismatched public dependency; run fetch_dependencies.py: '+name)
PYDEPS
python3 "$project_dir/prepare_sources.py"
python3 - "$project_dir" "$workspace_dir" <<'PY'
import os,sys,shutil
from pathlib import Path
project,workspace=map(Path,sys.argv[1:])
assets=project/'build/assets'
explicit=os.environ.get('DIPLAY_AUTH_ASSETS_DIR')
auth_files={}
if explicit:
    try: selected=Path(explicit).resolve(strict=True)
    except OSError: raise SystemExit('Explicit authentication input is unavailable')
    try: selected.relative_to(workspace.resolve())
    except ValueError: pass
    else: raise SystemExit('Authentication input must be outside the public checkout')
    expected={'offline-mfi','offline-mfi/identity.pk8','offline-mfi/certificate.p7b'}
    entries=list(selected.rglob('*'))
    if not selected.is_dir() or any(p.is_symlink() for p in entries) or {p.relative_to(selected).as_posix() for p in entries}!=expected:
        raise SystemExit('Authentication input must contain only the two explicitly selected files in offline-mfi')
    for name in ['identity.pk8','certificate.p7b']:
        original=selected/'offline-mfi'/name
        if not original.is_file() or not 0<original.stat().st_size<=16384: raise SystemExit('Incomplete runtime authentication input')
        auth_files[name]=original.read_bytes()
allowed={'feature-parity.txt','legacy-ui-strings.json','upstream-ui-strings.json',
         'native/libusbiface.so','native/armeabi-v7a/libusbiface.so','native/x86/libusbiface.so',
         'native/armeabi-v7a/libscreencrypto.so','native/x86/libscreencrypto.so'}
blocked={'.pk8','.p7b','.pem','.key','.p12','.pfx','.jks','.keystore','.apk','.aab','.zip'}
for file in (project/'assets').rglob('*'):
    relative=file.relative_to(project/'assets').as_posix()
    if file.is_symlink() or 'offline-mfi' in relative.split('/'):
        raise SystemExit('Symlink or implicit authentication directory is forbidden in public source assets')
    if not file.is_file(): continue
    license_asset=relative.startswith('licenses/') and (file.suffix.lower() in {'.txt','.md'} or file.name in {'LICENSE','LICENCE','COPYING','NOTICE','DiPlay-LICENSE'})
    if file.suffix.lower() in blocked or (relative not in allowed and not license_asset):
        raise SystemExit('Unexpected public build asset; no private identity, media or TUN helper may be staged: '+relative)
    if file.suffix=='.so' and (file.stat().st_size>1048576 or file.read_bytes()[:4]!=b'\x7fELF'):
        raise SystemExit('Invalid public native asset: '+relative)
if assets.exists(): shutil.rmtree(assets)
shutil.copytree(project/'assets',assets)
if explicit:
    target=assets/'offline-mfi'
    target.mkdir(mode=0o700)
    for name,content in auth_files.items(): (target/name).write_bytes(content)
    print('Runtime authentication selected explicitly')
else: print('Source-only build: runtime authentication omitted')
print('Media fixtures and ROOT TUN helper are not bundled; related probes/standalone vehicle connection are unavailable')
PY
compiler_cp="$platform_jar:$dependencies/bcprov-jdk15to18-1.79.jar:$dependencies/bctls-jdk15to18-1.79.jar:$dependencies/bcutil-jdk15to18-1.79.jar:$dependencies/jmdns-3.5.9.jar:$dependencies/slf4j-api-1.7.36.jar"
"$jdk_dir/bin/javac" -J-Djava.io.tmpdir="$TMPDIR" -source 8 -target 8 -bootclasspath "$platform_jar" -d "$build_dir/java" \
    "$project_dir/src/local/airuize/receiver/LegacyCharsetFields.java"
"$jdk_dir/bin/jar" -J-Djava.io.tmpdir="$TMPDIR" cf "$build_dir/java.jar" -C "$build_dir/java" .
"$jdk_dir/bin/javac" -J-Djava.io.tmpdir="$TMPDIR" -classpath "$kotlin_dir/lib/kotlin-compiler.jar" -d "$build_dir/build-tools" "$project_dir/build-tools/PatchJmDns.java"
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -classpath "$build_dir/build-tools:$kotlin_dir/lib/kotlin-compiler.jar" PatchJmDns \
    "$dependencies/jmdns-3.5.9.jar" "$build_dir/jmdns-legacy.jar"
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -Xmx2g -cp "$kotlin_dir/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -kotlin-home "$kotlin_dir" -no-jdk -jvm-target 1.8 -classpath "$compiler_cp" \
    "$build_dir/upstream" "$project_dir/src" -d "$build_dir/receiver.jar"
python3 - "$project_dir/AndroidManifest.xml" "$build_dir/AndroidManifest.xml" "$android_min_sdk" "$android_target_sdk" <<'PYSDK'
from pathlib import Path
import sys,xml.etree.ElementTree as ET
source,target,minimum,requested=sys.argv[1:]
namespace='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',namespace)
tree=ET.parse(source); sdk=tree.getroot().find('uses-sdk')
sdk.set('{'+namespace+'}minSdkVersion',minimum)
sdk.set('{'+namespace+'}targetSdkVersion',requested)
tree.write(target,encoding='utf-8',xml_declaration=True)
PYSDK
"$build_tools/aapt" package -f -M "$build_dir/AndroidManifest.xml" -S "$project_dir/res" \
    -A "$build_dir/assets" -I "$platform_jar" -0 mp4 -0 m4a -F "$build_dir/resources.apk"
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -Xmx2g -cp "$build_tools/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 18 \
    --pg-conf "$project_dir/shrink.pro" --lib "$platform_jar" --output "$build_dir/dex" "$build_dir/receiver.jar" "$build_dir/java.jar" \
    "$kotlin_dir/lib/kotlin-stdlib.jar" "$dependencies/bcprov-jdk15to18-1.79.jar" \
    "$dependencies/bctls-jdk15to18-1.79.jar" "$dependencies/bcutil-jdk15to18-1.79.jar" \
    "$build_dir/jmdns-legacy.jar" "$dependencies/slf4j-api-1.7.36.jar" "$dependencies/slf4j-nop-1.7.36.jar"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -u "$build_dir/unsigned.apk" classes*.dex)
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
# Passwords use environment references, never literal command arguments or output.
set +x
if [ -n "${ANDROID_KEYSTORE_PATH:-}" ]; then
    signing_key="$ANDROID_KEYSTORE_PATH"
    signing_alias=${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS for external signing}
    : "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD for external signing}"
    : "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD for external signing}"
    [ -f "$signing_key" ] || { echo 'External signing keystore is unavailable' >&2; exit 1; }
    echo 'Signing with explicitly configured external keystore'
else
    signing_key="$build_dir/source-debug.keystore"
    signing_password_file="$build_dir/.source-debug-signing.password"
    signing_alias=source-debug
    if [ -L "$signing_key" ] || [ -L "$signing_password_file" ]; then
        echo 'Local signing material must not be a symlink' >&2; exit 1
    fi
    if [ -f "$signing_key" ] && [ ! -f "$signing_password_file" ]; then
        echo 'Local signing password file is missing; restore its matching file or configure external signing' >&2; exit 1
    fi
    if [ ! -f "$signing_password_file" ]; then
        python3 - "$signing_password_file" <<'PYPASSWORD'
from pathlib import Path
import os,secrets,sys
path=Path(sys.argv[1])
descriptor=os.open(str(path),os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(descriptor,'w') as output:
    output.write(secrets.token_urlsafe(48))
PYPASSWORD
    fi
    chmod 600 "$signing_password_file"
    ANDROID_KEYSTORE_PASSWORD=$(cat "$signing_password_file")
    [ -n "$ANDROID_KEYSTORE_PASSWORD" ] || { echo 'Local signing password file is empty' >&2; exit 1; }
    ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"
    export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
    if [ ! -f "$signing_key" ]; then
        "$jdk_dir/bin/keytool" -J-Djava.io.tmpdir="$TMPDIR" -genkeypair -keystore "$signing_key" \
            -storepass:env ANDROID_KEYSTORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD \
            -alias "$signing_alias" -dname 'CN=Local DiPlay Public Source Build' -keyalg RSA \
            -keysize 2048 -validity 3650 -storetype PKCS12
    fi
    chmod 600 "$signing_key"
    echo 'Signing with a newly provisioned or retained local source-build key'
fi
export ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -jar "$build_tools/lib/apksigner.jar" sign --ks "$signing_key" \
    --ks-key-alias "$signing_alias" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --min-sdk-version "$android_min_sdk" --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false \
    --v4-signing-enabled false --out "$delivery_dir/$apk_filename" "$build_dir/aligned.apk"
unset ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -jar "$build_tools/lib/apksigner.jar" verify --verbose --min-sdk-version "$android_min_sdk" \
    "$delivery_dir/$apk_filename"
python3 - "$delivery_dir/$apk_filename" <<'PYASSETS'
from pathlib import Path
from zipfile import ZipFile
import os,sys
explicit=os.environ.get('DIPLAY_AUTH_ASSETS_DIR')
expected={'assets/offline-mfi/identity.pk8','assets/offline-mfi/certificate.p7b'} if explicit else set()
blocked={'.pk8','.p7b','.pem','.key','.p12','.pfx','.jks','.keystore'}
with ZipFile(sys.argv[1]) as package:
    actual={name for name in package.namelist() if not name.endswith('/') and
            (Path(name).suffix.lower() in blocked or 'offline-mfi' in name.split('/'))}
    if actual!=expected: raise SystemExit('Unexpected or missing authentication assets in APK')
    if explicit:
        for name in expected:
            if package.read(name)!=(Path(explicit)/Path(name).relative_to('assets')).read_bytes():
                raise SystemExit('Selected authentication input differs from packaged asset')
print('PASS explicit authentication asset allowlist; signing key/password are never packaged')
PYASSETS
python3 "$project_dir/verify_apk.py" "$delivery_dir/$apk_filename"
"$build_tools/aapt" dump badging "$delivery_dir/$apk_filename"
shasum -a 256 "$delivery_dir/$apk_filename"
echo 'Public source artifact only: no claim of full vehicle connectivity, media probe coverage or reproduction of the private 0.60 validation.'
