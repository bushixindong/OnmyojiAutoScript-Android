param([string]$JavaHome, [string]$SdkPath, [switch]$Install, [string]$Serial,[string]$PythonExecutable)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
if (-not $JavaHome) { $JavaHome = $env:JAVA_HOME }
if (-not $JavaHome) {
    $taskStudio = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*','HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Android Studio' } | Select-Object -First 1
    if ($taskStudio.UninstallString) { $JavaHome = Join-Path (Split-Path $taskStudio.UninstallString.Trim('"')) 'jbr' }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\java.exe'))) { throw '请通过 -JavaHome 指定 JDK 25（可使用 Android Studio 的 jbr 目录）' }
if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $SdkPath)) { throw '请通过 -SdkPath 指定 Android SDK' }
$env:JAVA_HOME = $JavaHome
$env:PYTHONDONTWRITEBYTECODE = '1'
& (Join-Path $taskRoot 'tools\build-frames.ps1') -NdkPath (Join-Path $taskRoot '.toolchains\android-ndk-r27d')
$taskSdkValue = $SdkPath.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $taskRoot 'local.properties'), "sdk.dir=$taskSdkValue`r`n", (New-Object System.Text.UTF8Encoding $false))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRoot 'app\src\main\assets\oas\catalog.json') -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($taskRule in $taskCatalog.rules) {
    $taskAsset = Join-Path $taskRoot ('app\src\main\assets\oas\' + $taskRule.file)
    if ((Get-FileHash -LiteralPath $taskAsset -Algorithm SHA256).Hash -ne $taskRule.sha256) { throw "OAS 素材校验失败: $($taskRule.id)" }
}
foreach ($taskModel in $taskCatalog.models) {
    $taskAsset = Join-Path $taskRoot ('app\src\main\assets\oas\' + $taskModel.file)
    if ((Get-FileHash -LiteralPath $taskAsset -Algorithm SHA256).Hash -ne $taskModel.sha256) { throw "OCR 模型校验失败: $($taskModel.file)" }
}
$taskGradleArgs=@('-p',$taskRoot,':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug','--console=plain','--no-daemon')
if($PythonExecutable){$taskGradleArgs+="-PpythonExecutable=$PythonExecutable"}
& (Join-Path $taskRoot 'gradlew.bat') @taskGradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Android 构建或检查失败' }
$taskApk = Join-Path $taskRoot 'app\build\outputs\apk\debug\app-debug.apk'
Write-Host "APK: $taskApk"
$taskMetadata = Get-Content -LiteralPath (Join-Path $taskRoot 'app\build\outputs\apk\debug\output-metadata.json') -Raw -Encoding utf8 | ConvertFrom-Json
$taskVersion = $taskMetadata.elements[0].versionName
if ($taskVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-A-Za-z0-9.]*)$') { throw 'APK 版本号格式无效，未生成分发文件' }
$taskDist = Join-Path $taskRoot 'dist'
New-Item -ItemType Directory -Path $taskDist -Force | Out-Null
$taskDistribution = Join-Path $taskDist "OASA-$taskVersion-debug.apk"
Copy-Item -LiteralPath $taskApk -Destination $taskDistribution -Force
$taskDigest = (Get-FileHash -LiteralPath $taskDistribution -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$taskDistribution.sha256" -Value "$taskDigest  $(Split-Path -Leaf $taskDistribution)" -Encoding ascii
Write-Host "Distribution: $taskDistribution"
if ($Install) {
    if (-not $Serial) { throw '-Install 需要显式指定 -Serial，以免安装到错误设备' }
    & (Join-Path $SdkPath 'platform-tools\adb.exe') -s $Serial install -r $taskApk
    if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败' }
}
