param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    foreach ($candidate in @($env:JAVA_HOME, (Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
            $JdkHome = $candidate; break
        }
    }
}
if (-not $JdkHome) { throw 'Specify -JdkHome for the pure-Java live-continuation checks.' }
$classes = Join-Path $PSScriptRoot '.build\live-continuation\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(
    (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\connection\PhotoLiveContinuation.java'),
    (Join-Path $PSScriptRoot 'live-continuation-java\com\nikonpreset\usbprobe\connection\PhotoLiveContinuationTest.java')
)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Photo-live continuation compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes com.nikonpreset.usbprobe.connection.PhotoLiveContinuationTest
if ($LASTEXITCODE -ne 0) { throw 'Photo-live continuation checks failed.' }
