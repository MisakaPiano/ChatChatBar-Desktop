# Existing-failure mode only. Requires the unchanged common Header helper loaded
# by the separately verified host entry. Defining functions performs no probing.
Set-StrictMode -Version 2
$ErrorActionPreference='Stop'
function Assert-Existing1112Binding($Binding) {
    $instance=[guid]::Empty
    if($Binding.protocol -ne 'CCB_1112_HEADER_PSDIRECT_V1' -or $Binding.host -ne 'DL1' -or
       $Binding.vmId -ne '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' -or
       $Binding.vmName -ne 'CCB-MSI-Op1-1112-1113' -or $Binding.vmState -ne 'Running' -or
       $Binding.transport -ne 'New-PSSession-VMId' -or
       -not [guid]::TryParse([string]$Binding.sessionInstanceId,[ref]$instance) -or $instance -eq [guid]::Empty){throw 'EXACT_1112_HOST_SESSION_BINDING_REQUIRED'}
}
function Get-Existing1112FreeBytes([string]$Path) {
    return (New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($Path))).AvailableFreeSpace
}
function Initialize-Existing1112ObservationNative {
    if(-not ('Existing1112MutexNative' -as [type])){
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class Existing1112MutexNative {
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] public static extern IntPtr OpenMutexW(uint access,bool inherit,string name);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern uint WaitForSingleObject(IntPtr handle,uint milliseconds);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool ReleaseMutex(IntPtr handle);
 [DllImport("kernel32.dll",SetLastError=true)] public static extern bool CloseHandle(IntPtr handle);
 [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr OpenSCManagerW(string machine,string database,uint access);
 [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr OpenServiceW(IntPtr manager,string name,uint access);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool QueryServiceStatusEx(IntPtr service,int level,[Out] byte[] buffer,uint size,out uint needed);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool CloseServiceHandle(IntPtr handle);
 [DllImport("advapi32.dll",SetLastError=true)] static extern bool GetTokenInformation(IntPtr token,int kind,IntPtr buffer,uint size,out uint needed);
 static uint TokenUInt(IntPtr token,int kind) {
  IntPtr b=Marshal.AllocHGlobal(4);
  try {uint needed;if(!GetTokenInformation(token,kind,b,4,out needed)||needed!=4)throw new InvalidOperationException("CURRENT_TOKEN_QUERY_UNKNOWN");return unchecked((uint)Marshal.ReadInt32(b));}
  finally{Marshal.FreeHGlobal(b);}
 }
 public static string[] CurrentTokenContext() {
  using(var identity=System.Security.Principal.WindowsIdentity.GetCurrent()) {
   uint needed;
   if(GetTokenInformation(identity.Token,25,IntPtr.Zero,0,out needed)||Marshal.GetLastWin32Error()!=122||needed==0||needed>65536)throw new InvalidOperationException("CURRENT_INTEGRITY_SIZE_UNKNOWN");
   IntPtr b=Marshal.AllocHGlobal((int)needed);
   try {
    if(!GetTokenInformation(identity.Token,25,b,needed,out needed))throw new InvalidOperationException("CURRENT_INTEGRITY_QUERY_UNKNOWN");
    string integrity=new System.Security.Principal.SecurityIdentifier(Marshal.ReadIntPtr(b)).Value;
    return new string[]{identity.User.Value,identity.Name,TokenUInt(identity.Token,18).ToString(),TokenUInt(identity.Token,20).ToString(),integrity,identity.ImpersonationLevel.ToString()};
   } finally{Marshal.FreeHGlobal(b);}
  }
 }
 public static uint[] ServiceStatus() {
  IntPtr manager=OpenSCManagerW(null,null,1),service=IntPtr.Zero;
  if(manager==IntPtr.Zero)throw new InvalidOperationException("MSI_SERVICE_MANAGER_QUERY_UNKNOWN");
  try {
   service=OpenServiceW(manager,"msiserver",4);
   if(service==IntPtr.Zero)throw new InvalidOperationException("MSI_SERVICE_OPEN_QUERY_UNKNOWN");
   byte[] b=new byte[36];uint needed;
   if(!QueryServiceStatusEx(service,0,b,(uint)b.Length,out needed))throw new InvalidOperationException("MSI_SERVICE_STATUS_QUERY_UNKNOWN");
   return new uint[]{BitConverter.ToUInt32(b,4),BitConverter.ToUInt32(b,8),BitConverter.ToUInt32(b,28)};
  } finally {
   try{if(service!=IntPtr.Zero && !CloseServiceHandle(service))throw new InvalidOperationException("MSI_SERVICE_HANDLE_CLOSE_UNKNOWN");}
   finally{if(!CloseServiceHandle(manager))throw new InvalidOperationException("MSI_MANAGER_HANDLE_CLOSE_UNKNOWN");}
  }
 }
}
'@
    }
}
function Get-Existing1112ServiceControlStatus {
    Initialize-Existing1112ObservationNative
    $status=[Existing1112MutexNative]::ServiceStatus()
    return @{state=$status[0];controlsAccepted=$status[1];pid=$status[2]}
}
function Get-Existing1112AccountContext {
    Initialize-Existing1112ObservationNative
    $value=[Existing1112MutexNative]::CurrentTokenContext()
    if($value.Count -ne 6){throw 'CURRENT_ACCOUNT_TOKEN_CONTEXT_UNKNOWN'}
    $integrity=[regex]::Match($value[4],'^S-1-16-(\d+)$')
    if($value[0] -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$' -or -not $value[1] -or
       $value[2] -notin @('1','2','3') -or $value[3] -notin @('0','1') -or -not $integrity.Success -or
       ($value[2] -eq '2' -and $value[3] -ne '1') -or ($value[2] -eq '3' -and $value[3] -ne '0') -or
       $value[5] -cnotin @('None','Anonymous','Identification','Impersonation','Delegation')){throw 'CURRENT_ACCOUNT_TOKEN_CONTEXT_UNKNOWN'}
    return @{status='OBSERVED';sid=$value[0];accountName=$value[1];elevationType=[int]$value[2];isElevated=($value[3] -eq '1');
        integritySid=$value[4];integrityRid=[uint32]$integrity.Groups[1].Value;impersonationLevel=$value[5]}
}
function New-Existing1112Receipt($Plan,[bool]$Execute) {
    return @{receiptVersion=1;kind=$(if($Execute){'DIAGNOSTIC'}else{'PREFLIGHT'});mode='EXISTING_1112_FAILURE_HEADER_ONLY';
        status=$(if($Execute){'PREPARED'}else{'PREFLIGHT_READY_NOT_EXECUTED'});binding=$Plan.binding;guest=$Plan.guest;accountContext=$Plan.accountContext;
        contextEquivalence='UNKNOWN';contextEquivalenceReason='ORIGINAL_TOKEN_AND_CA_CONTEXT_NOT_RECORDED';
        caContext=@{status='NOT_OBSERVED';bitness=$null;invocation=$null;sid=$null;elevationType=$null;isElevated=$null;integritySid=$null;equivalence='UNKNOWN';probeMsiSha256=$Plan.identity.sha256};
        baseline=$Plan.baselineProof;before=$Plan.before;after=$null;installerInvoked=$false;nativeExit=$null;observed=$null;error=$null;retry=$false;noCleanup=$true;
        resultDirectory=$Plan.output;originalPackageSha256=$Plan.identity.sourceSha256;probePackageSha256=$Plan.identity.sha256;probeManifestSha256=$Plan.probeManifestSha256}
}
function Get-Existing1112MutexState([string]$Name) {
    Initialize-Existing1112ObservationNative
    $mutex=[Existing1112MutexNative]::OpenMutexW(0x100001,$false,$Name)
    if($mutex -eq [IntPtr]::Zero){
        $code=[Runtime.InteropServices.Marshal]::GetLastWin32Error()
        # OpenMutex's documented NAME_NOT_FOUND is distinct from failed MSI
        # inventory enumeration. Other errors, including invalid object type, refuse.
        if($code -eq 2){return 'ABSENT'}
        throw 'MSI_MUTEX_OPEN_OBSERVATION_UNKNOWN'
    }
    $owned=$false
    try{
        $wait=[Existing1112MutexNative]::WaitForSingleObject($mutex,0)
        if($wait -eq 0){$owned=$true;return 'AVAILABLE'}
        if($wait -eq 128){$owned=$true;return 'UNKNOWN_ABANDONED'}
        if($wait -eq 258){return 'BUSY'}
        throw 'MSI_MUTEX_WAIT_OBSERVATION_UNKNOWN'
    }finally{
        try{if($owned -and -not [Existing1112MutexNative]::ReleaseMutex($mutex)){throw 'MSI_MUTEX_RELEASE_FAILED'}}
        finally{if(-not [Existing1112MutexNative]::CloseHandle($mutex)){throw 'MSI_MUTEX_CLOSE_FAILED'}}
    }
}
function Get-Existing1112InProgressState {
    $inProgress=@()
    foreach($view in @([Microsoft.Win32.RegistryView]::Registry64,[Microsoft.Win32.RegistryView]::Registry32)){
        $base=$null;$key=$null
        try{
            $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::LocalMachine,$view)
            $key=$base.OpenSubKey('SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\InProgress',$false)
            $inProgress+=($null -ne $key)
        }finally{if($key){$key.Dispose()};if($base){$base.Dispose()}}
    }
    return @($inProgress)
}
function Get-Existing1112InstallerActivity {
    # No command lines or arbitrary installer properties are read. The idle MSI
    # service alone is not an active install; unexplained client PIDs are refused.
    $clients=@(Get-CimInstance -Query "SELECT ProcessId FROM Win32_Process WHERE Name='msiexec.exe'" -ErrorAction Stop)
    $service=@(Get-CimInstance -Query "SELECT ProcessId,State FROM Win32_Service WHERE Name='msiserver'" -ErrorAction Stop)
    if($service.Count -ne 1){throw 'INSTALLER_SERVICE_OBSERVATION_UNKNOWN'}
    if($null -eq $service[0].ProcessId -or $service[0].State -notin @('Running','Stopped')){throw 'INSTALLER_SERVICE_STATE_UNKNOWN'}
    $serverPid=[long]$service[0].ProcessId
    if(($service[0].State -eq 'Running' -and $serverPid -le 0) -or ($service[0].State -eq 'Stopped' -and $serverPid -ne 0)){throw 'INSTALLER_SERVICE_PID_UNKNOWN'}
    foreach($client in $clients){if($null -eq $client.ProcessId -or [long]$client.ProcessId -le 0){throw 'INSTALLER_CLIENT_PID_UNKNOWN'}}
    $nativeStatus=Get-Existing1112ServiceControlStatus
    if($null -eq $nativeStatus -or $nativeStatus.controlsAccepted -isnot [uint32] -or
       $nativeStatus.state -notin @(1,4) -or $nativeStatus.pid -ne $serverPid -or
       (($nativeStatus.state -eq 4) -ne ($service[0].State -eq 'Running'))){throw 'INSTALLER_NATIVE_SERVICE_STATUS_UNKNOWN'}
    # Microsoft documents SERVICE_ACCEPT_SHUTDOWN for the Installer's running
    # operation; a Running service process alone can remain idle after a failure.
    if($nativeStatus.controlsAccepted -band 4){throw 'WINDOWS_INSTALLER_SERVICE_BUSY'}
    $other=@($clients|Where-Object {[long]$_.ProcessId -ne $serverPid})
    $mutexes=@(Get-Existing1112MutexState 'Global\_MSIExecute';Get-Existing1112MutexState '_MSIExecute')
    $inProgress=@(Get-Existing1112InProgressState)
    return @{observed=$true;otherClientCount=$other.Count;mutexes=@($mutexes);inProgress=@($inProgress)}
}
function Assert-Existing1112InstallerIdle($Activity) {
    if($Activity.observed -ne $true -or $Activity.otherClientCount -ne 0 -or @($Activity.mutexes).Count -ne 2 -or
       @($Activity.inProgress).Count -ne 2 -or @($Activity.mutexes|Where-Object {$_ -notin @('ABSENT','AVAILABLE')}).Count -ne 0 -or
       @($Activity.inProgress|Where-Object {$_ -ne $false}).Count -ne 0){throw 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED'}
}
function Assert-Existing1112Baseline($Directory,$Expected) {
    $fixed=@('COMMAND.json','RESULT.json','install-1112.log')
    if(@($Expected).Count -ne 3 -or @($Expected.name|Sort-Object -Unique).Count -ne 3){throw 'EXACT_THREE_BASELINE_IDENTITIES_REQUIRED'}
    $proofs=@()
    foreach($name in $fixed){
        $rows=@($Expected|Where-Object {$_.name -ceq $name})
        if($rows.Count -ne 1 -or $rows[0].sha256 -notmatch '^[a-f0-9]{64}$' -or $rows[0].bytes -le 0){throw 'BASELINE_IDENTITY_INVALID'}
        $proof=Get-HeaderFileProof (Join-Path $Directory $name)
        if($proof.bytes -ne $rows[0].bytes -or $proof.sha256 -cne $rows[0].sha256){throw 'ORIGINAL_1112_EVIDENCE_MISMATCH'}
        $proofs+=@{name=$name;bytes=$proof.bytes;sha256=$proof.sha256}
    }
    return @($proofs)
}
function Get-Existing1112Preflight([string]$Source,[string]$OriginalDirectory,$Policy,$Binding) {
    Assert-Existing1112Binding $Binding
    $source=Assert-HeaderPath $Source;$original=Assert-HeaderPath $OriginalDirectory
    $manifest=Join-Path $source 'DELIVERY_IDENTITY.json'
    if((Get-HeaderFileProof $manifest).sha256 -cne $Policy.probeManifestSha256){throw 'FROZEN_PROBE_MANIFEST_MISMATCH'}
    $manifestData=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json
    foreach($row in $manifestData.files){
        if($row.name -notmatch '^[A-Za-z0-9_.-]+$'){throw 'PROBE_MANIFEST_LEAF_REQUIRED'}
        $proof=Get-HeaderFileProof (Join-Path $source $row.name)
        if($proof.bytes -ne $row.bytes -or $proof.sha256 -cne $row.sha256){throw 'FROZEN_PROBE_FILE_MISMATCH'}
    }
    $identity=Get-Content -LiteralPath (Join-Path $source 'PROBE_IDENTITY.json') -Raw|ConvertFrom-Json
    if($identity.profile -ne 'HEADER_EARLY_STOP_ONLY_NOT_INSTALL' -or $identity.fileName -ne 'CCB-Header-EarlyStop-Probe-1112.msi' -or
       $identity.sha256 -cne 'ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558' -or
       $identity.sourceSha256 -cne '5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'){throw 'FIXED_PROBE_MSI_IDENTITY_REQUIRED'}
    $msi=Join-Path $source $identity.fileName
    $sourceMsi=Join-Path $original 'ChatChatBarDesktopSynthetic-0.1.1112.msi'
    if((Get-HeaderFileProof $msi).sha256 -cne $identity.sha256){throw 'PROBE_MSI_HASH_MISMATCH'}
    if((Get-HeaderFileProof $sourceMsi).sha256 -cne $identity.sourceSha256){throw 'ORIGINAL_MSI_HASH_MISMATCH'}
    $guest=Get-HeaderGuest
    if($guest.computer -in @('DD1','DL1') -or $guest.manufacturer -ne 'Microsoft Corporation' -or $guest.model -ne 'Virtual Machine' -or
       $guest.bits -ne 64 -or $guest.build -ne '19045' -or $guest.edition -ne 'Professional' -or
       $guest.sid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$'){throw 'FIXED_1112_WINDOWS_GUEST_REQUIRED'}
    $accountContext=Get-Existing1112AccountContext
    if($accountContext.status -ne 'OBSERVED' -or $accountContext.sid -ne $guest.sid){throw 'CURRENT_ACCOUNT_CONTEXT_UNKNOWN_OR_SID_MISMATCH'}
    $baseline=Assert-HeaderPath (Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01')
    $baselineProof=@(Assert-Existing1112Baseline $baseline $Policy.baseline)
    $prior=Get-Content -LiteralPath (Join-Path $baseline 'RESULT.json') -Raw|ConvertFrom-Json
    if($prior.installExit -ne 1603 -or $prior.installerInvoked -ne $true -or $prior.guest.sid -ne $guest.sid -or
       $prior.guest.computer -ne $guest.computer -or $null -ne $prior.before.state -or $null -ne $prior.after.state -or
       $prior.before.feature -ne -1 -or $prior.after.feature -ne -1 -or @($prior.before.related).Count -ne 0 -or @($prior.after.related).Count -ne 0){throw 'ORIGINAL_FAILURE_CONTEXT_OR_ACCOUNT_MISMATCH'}
    $priorLog=Get-Content -LiteralPath (Join-Path $baseline 'install-1112.log') -Raw
    if($priorLog -notmatch 'CCB_OPERATION_DENIED stage=ACTIVE_RESOURCE_CONTRACT'){throw 'ORIGINAL_HEADER_FAILURE_NOT_OBSERVED'}
    $output=Assert-HeaderPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01')
    if(Test-Path -LiteralPath $output -ErrorAction Stop){throw 'EXISTING_DIAGNOSTIC_OUTPUT_REFUSED_NO_OVERWRITE'}
    $free=Get-Existing1112FreeBytes $output
    if($null -eq $free -or $free -isnot [long] -or $free -lt 1GB){throw 'GUEST_FREE_SPACE_UNKNOWN_OR_BELOW_1_GIB'}
    $registration=Get-HeaderRegistration $identity $guest.sid;Assert-HeaderAbsent $registration
    $activity=Get-Existing1112InstallerActivity;Assert-Existing1112InstallerIdle $activity
    return @{mode='EXISTING_1112_FAILURE_HEADER_ONLY';guest=$guest;accountContext=$accountContext;binding=$Binding;source=$source;original=$original;
        identity=$identity;baseline=$baseline;baselineProof=$baselineProof;output=$output;guestFreeBytes=$free;
        probeManifestSha256=$Policy.probeManifestSha256;
        before=$registration;installerActivity=$activity;readOnlyPreflight=$true;actualInstallerInvoked=$false;
        baselineRuntimePreserved=$false;noGlobalNoWriteClaim=$true}
}
function Invoke-Existing1112Diagnostic([string]$Source,[string]$OriginalDirectory,$Policy,$Binding,[bool]$Confirmed) {
    if(-not $Confirmed){throw 'EXISTING_1112_INSTALLER_WRITES_NOT_CONFIRMED'}
    $plan=Get-Existing1112Preflight $Source $OriginalDirectory $Policy $Binding
    $created=$false;$result=New-Existing1112Receipt $plan $true
    try{
        $null=New-Item -ItemType Directory -Path $plan.output -ErrorAction Stop;$created=$true
        $null=New-Item -ItemType Directory -Path (Join-Path $plan.output 'baseline') -ErrorAction Stop
        foreach($row in $plan.baselineProof){
            $from=Join-Path $plan.baseline $row.name;$to=Join-Path (Join-Path $plan.output 'baseline') $row.name
            if((Get-HeaderFileProof $from).sha256 -cne $row.sha256){throw 'BASELINE_CHANGED_BEFORE_COPY'}
            Copy-Item -LiteralPath $from -Destination $to -ErrorAction Stop
            if((Get-HeaderFileProof $to).sha256 -cne $row.sha256){throw 'BASELINE_PRESERVATION_COPY_MISMATCH'}
        }
        Assert-Existing1112InstallerIdle (Get-Existing1112InstallerActivity)
        $result.installerInvoked=$true
        $result.nativeExit=Invoke-HeaderNativeProbe (Join-Path $plan.source $plan.identity.fileName) (Join-Path $plan.output 'probe.log') $plan.output
        $result.after=Get-HeaderRegistration $plan.identity $plan.guest.sid;Assert-HeaderAbsent $result.after
        $null=Assert-Existing1112Baseline $plan.baseline $plan.baselineProof
        if((Get-HeaderFileProof (Join-Path $plan.original 'ChatChatBarDesktopSynthetic-0.1.1112.msi')).sha256 -cne $plan.identity.sourceSha256 -or
           (Get-HeaderFileProof (Join-Path $plan.source $plan.identity.fileName)).sha256 -cne $plan.identity.sha256){throw 'INPUT_PACKAGE_CHANGED'}
        $logText=Get-Content -LiteralPath (Join-Path $plan.output 'probe.log') -Raw
        # This fixed MSI logs bitness/impersonated invocation, but no actual CA
        # SID/elevation/integrity. Do not substitute the PowerShell token for it.
        if(Test-HeaderCaContextMarker $logText){
            $result.caContext.status='LIMITED_LOG_ONLY';$result.caContext.bitness=64;$result.caContext.invocation='Impersonated'
        }
        $result.observed=Test-HeaderLog $logText $result.nativeExit
        $result.status='HEADER_DIAGNOSTIC_OBSERVED_NOT_INSTALL_ACCEPTANCE'
    }catch{$result.status='STOP_PRESERVE_NO_RETRY';$result.error=[string]$_.Exception.Message;throw}
    finally{if($created){$result|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $plan.output 'RESULT.json') -Encoding UTF8}}
    return $result
}
