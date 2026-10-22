param([string]$JdkHome, [string]$CorpusZip)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = Join-Path $PSScriptRoot '.build\np3'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null

if (-not $JdkHome) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) { $JdkHome = $candidate; break }
    }
}
if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}

$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sources = @()
foreach ($name in @('NcpFile', 'NcpPreviewProfile', 'PresetPreviewProfile', 'Np3PreviewProfile', 'Np3MonochromePreviewProfile', 'PresetPreviewProfiles', 'SpatialPreviewFilter', 'StrengthPreviewProfile')) {
    $sources += Join-Path $javaRoot ('ncp\' + $name + '.java')
}
$sources += Join-Path $javaRoot 'library\LibraryJson.java'
$sources += Join-Path $javaRoot 'library\PresetFile.java'
$sources += Join-Path $PSScriptRoot 'java\cc\huevia\ncp\Np3PreviewProfileTest.java'
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'np3-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'NP3 phone-renderer compilation failed.' }
& $runtimePath '-Dfile.encoding=UTF-8' -cp $classesDirectory cc.huevia.ncp.Np3PreviewProfileTest
if ($LASTEXITCODE -ne 0) { throw 'NP3 synthetic renderer tests failed.' }
& $runtimePath '-Dfile.encoding=UTF-8' -cp $classesDirectory cc.huevia.ncp.Np3MonochromePreviewProfileTest
if ($LASTEXITCODE -ne 0) { throw 'NP3 monochrome renderer tests failed.' }
& $runtimePath '-Dfile.encoding=UTF-8' -cp $classesDirectory cc.huevia.ncp.Np3StandardMonochromePreviewProfileTest
if ($LASTEXITCODE -ne 0) { throw 'NP3 standard monochrome renderer tests failed.' }

if ($CorpusZip) {
    if (-not (Test-Path -LiteralPath $CorpusZip -PathType Leaf)) { throw 'The requested NP3 corpus ZIP does not exist.' }
    $CorpusZip = [IO.Path]::GetFullPath($CorpusZip)
} else {
    $CorpusZip = Join-Path $projectRoot 'dist\nikon-cloud-recipes-20261018\nikon-cloud-recipes-142.zip'
}
if (Test-Path -LiteralPath $CorpusZip -PathType Leaf) {
    & $runtimePath '-Dfile.encoding=UTF-8' -cp $classesDirectory cc.huevia.ncp.Np3CorpusTest $CorpusZip
    if ($LASTEXITCODE -ne 0) { throw 'NP3 real-recipe corpus tests failed.' }
} else {
    Write-Host 'SKIP: local 142-recipe NP3 ZIP is absent; synthetic renderer checks passed. No samples were downloaded.'
}
