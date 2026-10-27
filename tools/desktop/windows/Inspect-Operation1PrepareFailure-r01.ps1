param([string]$OriginalScriptPath='E:\CCB-OP1-DL1-20261027-r01\Create-Operation1TestCopy.ps1')
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if($env:COMPUTERNAME -ne 'DL1'){throw 'DL1_LOCAL_READONLY_INSPECTION_ONLY'}
Import-Module Hyper-V -ErrorAction Stop
. (Join-Path $PSScriptRoot 'Operation1CopyMetadata-r02.ps1')
# Successful inventory read is required before classifying a named VM as absent.
$vms=@(Get-VM -ErrorAction Stop)
$original=@($vms|Where-Object {[string]$_.Id -eq '74876b21-e684-4e33-a46c-98a7a7d5dc72'})
if($original.Count -ne 1){throw 'ORIGINAL_VM_ID_UNAVAILABLE'}
$new=@($vms|Where-Object {$_.Name -eq 'CCB-MSI-Op1-1112-1113'})
$oldFailure=@($vms|Where-Object {$_.Name -eq 'CCB-MSI-Comp1-1102-1103'})
$clean=@(Get-VMSnapshot -VM $original[0]|Where-Object {$_.Name -eq 'Clean-Win10-19045-Before-CCB'})
$root='C:\CCB-MSI-Op1-20261027-r01'
$source=Get-Op1SourceEvidence $original[0]
$total=Get-Op1DiskBytes $source.disks
$required=Get-Op1CopyRequiredBytes $total $original[0].MemoryStartup
$free=(New-Object IO.DriveInfo 'C:\').AvailableFreeSpace
$shapes=@($source.disks|ForEach-Object {@{entryType=$_.GetType().FullName;bytesType=$_['bytes'].GetType().FullName;bytes=$_['bytes'];keys=@($_.Keys|Sort-Object)}})
$rootExists=Test-Path -LiteralPath $root
$oldHash=$null
try{if([IO.File]::Exists($OriginalScriptPath)){$oldHash=(Get-FileHash -LiteralPath $OriginalScriptPath -Algorithm SHA256).Hash.ToLowerInvariant()}}catch{$oldHash=$null}
$oldHashMatches=($oldHash -eq '69379321976a3ae6912560bed290e49544af1c1ca63ed078d8b040c2835c8869')
$safe=($oldHashMatches -and [string]$original[0].State -eq 'Off' -and $new.Count -eq 0 -and -not $rootExists -and $clean.Count -eq 1 -and [string]$clean[0].Id -eq 'f90e4a46-b08b-4f2c-a9ff-6dfa245bea75' -and $free -ge $required)
$result=@{readOnly=$true;vmOperationExecuted=$false;directoryCreated=$false;installerExecuted=$false;originalScriptSha256=$oldHash;originalScriptMatchesFrozenDelivery=$oldHashMatches;originalVmState=[string]$original[0].State;newVmCount=$new.Count;newVmStates=@($new|ForEach-Object {[string]$_.State});oldFailureVmStates=@($oldFailure|ForEach-Object {[string]$_.State});newDestinationExists=$rootExists;cleanCheckpointCount=$clean.Count;cleanCheckpointIds=@($clean|ForEach-Object {[string]$_.Id});diskEvidenceContainerType=$source.disks.GetType().FullName;diskEvidenceShapes=$shapes;diskBytes=$total;requiredBytes=$required;availableBytes=$free;missingBytes=[Math]::Max([long]0,([long]$required-[long]$free));safeContinuationPreconditionsObserved=$safe;originalDiskContentHashMeasured=$false}
$result|ConvertTo-Json -Depth 8|Write-Output
if(-not $safe){Write-Output 'STOP_PRESERVE_SCENE_OR_CAPACITY_INSUFFICIENT_NO_RETRY'}else{Write-Output 'READONLY_PRECONDITIONS_OBSERVED_USE_NEW_R02_ONLY'}
