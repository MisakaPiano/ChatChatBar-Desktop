param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'PS51_REQUIRED'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_SYNTHETIC_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
. (Join-Path $PSScriptRoot 'HeaderProbeBranchPreflight-r01.ps1')
$actualPathState=(Get-Command Get-HeaderBranchPathState).ScriptBlock
$rows=New-Object Collections.Generic.List[object]
function Case([string]$Name,[scriptblock]$Body){
    $row=@{name=$Name;failure=$null};try{Reset-Fakes;&$Body}catch{$row.failure=[string]$_};$rows.Add($row)
}
function Equal($A,$B){if($A -cne $B){throw ('NOT_EQUAL: '+$A+' / '+$B)}}
function Has-Issue($Result,[string]$Issue){if($Issue -notin $Result.issues){throw ('EXPECTED_ISSUE_MISSING_'+$Issue)}}
$child=Join-Path $Evidence 'checkpoint-child.fake';$parent=Join-Path $Evidence 'checkpoint-parent.fake'
[IO.File]::WriteAllBytes($child,[byte[]](0..16));[IO.File]::WriteAllBytes($parent,[byte[]](0..28))
$baseline=Join-Path $Evidence 'baseline';$null=New-Item -ItemType Directory -Path $baseline
foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){[IO.File]::WriteAllText((Join-Path $baseline $name),'SYNTHETIC_BASELINE_ONLY')}
function Reset-Fakes {
    $script:vmState='Off';$script:inventoryError=$false;$script:missingOld=$false;$script:wrongOldName=$false
    $script:targetVm=$false;$script:targetPath=$false;$script:checkpointId='f90e4a46-b08b-4f2c-a9ff-6dfa245bea75'
    $script:duplicateSnapshot=$false;$script:free=[long](8GB+92);$script:cycle=$false;$script:diskReadError=$false
    $script:checkpointDriveCalls=0;$script:memory=[long]1GB
}
function Assert-HeaderBranchHost {}
function Get-VM {
    [CmdletBinding()]param()
    if($script:inventoryError){throw 'SYNTHETIC_INVENTORY_UNAVAILABLE'}
    $answer=@([pscustomobject]@{Id='74876b21-e684-4e33-a46c-98a7a7d5dc72';Name='CCB-MSI-Lab';State=$script:vmState;MemoryStartup=$script:memory},
        [pscustomobject]@{Id='61ce90eb-cd75-4181-9eb8-4faffaa1bed2';Name=$(if($script:wrongOldName){'WRONG'}else{'CCB-MSI-Op1-1112-1113'});State='Running'})
    if(-not $script:missingOld){$answer+=[pscustomobject]@{Id='f6e8f514-8d45-42a2-bf2c-fccb6ab1c10c';Name='CCB-MSI-Comp1-1102-1103';State='Off'}}
    if($script:targetVm){$answer+=[pscustomobject]@{Id='SYNTHETIC';Name='CCB-MSI-HeaderProbe-1112-r01';State='Off'}}
    return $answer
}
function Get-VMSnapshot {
    [CmdletBinding()]param($VM)
    $row=[pscustomobject]@{Id=$script:checkpointId;Name='Clean-Win10-19045-Before-CCB'}
    if($script:duplicateSnapshot){return @($row,$row)};return $row
}
function Get-VMHardDiskDrive {
    [CmdletBinding()]param($VMSnapshot)
    if($null -eq $VMSnapshot){throw 'SELECTED_CHECKPOINT_REQUIRED_NOT_CURRENT_VM'}
    $script:checkpointDriveCalls++
    return @([pscustomobject]@{Path=$child},[pscustomobject]@{Path=$parent})
}
function Get-VHD {
    [CmdletBinding()]param([string]$Path)
    if($script:diskReadError){throw 'SYNTHETIC_DISK_METADATA_UNKNOWN'}
    return [pscustomobject]@{ParentPath=$(if($Path -eq $child){$parent}elseif($script:cycle){$child}else{$null})}
}
function Get-HeaderBranchPathState([string]$Path){
    if($Path -eq 'C:\CCB-MSI-HeaderProbe-20261027-r01'){return @{path=$Path;exists=$script:targetPath;observed=$true}}
    return (& $actualPathState $Path)
}
function Get-HeaderBranchFreeBytes {return $script:free}
function Probe {return (Get-HeaderBranchPreflight (Join-Path $Evidence 'missing-baseline'))}
Case 'hashtable-size-fields-and-exact-current-space' {
    $r=Probe;Equal $r.creationPreconditionsObserved $true;Equal $r.capacity.diskBytes 46
    Equal $r.capacity.requiredBytes (8GB+92);Equal $r.capacity.availableBytes (8GB+92)
    Equal $r.capacity.missingBytes 0;Equal $script:checkpointDriveCalls 1
}
Case 'one-byte-short-refuses-with-exact-deficit' {$script:free=[long](8GB+91);$r=Probe;Equal $r.creationPreconditionsObserved $false;Equal $r.capacity.missingBytes 1;Has-Issue $r 'INSUFFICIENT_CURRENT_C_SPACE_NO_CLEANUP'}
Case 'historical-free-space-is-never-used' {$script:free=[long]100;$r=Probe;Equal $r.capacity.availableBytes 100;Equal $r.capacity.missingBytes (8GB-8)}
Case 'unknown-space-never-means-enough' {foreach($bad in @($null,'49500000000',12.5,-1)){Reset-Fakes;$script:free=$bad;$r=Probe;Equal $r.creationPreconditionsObserved $false;Has-Issue $r 'CURRENT_FREE_SPACE_UNKNOWN'}}
Case 'source-must-be-off-no-automatic-stop' {$script:vmState='Running';$r=Probe;Equal $r.creationPreconditionsObserved $false;Has-Issue $r 'SOURCE_VM_NOT_OFF_DO_NOT_STOP_IT_AUTOMATICALLY'}
Case 'preserved-failure-vm-may-remain-running' {$r=Probe;Equal $r.creationPreconditionsObserved $true;Equal (@($r.inventory|Where-Object {$_.id -eq '61ce90eb-cd75-4181-9eb8-4faffaa1bed2'})[0].state) 'Running'}
Case 'missing-preserved-vm-is-not-ignored' {$script:missingOld=$true;$r=Probe;Equal $r.creationPreconditionsObserved $false;Has-Issue $r 'PRESERVED_FAILURE_VM_ID_NAME_UNVERIFIED'}
Case 'preserved-id-name-conflict-refuses' {$script:wrongOldName=$true;$r=Probe;Has-Issue $r 'PRESERVED_FAILURE_VM_ID_NAME_UNVERIFIED'}
Case 'inventory-query-error-is-unknown-not-empty' {$script:inventoryError=$true;$r=Probe;Equal $r.status 'UNKNOWN_STOP_PRESERVE_ALL_NO_CREATE';Has-Issue $r 'SYNTHETIC_INVENTORY_UNAVAILABLE'}
Case 'new-name-or-directory-collision-refuses' {
    $script:targetVm=$true;$r=Probe;Has-Issue $r 'NEW_VM_NAME_ALREADY_EXISTS_NO_RETRY'
    Reset-Fakes;$script:targetPath=$true;$r=Probe;Has-Issue $r 'NEW_TARGET_ALREADY_EXISTS_NO_OVERWRITE'
}
Case 'exact-unique-clean-checkpoint-required' {
    $script:checkpointId='WRONG';$r=Probe;Has-Issue $r 'FIXED_CLEAN_CHECKPOINT_UNVERIFIED'
    Reset-Fakes;$script:duplicateSnapshot=$true;$r=Probe;Has-Issue $r 'FIXED_CLEAN_CHECKPOINT_UNVERIFIED'
}
Case 'checkpoint-chain-deduplicates-shared-parents' {$r=Probe;Equal @($r.checkpointDisks).Count 2;Equal $r.capacity.diskBytes 46}
Case 'checkpoint-parent-cycle-refuses' {$script:cycle=$true;$r=Probe;Has-Issue $r 'CHECKPOINT_DISK_PARENT_CYCLE'}
Case 'metadata-read-error-does-not-omit-disk' {$script:diskReadError=$true;$r=Probe;Equal $r.creationPreconditionsObserved $false;Has-Issue $r 'SYNTHETIC_DISK_METADATA_UNKNOWN'}
Case 'missing-memory-cannot-create-capacity-proof' {$script:memory=$null;$r=Probe;Has-Issue $r 'COPY_CAPACITY_INPUT_UNVERIFIED'}
Case 'baseline-absent-does-not-claim-guest-ready' {$r=Probe;Equal $r.baseline.status 'MISSING_NOT_READY_FOR_GUEST_PROBE';Equal $r.diagnosticReady $false;Equal $r.stateDifference 'CLEAN_CHECKPOINT_NOT_1112_FAILURE_STATE';Equal $r.newVmId $null}
Case 'three-baseline-files-hashed-without-reading-private-content-to-output' {
    $r=Get-HeaderBranchPreflight $baseline;Equal @($r.baseline.files).Count 3
    Equal $r.baseline.sourceAuthenticated $false
    if(($r|ConvertTo-Json -Depth 12) -match 'SYNTHETIC_BASELINE_ONLY'){throw 'CONTENT_LEAK'}
}
Case 'no-vm-msi-filesystem-mutator-in-delivered-readonly-entry' {
    foreach($file in @('Inspect-HeaderProbeNewBranch-r01.ps1','HeaderProbeBranchPreflight-r01.ps1','Operation1CopyMetadata-r02.ps1')){
        $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$tokens,[ref]$errors)
        if($errors){throw 'PS51_PARSE_FAILURE'}
        $bad=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst]},$true)|Where-Object {
            $_.GetCommandName() -in @('Export-VM','Export-VMSnapshot','Import-VM','Rename-VM','Restore-VMSnapshot','Checkpoint-VM',
            'Start-VM','Stop-VM','Remove-VM','Set-VM','Disconnect-VMNetworkAdapter','New-Item','Set-Content','Out-File','Remove-Item','Start-Process')})
        if($bad.Count){throw 'MUTATOR_FOUND_IN_READONLY_DELIVERY'}
    }
}
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$xml.AppendChild($suite)|Out-Null
$failures=@($rows|Where-Object {$null -ne $_.failure}).Count
$suite.SetAttribute('name','HeaderBranchPreflightPS51Synthetic');$suite.SetAttribute('tests',[string]$rows.Count)
$suite.SetAttribute('failures',[string]$failures);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0')
foreach($row in $rows){$case=$xml.CreateElement('testcase');$case.SetAttribute('name',$row.name);$suite.AppendChild($case)|Out-Null
    if($row.failure){$node=$xml.CreateElement('failure');$node.InnerText=$row.failure;$case.AppendChild($node)|Out-Null}}
$xml.Save((Join-Path $Evidence 'TEST-HeaderBranchPreflightPS51.xml'))
$result=@{tests=$rows.Count;failures=$failures;powershell=[string]$PSVersionTable.PSVersion;actualHyperVCalled=$false;
    actualVmOrInstallerExecuted=$false;rows=@($rows.ToArray())}
$result|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$result|ConvertTo-Json -Depth 8|Write-Output
if($failures){exit 1}
