param(
    [ValidateSet('disconnected','connected')][string]$Scope='disconnected',
    [ValidateSet('light','dark')][string]$Theme='light',
    [int]$ExpectedVersion=72,
    [string]$AdbPath='D:\dev\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference='Stop'
$serial='emulator-5582'
$targetPackage='cc.huevia'
$probeRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$evidence=Join-Path $probeRoot ('evidence\'+$ExpectedVersion+'\'+$Scope+'-'+$Theme)
New-Item -ItemType Directory -Force -Path $evidence|Out-Null
function Adb([string[]]$Arguments){$observed=& $AdbPath -s $serial @Arguments;if($LASTEXITCODE -ne 0){throw ('Dedicated-emulator adb command failed: '+($Arguments -join ' '))};return $observed}
$hardware=((Adb @('shell','getprop','ro.hardware')) -join '').Trim()
if($hardware -notin @('ranchu','goldfish')){throw 'Only the dedicated emulator-5582 with ranchu/goldfish hardware may run this probe.'}
$packageText=(Adb @('shell','dumpsys','package',$targetPackage)) -join "`n"
if($packageText -notmatch ('versionCode='+$ExpectedVersion+'\b')){throw ('Install the coordinated product APK version '+$ExpectedVersion+' before running this probe.')}
$probeApk=Join-Path $probeRoot ('.build\'+$ExpectedVersion+'\wifi-simplify-probe.apk')
if(!(Test-Path -LiteralPath $probeApk)){throw 'Build the standalone probe APK first.'}
$priorAirplane=((Adb @('shell','settings','get','global','airplane_mode_on')) -join '').Trim()
$priorWifiStatus=(Adb @('shell','cmd','wifi','status')) -join "`n"
$priorWifiEnabled=$priorWifiStatus -match '(?im)^Wifi is enabled\s*$'
# This probe requires API 33+. Fine/coarse permissions have maxSdkVersion 32 in
# the product manifest and must not be granted on the newer test environment.
$permissions=@('android.permission.NEARBY_WIFI_DEVICES')
$priorPermissions=@{}
foreach($permission in $permissions){
    $match=[regex]::Match($packageText,[regex]::Escape($permission)+': granted=(true|false)')
    if(!$match.Success){throw ('Cannot snapshot the target runtime permission: '+$permission)}
    $priorPermissions[$permission]=$match.Groups[1].Value -eq 'true'
}
$priorFailure=Join-Path $evidence 'failure.png'
if(Test-Path -LiteralPath $priorFailure){Remove-Item -LiteralPath $priorFailure}
try{
    Adb @('shell','am','force-stop',$targetPackage)|Out-Null
    Adb @('shell','cmd','connectivity','airplane-mode','enable')|Out-Null
    foreach($permission in $permissions){
        $action=if($Scope -eq 'disconnected'){'grant'}else{'revoke'}
        Adb @('shell','pm',$action,$targetPackage,$permission)|Out-Null
    }
    if($Scope -eq 'disconnected'){
        Adb @('shell','svc','wifi','disable')|Out-Null
        for($i=0;$i -lt 40;$i++){
            $wifi=(Adb @('shell','cmd','wifi','status')) -join "`n"
            if($wifi -match '(?im)^Wifi is disabled\s*$'){break}
            Start-Sleep -Milliseconds 300
        }
        if($wifi -notmatch '(?im)^Wifi is disabled\s*$'){throw 'Dedicated emulator Wi-Fi did not disable.'}
    }else{
        Adb @('shell','svc','wifi','enable')|Out-Null
        for($i=0;$i -lt 60;$i++){
            $wifi=(Adb @('shell','cmd','wifi','status')) -join "`n"
            if($wifi -match 'Supplicant state:\s*COMPLETED'){break}
            Start-Sleep -Milliseconds 300
        }
        if($wifi -notmatch 'Supplicant state:\s*COMPLETED'){throw 'The dedicated emulator must already have a saved AndroidWifi network; connection did not settle.'}
    }
    Adb @('install','-r',$probeApk)|Out-Null
    $nearbyGranted=$Scope -eq 'disconnected'
    [IO.File]::WriteAllLines((Join-Path $evidence 'environment.txt'),@("serial=$serial","hardware=$hardware","scope=$Scope","theme=$Theme","expected_version=$ExpectedVersion","prior_airplane_mode=$priorAirplane","prior_wifi_enabled=$priorWifiEnabled","nearby_granted_for_this_scope=$nearbyGranted",$wifi),[Text.UTF8Encoding]::new($false))
    $instrument=Adb @('shell','am','instrument','-w','-r','-e','scope',$Scope,'-e','theme',$Theme,'-e','version',([string]$ExpectedVersion),'cc.huevia.wifisimplifyqa/.WifiSimplifyProbe')
    [IO.File]::WriteAllLines((Join-Path $evidence 'instrumentation.txt'),$instrument,[Text.UTF8Encoding]::new($false))
    $instrument|Write-Output
    Adb @('pull',('/sdcard/Android/data/'+$targetPackage+'/files/wifi-simplify-'+$ExpectedVersion+'/'+$Scope+'-'+$Theme+'/.'),$evidence)|Out-Null
    if(($instrument -join "`n") -notmatch 'Wi-Fi simplification native PASS'){throw 'Wi-Fi simplification verification failed; inspect the evidence directory.'}
    Write-Output ('Evidence: '+$evidence)
}finally{
    Adb @('shell','am','force-stop',$targetPackage)|Out-Null
    foreach($permission in $permissions){
        $action=if($priorPermissions[$permission]){'grant'}else{'revoke'}
        Adb @('shell','pm',$action,$targetPackage,$permission)|Out-Null
    }
    $airplaneAction=if($priorAirplane -eq '1'){'enable'}else{'disable'}
    Adb @('shell','cmd','connectivity','airplane-mode',$airplaneAction)|Out-Null
    $wifiAction=if($priorWifiEnabled){'enable'}else{'disable'}
    Adb @('shell','svc','wifi',$wifiAction)|Out-Null
}
