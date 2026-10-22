param([string]$JdkHome = 'D:/dev/Java/jdk-17.0.19', [string]$AndroidJar = 'D:/dev/android-sdk/platforms/android-35/android.jar')
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $PSScriptRoot '.build/community-host/classes'
$scratch = Join-Path $PSScriptRoot '.build/community-host/files'
New-Item -ItemType Directory -Force -Path $classes, $scratch | Out-Null
$source = Join-Path $projectRoot 'app/src/main/java'
$dependencies = @($AndroidJar) + @(Get-ChildItem (Join-Path $projectRoot 'app/libs') -Filter '*.jar' | ForEach-Object FullName) + @(Get-ChildItem (Join-Path $projectRoot 'app/libs/compile-only') -Filter '*.jar' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -cp ($dependencies -join ';') -sourcepath $source -d $classes (Join-Path $PSScriptRoot 'community-java/cc/huevia/community/CommunityRepositoryTest.java') (Join-Path $PSScriptRoot 'community-java/cc/huevia/library/CommunityPagingTest.java') (Join-Path $PSScriptRoot 'community-java/cc/huevia/community/CommentReplyPagesTest.java') (Join-Path $PSScriptRoot 'community-java/cc/huevia/community/CommentReplyTargetTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Community host compilation failed.' }
& (Join-Path $JdkHome 'bin/java.exe') "-Djava.io.tmpdir=$scratch" -cp ($classes + ';' + $AndroidJar) cc.huevia.community.CommunityRepositoryTest
if ($LASTEXITCODE -ne 0) { throw 'Community host regression failed.' }
& (Join-Path $JdkHome 'bin/java.exe') "-Djava.io.tmpdir=$scratch" -cp ($classes + ';' + $AndroidJar) cc.huevia.library.CommunityPagingTest
if ($LASTEXITCODE -ne 0) { throw 'Community paging regression failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp ($classes + ';' + $AndroidJar) cc.huevia.community.CommentReplyPagesTest
if ($LASTEXITCODE -ne 0) { throw 'Comment reply paging regression failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp ($classes + ';' + $AndroidJar) cc.huevia.community.CommentReplyTargetTest
if ($LASTEXITCODE -ne 0) { throw 'Comment reply recipient regression failed.' }
