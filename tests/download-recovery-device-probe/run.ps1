param(
    [ValidateSet('phone','large-font','landscape')][string]$Mode='phone',
    [int]$ExpectedVersion=73,
    [string]$AdbPath='D:\dev\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$serial='emulator-5582'
$targetPackage='cc.huevia'
$probeRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$evidence=Join-Path $probeRoot ('evidence\'+$ExpectedVersion+'\'+$Mode)
New-Item -ItemType Directory -Force -Path $evidence|Out-Null
function Adb([string[]]$Arguments){$observed=& $AdbPath -s $serial @Arguments;if($LASTEXITCODE -ne 0){throw ('Dedicated-emulator adb command failed: '+($Arguments -join ' '))};return $observed}
$hardware=((Adb @('shell','getprop','ro.hardware')) -join '').Trim()
if($hardware -notin @('ranchu','goldfish')){throw 'Only the dedicated emulator-5582 with ranchu/goldfish hardware may run this probe.'}
$packageText=(Adb @('shell','dumpsys','package',$targetPackage)) -join "`n"
if($packageText -notmatch ('versionCode='+$ExpectedVersion+'\b')){throw 'Install the coordinated product APK first.'}
$probeApk=Join-Path $probeRoot ('.build\'+$ExpectedVersion+'\download-recovery-probe.apk')
if(!(Test-Path -LiteralPath $probeApk)){throw 'Build the standalone probe APK first.'}
$priorFont=((Adb @('shell','settings','get','system','font_scale')) -join '').Trim()
$priorAutoRotation=((Adb @('shell','settings','get','system','accelerometer_rotation')) -join '').Trim()
$priorRotation=((Adb @('shell','settings','get','system','user_rotation')) -join '').Trim()
function RestoreSetting([string]$Name,[string]$Value){if($Value -eq 'null'){Adb @('shell','settings','delete','system',$Name)|Out-Null}else{Adb @('shell','settings','put','system',$Name,$Value)|Out-Null}}
try{
    Adb @('shell','am','force-stop',$targetPackage)|Out-Null
    $font=if($Mode -eq 'large-font'){'1.3'}else{'1.0'}
    $rotation=if($Mode -eq 'landscape'){'1'}else{'0'}
    Adb @('shell','settings','put','system','font_scale',$font)|Out-Null
    Adb @('shell','settings','put','system','accelerometer_rotation','0')|Out-Null
    Adb @('shell','settings','put','system','user_rotation',$rotation)|Out-Null
    Adb @('install','-r',$probeApk)|Out-Null
    $instrument=Adb @('shell','am','instrument','-w','-r','-e','version',([string]$ExpectedVersion),'-e','mode',$Mode,'cc.huevia.downloadrecoveryqa/.DownloadRecoveryProbe')
    [IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$instrument,[Text.UTF8Encoding]::new($false))
    $instrument|Write-Output
    Adb @('pull',('/sdcard/Android/data/'+$targetPackage+'/files/download-recovery-'+$ExpectedVersion+'/'+$Mode+'/.'),$evidence)|Out-Null
    if(($instrument -join "`n") -notmatch 'Download recovery native PASS'){throw 'Download recovery verification failed; inspect the evidence directory.'}
    Write-Output ('Evidence: '+$evidence)
}finally{
    Adb @('shell','am','force-stop',$targetPackage)|Out-Null
    RestoreSetting 'font_scale' $priorFont
    RestoreSetting 'accelerometer_rotation' $priorAutoRotation
    RestoreSetting 'user_rotation' $priorRotation
}
