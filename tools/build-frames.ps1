param([Parameter(Mandatory=$true)][string]$NdkPath)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot
$taskSource=Join-Path $taskRoot 'app\src\main\native\raw_frames.cpp'
$taskHardwareSource=Join-Path $taskRoot 'app\src\main\native\hardware_capture.cpp'
$taskCompiler=Join-Path $NdkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$taskDigest=(@($taskSource,$taskHardwareSource) | ForEach-Object { (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }) -join ':'
if(-not(Test-Path -LiteralPath $taskCompiler)){
    $taskStale=$false
    foreach($taskAbi in @('arm64-v8a','x86_64')){
        $taskOutput=Join-Path $taskRoot "app\src\main\jniLibs\$taskAbi\liboas_frames.so"
        if(-not(Test-Path -LiteralPath $taskOutput)-or -not(Test-Path -LiteralPath "$taskOutput.source-sha256")-or ((Get-Content -LiteralPath "$taskOutput.source-sha256" -Raw).Trim() -ne $taskDigest)){$taskStale=$true}
    }
    if(-not $taskStale){return}
    if(-not(Test-Path -LiteralPath (Join-Path $NdkPath 'toolchains\llvm\prebuilt\linux-x86_64\bin\clang++'))){throw 'Native RGB frames require Android NDK r27d'}
    $taskLinuxScript=(& wsl.exe --exec wslpath -u (Join-Path $PSScriptRoot 'build-frames.sh')).Trim()
    & wsl.exe --exec sh $taskLinuxScript
    if($LASTEXITCODE -ne 0){throw 'Native RGB frame build via WSL failed'}
    foreach($taskAbi in @('arm64-v8a','x86_64')){Set-Content -LiteralPath (Join-Path $taskRoot "app\src\main\jniLibs\$taskAbi\liboas_frames.so.source-sha256") -Value $taskDigest -Encoding ascii}
    return
}
foreach($taskAbi in @('arm64-v8a','x86_64')){
    $taskTriple=if($taskAbi -eq 'arm64-v8a'){'aarch64-linux-android28'}else{'x86_64-linux-android28'}
    $taskOutput=Join-Path $taskRoot "app\src\main\jniLibs\$taskAbi\liboas_frames.so"
    $taskStamp="$taskOutput.source-sha256"
    if((Test-Path -LiteralPath $taskOutput)-and(Test-Path -LiteralPath $taskStamp)-and((Get-Content -LiteralPath $taskStamp -Raw).Trim() -eq $taskDigest)){continue}
    New-Item -ItemType Directory -Path (Split-Path $taskOutput) -Force | Out-Null
    & $taskCompiler "--target=$taskTriple" -std=c++17 -O3 -fPIC -shared -static-libstdc++ '-Wl,-z,max-page-size=16384' '-Wl,-soname,liboas_frames.so' $taskSource $taskHardwareSource -lmediandk -landroid -lEGL -lGLESv2 -o $taskOutput
    if($LASTEXITCODE -ne 0){throw "Native RGB build failed: $taskAbi"}
    Set-Content -LiteralPath $taskStamp -Value $taskDigest -Encoding ascii
}
