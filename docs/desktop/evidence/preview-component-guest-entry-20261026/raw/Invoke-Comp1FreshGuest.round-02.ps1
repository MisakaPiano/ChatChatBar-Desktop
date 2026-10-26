param([switch]$ConfirmIsolatedGuest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

# Fixed synthetic pair only. No application launch, repair, upgrade, or cleanup.
function Assert-Comp1SafePath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -notmatch '^[A-Za-z]:\\' -or $full.Contains('"') -or $full -match '[\r\n]') { throw 'LOCAL_PATH_REQUIRED' }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'REPARSE_REFUSED' }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}
function Get-Comp1GuestContext {
    $system = Get-CimInstance Win32_ComputerSystem
    $identity = Get-CimInstance Win32_ComputerSystemProduct
    return @{computer=$env:COMPUTERNAME; manufacturer=$system.Manufacturer; model=$system.Model; guestUuid=$identity.UUID; userSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}
}
function Test-Comp1FixedPair([string]$Source) {
    $pins = @{
        'Verify-Packages.ps1'='b62b24951e7fa78011c2ce8ba21687c01d2dbc33d887eba8859faa93c474dad0'
        'IDENTITY.json'='74f4ee5ad23f467d5b72d9fd4f818ab59a65b149a91281777c4138cc0408c809'
        'ChatChatBarDesktopSynthetic-0.1.1102.msi'='af3fbb1d5f652d15d45a5072c93bf5461b47669301e217df0d5ff39cf67d424d'
        'ChatChatBarDesktopSynthetic-0.1.1103.msi'='ee186196ceb0770fb7073d3662d9996c238dcdd0018b25efe75ec8c35efdb92e'
    }
    foreach ($name in $pins.Keys) {
        $file = Assert-Comp1SafePath (Join-Path $Source $name)
        if ((Get-Item -LiteralPath $file -Force).PSIsContainer -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $pins[$name]) { throw 'FIXED_INPUT_MISMATCH' }
    }
    $proof = & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Source 'Verify-Packages.ps1') 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'FIXED_METADATA_VERIFICATION_FAILED' }
    return ($proof | Out-String)
}
function Get-Comp1Registration {
    if (-not ('Comp1GuestMsiNative' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Comp1GuestMsiNative {
 [DllImport("msi.dll", CharSet=CharSet.Unicode)] public static extern int MsiQueryProductStateW(string product);
 [DllImport("msi.dll", CharSet=CharSet.Unicode)] public static extern int MsiQueryFeatureStateW(string product, string feature);
 [DllImport("msi.dll", CharSet=CharSet.Unicode)] public static extern uint MsiEnumRelatedProductsW(string upgrade, uint reserved, uint index, StringBuilder product);
}
'@
    }
    $related = @()
    for ($i=0; $i -lt 32; $i++) {
        $buffer = New-Object Text.StringBuilder 39
        $result = [Comp1GuestMsiNative]::MsiEnumRelatedProductsW('{58E95FD1-709D-5813-9B5B-F518A4612BAE}',0,$i,$buffer)
        if ($result -eq 259) { break }
        if ($result -ne 0) { throw 'REGISTRATION_READ_FAILED' }
        $related += $buffer.ToString()
    }
    if ($i -eq 32) { throw 'REGISTRATION_LIMIT' }
    $a='{5EE512DA-5F61-3A3B-9D79-653D60C4A2ED}'; $b='{0E03CDEA-882E-3F84-9B02-3D09E99C5984}'
    return @{related=@($related); aProduct=[Comp1GuestMsiNative]::MsiQueryProductStateW($a); aFeature=[Comp1GuestMsiNative]::MsiQueryFeatureStateW($a,'DefaultFeature'); bProduct=[Comp1GuestMsiNative]::MsiQueryProductStateW($b); bFeature=[Comp1GuestMsiNative]::MsiQueryFeatureStateW($b,'DefaultFeature')}
}
function Get-Comp1OutputRoot { return (Join-Path $env:LOCALAPPDATA 'CCB-Comp1-Fresh-1102-r01') }
function Assert-Comp1Disk([string]$Root) {
    if ((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($Root))).AvailableFreeSpace -lt 2GB) { throw 'TARGET_NEEDS_2_GIB_FREE' }
}
function Invoke-Comp1Installer([string]$Source,[string]$Root) {
    $installer = Join-Path $Source 'ChatChatBarDesktopSynthetic-0.1.1102.msi'
    $arguments = '/i "'+$installer+'" /qn /norestart /l*v "'+(Join-Path $Root 'install-1102.log')+'" INSTALLDIR="'+(Join-Path $Root 'program')+'" REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable'
    @{exe="$env:SystemRoot\System32\msiexec.exe"; arguments=$arguments} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Root 'COMMAND.json') -Encoding UTF8
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -PassThru
    if (-not $process.WaitForExit(300000)) { throw 'INSTALLER_STILL_RUNNING_DO_NOT_RETRY_OR_CLEAN' }
    $process.Refresh()
    return $process.ExitCode
}
function Get-Comp1ProgramEvidence([string]$Program) {
    $files = @()
    if (Test-Path -LiteralPath $Program) {
        # Walk one level at a time: reject links before descending into them.
        $pending = New-Object 'Collections.Generic.Queue[string]'
        $pending.Enqueue((Assert-Comp1SafePath $Program))
        while ($pending.Count) {
            $directory = $pending.Dequeue()
            foreach ($file in Get-ChildItem -LiteralPath $directory -Force) {
                $null = Assert-Comp1SafePath $file.FullName
                if ($file.PSIsContainer) { $pending.Enqueue($file.FullName) }
                else { $files += @{path=$file.FullName.Substring($Program.Length+1); bytes=$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()} }
                if ($files.Count -gt 4096) { throw 'PROGRAM_FILE_LIMIT' }
            }
        }
    }
    return @{files=@($files)}
}
function Get-Comp1BuildIdentity([string]$Program) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $identities = @()
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $Program 'app') -Filter '*.jar' -File) {
        $null=Assert-Comp1SafePath $jar.FullName
        $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $entries = @($zip.Entries | Where-Object FullName -eq 'desktop-build.properties')
            foreach ($entry in $entries) {
                if ($entry.Length -gt 4096) { throw 'BUILD_IDENTITY_LIMIT' }
                $reader = New-Object IO.StreamReader ($entry.Open())
                try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
                $values=@{}
                foreach ($line in $text -split '\r?\n') {
                    if (-not $line) { continue }
                    $pair=$line.Split(@('='),2)
                    if ($pair.Count -ne 2 -or $values.ContainsKey($pair[0])) { throw 'BUILD_IDENTITY_INVALID' }
                    $values[$pair[0]]=$pair[1]
                }
                $identities += $values
            }
        } finally { $zip.Dispose() }
    }
    if ($identities.Count -ne 1) { throw 'BUILD_IDENTITY_COUNT' }
    $identity=$identities[0]
    if ($identity.packagingProfile -ne 'synthetic' -or $identity.installerVersionAssigned -ne 'true' -or $identity.installerVersion -ne '0.1.1102' -or $identity.commit -ne 'cc813f61b833d676ae601d4f803edcd9bc1a1af2') { throw 'BUILD_IDENTITY_MISMATCH' }
    return $identity
}
function Invoke-Comp1FreshGuest([string]$Source,[bool]$Confirmed) {
    if (-not $Confirmed) { throw 'CONFIRM_NEW_ISOLATED_GUEST_REQUIRED' }
    $guest=Get-Comp1GuestContext
    if ($guest.manufacturer -ne 'Microsoft Corporation' -or $guest.model -ne 'Virtual Machine' -or $guest.computer -in @('DD1','DL1') -or $guest.userSid -in @('S-1-5-18','S-1-5-19','S-1-5-20')) { throw 'INTERACTIVE_HYPERV_GUEST_REQUIRED' }
    $Source=Assert-Comp1SafePath $Source
    $pairProof=Test-Comp1FixedPair $Source
    $before=Get-Comp1Registration
    if ($before.related.Count -ne 0 -or $before.aProduct -ne -1 -or $before.bProduct -ne -1 -or $before.aFeature -ne -1 -or $before.bFeature -ne -1) { throw 'FRESH_GUEST_REGISTRATION_REQUIRED_NO_RETRY' }
    $root=Assert-Comp1SafePath (Get-Comp1OutputRoot)
    if (Test-Path -LiteralPath $root) { throw 'FRESH_OUTPUT_REQUIRED_NO_RETRY' }
    Assert-Comp1Disk $root
    $null=New-Item -ItemType Directory -Path $root
    $result=@{status='PREPARED'; installerInvoked=$false; upgradeExecuted=$false; applicationLaunched=$false; guest=$guest; expectedVmSelection='USER_CONFIRMED_NEW_COPY_ONLY_NOT_OS_VM_ID_PROOF'; before=$before; exitCode=$null}
    try {
        $pairProof | Set-Content -LiteralPath (Join-Path $root 'PAIR_VERIFICATION.txt') -Encoding UTF8
        $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'PREPARED.json') -Encoding UTF8
        $result.installerInvoked=$true
        $result.exitCode=Invoke-Comp1Installer $Source $root
        $result.after=Get-Comp1Registration
        Get-Comp1ProgramEvidence (Join-Path $root 'program') | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'PROGRAM_FILES.json') -Encoding UTF8
        if ($result.exitCode -ne 0) { throw 'NATIVE_FRESH_INSTALL_FAILED_PRESERVE_SCENE' }
        if ($result.after.related.Count -ne 1 -or $result.after.related[0] -ne '{5EE512DA-5F61-3A3B-9D79-653D60C4A2ED}' -or $result.after.aProduct -ne 5 -or $result.after.aFeature -ne 3 -or $result.after.bProduct -ne -1) { throw 'NATIVE_REGISTRATION_NOT_COMPLETE' }
        $result.build=Get-Comp1BuildIdentity (Join-Path $root 'program')
        $result.status='FRESH_1102_INSTALLED_REGISTRATION_AND_IDENTITY_OBSERVED'
    } catch {
        $result.status='STOP_PRESERVE_SCENE_NO_RETRY'
        $result.error=$_.Exception.Message
        throw
    } finally {
        $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'RESULT.json') -Encoding UTF8
        Write-Output ('RESULT_DIRECTORY='+$root)
        $result | ConvertTo-Json -Depth 8 | Write-Output
    }
}
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-Comp1FreshGuest -Source $PSScriptRoot -Confirmed ([bool]$ConfirmIsolatedGuest)
}
