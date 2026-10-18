# Build the public API18 receiver source

This target is separate from the modern root Gradle applications. It contains the
HSAE Android 4.4 adaptation source derived from version 0.60. The sanitized public
copy and its changed build scripts have not been built or tested. They do not
reproduce the privately signed, previously validated vehicle package.

## Source-only package

Use a Unix shell host with JDK 11, Kotlin compiler 1.9.24, Android API18
`android.jar`, Android build-tools 34.0.0, Python 3, `zip` and `shasum`.
Install toolchains outside Git. The script uses explicit SDK/compiler paths and
keeps output and temporary files in the checkout's ignored `build/` and
`adaptation/output/` directories.

From the public repository root:

```sh
export JAVA_HOME=/absolute/path/to/jdk-11
export DIPLAY_PLATFORM_JAR=/absolute/path/to/android-sdk/platforms/android-18/android.jar
export DIPLAY_BUILD_TOOLS_DIR=/absolute/path/to/android-sdk/build-tools/34.0.0
export DIPLAY_KOTLIN_DIR=/absolute/path/to/kotlinc-1.9.24
export DIPLAY_DEPS_DIR="$PWD/downloads"
unset DIPLAY_AUTH_ASSETS_DIR
python3 adaptation/receiver/fetch_dependencies.py
sh adaptation/receiver/build.sh
```

The downloader verifies pinned public JAR hashes, including Bouncy Castle
`bcprov`, `bctls` and `bcutil` 1.79. The build checks runtime dependency hashes
again, generates API18 sources from `shared`, packages a single DEX035, signs and
checks the APK with `verify_apk.py`. Keep `shared` source, `common` translations,
root `LICENSE` and `docs/licenses`; the generator needs them.

Default output:
`adaptation/output/DiPlay-HSAE-Android44-v0.60-source-only.apk`.
`DIPLAY_APK_FILENAME` may select a plain filename in that output directory.
The shell workflow is intended for Unix Java/classpaths; native Windows parity
is not established by an earlier private Windows validation driver.

The optional `sh adaptation/probe/build.sh` uses the same explicit JDK/SDK paths
and signing policy, stages an empty asset directory and writes
`adaptation/output/DiPlay-Android43-CompatibilityProbe-source-only.apk`.
It does not restore omitted media fixtures or test the receiver connection.

The public package contains no accessory identity, private media, vehicle photo
or ROOT TUN helper. Missing H.264/AAC test clips do not prevent compilation;
playback/decoder probes requiring those clips are unavailable. Missing identity
prevents accessory authentication. Missing `assets/native/tunhelper` independently
prevents the tested HSAE wired TUN path. This output is for source inspection and
compatibility development, not a standalone iPhone connection package.

## Signing

By default the build generates a fresh local source-build key and a random
password under ignored `adaptation/receiver/build/`. Both are owner-only files;
the password is never printed or passed as a literal command argument. Retain
the matching local pair if you want to update your own source-build installation.
Never commit or share these files. This new signer cannot update an installation
signed with the original private vehicle-test key.

For an intentionally selected external signer, set `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` in
your local environment or secret manager. All four are required; there are no
fixed password defaults. Do not put values in source, shell history, documentation
or CI logs. The signing key and password are never APK assets.

## Optional local authentication input

Leave `DIPLAY_AUTH_ASSETS_DIR` unset for public source/CI builds. An explicit
local input must be outside the public checkout and contain exactly:

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

Only those two nonempty bounded files may enter the staged authentication
directory. The build rejects credential containers in ordinary source assets,
rejects unexpected assets, and verifies the final APK authentication allowlist.
There is no identity download, generated real identity or remote fallback.
An APK intentionally containing authentication material exposes it to recipients;
do not publish such APKs or inputs. Authentication alone does not supply the
missing TUN helper or restore the private vehicle package.

## Rebuild the included native libraries

USB JNI and optional screen/audio crypto source, including Monocypher notices,
are included. The supplied `.so` files correspond to components with source;
they are not accessory credentials. Native builds require Android NDK r16b
16.1.4479499 for API18 and produce `armeabi-v7a` and `x86` assets.

Windows PowerShell wrappers accept an explicit absolute `-NdkRoot`, or the
`DIPLAY_NDK_ROOT` environment variable; no developer-specific installation path
is assumed:

```powershell
./adaptation/receiver/native/build-usbiface.ps1 -NdkRoot 'D:/your-toolchains/android-ndk-r16b'
./adaptation/receiver/native/screencrypto/build-screencrypto.ps1 -NdkRoot 'D:/your-toolchains/android-ndk-r16b'
```

These wrappers write output and temporary files under the receiver's ignored
build directory, then refresh only the corresponding public `.so` assets. They
are separate steps; `build.sh` does not silently download or rebuild an NDK.
On other hosts, use the checked-in Android.mk/Application.mk with installed
host `ndk-build` and explicit `NDK_OUT`/`NDK_LIBS_OUT` paths.

TUN helper source/provenance is unresolved and its binary is excluded. There is
no rebuild recipe for it in this public copy. Do not substitute a fake helper,
claim native-source completeness for it, or weaken the successful connection
path to conceal the missing component. Missing crypto acceleration has a
software fallback; missing USB/TUN components do not establish equivalent
vehicle behavior.

## Validation status

Test sources remain available for maintenance. Historical `test.sh` and
`test-packaging.sh` use older class lists/classpaths and are not a reproduction
of the complete private 0.60 suite. No private review assets, old compiled test
JARs, original signing key, device logs or emulator state are included. Synthetic
test identities do not authenticate a real accessory. Public clean-build,
packaging and test coverage still need their own validation; previous desktop
or vehicle evidence must not be presented as a pass for this sanitized copy.
