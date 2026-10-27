param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop';Set-StrictMode -Version 2
if($PSVersionTable.PSVersion.Major -ne 5){throw 'PS51_REQUIRED'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
. (Join-Path $PSScriptRoot 'HeaderExisting1112Host.ps1')
function New-R1Receipt($Binding,$Policy,[bool]$Execute) {
    $sid='S-1-5-21-101-202-303-1001'
    $products=@();foreach($id in @('{882AF0EF-CDB0-466F-93D9-3AC6D4F156DF}','{57CF060B-D0A1-3993-834A-15B88CB00D5D}')){
        $products+=@{product=$id;unmanaged='ABSENT';managed='ABSENT';machine='ABSENT';feature=-1}
    }
    $bound=@{};foreach($key in $Binding.Keys){$bound[$key]=$Binding[$key]}
    $baseline=@($Policy.baseline|ForEach-Object {@{name=$_.name;bytes=$_.bytes;sha256=$_.sha256}})
    $r=@{receiptVersion=1;kind='PREFLIGHT';mode='EXISTING_1112_FAILURE_HEADER_ONLY';status='PREFLIGHT_READY_NOT_EXECUTED';binding=$bound;
        guest=@{computer='SYNTHETIC-WIN10';sid=$sid;manufacturer='Microsoft Corporation';model='Virtual Machine';build='19045';edition='Professional';bits=64};
        accountContext=@{status='OBSERVED';sid=$sid;accountName='SYNTHETIC-WIN10\TEST';elevationType=3;isElevated=$false;integritySid='S-1-16-8192';integrityRid=8192;impersonationLevel='None'};
        contextEquivalence='UNKNOWN';contextEquivalenceReason='ORIGINAL_TOKEN_AND_CA_CONTEXT_NOT_RECORDED';
        caContext=@{status='NOT_OBSERVED';bitness=$null;invocation=$null;sid=$null;elevationType=$null;isElevated=$null;integritySid=$null;equivalence='UNKNOWN';probeMsiSha256='ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558'};
        baseline=$baseline;before=@{products=$products;related=@()};after=$null;installerInvoked=$false;nativeExit=$null;observed=$null;error=$null;retry=$false;noCleanup=$true;
        resultDirectory='C:\SYNTHETIC\CCB-Header-Existing1112-20261027-r01';
        originalPackageSha256='5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79';probePackageSha256='ce90ab7550a02ffcf3802edfc535bfa81bbc4db9dc0ec995e68c6a3aff2d9558';probeManifestSha256=$Policy.probeManifestSha256}
    if($Execute){
        $r.kind='DIAGNOSTIC';$r.status='HEADER_DIAGNOSTIC_OBSERVED_NOT_INSTALL_ACCEPTANCE';$r.installerInvoked=$true;$r.nativeExit=1603;$r.after=@{products=$products;related=@()}
        $r.observed=@{header='PASS';classification='UNREPRODUCED_INCONCLUSIVE';details=@();installationAccepted=$false;rollbackProven=$false;globalNoWritesProven=$false}
        $r.caContext.status='LIMITED_LOG_ONLY';$r.caContext.bitness=64;$r.caContext.invocation='Impersonated'
    }
    return $r
}
$script:rows=New-Object Collections.Generic.List[object]
function Eq($A,$B){if($A -cne $B){throw ('NOT_EQUAL: '+$A+' / '+$B)}}
function Reset-R1 {
    $script:shape='valid';$script:closeFails=$false;$script:releaseFails=$false;$script:remoteFails=$false;$script:disconnectRemote=$false;$script:openFails=$false
    $script:closed=0;$script:released=0;$script:readbacks=0;$script:mutate=$null;$script:lastReceipt=$null;$script:recover='none';$script:lastBinding=$null
    $script:policy=@{probeManifestSha256=('a'*64);baseline=@(@{name='COMMAND.json';bytes=12;sha256=('1'*64)},@{name='RESULT.json';bytes=23;sha256=('2'*64)},@{name='install-1112.log';bytes=34;sha256=('3'*64)})}
}
function Case([string]$Name,[scriptblock]$Body){$row=@{name=$Name;failure=$null};try{Reset-R1;&$Body}catch{$row.failure=[string]$_};$script:rows.Add($row)}
function Assert-Existing1112HostPlatform {}
function Get-Existing1112HostFacts {return @{vmId='61ce90eb-cd75-4181-9eb8-4faffaa1bed2';vmName='CCB-MSI-Op1-1112-1113';vmState='Running'}}
function Get-Existing1112Credential {
    $password=New-Object PSObject
    $password|Add-Member ScriptMethod Dispose {$script:released++;if($script:releaseFails){throw 'SYNTHETIC_RELEASE_FAILURE'}}
    return [pscustomobject]@{Password=$password}
}
function Open-Existing1112Session($Credential){if($script:openFails){throw 'SYNTHETIC_OPEN_FAILURE'};return [pscustomobject]@{State='Opened';InstanceId=[guid]'11111111-2222-4333-8444-555555555555'}}
function Close-Existing1112Session($Session){$script:closed++;if($script:closeFails){throw 'SYNTHETIC_CLOSE_FAILURE'}}
function Read-Existing1112FailureReceipt($Session){
    $script:readbacks++
    if($script:recover -eq 'none'){return @{found=$false}}
    $r=New-R1Receipt $script:lastBinding $script:policy $false
    $r.kind='DIAGNOSTIC';$r.status='STOP_PRESERVE_NO_RETRY';$r.installerInvoked=$true;$r.nativeExit=1618;$r.error='SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE'
    if($script:recover -eq 'wrongSession'){$r.binding.sessionInstanceId='99999999-8888-4777-8666-555555555555'}
    return @{found=$true;attribution='CURRENT_PSDIRECT_SESSION_RECEIPT';result=$r}
}
function Invoke-Existing1112GuestSession($Session,$CommonCode,$GuestCode,$Binding,$Policy,[string]$Source,[string]$Original,[bool]$Execute){
    $script:lastBinding=$Binding
    if($script:remoteFails){if($script:disconnectRemote){$Session.State='Broken'};throw 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE'}
    $r=New-R1Receipt $Binding $Policy $Execute
    if($script:mutate){&$script:mutate $r}
    $script:lastReceipt=$r
    switch($script:shape){
        'empty' {return}
        'duplicate' {return @($r,$r)}
        'prefix' {return @('UNEXPECTED_PREFIX',$r)}
        'fakeOnly' {return @{fakeOnly=$true;mode='EXISTING_1112_FAILURE_HEADER_ONLY'}}
        'primitive' {return 42}
        'nestedArray' {return ,@($r,$r)}
        'serialized' {return [Management.Automation.PSSerializer]::Deserialize([Management.Automation.PSSerializer]::Serialize($r,20))}
        default {return $r}
    }
}
function Flow([bool]$Execute=$false){return (Invoke-Existing1112HostFlow 'FAKE' 'FAKE' $script:policy 'FAKE_SOURCE' 'FAKE_ORIGINAL' $Execute $Execute)}
function Must-Stop($Report){Eq $Report.status 'HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY'}
Case 'unique-valid-preflight-accepted' {Eq (Flow).status 'PREFLIGHT_OBSERVED_NOT_EXECUTED'}
Case 'unique-valid-diagnostic-accepted' {Eq (Flow $true).status 'DIAGNOSTIC_CALL_COMPLETED_CHECK_GUEST_RESULT'}
Case 'serialized-valid-receipt-accepted' {$script:shape='serialized';Eq (Flow).status 'PREFLIGHT_OBSERVED_NOT_EXECUTED'}
Case 'empty-primitive-or-nested-array-refused' {foreach($shape in @('empty','primitive','nestedArray')){$script:shape=$shape;Must-Stop (Flow)}}
Case 'duplicate-structured-receipts-refused' {$script:shape='duplicate';Must-Stop (Flow)}
Case 'extra-prefix-is-not-discarded' {$script:shape='prefix';Must-Stop (Flow)}
Case 'fake-only-object-refused' {$script:shape='fakeOnly';Must-Stop (Flow)}
Case 'wrong-mode-refused' {$script:mutate={param($r)$r.mode='OTHER'};Must-Stop (Flow)}
Case 'wrong-session-refused' {$script:mutate={param($r)$r.binding.sessionInstanceId='99999999-8888-4777-8666-555555555555'};Must-Stop (Flow)}
Case 'unknown-terminal-refused' {$script:mutate={param($r)$r.status='UNKNOWN'};Must-Stop (Flow)}
Case 'wrong-vm-refused' {$script:mutate={param($r)$r.binding.vmId='74876b21-e684-4e33-a46c-98a7a7d5dc72'};Must-Stop (Flow)}
Case 'missing-or-extra-field-refused' {
    $script:mutate={param($r)$r.Remove('guest')};Must-Stop (Flow)
    $script:mutate={param($r)$r.fakeOnly=$true};Must-Stop (Flow)
}
Case 'string-version-or-boolean-refused' {
    $script:mutate={param($r)$r.receiptVersion='1'};Must-Stop (Flow)
    $script:mutate={param($r)$r.installerInvoked='false'};Must-Stop (Flow)
}
Case 'array-valued-discriminators-and-identities-refused' {
    $script:mutate={param($r)$r.mode=@('EXISTING_1112_FAILURE_HEADER_ONLY')};Must-Stop (Flow)
    $script:mutate={param($r)$r.guest.build=@('19045')};Must-Stop (Flow)
    $script:mutate={param($r)$r.observed.header=@('PASS')};Must-Stop (Flow $true)
}
Case 'preflight-with-execution-fields-refused' {$script:mutate={param($r)$r.nativeExit=1603;$r.installerInvoked=$true};Must-Stop (Flow)}
Case 'wrong-kind-and-intermediate-diagnostic-status-refused' {
    $script:mutate={param($r)$r.kind='PREFLIGHT'};Must-Stop (Flow $true)
    $script:mutate={param($r)$r.status='PREPARED'};Must-Stop (Flow $true)
}
Case 'invalid-native-exit-or-header-result-refused' {
    $script:mutate={param($r)$r.nativeExit=1618};Must-Stop (Flow $true)
    $script:mutate={param($r)$r.observed.header='UNKNOWN'};Must-Stop (Flow $true)
}
Case 'classification-and-success-claims-must-agree' {
    $script:mutate={param($r)$r.observed.classification='HEADER_REJECTION_OBSERVED'};Must-Stop (Flow $true)
    $script:mutate={param($r)$r.observed.installationAccepted=$true};Must-Stop (Flow $true)
}
Case 'product-and-baseline-identities-checked-independently' {
    $script:mutate={param($r)$r.before.products[0].product='{00000000-0000-0000-0000-000000000000}'};Must-Stop (Flow)
    $script:mutate={param($r)$r.baseline[0].sha256='0'*64};Must-Stop (Flow)
}
Case 'unknown-current-context-or-unproven-equivalence-refused' {
    $script:mutate={param($r)$r.accountContext.status='UNKNOWN'};Must-Stop (Flow)
    $script:mutate={param($r)$r.contextEquivalence='EQUIVALENT'};Must-Stop (Flow)
}
Case 'contradictory-token-or-unobserved-ca-identity-refused' {
    $script:mutate={param($r)$r.accountContext.isElevated=$true};Must-Stop (Flow)
    $script:mutate={param($r)$r.caContext.sid=$r.guest.sid};Must-Stop (Flow $true)
}
Case 'valid-rejection-receipt-accepted-with-limited-ca-context' {
    $script:mutate={param($r)$r.observed.header='REJECT';$r.observed.classification='HEADER_REJECTION_OBSERVED';$r.observed.details=@('CCB_HEADER_REJECT phase=SUMMARY_TEMPLATE check=SUMMARY_OPEN group=0 group_ordinal=0 ordinal=0 last_api=MsiGetSummaryInformationW last_kind=1 last_code=6 last_field=0 first_api=MsiGetSummaryInformationW first_kind=1 first_code=6 first_field=0')}
    $r=Flow $true;Eq $r.succeeded $true;Eq $r.guestReport.caContext.equivalence 'UNKNOWN'
}
Case 'malformed-rejection-protocol-refused' {
    $script:mutate={param($r)$r.observed.header='REJECT';$r.observed.classification='HEADER_REJECTION_OBSERVED';$r.observed.details=@('CCB_HEADER_REJECT phase=SUMMARY_TEMPLATE check=SUMMARY_OPEN BAD')};Must-Stop (Flow $true)
}
Case 'primary-failure-survives-close-failure-and-password-released' {
    $script:remoteFails=$true;$script:closeFails=$true;$r=Flow $true
    Must-Stop $r;Eq $r.error 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE';Eq $script:released 1
}
Case 'success-then-close-failure-is-not-unqualified-success' {
    $script:closeFails=$true;$r=Flow $true;Eq $r.succeeded $false;Eq $r.guestReport.status 'HEADER_DIAGNOSTIC_OBSERVED_NOT_INSTALL_ACCEPTANCE';Eq $script:released 1
}
Case 'primary-failure-plus-disconnect-close-and-release-failures-retained' {
    $script:remoteFails=$true;$script:disconnectRemote=$true;$script:closeFails=$true;$script:releaseFails=$true
    $r=Flow $true;Must-Stop $r;Eq $r.error 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE';Eq $r.secondaryFaults.Count 2
    Eq $script:released 1;Eq $script:closed 1;Eq $script:readbacks 0;Eq $r.succeeded $false
}
Case 'release-failure-after-primary-failure-does-not-mask-it' {
    $script:remoteFails=$true;$script:releaseFails=$true;$r=Flow $true
    Eq $r.error 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE';Eq $r.secondaryFaults[0].stage 'CREDENTIAL_RELEASE';Eq $script:closed 1
}
Case 'open-failure-still-releases-credential' {
    $script:openFails=$true;$r=Flow;Eq $r.error 'SYNTHETIC_OPEN_FAILURE';Eq $script:released 1;Eq $script:closed 0
}
Case 'validated-failure-receipt-survives-secondary-close-failure' {
    $script:remoteFails=$true;$script:recover='valid';$script:closeFails=$true;$r=Flow $true
    Eq $r.error 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE';Eq $r.receipt.result.nativeExit 1618;Eq $r.secondaryFaults.Count 1;Eq $script:released 1
}
Case 'invalid-recovered-receipt-is-not-returned-as-trusted' {
    $script:remoteFails=$true;$script:recover='wrongSession';$r=Flow $true
    Eq $r.error 'SYNTHETIC_PRIMARY_DIAGNOSTIC_FAILURE';Eq $r.receipt $null;Eq $r.retrievalError 'RECEIPT_UNAVAILABLE_OR_INVALID_PRESERVE_GUEST_FILES'
}
$failures=@($script:rows|Where-Object {$null -ne $_.failure}).Count
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$xml.AppendChild($suite)|Out-Null
$suite.SetAttribute('name','Existing1112R1ReceiptAndCleanup');$suite.SetAttribute('tests',[string]$script:rows.Count);$suite.SetAttribute('failures',[string]$failures);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0')
foreach($row in $script:rows){$c=$xml.CreateElement('testcase');$c.SetAttribute('name',$row.name);$suite.AppendChild($c)|Out-Null;if($row.failure){$f=$xml.CreateElement('failure');$f.InnerText=$row.failure;$c.AppendChild($f)|Out-Null}}
$xml.Save((Join-Path $Evidence 'TEST-Existing1112R1.xml'))
@{tests=$script:rows.Count;failures=$failures;actualVmOrMsi=$false;rows=@($script:rows.ToArray())}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
if($failures){exit 1}
