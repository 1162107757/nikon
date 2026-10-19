param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classesDirectory = Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null

if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}

$sourceDirectory = Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ptp'
$sourcePaths = @(Get-ChildItem -LiteralPath $sourceDirectory -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\picturecontrol') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += Join-Path $PSScriptRoot 'PtpCoreTest.java'
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ncp') -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\usb\NcpTransferSession.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\usb\StorageAssessment.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\usb\PtpDirectorySurvey.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\PtpIpClient.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\ConnectionTrace.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\PhotoItem.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\PhotoPreview.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\album\PhotoPreviewCache.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ui\AlbumPaging.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ui\PhotoSwipeGesture.java'
$sourcePaths += Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe\ui\PhotoPagerMotion.java'
$sourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'java') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sourcePaths += Join-Path $PSScriptRoot 'stubs\com\nikonpreset\usbprobe\usb\UsbNcpTransfer.java'
& $compilerPath --release 17 -encoding UTF-8 -d $classesDirectory @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'PTP core compilation failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ptp.PtpCoreTest
if ($LASTEXITCODE -ne 0) { throw 'PTP core tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ncp.NcpFileTest
if ($LASTEXITCODE -ne 0) { throw 'NCP structural tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.usb.NcpTransferSessionTest
if ($LASTEXITCODE -ne 0) { throw 'NCP transfer simulation tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.usb.StorageAssessmentTest
if ($LASTEXITCODE -ne 0) { throw 'Storage eligibility tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.usb.PtpDirectorySurveyTest
if ($LASTEXITCODE -ne 0) { throw 'Read-only directory survey tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.picturecontrol.PictureControlCodecTest
if ($LASTEXITCODE -ne 0) { throw 'Picture Control read-dataset tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ncp.NcpPictureControlConverterTest
if ($LASTEXITCODE -ne 0) { throw 'NCP compatibility adaptation tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ncp.NcpPreviewProfileTest
if ($LASTEXITCODE -ne 0) { throw 'NCP phone-template approximation tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ncp.Np3PreviewProfileTest
if ($LASTEXITCODE -ne 0) { throw 'NP3 phone-colour approximation tests failed' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory com.nikonpreset.usbprobe.album.PtpIpClientTest
if ($LASTEXITCODE -ne 0) { throw 'Read-only PTP/IP protocol tests failed' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory com.nikonpreset.usbprobe.album.ConnectionTraceTest
if ($LASTEXITCODE -ne 0) { throw 'Bounded connection trace tests failed' }
& $runtimePath ('-Djava.io.tmpdir=' + $classesDirectory) -cp $classesDirectory com.nikonpreset.usbprobe.album.PhotoPreviewCacheTest
if ($LASTEXITCODE -ne 0) { throw 'Photo preview cache tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ui.AlbumPagingTest
if ($LASTEXITCODE -ne 0) { throw 'Album pagination tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ui.PhotoSwipeGestureTest
if ($LASTEXITCODE -ne 0) { throw 'Photo swipe gesture tests failed' }
& $runtimePath -cp $classesDirectory com.nikonpreset.usbprobe.ui.PhotoPagerMotionTest
if ($LASTEXITCODE -ne 0) { throw 'Photo pager motion tests failed' }
& (Join-Path $PSScriptRoot 'run-album.ps1') -JdkHome $JdkHome
& (Join-Path $PSScriptRoot 'run-gallery.ps1') -JdkHome $JdkHome
& (Join-Path $PSScriptRoot 'run-photo-presets.ps1') -JdkHome $JdkHome
& (Join-Path $PSScriptRoot 'run-photo-dates.ps1') -JdkHome $JdkHome
