param(
    [string]$OasSource = "D:\OnmyojiAutoScript-easy-install\OnmyojiAutoScript-easy-install",
    [string]$RuntimeVersion = (Get-Date -Format "yyyy.MM.dd.HHmm"),
    [string]$RuntimeNotes = "同步上游 OAS 任务与资源更新",
    [string]$Repo = "kongdr418/OnmyojiAutoScript-Android",
    [string]$AppVersion = "1.0.0",
    [int]$AppVersionCode = 10
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
python (Join-Path $PSScriptRoot 'export-runtime.py') --source $OasSource
if ($LASTEXITCODE -ne 0) { throw '导出 oas-runtime.zip 失败' }

$assetDir = Join-Path $root 'app\src\main\assets\runtime'
$outDir = Join-Path $root 'dist\update'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$zipSrc = Join-Path $assetDir 'oas-runtime.zip'
$manifestSrc = Join-Path $assetDir 'manifest.json'
Copy-Item -Force $zipSrc (Join-Path $outDir 'oas-runtime.zip')
Copy-Item -Force $manifestSrc (Join-Path $outDir 'manifest.json')

$sha = (Get-FileHash -LiteralPath $zipSrc -Algorithm SHA256).Hash.ToLowerInvariant()
$base = "https://github.com/$Repo/releases/latest/download"
$payload = [ordered]@{
    runtimeVersion     = $RuntimeVersion
    runtimeArchiveUrl  = "$base/oas-runtime.zip"
    runtimeSha256      = $sha
    runtimeManifestUrl = "$base/manifest.json"
    runtimeNotes       = $RuntimeNotes
    appVersion         = $AppVersion
    appVersionCode     = $AppVersionCode
    appDownloadUrl     = "https://github.com/$Repo/releases/download/v$AppVersion/OASA-$AppVersion-arm64.apk"
    appNotes           = ""
}
$jsonText = $payload | ConvertTo-Json -Depth 4
[System.IO.File]::WriteAllText((Join-Path $outDir 'update.json'), $jsonText + "`n", (New-Object System.Text.UTF8Encoding $false))

Write-Host "Update bundle ready in: $outDir"
Write-Host "  - update.json"
Write-Host "  - oas-runtime.zip (sha256=$sha)"
Write-Host "  - manifest.json"
Write-Host "Upload these 3 files to GitHub Release: https://github.com/$Repo/releases"
