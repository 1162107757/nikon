param([string]$JdkHome='D:/dev/Java/jdk-17.0.19',[string]$AndroidJar='D:/dev/android-sdk/platforms/android-35/android.jar')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $PSScriptRoot '.build/personal-presets/classes'
$scratch=Join-Path $PSScriptRoot '.build/personal-presets/files'
New-Item -ItemType Directory -Force $output,$scratch | Out-Null
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -sourcepath (Join-Path $projectRoot 'app/src/main/java') -d $output (Join-Path $PSScriptRoot 'library-java/cc/huevia/library/PersonalPresetSyncTest.java')
if($LASTEXITCODE -ne 0){throw 'Personal sync compilation failed.'}
& (Join-Path $JdkHome 'bin/java.exe') -cp ($output+';'+$AndroidJar) cc.huevia.library.PersonalPresetSyncTest $scratch
if($LASTEXITCODE -ne 0){throw 'Personal sync regression failed.'}
