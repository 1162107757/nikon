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
if (-not $JdkHome -or -not $AndroidJar) { throw 'Specify -JdkHome and -AndroidJar for photo-date checks.' }
$classes = Join-Path $PSScriptRoot '.build\photo-dates\classes'
$scratch = Join-Path $PSScriptRoot '.build\photo-dates\files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$album = Join-Path $projectRoot 'app\src\main\java\cc\huevia\album'
$sources = @('DownloadedPhoto', 'PhotoDateGroups', 'PhotoDateReader') | ForEach-Object { Join-Path $album ($_.ToString() + '.java') }
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'date-java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $AndroidJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Photo-date focused compilation failed.' }
foreach ($test in @('PhotoDateGroupsTest', 'PhotoDateReaderTest')) {
    & (Join-Path $JdkHome 'bin\java.exe') ('-Djava.io.tmpdir=' + $scratch) -cp ($classes + [IO.Path]::PathSeparator + $AndroidJar) ('cc.huevia.album.' + $test)
    if ($LASTEXITCODE -ne 0) { throw ($test + ' failed.') }
}
