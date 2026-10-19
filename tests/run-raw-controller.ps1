param([string]$JdkHome='D:/dev/Java/jdk-17.0.19')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$javaRoot=Join-Path $projectRoot 'app/src/main/java/com/nikonpreset/usbprobe'
$classesDirectory=Join-Path $PSScriptRoot '.build/raw-controller'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sources=@()
foreach($directory in @('ptp','picturecontrol','ncp')) { $sources+=@(Get-ChildItem (Join-Path $javaRoot $directory) -Filter '*.java' | ForEach-Object FullName) }
foreach($name in @('PhotoCameraClient', 'CameraProtocol', 'FujifilmPhotoClient', 'AlbumController','PtpIpClient','ConnectionTrace','PhotoItem','PhotoFormat','RawPhotoPairing','RawPhotoDownload','RawPhotoStore','RawJpegReferenceStore','PhotoPreview','PhotoPreviewCache','DownloadedPhoto','PhotoPage','ConnectionInfo','PhotoLiveState','PhotoLiveLedger','PhotoLiveReceipt','PhotoLiveReceiptStore','PhotoLiveProject','PhotoLiveProjectStore','PhotoLiveProjectFolders','PhotoLocalTasks','PhotoDeleteResult','PhotoAlbumDeletion','PhotoOriginalStore','PhotoPresetSources')) { $sources+=Join-Path $javaRoot ('album/'+$name+'.java') }
$sources+=Join-Path $PSScriptRoot 'android-stubs/android/os/Looper.java'
$sources+=Join-Path $PSScriptRoot 'gallery-stubs/android/util/AtomicFile.java'
foreach($directory in @('deletion-stubs','album-stubs','album-java','raw-controller-stubs','raw-controller-java')) { $sources+=@(Get-ChildItem (Join-Path $PSScriptRoot $directory) -Recurse -Filter '*.java' | ForEach-Object FullName) }
$sources+=Join-Path $PSScriptRoot 'raw-java/com/nikonpreset/usbprobe/album/RawFormatPairingTest.java'
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -d $classesDirectory @sources
if($LASTEXITCODE -ne 0){throw 'RAW controller compilation failed.'}
foreach($test in @('RawFormatPairingTest','RawJpegReferenceTest','RawControllerHostTest','RawPairTransportHostTest')) {
  & (Join-Path $JdkHome 'bin/java.exe') ('-Djava.io.tmpdir='+$classesDirectory) -cp $classesDirectory ('com.nikonpreset.usbprobe.album.'+$test)
  if($LASTEXITCODE -ne 0){throw ('RAW test failed: '+$test)}
}
