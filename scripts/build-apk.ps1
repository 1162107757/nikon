param(
    [string]$SdkRoot,
    [string]$JdkRoot
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$appBuildDefinition = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle') -Raw
$appVersionMatch = [regex]::Match($appBuildDefinition, "versionName\s+'([0-9A-Za-z.\-]+)'")
$appCodeMatch = [regex]::Match($appBuildDefinition, 'versionCode\s+(\d+)')
if (-not $appVersionMatch.Success -or -not $appCodeMatch.Success) { throw 'Cannot read app version from app/build.gradle.' }
$appVersionName = $appVersionMatch.Groups[1].Value
$appVersionCode = $appCodeMatch.Groups[1].Value
if (-not $SdkRoot) {
    if ($env:ANDROID_HOME) { $SdkRoot = $env:ANDROID_HOME }
    else { $SdkRoot = Join-Path $projectRoot '.tooling\sdk' }
}
if (-not $JdkRoot) {
    if ($env:JAVA_HOME) { $JdkRoot = $env:JAVA_HOME }
    else {
        $bundledJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tooling\jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($bundledJdk) { $JdkRoot = $bundledJdk.FullName }
    }
}
if (-not $JdkRoot -or -not (Test-Path -LiteralPath (Join-Path $JdkRoot 'bin\javac.exe'))) {
    throw 'Set JAVA_HOME or pass -JdkRoot to a JDK 17 installation.'
}
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$toolsRoot = Join-Path $SdkRoot 'build-tools\35.0.0'
if (-not (Test-Path -LiteralPath $androidJar) -or -not (Test-Path -LiteralPath $toolsRoot)) {
    throw 'Install Android platform 35 and build-tools 35.0.0, then pass -SdkRoot.'
}
$env:JAVA_HOME = $JdkRoot
$outputRoot = Join-Path $projectRoot 'app\build\manual'
$classesRoot = Join-Path $outputRoot 'classes'
$dexRoot = Join-Path $outputRoot 'dex'
$generatedRoot = Join-Path $outputRoot 'generated'
$distRoot = Join-Path $projectRoot 'dist'
foreach ($directory in @($outputRoot, $classesRoot, $dexRoot, $generatedRoot, $distRoot)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
# These three directories contain only generated compiler outputs.
foreach ($generatedDirectory in @($classesRoot, $dexRoot, $generatedRoot)) {
    $absoluteGenerated = [IO.Path]::GetFullPath($generatedDirectory)
    $absoluteOutput = [IO.Path]::GetFullPath($outputRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $absoluteGenerated.StartsWith($absoluteOutput)) { throw 'Generated directory escaped build root.' }
    foreach ($generatedItem in Get-ChildItem -LiteralPath $absoluteGenerated -Force) {
        Remove-Item -LiteralPath $generatedItem.FullName -Recurse -Force
    }
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable exited with code $LASTEXITCODE" }
}

# aapt2 receives the same application ID that Gradle normally adds to the manifest.
[xml]$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'app\src\main\AndroidManifest.xml') -Raw
$manifest.manifest.SetAttribute('package', 'com.nikonpreset.usbprobe')
$manifestPath = Join-Path $outputRoot 'AndroidManifest.xml'
$manifest.Save($manifestPath)
$resourcesZip = Join-Path $outputRoot 'resources.zip'
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('compile', '--dir', (Join-Path $projectRoot 'app\src\main\res'), '-o', $resourcesZip)
$unsignedApk = Join-Path $outputRoot 'unsigned.apk'
Invoke-Checked (Join-Path $toolsRoot 'aapt2.exe') @('link', '-I', $androidJar, '--manifest', $manifestPath,
    '-A', (Join-Path $projectRoot 'app\src\main\assets'),
    '--java', $generatedRoot, '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--version-code', $appVersionCode, '--version-name', $appVersionName, '-o', $unsignedApk, $resourcesZip)

$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java') -Filter '*.java' -Recurse)
$sources += @(Get-ChildItem -LiteralPath $generatedRoot -Filter '*.java' -Recurse)
$runtimeJars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\libs') -Filter '*.jar' -File -ErrorAction SilentlyContinue)
$compileOnlyJars = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\libs\compile-only') -Filter '*.jar' -File -ErrorAction SilentlyContinue)
$compileClassPath = (@($androidJar) + @($runtimeJars | ForEach-Object FullName) + @($compileOnlyJars | ForEach-Object FullName)) -join [IO.Path]::PathSeparator
$javacArgs = Join-Path $outputRoot 'javac.args'
$javacLines = @('-J-Duser.language=en', '-J-Duser.country=US', '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-classpath', ('"' + $compileClassPath.Replace('\', '/') + '"'),
    '-d', ('"' + $classesRoot.Replace('\', '/') + '"'))
$javacLines += $sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
[IO.File]::WriteAllLines($javacArgs, $javacLines, [Text.UTF8Encoding]::new($false))
Invoke-Checked (Join-Path $JdkRoot 'bin\javac.exe') @('@' + $javacArgs)

# Keep the Windows batch invocation below cmd.exe's command-line length limit.
# A single class archive also prevents a growing UI from silently omitting class files.
$classArchive = Join-Path $outputRoot 'classes.jar'
Invoke-Checked (Join-Path $JdkRoot 'bin\jar.exe') @('--create', '--file', $classArchive, '-C', $classesRoot, '.')
$d8Args = @('--debug', '--min-api', '26', '--lib', $androidJar, '--output', $dexRoot, $classArchive)
foreach ($compileOnlyJar in $compileOnlyJars) { $d8Args += @('--classpath', $compileOnlyJar.FullName) }
$d8Args += @($runtimeJars | ForEach-Object FullName)
Invoke-Checked (Join-Path $toolsRoot 'd8.bat') $d8Args
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$apkArchive = [IO.Compression.ZipFile]::Open($unsignedApk, [IO.Compression.ZipArchiveMode]::Update)
try {
    # Windows aapt2 can leave backslashes in nested asset names. Android assets use '/'.
    foreach ($resource in @($apkArchive.Entries)) {
        if (-not $resource.FullName.StartsWith('assets/') -or -not $resource.FullName.Contains('\')) { continue }
        $assetName = $resource.FullName.Replace('\', '/')
        if ($apkArchive.GetEntry($assetName)) { throw ('Duplicate application asset: ' + $assetName) }
        $apkEntry = $apkArchive.CreateEntry($assetName, [IO.Compression.CompressionLevel]::Optimal)
        $sourceStream = $resource.Open()
        try {
            $targetStream = $apkEntry.Open()
            try { $sourceStream.CopyTo($targetStream) } finally { $targetStream.Dispose() }
        } finally { $sourceStream.Dispose() }
        $resource.Delete()
    }
    foreach ($dexFile in Get-ChildItem -LiteralPath $dexRoot -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkArchive, $dexFile.FullName, $dexFile.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    # QQ SDK's H5 assets/native helpers live inside its jar and must accompany its dex classes.
    foreach ($runtimeJar in $runtimeJars) {
        $runtimeArchive = [IO.Compression.ZipFile]::OpenRead($runtimeJar.FullName)
        try {
            foreach ($resource in $runtimeArchive.Entries) {
                if (-not $resource.FullName.StartsWith('assets/') -or $resource.FullName.EndsWith('/')) { continue }
                if ($apkArchive.GetEntry($resource.FullName)) { throw ('Duplicate SDK asset: ' + $resource.FullName) }
                $apkEntry = $apkArchive.CreateEntry($resource.FullName, [IO.Compression.CompressionLevel]::Optimal)
                $sourceStream = $resource.Open()
                try {
                    $targetStream = $apkEntry.Open()
                    try { $sourceStream.CopyTo($targetStream) } finally { $targetStream.Dispose() }
                } finally { $sourceStream.Dispose() }
            }
        } finally { $runtimeArchive.Dispose() }
    }
} finally { $apkArchive.Dispose() }
$alignedApk = Join-Path $outputRoot 'aligned.apk'
Invoke-Checked (Join-Path $toolsRoot 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)
$debugKey = Join-Path $projectRoot '.tooling\debug.keystore'
New-Item -ItemType Directory -Path (Split-Path -Parent $debugKey) -Force | Out-Null
if (-not (Test-Path -LiteralPath $debugKey)) {
    Invoke-Checked (Join-Path $JdkRoot 'bin\keytool.exe') @('-genkeypair', '-keystore', $debugKey,
        '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=Nikon USB Debug,OU=Development,O=Local,C=CN')
}
$finalApk = Join-Path $distRoot ('nikon-usb-' + $appVersionName + '-debug.apk')
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('sign', '--ks', $debugKey, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey', '--out', $finalApk, $alignedApk)
Invoke-Checked (Join-Path $toolsRoot 'apksigner.bat') @('verify', '--verbose', $finalApk)
$appApkHash = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($finalApk + '.sha256', $appApkHash + '  ' + [IO.Path]::GetFileName($finalApk) + [Environment]::NewLine,
    [Text.UTF8Encoding]::new($false))
Write-Output "APK: $finalApk"
Write-Output ('SHA-256: ' + $appApkHash)
