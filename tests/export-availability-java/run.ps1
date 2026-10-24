param([string]$JdkRoot='D:/dev/Java/jdk-17.0.19')
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$testOutput=Join-Path $repository ('.tooling/photo-export-availability-tests/'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
$sources=@((Join-Path $repository 'app/src/main/java/cc/huevia/ui/PhotoExportAvailability.java'),(Join-Path $repository 'app/src/main/java/cc/huevia/album/PhotoExportSpec.java'),(Join-Path $repository 'app/src/main/java/cc/huevia/album/PhotoSaveTarget.java'))
$sources+=@(Get-ChildItem -LiteralPath $PSScriptRoot -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkRoot 'bin/javac.exe') -encoding UTF-8 -d $testOutput $sources
if($LASTEXITCODE -ne 0){throw 'Export availability compilation failed.'}
& (Join-Path $JdkRoot 'bin/java.exe') -cp $testOutput cc.huevia.ui.PhotoExportAvailabilityTest
if($LASTEXITCODE -ne 0){throw 'Export availability checks failed.'}
