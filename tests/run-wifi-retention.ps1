param([string]$JdkHome = 'D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build\wifi-retention\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\connection\CameraWifiRetention.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\connection\CameraWifiSession.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\CameraWifiNetwork.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\ConnectionTrace.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ptp\PtpResponseException.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ptp\PtpProtocolException.java')
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ptp\PtpConstants.java')
)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'wifi-retention-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'wifi-retention-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi retention compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes com.nikonpreset.usbprobe.connection.CameraWifiRetentionTest
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi retention checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes com.nikonpreset.usbprobe.album.CameraWifiSocketNetworkTest
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi socket target checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes com.nikonpreset.usbprobe.album.CameraWifiGatewayTest
if ($LASTEXITCODE -ne 0) { throw 'Selected camera gateway/self-target checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes com.nikonpreset.usbprobe.connection.CameraWifiSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Specific camera Wi-Fi session checks failed.' }
