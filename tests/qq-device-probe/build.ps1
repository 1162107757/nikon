param(
    [string]$SdkRoot = 'D:\dev\android-sdk',
    [string]$JdkRoot = 'D:\dev\Java\jdk-17.0.19'
)
$ErrorActionPreference = 'Stop'
$probeRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
$debugKey = Join-Path $projectRoot '.tooling\debug.keystore'
foreach ($requiredPath in @($androidJar, (Join-Path $JdkRoot 'bin\javac.exe'), (Join-Path $JdkRoot 'bin\jar.exe'),
        (Join-Path $buildTools 'aapt2.exe'), (Join-Path $buildTools 'lib\d8.jar'),
        (Join-Path $buildTools 'zipalign.exe'), (Join-Path $buildTools 'lib\apksigner.jar'), $debugKey)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw 'Android 35 toolchain and the existing development signing key are required.' }
}
# Build only. This script never connects to a device, installs an APK, or modifies the product.
$outputRoot = Join-Path $projectRoot 'tests\.build\qq-device-probe'
$runRoot = Join-Path $outputRoot ([Guid]::NewGuid().ToString('N'))
$classesRoot = Join-Path $runRoot 'classes'
$dexRoot = Join-Path $runRoot 'dex'
foreach ($directory in @($outputRoot, $runRoot, $classesRoot, $dexRoot)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
function Invoke-ProbeTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'QQ instrumentation build failed.' }
}
$unsignedApk = Join-Path $runRoot 'unsigned.apk'
Invoke-ProbeTool (Join-Path $buildTools 'aapt2.exe') @('link', '-I', $androidJar, '--manifest',
    (Join-Path $probeRoot 'AndroidManifest.xml'), '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--version-code', '1', '--version-name', '1.0', '-o', $unsignedApk)
$javacArgs = Join-Path $runRoot 'javac.args'
$javacLines = @('-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-classpath', ('"' + $androidJar.Replace('\', '/') + '"'), '-d', ('"' + $classesRoot.Replace('\', '/') + '"'))
$javacLines += @(Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines($javacArgs, $javacLines, [Text.UTF8Encoding]::new($false))
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\javac.exe') @('@' + $javacArgs)
$classArchive = Join-Path $runRoot 'classes.jar'
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\jar.exe') @('--create', '--file', $classArchive, '-C', $classesRoot, '.')
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\java.exe') @('-cp', (Join-Path $buildTools 'lib\d8.jar'), 'com.android.tools.r8.D8', '--debug', '--min-api', '26', '--lib', $androidJar, '--output', $dexRoot, $classArchive)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($dexFile in Get-ChildItem -LiteralPath $dexRoot -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dexFile.FullName, $dexFile.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
$alignedApk = Join-Path $runRoot 'aligned.apk'
Invoke-ProbeTool (Join-Path $buildTools 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)
$finalApk = Join-Path $outputRoot 'qq-device-probe.apk'
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'sign', '--ks', $debugKey, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--out', $finalApk, $alignedApk)
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\java.exe') @('-jar', (Join-Path $buildTools 'lib\apksigner.jar'), 'verify', '--verbose', $finalApk)
$apkHash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($finalApk + '.sha256', $apkHash + '  qq-device-probe.apk' + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output ('Instrumentation APK: ' + $finalApk)
Write-Output ('Runner: cc.huevia.qqqa/.QqAccountProbe')
Write-Output ('SHA-256: ' + $apkHash)
