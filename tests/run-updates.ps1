param([string]$JdkHome = 'D:/dev/Java/jdk-17.0.19')
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build/updates/classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -d $classes (Join-Path $root 'app/src/main/java/com/nikonpreset/usbprobe/updates/UpdatePolicy.java') (Join-Path $PSScriptRoot 'update-java/com/nikonpreset/usbprobe/updates/UpdatePolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Update policy compilation failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp $classes com.nikonpreset.usbprobe.updates.UpdatePolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Update policy regression failed.' }
