param([string[]]$Modes=@('phone','large-font','landscape','dark','tablet'),[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidence=Join-Path $project 'docs/validation/orange-ui-84'
$adb='D:/dev/Android/Sdk/platform-tools/adb.exe';$serial='emulator-5582'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
function Pins {
    $files=@(Get-ChildItem (Join-Path $project 'app/src') -File -Recurse)
    $files+=Get-ChildItem (Join-Path $project 'app/libs') -File -Recurse
    $files+=Get-ChildItem $PSScriptRoot -File -Recurse
    $files+=Get-Item (Join-Path $project 'app/build.gradle'),(Join-Path $project 'scripts/build-apk.ps1')
    @($files|Sort-Object FullName|ForEach-Object{[ordered]@{path=[IO.Path]::GetRelativePath($project,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
}
$before=Pins;$serialized=$before|ConvertTo-Json -Depth 5 -Compress
$state=[ordered]@{startedAtUtc=[DateTime]::UtcNow.ToString('O');status='RUNNING';sources=$before;modes=@();scope='Dedicated read-only Android emulator; actual native Views/JPEG/EXIF/MediaStore. Generated photo/structural NEF and typed camera-slot fixtures. No real camera, live catalog, account mutation or publication; no physical-device performance/TalkBack certification.'}
function Save { [IO.File]::WriteAllText((Join-Path $evidence 'matrix.json'),($state|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false)) }
Save
try {
    if(-not$SkipBuild){
        $env:JAVA_TOOL_OPTIONS='-Xmx512m';$env:JAVA_OPTS='-Xmx512m'
        & (Join-Path $project 'scripts/build-apk.ps1') -SdkRoot D:/dev/android-sdk -JdkRoot D:/dev/Java/jdk-17.0.19 *> (Join-Path $evidence 'build.txt')
        if($LASTEXITCODE-ne0){throw 'App build failed'}
    }
    if(($serialized)-cne((Pins)|ConvertTo-Json -Depth 5 -Compress)){throw 'Source changed during build'}
    $apk=Join-Path $project 'dist/huevia-0.17.0-beta-debug.apk'
    $state.apkSha256=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant();$state.apkBytes=(Get-Item $apk).Length
    & $adb -s $serial install --no-incremental -r $apk *> (Join-Path $evidence 'install.txt');if($LASTEXITCODE-ne0){throw 'Install failed'}
    foreach($mode in $Modes){
        $sizeChanged=$false
        try{
            if($mode-eq'tablet'){& $adb -s $serial shell wm size 1280x1800|Out-Null;& $adb -s $serial shell wm density 240|Out-Null;$sizeChanged=$true}
            Write-Output ('Running '+$mode)
            & (Join-Path $PSScriptRoot 'run.ps1') -Mode $mode -ExpectedVersion 84 -Scope local-album *> (Join-Path $evidence ($mode+'-runner.txt'))
            $report=Get-Content (Join-Path $evidence ('local-album/'+$mode+'/report.json')) -Raw|ConvertFrom-Json
            if($report.status-ne'PASS'-or$report.apkSha256-ne$state.apkSha256){throw ('Native test failed: '+$mode)}
            $state.modes+=@([ordered]@{mode=$mode;status=$report.status;assertions=$report.checks;apkSha256=$report.apkSha256;screenshots=@($report.screenshots.PSObject.Properties.Name)})
            Write-Output ($mode+' PASS '+$report.checks)
        }finally{if($sizeChanged){& $adb -s $serial shell wm size reset|Out-Null;& $adb -s $serial shell wm density reset|Out-Null}}
        Save
    }
    $state.sourcesStable=($serialized)-ceq((Pins)|ConvertTo-Json -Depth 5 -Compress)
    if(-not$state.sourcesStable){throw 'Sources changed during native verification'}
    $state.status='PASS'
}catch{$state.status='FAIL';$state.failure=$_.Exception.Message;throw}finally{$state.completedAtUtc=[DateTime]::UtcNow.ToString('O');Save}
