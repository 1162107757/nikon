param([string]$JdkHome, [string]$ClassesDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$rawClassesDirectory = if ($ClassesDirectory) { [System.IO.Path]::GetFullPath($ClassesDirectory) } else { Join-Path $PSScriptRoot '.build\raw-store' }
New-Item -ItemType Directory -Force -Path $rawClassesDirectory | Out-Null
if ($JdkHome) { $compilerPath = Join-Path $JdkHome 'bin\javac.exe'; $runtimePath = Join-Path $JdkHome 'bin\java.exe' }
else { $compilerPath = (Get-Command javac -ErrorAction Stop).Source; $runtimePath = (Get-Command java -ErrorAction Stop).Source }
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('PhotoFormat', 'RawPhotoDownload', 'RawPhotoStore', 'RawPhotoPublisher', 'PhotoLocalTasks')) { $sourcePaths += Join-Path $javaRoot ('album\' + $name + '.java') }
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gallery-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += Join-Path $PSScriptRoot 'raw-java\cc\huevia\album\RawPhotoStorePublisherHostTest.java'
& $compilerPath --release 17 -encoding UTF-8 -d $rawClassesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'RAW original store/publication host compilation failed.' }
& $runtimePath ('-Djava.io.tmpdir=' + $rawClassesDirectory) -cp $rawClassesDirectory cc.huevia.album.RawPhotoStorePublisherHostTest
if ($LASTEXITCODE -ne 0) { throw 'RAW original store/publication host checks failed.' }
