param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. (Join-Path $PSScriptRoot 'Invoke-HeaderEarlyStopProbe.ps1')
if($PSVersionTable.PSVersion.Major -ne 5){throw 'REAL_WINDOWS_POWERSHELL_5_REQUIRED'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_TEST_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
$rows=New-Object Collections.Generic.List[object]
function Case([string]$Name,[scriptblock]$Body){
    $row=@{name=$Name;failure=$null}
    try{& $Body}catch{$row.failure=[string]$_}
    $rows.Add($row)
}
function Equal($A,$B){if($A -cne $B){throw ('NOT_EQUAL: '+$A+' / '+$B)}}
function Refuses([scriptblock]$Body,[string]$Prefix){
    try{& $Body|Out-Null}catch{if($_.Exception.Message -like ($Prefix+'*')){return};throw}
    throw ('DID_NOT_REFUSE: '+$Prefix)
}
function Make-Log([string]$Result='PASS'){
    $detail=if($Result -eq 'REJECT'){"CCB_HEADER_REJECT phase=SUMMARY_TEMPLATE check=SUMMARY_OPEN group=0 group_ordinal=0 ordinal=0 last_api=MsiGetSummaryInformationW last_kind=1 last_code=6 last_field=0 first_api=MsiGetSummaryInformationW first_kind=1 first_code=6 first_field=0`r`n"}else{''}
    return "Action start 12:00:00: INSTALL.`r`nAction start 12:00:00: FindRelatedProducts.`r`nAction start 12:00:00: AppSearch.`r`nAction start 12:00:00: CcbHeaderDiagnosticOnly.`r`nHello, I'm your 64bit Impersonated custom action server.`r`nCCB_HEADER_PROBE_BEGIN contract=1`r`n${detail}CCB_HEADER_PROBE_RESULT result=$Result`r`nCCB_HEADER_PROBE_STOP result=1603`r`nAction ended 12:00:00: CcbHeaderDiagnosticOnly. Return value 3.`r`nAction ended 12:00:00: INSTALL. Return value 3.`r`nMainEngineThread is returning 1603`r`n"
}
Case 'pass-is-inconclusive-not-install-acceptance' {Equal (Test-HeaderLog (Make-Log) 1603).classification 'UNREPRODUCED_INCONCLUSIVE'}
Case 'reject-with-one-detail-is-observed' {Equal (Test-HeaderLog (Make-Log 'REJECT') 1603).classification 'HEADER_REJECTION_OBSERVED'}
Case '1603-alone-is-not-diagnostic' {Refuses {Test-HeaderLog 'error 1603' 1603} 'EXPECTED_CA_CONTEXT_NOT_OBSERVED'}
Case 'other-native-exit-refuses' {foreach($code in @(0,1618,1602,3010)){Refuses {Test-HeaderLog (Make-Log) $code} 'PROBE_EXIT_NOT_EXPECTED'}}
Case 'missing-or-duplicate-marker-refuses' {
    Refuses {Test-HeaderLog ((Make-Log).Replace('CCB_HEADER_PROBE_STOP result=1603','')) 1603} 'PROBE_MARKERS_INCOMPLETE'
    Refuses {Test-HeaderLog ((Make-Log)+"CCB_HEADER_PROBE_BEGIN contract=1`r`n") 1603} 'PROBE_MARKERS_INCOMPLETE'
}
Case 'missing-real-ca-context-refuses' {Refuses {Test-HeaderLog ((Make-Log).Replace('64bit Impersonated','32bit')) 1603} 'EXPECTED_CA_CONTEXT'}
Case 'unplanned-actions-and-insurance-stop-refuse' {
    foreach($name in @('CostInitialize','FileCost','CostFinalize','InstallInitialize','RemoveExistingProducts','ProcessComponents',
        'InstallFiles','WriteRegistryValues','CreateShortcuts','RegisterProduct','InstallFinalize','CcbStopAfterHeaderProbe')){
        Refuses {Test-HeaderLog ((Make-Log)+"Action start 12:00:00: $name.`r`n") 1603} 'UNEXPECTED_ACTION_STARTED'
    }
}
Case 'result-detail-mismatch-refuses' {Refuses {Test-HeaderLog ((Make-Log 'REJECT').Replace('result=REJECT','result=PASS')) 1603} 'HEADER_DETAIL_RESULT_MISMATCH'}
Case 'incomplete-end-log-refuses' {Refuses {Test-HeaderLog ((Make-Log).Replace('MainEngineThread is returning 1603','')) 1603} 'EXPECTED_STOP_NOT_CONFIRMED'}
$fakeGuest=@{computer='SYNTHETIC-GUEST';model='Virtual Machine';manufacturer='Microsoft Corporation';build='19045';edition='Professional';bits=64;sid='S-1-5-21-111-222-333-1001'}
$newId='11111111-2222-4333-8444-555555555555'
Case 'preserved-vms-cannot-be-selected' {
    foreach($id in @('61ce90eb-cd75-4181-9eb8-4faffaa1bed2','74876b21-e684-4e33-a46c-98a7a7d5dc72','f6e8f514-8d45-42a2-bf2c-fccb6ab1c10c')){
        Refuses {Assert-HeaderGuest $fakeGuest $id $true} 'PRESERVED_FAILURE_VM_REFUSED'
    }
}
Case 'host-identity-confirmation-is-required' {Refuses {Assert-HeaderGuest $fakeGuest $newId $false} 'NEW_BRANCH_HOST_ID_CONFIRMATION_REQUIRED'}
Case 'host-and-wrong-os-refuse' {
    $guest=$fakeGuest.Clone();$guest.computer='DD1';Refuses {Assert-HeaderGuest $guest $newId $true} 'FIXED_ISOLATED'
    $guest=$fakeGuest.Clone();$guest.build='22631';Refuses {Assert-HeaderGuest $guest $newId $true} 'FIXED_ISOLATED'
}
function Absent-Registration {
    return @{products=@(@{unmanaged='ABSENT';managed='ABSENT';machine='ABSENT';feature=-1},
                       @{unmanaged='ABSENT';managed='ABSENT';machine='ABSENT';feature=-1});related=@()}
}
Case 'unknown-registration-is-not-absent' {
    $state=Absent-Registration;$state.products[1].machine='UNKNOWN';Refuses {Assert-HeaderAbsent $state} 'EXACT_PRODUCTS_NOT_ABSENT'
}
Case 'related-products-refuse' {$state=Absent-Registration;$state.related=@('{SYNTHETIC}');Refuses {Assert-HeaderAbsent $state} 'EXACT_PRODUCTS_NOT_ABSENT'}

# Invoke the real helper with ONLY guest/native/installer boundaries replaced. Tiny
# invented files/logs are not MSI packages, native registry evidence or VM results.
$source=Join-Path $Evidence 'fake-delivery';$null=New-Item -ItemType Directory -Path $source
$local=Join-Path $Evidence 'fake-local';$null=New-Item -ItemType Directory -Path $local
$baseline=Join-Path $local 'CCB-OP1-First-1112-r01';$null=New-Item -ItemType Directory -Path $baseline
foreach($name in @('COMMAND.json','RESULT.json')){[IO.File]::WriteAllText((Join-Path $baseline $name),'{}')}
[IO.File]::WriteAllText((Join-Path $baseline 'install-1112.log'),'CCB_OPERATION_DENIED stage=ACTIVE_RESOURCE_CONTRACT')
$msi=Join-Path $source 'fixture.msi';[IO.File]::WriteAllText($msi,'SYNTHETIC_NON_EXECUTABLE_BOUNDARY_ONLY')
$proof=Get-HeaderFileProof $msi
$identity=@{profile='HEADER_EARLY_STOP_ONLY_NOT_INSTALL';sourceSha256='5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79';fileName='fixture.msi';sha256=$proof.sha256}
$identity|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $source 'PROBE_IDENTITY.json') -Encoding ASCII
$files=@();foreach($name in @('fixture.msi','PROBE_IDENTITY.json')){$f=Get-HeaderFileProof (Join-Path $source $name);$files+=@{name=$name;bytes=$f.bytes;sha256=$f.sha256}}
@{files=$files}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $source 'DELIVERY_IDENTITY.json') -Encoding ASCII
$manifestHash=(Get-HeaderFileProof (Join-Path $source 'DELIVERY_IDENTITY.json')).sha256
function Get-HeaderGuest {return $fakeGuest}
function Get-HeaderRegistration($Identity,[string]$Sid){
    $script:registrationCalls++;$state=Absent-Registration
    if($script:registrationCalls -eq $script:conflictAtCall){$state.products[1].unmanaged='SYNTHETIC_PRESENT'}
    return $state
}
function Invoke-HeaderNativeProbe([string]$Msi,[string]$Log,[string]$Root){
    $script:installerCalls++
    [IO.File]::WriteAllText($Log,(Make-Log))
    return $script:fakeInstallerExit
}
$script:installerCalls=0;$script:registrationCalls=0;$script:fakeInstallerExit=1603;$script:conflictAtCall=-1
$savedLocal=$env:LOCALAPPDATA
try{
    $env:LOCALAPPDATA=$local
    Case 'manifest-failure-never-observes-or-executes' {
        Refuses {Invoke-HeaderProbeOnce $source ('0'*64) $newId $true} 'MANIFEST_HASH_MISMATCH'
        Equal $script:installerCalls 0;Equal $script:registrationCalls 0
    }
    Case 'actual-helper-one-fake-execution-collects-only-explicit-baseline' {
        $answer=@(Invoke-HeaderProbeOnce $source $manifestHash $newId $true)[-1]
        Equal $answer.status 'EARLY_STOP_DIAGNOSTIC_ONLY_NOT_INSTALL_ACCEPTANCE'
        Equal $script:installerCalls 1;Equal $script:registrationCalls 2
        $copies=@(Get-ChildItem -LiteralPath (Join-Path $local 'CCB-Header-EarlyStop-1112-r01\baseline') -File)
        Equal $copies.Count 3
        Equal (Get-HeaderFileProof $msi).sha256 $proof.sha256
    }
    Case 'second-invocation-preserves-first-receipt-with-no-retry' {
        $receipt=Join-Path $local 'CCB-Header-EarlyStop-1112-r01\RESULT.json';$before=(Get-HeaderFileProof $receipt).sha256
        Refuses {Invoke-HeaderProbeOnce $source $manifestHash $newId $true} 'ONE_SHOT_OUTPUT_EXISTS_NO_RETRY'
        Equal $script:installerCalls 1;Equal (Get-HeaderFileProof $receipt).sha256 $before
    }
    Case 'unexpected-exit-still-retains-explicit-baseline-and-registration' {
        $env:LOCALAPPDATA=Join-Path $Evidence 'fake-failed-local'
        $newBaseline=Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01'
        $null=New-Item -ItemType Directory -Path $newBaseline
        foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){
            Copy-Item -LiteralPath (Join-Path $baseline $name) -Destination (Join-Path $newBaseline $name)
        }
        $script:fakeInstallerExit=1618
        Refuses {Invoke-HeaderProbeOnce $source $manifestHash $newId $true} 'PROBE_EXIT_NOT_EXPECTED'
        $failedRoot=Join-Path $env:LOCALAPPDATA 'CCB-Header-EarlyStop-1112-r01'
        Equal @(Get-ChildItem -LiteralPath (Join-Path $failedRoot 'baseline') -File).Count 3
        $receipt=Get-Content -LiteralPath (Join-Path $failedRoot 'RESULT.json') -Raw|ConvertFrom-Json
        Equal $receipt.status 'PRESERVE_STOP_NO_RETRY';Equal $receipt.nativeExit 1618
        Equal @($receipt.after.products).Count 2;Equal $script:installerCalls 2
    }
    Case 'unexpected-registration-is-retained-as-evidence-before-refusal' {
        $env:LOCALAPPDATA=Join-Path $Evidence 'fake-registration-conflict-local'
        $newBaseline=Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01'
        $null=New-Item -ItemType Directory -Path $newBaseline
        foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){
            Copy-Item -LiteralPath (Join-Path $baseline $name) -Destination (Join-Path $newBaseline $name)
        }
        $script:fakeInstallerExit=1603;$script:conflictAtCall=$script:registrationCalls+2
        Refuses {Invoke-HeaderProbeOnce $source $manifestHash $newId $true} 'EXACT_PRODUCTS_NOT_ABSENT'
        $receipt=Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-EarlyStop-1112-r01\RESULT.json') -Raw|ConvertFrom-Json
        Equal $receipt.status 'PRESERVE_STOP_NO_RETRY'
        Equal $receipt.after.products[1].unmanaged 'SYNTHETIC_PRESENT'
        Equal $script:installerCalls 3
    }
}finally{$env:LOCALAPPDATA=$savedLocal}
$xml=New-Object Xml.XmlDocument
$suite=$xml.CreateElement('testsuite');$xml.AppendChild($suite)|Out-Null
$suite.SetAttribute('name','WindowsPowerShell51HeaderProbeFakeBoundaries');$suite.SetAttribute('tests',[string]$rows.Count)
$failures=@($rows|Where-Object {$null -ne $_.failure}).Count
$suite.SetAttribute('failures',[string]$failures);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0')
foreach($row in $rows){$case=$xml.CreateElement('testcase');$case.SetAttribute('name',$row.name);$suite.AppendChild($case)|Out-Null
    if($row.failure){$node=$xml.CreateElement('failure');$node.InnerText=$row.failure;$case.AppendChild($node)|Out-Null}}
$xml.Save((Join-Path $Evidence 'TEST-WindowsPowerShell51HeaderProbe.xml'))
$result=@{tests=$rows.Count;failures=$failures;errors=0;skipped=0;powershell=[string]$PSVersionTable.PSVersion;
    actualMsiExecuted=$false;realCaExecuted=$false;actualVmUsed=$false;fakeInstallerCalls=$script:installerCalls;rows=@($rows.ToArray())}
$result|ConvertTo-Json -Depth 9|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$result|ConvertTo-Json -Depth 9
if($failures){exit 1}
