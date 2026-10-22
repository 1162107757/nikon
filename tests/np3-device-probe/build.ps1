param([string]$SdkRoot = 'D:\dev\android-sdk', [string]$JdkRoot = 'D:\dev\Java\jdk-17.0.19', [int]$ExpectedVersion = 79)
$ErrorActionPreference = 'Stop'
$probeRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$projectRoot = [IO.Path]::GetFullPath((Join-Path $probeRoot '..\..'))
$outputRoot = Join-Path $projectRoot ('.tooling\np3-native\build\' + [Guid]::NewGuid().ToString('N'))
$assets = Join-Path $outputRoot 'assets'
$classes = Join-Path $outputRoot 'classes'
$dex = Join-Path $outputRoot 'dex'
foreach ($directory in @($assets, $classes, $dex)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$corpus142 = Join-Path $projectRoot 'dist\nikon-cloud-recipes-20261018\nikon-cloud-recipes-142.zip'
if ((Get-FileHash -LiteralPath $corpus142 -Algorithm SHA256).Hash.ToLowerInvariant() -ne '7a1cf3cd719ed9ec69dae4936625afb1fc3cc1871bc6d61f46c27903a32a087c') { throw 'The fixed 142-recipe research archive changed.' }
$zip = [IO.Compression.ZipFile]::OpenRead($corpus142)
try {
    foreach ($pair in @(@('Color Grading/BleachBypass_01a.NP3','supported.np3'), @('Nikon Creators/Bluegrain_jyota.NP3','unsupported.np3'), @('Nikon Creators/24Look_24Frames.NP3','quick-minus.np3'), @('Nikon Creators/BlueHour_Luna.NP3','quick-plus.np3'))) {
        $entry = $zip.GetEntry($pair[0]); if ($null -eq $entry) { throw 'Expected research corpus fixture is missing.' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $assets $pair[1]))
    }
} finally { $zip.Dispose() }
$fullCorpus = Join-Path $projectRoot 'dist\nikon-cloud-recipes-20261018\nikon-recipes-main.zip'
if ((Get-FileHash -LiteralPath $fullCorpus -Algorithm SHA256).Hash.ToLowerInvariant() -ne '2f0ee976bf8026be25120c89863dbfd727f667a429a5a213c497be3d537b8367') { throw 'The fixed full research archive changed.' }
$monoFixtures = @(
    @{ source='Nikon-Recipes-main/Third Party Creators/Mark G Adams/Nikon Premium Recipes/Premium Dreamy Mono.NP3'; asset='dreamy-mono.np3'; bytes=978; sha256='886cc2b108050b2d7c27052cd17f005d6f98800bb577493971a0f221c247313d' },
    @{ source='Nikon-Recipes-main/Third Party Creators/Mark G Adams/Nikon Premium Recipes/Premium Dreamy Peep.NP3'; asset='dreamy-peep.np3'; bytes=978; sha256='d3774968a0c3919ba7e4ddabd876bb84d9a77c3d733d7cfad53fcdda937e26af' },
    @{ source="Nikon-Recipes-main/Third Party Creators/Scott Tucker/Scott's Ilford 400 LO.NP3"; asset='scott-ilford.np3'; bytes=1036; sha256='ff57c07d5c7df1b6f6ffe031ada0b987f6242043c28a2837dc40e3ebdd14561f' }
)
$zip = [IO.Compression.ZipFile]::OpenRead($fullCorpus)
try {
    foreach ($fixture in $monoFixtures) {
        $entry=$zip.GetEntry($fixture.source); if ($null -eq $entry) { throw 'An original monochrome fixture is missing.' }
        $target=Join-Path $assets $fixture.asset
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target)
        if ((Get-Item -LiteralPath $target).Length -ne $fixture.bytes -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixture.sha256) { throw 'An original monochrome fixture changed.' }
    }
} finally { $zip.Dispose() }
@{ expectedVersion=$ExpectedVersion; fullArchiveSha256='2f0ee976bf8026be25120c89863dbfd727f667a429a5a213c497be3d537b8367'; monochrome=$monoFixtures; scope='Local probe assets only; original public recipes, no private photograph or NX reference image.' } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $assets 'np3-fixtures.json') -Encoding utf8
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$buildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
function Run([string]$Exe, [string[]]$Arguments) { & $Exe @Arguments; if ($LASTEXITCODE -ne 0) { throw 'NP3 instrumentation build failed.' } }
$unsigned = Join-Path $outputRoot 'unsigned.apk'
Run (Join-Path $buildTools 'aapt2.exe') @('link','-I',$androidJar,'--manifest',(Join-Path $probeRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','-A',$assets,'--version-code','1','--version-name','1.0','-o',$unsigned)
$argsFile = Join-Path $outputRoot 'javac.args'
$lines = @('-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','-source','17','-target','17','-classpath',('"'+$androidJar.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'))
$lines += Get-ChildItem -LiteralPath (Join-Path $probeRoot 'src') -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
[IO.File]::WriteAllLines($argsFile,$lines,[Text.UTF8Encoding]::new($false))
Run (Join-Path $JdkRoot 'bin\javac.exe') @('@'+$argsFile)
$jar = Join-Path $outputRoot 'classes.jar'
Run (Join-Path $JdkRoot 'bin\jar.exe') @('--create','--file',$jar,'-C',$classes,'.')
$env:JAVA_HOME = $JdkRoot
Run (Join-Path $buildTools 'd8.bat') @('--debug','--min-api','26','--lib',$androidJar,'--output',$dex,$jar)
$archive = [IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try { foreach ($file in Get-ChildItem -LiteralPath $dex -Filter '*.dex') { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null } } finally { $archive.Dispose() }
$aligned = Join-Path $outputRoot 'aligned.apk'
Run (Join-Path $buildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
$finalDirectory = Join-Path $projectRoot ('.tooling\np3-native\'+$ExpectedVersion+'-monochrome')
New-Item -ItemType Directory -Force -Path $finalDirectory | Out-Null
$final = Join-Path $finalDirectory 'np3-probe.apk'
Run (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',(Join-Path $projectRoot '.tooling\debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',$final,$aligned)
Run (Join-Path $buildTools 'apksigner.bat') @('verify',$final)
$sourceAudit=@(Get-ChildItem -LiteralPath $probeRoot -File -Recurse | Sort-Object FullName | ForEach-Object { @{path=$_.FullName.Substring($projectRoot.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
$assetAudit=@(Get-ChildItem -LiteralPath $assets -File | Sort-Object Name | ForEach-Object { @{asset=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} })
@{builtAt=[DateTime]::UtcNow.ToString('o');expectedVersion=$ExpectedVersion;probeSha256=(Get-FileHash -LiteralPath $final -Algorithm SHA256).Hash.ToLowerInvariant();probeBytes=(Get-Item -LiteralPath $final).Length;sources=$sourceAudit;assets=$assetAudit;buildDirectory=$outputRoot;privatePhotosIncluded=$false} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $finalDirectory 'build-audit.json') -Encoding utf8
Write-Output ('NP3 instrumentation: '+$final)
