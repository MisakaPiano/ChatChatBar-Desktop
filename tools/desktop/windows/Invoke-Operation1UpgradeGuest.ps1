param([string]$ExpectedAIdentitySha256,[string]$ExpectedBIdentitySha256,[string]$ExpectedCommonHelperSha256,[switch]$ConfirmIsolatedGuest)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2

function Import-Op1UpgradeCommon([string]$Source,[string]$Hash){
    if($Hash -notmatch '^[a-f0-9]{64}$'){throw 'FIXED_COMMON_HELPER_HASH_REQUIRED'}
    $path=Join-Path $Source 'Invoke-Operation1FreshGuest.ps1'
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash){throw 'COMMON_HELPER_HASH_MISMATCH'}
    # Dot-sourced by the caller so the functions remain in its scope.
    return $path
}
function Get-Op1UpgradeIdentity([string]$Source,[string]$Name,[string]$Hash,[string]$Version,[string]$Product){
    if($Hash -notmatch '^[a-f0-9]{64}$'){throw 'FIXED_IDENTITY_HASH_REQUIRED'}
    $path=Assert-Op1Path (Join-Path $Source $Name)
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Hash){throw 'IDENTITY_HASH_MISMATCH'}
    $id=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($id.productName -ne 'ChatChatBarDesktopSynthetic' -or $id.version -ne $Version -or $id.productCode -ne $Product -or $id.upgradeCode -ne '{58E95FD1-709D-5813-9B5B-F518A4612BAE}' -or $id.operationContract -ne 1){throw 'FIXED_SYNTHETIC_PAIR_REQUIRED'}
    if(@($id.files).Count -ne 216 -or @($id.components).Count -ne 218 -or @($id.registry).Count -ne 220){throw 'FIXED_RESOURCE_COUNTS_REQUIRED'}
    return $id
}
function Assert-Op1UpgradePackage([string]$Source,$Id){
    if($Id.fileName -ne 'ChatChatBarDesktopSynthetic-0.1.1113.msi'){throw 'FIXED_B_PACKAGE_NAME_REQUIRED'}
    $path=Assert-Op1Path (Join-Path $Source $Id.fileName)
    $item=Get-Item -LiteralPath $path -Force
    if($item.PSIsContainer -or $item.Length -ne $Id.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Id.sha256){throw 'B_PACKAGE_HASH_MISMATCH'}
}
function Get-Op1FirstReceipt {
    $path=Assert-Op1Path (Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01\RESULT.json')
    return (Get-Content -LiteralPath $path -Raw|ConvertFrom-Json)
}
function Assert-Op1UpgradeProcessIdle {
    if(@(Get-Process -Name ChatChatBarDesktopSynthetic -ErrorAction SilentlyContinue).Count){throw 'CLOSE_SYNTHETIC_WINDOW_BEFORE_UPGRADE'}
    # This is a launcher check, not a complete Job/descendant-lock attestation.
}
function Get-Op1UpgradeOutput {return (Join-Path $env:LOCALAPPDATA 'CCB-OP1-Upgrade-1112-1113-r01')}
function New-Op1UnmanagedCanary([string]$Program){
    $path=Assert-Op1Path (Join-Path $Program 'CCB_OPERATION1_UNMANAGED_CANARY_1112_1113.txt')
    $bytes=[Text.Encoding]::ASCII.GetBytes('SYNTHETIC_NONPACKAGE_FILE_MUST_SURVIVE_1112_TO_1113')
    $stream=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try{$stream.Write($bytes,0,$bytes.Length);$stream.Flush($true)}finally{$stream.Dispose()}
    return @{path=$path;bytes=$bytes.Length;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function Assert-Op1CanaryUnchanged($Canary){
    $path=Assert-Op1Path $Canary.path;$item=Get-Item -LiteralPath $path -Force
    if($item.PSIsContainer -or $item.Length -ne $Canary.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Canary.sha256){throw 'NONPACKAGE_CANARY_CHANGED'}
}
function Assert-Op1OldOnlyRegistryAbsent($A,$B){
    $targets=@{};foreach($r in $B.registry){$targets[($r.key+'|'+$r.name).ToLowerInvariant()]=$true}
    $base=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser,[Microsoft.Win32.RegistryView]::Registry64)
    try{
        foreach($r in $A.registry){
            if($targets.ContainsKey(($r.key+'|'+$r.name).ToLowerInvariant())){continue}
            if(-not $r.key.StartsWith('Software\ChatChatBar\ChatChatBarDesktopSynthetic\',[StringComparison]::OrdinalIgnoreCase)){throw 'OLD_REGISTRY_SCOPE_REFUSED'}
            $key=$base.OpenSubKey($r.key,$false)
            if($null -ne $key){try{if($key.GetValueNames() -contains $r.name){throw 'OLD_ONLY_REGISTRY_VALUE_REMAINED'}}finally{$key.Dispose()}}
        }
    }finally{$base.Dispose()}
}
function Invoke-Op1UpgradeInstaller([string]$Source,[string]$Root,$Id){
    $args='/i "'+(Join-Path $Source $Id.fileName)+'" /qn /norestart /l*v "'+(Join-Path $Root 'upgrade-1112-1113.log')+'" REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable'
    @{exe='msiexec.exe';arguments=$args;preserveSelectedDirectory=$true;retry=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'COMMAND.json') -Encoding UTF8
    $process=Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $args -PassThru -WindowStyle Hidden
    if(-not $process.WaitForExit(600000)){throw 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'}
    $process.Refresh();return $process.ExitCode
}
function Assert-Op1UpgradeAdmissionLog([string]$Root){
    $log=Get-Content -LiteralPath (Join-Path $Root 'upgrade-1112-1113.log') -Raw
    foreach($operation in @('U','C')){
        if($log -notmatch ('CCB_OPERATION_ADMITTED operation='+$operation+' contract=1 inventory=NOT_REQUIRED rollback=ENABLED')){throw 'PARENT_UPGRADE_AND_OLD_CHILD_ADMISSION_REQUIRED'}
    }
}
function Assert-Op1ExactInstalled($State,$Id){
    if($State.state -ne '5' -or $State.feature -ne 3 -or @($State.related).Count -ne 1 -or $State.related[0] -ne $Id.productCode -or [Guid]$State.packageCode -ne [Guid]$Id.packageCode){throw 'EXACT_INSTALLED_PRODUCT_FEATURE_PACKAGE_REQUIRED'}
    if(-not [IO.File]::Exists((Assert-Op1Path $State.cache))){throw 'NATIVE_CACHE_MISSING'}
}
function Invoke-Operation1UpgradeGuest([string]$Source,[string]$AHash,[string]$BHash,[bool]$Confirmed){
    if(-not $Confirmed){throw 'CONFIRM_SAME_INDEPENDENT_GUEST_REQUIRED'}
    $guest=Get-Op1Guest
    if($guest.manufacturer -ne 'Microsoft Corporation' -or $guest.model -ne 'Virtual Machine' -or $guest.computer -in @('DD1','DL1') -or $guest.sid -in @('S-1-5-18','S-1-5-19','S-1-5-20') -or $guest.build -ne '19045' -or $guest.edition -ne 'Professional' -or $guest.bits -ne 64){throw 'WIN10_PRO_19045_INTERACTIVE_HYPERV_GUEST_REQUIRED'}
    $Source=Assert-Op1Path $Source
    $a=Get-Op1UpgradeIdentity $Source 'A_IDENTITY.json' $AHash '0.1.1112' '{57CF060B-D0A1-3993-834A-15B88CB00D5D}'
    $b=Get-Op1UpgradeIdentity $Source 'B_IDENTITY.json' $BHash '0.1.1113' '{CCBBAAD8-9942-3FC4-9765-929D03E3511F}'
    Assert-Op1UpgradePackage $Source $b
    $first=Get-Op1FirstReceipt
    if($first.status -ne 'FULL_FRESH_INSTALL_FILES_REGISTRY_COMPONENTS_OBSERVED' -or $first.installExit -ne 0 -or $first.guest.sid -ne $guest.sid){throw 'SUCCESSFUL_SAME_USER_1112_RECEIPT_REQUIRED'}
    Assert-Op1UpgradeProcessIdle
    $beforeA=Get-Op1Registration $a $guest.sid;$beforeB=Get-Op1Registration $b $guest.sid
    Assert-Op1ExactInstalled $beforeA $a
    if($null -ne $beforeB.state -or $beforeB.feature -ne -1){throw 'B_ALREADY_REGISTERED_NO_RETRY'}
    $program=Assert-Op1Path $beforeA.location
    if($program.TrimEnd('\') -ne ([string]$first.after.location).TrimEnd('\')){throw 'INSTALL_LOCATION_CHANGED_SINCE_FIRST_RECEIPT'}
    $beforeFiles=Get-Op1InstalledEvidence $program $a $guest.sid
    $root=Assert-Op1Path (Get-Op1UpgradeOutput)
    if(Test-Path -LiteralPath $root){throw 'NEW_UPGRADE_RESULT_REQUIRED_NO_RETRY'}
    Assert-Op1FreeSpace $root;$null=New-Item -ItemType Directory -Path $root
    $result=@{status='PREPARED';beforeA=$beforeA;beforeB=$beforeB;guest=$guest;installerInvoked=$false;retry=$false;installExit=$null;fullJobDrain='NOT_TESTED';vmIdentity='USER_SELECTED_COPY_NOT_PROVEN_BY_GUEST_MODEL'}
    try{
        $beforeFiles|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'BEFORE_RESOURCES.json') -Encoding UTF8
        $result.canary=New-Op1UnmanagedCanary $program
        $result.installerInvoked=$true;$result.installExit=Invoke-Op1UpgradeInstaller $Source $root $b
        $result.afterA=Get-Op1Registration $a $guest.sid;$result.afterB=Get-Op1Registration $b $guest.sid
        if($result.installExit -ne 0){throw 'NATIVE_UPGRADE_FAILED_PRESERVE_NO_RETRY'}
        Assert-Op1ExactInstalled $result.afterB $b
        if($null -ne $result.afterA.state -or $result.afterA.feature -ne -1){throw 'OLD_PRODUCT_REGISTRATION_REMAINED'}
        if((Assert-Op1Path $result.afterB.location).TrimEnd('\') -ne $program.TrimEnd('\')){throw 'UPGRADE_SELECTED_DIRECTORY_NOT_PRESERVED'}
        Get-Op1InstalledEvidence $program $b $guest.sid|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'AFTER_RESOURCES.json') -Encoding UTF8
        Assert-Op1OldOnlyRegistryAbsent $a $b
        Assert-Op1CanaryUnchanged $result.canary
        Assert-Op1UpgradePackage $Source $b
        Assert-Op1UpgradeAdmissionLog $root
        $result.status='FULL_UPGRADE_FILES_COMPONENTS_REGISTRY_AND_CANARY_OBSERVED'
        $result.remaining='CUSTOM_FIRST_INSTALL_REPAIR_MISSING_DIRECTORY_UNINSTALL_FAILURE_ROLLBACK_NOT_TESTED'
    }catch{$result.status='STOP_PRESERVE_SCENE_NO_RETRY';$result.error=$_.Exception.Message;throw}
    finally{$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'RESULT.json') -Encoding UTF8;Write-Output ('RESULT_DIRECTORY='+$root);$result|ConvertTo-Json -Depth 10|Write-Output}
}
if($MyInvocation.InvocationName -ne '.'){
    # The common script has a parameter with the same switch name. Dot-sourcing
    # binds its defaults in this scope; retain this entry's explicit confirmation.
    $upgradeConfirmed=[bool]$ConfirmIsolatedGuest
    $common=Import-Op1UpgradeCommon $PSScriptRoot $ExpectedCommonHelperSha256
    . $common
    Invoke-Operation1UpgradeGuest $PSScriptRoot $ExpectedAIdentitySha256 $ExpectedBIdentitySha256 $upgradeConfirmed
}
