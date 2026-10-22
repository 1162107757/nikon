param([ValidateSet('phone','large-font','landscape')][string]$Mode='phone',[int]$ExpectedVersion=74,[ValidateSet('full','viewer-boundary','selection-polish','album-refresh')][string]$Scope='full')
$ErrorActionPreference='Stop'
$serial='emulator-5582'
$adb='D:\dev\Android\Sdk\platform-tools\adb.exe'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$evidenceFamily=if ($Scope -eq 'album-refresh') { 'photo-album-refresh-' } elseif ($Scope -eq 'viewer-boundary') { 'photo-viewer-boundary-' } elseif ($Scope -eq 'selection-polish') { 'photo-selection-polish-' } else { 'photo-editor-categories-' }
$buildFamily=if ($Scope -eq 'album-refresh') { 'photo-album-refresh-' } else { 'photo-editor-categories-' }
$probeRoot=Join-Path $projectRoot ('.tooling\'+$buildFamily+$ExpectedVersion)
$probeApk=Join-Path $probeRoot 'editor-probe.apk'
$provenancePath=Join-Path $probeRoot 'provenance.json'
$evidence=Join-Path $projectRoot ('docs\validation\'+$evidenceFamily+$ExpectedVersion+'\'+$Mode)
function Adb([string[]]$Arguments) {
    $observed=& $adb -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw ('Dedicated-emulator adb command failed: '+($Arguments -join ' ')) }
    return $observed
}
function ReadSetting([string]$Name) { return (((Adb @('shell','settings','get','system',$Name)) -join '').Trim()) }
function RestoreSetting([string]$Name,[string]$Value) {
    if ($Value -eq 'null') { Adb @('shell','settings','delete','system',$Name) | Out-Null }
    else { Adb @('shell','settings','put','system',$Name,$Value) | Out-Null }
}
$hardware=((Adb @('shell','getprop','ro.hardware')) -join '').Trim()
if ($hardware -notin @('ranchu','goldfish')) { throw 'Only the dedicated emulator-5582 may run this probe.' }
if (-not (Test-Path -LiteralPath $probeApk)) { throw 'Build the standalone probe APK first.' }
if (Test-Path -LiteralPath $provenancePath) {
    $provenance=Get-Content -Encoding UTF8 -LiteralPath $provenancePath -Raw | ConvertFrom-Json
    $observedProbeSha=(Get-FileHash -Algorithm SHA256 -LiteralPath $probeApk).Hash.ToLowerInvariant()
    if ($provenance.expectedVersion -ne $ExpectedVersion -or $provenance.probeApkSha256 -ne $observedProbeSha) { throw 'Probe provenance does not match the requested version and APK.' }
    if ($ExpectedVersion -ge 74 -and $provenance.runScriptSha256 -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()) { throw 'Run script changed after probe build; rebuild to capture the executed script provenance.' }
} elseif ($ExpectedVersion -ge 74) { throw 'Version 74 and newer require probe provenance; rebuild the standalone probe.' }
$priorSettings=[ordered]@{
    font_scale=ReadSetting 'font_scale'
    accelerometer_rotation=ReadSetting 'accelerometer_rotation'
    user_rotation=ReadSetting 'user_rotation'
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$priorFailure=Join-Path $evidence 'failure.png'
if (Test-Path -LiteralPath $priorFailure) { Remove-Item -LiteralPath $priorFailure }
$expectedFontScale=if ($Mode -eq 'large-font') { '1.3' } else { '1.0' }
$expectedRotation=if ($Mode -eq 'landscape') { '1' } else { '0' }
if (Test-Path -LiteralPath $provenancePath) { Copy-Item -LiteralPath $provenancePath -Destination (Join-Path $evidence 'provenance.json') }
try {
    Adb @('shell','am','force-stop','cc.huevia') | Out-Null
    Adb @('shell','cmd','connectivity','airplane-mode','enable') | Out-Null
    Adb @('install','-r',$probeApk) | Out-Null
    Adb @('shell','settings','put','secure','immersive_mode_confirmations','confirmed') | Out-Null
    Adb @('shell','settings','put','system','font_scale',$expectedFontScale) | Out-Null
    Adb @('shell','settings','put','system','accelerometer_rotation','0') | Out-Null
    Adb @('shell','settings','put','system','user_rotation',$expectedRotation) | Out-Null
    $observedFontScale=ReadSetting 'font_scale'
    $observedAutoRotation=ReadSetting 'accelerometer_rotation'
    $observedRotation=ReadSetting 'user_rotation'
    $parsedFontScale=[double]::Parse($observedFontScale,[Globalization.CultureInfo]::InvariantCulture)
    $requestedFontScale=[double]::Parse($expectedFontScale,[Globalization.CultureInfo]::InvariantCulture)
    if ([Math]::Abs($parsedFontScale-$requestedFontScale) -gt 0.0001) { throw ('Emulator font scale mismatch: expected '+$expectedFontScale+', observed '+$observedFontScale) }
    if ($observedAutoRotation -ne '0' -or $observedRotation -ne $expectedRotation) { throw 'Emulator rotation setting did not apply.' }
    [IO.File]::WriteAllLines((Join-Path $evidence 'environment.txt'),@("mode=$Mode","scope=$Scope","expected_version=$ExpectedVersion","run_started_utc=$([DateTime]::UtcNow.ToString('O'))","font_scale_expected=$expectedFontScale","font_scale_observed=$observedFontScale","accelerometer_rotation_observed=$observedAutoRotation","user_rotation_expected=$expectedRotation","user_rotation_observed=$observedRotation","font_scale_before=$($priorSettings.font_scale)","accelerometer_rotation_before=$($priorSettings.accelerometer_rotation)","user_rotation_before=$($priorSettings.user_rotation)",'immersive_mode_confirmations=confirmed'),[Text.UTF8Encoding]::new($false))
    $output=& $adb -s $serial shell am instrument -w -r -e mode $Mode -e version $ExpectedVersion -e scope $Scope 'cc.huevia.editorqa/.EditorNativeProbe'
    $instrumentExit=$LASTEXITCODE
    [IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$output,[Text.UTF8Encoding]::new($false))
    $output | Write-Output
    & $adb -s $serial pull ('/sdcard/Android/data/cc.huevia/files/'+$evidenceFamily+$ExpectedVersion+'/'+$Mode+'/.') $evidence
    if ($LASTEXITCODE -ne 0 -or $instrumentExit -ne 0 -or ($output -join "`n") -notmatch 'Photo editor native PASS') { throw 'Native photo editor verification failed; inspect evidence.' }
    Write-Output ('Evidence: '+$evidence)
} finally {
    $restoreErrors=[Collections.Generic.List[string]]::new()
    $restored=[Collections.Generic.List[string]]::new()
    try { Adb @('shell','am','force-stop','cc.huevia') | Out-Null } catch { $restoreErrors.Add($_.Exception.Message) }
    foreach ($name in $priorSettings.Keys) {
        try {
            RestoreSetting $name $priorSettings[$name]
            $value=ReadSetting $name
            $restored.Add("${name}_restored=$value")
            if ($value -ne $priorSettings[$name]) { throw ("Restored setting mismatch for ${name}: expected "+$priorSettings[$name]+', observed '+$value) }
        } catch { $restoreErrors.Add($_.Exception.Message) }
    }
    $restored.Add('status='+$(if ($restoreErrors.Count -eq 0) { 'PASS' } else { 'FAIL' }))
    [IO.File]::WriteAllLines((Join-Path $evidence 'restoration.txt'),$restored,[Text.UTF8Encoding]::new($false))
    if ($restoreErrors.Count -gt 0) { throw ('Cannot fully restore the dedicated emulator settings: '+($restoreErrors -join '; ')) }
}
