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
        else{New-Existing1112Receipt (Get-Existing1112Preflight $Source $Original $Policy $Binding) $false}
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
function Assert-Existing1112ReceiptShape($Value,[string[]]$Fields) {
    if($null -eq $Value -or ($Value -isnot [Collections.IDictionary] -and $Value -isnot [pscustomobject])){throw 'INVALID_GUEST_RECEIPT_OBJECT_TYPE'}
    $names=if($Value -is [Collections.IDictionary]){@($Value.Keys)}else{@($Value.PSObject.Properties.Name)}
    if($names.Count -ne $Fields.Count){throw 'INVALID_GUEST_RECEIPT_FIELD_SET'}
    foreach($name in $Fields){if($names -cnotcontains $name){throw 'INVALID_GUEST_RECEIPT_FIELD_SET'}}
}
function Assert-Existing1112ReceiptList($Value) {
    if($null -eq $Value -or $Value -isnot [Collections.IList]){throw 'INVALID_GUEST_RECEIPT_ARRAY_TYPE'}
}
function Assert-Existing1112ReceiptInteger($Value) {
    if($null -eq $Value -or $Value.GetType().FullName -notin @('System.Int32','System.Int64','System.UInt32')){throw 'INVALID_GUEST_RECEIPT_INTEGER_TYPE'}
}
function Assert-Existing1112ReceiptStrings($Value,[string[]]$Fields) {
    foreach($name in $Fields){if($Value.$name -isnot [string]){throw 'INVALID_GUEST_RECEIPT_STRING_TYPE'}}
}
function Assert-Existing1112HeaderDetail([string]$Line) {
    $pattern='^CCB_HEADER_REJECT phase=(?<phase>[A-Z0-9_]{1,64}) check=[A-Z0-9_]{1,96} group=(?<group>\d+) group_ordinal=(?<groupOrdinal>\d+) ordinal=(?<ordinal>\d+) last_api=[A-Za-z0-9_]{1,96} last_kind=(?<lastKind>\d+) last_code=(?<lastCode>\d+) last_field=(?<lastField>\d+) first_api=[A-Za-z0-9_]{1,96} first_kind=(?<firstKind>\d+) first_code=(?<firstCode>\d+) first_field=(?<firstField>\d+)$'
    $match=[regex]::Match($Line,$pattern)
    if(-not $match.Success -or $match.Groups['phase'].Value -cnotin @('PRODUCT_CODE_READ','PRODUCT_CODE_MATCH','PRODUCT_NAME_READ','PRODUCT_NAME_MATCH','UPGRADE_CODE_READ','UPGRADE_CODE_MATCH','VERSION_READ','VERSION_MATCH','SUMMARY_TEMPLATE','SUMMARY_ARCHITECTURE','SUMMARY_PACKAGE','SUMMARY_PACKAGE_GUID','COMPONENTS','SEALED_TABLES')){throw 'INVALID_HEADER_DETAIL_PROTOCOL'}
    foreach($name in @('group','groupOrdinal','ordinal','lastKind','lastCode','lastField','firstKind','firstCode','firstField')){
        $number=[uint32]0;if(-not [uint32]::TryParse($match.Groups[$name].Value,[ref]$number)){throw 'INVALID_HEADER_DETAIL_INTEGER'}
        if(($name -eq 'group' -and $number -gt 4) -or ($name -in @('lastKind','firstKind') -and $number -gt 5)){throw 'INVALID_HEADER_DETAIL_ENUM'}
    }
}
function Assert-Existing1112ReceiptNativeState($Value,[bool]$AbsentRequired) {
    Assert-Existing1112ReceiptShape $Value @('products','related')
    Assert-Existing1112ReceiptList $Value.products;Assert-Existing1112ReceiptList $Value.related
    if($Value.products.Count -ne 2){throw 'INVALID_GUEST_RECEIPT_PRODUCT_SET'}
    $expected=@('{882AF0EF-CDB0-466F-93D9-3AC6D4F156DF}','{57CF060B-D0A1-3993-834A-15B88CB00D5D}')
    foreach($code in $expected){
        $items=@($Value.products|Where-Object {$_.product -ceq $code})
        if($items.Count -ne 1){throw 'INVALID_GUEST_RECEIPT_PRODUCT_SET'}
        $item=$items[0];Assert-Existing1112ReceiptShape $item @('product','unmanaged','managed','machine','feature')
        Assert-Existing1112ReceiptStrings $item @('product','unmanaged','managed','machine')
        Assert-Existing1112ReceiptInteger $item.feature
        foreach($key in @('unmanaged','managed','machine')){
            if($item.$key -isnot [string] -or $item.$key.Length -gt 64){throw 'INVALID_GUEST_RECEIPT_PRODUCT_STATE'}
            if($AbsentRequired -and $item.$key -cne 'ABSENT'){throw 'CONTRADICTORY_GUEST_RECEIPT_PRODUCT_STATE'}
        }
        if($AbsentRequired -and $item.feature -ne -1){throw 'CONTRADICTORY_GUEST_RECEIPT_FEATURE_STATE'}
    }
    if($Value.related.Count -gt 32){throw 'INVALID_GUEST_RECEIPT_RELATED_SET'}
    foreach($related in $Value.related){if($related -isnot [string] -or $related -notmatch '^\{[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$'){throw 'INVALID_GUEST_RECEIPT_RELATED_TYPE'}}
    if($AbsentRequired -and $Value.related.Count -ne 0){throw 'CONTRADICTORY_GUEST_RECEIPT_RELATED_STATE'}
}
function Assert-Existing1112GuestReceipt($Receipt,$Policy,[string]$SessionId,[bool]$Execute,[bool]$Stopped=$false) {
    Assert-Existing1112ReceiptShape $Receipt @('receiptVersion','kind','mode','status','binding','guest','accountContext','contextEquivalence','contextEquivalenceReason','caContext','baseline','before','after','installerInvoked','nativeExit','observed','error','retry','noCleanup','resultDirectory','originalPackageSha256','probePackageSha256','probeManifestSha256')
    Assert-Existing1112ReceiptStrings $Receipt @('kind','mode','status','contextEquivalence','contextEquivalenceReason','resultDirectory','originalPackageSha256','probePackageSha256','probeManifestSha256')
    Assert-Existing1112ReceiptInteger $Receipt.receiptVersion
    if($Receipt.receiptVersion -ne 1 -or $Receipt.mode -cne 'EXISTING_1112_FAILURE_HEADER_ONLY'){throw 'INVALID_GUEST_RECEIPT_VERSION_OR_MODE'}
    $expected=@{protocol='CCB_1112_HEADER_PSDIRECT_V1';host='DL1';vmId='61ce90eb-cd75-4181-9eb8-4faffaa1bed2';vmName='CCB-MSI-Op1-1112-1113';vmState='Running';transport='New-PSSession-VMId';sessionInstanceId=$SessionId}
    Assert-Existing1112ReceiptShape $Receipt.binding @($expected.Keys)
    foreach($key in $expected.Keys){if($Receipt.binding.$key -isnot [string] -or $Receipt.binding.$key -cne $expected[$key]){throw 'INVALID_GUEST_RECEIPT_VM_OR_SESSION_BINDING'}}
    Assert-Existing1112ReceiptShape $Receipt.guest @('computer','sid','manufacturer','model','build','edition','bits')
    Assert-Existing1112ReceiptStrings $Receipt.guest @('computer','sid','manufacturer','model','build','edition')
    if($Receipt.guest.computer -isnot [string] -or -not $Receipt.guest.computer -or $Receipt.guest.computer -in @('DD1','DL1') -or
       $Receipt.guest.sid -isnot [string] -or $Receipt.guest.sid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$' -or
       $Receipt.guest.manufacturer -cne 'Microsoft Corporation' -or $Receipt.guest.model -cne 'Virtual Machine' -or
       $Receipt.guest.build -cne '19045' -or $Receipt.guest.edition -cne 'Professional'){throw 'INVALID_GUEST_RECEIPT_GUEST_IDENTITY'}
    Assert-Existing1112ReceiptInteger $Receipt.guest.bits;if($Receipt.guest.bits -ne 64){throw 'INVALID_GUEST_RECEIPT_GUEST_BITNESS'}
    $account=$Receipt.accountContext
    Assert-Existing1112ReceiptShape $account @('status','sid','accountName','elevationType','isElevated','integritySid','integrityRid','impersonationLevel')
    Assert-Existing1112ReceiptStrings $account @('status','sid','accountName','integritySid','impersonationLevel')
    Assert-Existing1112ReceiptInteger $account.elevationType;Assert-Existing1112ReceiptInteger $account.integrityRid
    if($account.status -cne 'OBSERVED' -or $account.sid -cne $Receipt.guest.sid -or $account.accountName -isnot [string] -or -not $account.accountName -or
       $account.elevationType -notin @(1,2,3) -or $account.isElevated -isnot [bool] -or
       ($account.elevationType -eq 2 -and -not $account.isElevated) -or ($account.elevationType -eq 3 -and $account.isElevated) -or
       $account.integrityRid -lt 0 -or $account.integritySid -cne ('S-1-16-'+$account.integrityRid) -or
       $account.impersonationLevel -cnotin @('None','Anonymous','Identification','Impersonation','Delegation')){throw 'INVALID_GUEST_RECEIPT_ACCOUNT_CONTEXT'}
    if($Receipt.contextEquivalence -cne 'UNKNOWN' -or $Receipt.contextEquivalenceReason -cne 'ORIGINAL_TOKEN_AND_CA_CONTEXT_NOT_RECORDED'){throw 'UNPROVEN_CONTEXT_EQUIVALENCE_REFUSED'}
    if($Receipt.originalPackageSha256 -cne '5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79' -or
       $Receipt.probePackageSha256 -cne 'ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558' -or
       $Receipt.probeManifestSha256 -cne $Policy.probeManifestSha256){throw 'INVALID_GUEST_RECEIPT_PACKAGE_IDENTITY'}
    Assert-Existing1112ReceiptList $Receipt.baseline
    if($Receipt.baseline.Count -ne 3){throw 'INVALID_GUEST_RECEIPT_BASELINE_SET'}
    foreach($row in $Policy.baseline){
        $items=@($Receipt.baseline|Where-Object {$_.name -ceq $row.name})
        if($items.Count -ne 1){throw 'INVALID_GUEST_RECEIPT_BASELINE_SET'}
        Assert-Existing1112ReceiptShape $items[0] @('name','bytes','sha256');Assert-Existing1112ReceiptInteger $items[0].bytes
        Assert-Existing1112ReceiptStrings $items[0] @('name','sha256')
        if($items[0].bytes -ne $row.bytes -or $items[0].sha256 -cne $row.sha256){throw 'INVALID_GUEST_RECEIPT_BASELINE_IDENTITY'}
    }
    if($Receipt.installerInvoked -isnot [bool] -or $Receipt.retry -isnot [bool] -or $Receipt.retry -or
       $Receipt.noCleanup -isnot [bool] -or -not $Receipt.noCleanup){throw 'INVALID_GUEST_RECEIPT_BOOLEAN_FLAGS'}
    if($Receipt.resultDirectory -isnot [string] -or $Receipt.resultDirectory -notmatch '^[A-Za-z]:\\[^\r\n"]+\\CCB-Header-Existing1112-20261027-r01$' -or
       @($Receipt.resultDirectory.Split('\')|Where-Object {$_ -in @('.','..')}).Count){throw 'INVALID_GUEST_RECEIPT_RESULT_PATH'}
    Assert-Existing1112ReceiptNativeState $Receipt.before $true
    $ca=$Receipt.caContext
    Assert-Existing1112ReceiptShape $ca @('status','bitness','invocation','sid','elevationType','isElevated','integritySid','equivalence','probeMsiSha256')
    Assert-Existing1112ReceiptStrings $ca @('status','equivalence','probeMsiSha256')
    if($ca.equivalence -cne 'UNKNOWN' -or $ca.probeMsiSha256 -cne $Receipt.probePackageSha256 -or $null -ne $ca.sid -or
       $null -ne $ca.elevationType -or $null -ne $ca.isElevated -or $null -ne $ca.integritySid){throw 'UNOBSERVED_CA_TOKEN_CLAIM_REFUSED'}
    if($Stopped){
        if(-not $Execute -or $Receipt.kind -cne 'DIAGNOSTIC' -or $Receipt.status -cne 'STOP_PRESERVE_NO_RETRY' -or $Receipt.error -isnot [string] -or -not $Receipt.error){throw 'INVALID_STOPPED_GUEST_RECEIPT'}
        if($null -ne $Receipt.nativeExit){Assert-Existing1112ReceiptInteger $Receipt.nativeExit;if(-not $Receipt.installerInvoked){throw 'CONTRADICTORY_STOPPED_GUEST_RECEIPT'}}
        if($null -ne $Receipt.after){Assert-Existing1112ReceiptNativeState $Receipt.after $false}
        if($null -ne $Receipt.observed){throw 'CONTRADICTORY_STOPPED_GUEST_RECEIPT'}
        if($ca.status -ceq 'NOT_OBSERVED'){
            if($null -ne $ca.bitness -or $null -ne $ca.invocation){throw 'INVALID_STOPPED_CA_CONTEXT'}
        }elseif($ca.status -ceq 'LIMITED_LOG_ONLY'){
            Assert-Existing1112ReceiptInteger $ca.bitness
            if(-not $Receipt.installerInvoked -or $null -eq $Receipt.nativeExit -or $ca.bitness -ne 64 -or $ca.invocation -isnot [string] -or $ca.invocation -cne 'Impersonated'){throw 'INVALID_STOPPED_CA_CONTEXT'}
        }else{throw 'INVALID_STOPPED_CA_CONTEXT'}
        return
    }
    if($null -ne $Receipt.error){throw 'CONTRADICTORY_SUCCESS_GUEST_RECEIPT'}
    if(-not $Execute){
        if($Receipt.kind -cne 'PREFLIGHT' -or $Receipt.status -cne 'PREFLIGHT_READY_NOT_EXECUTED' -or $Receipt.installerInvoked -or
           $null -ne $Receipt.nativeExit -or $null -ne $Receipt.observed -or $null -ne $Receipt.after -or
           $ca.status -cne 'NOT_OBSERVED' -or $null -ne $ca.bitness -or $null -ne $ca.invocation){throw 'CONTRADICTORY_PREFLIGHT_RECEIPT'}
        return
    }
    if($Receipt.kind -cne 'DIAGNOSTIC' -or $Receipt.status -cne 'HEADER_DIAGNOSTIC_OBSERVED_NOT_INSTALL_ACCEPTANCE' -or -not $Receipt.installerInvoked){throw 'INVALID_DIAGNOSTIC_TERMINAL_RECEIPT'}
    Assert-Existing1112ReceiptInteger $Receipt.nativeExit
    if($Receipt.nativeExit -ne 1603 -or $ca.status -cne 'LIMITED_LOG_ONLY' -or $ca.bitness -ne 64 -or $ca.invocation -isnot [string] -or $ca.invocation -cne 'Impersonated'){throw 'INVALID_DIAGNOSTIC_EXIT_OR_CA_LOG_CONTEXT'}
    Assert-Existing1112ReceiptInteger $ca.bitness;Assert-Existing1112ReceiptNativeState $Receipt.after $true
    $observed=$Receipt.observed
    Assert-Existing1112ReceiptShape $observed @('header','classification','details','installationAccepted','rollbackProven','globalNoWritesProven')
    Assert-Existing1112ReceiptStrings $observed @('header','classification')
    foreach($key in @('installationAccepted','rollbackProven','globalNoWritesProven')){if($observed.$key -isnot [bool] -or $observed.$key){throw 'UNPROVEN_DIAGNOSTIC_SUCCESS_CLAIM'}}
    Assert-Existing1112ReceiptList $observed.details
    if($observed.header -ceq 'PASS'){
        if($observed.classification -cne 'UNREPRODUCED_INCONCLUSIVE' -or $observed.details.Count -ne 0){throw 'CONTRADICTORY_HEADER_PASS_RECEIPT'}
    }elseif($observed.header -ceq 'REJECT'){
        if($observed.classification -cne 'HEADER_REJECTION_OBSERVED' -or $observed.details.Count -ne 1 -or $observed.details[0] -isnot [string] -or
           $observed.details[0] -notmatch '^CCB_HEADER_REJECT phase=[A-Z0-9_]+ check=[A-Z0-9_]+ '){throw 'CONTRADICTORY_HEADER_REJECT_RECEIPT'}
        Assert-Existing1112HeaderDetail $observed.details[0]
    }else{throw 'UNKNOWN_HEADER_RESULT_REFUSED'}
}
function Release-Existing1112Credential($Credential) { $Credential.Password.Dispose() }
function Invoke-Existing1112HostFlow($CommonCode,$GuestCode,$Policy,[string]$Source,[string]$Original,[bool]$Execute,[bool]$Confirmed) {
    if($Execute -and -not $Confirmed){throw 'REAL_INSTALLER_INTERNAL_WRITES_REQUIRE_SEPARATE_CONFIRMATION'}
    Assert-Existing1112HostPlatform
    $before=Get-Existing1112HostFacts
    $credential=$null;$session=$null;$guestCallStarted=$false;$report=$null
    $secondary=New-Object Collections.Generic.List[object]
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
        $items=@(Invoke-Existing1112GuestSession $session $CommonCode $GuestCode $binding $Policy $Source $Original $Execute)
        if($items.Count -ne 1){throw 'GUEST_RECEIPT_COUNT_MUST_BE_EXACTLY_ONE'}
        $guestReport=$items[0]
        Assert-Existing1112GuestReceipt $guestReport $Policy ([string]$session.InstanceId) $Execute
        $report=@{status=$(if($Execute){'DIAGNOSTIC_CALL_COMPLETED_CHECK_GUEST_RESULT'}else{'PREFLIGHT_OBSERVED_NOT_EXECUTED'});
            mode='EXISTING_1112_FAILURE_HEADER_ONLY';host=$current;binding=$binding;executeRequested=$Execute;
            guestReport=$guestReport;newVmCreated=$false;resetPerformed=$false;fullCopyRequired=$false;primarySucceeded=$true}
    }catch{
        $report=@{status='HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY';executeRequested=$Execute;error=[string]$_.Exception.Message;primarySucceeded=$false;
            guestResultRoot='LOCALAPPDATA\CCB-Header-Existing1112-20261027-r01';receipt=$null;retrievalError=$null}
        if($Execute -and $guestCallStarted -and $session -and [string]$session.State -eq 'Opened'){
            try{
                $recovered=@(Read-Existing1112FailureReceipt $session)
                if($recovered.Count -ne 1){throw 'RECOVERED_RECEIPT_COUNT_INVALID'}
                if($recovered[0].found -isnot [bool]){throw 'RECOVERED_RECEIPT_SHAPE_INVALID'}
                if($recovered[0].found){
                    if($recovered[0].attribution -cne 'CURRENT_PSDIRECT_SESSION_RECEIPT'){throw 'RECOVERED_RECEIPT_ATTRIBUTION_INVALID'}
                    $stopped=($recovered[0].result.status -ceq 'STOP_PRESERVE_NO_RETRY')
                    Assert-Existing1112GuestReceipt $recovered[0].result $Policy ([string]$session.InstanceId) $true $stopped
                }
                $report.receipt=$recovered[0]
            }catch{$report.retrievalError='RECEIPT_UNAVAILABLE_OR_INVALID_PRESERVE_GUEST_FILES'}
        }
    }finally{
        if($session){
            try{$null=Close-Existing1112Session $session}
            catch{$secondary.Add(@{stage='SESSION_CLOSE';code='SESSION_CLOSE_FAILED';exceptionType=$_.Exception.GetType().FullName})}
        }
        try{if($credential){$null=Release-Existing1112Credential $credential}}
        catch{$secondary.Add(@{stage='CREDENTIAL_RELEASE';code='CREDENTIAL_RELEASE_FAILED';exceptionType=$_.Exception.GetType().FullName})}
        finally{$credential=$null}
    }
    if($null -eq $report){throw 'HOST_REPORT_NOT_CONSTRUCTED'}
    $report.secondaryFaults=@($secondary.ToArray())
    $report.succeeded=($report.primarySucceeded -and $secondary.Count -eq 0)
    return $report
}
