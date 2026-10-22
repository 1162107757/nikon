param([string]$JdkHome = 'D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build\wifi-retention\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\connection\CameraWifiRetention.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\connection\CameraWifiSession.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\CameraWifiNetwork.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\ConnectionTrace.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\ptp\PtpResponseException.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\ptp\PtpProtocolException.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\ptp\PtpConstants.java')
)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'wifi-retention-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'wifi-retention-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi retention compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.connection.CameraWifiRetentionTest
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi retention checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.album.CameraWifiSocketNetworkTest
if ($LASTEXITCODE -ne 0) { throw 'Camera Wi-Fi socket target checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.album.CameraWifiGatewayTest
if ($LASTEXITCODE -ne 0) { throw 'Selected camera gateway/self-target checks failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.connection.CameraWifiSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Specific camera Wi-Fi session checks failed.' }
