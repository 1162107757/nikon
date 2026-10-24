param([string]$JdkRoot='D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$testOutput=Join-Path $projectRoot ('.tooling\photo-histogram-tests\'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
& (Join-Path $JdkRoot 'bin\javac.exe') -encoding UTF-8 -d $testOutput (Join-Path $projectRoot 'app\src\main\java\cc\huevia\ncp\PhotoHistogram.java') (Join-Path $PSScriptRoot 'cc\huevia\ncp\PhotoHistogramTest.java')
if($LASTEXITCODE -ne 0){throw 'Histogram test compilation failed.'}
& (Join-Path $JdkRoot 'bin\java.exe') -cp $testOutput cc.huevia.ncp.PhotoHistogramTest
if($LASTEXITCODE -ne 0){throw 'Histogram tests failed.'}
