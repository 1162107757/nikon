param([string]$JdkHome, [string]$ClassesDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = if ($ClassesDirectory) { $ClassesDirectory } else { Join-Path $PSScriptRoot '.build\fuji' }
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
foreach ($name in @('PhotoCameraClient', 'PtpIpClient', 'ConnectionTrace', 'FujifilmPhotoClient')) {
    $sourcePaths += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fuji-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Fujifilm transport compilation failed.' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.album.FujifilmPhotoClientTest
if ($LASTEXITCODE -ne 0) { throw 'Fujifilm transport tests failed.' }
