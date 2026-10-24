param([string[]]$Modes=@('phone','large-font','dark','landscape','tablet'))
$ErrorActionPreference='Stop'
$borderRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'));$borderAdb='D:/dev/Android/Sdk/platform-tools/adb.exe';$borderSerial='emulator-5582'
$borderEvidence=Join-Path $borderRoot 'docs/validation/photo-borders-89';$borderApk=Join-Path $borderRoot 'dist/huevia-0.18.0-beta-debug.apk'
function BorderAdb([string[]]$Arguments){$observed=& $borderAdb -s $borderSerial @Arguments;if($LASTEXITCODE-ne0){throw ('Border adb failed: '+($Arguments-join ' '))};return $observed}
$borderPrior=[ordered]@{fontScale=((BorderAdb @('shell','settings','get','system','font_scale'))-join '').Trim();night=((BorderAdb @('shell','cmd','uimode','night'))-join '').Trim();size=((BorderAdb @('shell','wm','size'))-join "`n");density=((BorderAdb @('shell','wm','density'))-join "`n")}
$borderReports=@();$borderHash=(Get-FileHash -LiteralPath $borderApk -Algorithm SHA256).Hash.ToLowerInvariant()
try{
 foreach($borderMode in $Modes){
  if($borderMode-eq'tablet'){BorderAdb @('shell','wm','size','1600x2560')|Out-Null;BorderAdb @('shell','wm','density','240')|Out-Null}else{BorderAdb @('shell','wm','size','reset')|Out-Null;BorderAdb @('shell','wm','density','reset')|Out-Null}
  $borderScale=if($borderMode-eq'large-font'){'1.3'}else{'1.0'};$borderNight=if($borderMode-eq'dark'){'yes'}else{'no'}
  BorderAdb @('shell','settings','put','system','font_scale',$borderScale)|Out-Null;BorderAdb @('shell','cmd','uimode','night',$borderNight)|Out-Null
  $borderModePath=Join-Path $borderEvidence $borderMode;New-Item -ItemType Directory -Force -Path $borderModePath|Out-Null
  BorderAdb @('shell','am','instrument','-w','-e','version','89','-e','mode',$borderMode,'cc.huevia.borderqa/.BorderNativeProbe') | Set-Content -LiteralPath (Join-Path $borderModePath 'runner.txt')
  $remote='/sdcard/Android/data/cc.huevia/files/photo-borders-89/'+$borderMode+'/'
  BorderAdb @('pull',($remote+'report.json'),(Join-Path $borderModePath 'report.json'))|Out-Null
  $report=Get-Content -LiteralPath (Join-Path $borderModePath 'report.json') -Raw|ConvertFrom-Json
  if($report.status-ne'PASS'){BorderAdb @('pull',($remote+'failure.png'),(Join-Path $borderModePath 'failure.png'))|Out-Null;throw ('Border native failed: '+$borderMode+' '+$report.failure)}
  if($report.apkSha256-ne$borderHash){throw 'Native evidence belongs to another APK.'}
  foreach($file in @('editor-template-1.png','editor-template-2.png','editor-template-3.png','camera-auto.png','camera-manual.png','border-information.png','border-appearance.png','template-1.JPG','template-2.JPG','template-3.JPG','hero-repro.JPG','missing-orientation-1.JPG','missing-orientation-6.JPG')){BorderAdb @('pull',($remote+$file),(Join-Path $borderModePath $file))|Out-Null}
  if($borderMode-eq'phone'){foreach($brand in @('NIKON','FUJIFILM','SONY','CANON','LONG')){foreach($template in @(1,3)){$file="brand-$brand-$template.JPG";BorderAdb @('pull',($remote+$file),(Join-Path $borderModePath $file))|Out-Null}}}
  if($borderMode-eq'landscape'){BorderAdb @('pull',($remote+'camera-manual-scrolled.png'),(Join-Path $borderModePath 'camera-manual-scrolled.png'))|Out-Null}
  $borderReports+=@{mode=$borderMode;checks=$report.checks;apkSha256=$report.apkSha256;status=$report.status};Write-Output ($borderMode+': PASS '+$report.checks)
 }
}finally{
 if($borderPrior.fontScale-eq'null'){BorderAdb @('shell','settings','delete','system','font_scale')|Out-Null}else{BorderAdb @('shell','settings','put','system','font_scale',$borderPrior.fontScale)|Out-Null}
 $borderPriorNight=($borderPrior.night-split ':')[-1].Trim();BorderAdb @('shell','cmd','uimode','night',$borderPriorNight)|Out-Null
 if($borderPrior.size-match'Override size: (\d+x\d+)'){BorderAdb @('shell','wm','size',$Matches[1])|Out-Null}else{BorderAdb @('shell','wm','size','reset')|Out-Null}
 if($borderPrior.density-match'Override density: (\d+)'){BorderAdb @('shell','wm','density',$Matches[1])|Out-Null}else{BorderAdb @('shell','wm','density','reset')|Out-Null}
 $borderSummary=[ordered]@{apkSha256=$borderHash;prior=$borderPrior;reports=$borderReports;restored=@{fontScale=((BorderAdb @('shell','settings','get','system','font_scale'))-join '').Trim();night=((BorderAdb @('shell','cmd','uimode','night'))-join '').Trim();size=((BorderAdb @('shell','wm','size'))-join "`n");density=((BorderAdb @('shell','wm','density'))-join "`n")}}
 [IO.File]::WriteAllText((Join-Path $borderEvidence 'matrix.json'),($borderSummary|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
