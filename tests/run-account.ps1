param([string]$JdkHome, [string]$AndroidJar)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) { $JdkHome = Join-Path $projectRoot '.tooling\jdk\jdk-17.0.20.1+1' }
if (-not $AndroidJar) { $AndroidJar = Join-Path $projectRoot '.tooling\sdk\platforms\android-35\android.jar' }
$classes = Join-Path $PSScriptRoot '.build\account\classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = @($AndroidJar)
$libraries = Join-Path $projectRoot 'app\libs'
if (Test-Path -LiteralPath $libraries) {
    $classpath += @(Get-ChildItem -LiteralPath $libraries -Filter '*.jar' | ForEach-Object FullName)
}
$compileOnly = Join-Path $libraries 'compile-only'
$compileDependencies = @($classpath)
if (Test-Path -LiteralPath $compileOnly) {
    $compileDependencies += @(Get-ChildItem -LiteralPath $compileOnly -Filter '*.jar' | ForEach-Object FullName)
}
$compileClasspath = $compileDependencies -join [IO.Path]::PathSeparator
$runtimeClasspath = $classpath -join [IO.Path]::PathSeparator
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java\cc\huevia\account') -Filter '*.java' | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'account-java\cc\huevia\account') -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $compileClasspath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Account targeted compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($classes + [IO.Path]::PathSeparator + $runtimeClasspath) cc.huevia.account.AccountRepositoryTest
if ($LASTEXITCODE -ne 0) { throw 'Account targeted regression failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp ($classes + [IO.Path]::PathSeparator + $runtimeClasspath) cc.huevia.account.AccountAvatarTest
if ($LASTEXITCODE -ne 0) { throw 'Account avatar regression failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -cp $classes cc.huevia.account.QqAuthorizationGateTest
if ($LASTEXITCODE -ne 0) { throw 'QQ authorization gate regression failed.' }
& (Join-Path $JdkHome 'bin/java.exe') -cp ($classes + [IO.Path]::PathSeparator + $runtimeClasspath) cc.huevia/account/AccountCommunityAuthTest
if ($LASTEXITCODE -ne 0) { throw 'Account challenge contract regression failed.' }
