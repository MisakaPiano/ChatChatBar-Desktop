# Host-only transport for the existing1112 diagnostic. Definitions do not open
# sessions, read Hyper-V, prompt for credentials or execute guest code.
Set-StrictMode -Version 2
$ErrorActionPreference='Stop'
function Assert-Existing1112HostPlatform {
    if($env:COMPUTERNAME -ne 'DL1' -or $PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'DL1_LOCAL_WINDOWS_POWERSHELL51_REQUIRED'}
    $principal=New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())
    if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'DL1_HOST_ADMIN_REQUIRED'}
    Import-Module Hyper-V -ErrorAction Stop
}
function Get-Existing1112HostFreeBytes([string]$Path) {
    return (New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($Path))).AvailableFreeSpace
}
function Get-Existing1112HostFacts {
    $vm=Get-VM -Id '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' -ErrorAction Stop
    if([string]$vm.Id -ne '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' -or $vm.Name -ne 'CCB-MSI-Op1-1112-1113' -or
       [string]$vm.State -ne 'Running'){throw 'EXACT_RUNNING_1112_VM_REQUIRED_NO_START_OR_RESTORE'}
    $adapters=@(Get-VMNetworkAdapter -VM $vm -ErrorAction Stop)
    if(@($adapters|Where-Object {$_.SwitchName}).Count){throw 'EXISTING_GUEST_NETWORK_NOT_DISCONNECTED_NO_SETTING_CHANGE'}
    $disks=@(Get-VMHardDiskDrive -VM $vm -ErrorAction Stop)
    if($disks.Count -eq 0){throw 'ACTIVE_VM_DISK_LOCATION_UNKNOWN'}
    $storage=@()
    foreach($disk in $disks){
        $path=Assert-HeaderPath $disk.Path -File
        $free=Get-Existing1112HostFreeBytes $path
        if($null -eq $free -or $free -isnot [long]){throw 'HOST_ACTIVE_DISK_FREE_SPACE_UNKNOWN'}
        if($free -lt 2GB){throw 'HOST_ACTIVE_DISK_VOLUME_BELOW_2_GIB'}
        $storage+=@{path=$path;volume=[IO.Path]::GetPathRoot($path);availableBytes=$free;minimumBytes=[long]2GB}
    }
    return @{vmId=[string]$vm.Id;vmName=$vm.Name;vmState=[string]$vm.State;networkDisconnected=$true;storage=@($storage)}
}
function Get-Existing1112Credential {return (Get-Credential -Message 'Existing1112 test guest Windows account; not CCB API credentials.')}
function Open-Existing1112Session($Credential) {
    if($null -eq $Credential){throw 'EXPLICIT_GUEST_OS_CREDENTIAL_REQUIRED'}
    return (New-PSSession -VMId '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' -Credential $Credential -ErrorAction Stop)
}
function Close-Existing1112Session($Session) {Remove-PSSession -Session $Session -ErrorAction Stop}
function Invoke-Existing1112GuestSession($Session,$CommonCode,$GuestCode,$Binding,$Policy,[string]$Source,[string]$Original,[bool]$Execute) {
    return (Invoke-Command -Session $Session -ErrorAction Stop -ScriptBlock {
        param($CommonCode,$GuestCode,$Binding,$Policy,$Source,$Original,$Execute)
        . ([scriptblock]::Create($CommonCode))
        . ([scriptblock]::Create($GuestCode))
        if($Execute){Invoke-Existing1112Diagnostic $Source $Original $Policy $Binding $true}
        else{Get-Existing1112Preflight $Source $Original $Policy $Binding}
    } -ArgumentList $CommonCode,$GuestCode,$Binding,$Policy,$Source,$Original,$Execute)
}
function Read-Existing1112FailureReceipt($Session) {
    return (Invoke-Command -Session $Session -ErrorAction Stop -ScriptBlock {
        param($ExpectedSessionId)
        $root=Join-Path $env:LOCALAPPDATA 'CCB-Header-Existing1112-20261027-r01'
        $path=Join-Path $root 'RESULT.json'
        if(-not (Test-Path -LiteralPath $path -ErrorAction Stop)){return @{found=$false}}
        $cursor=$path
        while($cursor){
            if((Get-Item -LiteralPath $cursor -Force -ErrorAction Stop).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'FAILURE_RECEIPT_REPARSE_REFUSED'}
            $cursor=[IO.Path]::GetDirectoryName($cursor)
        }
        $item=Get-Item -LiteralPath $path -Force -ErrorAction Stop
        if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -gt 1MB){throw 'BOUNDED_FAILURE_RECEIPT_REFUSED'}
        $result=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
        if($result.mode -ne 'EXISTING_1112_FAILURE_HEADER_ONLY' -or $result.binding.vmId -ne '61ce90eb-cd75-4181-9eb8-4faffaa1bed2' -or
           $result.binding.sessionInstanceId -ne $ExpectedSessionId){return @{found=$false;reason='NOT_CURRENT_SESSION_RECEIPT_CONTENT_NOT_RETURNED'}}
        return @{found=$true;attribution='CURRENT_PSDIRECT_SESSION_RECEIPT';result=$result}
    } -ArgumentList ([string]$Session.InstanceId))
}
function Invoke-Existing1112HostFlow($CommonCode,$GuestCode,$Policy,[string]$Source,[string]$Original,[bool]$Execute,[bool]$Confirmed) {
    if($Execute -and -not $Confirmed){throw 'REAL_INSTALLER_INTERNAL_WRITES_REQUIRE_SEPARATE_CONFIRMATION'}
    Assert-Existing1112HostPlatform
    $before=Get-Existing1112HostFacts
    $credential=$null;$session=$null;$guestCallStarted=$false
    try{
        $credential=Get-Existing1112Credential
        $session=Open-Existing1112Session $credential
        $instance=[guid]::Empty
        if($null -eq $session -or [string]$session.State -ne 'Opened' -or
           -not [guid]::TryParse([string]$session.InstanceId,[ref]$instance) -or $instance -eq [guid]::Empty){throw 'NEW_PSDIRECT_SESSION_NOT_OPENED'}
        $current=Get-Existing1112HostFacts
        if($current.vmId -ne $before.vmId -or $current.vmName -ne $before.vmName){throw 'HOST_VM_IDENTITY_CHANGED_DURING_SESSION_OPEN'}
        $binding=@{protocol='CCB_1112_HEADER_PSDIRECT_V1';host='DL1';vmId=$current.vmId;vmName=$current.vmName;
            vmState=$current.vmState;transport='New-PSSession-VMId';sessionInstanceId=[string]$session.InstanceId}
        # One newly-created, exact-ID session supplies BOTH the read-only checks
        # and (only with the future execute flag) the early-stop call. No guest-
        # typed UUID, old $s1112, network remoting or VM control action is used.
        $guestCallStarted=$true
        $guestReport=@(Invoke-Existing1112GuestSession $session $CommonCode $GuestCode $binding $Policy $Source $Original $Execute)[-1]
        return @{status=$(if($Execute){'DIAGNOSTIC_CALL_COMPLETED_CHECK_GUEST_RESULT'}else{'PREFLIGHT_OBSERVED_NOT_EXECUTED'});
            mode='EXISTING_1112_FAILURE_HEADER_ONLY';host=$current;binding=$binding;executeRequested=$Execute;
            guestReport=$guestReport;newVmCreated=$false;resetPerformed=$false;fullCopyRequired=$false}
    }catch{
        $failure=@{status='HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY';executeRequested=$Execute;error=[string]$_.Exception.Message;
            guestResultRoot='LOCALAPPDATA\CCB-Header-Existing1112-20261027-r01';receipt=$null;retrievalError=$null}
        if($Execute -and $guestCallStarted -and $session -and [string]$session.State -eq 'Opened'){
            try{$failure.receipt=Read-Existing1112FailureReceipt $session}catch{$failure.retrievalError='RECEIPT_UNAVAILABLE_PRESERVE_GUEST_FILES'}
        }
        return $failure
    }finally{
        if($session){Close-Existing1112Session $session}
        if($credential -and $credential.PSObject.Properties['Password']){$credential.Password.Dispose()}
        $credential=$null
    }
}
