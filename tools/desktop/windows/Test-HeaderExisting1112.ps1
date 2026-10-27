param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'REAL_PS51_REQUIRED'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_TEST_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
. (Join-Path $PSScriptRoot 'Invoke-HeaderEarlyStopProbe.ps1')
. (Join-Path $PSScriptRoot 'HeaderExisting1112Guest.ps1')
. (Join-Path $PSScriptRoot 'HeaderExisting1112Host.ps1')
$realProof=(Get-Command Get-HeaderFileProof).ScriptBlock
$realActivity=(Get-Command Get-Existing1112InstallerActivity).ScriptBlock
$tokens=$null;$errors=$null
$hostAst=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'HeaderExisting1112Host.ps1'),[ref]$tokens,[ref]$errors)
$receiptFunction=@($hostAst.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Read-Existing1112FailureReceipt'},$true))
$receiptExpressions=@($receiptFunction[0].Body.FindAll({param($n)$n -is [Management.Automation.Language.ScriptBlockExpressionAst]},$true))
if($receiptExpressions.Count -ne 1){throw 'EXACT_RECEIPT_BODY_REQUIRED'}
$receiptText=$receiptExpressions[0].ScriptBlock.Extent.Text
$readReceiptBody=[scriptblock]::Create($receiptText.Substring(1,$receiptText.Length-2))
$tokens=$null;$errors=$null
$oldTests=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-HeaderEarlyStopProbe.ps1'),[ref]$tokens,[ref]$errors)
$makeLog=@($oldTests.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Make-Log'},$true))
if($errors -or $makeLog.Count -ne 1){throw 'REUSE_FROZEN_PROTOCOL_FIXTURE_REQUIRED'}
. ([scriptblock]::Create($makeLog[0].Extent.Text))
$rows=New-Object Collections.Generic.List[object]
$savedLocal=$env:LOCALAPPDATA
$script:caseNumber=0
function Equal($A,$B){if($A -cne $B){throw ('NOT_EQUAL: '+$A+' / '+$B)}}
function Refuses([scriptblock]$Body,[string]$Prefix){
    try{&$Body|Out-Null}catch{if($_.Exception.Message -like ($Prefix+'*')){return};throw}
    throw ('DID_NOT_REFUSE_'+$Prefix)
}
function Absent-Native {
    return @{products=@(@{unmanaged='ABSENT';managed='ABSENT';machine='ABSENT';feature=-1},
                       @{unmanaged='ABSENT';managed='ABSENT';machine='ABSENT';feature=-1});related=@()}
}
function Reset-Fakes {
    $script:caseNumber++;$script:root=Join-Path $Evidence ('case-'+$script:caseNumber)
    $null=New-Item -ItemType Directory -Path $script:root
    $script:source=Join-Path $script:root 'probe';$script:original=Join-Path $script:root 'original'
    $env:LOCALAPPDATA=Join-Path $script:root 'local'
    $baseline=Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01'
    foreach($path in @($script:source,$script:original,$baseline)){$null=New-Item -ItemType Directory -Path $path}
    $script:guest=@{computer='SYNTHETIC-WIN10';sid='S-1-5-21-101-202-303-1001';manufacturer='Microsoft Corporation';model='Virtual Machine';build='19045';edition='Professional';bits=64}
    $state=@{state=$null;feature=-1;related=@()}
    @{installExit=1603;installerInvoked=$true;guest=$script:guest;before=$state;after=$state}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $baseline 'RESULT.json') -Encoding ASCII
    [IO.File]::WriteAllText((Join-Path $baseline 'COMMAND.json'),'SYNTHETIC_COMMAND_NOT_EXECUTABLE')
    [IO.File]::WriteAllText((Join-Path $baseline 'install-1112.log'),'CCB_OPERATION_DENIED stage=ACTIVE_RESOURCE_CONTRACT')
    $script:identity=@{profile='HEADER_EARLY_STOP_ONLY_NOT_INSTALL';fileName='CCB-Header-EarlyStop-Probe-1112.msi';
        sha256='ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558';
        sourceSha256='5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'}
    $script:identity|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $script:source 'PROBE_IDENTITY.json') -Encoding ASCII
    foreach($path in @((Join-Path $script:source $script:identity.fileName),(Join-Path $script:original 'ChatChatBarDesktopSynthetic-0.1.1112.msi'))){
        [IO.File]::WriteAllText($path,'FAKE_NON_EXECUTABLE_MSI_BOUNDARY_FIXTURE')
    }
    $script:wrongOriginal=$false;$script:wrongProbe=$false
    $files=@();foreach($name in @('PROBE_IDENTITY.json',$script:identity.fileName)){
        $p=Get-HeaderFileProof (Join-Path $script:source $name);$files+=@{name=$name;bytes=$p.bytes;sha256=$p.sha256}
    }
    @{files=$files}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $script:source 'DELIVERY_IDENTITY.json') -Encoding ASCII
    $proofs=@();foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){
        $p=Get-HeaderFileProof (Join-Path $baseline $name);$proofs+=@{name=$name;bytes=$p.bytes;sha256=$p.sha256}
    }
    $script:policy=@{baseline=$proofs;probeManifestSha256=(Get-HeaderFileProof (Join-Path $script:source 'DELIVERY_IDENTITY.json')).sha256}
    $script:binding=@{protocol='CCB_1112_HEADER_PSDIRECT_V1';host='DL1';vmId='61ce90eb-cd75-4181-9eb8-4faffaa1bed2';
        vmName='CCB-MSI-Op1-1112-1113';vmState='Running';transport='New-PSSession-VMId';sessionInstanceId='11111111-2222-4333-8444-555555555555'}
    $script:free=[long]2GB;$script:nativeCalls=0;$script:activityCalls=0;$script:registrationCalls=0;$script:guestCalls=0
    $script:activityFailure=$false;$script:registrationFailure=$false;$script:busyAt=0;$script:activity='AVAILABLE'
    $script:nativeExit=1603;$script:headerResult='PASS';$script:registrationConflict=$false
    $script:hostVmName='CCB-MSI-Op1-1112-1113';$script:hostVmState='Running';$script:hostVmId=$script:binding.vmId
    $script:hostFree=[long]3GB;$script:hostQueryFailure=$false;$script:networkConnected=$false
    $script:vmReads=0;$script:credentialCalls=0;$script:sessionOpens=0;$script:sessionCloses=0;$script:remoteCalls=0
    $script:hostChangeAt=0;$script:openedVmId=$null;$script:usedSessionId=$null;$script:remoteFail=$false
    $script:receiptReads=0
    $script:hostDisk=Join-Path $script:root 'host-disk.fake';[IO.File]::WriteAllText($script:hostDisk,'FAKE_DISK_NOT_VHD')
}
function Case([string]$Name,[scriptblock]$Body){
    $row=@{name=$Name;failure=$null};try{Reset-Fakes;&$Body}catch{$row.failure=[string]$_};$rows.Add($row)
}
function Get-HeaderFileProof([string]$Path){
    $real=&$realProof $Path
    if([IO.Path]::GetFileName($Path) -eq 'CCB-Header-EarlyStop-Probe-1112.msi'){
        return @{bytes=$real.bytes;sha256=$(if($script:wrongProbe){'0'*64}else{'ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558'})}
    }
    if([IO.Path]::GetFileName($Path) -eq 'ChatChatBarDesktopSynthetic-0.1.1112.msi'){
        return @{bytes=$real.bytes;sha256=$(if($script:wrongOriginal){'0'*64}else{'5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'})}
    }
    return $real
}
function Get-HeaderGuest {$script:guestCalls++;return $script:guest}
function Get-Existing1112FreeBytes([string]$Path){return $script:free}
function Get-Existing1112InstallerActivity {
    $script:activityCalls++
    if($script:activityFailure){throw 'SYNTHETIC_ACTIVITY_QUERY_FAILED'}
    return @{observed=$true;otherClientCount=0;mutexes=@($(if($script:busyAt -eq $script:activityCalls){'BUSY'}else{$script:activity}),'ABSENT');inProgress=@($false,$false)}
}
function Get-HeaderRegistration($Identity,[string]$Sid){
    $script:registrationCalls++;if($script:registrationFailure){throw 'SYNTHETIC_REGISTRATION_QUERY_FAILED'}
    $r=Absent-Native;if($script:registrationConflict -and $script:registrationCalls -gt 1){$r.products[0].unmanaged='SYNTHETIC_UNEXPECTED'};return $r
}
function Invoke-HeaderNativeProbe([string]$Msi,[string]$Log,[string]$Root){
    $script:nativeCalls++;[IO.File]::WriteAllText($Log,(Make-Log $script:headerResult));return $script:nativeExit
}
function Preflight {return (Get-Existing1112Preflight $script:source $script:original $script:policy $script:binding)}
function Execute {return (Invoke-Existing1112Diagnostic $script:source $script:original $script:policy $script:binding $true)}

try{
Case 'valid-readonly-preflight-does-not-create-output-or-run-msi' {$r=Preflight;Equal $r.mode 'EXISTING_1112_FAILURE_HEADER_ONLY';Equal $script:nativeCalls 0;Equal (Test-Path -LiteralPath $r.output) $false}
Case 'wrong-vm-refused-before-guest-observation' {$script:binding.vmId='74876b21-e684-4e33-a46c-98a7a7d5dc72';Refuses {Preflight} 'EXACT_1112_HOST_SESSION_BINDING_REQUIRED';Equal $script:guestCalls 0}
Case 'typed-id-without-new-psdirect-session-refused' {$script:binding.transport='USER_TYPED_UUID';Refuses {Preflight} 'EXACT_1112_HOST_SESSION_BINDING_REQUIRED'}
Case 'missing-session-identity-refused' {$script:binding.sessionInstanceId='';Refuses {Preflight} 'EXACT_1112_HOST_SESSION_BINDING_REQUIRED'}
Case 'wrong-probe-or-original-package-refused' {$script:wrongProbe=$true;Refuses {Preflight} 'FROZEN_PROBE_FILE_MISMATCH';$script:wrongProbe=$false;$script:wrongOriginal=$true;Refuses {Preflight} 'ORIGINAL_MSI_HASH_MISMATCH';Equal $script:nativeCalls 0}
Case 'wrong-manifest-refused-before-guest-query' {$script:policy.probeManifestSha256='0'*64;Refuses {Preflight} 'FROZEN_PROBE_MANIFEST_MISMATCH';Equal $script:guestCalls 0}
Case 'raw-evidence-tamper-refused-without-overwriting-original' {
    $path=Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01\COMMAND.json';[IO.File]::AppendAllText($path,'TAMPER')
    $before=(&$realProof $path).sha256;Refuses {Preflight} 'ORIGINAL_1112_EVIDENCE_MISMATCH';Equal (&$realProof $path).sha256 $before;Equal $script:nativeCalls 0
}
Case 'wrong-guest-version-or-account-refused' {
    $script:guest.build='22631';Refuses {Preflight} 'FIXED_1112_WINDOWS_GUEST_REQUIRED'
    $script:guest.build='19045';$script:guest.sid='S-1-5-21-101-202-303-9999';Refuses {Preflight} 'ORIGINAL_FAILURE_CONTEXT_OR_ACCOUNT_MISMATCH'
}
Case 'output-collision-is-preserved' {
    $path=Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01';$null=New-Item -ItemType Directory -Path $path
    $file=Join-Path $path 'KEEP';[IO.File]::WriteAllText($file,'KEEP');Refuses {Execute} 'EXISTING_DIAGNOSTIC_OUTPUT_REFUSED_NO_OVERWRITE'
    Equal ([IO.File]::ReadAllText($file)) 'KEEP';Equal $script:nativeCalls 0
}
Case 'guest-space-low-or-unknown-refuses' {
    foreach($value in @([long](1GB-1),$null,'9000000000')){$script:free=$value;Refuses {Preflight} 'GUEST_FREE_SPACE_UNKNOWN_OR_BELOW_1_GIB'};Equal $script:nativeCalls 0
}
Case 'parallel-unknown-abandoned-or-inprogress-refuse' {
    foreach($value in @('BUSY','UNKNOWN','UNKNOWN_ABANDONED')){$script:activity=$value;Refuses {Preflight} 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED'}
    Refuses {Assert-Existing1112InstallerIdle @{observed=$true;otherClientCount=1;mutexes=@('ABSENT','ABSENT');inProgress=@($false,$false)}} 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED'
    Refuses {Assert-Existing1112InstallerIdle @{observed=$true;otherClientCount=0;mutexes=@('ABSENT','ABSENT');inProgress=@($true,$false)}} 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED'
}
Case 'required-observation-errors-are-not-absence' {
    $script:registrationFailure=$true;Refuses {Preflight} 'SYNTHETIC_REGISTRATION_QUERY_FAILED'
    $script:registrationFailure=$false;$script:activityFailure=$true;Refuses {Preflight} 'SYNTHETIC_ACTIVITY_QUERY_FAILED';Equal $script:nativeCalls 0
}
Case 'no-confirmation-never-invokes-msi' {Refuses {Invoke-Existing1112Diagnostic $script:source $script:original $script:policy $script:binding $false} 'EXISTING_1112_INSTALLER_WRITES_NOT_CONFIRMED';Equal $script:nativeCalls 0}
Case 'one-fake-early-stop-copies-three-originals-and-preserves-inputs' {
    $r=@(Execute)[-1];Equal $script:nativeCalls 1;Equal $r.nativeExit 1603;Equal $r.status 'HEADER_DIAGNOSTIC_OBSERVED_NOT_INSTALL_ACCEPTANCE'
    Equal $r.observed.classification 'UNREPRODUCED_INCONCLUSIVE'
    foreach($row in $script:policy.baseline){Equal (Get-HeaderFileProof (Join-Path $env:LOCALAPPDATA ('CCB-OP1-First-1112-r01\'+$row.name))).sha256 $row.sha256}
    $copies=@(Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01\baseline') -File);Equal $copies.Count 3
}
Case 'header-rejection-is-diagnostic-not-install-success' {$script:headerResult='REJECT';$r=@(Execute)[-1];Equal $r.observed.classification 'HEADER_REJECTION_OBSERVED';Equal $r.observed.installationAccepted $false;Equal $script:nativeCalls 1}
Case 'late-parallel-activity-stops-before-msi-and-keeps-receipt' {
    $script:busyAt=2;Refuses {Execute} 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED';Equal $script:nativeCalls 0
    $r=Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01\RESULT.json') -Raw|ConvertFrom-Json
    Equal $r.installerInvoked $false;Equal $r.status 'STOP_PRESERVE_NO_RETRY'
}
Case 'unexpected-exit-and-registration-are-retained' {
    $script:nativeExit=1618;Refuses {Execute} 'PROBE_EXIT_NOT_EXPECTED'
    $r=Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01\RESULT.json') -Raw|ConvertFrom-Json
    Equal $r.nativeExit 1618;Equal @($r.after.products).Count 2
}
Case 'second-execution-is-refused-without-deleting-result' {
    $null=Execute;$path=Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01\RESULT.json';$hash=(Get-HeaderFileProof $path).sha256
    Refuses {Execute} 'EXISTING_DIAGNOSTIC_OUTPUT_REFUSED_NO_OVERWRITE';Equal $script:nativeCalls 1;Equal (Get-HeaderFileProof $path).sha256 $hash
}

# Exercise the actual host flow with fake Hyper-V, session, credential and remote
# boundaries. No real VM, PSSession, registry observer or installer is called.
function Assert-Existing1112HostPlatform {}
function Get-VM {
    [CmdletBinding()]param($Id)
    $script:vmReads++;if($script:hostQueryFailure){throw 'SYNTHETIC_HOST_QUERY_FAILED'}
    Equal ([string]$Id) '61ce90eb-cd75-4181-9eb8-4faffaa1bed2'
    return [pscustomobject]@{Id=$script:hostVmId;Name=$(if($script:hostChangeAt -eq $script:vmReads){'CHANGED'}else{$script:hostVmName});State=$script:hostVmState}
}
function Get-VMNetworkAdapter {[CmdletBinding()]param($VM);return [pscustomobject]@{SwitchName=$(if($script:networkConnected){'CONNECTED'}else{''})}}
function Get-VMHardDiskDrive {[CmdletBinding()]param($VM);return [pscustomobject]@{Path=$script:hostDisk}}
function Get-Existing1112HostFreeBytes([string]$Path){return $script:hostFree}
function Get-Credential {[CmdletBinding()]param($Message);$script:credentialCalls++;return [pscredential]::new('SYNTHETIC-USER',(ConvertTo-SecureString 'SYNTHETIC-ONLY' -AsPlainText -Force))}
function New-PSSession {
    [CmdletBinding()]param($VMId,$Credential)
    $script:sessionOpens++;$script:openedVmId=[string]$VMId
    return [pscustomobject]@{State='Opened';InstanceId=[guid]'11111111-2222-4333-8444-555555555555'}
}
function Remove-PSSession {[CmdletBinding()]param($Session);$script:sessionCloses++}
function Invoke-Existing1112GuestSession($Session,$CommonCode,$GuestCode,$Binding,$Policy,[string]$Source,[string]$Original,[bool]$Execute){
    $script:remoteCalls++;$script:usedSessionId=[string]$Session.InstanceId;Assert-Existing1112Binding $Binding
    if($script:remoteFail){throw 'SYNTHETIC_REMOTE_FAILURE'}
    return @{mode='EXISTING_1112_FAILURE_HEADER_ONLY';executionRequested=$Execute;fakeOnly=$true}
}
function Read-Existing1112FailureReceipt($Session){$script:receiptReads++;return @{found=$true;attribution='FAKE_CURRENT_SESSION_RECEIPT';result=@{status='STOP_PRESERVE_NO_RETRY'}}}
function Host-Inspect {return (Invoke-Existing1112HostFlow 'FAKE_COMMON' 'FAKE_GUEST' $script:policy $script:source $script:original $false $false)}
Case 'host-binds-exact-vmid-in-a-new-session-and-closes-it' {
    $r=Host-Inspect;Equal $script:openedVmId '61ce90eb-cd75-4181-9eb8-4faffaa1bed2';Equal $script:usedSessionId $r.binding.sessionInstanceId
    Equal $script:sessionOpens 1;Equal $script:sessionCloses 1;Equal $script:remoteCalls 1;Equal $script:nativeCalls 0
}
Case 'host-wrong-name-state-or-id-never-prompts-or-opens-session' {
    $script:hostVmName='OTHER';Refuses {Host-Inspect} 'EXACT_RUNNING_1112_VM_REQUIRED'
    $script:hostVmName='CCB-MSI-Op1-1112-1113';$script:hostVmState='Off';Refuses {Host-Inspect} 'EXACT_RUNNING_1112_VM_REQUIRED'
    $script:hostVmState='Running';$script:hostVmId='74876b21-e684-4e33-a46c-98a7a7d5dc72';Refuses {Host-Inspect} 'EXACT_RUNNING_1112_VM_REQUIRED'
    Equal $script:credentialCalls 0;Equal $script:sessionOpens 0
}
Case 'host-low-or-unknown-space-refuses-before-session' {
    $script:hostFree=[long](2GB-1);Refuses {Host-Inspect} 'HOST_ACTIVE_DISK_VOLUME_BELOW_2_GIB'
    $script:hostFree=$null;Refuses {Host-Inspect} 'HOST_ACTIVE_DISK_FREE_SPACE_UNKNOWN';Equal $script:sessionOpens 0
}
Case 'host-query-error-refuses-without-changing-vm' {$script:hostQueryFailure=$true;Refuses {Host-Inspect} 'SYNTHETIC_HOST_QUERY_FAILED';Equal $script:remoteCalls 0}
Case 'host-connected-network-is-not-automatically-disconnected' {$script:networkConnected=$true;Refuses {Host-Inspect} 'EXISTING_GUEST_NETWORK_NOT_DISCONNECTED';Equal $script:sessionOpens 0}
Case 'host-identity-change-after-open-closes-session-without-guest-call' {
    $script:hostChangeAt=2;$r=Host-Inspect;Equal $r.status 'HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY';Equal $r.error 'EXACT_RUNNING_1112_VM_REQUIRED_NO_START_OR_RESTORE'
    Equal $script:sessionCloses 1;Equal $script:remoteCalls 0
}
Case 'remote-failure-does-not-retry-and-closes-session' {$script:remoteFail=$true;$r=Host-Inspect;Equal $r.error 'SYNTHETIC_REMOTE_FAILURE';Equal $script:remoteCalls 1;Equal $script:sessionCloses 1}
Case 'host-real-execution-unconfirmed-is-refused-before-any-query' {
    Refuses {Invoke-Existing1112HostFlow 'FAKE' 'FAKE' $script:policy $script:source $script:original $true $false} 'REAL_INSTALLER_INTERNAL_WRITES_REQUIRE_SEPARATE_CONFIRMATION'
    Equal $script:vmReads 0;Equal $script:sessionOpens 0
}
Case 'remote-execute-failure-collects-once-without-a-second-probe-call' {
    $script:remoteFail=$true;$r=Invoke-Existing1112HostFlow 'FAKE' 'FAKE' $script:policy $script:source $script:original $true $true
    Equal $r.status 'HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY';Equal $script:receiptReads 1;Equal $script:remoteCalls 1;Equal $script:sessionCloses 1
}
Case 'fresh-branch-mode-still-refuses-old-1112-id' {
    Refuses {Assert-HeaderGuest $script:guest '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' $true} 'PRESERVED_FAILURE_VM_REFUSED'
}
Case 'exact-receipt-reader-returns-only-current-session-result' {
    $path=Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01';$null=New-Item -ItemType Directory -Path $path
    @{mode='EXISTING_1112_FAILURE_HEADER_ONLY';binding=$script:binding;status='SYNTHETIC_CURRENT_RESULT'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $path 'RESULT.json') -Encoding ASCII
    $r=&$readReceiptBody $script:binding.sessionInstanceId;Equal $r.found $true;Equal $r.result.status 'SYNTHETIC_CURRENT_RESULT'
    $other=&$readReceiptBody '99999999-8888-4777-8666-555555555555';Equal $other.found $false
    if(($other|ConvertTo-Json) -match 'SYNTHETIC_CURRENT_RESULT'){throw 'OLDER_RECEIPT_CONTENT_LEAK'}
}
Case 'exact-receipt-reader-refuses-oversized-file' {
    $path=Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01';$null=New-Item -ItemType Directory -Path $path
    $stream=[IO.File]::Create((Join-Path $path 'RESULT.json'));try{$stream.SetLength(1MB+1)}finally{$stream.Dispose()}
    Refuses {&$readReceiptBody $script:binding.sessionInstanceId} 'BOUNDED_FAILURE_RECEIPT_REFUSED'
}
function Get-CimInstance {
    [CmdletBinding()]param([string]$Query)
    if($Query -eq "SELECT ProcessId FROM Win32_Process WHERE Name='msiexec.exe'"){return $script:fakeMsiClients}
    if($Query -eq "SELECT ProcessId,State FROM Win32_Service WHERE Name='msiserver'"){return $script:fakeMsiService}
    throw 'UNEXPECTED_CIM_QUERY_NO_COMMAND_LINES_ALLOWED'
}
function Get-Existing1112MutexState([string]$Name){
    if($script:mutexFailure){throw 'SYNTHETIC_MUTEX_QUERY_FAILED'}
    return 'ABSENT'
}
function Get-Existing1112InProgressState {
    if($script:inProgressFailure){throw 'SYNTHETIC_REGISTRY_QUERY_FAILED'}
    return @($false,$false)
}
function Get-Existing1112ServiceControlStatus {return $script:fakeServiceStatus}
function Reset-ActivityAdapters {
    $script:fakeMsiService=@([pscustomobject]@{ProcessId=[uint32]99;State='Running'})
    $script:fakeMsiClients=@([pscustomobject]@{ProcessId=[uint32]99})
    $script:mutexFailure=$false;$script:inProgressFailure=$false
    $script:fakeServiceStatus=@{state=[uint32]4;controlsAccepted=[uint32]1;pid=[uint32]99}
}
Case 'idle-msi-service-is-not-falsely-classified-as-a-client-install' {
    Reset-ActivityAdapters;$r=&$realActivity;Equal $r.otherClientCount 0;Assert-Existing1112InstallerIdle $r
}
Case 'unexplained-msiexec-client-refuses-even-with-free-execute-mutex' {
    Reset-ActivityAdapters;$script:fakeMsiClients+=([pscustomobject]@{ProcessId=[uint32]100})
    Refuses {Assert-Existing1112InstallerIdle (&$realActivity)} 'PARALLEL_OR_UNKNOWN_MSI_ACTIVITY_REFUSED'
}
Case 'native-observer-unknown-service-or-pid-is-never-absence' {
    Reset-ActivityAdapters;$script:fakeMsiService=@();Refuses {&$realActivity} 'INSTALLER_SERVICE_OBSERVATION_UNKNOWN'
    Reset-ActivityAdapters;$script:fakeMsiService[0].ProcessId=$null;Refuses {&$realActivity} 'INSTALLER_SERVICE_STATE_UNKNOWN'
    Reset-ActivityAdapters;$script:fakeMsiClients[0].ProcessId=$null;Refuses {&$realActivity} 'INSTALLER_CLIENT_PID_UNKNOWN'
}
Case 'mutex-and-registry-query-errors-propagate-as-unknown' {
    Reset-ActivityAdapters;$script:mutexFailure=$true;Refuses {&$realActivity} 'SYNTHETIC_MUTEX_QUERY_FAILED'
    Reset-ActivityAdapters;$script:inProgressFailure=$true;Refuses {&$realActivity} 'SYNTHETIC_REGISTRY_QUERY_FAILED'
}
Case 'native-service-busy-or-identity-unknown-never-counts-as-idle' {
    Reset-ActivityAdapters;$script:fakeServiceStatus.controlsAccepted=[uint32]4;Refuses {&$realActivity} 'WINDOWS_INSTALLER_SERVICE_BUSY'
    Reset-ActivityAdapters;$script:fakeServiceStatus.pid=[uint32]100;Refuses {&$realActivity} 'INSTALLER_NATIVE_SERVICE_STATUS_UNKNOWN'
}
}finally{$env:LOCALAPPDATA=$savedLocal}
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$xml.AppendChild($suite)|Out-Null
$failures=@($rows|Where-Object {$null -ne $_.failure}).Count
$suite.SetAttribute('name','Existing1112HeaderPS51Synthetic');$suite.SetAttribute('tests',[string]$rows.Count)
$suite.SetAttribute('failures',[string]$failures);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0')
foreach($row in $rows){$case=$xml.CreateElement('testcase');$case.SetAttribute('name',$row.name);$suite.AppendChild($case)|Out-Null
    if($row.failure){$node=$xml.CreateElement('failure');$node.InnerText=$row.failure;$case.AppendChild($node)|Out-Null}}
$xml.Save((Join-Path $Evidence 'TEST-Existing1112HeaderPS51.xml'))
$result=@{tests=$rows.Count;failures=$failures;errors=0;skipped=0;powershell=[string]$PSVersionTable.PSVersion;
    actualHyperVOrPSSession=$false;actualInstallerOrNativeRegistry=$false;realGuestFilesUsed=$false;rows=@($rows.ToArray())}
$result|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$result|ConvertTo-Json -Depth 9
if($failures){exit 1}
