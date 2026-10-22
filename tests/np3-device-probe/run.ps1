param([ValidateSet('light','dark')][string]$Theme='light',[int]$ExpectedVersion=79,[ValidateSet('full','renderer-only','monochrome-batch')][string]$Scope='renderer-only',
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedApkSha256,
    [ValidatePattern('^[A-Za-z0-9._+-]+$')][string]$ExpectedVersionName='0.13.11-beta',
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')][string]$RunId=('monochrome-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')))
$ErrorActionPreference='Stop'
$serial='emulator-5582'
$adb='D:\dev\Android\Sdk\platform-tools\adb.exe'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$evidence=Join-Path $projectRoot ('docs\validation\np3-native\'+$ExpectedVersion+'\monochrome-native\'+$RunId+'\'+$Scope+'\'+$Theme)
if (Test-Path -LiteralPath $evidence) { throw 'This evidence directory already exists; choose a fresh RunId. Existing evidence is never removed.' }
New-Item -ItemType Directory -Path $evidence | Out-Null
function SourceAudit {
    $paths=@(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    $paths += Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
    $paths += Join-Path $projectRoot 'app\build.gradle'
    return @($paths | Sort-Object | ForEach-Object { $item=Get-Item -LiteralPath $_; @{path=$item.FullName.Substring($projectRoot.Length+1).Replace('\','/');bytes=$item.Length;sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
}
$sourcesBefore=SourceAudit
$hardware=& $adb -s $serial shell getprop ro.hardware
if ($LASTEXITCODE -ne 0 -or $hardware.Trim() -notin @('ranchu','goldfish')) { throw 'Only the dedicated emulator-5582 may run this probe.' }
$productPath=& $adb -s $serial shell pm path cc.huevia
if ($LASTEXITCODE -ne 0 -or @($productPath).Count -ne 1 -or $productPath -notmatch '^package:(/data/app/[A-Za-z0-9_+=~/.-]+/base\.apk)$') { throw 'Cannot identify the installed product APK.' }
$deviceApkPath=$Matches[1]
$deviceDigest=& $adb -s $serial shell sha256sum $deviceApkPath
if ($LASTEXITCODE -ne 0 -or $deviceDigest -notmatch '^([a-fA-F0-9]{64})\s' -or $Matches[1].ToLowerInvariant() -ne $ExpectedApkSha256.ToLowerInvariant()) { throw 'Installed product does not match the frozen APK SHA.' }
& $adb -s $serial shell cmd connectivity airplane-mode enable
if ($LASTEXITCODE -ne 0) { throw 'Cannot isolate emulator networking.' }
$probe=Join-Path $projectRoot ('.tooling\np3-native\'+$ExpectedVersion+'-monochrome\np3-probe.apk')
& $adb -s $serial install -r $probe
if ($LASTEXITCODE -ne 0) { throw 'Cannot install dedicated instrumentation.' }
$timer=[Diagnostics.Stopwatch]::StartNew()
$output=& $adb -s $serial shell am instrument -w -r -e theme $Theme -e version $ExpectedVersion -e versionName $ExpectedVersionName -e scope $Scope -e runId $RunId -e apkSha256 $ExpectedApkSha256.ToLowerInvariant() 'cc.huevia.np3qa/.Np3NativeProbe'
$instrumentExit=$LASTEXITCODE
$timer.Stop()
@{ serial=$serial; expectedVersion=$ExpectedVersion; expectedApkSha256=$ExpectedApkSha256.ToLowerInvariant(); probeSha256=(Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLowerInvariant(); runId=$RunId; scope=$Scope; theme=$Theme; instrumentationElapsedSeconds=$timer.Elapsed.TotalSeconds; productInstalledByThisRunner=$false } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'runner.json') -Encoding utf8
[IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$output,[Text.UTF8Encoding]::new($false))
$output | Write-Output
& $adb -s $serial pull ('/sdcard/Android/data/cc.huevia/files/np3-native/'+$ExpectedVersion+'/'+$RunId+'/'+$Scope+'/'+$Theme+'/.') $evidence
if ($LASTEXITCODE -ne 0 -or $instrumentExit -ne 0 -or ($output -join "`n") -notmatch 'NP3 native PASS') { throw 'Native NP3 verification failed; inspect evidence.' }
$sourcesAfter=SourceAudit
$beforeIndex=@{};foreach ($entry in $sourcesBefore) { $beforeIndex[$entry.path]=$entry.sha256 }
if ($sourcesBefore.Count -ne $sourcesAfter.Count -or @($sourcesAfter | Where-Object { $beforeIndex[$_.path] -ne $_.sha256 }).Count -ne 0) { throw 'Product source changed during native verification; preserve this run and validate a newly frozen APK.' }
$buildAuditPath=Join-Path $projectRoot ('.tooling\np3-native\'+$ExpectedVersion+'-monochrome\build-audit.json')
$buildAudit=Get-Content -LiteralPath $buildAuditPath -Raw | ConvertFrom-Json
if ($buildAudit.probeSha256 -ne (Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Probe changed after its asset/source build audit.' }
@{capturedAt=[DateTime]::UtcNow.ToString('o');productApkSha256=$ExpectedApkSha256.ToLowerInvariant();productVersionCode=$ExpectedVersion;productVersionName=$ExpectedVersionName;
    workspaceSourcesStableDuringValidation=$true;workspaceSources=$sourcesAfter;probeBuild=$buildAudit;
    scope='Installed APK identity independently checked. Source inventory records current workspace before/after validation; it is not an embedded source attestation from the APK compiler.'} |
    ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $evidence 'artifact-binding.json') -Encoding utf8
Write-Output ('Evidence: '+$evidence)
