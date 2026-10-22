param([string]$JdkHome, [string]$ClassesDirectory, [switch]$ReceiptsOnly, [switch]$GatewayOnly, [switch]$DownloadRecoveryOnly)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = if ($ClassesDirectory) { $ClassesDirectory } else { Join-Path $PSScriptRoot '.build\album' }
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'picturecontrol') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ncp') -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('PhotoCameraClient', 'CameraProtocol', 'FujifilmPhotoClient', 'AlbumController', 'PhotoDownloadRecovery', 'RawPhotoDownload', 'RawPhotoStore', 'RawPhotoPairing', 'RawJpegReferenceStore', 'PtpIpClient', 'ConnectionTrace', 'PhotoItem', 'PhotoFormat', 'PhotoPreview', 'PhotoPreviewCache', 'DownloadedPhoto', 'PhotoPage', 'ConnectionInfo', 'PhotoLiveState', 'PhotoLiveLedger', 'PhotoLiveReceipt', 'PhotoLiveReceiptStore', 'PhotoLiveProject', 'PhotoLiveProjectStore', 'PhotoLiveProjectFolders', 'PhotoLocalTasks', 'PhotoDeleteResult', 'PhotoAlbumDeletion', 'PhotoOriginalStore', 'PhotoPresetSources')) {
    $sourcePaths += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sourcePaths += Join-Path $PSScriptRoot 'raw-controller-stubs\cc\huevia\album\RawPhotoPublisher.java'
$sourcePaths += Join-Path $PSScriptRoot 'android-stubs\android\os\Looper.java'
$sourcePaths += Join-Path $PSScriptRoot 'gallery-stubs\android\util\AtomicFile.java'
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'deletion-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'album-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'album-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Production album controller host compilation failed.' }
if ($DownloadRecoveryOnly) {
    & $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.AlbumControllerHostTest download-recovery
    if ($LASTEXITCODE -ne 0) { throw 'Interrupted original recovery tests failed.' }
    return
}
if ($GatewayOnly) {
    & $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.CameraGatewayConnectionHostTest
    if ($LASTEXITCODE -ne 0) { throw 'Gateway connection controller tests failed.' }
    return
}
if ($ReceiptsOnly) {
    & $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.AlbumControllerHostTest receipts
} else {
    & $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.AlbumControllerHostTest
}
if ($LASTEXITCODE -ne 0) { throw 'Production album controller tests failed.' }
if ($ReceiptsOnly) { return }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.PhotoCameraCapabilityHostTest
if ($LASTEXITCODE -ne 0) { throw 'Camera capability controller tests failed.' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.CameraGatewayConnectionHostTest
if ($LASTEXITCODE -ne 0) { throw 'Gateway connection controller tests failed.' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.PhotoLiveProjectStoreTest
if ($LASTEXITCODE -ne 0) { throw 'Photo live project store tests failed.' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory cc.huevia.album.PhotoLiveProjectFoldersTest
if ($LASTEXITCODE -ne 0) { throw 'Photo live project folder binding tests failed.' }
