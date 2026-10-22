param([string]$JdkHome, [string]$AndroidJar, [switch]$Reproduce014, [switch]$OnlyCopy)

$ErrorActionPreference = 'Stop'
if ($OnlyCopy -and $Reproduce014) { throw '-OnlyCopy cannot be combined with -Reproduce014.' }
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$probeBuildRoot = Join-Path $PSScriptRoot '.build\probe'
New-Item -ItemType Directory -Force -Path $probeBuildRoot | Out-Null
if ($JdkHome) {
    $compilerPath = Join-Path $JdkHome 'bin\javac.exe'
    $runtimePath = Join-Path $JdkHome 'bin\java.exe'
} else {
    $compilerPath = (Get-Command javac -ErrorAction Stop).Source
    $runtimePath = (Get-Command java -ErrorAction Stop).Source
}
if (-not $AndroidJar) {
    $AndroidJar = Join-Path $projectRoot '.tooling\sdk\platforms\android-35\android.jar'
}
if (-not (Test-Path -LiteralPath $AndroidJar)) { throw 'Pass -AndroidJar with an existing Android platform jar.' }
$AndroidJar = [System.IO.Path]::GetFullPath($AndroidJar)
$javaRoot = Join-Path $projectRoot 'app\src\main\java\cc\huevia'
$probeSourcePath = Join-Path $javaRoot 'usb\UsbProbe.java'
$commonSourcePaths = @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ptp') -Filter '*.java' | ForEach-Object FullName)
$commonSourcePaths += Join-Path $javaRoot 'usb\StorageAssessment.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\PtpDirectorySurvey.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\NcpTransferSession.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\PtpUsbTransport.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\PictureControlReadSession.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\UsbPictureControlProbe.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\PictureControlImportSession.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\UsbPictureControlImport.java'
$commonSourcePaths += Join-Path $javaRoot 'usb\WirelessPictureControl.java'
$commonSourcePaths += Join-Path $javaRoot 'album\PhotoCameraClient.java'
$commonSourcePaths += Join-Path $javaRoot 'album\PtpIpClient.java'
$commonSourcePaths += Join-Path $javaRoot 'album\ConnectionTrace.java'
$commonSourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'picturecontrol') -Filter '*.java' | ForEach-Object FullName)
$commonSourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $javaRoot 'ncp') -Filter '*.java' | ForEach-Object FullName)
$commonSourcePaths += Join-Path $PSScriptRoot 'java\cc\huevia\ncp\NcpCompatibilityFixtures.java'
$commonSourcePaths += Join-Path $PSScriptRoot 'stubs\cc\huevia\usb\UsbNcpTransfer.java'
$commonSourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'android-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
$commonSourcePaths += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'probe-java') -Recurse -Filter '*.java' | ForEach-Object FullName)

function Invoke-ProbeTest([string]$SourcePath, [string]$OutputPath, [string]$TestMode) {
    New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
    & $compilerPath --release 17 -encoding UTF-8 -cp $AndroidJar -d $OutputPath @commonSourcePaths $SourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Production UsbProbe host compilation failed.' }
    $testClassPath = $OutputPath + [System.IO.Path]::PathSeparator + $AndroidJar
    if (-not $OnlyCopy) {
        & $runtimePath -cp $testClassPath cc.huevia.usb.UsbProbeHostTest $TestMode
        if ($LASTEXITCODE -ne 0) { throw 'Production UsbProbe host regression failed.' }
    }
    if ($TestMode -eq 'fixed') {
        if (-not $OnlyCopy) {
            & $runtimePath -cp $testClassPath cc.huevia.usb.UsbPictureControlProbeHostTest
            if ($LASTEXITCODE -ne 0) { throw 'Production Picture Control read-only host tests failed.' }
        }
        & $runtimePath -cp $testClassPath cc.huevia.usb.UsbPictureControlImportHostTest
        if ($LASTEXITCODE -ne 0) { throw 'Production Picture Control empty-slot copy host tests failed.' }
        & $runtimePath -cp $testClassPath cc.huevia.usb.UsbWirelessPictureControlHostTest
        if ($LASTEXITCODE -ne 0) { throw 'Production wireless Picture Control host tests failed.' }
    }
}

if ($Reproduce014) {
    # Restore only the old nullable expression in a generated source copy.
    # The checked-in production source is never modified by this reproduction.
    $probeSourceText = [System.IO.File]::ReadAllText($probeSourcePath)
    $contextStart = $probeSourceText.IndexOf('            Long storageContext = null;')
    $contextEnd = $probeSourceText.IndexOf('            result.lastStorageId = storageContext', $contextStart)
    if ($contextStart -lt 0 -or $contextEnd -lt 0) { throw 'Cannot identify the repaired nullable-context block.' }
    $oldExpression = @'
            Long storageContext = (operation == GET_STORAGE_INFO || operation == GET_OBJECT_HANDLES) && params.length > 0
                    ? params[0] : operation == GET_OBJECT_INFO ? directorySurveyStorageId : null;

'@
    $reproText = $probeSourceText.Substring(0, $contextStart) + $oldExpression + $probeSourceText.Substring($contextEnd)
    $reproRoot = Join-Path $probeBuildRoot 'repro'
    New-Item -ItemType Directory -Force -Path $reproRoot | Out-Null
    $reproSourcePath = Join-Path $reproRoot 'UsbProbe.java'
    [System.IO.File]::WriteAllText($reproSourcePath, $reproText, [System.Text.UTF8Encoding]::new($false))
    Invoke-ProbeTest $reproSourcePath (Join-Path $reproRoot 'classes') 'reproduce-014'
}
Invoke-ProbeTest $probeSourcePath (Join-Path $probeBuildRoot 'classes') 'fixed'
