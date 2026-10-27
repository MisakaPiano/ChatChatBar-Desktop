param([Parameter(Mandatory=$true)][string]$RunId,[Parameter(Mandatory=$true)][string]$ImageId,[Parameter(Mandatory=$true)][string]$LocalId,[ValidateSet('normal','portable')][string]$Mode='normal')
$ErrorActionPreference='Stop'
$gate=$PSScriptRoot
$run=Join-Path $gate "runs/$RunId"
New-Item -ItemType Directory -Path $run -ErrorAction Stop | Out-Null
$image=Join-Path $gate $ImageId
$exe=Join-Path $image 'ChatChatBarDesktop.exe'
$local=Join-Path $gate "environments/$LocalId"
New-Item -ItemType Directory -Path $local -Force | Out-Null
$root=if($Mode -eq 'portable'){Join-Path $image 'UserData'}else{Join-Path $local 'ChatChatBarDesktop'}
Add-Type @'
using System;
using System.IO;
using System.Collections.Concurrent;
public sealed class ProbeObservation : IDisposable {
 public readonly ConcurrentQueue<string> Events=new ConcurrentQueue<string>();
 public readonly ConcurrentQueue<string> Errors=new ConcurrentQueue<string>();
 private readonly FileSystemWatcher watcher;
 public ProbeObservation(string path){
  watcher=new FileSystemWatcher(path,".portable-write-probe-*"); watcher.IncludeSubdirectories=true; watcher.InternalBufferSize=65536;
  watcher.Created+=(s,e)=>Events.Enqueue(DateTimeOffset.UtcNow.ToString("o")+" CREATED "+e.Name);
  watcher.Deleted+=(s,e)=>Events.Enqueue(DateTimeOffset.UtcNow.ToString("o")+" DELETED "+e.Name);
  watcher.Error+=(s,e)=>Errors.Enqueue(e.GetException().GetType().Name);
  watcher.EnableRaisingEvents=true;
 }
 public void Dispose(){watcher.Dispose();}
}
'@
$watchPath=if($Mode -eq 'portable'){$root}else{$local}
$watch=[ProbeObservation]::new($watchPath)
$started=[DateTimeOffset]::UtcNow
$psi=[System.Diagnostics.ProcessStartInfo]::new($exe)
$psi.UseShellExecute=$false
$psi.WorkingDirectory=$image
$psi.CreateNoWindow=$true
$psi.EnvironmentVariables['LOCALAPPDATA']=$local
$psi.EnvironmentVariables['CCB_SHUTDOWN_TRACE']='1'
$psi.RedirectStandardOutput=$true
$psi.RedirectStandardError=$true
$launcher=[System.Diagnostics.Process]::new()
$launcher.StartInfo=$psi
if(-not $launcher.Start()){throw 'Native launcher start failed'}
$outTask=$launcher.StandardOutput.ReadToEndAsync(); $errTask=$launcher.StandardError.ReadToEndAsync()
$owned=@{}; $owned[$launcher.Id]=$launcher
$identities=@{}; $observedJvm=@{}; $moduleReadErrors=@{}
$launcherStart=$launcher.StartTime.ToUniversalTime().ToString('o')
[ordered]@{run=$RunId;image=$ImageId;localEnvironment=$LocalId;rootMode=$Mode;launcherPid=$launcher.Id;launcherCreatedUtc=$launcherStart;startedUtc=$started.ToString('o');launcherPath=$exe;expectedDataRoot=$root;injectedLocalAppData=$local;startupWatchdogSeconds=180;exitWatchdogSeconds=120;lifetimeWatchdogSeconds=$null;manualSessionNoForcedExpiry=$true} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'launch.json') -Encoding utf8
Write-Output "RUN=$RunId LAUNCHER_PID=$($launcher.Id)"
$counter=0; $timedOut=$false; $closeTime=$null
try {
 while($true){
  $counter++
  $rows=@(Get-CimInstance Win32_Process -Filter "Name='ChatChatBarDesktop.exe'")
  foreach($row in $rows){
   if($row.ExecutablePath -ne $exe -or $row.CreationDate.ToUniversalTime() -lt $started.UtcDateTime.AddSeconds(-1)){continue}
   if($owned.ContainsKey([int]$row.ProcessId) -or $owned.ContainsKey([int]$row.ParentProcessId)){
    if(-not $owned.ContainsKey([int]$row.ProcessId)){
     try{
      $child=[System.Diagnostics.Process]::GetProcessById([int]$row.ProcessId)
      $null=$child.Handle # Retain a real query handle across exit; do not rely on PID reuse.
      $owned[[int]$row.ProcessId]=$child
     }catch{continue}
    }
    $identities[[int]$row.ProcessId]=[ordered]@{pid=[int]$row.ProcessId;parentPid=[int]$row.ParentProcessId;createdUtc=$row.CreationDate.ToUniversalTime().ToString('o');executablePath=$row.ExecutablePath;image=$ImageId}
   }
  }
  $live=@()
  foreach($key in @($owned.Keys)){
   $proc=$owned[$key]
   if(-not $proc.HasExited){
    $live+=$key
    if(-not $observedJvm.ContainsKey($key)){
     try{$modules=@($proc.Modules | ForEach-Object {$_.ModuleName});if($modules -contains 'jvm.dll'){$observedJvm[$key]=$true}}
     catch{$moduleReadErrors[$key]=$_.Exception.GetType().Name}
    }
   }
  }
  if($counter -eq 1 -or $counter % 5 -eq 0){
   [ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');processes=@($identities.Values);alivePids=$live;jvmPids=@($observedJvm.Keys)} | ConvertTo-Json -Depth 6 -Compress | Add-Content -LiteralPath (Join-Path $run 'process-observations.jsonl') -Encoding utf8
  }
  $marker=Join-Path $run 'close-request.json'
  if($null -eq $closeTime -and (Test-Path -LiteralPath $marker)){$closeTime=[DateTimeOffset]::UtcNow}
  if($live.Count -eq 0){break}
  if($null -ne $closeTime -and ([DateTimeOffset]::UtcNow-$closeTime).TotalSeconds -gt 120){$timedOut=$true;break}
  Start-Sleep -Milliseconds 500
 }
 $ended=[DateTimeOffset]::UtcNow
 $results=@()
 foreach($key in @($owned.Keys)){
  $proc=$owned[$key]; $exited=$proc.HasExited
  $results += [ordered]@{pid=$key;identity=$identities[$key];isLauncher=($key -eq $launcher.Id);jvmLoaded=$observedJvm.ContainsKey($key);exited=$exited;exitCode=if($exited){$proc.ExitCode}else{$null};moduleReadError=$moduleReadErrors[$key]}
 }
 if($launcher.HasExited){
  $outTask.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $run 'stdout.log') -Encoding utf8
  $errTask.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $run 'stderr.log') -Encoding utf8
 }
 $watch.Dispose()
 [ordered]@{run=$RunId;startedUtc=$started.ToString('o');endedUtc=$ended.ToString('o');durationSeconds=($ended-$started).TotalSeconds;closeObservedUtc=if($null -ne $closeTime){$closeTime.ToString('o')}else{$null};processLifetimeBlocked=$timedOut;allOwnedProcessesExited=(@($results | Where-Object {-not $_.exited}).Count -eq 0);processes=$results;probeEvents=@($watch.Events.ToArray());probeObservationErrors=@($watch.Errors.ToArray())} | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $run 'process-result.json') -Encoding utf8
 Write-Output "RUN=$RunId COMPLETE TIMEOUT=$timedOut OWNED_COUNT=$($results.Count)"
 if($timedOut){exit 3}
}finally{
 $watch.Dispose()
 # No Kill, detached app cleanup, lock deletion, or root deletion, even after timeout.
}
