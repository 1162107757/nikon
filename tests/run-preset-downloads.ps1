param([string]$JdkHome = 'D:/dev/Java/jdk-17.0.19', [string]$AndroidJar = 'D:/dev/android-sdk/platforms/android-35/android.jar')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $PSScriptRoot ('.build/preset-downloads/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app/src/main/java/com/nikonpreset/usbprobe/library') -Filter *.java | ForEach-Object FullName)
$sources += Join-Path $projectRoot 'app/src/main/java/com/nikonpreset/usbprobe/ncp/NcpFile.java'
$sources += Join-Path $PSScriptRoot 'library-java/com/nikonpreset/usbprobe/library/PresetDownloadTasksTest.java'
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -sourcepath (Join-Path $projectRoot 'app/src/main/java') -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Preset download host compilation failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp ($output + [IO.Path]::PathSeparator + $AndroidJar) com.nikonpreset.usbprobe.library.PresetDownloadTasksTest $output
if ($LASTEXITCODE -ne 0) { throw 'Preset download host checks failed.' }
