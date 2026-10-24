param([string]$JdkHome, [string]$ClassesDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = if ($ClassesDirectory) { $ClassesDirectory } else { Join-Path $PSScriptRoot '.build\gallery' }
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
if ($JdkHome) { $compilerPath = Join-Path $JdkHome 'bin\javac.exe'; $runtimePath = Join-Path $JdkHome 'bin\java.exe' }
else { $compilerPath = (Get-Command javac -ErrorAction Stop).Source; $runtimePath = (Get-Command java -ErrorAction Stop).Source }
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ncp') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += Join-Path $javaRoot 'album\PhotoEditStore.java'
$sourcePaths += Join-Path $PSScriptRoot 'batch-stubs\android\media\ExifInterface.java'
foreach ($name in @('PhotoSaveTarget', 'LocalPhotoOrigin', 'PhotoItem', 'PhotoFormat', 'PhotoPreview', 'PhotoPreviewCache', 'DownloadedPhoto', 'PhotoLiveProject', 'PhotoLiveProjectStore', 'PhotoLiveProjectFolders', 'PhotoLiveGallery', 'PhotoEffectGallery', 'PhotoAlbumSaver', 'PhotoLocalTasks', 'PhotoDeleteResult', 'PhotoAlbumDeletion', 'PhotoOriginalStore', 'PhotoLiveLedger', 'PhotoPresetSources')) {
    $sourcePaths += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gallery-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'deletion-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'gallery-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Photo album saver host compilation failed.' }
& $runtimePath -cp $classesDirectory cc.huevia.album.PhotoAlbumSaverHostTest
if ($LASTEXITCODE -ne 0) { throw 'Photo album saver host checks failed.' }
& $runtimePath -cp $classesDirectory cc.huevia.album.LocalPhotoOriginHostTest
if ($LASTEXITCODE -ne 0) { throw 'Imported phone photo origin/publication checks failed.' }
& $runtimePath -cp $classesDirectory cc.huevia.album.PhotoAlbumDeletionHostTest
if ($LASTEXITCODE -ne 0) { throw 'Local photo deletion host checks failed.' }
& $runtimePath -cp $classesDirectory cc.huevia.album.PhotoOriginalStoreHostTest
if ($LASTEXITCODE -ne 0) { throw 'Original byte identity host checks failed.' }
& $runtimePath -cp $classesDirectory cc.huevia.album.JpegValidationHostTest
if ($LASTEXITCODE -ne 0) { throw 'JPEG primary-stream validation host checks failed.' }
