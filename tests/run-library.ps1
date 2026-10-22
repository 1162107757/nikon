param([string]$JdkHome, [string]$AndroidJar, [string]$PresetArchive)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) { $JdkHome = Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1' }
if (-not $AndroidJar) { $AndroidJar = Join-Path $projectRoot '.tooling\sdk\platforms\android-35\android.jar' }
$classes = Join-Path $PSScriptRoot '.build\library\classes'
$scratch = Join-Path $PSScriptRoot '.build\library\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java\cc\huevia\library') -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $projectRoot 'app\src\main\java\cc\huevia\ncp\NcpFile.java'
$sources += Join-Path $PSScriptRoot 'library-java\cc\huevia\library\PresetLibraryTest.java'
$sources += Join-Path $PSScriptRoot 'library-java\cc\huevia\library\EditorPresetCatalogTest.java'
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -sourcepath (Join-Path $projectRoot 'app/src/main/java') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Library targeted compilation failed.' }
$testArguments = @($scratch)
if ($PresetArchive) { $testArguments += [IO.Path]::GetFullPath($PresetArchive) }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) cc.huevia.library.PresetLibraryTest @testArguments
if ($LASTEXITCODE -ne 0) { throw 'Library targeted regression failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) cc.huevia.library.EditorPresetCatalogTest $scratch
if ($LASTEXITCODE -ne 0) { throw 'Editor preset catalog regression failed.' }
