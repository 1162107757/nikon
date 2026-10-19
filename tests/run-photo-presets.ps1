param([string]$JdkHome, [string]$AndroidJar)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) { $JdkHome = $candidate; break }
    }
}
if (-not $AndroidJar) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\sdk\platforms\android-35\android.jar'), 'D:\dev\android-sdk\platforms\android-35\android.jar')) {
        if (Test-Path -LiteralPath $candidate) { $AndroidJar = $candidate; break }
    }
}
if (-not $JdkHome -or -not $AndroidJar) { throw 'Specify -JdkHome and -AndroidJar for the focused photo-preset checks.' }
$classes = Join-Path $PSScriptRoot '.build\photo-presets\classes'
$scratch = Join-Path $PSScriptRoot '.build\photo-presets\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$javaRoot = Join-Path $projectRoot 'app\src\main\java\com\nikonpreset\usbprobe'
$sources = @()
foreach ($package in @('ptp', 'picturecontrol', 'ncp', 'library')) {
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot $package) -Filter '*.java' | ForEach-Object FullName)
}
foreach ($name in @('PhotoPresetBatch', 'PhotoPresetBatchState', 'PhotoPresetRenderer', 'PhotoPresetSources', 'PhotoLocalTasks', 'PhotoItem', 'PhotoFormat', 'DownloadedPhoto', 'PhotoPreview', 'PhotoPreviewCache')) {
    $sources += Join-Path $javaRoot ('album\' + $name + '.java')
}
# These facades explicitly isolate batch control/strip logic. The Android bitmap
# codecs/EXIF and real camera/MediaStore/service boundary still need device QA.
$sources += Join-Path $PSScriptRoot 'album-stubs\android\content\Context.java'
$sources += Join-Path $PSScriptRoot 'album-stubs\android\os\Handler.java'
$sources += Join-Path $PSScriptRoot 'album-stubs\android\os\Environment.java'
$sources += Join-Path $PSScriptRoot 'android-stubs\android\os\Looper.java'
$sources += Join-Path $PSScriptRoot 'gallery-stubs\android\util\AtomicFile.java'
$sources += Join-Path $PSScriptRoot 'java\com\nikonpreset\usbprobe\ncp\NcpCompatibilityFixtures.java'
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'batch-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'batch-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Photo-preset focused compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) com.nikonpreset.usbprobe.album.PhotoPresetBatchTest
if ($LASTEXITCODE -ne 0) { throw 'Photo-preset focused tests failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) com.nikonpreset.usbprobe.album.PhotoPresetSourcesTest
if ($LASTEXITCODE -ne 0) { throw 'Photo-preset source-link tests failed.' }
