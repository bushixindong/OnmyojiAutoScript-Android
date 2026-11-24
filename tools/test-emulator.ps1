param([Parameter(Mandatory=$true)][string]$Serial, [string]$Adb, [switch]$AttachDebugBridge)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot
if(-not $Adb){$Adb=(Get-Command adb -ErrorAction Stop).Source}
function Invoke-Device {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$DeviceArgs)
    & $Adb -s $Serial @DeviceArgs
    if($LASTEXITCODE -ne 0){throw "ADB 操作失败: $($DeviceArgs -join ' ')"}
}
$taskApk=Join-Path $taskRoot 'app\build\outputs\apk\debug\app-debug.apk'
if(-not(Test-Path -LiteralPath $taskApk)){throw '请先运行 build.ps1 构建 debug APK'}
Invoke-Device get-state
$taskOldPids=(& $Adb -s $Serial shell pidof oas_debug_bridge 2>$null) -join ' '
if($taskOldPids -match '^\d+(\s+\d+)*\s*$'){
    foreach($taskOldPid in ($taskOldPids.Trim() -split '\s+')) { Invoke-Device shell kill $taskOldPid }
}
Invoke-Device install -r $taskApk
Invoke-Device push $taskApk /data/local/tmp/oas-android-debug.apk
Invoke-Device shell 'CLASSPATH=/data/local/tmp/oas-android-debug.apk app_process /system/bin com.oas.android.bridge.SmokeMain'
Invoke-Device shell run-as com.oas.android rm -f files/recognition-test.txt
Invoke-Device shell am start -n com.oas.android/.RecognitionTestActivity
$taskReport=''
for($taskAttempt=0;$taskAttempt -lt 30;$taskAttempt++){
    Start-Sleep -Milliseconds 500
    $taskReport=(& $Adb -s $Serial shell run-as com.oas.android cat files/recognition-test.txt 2>$null) -join "`n"
    if($taskReport -match 'ALL PASS|FAIL'){break}
}
Write-Host $taskReport
if($taskReport -notmatch 'ALL PASS' -or $taskReport -match 'FAIL'){throw 'Android 上的 OAS 识别回放未通过'}
Invoke-Device shell run-as com.oas.android rm -f files/ocr-test.txt
Invoke-Device shell am start -n com.oas.android/.OcrTestActivity
$taskOcrReport=''
for($taskAttempt=0;$taskAttempt -lt 40;$taskAttempt++){
    Start-Sleep -Milliseconds 500
    $taskOcrReport=(& $Adb -s $Serial shell run-as com.oas.android cat files/ocr-test.txt 2>$null) -join "`n"
    if($taskOcrReport -match 'ALL PASS|FAIL'){break}
}
Write-Host $taskOcrReport
if($taskOcrReport -notmatch 'ALL PASS' -or $taskOcrReport -match 'FAIL'){throw 'Android 原生 OCR 与桌面参考回放不一致'}
Invoke-Device shell run-as com.oas.android rm -f files/task-replay.txt
Invoke-Device shell am start -n com.oas.android/.TaskReplayActivity
$taskFlowReport=''
for($taskAttempt=0;$taskAttempt -lt 800;$taskAttempt++){
    Start-Sleep -Milliseconds 500
    $taskFlowReport=(& $Adb -s $Serial shell run-as com.oas.android cat files/task-replay.txt 2>$null) -join "`n"
    if($taskFlowReport -match 'ALL PASS|FAIL'){break}
}
Write-Host $taskFlowReport
if($taskFlowReport -notmatch 'ALL PASS' -or $taskFlowReport -match 'FAIL'){throw '原生副本任务 AIDL 离线流程回放未通过'}
Invoke-Device shell am start -n com.oas.android/.MainActivity
if($AttachDebugBridge){
    # Debug-only provider requires android.permission.DUMP and independently checks caller UID.
    Invoke-Device shell 'CLASSPATH=/data/local/tmp/oas-android-debug.apk nohup app_process /system/bin --nice-name=oas_debug_bridge com.oas.android.bridge.DebugMain >/data/local/tmp/oas-debug-bridge.log 2>&1 </dev/null &'
    Start-Sleep -Milliseconds 1000
    $taskAttachLog=(Invoke-Device shell cat /data/local/tmp/oas-debug-bridge.log) -join "`n"
    Write-Host $taskAttachLog
    if($taskAttachLog -notmatch 'debug bridge attached'){throw 'ADB 调试服务未成功连接'}
}
