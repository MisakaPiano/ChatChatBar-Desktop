param([switch]$CreateCopy)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if(-not $CreateCopy){throw 'EXPLICIT_CREATE_NEW_COPY_REQUIRED'}
if($env:COMPUTERNAME -ne 'DL1'){throw 'DL1_LOCAL_HOST_ONLY_NOT_DD1_OR_GUEST'}
$principal=New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())
if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'RUN_IN_DL1_ADMIN_POWERSHELL'}
Import-Module Hyper-V -ErrorAction Stop
$old=Get-VM -Id '74876b21-e684-4e33-a46c-98a7a7d5dc72'
if($old.Name -ne 'CCB-MSI-Lab' -or [string]$old.State -ne 'Off'){throw 'ORIGINAL_LAB_MUST_REMAIN_OFF'}
$clean=@(Get-VMSnapshot -VM $old|Where-Object {$_.Name -eq 'Clean-Win10-19045-Before-CCB'})
if($clean.Count -ne 1 -or [string]$clean[0].Id -ne 'f90e4a46-b08b-4f2c-a9ff-6dfa245bea75'){throw 'FIXED_CLEAN_CHECKPOINT_NOT_UNIQUELY_PRESENT'}
$name='CCB-MSI-Op1-1112-1113'
if(Get-VM -Name $name -ErrorAction SilentlyContinue){throw 'NEW_VM_ALREADY_EXISTS_NO_RETRY'}
$root='C:\CCB-MSI-Op1-20261027-r01'
if(Test-Path -LiteralPath $root){throw 'NEW_DESTINATION_ALREADY_EXISTS_NO_OVERWRITE'}
if((Get-Item -LiteralPath 'C:\').Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'DESTINATION_ROOT_REPARSE_REFUSED'}
# r02: metadata shape and checked integer capacity; original delivery preserved.
. (Join-Path $PSScriptRoot 'Operation1CopyMetadata-r02.ps1')
$before=Get-Op1SourceEvidence $old
$diskBytes=Get-Op1DiskBytes $before.disks
$required=Get-Op1CopyRequiredBytes $diskBytes $old.MemoryStartup
$free=(New-Object IO.DriveInfo 'C:\').AvailableFreeSpace
Write-Output ('NEW_COPY_REQUIRED_BYTES='+$required+' AVAILABLE_BYTES='+$free+' MISSING_BYTES='+[Math]::Max([long]0,([long]$required-[long]$free)))
if($free -lt $required){throw 'INSUFFICIENT_C_SPACE_ORIGINAL_PRESERVED'}
$null=New-Item -ItemType Directory -Path $root
$result=@{status='STARTED';sourceVmId=[string]$old.Id;cleanCheckpointId=[string]$clean[0].Id;before=$before;originalDiskContentHashMeasured=$false;newVmName=$name;newVmStarted=$false;installerExecuted=$false;cleanupExecuted=$false}
try{
    $export=Join-Path $root 'retained-export'
    Export-VMSnapshot -VMSnapshot $clean[0] -Path $export
    $config=@(Get-ChildItem -LiteralPath (Join-Path $export 'CCB-MSI-Lab\Virtual Machines') -Filter '*.vmcx' -File)
    if($config.Count -ne 1){throw 'EXPORTED_CONFIG_NOT_UNIQUE_PRESERVE_EXPORT'}
    $vm=Import-VM -Path $config[0].FullName -Copy -GenerateNewId -VirtualMachinePath (Join-Path $root 'vm') -VhdDestinationPath (Join-Path $root 'disks') -SnapshotFilePath (Join-Path $root 'checkpoints') -SmartPagingFilePath (Join-Path $root 'paging')
    Rename-VM -VM $vm -NewName $name
    # Only the newly created test VM is disconnected. Original VM/host networking is unchanged.
    Get-VMNetworkAdapter -VM $vm|Disconnect-VMNetworkAdapter
    $result.newVmId=[string]$vm.Id;$result.newState=[string](Get-VM -Id $vm.Id).State
    $after=Get-Op1SourceEvidence (Get-VM -Id $old.Id)
    $result.after=$after
    if(($before|ConvertTo-Json -Depth 8 -Compress) -ne ($after|ConvertTo-Json -Depth 8 -Compress) -or [string](Get-VM -Id $old.Id).State -ne 'Off'){throw 'ORIGINAL_METADATA_CHANGED_STOP_NO_CLEANUP'}
    $result.originalDiskLengthWriteTimeAndCheckpointsUnchanged=$true
    $result.status='INDEPENDENT_CLEAN_COPY_READY_INACTIVE_NETWORK_DISCONNECTED'
}catch{$result.status='STOP_PRESERVE_ALL_NO_RETRY';$result.error=$_.Exception.Message;throw}
finally{$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'COPY_RESULT.json') -Encoding UTF8;Write-Output ('RESULT_DIRECTORY='+$root);$result|ConvertTo-Json -Depth 10|Write-Output}
