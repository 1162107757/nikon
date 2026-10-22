param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) { $JdkHome = $candidate; break }
    }
}
if (-not $JdkHome) { throw 'Specify -JdkHome for the pure-Java NP3 spatial checks.' }
$classes = Join-Path $PSScriptRoot '.build\np3-spatial\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @((Join-Path $projectRoot 'app\src\main\java\cc\huevia\ncp\SpatialPreviewFilter.java'))
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'spatial-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'NP3 spatial focused compilation failed.' }
# The streaming stress fixture represents far more pixels than this heap could hold
# as a full-frame source plus output. No Android runtime or device is involved.
& (Join-Path $JdkHome 'bin\java.exe') -Xmx64m -cp $classes cc.huevia.ncp.SpatialPreviewFilterTest
if ($LASTEXITCODE -ne 0) { throw 'NP3 spatial focused tests failed.' }
