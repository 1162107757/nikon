param(
    [string]$SdkRoot = 'D:\dev\android-sdk',
    [string]$JdkRoot = 'D:\dev\Java\jdk-17.0.19'
)
$ErrorActionPreference = 'Stop'
$probeRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$toolsRoot = Join-Path $SdkRoot 'build-tools\35.0.0'
$debugKey = Join-Path $projectRoot '.tooling\debug.keystore'
foreach ($requiredPath in @($androidJar, (Join-Path $toolsRoot 'aapt2.exe'), (Join-Path $JdkRoot 'bin\javac.exe'), $debugKey)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) { throw 'An existing Android 35 toolchain and the existing development signing key are required.' }
}
# Every invocation gets a dedicated output directory. Product build outputs and signing files are read only.
$outputRoot = Join-Path $probeRoot ('build\' + [Guid]::NewGuid().ToString('N'))
$classesRoot = Join-Path $outputRoot 'classes'
$dexRoot = Join-Path $outputRoot 'dex'
foreach ($directory in @($outputRoot, $classesRoot, $dexRoot)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }

function Invoke-ProbeTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'The isolated instrumentation build failed.' }
}

$unsignedApk = Join-Path $outputRoot 'unsigned.apk'
Invoke-ProbeTool (Join-Path $toolsRoot 'aapt2.exe') @('link', '-I', $androidJar, '--manifest',
    (Join-Path $probeRoot 'AndroidManifest.xml'), '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--version-code', '1', '--version-name', '1.0', '-o', $unsignedApk)
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Recurse -Filter '*.java')
$javacArgs = Join-Path $outputRoot 'javac.args'
$javacLines = @('-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-classpath', ('"' + $androidJar.Replace('\', '/') + '"'), '-d', ('"' + $classesRoot.Replace('\', '/') + '"'))
$javacLines += $sourceFiles | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($javacArgs, $javacLines, [Text.UTF8Encoding]::new($false))
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\javac.exe') @('@' + $javacArgs)
$classArchive = Join-Path $outputRoot 'classes.jar'
Invoke-ProbeTool (Join-Path $JdkRoot 'bin\jar.exe') @('--create', '--file', $classArchive, '-C', $classesRoot, '.')
$env:JAVA_HOME = $JdkRoot
Invoke-ProbeTool (Join-Path $toolsRoot 'd8.bat') @('--debug', '--min-api', '26', '--lib', $androidJar, '--output', $dexRoot, $classArchive)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($dexFile in Get-ChildItem -LiteralPath $dexRoot -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dexFile.FullName, $dexFile.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose() }
$alignedApk = Join-Path $outputRoot 'aligned.apk'
Invoke-ProbeTool (Join-Path $toolsRoot 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)
$finalApk = Join-Path $probeRoot 'comment-reply-probe.apk'
Invoke-ProbeTool (Join-Path $toolsRoot 'apksigner.bat') @('sign', '--ks', $debugKey, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--out', $finalApk, $alignedApk)
Invoke-ProbeTool (Join-Path $toolsRoot 'apksigner.bat') @('verify', '--verbose', $finalApk)
$apkHash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($finalApk + '.sha256', $apkHash + '  comment-reply-probe.apk' + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
Write-Output ('Instrumentation APK: ' + $finalApk)
Write-Output ('SHA-256: ' + $apkHash)
