param([string]$SdkRoot='D:\dev\android-sdk', [string]$JdkRoot='D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$outputRoot=Join-Path $PSScriptRoot '.build'; $work=Join-Path $outputRoot ([Guid]::NewGuid().ToString('N'))
$api=Join-Path $work 'api'; $classes=Join-Path $work 'classes'; $dex=Join-Path $work 'dex'
foreach($directory in @($api,$classes,$dex)){New-Item -ItemType Directory -Force -Path $directory | Out-Null}
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'; $buildTools=Join-Path $SdkRoot 'build-tools\35.0.0'
$source=Join-Path $PSScriptRoot 'src\cc\huevia\exportqa\PhotoExportProbe.java'
function Run([string]$Exe,[string[]]$Arguments){& $Exe @Arguments; if($LASTEXITCODE -ne 0){throw ('Export probe build failed: '+$Exe)}}
# Compile API signatures outside the probe classes: production classes are NEVER bundled into the instrumentation APK.
$sourcePath=(Join-Path $projectRoot 'app\src\main\java')+';'+(Join-Path $projectRoot 'app\build\manual\generated')
Run (Join-Path $JdkRoot 'bin\javac.exe') @('-encoding','UTF-8','--release','17','-cp',$androidJar,'-sourcepath',$sourcePath,'-d',$api,(Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\PhotoPresetRenderer.java'),(Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\PhotoPresetBatch.java'))
Run (Join-Path $JdkRoot 'bin\javac.exe') @('-encoding','UTF-8','--release','17','-cp',($androidJar+';'+$api),'-d',$classes,$source)
$jar=Join-Path $work 'probe-classes.jar'
Run (Join-Path $JdkRoot 'bin\jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
$unsigned=Join-Path $work 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','--version-code','1','--version-name','1.0','-o',$unsigned)
$priorJavaHome=$env:JAVA_HOME; $priorJavaOpts=$env:JAVA_OPTS
try{$env:JAVA_HOME=$JdkRoot; $env:JAVA_OPTS='-Xmx256m'; Run (Join-Path $buildTools 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--classpath',$api,'--output',$dex,$jar)}
finally{$env:JAVA_HOME=$priorJavaHome; $env:JAVA_OPTS=$priorJavaOpts}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try{foreach($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex'){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal)|Out-Null}}finally{$archive.Dispose()}
$aligned=Join-Path $work 'aligned.apk'; $final=Join-Path $outputRoot 'photo-export-probe.apk'
Run (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
Run (Join-Path $JdkRoot 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'sign','--ks',(Join-Path $projectRoot '.tooling\debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$final,$aligned)
Run (Join-Path $JdkRoot 'bin\java.exe') @('-jar',(Join-Path $buildTools 'lib\apksigner.jar'),'verify',$final)
$pins=[ordered]@{builtAtUtc=[DateTime]::UtcNow.ToString('O'); probeApkSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $final).Hash.ToLowerInvariant(); inputs=[ordered]@{}}
foreach($file in @($source,$PSCommandPath,(Join-Path $PSScriptRoot 'AndroidManifest.xml'),(Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\PhotoPresetRenderer.java'),(Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\PhotoPresetBatch.java'),(Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\PhotoExportSpec.java'))){$pins.inputs[$file]=(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()}
[IO.File]::WriteAllText((Join-Path $outputRoot 'provenance.json'),($pins|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output ('Standalone export probe: '+$final)
