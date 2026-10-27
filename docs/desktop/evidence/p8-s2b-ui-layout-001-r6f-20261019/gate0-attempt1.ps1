$ErrorActionPreference='Stop'
$jdk='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$cwd='H:\ChatChatBar-Desktop\app'
$systemCmd=Join-Path $env:SystemRoot 'System32\cmd.exe'
$searchDirs=@((Join-Path $jdk 'bin'),$cwd,(Join-Path $env:SystemRoot 'System32'),(Join-Path $env:SystemRoot 'SysWOW64'),$env:SystemRoot)+@($env:PATH -split ';' | Where-Object {$_})
$seen=@{};$candidates=@()
foreach($dir in $searchDirs){
 try {$p=[IO.Path]::GetFullPath((Join-Path $dir.Trim('"') 'cmd.exe'))} catch {continue}
 if(!$seen.ContainsKey($p.ToLowerInvariant())){
  $seen[$p.ToLowerInvariant()]=$true
  if(Test-Path -LiteralPath $p -PathType Leaf){$candidates+=$p}
 }
}
$identities=@(foreach($p in $candidates){
 $item=Get-Item -LiteralPath $p -Force
 $sig=Get-AuthenticodeSignature -LiteralPath $p
 $stream=[IO.File]::OpenRead($p)
 try {
  $reader=[IO.BinaryReader]::new($stream)
  $mz=$reader.ReadUInt16();$stream.Position=60;$offset=$reader.ReadInt32();$stream.Position=$offset
  $pe=$reader.ReadUInt32();$machine=$reader.ReadUInt16()
 }finally{$stream.Dispose()}
 [pscustomobject]@{path=$p;bytes=$item.Length;sha256=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower();attributes=$item.Attributes.ToString();reparsePoint=($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0;fileVersion=$item.VersionInfo.FileVersion;company=$item.VersionInfo.CompanyName;originalFilename=$item.VersionInfo.OriginalFilename;isPortableExecutable=($mz -eq 0x5a4d -and $pe -eq 0x4550);machine=$machine;signatureStatus=$sig.Status.ToString();microsoftSigner=($sig.SignerCertificate.Subject -match 'Microsoft');signatureType=$sig.SignatureType.ToString()}
})
$acl=Get-Acl -LiteralPath $systemCmd
$aclSummary=[ordered]@{ownerIsWindowsSystemPrincipal=($acl.Owner -match 'TrustedInstaller|SYSTEM|Administrators');accessRuleCount=$acl.Access.Count;denyExecuteRules=@($acl.Access | Where-Object {$_.AccessControlType -eq 'Deny' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ExecuteFile)}).Count;accountsAndAclEntriesOmitted=True;effectiveExecutePermission='Not asserted from ACL; Gate A will exercise actual Java ProcessBuilder'}
$current=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
$privilege=[ordered]@{pid=$PID;processName=(Get-Process -Id $PID).ProcessName;is64BitProcess=[Environment]::Is64BitProcess;administratorRoleEnabled=$current.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator);runAsRequested=False;uacOrTokenChanges=False;userAccountNotRecorded=True}
$old=Get-Content -LiteralPath 'docs/desktop/evidence/p8-s2b-ui-layout-001-r6e-20261019/03-bounded-desktop/stage.json' -Raw | ConvertFrom-Json
$eventResults=@()
foreach($spec in @(
 @{name='Microsoft-Windows-AppLocker/EXE and DLL';ids=@(8003,8004)},
 @{name='Microsoft-Windows-CodeIntegrity/Operational';ids=@(3033,3076,3077)},
 @{name='Microsoft-Windows-Windows Defender/Operational';ids=@(1116,1117,1121,1122)}
)){
 try{
  $events=@(Get-WinEvent -FilterHashtable @{LogName=$spec.name;StartTime=([DateTimeOffset]::Parse($old.startedUtc)).LocalDateTime;EndTime=([DateTimeOffset]::Parse($old.finishedUtc)).LocalDateTime;Id=$spec.ids} -MaxEvents 20 -ErrorAction Stop)
  $matches=@($events | Where-Object {$_.Message -match '(?i)\b(cmd|java|javaw)\.exe\b'} | ForEach-Object {[pscustomobject]@{id=$_.Id;utc=$_.TimeCreated.ToUniversalTime().ToString('o');recordId=$_.RecordId;provider=$_.ProviderName;executableMatchOnly=True;messageAndAccountOmitted=True}})
  $eventResults+=[pscustomobject]@{channel=$spec.name;status='READ';examined=$events.Count;related=$matches}
 }catch{
  $eventResults+=[pscustomobject]@{channel=$spec.name;status='NO_RECORD_OR_UNAVAILABLE';errorId=$_.FullyQualifiedErrorId;hresult=$_.Exception.HResult;messageOmitted=True}
 }
}
$report=[ordered]@{utc=[DateTimeOffset]::UtcNow.ToString('o');workingDirectory=$cwd;diagnosticWorkingDirectory=(Get-Location).Path;SystemRoot=$env:SystemRoot;ComSpec=$env:ComSpec;systemCmd=$systemCmd;powerShellResolvedCmd=@(Get-Command cmd.exe -CommandType Application -All | Select-Object -ExpandProperty Source);cmdCandidates=$identities;searchScope='JDK bin, test working directory, system directories and existing PATH candidates only; full PATH not output';acl=$aclSummary;privilege=$privilege;jdk=@{home=$jdk;javaVersion=@(Get-Content (Join-Path $jdk 'release') | Where-Object {$_ -match '^JAVA_VERSION='});javaExeSha256=(Get-FileHash (Join-Path $jdk 'bin\java.exe') -Algorithm SHA256).Hash.ToLower()};gradle=@{version='9.1.0';heap='-Xmx2048m';workerJdk='jvmToolchain(17), same JDK17 runner';workerEnvironment='No Java worker alive at resource preflight; inherited SystemRoot/ComSpec; no env dump or worker code modification';workingDirectory=$cwd};securityEventWindow=@{startUtc=$old.startedUtc;endUtc=$old.finishedUtc;maxEventsPerChannel=20};securityEvents=$eventResults;securityPolicyModified=False;alternativeShellUsed=False;cmdSpawnDiagnostic='NOT EXECUTED; only original Gate A may spawn cmd.exe'}
$report | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $PSScriptRoot 'gate0-environment.json') -Encoding UTF8
$report | ConvertTo-Json -Depth 10
$untrusted=@($identities | Where-Object {$_.signatureStatus -ne 'Valid' -or !$_.microsoftSigner -or !$_.isPortableExecutable -or $_.reparsePoint -or $_.path -notmatch '(?i)\\Windows\\(System32|SysWOW64)\\cmd\.exe$'})
if($untrusted.Count -or !$aclSummary.ownerIsWindowsSystemPrincipal -or $aclSummary.denyExecuteRules){throw 'GATE 0 EXECUTABLE OR PERMISSION ANOMALY / STOP'}
