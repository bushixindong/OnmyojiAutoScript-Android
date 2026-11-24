param([Parameter(Mandatory=$true)][string]$Serial,[string]$Adb)
$ErrorActionPreference='Stop'
if(-not $Adb){$Adb=(Get-Command adb -ErrorAction Stop).Source}
$taskRoot=Split-Path $PSScriptRoot
& $Adb -s $Serial shell run-as com.oas.android rm -f files/engine-audit.json
if($LASTEXITCODE -ne 0){throw '不能清理本应用的旧审计报告'}
& $Adb -s $Serial shell am start -n com.oas.android/.EngineAuditActivity
if($LASTEXITCODE -ne 0){throw '不能启动离线引擎审计'}
$taskReport=''
for($taskAttempt=0;$taskAttempt -lt 240;$taskAttempt++){
    Start-Sleep -Milliseconds 500
    $taskReport=(& $Adb -s $Serial shell "run-as com.oas.android sh -c 'test -f files/engine-audit.json && cat files/engine-audit.json'")-join "`n"
    if($taskReport.StartsWith('{')-or $taskReport.StartsWith('FAIL')){break}
}
if(-not $taskReport.StartsWith('{')){throw "离线审计未成功：$taskReport"}
$taskResult=$taskReport|ConvertFrom-Json
$taskFailures=@($taskResult.entries|Where-Object status -ne ready)+@($taskResult.forms|Where-Object status -ne passed)+@($taskResult.integration|Where-Object status -ne passed)
if($taskResult.entries.Count -ne 61 -or $taskResult.forms.Count -lt 64 -or $taskResult.integration.Count -lt 7 -or $taskFailures.Count){$taskFailures|ConvertTo-Json -Depth 8;throw '离线引擎审计未通过'}
$taskReport|Set-Content -LiteralPath (Join-Path $taskRoot 'engine-audit-mumu.json') -Encoding utf8
Write-Host "PASS $($taskResult.entries.Count) task entries/constructors, $($taskResult.forms.Count) config forms, $($taskResult.integration.Count) integration probes. No real game task run."
