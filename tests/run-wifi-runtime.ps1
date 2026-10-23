param([string]$JdkHome = 'D:\dev\Java\jdk-17.0.19', [string]$AndroidJar = 'D:\dev\android-sdk\platforms\android-35\android.jar')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build\wifi-runtime\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @()
foreach ($name in @('CameraWifiSession', 'CameraConnectionRuntime')) { $sources += Join-Path $projectRoot ('app\src\main\java\cc\huevia\connection\' + $name + '.java') }
foreach ($name in @('PhotoSaveTarget', 'CameraWifiNetwork', 'ConnectionTrace', 'PhotoLiveState', 'CameraProtocol', 'ConnectionInfo')) { $sources += Join-Path $projectRoot ('app\src\main\java\cc\huevia\album\' + $name + '.java') }
foreach ($directory in @('wifi-retention-stubs', 'wifi-runtime-stubs', 'wifi-runtime-java')) { $sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $directory) -Recurse -Filter '*.java' | ForEach-Object FullName) }
$compilePath = $AndroidJar + [IO.Path]::PathSeparator + (Join-Path $projectRoot 'app\build\manual\classes')
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $compilePath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Actual runtime Wi-Fi loss test compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($classes + [IO.Path]::PathSeparator + $compilePath) cc.huevia.connection.CameraWifiRuntimeLossTest
if ($LASTEXITCODE -ne 0) { throw 'Actual runtime Wi-Fi loss tests failed.' }
