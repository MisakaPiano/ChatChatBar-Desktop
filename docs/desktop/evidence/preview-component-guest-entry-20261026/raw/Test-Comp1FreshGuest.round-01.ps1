param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. (Join-Path $PSScriptRoot 'Invoke-Comp1FreshGuest.ps1')
if (Test-Path -LiteralPath $Evidence) { throw 'NEW_EVIDENCE_REQUIRED' }
$null=New-Item -ItemType Directory -Path $Evidence
$Evidence=[IO.Path]::GetFullPath($Evidence)
$script:cases=@(); $script:counter=0
$realIdentity=${function:Get-Comp1BuildIdentity}
function Reset-Fakes {
    $script:mode='success'; $script:calls=0; $script:observations=0
    $script:root=Join-Path $Evidence ('root-'+$script:counter)
}
function Get-Comp1GuestContext {
    $guest=@{computer='SYNTHETIC-GUEST'; manufacturer='Microsoft Corporation'; model='Virtual Machine'; userSid='S-1-5-21-FAKE'; guestUuid='SYNTHETIC_ONLY'}
    if($script:mode -eq 'physical'){$guest.model='Desktop'}
    if($script:mode -eq 'host'){$guest.computer='DL1'}
    if($script:mode -eq 'system'){$guest.userSid='S-1-5-18'}
    return $guest
}
function Test-Comp1FixedPair([string]$Source) { if($script:mode -eq 'bad-pair'){throw 'FIXED_INPUT_MISMATCH'};return 'FAKE_PAIR_NOT_NATIVE_VERIFICATION' }
function Get-Comp1Registration {
    $script:observations++
    if($script:mode -eq 'read-fail'){throw 'REGISTRATION_READ_FAILED'}
    $state=@{related=@();aProduct=-1;aFeature=-1;bProduct=-1;bFeature=-1}
    if($script:mode -eq 'partial'){$state.aProduct=1}
    if($script:mode -eq 'foreign'){$state.related=@('{SYNTHETIC-FOREIGN}')}
    if($script:calls -gt 0 -and $script:mode -ne 'bad-exit'){
        $state.related=@('{5EE512DA-5F61-3A3B-9D79-653D60C4A2ED}');$state.aProduct=5;$state.aFeature=3
        if($script:mode -eq 'bad-after'){$state.aFeature=1}
    }
    return $state
}
function Get-Comp1OutputRoot { return $script:root }
function Assert-Comp1Disk([string]$Root) { if($script:mode -eq 'low-disk'){throw 'TARGET_NEEDS_2_GIB_FREE'} }
function Invoke-Comp1Installer([string]$Source,[string]$Root) { $script:calls++;if($script:mode -eq 'timeout'){throw 'INSTALLER_STILL_RUNNING_DO_NOT_RETRY_OR_CLEAN'};if($script:mode -eq 'bad-exit'){return 1603};return 0 }
function Get-Comp1ProgramEvidence([string]$Program) { return @{files=@(@{path='FAKE-NO-INSTALL';sha256='SYNTHETIC'})} }
function Get-Comp1BuildIdentity([string]$Program) { if($script:mode -eq 'identity-fail'){throw 'BUILD_IDENTITY_MISMATCH'};return @{installerVersion='0.1.1102';kind='FAKE'} }
function Case([string]$Name,[scriptblock]$Body) {
    $script:counter++;Reset-Fakes
    try { &$Body;$script:cases+=@{name=$Name;pass=$true} }
    catch { $script:cases+=@{name=$Name;pass=$false;error=$_.Exception.Message} }
}
function Refuses([string]$Mode,[string]$Expected,[bool]$Confirmed=$true) {
    $script:mode=$Mode;$caught=$null
    try {$null=Invoke-Comp1FreshGuest $Evidence $Confirmed} catch {$caught=$_.Exception.Message}
    if($caught -ne $Expected -or $script:calls -ne 0 -or (Test-Path -LiteralPath $script:root)){throw ('REFUSAL_ASSERTION_'+$Mode+':'+$caught)}
}
Case 'confirmation_required_before_observation' { Refuses 'success' 'CONFIRM_NEW_ISOLATED_GUEST_REQUIRED' $false }
Case 'physical_host_refused' { Refuses 'physical' 'INTERACTIVE_HYPERV_GUEST_REQUIRED' }
Case 'dl1_host_refused' { Refuses 'host' 'INTERACTIVE_HYPERV_GUEST_REQUIRED' }
Case 'system_identity_refused' { Refuses 'system' 'INTERACTIVE_HYPERV_GUEST_REQUIRED' }
Case 'fixed_pair_mismatch_refused' { Refuses 'bad-pair' 'FIXED_INPUT_MISMATCH' }
Case 'registration_read_failure_not_absent' { Refuses 'read-fail' 'REGISTRATION_READ_FAILED' }
Case 'partial_registration_refused' { Refuses 'partial' 'FRESH_GUEST_REGISTRATION_REQUIRED_NO_RETRY' }
Case 'foreign_related_product_refused' { Refuses 'foreign' 'FRESH_GUEST_REGISTRATION_REQUIRED_NO_RETRY' }
Case 'disk_failure_before_mutation' { Refuses 'low-disk' 'TARGET_NEEDS_2_GIB_FREE' }
Case 'success_fake_boundary_and_no_second_install' {
    $null=Invoke-Comp1FreshGuest $Evidence $true
    $result=Get-Content -LiteralPath (Join-Path $script:root 'RESULT.json') -Raw | ConvertFrom-Json
    if($script:calls -ne 1 -or $result.status -ne 'FRESH_1102_INSTALLED_REGISTRATION_AND_IDENTITY_OBSERVED' -or $result.upgradeExecuted -or $result.applicationLaunched){throw 'FAKE_SUCCESS_ASSERTION'}
    $caught=$false;try {$null=Invoke-Comp1FreshGuest $Evidence $true}catch{$caught=$true}
    if(-not $caught -or $script:calls -ne 1){throw 'SECOND_INSTALL_NOT_REFUSED'}
}
foreach($failure in @(@('bad-exit','NATIVE_FRESH_INSTALL_FAILED_PRESERVE_SCENE'),@('bad-after','NATIVE_REGISTRATION_NOT_COMPLETE'),@('identity-fail','BUILD_IDENTITY_MISMATCH'),@('timeout','INSTALLER_STILL_RUNNING_DO_NOT_RETRY_OR_CLEAN'))) {
    Case ('preserves_failure_'+$failure[0]) {
        $script:mode=$failure[0];$caught=$null
        try {$null=Invoke-Comp1FreshGuest $Evidence $true}catch{$caught=$_.Exception.Message}
        $result=Get-Content -LiteralPath (Join-Path $script:root 'RESULT.json') -Raw | ConvertFrom-Json
        if($caught -ne $failure[1] -or $script:calls -ne 1 -or $result.status -ne 'STOP_PRESERVE_SCENE_NO_RETRY' -or $result.error -ne $caught){throw ('FAILURE_NOT_PRESERVED:'+ $caught)}
    }
}
# Exercise the actual JAR reader with independent, tiny synthetic resources.
foreach($wrong in @($false,$true)) {
    Case ('actual_jar_identity_wrong_commit_'+$wrong) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $app=Join-Path $script:root 'app';$null=New-Item -ItemType Directory -Path $app
        $zip=[IO.Compression.ZipFile]::Open((Join-Path $app 'tiny.jar'),[IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry=$zip.CreateEntry('desktop-build.properties');$writer=New-Object IO.StreamWriter ($entry.Open())
            try {$commit='cc813f61b833d676ae601d4f803edcd9bc1a1af2';if($wrong){$commit='development'};$writer.Write("packagingProfile=synthetic`ninstallerVersionAssigned=true`ninstallerVersion=0.1.1102`ncommit=$commit`n")}finally{$writer.Dispose()}
        }finally{$zip.Dispose()}
        $caught=$null;try{$identity=&$realIdentity $script:root}catch{$caught=$_.Exception.Message}
        if($wrong){if($caught -ne 'BUILD_IDENTITY_MISMATCH'){throw ('WRONG_COMMIT_ACCEPTED:'+ $caught)}}elseif($caught -or $identity.installerVersion -ne '0.1.1102'){throw ('JAR_READER_FAILED:'+ $caught)}
    }
}
$failed=@($script:cases | Where-Object {-not $_.pass})
@{suite='Comp1FreshGuestOrchestration';tests=$script:cases.Count;failures=$failed.Count;nativeInstallerExecuted=$false;cases=$script:cases} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$suite.SetAttribute('name','Comp1FreshGuestOrchestration');$suite.SetAttribute('tests',[string]$script:cases.Count);$suite.SetAttribute('failures',[string]$failed.Count);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0');$null=$xml.AppendChild($suite)
foreach($case in $script:cases){$node=$xml.CreateElement('testcase');$node.SetAttribute('name',$case.name);$node.SetAttribute('classname','Comp1FreshGuestOrchestration');if(-not $case.pass){$failureNode=$xml.CreateElement('failure');$failureNode.InnerText=$case.error;$null=$node.AppendChild($failureNode)};$null=$suite.AppendChild($node)}
$xml.Save((Join-Path $Evidence 'junit.xml'))
$script:cases | ConvertTo-Json -Depth 4 | Write-Output
if($failed.Count){exit 1}
exit 0
