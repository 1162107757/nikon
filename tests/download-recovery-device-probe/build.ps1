param([string]$SdkRoot='D:\dev\android-sdk',[string]$JdkRoot='D:\dev\Java\jdk-17.0.19',[int]$ExpectedVersion=73)
$ErrorActionPreference='Stop'
$probeRoot=[IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot=[IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$versionRoot=Join-Path $probeRoot ('.build\'+$ExpectedVersion)
$outputRoot=Join-Path $versionRoot ([Guid]::NewGuid().ToString('N'))
$classes=Join-Path $outputRoot 'classes'
$dex=Join-Path $outputRoot 'dex'
foreach($directory in @($classes,$dex)){New-Item -ItemType Directory -Force -Path $directory|Out-Null}
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools=Join-Path $SdkRoot 'build-tools\35.0.0'
function Run([string]$Exe,[string[]]$Arguments){& $Exe @Arguments;if($LASTEXITCODE -ne 0){throw 'Download recovery instrumentation build failed.'}}
$unsigned=Join-Path $outputRoot 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $probeRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0','-o',$unsigned)
$argsFile=Join-Path $outputRoot 'javac.args'
$lines=@('-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','-source','17','-target','17','-classpath',('"'+$androidJar.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'))
$lines+=Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Recurse -Filter '*.java'|ForEach-Object{'"'+$_.FullName.Replace('\','/')+'"'}
[IO.File]::WriteAllLines($argsFile,$lines,[Text.UTF8Encoding]::new($false))
Run (Join-Path $JdkRoot 'bin\javac.exe') @('@'+$argsFile)
$jar=Join-Path $outputRoot 'classes.jar'
Run (Join-Path $JdkRoot 'bin\jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
$priorJavaHome=$env:JAVA_HOME
try{
    $env:JAVA_HOME=$JdkRoot
    Run (Join-Path $buildTools 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--output',$dex,$jar)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
    try{foreach($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex'){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$archive.Dispose()}
    $aligned=Join-Path $outputRoot 'aligned.apk'
    Run (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
    $final=Join-Path $versionRoot 'download-recovery-probe.apk'
    Run (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',(Join-Path $projectRoot '.tooling\debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$final,$aligned)
    Run (Join-Path $buildTools 'apksigner.bat') @('verify',$final)
    Write-Output ('Download recovery instrumentation: '+$final)
}finally{$env:JAVA_HOME=$priorJavaHome}
