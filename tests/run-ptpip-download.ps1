param([string]$JdkHome = 'D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$classes = Join-Path $PSScriptRoot '.build\ptpip-download'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('PictureControlData', 'PictureControlWriteValidation')) {
    $sources += Join-Path $javaRoot ('picturecontrol\' + $name + '.java')
}
foreach ($name in @('PhotoCameraClient', 'PtpIpClient', 'ConnectionTrace')) {
    $sources += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sources += Join-Path $PSScriptRoot 'java\cc\huevia\album\PtpIpClientTest.java'
$sources += Join-Path $PSScriptRoot 'java\cc\huevia\album\ConnectionTraceTest.java'
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'PTP/IP original-download host compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $classes) -cp $classes cc.huevia.album.PtpIpClientTest
if ($LASTEXITCODE -ne 0) { throw 'PTP/IP original-download checks failed.' }
