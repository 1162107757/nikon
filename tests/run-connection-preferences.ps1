param([string]$JdkHome)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build\connection-preferences'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\album\CameraProtocol.java')
    (Join-Path $projectRoot 'app\src\main\java\cc\huevia\connection\CameraConnectionPreferences.java')
)
foreach ($directory in @('connection-preferences-stubs', 'connection-preferences-java')) {
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $directory) -Recurse -Filter '*.java' | ForEach-Object FullName)
}
& $compilerPath --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Camera connection preference compilation failed.' }
& $runtimePath -cp $classes cc.huevia.connection.CameraConnectionPreferencesTest
if ($LASTEXITCODE -ne 0) { throw 'Camera connection preference tests failed.' }
