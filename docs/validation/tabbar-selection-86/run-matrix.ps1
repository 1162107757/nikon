param([string[]]$Modes=@('phone','large-font','dark'))
$ErrorActionPreference='Stop'
$project86=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$adb86='D:/dev/Android/Sdk/platform-tools/adb.exe'; $serial86='emulator-5582'
$apk86=Join-Path $project86 'dist/huevia-0.17.2-beta-debug.apk'
function Pins86 {
    $inputs86=@(Get-ChildItem (Join-Path $project86 'app/src') -File -Recurse)
    $inputs86+=Get-ChildItem (Join-Path $project86 'app/libs') -File -Recurse
    $inputs86+=Get-ChildItem (Join-Path $project86 'tests/orange-ui-device-probe') -File -Recurse
    $inputs86+=Get-Item (Join-Path $project86 'app/build.gradle'),(Join-Path $project86 'scripts/build-apk.ps1')
    @($inputs86|Sort-Object FullName|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($project86,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
}
$pins86=Pins86; $serialized86=$pins86|ConvertTo-Json -Compress -Depth 5
$state86=[ordered]@{startedAtUtc=[DateTime]::UtcNow.ToString('O');status='RUNNING';versionCode=86;apkSha256=(Get-FileHash $apk86 -Algorithm SHA256).Hash.ToLowerInvariant();apkBytes=(Get-Item $apk86).Length;sources=$pins86;modes=@();scope='Existing local-album regression on a task-owned read-only emulator. Synthetic slots/photos only. Whole-cell icon-and-label selection in phone floating bar; wide rail source behavior unchanged. Landscape/tablet suite not repeated for this color-only selection adjustment. No physical phone connected, camera or live network certification.'}
function Save86 { [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'matrix.json'),($state86|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
Save86
try {
    & $adb86 -s $serial86 install --no-incremental -r $apk86 *> (Join-Path $PSScriptRoot 'install.txt'); if($LASTEXITCODE-ne0){throw 'Candidate install failed'}
    & $adb86 -s $serial86 shell pm grant cc.huevia android.permission.POST_NOTIFICATIONS; if($LASTEXITCODE-ne0){throw 'Notification isolation failed'}
    foreach($mode86 in $Modes){
        $sizeChanged86=$false
        try {
            if($mode86-eq'tablet'){& $adb86 -s $serial86 shell wm size 1280x1800|Out-Null;& $adb86 -s $serial86 shell wm density 240|Out-Null;$sizeChanged86=$true}
            & (Join-Path $project86 'tests/orange-ui-device-probe/run.ps1') -Mode $mode86 -ExpectedVersion 86 -Scope local-album *> (Join-Path $PSScriptRoot ($mode86+'-runner.txt'))
            $reportPath86='docs/validation/orange-ui-86/local-album/'+$mode86+'/report.json'
            $report86=Get-Content (Join-Path $project86 $reportPath86) -Raw|ConvertFrom-Json
            if($report86.status-ne'PASS'-or$report86.apkSha256-ne$state86.apkSha256){throw ('Native regression failed: '+$mode86)}
            $state86.modes+=@([ordered]@{mode=$mode86;status=$report86.status;assertions=$report86.checks;report=$reportPath86;apkSha256=$report86.apkSha256;screenshots=@($report86.screenshots.PSObject.Properties.Name)})
            Write-Output ($mode86+' PASS '+$report86.checks)
        } finally {if($sizeChanged86){& $adb86 -s $serial86 shell wm size reset|Out-Null;& $adb86 -s $serial86 shell wm density reset|Out-Null}}
        Save86
    }
    $state86.sourcesStable=$serialized86-ceq((Pins86)|ConvertTo-Json -Compress -Depth 5)
    if(-not$state86.sourcesStable){throw 'Inputs changed during regression'}
    $state86.status='PASS'
} catch {$state86.status='FAIL';$state86.failure=$_.Exception.Message;throw} finally {$state86.completedAtUtc=[DateTime]::UtcNow.ToString('O');Save86}
