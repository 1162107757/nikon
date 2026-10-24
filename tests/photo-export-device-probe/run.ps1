param([Parameter(Mandatory=$true)][string]$CandidateApk,[string]$Serial='emulator-5586')
$ErrorActionPreference='Stop'
if($Serial -ne 'emulator-5586'){throw 'Only explicitly assigned emulator-5586 is allowed.'}
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')); $adb='D:\dev\Android\Sdk\platform-tools\adb.exe'
function Adb([string[]]$Arguments){$value=& $adb -s $Serial @Arguments; if($LASTEXITCODE -ne 0){throw ('Export probe adb failed: '+($Arguments -join ' '))}; return $value}
$hardware=(Adb @('shell','getprop','ro.hardware')) -join ''; if($hardware.Trim() -notin @('ranchu','goldfish')){throw 'Refusing non-emulator device.'}
$hash=(Get-FileHash -Algorithm SHA256 -LiteralPath $CandidateApk).Hash.ToLowerInvariant()
$evidence=Join-Path $projectRoot 'docs\validation\photo-detail-export-histogram\backend'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
Adb @('install','-r','--no-incremental',(Join-Path $PSScriptRoot '.build\photo-export-probe.apk')) | Out-Null
# Candidate installation is deliberately owned by the root agent, never by this runner.
Adb @('shell','am','instrument','-w','cc.huevia.exportqa/.PhotoExportProbe') | Set-Content -Encoding utf8 -LiteralPath (Join-Path $evidence 'instrumentation.txt')
Adb @('pull','/sdcard/Android/data/cc.huevia/files/photo-export-qa/report.json',(Join-Path $evidence 'report.json')) | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '.build\provenance.json') -Destination (Join-Path $evidence 'provenance.json')
$report=Get-Content -Raw -LiteralPath (Join-Path $evidence 'report.json') | ConvertFrom-Json
if($report.apkSha256 -ne $hash){throw 'Evidence belongs to a different candidate APK.'}
if($report.status -ne 'PASS' -or -not $report.scratchRemoved){throw ('Export native verification failed: '+$report.failure)}
Write-Output ('PASS: '+$report.checks+' native backend assertions, APK '+$hash)
