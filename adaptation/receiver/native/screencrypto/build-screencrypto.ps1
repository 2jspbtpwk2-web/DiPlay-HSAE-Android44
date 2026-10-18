param([string]$NdkRoot = $env:DIPLAY_NDK_ROOT)
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($NdkRoot)) { throw 'Provide -NdkRoot or DIPLAY_NDK_ROOT pointing to Android NDK r16b (16.1.4479499).' }
$NdkRoot=[System.IO.Path]::GetFullPath($NdkRoot)
$nativeRoot=[System.IO.Path]::GetFullPath($PSScriptRoot)
$receiverRoot=[System.IO.Path]::GetFullPath((Join-Path $nativeRoot '../..'))
$outputRoot=Join-Path $receiverRoot 'build/native-screencrypto'
$ndkBuild=Join-Path $NdkRoot 'ndk-build.cmd'
if(-not (Test-Path -LiteralPath $ndkBuild -PathType Leaf)) { throw 'Configured NDK does not contain ndk-build.cmd.' }
$env:TEMP=Join-Path $outputRoot 'tmp'
$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force -Path $env:TEMP | Out-Null
$version=[System.IO.File]::ReadAllText((Join-Path $NdkRoot 'source.properties'))
if($version -notmatch 'Pkg\.Revision\s*=\s*16\.1\.4479499') { throw 'Requires Android NDK r16b' }
$arguments=@("NDK_PROJECT_PATH=$nativeRoot","APP_BUILD_SCRIPT=$(Join-Path $nativeRoot 'Android.mk')","NDK_APPLICATION_MK=$(Join-Path $nativeRoot 'Application.mk')","NDK_OUT=$(Join-Path $outputRoot 'obj')","NDK_LIBS_OUT=$(Join-Path $outputRoot 'libs')",'-j2')
& $ndkBuild @arguments
if($LASTEXITCODE -ne 0) { throw "Native screen build failed: $LASTEXITCODE" }
$manifest=@('NDK=16.1.4479499','API=18','ABI=armeabi-v7a,x86','Monocypher=4.0.2')
foreach($file in (Get-ChildItem -LiteralPath $nativeRoot -Recurse -File)) {
    $manifest += "source $($file.FullName.Substring($nativeRoot.Length+1)) SHA256=$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
}
foreach($abi in @('armeabi-v7a','x86')) {
    $source=Join-Path $outputRoot "libs/$abi/libscreencrypto.so"
    $dest=Join-Path $receiverRoot "assets/native/$abi"
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -LiteralPath $source -Destination (Join-Path $dest 'libscreencrypto.so') -Force
    $manifest += "asset native/$abi/libscreencrypto.so SHA256=$((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant())"
}
[System.IO.File]::WriteAllLines((Join-Path $outputRoot 'build-manifest.txt'),$manifest,[System.Text.UTF8Encoding]::new($false))
