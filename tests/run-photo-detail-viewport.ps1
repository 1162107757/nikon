$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$testBuild = Join-Path ([System.IO.Path]::GetTempPath()) ('huevia-detail-viewport-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testBuild | Out-Null
$javac = 'D:/dev/Java/jdk-17.0.19/bin/javac.exe'
$java = 'D:/dev/Java/jdk-17.0.19/bin/java.exe'
& $javac -encoding UTF-8 -d $testBuild (Join-Path $repository 'app/src/main/java/cc/huevia/ui/PhotoDetailViewport.java') (Join-Path $PSScriptRoot 'photo-detail-java/cc/huevia/ui/PhotoDetailViewportTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Detail viewport compilation failed.' }
& $java -cp $testBuild cc.huevia.ui.PhotoDetailViewportTest
if ($LASTEXITCODE -ne 0) { throw 'Detail viewport checks failed.' }
