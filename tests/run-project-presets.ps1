param([string]$JdkHome)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) { $JdkHome = $candidate; break }
    }
}
if (-not $JdkHome) { throw 'Specify -JdkHome for project-preset checks.' }
$classes = Join-Path $PSScriptRoot '.build\project-presets\classes'
$scratch = Join-Path $PSScriptRoot '.build\project-presets\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$source = Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\PhotoProjectPresetStore.java'
$tests = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'project-preset-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes $source @tests
if ($LASTEXITCODE -ne 0) { throw 'Project-preset focused compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp $classes com.nikonpreset.usbprobe.album.PhotoProjectPresetStoreTest
if ($LASTEXITCODE -ne 0) { throw 'Project-preset persistence checks failed.' }
