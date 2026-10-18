#!/bin/sh
set -eu
umask 077
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
workspace_dir=$(CDPATH= cd -- "$project_dir/../.." && pwd)
platform_jar=${DIPLAY_PLATFORM_JAR:?Set DIPLAY_PLATFORM_JAR to the API18 android.jar}
build_tools=${DIPLAY_BUILD_TOOLS_DIR:?Set DIPLAY_BUILD_TOOLS_DIR to Android build-tools 34.0.0}
jdk_dir=${DIPLAY_PROBE_JDK_DIR:-${JAVA_HOME:?Set JAVA_HOME to JDK 11}}
build_dir="$project_dir/build"
delivery_dir="$workspace_dir/adaptation/output"
android_min_sdk=18
apk_filename=DiPlay-Android43-CompatibilityProbe-source-only.apk
mkdir -p "$build_dir/classes" "$build_dir/dex" "$delivery_dir" "$build_dir/tmp"
TMPDIR="$build_dir/tmp"; TEMP="$TMPDIR"; TMP="$TMPDIR"
export TMPDIR TEMP TMP
for required in "$platform_jar" "$build_tools/aapt" "$build_tools/zipalign" "$build_tools/lib/d8.jar" "$build_tools/lib/apksigner.jar" "$jdk_dir/bin/java" "$jdk_dir/bin/javac" "$jdk_dir/bin/jar"; do
    [ -f "$required" ] || { echo 'Missing configured build tool/platform; see receiver/BUILD.md' >&2; exit 1; }
done
python3 - "$build_dir/empty-public-assets" <<'PYEMPTY'
from pathlib import Path
import sys
assets=Path(sys.argv[1])
if assets.is_symlink(): raise SystemExit('Public probe asset staging must not be a symlink')
assets.mkdir(exist_ok=True)
if any(assets.iterdir()): raise SystemExit('Public probe asset staging must remain empty')
print('Probe source build omits media fixtures; video/audio clip probes are unavailable')
PYEMPTY
"$build_tools/aapt" package -f -M "$project_dir/AndroidManifest.xml" \
    -S "$project_dir/res" -A "$build_dir/empty-public-assets" -I "$platform_jar" -0 mp4 -F "$build_dir/resources.apk"
find "$project_dir/src" -name '*.java' > "$build_dir/sources.list"
"$jdk_dir/bin/javac" -J-Djava.io.tmpdir="$TMPDIR" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$platform_jar" \
    -d "$build_dir/classes" @"$build_dir/sources.list"
"$jdk_dir/bin/jar" -J-Djava.io.tmpdir="$TMPDIR" cf "$build_dir/classes.jar" -C "$build_dir/classes" .
"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -cp "$build_tools/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 18 --lib "$platform_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && zip -q -u "$build_dir/unsigned.apk" classes.dex)
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

"$jdk_dir/bin/java" -Djava.io.tmpdir="$TMPDIR" -jar "$build_tools/lib/apksigner.jar" verify --verbose --min-sdk-version 18 \
    "$delivery_dir/$apk_filename"
python3 - "$delivery_dir/$apk_filename" <<'PYPROBEASSETS'
from pathlib import Path
from zipfile import ZipFile
import sys
blocked={'.pk8','.p7b','.pem','.key','.p12','.pfx','.jks','.keystore'}
with ZipFile(sys.argv[1]) as package:
    if any(Path(name).suffix.lower() in blocked or 'offline-mfi' in name.split('/') for name in package.namelist()):
        raise SystemExit('Authentication/signing material is forbidden in the public probe APK')
print('PASS public probe contains no credential asset files')
PYPROBEASSETS
"$build_tools/aapt" dump badging "$delivery_dir/$apk_filename"
shasum -a 256 "$delivery_dir/$apk_filename"
echo 'Probe source artifact only; missing media fixtures are not validated playback coverage.'