param([string]$SdkRoot='D:\dev\android-sdk', [string]$JdkRoot='D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$outputRoot=Join-Path $PSScriptRoot '.build'
$work=Join-Path $outputRoot ([Guid]::NewGuid().ToString('N'))
$classes=Join-Path $work 'classes'; $dex=Join-Path $work 'dex'
foreach($directory in @($classes,$dex)){New-Item -ItemType Directory -Force -Path $directory | Out-Null}
$source=Join-Path $PSScriptRoot 'src\cc\huevia\imageupgradeqa\PhotoImageUpgradeProbe.java'
$inputs=[ordered]@{
    probeJavaSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()
    manifestSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml')).Hash.ToLowerInvariant()
    buildScriptSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    runScriptSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot 'run.ps1')).Hash.ToLowerInvariant()
}
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools=Join-Path $SdkRoot 'build-tools\35.0.0'
function Run([string]$Exe,[string[]]$Arguments){& $Exe @Arguments; if($LASTEXITCODE -ne 0){throw ('Standalone image-upgrade probe build failed: '+$Exe)}}
$unsigned=Join-Path $work 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0','-o',$unsigned)
$argsFile=Join-Path $work 'javac.args'
$lines=@('-J-Xmx256m','-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','-source','17','-target','17','-classpath',('"'+$androidJar.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'),('"'+$source.Replace('\','/')+'"'))
[IO.File]::WriteAllLines($argsFile,$lines,[Text.UTF8Encoding]::new($false))
Run (Join-Path $JdkRoot 'bin\javac.exe') @('@'+$argsFile)
$jar=Join-Path $work 'classes.jar'
Run (Join-Path $JdkRoot 'bin\jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
$priorJavaHome=$env:JAVA_HOME; $priorJavaOpts=$env:JAVA_OPTS
try{$env:JAVA_HOME=$JdkRoot; $env:JAVA_OPTS='-Xmx256m'; Run (Join-Path $buildTools 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--output',$dex,$jar)}
finally{$env:JAVA_HOME=$priorJavaHome; $env:JAVA_OPTS=$priorJavaOpts}
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$archive=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try{foreach($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex'){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}
finally{$archive.Dispose()}
$aligned=Join-Path $work 'aligned.apk'; $final=Join-Path $outputRoot 'image-upgrade-probe.apk'
Run (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
Run (Join-Path $JdkRoot 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'sign','--ks',(Join-Path $projectRoot '.tooling\debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$final,$aligned)
Run (Join-Path $JdkRoot 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'verify',$final)
if($inputs.probeJavaSha256 -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash.ToLowerInvariant()){throw 'Probe source changed during compilation; rebuild.'}
$provenance=[ordered]@{schemaVersion=1; builtAtUtc=[DateTime]::UtcNow.ToString('O'); probeSource='tests/photo-image-upgrade-device-probe/src/cc/huevia/imageupgradeqa/PhotoImageUpgradeProbe.java'; inputs=$inputs; probeApkSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $final).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText((Join-Path $outputRoot 'provenance.json'),($provenance|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output ('Standalone image-upgrade probe: '+$final)
