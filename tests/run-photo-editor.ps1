param([string]$JdkHome='D:\dev\Java\jdk-17.0.19')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=Join-Path $PSScriptRoot '.build\photo-editor'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$javaRoot=Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sources=@(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ncp') -Filter '*.java' | ForEach-Object FullName)
$sources+=Join-Path $javaRoot 'album\PhotoEditStore.java'
$sources+=Join-Path $javaRoot 'album\PhotoRecipeStore.java'
$sources+=Join-Path $PSScriptRoot 'java\cc\huevia\ncp\NcpCompatibilityFixtures.java'
$sources+=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'editor-java') -Filter '*.java' -Recurse | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $output @sources
if($LASTEXITCODE -ne 0){throw 'Photo editor core compilation failed.'}
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir='+$output) -cp $output cc.huevia.album.PhotoEditorCoreTest
if($LASTEXITCODE -ne 0){throw 'Photo editor core verification failed.'}
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir='+$output) -cp $output cc.huevia.album.PhotoAdjustmentsTest
if($LASTEXITCODE -ne 0){throw 'Basic photo adjustment verification failed.'}
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir='+$output) -cp $output cc.huevia.album.PhotoCompositionTest
if($LASTEXITCODE -ne 0){throw 'Photo composition/recipe verification failed.'}
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir='+$output) -cp $output cc.huevia.album.PhotoBorderTest
if($LASTEXITCODE -ne 0){throw 'Photo border migration/identity verification failed.'}
