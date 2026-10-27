# r02: definitions only. Calling this library performs no VM or filesystem mutation.
function Get-Op1SourceEvidence($Vm){
    $paths=@();$seen=@{}
    foreach($disk in Get-VMHardDiskDrive -VM $Vm){
        $path=$disk.Path
        while($path){
            $full=[IO.Path]::GetFullPath($path)
            if($seen.ContainsKey($full)){break};$seen[$full]=$true
            $file=Get-Item -LiteralPath $full -Force
            if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'SOURCE_DISK_REPARSE_REFUSED'}
            $paths+=@{path=$full;bytes=$file.Length;lastWriteUtc=$file.LastWriteTimeUtc.ToString('o')}
            $path=(Get-VHD -Path $full).ParentPath
        }
    }
    return @{disks=@($paths);checkpoints=@(Get-VMSnapshot -VM $Vm|Sort-Object Id|ForEach-Object {@{id=[string]$_.Id;name=$_.Name}})}
}
function Get-Op1DiskBytes($DiskEvidence){
    $rows=@($DiskEvidence)
    if($rows.Count -eq 0 -or $null -eq $DiskEvidence){throw 'SOURCE_DISK_SIZE_UNAVAILABLE'}
    [long]$total=0
    foreach($row in $rows){
        if($null -eq $row -or $row -is [Array]){throw 'SOURCE_DISK_EVIDENCE_SHAPE_UNSUPPORTED'}
        if($row -is [Collections.IDictionary]){
            if(-not $row.Contains('bytes')){throw 'SOURCE_DISK_BYTES_MISSING'}
            $value=$row['bytes']
        }else{
            $property=$row.PSObject.Properties['bytes']
            if($null -eq $property){throw 'SOURCE_DISK_BYTES_MISSING'}
            $value=$property.Value
        }
        if($null -eq $value -or $value.GetType().FullName -notin @('System.Byte','System.SByte','System.Int16','System.UInt16','System.Int32','System.UInt32','System.Int64','System.UInt64')){throw 'SOURCE_DISK_BYTES_NOT_INTEGER'}
        if([decimal]$value -le 0 -or [decimal]$value -gt [long]::MaxValue){throw 'SOURCE_DISK_BYTES_OUT_OF_RANGE'}
        [long]$size=$value
        if($total -gt ([long]::MaxValue-$size)){throw 'SOURCE_DISK_BYTES_OVERFLOW'}
        $total+=$size
    }
    return $total
}
function Get-Op1CopyRequiredBytes([long]$DiskBytes,$MemoryStartup){
    if($DiskBytes -le 0 -or $null -eq $MemoryStartup -or $MemoryStartup.GetType().FullName -notin @('System.Int32','System.UInt32','System.Int64','System.UInt64') -or [decimal]$MemoryStartup -le 0){throw 'COPY_CAPACITY_INPUT_UNVERIFIED'}
    [decimal]$required=2*[decimal]$DiskBytes+2*[decimal]$MemoryStartup+6442450944
    if($required -gt [long]::MaxValue){throw 'COPY_CAPACITY_OVERFLOW'}
    return [long]$required
}
