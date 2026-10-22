param([string]$JdkHome, [string]$AndroidJar)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) { $JdkHome = 'D:\dev\Java\jdk-17.0.19' }
if (-not $AndroidJar) { $AndroidJar = 'D:\dev\android-sdk\platforms\android-35\android.jar' }
$classes = Join-Path $PSScriptRoot '.build\project-auto\classes'
$scratch = Join-Path $PSScriptRoot '.build\project-auto\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sources = @((Join-Path $javaRoot 'account\AccountJson.java'))
foreach ($package in @('ptp', 'picturecontrol', 'ncp', 'library')) { $sources += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot $package) -Filter '*.java' | Where-Object { $_.Name -notin @('PersonalPresetApi.java') } | ForEach-Object FullName) }
foreach ($name in @('PhotoEditStore', 'PhotoProjectPresets', 'PhotoProjectPresetStore', 'PhotoPresetBatch', 'PhotoPresetBatchState', 'PhotoPresetRenderer', 'PhotoPresetSources', 'PhotoLocalTasks', 'PhotoItem', 'PhotoFormat', 'DownloadedPhoto', 'PhotoPreview', 'PhotoPreviewCache', 'PhotoLiveProject', 'PhotoLiveReceipt', 'PhotoLiveState')) { $sources += Join-Path $javaRoot ('album\' + $name + '.java') }
$sources += Join-Path $PSScriptRoot 'album-stubs\android\content\Context.java'
$sources += Join-Path $PSScriptRoot 'album-stubs\android\os\Environment.java'
$sources += Join-Path $PSScriptRoot 'android-stubs\android\os\Looper.java'
$sources += Join-Path $PSScriptRoot 'gallery-stubs\android\util\AtomicFile.java'
$sources += Join-Path $PSScriptRoot 'batch-stubs\cc\huevia\album\PhotoLiveGallery.java'
$sources += Join-Path $PSScriptRoot 'java\cc\huevia\ncp\NcpCompatibilityFixtures.java'
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'project-auto-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'project-auto-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Project automatic-preset compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) cc.huevia.album.PhotoProjectPresetsHostTest
if ($LASTEXITCODE -ne 0) { throw 'Project automatic-preset checks failed.' }
