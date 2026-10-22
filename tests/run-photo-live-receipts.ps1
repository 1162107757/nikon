param([string]$JdkHome)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    foreach ($candidate in @((Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1'), 'D:\dev\Java\jdk-17.0.19')) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) { $JdkHome = $candidate; break }
    }
}
if (-not $JdkHome) { throw 'Specify -JdkHome for photo-live receipt checks.' }
$classes = Join-Path $PSScriptRoot '.build\photo-live-receipts\classes'
$scratch = Join-Path $PSScriptRoot '.build\photo-live-receipts\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('DownloadedPhoto', 'PhotoItem', 'PhotoFormat', 'PhotoPreview', 'PhotoPreviewCache', 'PhotoLiveLedger', 'PhotoLiveReceipt', 'PhotoLiveReceiptStore', 'PhotoOriginalStore')) {
    $sources += Join-Path $javaRoot ('album\' + $name + '.java')
}
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'receipt-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Photo-live receipt focused compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp $classes cc.huevia.album.PhotoLiveReceiptStoreTest
if ($LASTEXITCODE -ne 0) { throw 'Photo-live receipt persistence checks failed.' }
