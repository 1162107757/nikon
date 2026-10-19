param([ValidateSet('light','dark')][string]$Theme='light',[int]$ExpectedVersion=57)
$ErrorActionPreference='Stop'
$serial='emulator-5582'
$adb='D:\dev\Android\Sdk\platform-tools\adb.exe'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$evidence=Join-Path $projectRoot ('docs\validation\np3-native\'+$Theme)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$priorFailure=Join-Path $evidence 'failure.png'
if (Test-Path -LiteralPath $priorFailure) { Remove-Item -LiteralPath $priorFailure }
$hardware=& $adb -s $serial shell getprop ro.hardware
if ($LASTEXITCODE -ne 0 -or $hardware.Trim() -notin @('ranchu','goldfish')) { throw 'Only the dedicated emulator-5582 may run this probe.' }
& $adb -s $serial shell cmd connectivity airplane-mode enable
if ($LASTEXITCODE -ne 0) { throw 'Cannot isolate emulator networking.' }
& $adb -s $serial install -r (Join-Path $projectRoot '.tooling\np3-native\np3-probe.apk')
if ($LASTEXITCODE -ne 0) { throw 'Cannot install dedicated instrumentation.' }
$output=& $adb -s $serial shell am instrument -w -r -e theme $Theme -e version $ExpectedVersion 'com.nikonpreset.usbprobe.np3qa/.Np3NativeProbe'
$instrumentExit=$LASTEXITCODE
[IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$output,[Text.UTF8Encoding]::new($false))
$output | Write-Output
& $adb -s $serial pull ('/sdcard/Android/data/com.nikonpreset.usbprobe/files/np3-native/'+$Theme+'/.') $evidence
if ($LASTEXITCODE -ne 0 -or $instrumentExit -ne 0 -or ($output -join "`n") -notmatch 'NP3 native PASS') { throw 'Native NP3 verification failed; inspect evidence.' }
Write-Output ('Evidence: '+$evidence)
