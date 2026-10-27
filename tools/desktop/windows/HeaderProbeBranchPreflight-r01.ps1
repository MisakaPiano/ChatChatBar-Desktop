# Definitions only; read-only adapters are invoked only by the explicit entry.
Set-StrictMode -Version 2
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Operation1CopyMetadata-r02.ps1')

function Get-HeaderBranchFixed {
    return @{sourceId='74876b21-e684-4e33-a46c-98a7a7d5dc72';sourceName='CCB-MSI-Lab';
        checkpointId='f90e4a46-b08b-4f2c-a9ff-6dfa245bea75';checkpointName='Clean-Win10-19045-Before-CCB';
        targetName='CCB-MSI-HeaderProbe-1112-r01';targetRoot='C:\CCB-MSI-HeaderProbe-20261027-r01';
        probeZipSha256='31d87f4524fd4f86c94c4ffe7d03c265feb43601b761b0ded825fe8bd99e0875';
        preserved=@(@{id='61ce90eb-cd75-4181-9eb8-4faffaa1bed2';name='CCB-MSI-Op1-1112-1113'},
                    @{id='f6e8f514-8d45-42a2-bf2c-fccb6ab1c10c';name='CCB-MSI-Comp1-1102-1103'})}
}
function Assert-HeaderBranchHost {
    if($env:COMPUTERNAME -ne 'DL1'){throw 'DL1_LOCAL_HOST_ONLY'}
    if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'WINDOWS_POWERSHELL_51_REQUIRED'}
    $principal=New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())
    if(-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){throw 'DL1_ADMIN_READONLY_INSPECTION_REQUIRED'}
    Import-Module Hyper-V -ErrorAction Stop
}
function Get-HeaderBranchPathState([string]$Path) {
    # Missing is classified only after successful ancestor observations; access
    # failures/reparse paths are UNKNOWN, never available empty destinations.
    $full=[IO.Path]::GetFullPath($Path);$cursor=$full;$exists=$false
    while($cursor){
        try{
            $item=Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'REPARSE_PATH_REFUSED'}
            if($cursor -eq $full){$exists=$true}
        }catch [Management.Automation.ItemNotFoundException]{}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    return @{path=$full;exists=$exists;observed=$true}
}
function Get-HeaderCheckpointDisks($Checkpoint) {
    $rows=@();$seen=@{}
    foreach($disk in @(Get-VMHardDiskDrive -VMSnapshot $Checkpoint -ErrorAction Stop)){
        $path=$disk.Path;$chain=@{}
        while($path){
            $full=[IO.Path]::GetFullPath($path)
            if($chain.ContainsKey($full)){throw 'CHECKPOINT_DISK_PARENT_CYCLE'}
            $chain[$full]=$true
            if($seen.ContainsKey($full)){break};$seen[$full]=$true
            $null=Get-HeaderBranchPathState $full
            $file=Get-Item -LiteralPath $full -Force -ErrorAction Stop
            if($file.PSIsContainer){throw 'CHECKPOINT_DISK_FILE_REQUIRED'}
            $rows+=@{path=$full;bytes=$file.Length;lastWriteUtc=$file.LastWriteTimeUtc.ToString('o')}
            $path=(Get-VHD -Path $full -ErrorAction Stop).ParentPath
        }
    }
    return @($rows)
}
function Get-HeaderBranchFreeBytes {return (New-Object IO.DriveInfo 'C:\').AvailableFreeSpace}
function Get-HeaderBranchBaseline([string]$Directory) {
    $state=Get-HeaderBranchPathState $Directory
    if(-not $state.exists){return @{status='MISSING_NOT_READY_FOR_GUEST_PROBE';files=@();sourceAuthenticated=$false}}
    $files=@()
    foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){
        $path=Join-Path $Directory $name;$null=Get-HeaderBranchPathState $path
        $file=Get-Item -LiteralPath $path -Force -ErrorAction Stop
        if($file.PSIsContainer -or $file.Length -le 0){throw 'BASELINE_FILE_REQUIRED'}
        $files+=@{name=$name;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    return @{status='THREE_LOCAL_FILES_OBSERVED_SOURCE_NOT_AUTHENTICATED';files=@($files);sourceAuthenticated=$false}
}
function Get-HeaderBranchPreflight([string]$BaselineDirectory) {
    $fixed=Get-HeaderBranchFixed
    $answer=@{profile='HEADER_PROBE_NEW_BRANCH_PREFLIGHT_ONLY';fixed=$fixed;readOnly=$true;
        vmOperationExecuted=$false;installerExecuted=$false;cleanupExecuted=$false;creationPreconditionsObserved=$false;
        creationAuthorizedByThisReport=$false;diagnosticReady=$false;newVmId=$null;
        stateDifference='CLEAN_CHECKPOINT_NOT_1112_FAILURE_STATE';originalDiskContentHashMeasured=$false}
    $issues=New-Object Collections.Generic.List[string]
    try{
        Assert-HeaderBranchHost
        # A successful inventory read is mandatory before named/ID absence.
        $vms=@(Get-VM -ErrorAction Stop)
        $answer.inventory=@($vms|ForEach-Object {@{id=[string]$_.Id;name=$_.Name;state=[string]$_.State}})
        $source=@($vms|Where-Object {[string]$_.Id -eq $fixed.sourceId})
        if($source.Count -ne 1 -or $source[0].Name -ne $fixed.sourceName){throw 'FIXED_SOURCE_VM_UNVERIFIED'}
        if([string]$source[0].State -ne 'Off'){$issues.Add('SOURCE_VM_NOT_OFF_DO_NOT_STOP_IT_AUTOMATICALLY')}
        foreach($old in $fixed.preserved){
            $found=@($vms|Where-Object {[string]$_.Id -eq $old.id})
            if($found.Count -ne 1 -or $found[0].Name -ne $old.name){$issues.Add('PRESERVED_FAILURE_VM_ID_NAME_UNVERIFIED')}
        }
        if(@($vms|Where-Object {$_.Name -eq $fixed.targetName}).Count){$issues.Add('NEW_VM_NAME_ALREADY_EXISTS_NO_RETRY')}
        $destination=Get-HeaderBranchPathState $fixed.targetRoot;$answer.destination=$destination
        if($destination.exists){$issues.Add('NEW_TARGET_ALREADY_EXISTS_NO_OVERWRITE')}
        $snapshots=@(Get-VMSnapshot -VM $source[0] -ErrorAction Stop)
        $answer.sourceCheckpoints=@($snapshots|ForEach-Object {@{id=[string]$_.Id;name=$_.Name}})
        $clean=@($snapshots|Where-Object {$_.Name -eq $fixed.checkpointName})
        if($clean.Count -ne 1 -or [string]$clean[0].Id -ne $fixed.checkpointId){throw 'FIXED_CLEAN_CHECKPOINT_UNVERIFIED'}
        # Measure the selected checkpoint chain, not a guessed/previous current disk.
        $disks=@(Get-HeaderCheckpointDisks $clean[0]);$answer.checkpointDisks=$disks
        $diskBytes=Get-Op1DiskBytes $disks
        $memory=$source[0].MemoryStartup
        $required=Get-Op1CopyRequiredBytes $diskBytes $memory
        $available=Get-HeaderBranchFreeBytes
        if($null -eq $available -or $available.GetType().FullName -notin @('System.Int32','System.UInt32','System.Int64','System.UInt64') -or
           [decimal]$available -lt 0 -or [decimal]$available -gt [long]::MaxValue){throw 'CURRENT_FREE_SPACE_UNKNOWN'}
        $answer.capacity=@{drive='C:';diskBytes=$diskBytes;memoryStartupBytes=$memory;requiredBytes=$required;
            availableBytes=[long]$available;missingBytes=[Math]::Max([long]0,($required-[long]$available));
            estimateOnly=$true;formula='2*selected-checkpoint-chain-bytes + 2*MemoryStartup + 6GiB';
            recheckBeforeCreationAndImport=$true}
        if([long]$available -lt $required){$issues.Add('INSUFFICIENT_CURRENT_C_SPACE_NO_CLEANUP')}
        $answer.baseline=Get-HeaderBranchBaseline $BaselineDirectory
        $answer.creationPreconditionsObserved=($issues.Count -eq 0)
        $answer.status=if($issues.Count){'STOP_PRESERVE_ALL_NO_CREATE'}else{'READONLY_PRECONDITIONS_OBSERVED_AWAIT_CREATION_STEP'}
    }catch{$issues.Add([string]$_.Exception.Message);$answer.status='UNKNOWN_STOP_PRESERVE_ALL_NO_CREATE'}
    $answer.issues=@($issues.ToArray())
    return $answer
}
