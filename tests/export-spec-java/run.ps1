param([string]$JdkHome = 'D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$classes = Join-Path $root '.tooling\photo-export\spec-host'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes (Join-Path $root 'app\src\main\java\cc\huevia\album\PhotoExportSpec.java') (Join-Path $PSScriptRoot 'cc\huevia\album\PhotoExportSpecTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Export specification compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.album.PhotoExportSpecTest
if ($LASTEXITCODE -ne 0) { throw 'Export specification tests failed.' }
