$ErrorActionPreference='Stop'
$borderRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$borderBuild=Join-Path $borderRoot '.tooling/jpeg-import-90/probe'
$borderSdk='D:/dev/android-sdk';$borderJdk='D:/dev/Java/jdk-17.0.19';$borderTools=Join-Path $borderSdk 'build-tools/35.0.0';$borderJar=Join-Path $borderSdk 'platforms/android-35/android.jar'
foreach($dir in @('assets','classes','dex')){New-Item -ItemType Directory -Force -Path (Join-Path $borderBuild $dir)|Out-Null}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/phone-screenshot.jpg') -Destination (Join-Path $borderBuild 'assets/phone-screenshot.jpg')
function RunBorder([string]$Exe,[string[]]$Arguments){& $Exe @Arguments;if($LASTEXITCODE-ne0){throw 'Border native probe build failed.'}}
$borderUnsigned=Join-Path $borderBuild 'unsigned.apk'
RunBorder (Join-Path $borderTools 'aapt2.exe') @('link','-I',$borderJar,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),'--min-sdk-version','26','--target-sdk-version','35','-A',(Join-Path $borderBuild 'assets'),'-o',$borderUnsigned)
RunBorder (Join-Path $borderJdk 'bin/javac.exe') @('-J-Xmx256m','-encoding','UTF-8','-source','17','-target','17','-cp',$borderJar,'-d',(Join-Path $borderBuild 'classes'),(Join-Path $PSScriptRoot 'src/cc/huevia/jpegqa/JpegImportProbe.java'))
RunBorder (Join-Path $borderJdk 'bin/jar.exe') @('--create','--file',(Join-Path $borderBuild 'classes.jar'),'-C',(Join-Path $borderBuild 'classes'),'.')
$env:JAVA_HOME=$borderJdk;$env:JAVA_OPTS='-Xmx256m'
RunBorder (Join-Path $borderTools 'd8.bat') @('--debug','--min-api','26','--lib',$borderJar,'--output',(Join-Path $borderBuild 'dex'),(Join-Path $borderBuild 'classes.jar'))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open($borderUnsigned,[IO.Compression.ZipArchiveMode]::Update)
try{foreach($f in Get-ChildItem -LiteralPath (Join-Path $borderBuild 'dex') -Filter '*.dex'){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$f.FullName,$f.Name)|Out-Null}}finally{$archive.Dispose()}
RunBorder (Join-Path $borderTools 'zipalign.exe') @('-f','4',$borderUnsigned,(Join-Path $borderBuild 'aligned.apk'))
RunBorder (Join-Path $borderTools 'apksigner.bat') @('sign','--ks',(Join-Path $borderRoot '.tooling/debug.keystore'),'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','androiddebugkey','--out',(Join-Path $borderBuild 'jpeg-probe.apk'),(Join-Path $borderBuild 'aligned.apk'))
Write-Output (Join-Path $borderBuild 'jpeg-probe.apk')
