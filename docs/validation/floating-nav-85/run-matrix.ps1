param([string[]]$Modes=@('phone','large-font','landscape','dark','tablet'))
$ErrorActionPreference='Stop'
$project85=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$adb85='D:/dev/Android/Sdk/platform-tools/adb.exe'; $serial85='emulator-5582'
$apk85=Join-Path $project85 'dist/huevia-0.17.1-beta-debug.apk'
function Pins85 {
    $inputs85=@(Get-ChildItem (Join-Path $project85 'app/src') -File -Recurse)
    $inputs85+=Get-ChildItem (Join-Path $project85 'app/libs') -File -Recurse
    $inputs85+=Get-ChildItem (Join-Path $project85 'tests/orange-ui-device-probe') -File -Recurse
    $inputs85+=Get-Item (Join-Path $project85 'app/build.gradle'),(Join-Path $project85 'scripts/build-apk.ps1')
    @($inputs85|Sort-Object FullName|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($project85,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
}
$pins85=Pins85; $serialized85=$pins85|ConvertTo-Json -Compress -Depth 5
$state85=[ordered]@{startedAtUtc=[DateTime]::UtcNow.ToString('O');status='RUNNING';versionCode=85;apkSha256=(Get-FileHash $apk85 -Algorithm SHA256).Hash.ToLowerInvariant();apkBytes=(Get-Item $apk85).Length;sources=$pins85;modes=@();scope='Existing local-album regression on a task-owned read-only emulator. Synthetic slots/photos only. Phone floating bar and unchanged wide rail. No physical phone connected, camera or live network certification.'}
function Save85 { [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'matrix.json'),($state85|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
Save85
try {
    & $adb85 -s $serial85 install --no-incremental -r $apk85 *> (Join-Path $PSScriptRoot 'install.txt'); if($LASTEXITCODE-ne0){throw 'Candidate install failed'}
    & $adb85 -s $serial85 shell pm grant cc.huevia android.permission.POST_NOTIFICATIONS; if($LASTEXITCODE-ne0){throw 'Notification isolation failed'}
    foreach($mode85 in $Modes){
        $sizeChanged85=$false
        try {
            if($mode85-eq'tablet'){& $adb85 -s $serial85 shell wm size 1280x1800|Out-Null;& $adb85 -s $serial85 shell wm density 240|Out-Null;$sizeChanged85=$true}
            & (Join-Path $project85 'tests/orange-ui-device-probe/run.ps1') -Mode $mode85 -ExpectedVersion 85 -Scope local-album *> (Join-Path $PSScriptRoot ($mode85+'-runner.txt'))
            $reportPath85='docs/validation/orange-ui-85/local-album/'+$mode85+'/report.json'
            $report85=Get-Content (Join-Path $project85 $reportPath85) -Raw|ConvertFrom-Json
            if($report85.status-ne'PASS'-or$report85.apkSha256-ne$state85.apkSha256){throw ('Native regression failed: '+$mode85)}
            $state85.modes+=@([ordered]@{mode=$mode85;status=$report85.status;assertions=$report85.checks;report=$reportPath85;apkSha256=$report85.apkSha256;screenshots=@($report85.screenshots.PSObject.Properties.Name)})
            Write-Output ($mode85+' PASS '+$report85.checks)
        } finally {if($sizeChanged85){& $adb85 -s $serial85 shell wm size reset|Out-Null;& $adb85 -s $serial85 shell wm density reset|Out-Null}}
        Save85
    }
    $state85.sourcesStable=$serialized85-ceq((Pins85)|ConvertTo-Json -Compress -Depth 5)
    if(-not$state85.sourcesStable){throw 'Inputs changed during regression'}
    $state85.status='PASS'
} catch {$state85.status='FAIL';$state85.failure=$_.Exception.Message;throw} finally {$state85.completedAtUtc=[DateTime]::UtcNow.ToString('O');Save85}
