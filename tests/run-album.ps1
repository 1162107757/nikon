param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = Join-Path $PSScriptRoot '.build\album'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}
$javaRoot = Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe'
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'picturecontrol') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ncp') -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('AlbumController', 'PtpIpClient', 'PhotoItem', 'PhotoPreview', 'PhotoPreviewCache', 'DownloadedPhoto', 'PhotoPage', 'ConnectionInfo')) {
    $sourcePaths += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sourcePaths += Join-Path $PSScriptRoot 'android-stubs\android\os\Looper.java'
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'album-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'album-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Production album controller host compilation failed.' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.album.AlbumControllerHostTest
if ($LASTEXITCODE -ne 0) { throw 'Production album controller tests failed.' }
