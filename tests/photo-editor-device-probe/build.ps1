param([string]$SdkRoot = 'D:\dev\android-sdk', [string]$JdkRoot = 'D:\dev\Java\jdk-17.0.19', [int]$ExpectedVersion = 74, [ValidateSet('full','viewer-boundary','selection-polish','album-refresh','basic-adjustments','composition-recipes','filter-navigation','local-album')][string]$Scope = 'full')
$ErrorActionPreference = 'Stop'
$probeRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$probeSource = Join-Path $probeRoot 'src\cc\huevia\editorqa\EditorNativeProbe.java'
$buildInputs = [ordered]@{
    probeJavaSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $probeSource).Hash.ToLowerInvariant()
    buildScriptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
    runScriptSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $probeRoot 'run.ps1')).Hash.ToLowerInvariant()
}
$buildFamily = if ($Scope -eq 'local-album') { 'photo-local-album-' } elseif ($Scope -eq 'filter-navigation') { 'photo-filter-navigation-' } elseif ($Scope -eq 'composition-recipes') { 'photo-composition-recipes-' } elseif ($Scope -eq 'basic-adjustments') { 'photo-basic-adjustments-' } elseif ($Scope -eq 'album-refresh') { 'photo-album-refresh-' } else { 'photo-editor-categories-' }
$probeOutputRoot = Join-Path $projectRoot ('.tooling\' + $buildFamily + $ExpectedVersion)
$outputRoot = Join-Path $probeOutputRoot ('build\' + [Guid]::NewGuid().ToString('N'))
$assets = Join-Path $outputRoot 'assets'
$classes = Join-Path $outputRoot 'classes'
$dex = Join-Path $outputRoot 'dex'
foreach ($directory in @($assets, $classes, $dex)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $projectRoot 'dist\nikon-cloud-recipes-20261018\nikon-cloud-recipes-142.zip'))
try {
    foreach ($pair in @(@('Color Grading/BleachBypass_01a.NP3','supported.np3'), @('Nikon Creators/Bluegrain_jyota.NP3','unsupported.np3'))) {
        $entry = $zip.GetEntry($pair[0]); if ($null -eq $entry) { throw 'Expected research corpus fixture is missing.' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $assets $pair[1]))
    }
} finally { $zip.Dispose() }
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\src\main\res\drawable-nodpi\preset_template.png') -Destination (Join-Path $assets 'photo.png')
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
function Run([string]$Exe, [string[]]$Arguments) { & $Exe @Arguments; if ($LASTEXITCODE -ne 0) { throw 'Photo editor instrumentation build failed.' } }
$unsigned = Join-Path $outputRoot 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $probeRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','-A',$assets,'--version-code','1','--version-name','1.0','-o',$unsigned)
$argsFile = Join-Path $outputRoot 'javac.args'
$lines = @('-J-Xmx256m','-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','-source','17','-target','17','-classpath',('"'+$androidJar.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'))
$lines += '"' + (Join-Path $projectRoot 'tests\java\cc\huevia\ncp\NcpCompatibilityFixtures.java').Replace('\','/') + '"'
$lines += Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
[IO.File]::WriteAllLines($argsFile,$lines,[Text.UTF8Encoding]::new($false))
Run (Join-Path $JdkRoot 'bin\javac.exe') @('@'+$argsFile)
$jar = Join-Path $outputRoot 'classes.jar'
Run (Join-Path $JdkRoot 'bin\jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
$env:JAVA_HOME = $JdkRoot
$env:JAVA_OPTS = '-Xmx256m'
Run (Join-Path $buildTools 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--output',$dex,$jar)
$archive = [IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try { foreach ($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex') { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null } } finally { $archive.Dispose() }
$aligned = Join-Path $outputRoot 'aligned.apk'
Run (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
$final = Join-Path $probeOutputRoot 'editor-probe.apk'
Run (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',(Join-Path $projectRoot '.tooling\debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$final,$aligned)
Run (Join-Path $buildTools 'apksigner.bat') @('verify',$final)
$observedSourceSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $probeSource).Hash.ToLowerInvariant()
if ($observedSourceSha -ne $buildInputs.probeJavaSha256) { throw 'Probe source changed during compilation; rebuild to produce valid provenance.' }
$provenance = [ordered]@{
    schemaVersion = 1
    builtAtUtc = [DateTime]::UtcNow.ToString('O')
    expectedVersion = $ExpectedVersion
    buildScope = $Scope
    probeSource = 'tests/photo-editor-device-probe/src/cc/huevia/editorqa/EditorNativeProbe.java'
    probeJavaSha256 = $buildInputs.probeJavaSha256
    buildScriptSha256 = $buildInputs.buildScriptSha256
    runScriptSha256 = $buildInputs.runScriptSha256
    probeApkSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $final).Hash.ToLowerInvariant()
}
$provenancePath = Join-Path $probeOutputRoot 'provenance.json'
[IO.File]::WriteAllText($provenancePath,($provenance | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output ('Photo editor instrumentation: '+$final)
Write-Output ('Probe provenance: '+$provenancePath)
