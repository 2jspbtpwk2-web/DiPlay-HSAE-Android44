param(
    [string]$NdkRoot = $env:DIPLAY_NDK_ROOT
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    throw 'Provide -NdkRoot or DIPLAY_NDK_ROOT pointing to Android NDK r16b (16.1.4479499).'
}
$NdkRoot = [System.IO.Path]::GetFullPath($NdkRoot)
$nativeRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$receiverRoot = [System.IO.Path]::GetFullPath((Join-Path $nativeRoot '..'))
$outputRoot = Join-Path $receiverRoot 'build/native-usbiface'
$env:TEMP = Join-Path $outputRoot 'tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
$ndkBuild = Join-Path $NdkRoot 'ndk-build.cmd'
if (-not (Test-Path -LiteralPath $ndkBuild -PathType Leaf)) {
    throw "NDK r16b ndk-build.cmd was not found: $ndkBuild"
}

$sourceProperties = Join-Path $NdkRoot 'source.properties'
$ndkVersion = [System.IO.File]::ReadAllText($sourceProperties)
if ($ndkVersion -notmatch 'Pkg\.Revision\s*=\s*16\.1\.4479499') {
    throw 'This native shim build requires Android NDK r16b (16.1.4479499).'
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$arguments = @(
    "NDK_PROJECT_PATH=$nativeRoot",
    "APP_BUILD_SCRIPT=$(Join-Path $nativeRoot 'Android.mk')",
    "NDK_APPLICATION_MK=$(Join-Path $nativeRoot 'Application.mk')",
    "NDK_OUT=$(Join-Path $outputRoot 'obj')",
    "NDK_LIBS_OUT=$(Join-Path $outputRoot 'libs')",
    '-j2'
)
& $ndkBuild @arguments
if ($LASTEXITCODE -ne 0) { throw "Native build failed with exit code $LASTEXITCODE" }

$manifest = @('NDK=16.1.4479499', 'API=18', 'ABI=armeabi-v7a,x86')
foreach ($name in @('usbiface.c', 'Android.mk', 'Application.mk', 'build-usbiface.ps1')) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $nativeRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest += "source $name SHA256=$hash"
}
foreach ($abi in @('armeabi-v7a', 'x86')) {
    $builtLibrary = Join-Path $outputRoot "libs/$abi/libusbiface.so"
    if (-not (Test-Path -LiteralPath $builtLibrary -PathType Leaf)) {
        throw "Native build did not produce $builtLibrary"
    }
    $assetDirectory = Join-Path $receiverRoot "assets/native/$abi"
    New-Item -ItemType Directory -Path $assetDirectory -Force | Out-Null
    Copy-Item -LiteralPath $builtLibrary -Destination (Join-Path $assetDirectory 'libusbiface.so') -Force
    $hash = (Get-FileHash -LiteralPath $builtLibrary -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest += "asset native/$abi/libusbiface.so SHA256=$hash"
}
# Keep the older ARM asset location usable by existing APK packaging workflows.
Copy-Item -LiteralPath (Join-Path $outputRoot 'libs/armeabi-v7a/libusbiface.so') -Destination (Join-Path $receiverRoot 'assets/native/libusbiface.so') -Force
[System.IO.File]::WriteAllLines((Join-Path $outputRoot 'build-manifest.txt'), $manifest, [System.Text.UTF8Encoding]::new($false))
Write-Output "Native ARMv7 and x86 assets built; manifest: $(Join-Path $outputRoot 'build-manifest.txt')"
