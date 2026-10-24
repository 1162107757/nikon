param(
    [ValidateSet('phone','font1.3','dark')][string]$Mode='phone',
    [Parameter(Mandatory=$true)][string]$CandidateApk,
    [ValidateSet('emulator-5586')][string]$Serial='emulator-5586',
    [string]$SdkRoot='D:\dev\android-sdk',
    [string]$AdbPath='D:\dev\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$probeApk=Join-Path $PSScriptRoot '.build\thumbnail-probe.apk'
$provenancePath=Join-Path $PSScriptRoot '.build\provenance.json'
$evidence=Join-Path $projectRoot ('docs\validation\thumbnail-placeholder\'+$Mode)
function Adb([string[]]$Arguments){$value=& $AdbPath -s $Serial @Arguments; if($LASTEXITCODE -ne 0){throw ('Dedicated emulator adb failed: '+($Arguments -join ' '))}; return $value}
function ReadSetting([string]$Name){return (((Adb @('shell','settings','get','system',$Name))-join '').Trim())}
function RestoreSetting([string]$Name,[string]$Value){if($Value -eq 'null'){Adb @('shell','settings','delete','system',$Name)|Out-Null}else{Adb @('shell','settings','put','system',$Name,$Value)|Out-Null}}
function ReadNightState{
    $reported=((Adb @('shell','cmd','uimode','night'))-join '').Trim()
    if($reported -notmatch '^Night mode: (custom_schedule|custom_bedtime|auto|yes|no|custom)$'){throw ('Cannot snapshot emulator night mode: '+$reported)}
    $command=$Matches[1]
    $secure=((Adb @('shell','settings','get','secure','ui_night_mode'))-join '').Trim()
    $subtype=((Adb @('shell','settings','get','secure','ui_night_mode_custom_type'))-join '').Trim()
    if($command -eq 'custom'){$command=switch($subtype){'0'{'custom_schedule'} '1'{'custom_bedtime'} default{throw 'Cannot resolve custom night subtype'}}}
    return [pscustomobject]@{command=$command;reported=$reported;secureMode=$secure;customType=$subtype}
}
function ReadGrants{
    $dump=(Adb @('shell','dumpsys','package','cc.huevia'))-join "`n"
    $grants=[ordered]@{}
    foreach($permission in @('android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VISUAL_USER_SELECTED','android.permission.POST_NOTIFICATIONS')){
        if($dump -notmatch ([regex]::Escape($permission)+': granted=(true|false)')){throw ('Cannot snapshot permission grant: '+$permission)}
        $grants[$permission]=($Matches[1] -eq 'true')
    }
    return $grants
}
if(-not(Test-Path -LiteralPath $CandidateApk)){throw 'Final candidate APK path is missing.'}
if(-not(Test-Path -LiteralPath $probeApk)){throw 'Build this standalone instrumentation APK first.'}
$candidateSha=(Get-FileHash -Algorithm SHA256 -LiteralPath $CandidateApk).Hash.ToLowerInvariant()
$badging=(& (Join-Path $SdkRoot 'build-tools\35.0.0\aapt2.exe') dump badging $CandidateApk)-join "`n"
if($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='cc.huevia' versionCode='([0-9]+)' versionName='([^']+)' "){throw 'Candidate is not a readable cc.huevia APK.'}
$versionCode=$Matches[1]; $versionName=$Matches[2]
$provenance=Get-Content -LiteralPath $provenancePath -Raw -Encoding UTF8|ConvertFrom-Json
if($provenance.probeApkSha256 -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $probeApk).Hash.ToLowerInvariant()){throw 'Standalone probe APK provenance mismatch.'}
foreach($entry in @(@('probeJavaSha256','src\cc\huevia\thumbnailqa\ThumbnailPlaceholderProbe.java'),@('manifestSha256','AndroidManifest.xml'),@('buildScriptSha256','build.ps1'),@('runScriptSha256','run.ps1'))){
    if($provenance.inputs.($entry[0]) -ne (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot $entry[1])).Hash.ToLowerInvariant()){throw ('Probe input changed since build: '+$entry[1])}
}
$hardware=((Adb @('shell','getprop','ro.hardware'))-join '').Trim()
if($Serial -ne 'emulator-5586' -or $hardware -notin @('ranchu','goldfish')){throw 'This probe may run only on the reserved emulator-5586, never a physical device.'}
$prior=[ordered]@{font_scale=ReadSetting 'font_scale';accelerometer_rotation=ReadSetting 'accelerometer_rotation';user_rotation=ReadSetting 'user_rotation'}
$priorNight=ReadNightState; $priorGrants=ReadGrants
New-Item -ItemType Directory -Force -Path $evidence|Out-Null
Copy-Item -LiteralPath $provenancePath -Destination (Join-Path $evidence 'provenance.json')
$environment=[ordered]@{mode=$Mode;serial=$Serial;hardware=$hardware;candidateApk=[IO.Path]::GetFullPath($CandidateApk);candidateApkSha256=$candidateSha;candidateVersionCode=$versionCode;candidateVersionName=$versionName;startedAtUtc=[DateTime]::UtcNow.ToString('O');settingsBefore=$prior;nightBefore=$priorNight;permissionsBefore=$priorGrants}
$font=if($Mode -eq 'font1.3'){'1.3'}else{'1.0'}
try{
    Adb @('shell','am','force-stop','cc.huevia')|Out-Null
    Adb @('shell','cmd','uimode','night',$(if($Mode -eq 'dark'){'yes'}else{'no'}))|Out-Null
    Adb @('shell','settings','put','system','font_scale',$font)|Out-Null
    Adb @('shell','settings','put','system','accelerometer_rotation','0')|Out-Null
    Adb @('shell','settings','put','system','user_rotation','0')|Out-Null
    Adb @('shell','pm','grant','cc.huevia','android.permission.READ_MEDIA_IMAGES')|Out-Null
    Adb @('shell','pm','grant','cc.huevia','android.permission.POST_NOTIFICATIONS')|Out-Null
    $observed=ReadSetting 'font_scale'
    if([Math]::Abs([double]::Parse($observed,[Globalization.CultureInfo]::InvariantCulture)-[double]::Parse($font,[Globalization.CultureInfo]::InvariantCulture)) -gt 0.0001 -or (ReadSetting 'accelerometer_rotation') -ne '0' -or (ReadSetting 'user_rotation') -ne '0'){throw 'Requested emulator font/rotation settings did not apply.'}
    $environment.settingsObserved=[ordered]@{font_scale=$observed;accelerometer_rotation=ReadSetting 'accelerometer_rotation';user_rotation=ReadSetting 'user_rotation';night=ReadNightState;permissions=ReadGrants}
    [IO.File]::WriteAllText((Join-Path $evidence 'environment.json'),($environment|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Adb @('install','-r',$probeApk)|Out-Null
    $output=& $AdbPath -s $Serial shell am instrument -w -r -e mode $Mode -e apkSha256 $candidateSha -e versionCode $versionCode 'cc.huevia.thumbnailqa/.ThumbnailPlaceholderProbe'
    $instrumentExit=$LASTEXITCODE
    [IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$output,[Text.UTF8Encoding]::new($false)); $output|Write-Output
    & $AdbPath -s $Serial pull ('/sdcard/Android/data/cc.huevia/files/thumbnail-placeholder/'+$Mode+'/.') $evidence
    if($LASTEXITCODE -ne 0 -or $instrumentExit -ne 0 -or ($output-join "`n") -notmatch 'Thumbnail placeholder native PASS'){throw 'Native thumbnail verification failed; inspect this mode evidence.'}
    $report=Get-Content -LiteralPath (Join-Path $evidence 'report.json') -Raw -Encoding UTF8|ConvertFrom-Json
    if($report.status -ne 'PASS' -or $report.target.installedApkSha256 -ne $candidateSha){throw 'Native report failed or tested a different APK.'}
    Write-Output ('Evidence: '+$evidence)
}finally{
    $errors=[Collections.Generic.List[string]]::new(); $restored=[ordered]@{}
    try{Adb @('shell','am','force-stop','cc.huevia')|Out-Null}catch{$errors.Add($_.Exception.Message)}
    try{Adb @('shell','cmd','uimode','night',$priorNight.command)|Out-Null; $restored.night=ReadNightState; if($restored.night.command -ne $priorNight.command){throw 'Night mode restoration mismatch'}}catch{$errors.Add($_.Exception.Message)}
    foreach($name in $prior.Keys){try{RestoreSetting $name $prior[$name]; $restored[$name]=ReadSetting $name; if($restored[$name] -ne $prior[$name]){throw ('System setting restoration mismatch: '+$name)}}catch{$errors.Add($_.Exception.Message)}}
    foreach($permission in $priorGrants.Keys){try{Adb @('shell','pm',$(if($priorGrants[$permission]){'grant'}else{'revoke'}),'cc.huevia',$permission)|Out-Null}catch{$errors.Add($_.Exception.Message)}}
    try{$restored.permissions=ReadGrants; foreach($permission in $priorGrants.Keys){if($restored.permissions[$permission] -ne $priorGrants[$permission]){throw ('Permission restoration mismatch: '+$permission)}}}catch{$errors.Add($_.Exception.Message)}
    $restored.status=if($errors.Count -eq 0){'PASS'}else{'FAIL'}; $restored.errors=$errors.ToArray()
    [IO.File]::WriteAllText((Join-Path $evidence 'restoration.json'),($restored|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    if($errors.Count -gt 0){throw ('Cannot fully restore emulator settings/grants: '+($errors-join '; '))}
}
