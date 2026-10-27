# Test-only helpers. Dot-sourcing defines functions; it never invokes Windows Installer.
Set-StrictMode -Version 2
$ErrorActionPreference='Stop'

function Assert-HeaderPath([string]$Path,[switch]$File) {
    $full=[IO.Path]::GetFullPath($Path)
    if($full -notmatch '^[A-Za-z]:\\' -or $full -match '["\r\n]'){throw 'LOCAL_PATH_REQUIRED'}
    $cursor=$full
    while($cursor){
        $item=Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue -ErrorVariable itemError
        if($itemError -and -not ($itemError[0].Exception -is [Management.Automation.ItemNotFoundException])){throw 'PATH_OBSERVATION_UNKNOWN'}
        if($item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'REPARSE_REFUSED'}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    if($File){
        $item=Get-Item -LiteralPath $full -Force -ErrorAction Stop
        if($item.PSIsContainer){throw 'REGULAR_FILE_REQUIRED'}
    }
    return $full
}
function Get-HeaderFileProof([string]$Path) {
    $full=Assert-HeaderPath $Path -File
    $item=Get-Item -LiteralPath $full -Force
    return @{bytes=$item.Length;sha256=(Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()}
}
function Get-HeaderGuest {
    $system=Get-CimInstance Win32_ComputerSystem
    $os=Get-CimInstance Win32_OperatingSystem
    $edition=(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').EditionID
    return @{computer=$env:COMPUTERNAME;model=$system.Model;manufacturer=$system.Manufacturer;
        build=[string]$os.BuildNumber;edition=$edition;bits=[IntPtr]::Size*8;
        sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value}
}
function Assert-HeaderGuest($Guest,[string]$HostConfirmedVmId,[bool]$Confirmed) {
    $id=[guid]::Empty
    if(-not $Confirmed -or -not [guid]::TryParse($HostConfirmedVmId,[ref]$id) -or $id -eq [guid]::Empty){throw 'NEW_BRANCH_HOST_ID_CONFIRMATION_REQUIRED'}
    if($id.ToString() -in @('61ce90eb-cd75-4181-9eb8-4faffaa1bed2','74876b21-e684-4e33-a46c-98a7a7d5dc72','f6e8f514-8d45-42a2-bf2c-fccb6ab1c10c')){throw 'PRESERVED_FAILURE_VM_REFUSED'}
    if($Guest.computer -in @('DD1','DL1') -or $Guest.manufacturer -ne 'Microsoft Corporation' -or
       $Guest.model -ne 'Virtual Machine' -or $Guest.bits -ne 64 -or $Guest.build -ne '19045' -or
       $Guest.edition -ne 'Professional' -or $Guest.sid -notmatch '^S-1-5-21-\d+-\d+-\d+-\d+$'){throw 'FIXED_ISOLATED_WIN10_GUEST_REQUIRED'}
    # This is not an OS-authenticated VM ID. Host-side exact-ID checking remains required.
}
function Initialize-HeaderNative {
    if('HeaderProbeNative' -as [type]){return}
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class HeaderProbeNative {
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiGetProductInfoExW(string p,string sid,uint c,string n,StringBuilder b,ref uint z);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiEnumRelatedProductsW(string p,uint r,uint i,StringBuilder b);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern int MsiQueryFeatureStateW(string p,string f);
 public static string State(string p,string sid,uint c) {
  if(c==4&&sid!=null)throw new InvalidOperationException("MACHINE_NATIVE_NULL_SID_REQUIRED");
  uint z=256;var b=new StringBuilder((int)z);uint rc=MsiGetProductInfoExW(p,sid,c,"State",b,ref z);
  if(rc==1605)return "ABSENT";
  if(rc!=0)throw new InvalidOperationException("EXACT_PRODUCT_QUERY_UNKNOWN product="+p+" context="+c+" sidKind="+(sid==null?"NULL":sid.Length==0?"EMPTY":"EXPLICIT")+" rc="+rc);
  return b.ToString();
 }
 public static string MachineState(string p) {return State(p,null,4);}
}
'@
}
function Get-HeaderRegistration($Identity,[string]$Sid) {
    Initialize-HeaderNative
    $states=@()
    foreach($product in @($Identity.productCode,$Identity.sourceProductCode)){
        $states+=@{product=$product;unmanaged=[HeaderProbeNative]::State($product,$Sid,2);
            managed=[HeaderProbeNative]::State($product,$Sid,1);machine=[HeaderProbeNative]::MachineState($product);
            feature=[HeaderProbeNative]::MsiQueryFeatureStateW($product,'DefaultFeature')}
    }
    $related=@()
    for($i=0;$i -lt 32;$i++){
        $b=New-Object Text.StringBuilder 39
        $rc=[HeaderProbeNative]::MsiEnumRelatedProductsW($Identity.upgradeCode,0,$i,$b)
        if($rc -eq 259){break};if($rc -ne 0){throw 'RELATED_QUERY_UNKNOWN'}
        $related+=$b.ToString()
    }
    if($i -eq 32){throw 'RELATED_LIMIT_UNKNOWN'}
    return @{products=@($states);related=@($related)}
}
function Assert-HeaderAbsent($Registration) {
    if(@($Registration.products).Count -ne 2 -or @($Registration.related).Count -ne 0){throw 'EXACT_PRODUCTS_NOT_ABSENT'}
    foreach($row in $Registration.products){
        if($row.unmanaged -ne 'ABSENT' -or $row.managed -ne 'ABSENT' -or $row.machine -ne 'ABSENT' -or $row.feature -ne -1){throw 'EXACT_PRODUCTS_NOT_ABSENT'}
    }
}
function Test-HeaderCaContextMarker([string]$Text) {
    $prefix='(?:MSI \([cs]\) \([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}\) \[[0-9:.]+\]: )?'
    $all=[regex]::Matches($Text,'(?m)^'+$prefix+'Hello, I''m your[^\r\n]*custom action server\.\r?$')
    $expected=[regex]::Matches($Text,'(?m)^'+$prefix+'Hello, I''m your 64bit Impersonated custom action server\.\r?$')
    return ($all.Count -eq 1 -and $expected.Count -eq 1)
}
function Test-HeaderLog([string]$Text,[int]$ExitCode) {
    if($ExitCode -ne 1603){throw 'PROBE_EXIT_NOT_EXPECTED_PRESERVE_NO_RETRY'}
    if(-not (Test-HeaderCaContextMarker $Text) -or
       $Text -notmatch 'Action start [^\r\n]*CcbHeaderDiagnosticOnly\.') {throw 'EXPECTED_CA_CONTEXT_NOT_OBSERVED'}
    $begin=[regex]::Matches($Text,'(?m)^CCB_HEADER_PROBE_BEGIN contract=1\s*$')
    $result=[regex]::Matches($Text,'(?m)^CCB_HEADER_PROBE_RESULT result=(PASS|REJECT)\s*$')
    $stop=[regex]::Matches($Text,'(?m)^CCB_HEADER_PROBE_STOP result=1603\s*$')
    if($begin.Count -ne 1 -or $result.Count -ne 1 -or $stop.Count -ne 1 -or
       $begin[0].Index -ge $result[0].Index -or $result[0].Index -ge $stop[0].Index){throw 'PROBE_MARKERS_INCOMPLETE_OR_DUPLICATE'}
    $allowed=@('INSTALL','FindRelatedProducts','AppSearch','CcbHeaderDiagnosticOnly')
    foreach($match in [regex]::Matches($Text,'(?m)^Action start [^\r\n]*?: ([A-Za-z][A-Za-z0-9_]*)\.\s*$')){
        if($match.Groups[1].Value -notin $allowed){throw 'UNEXPECTED_ACTION_STARTED_PRESERVE_NO_RETRY'}
    }
    if($Text -match 'Action start [^\r\n]*CcbStopAfterHeaderProbe\.' -or
       $Text -notmatch 'Action ended [^\r\n]*CcbHeaderDiagnosticOnly\. Return value 3\.' -or
       $Text -notmatch 'Action ended [^\r\n]*INSTALL\. Return value 3\.' -or
       $Text -notmatch 'MainEngineThread is returning 1603'){throw 'EXPECTED_STOP_NOT_CONFIRMED'}
    $details=@([regex]::Matches($Text,'(?m)^CCB_HEADER_REJECT [^\r\n]+\r?$')|ForEach-Object {$_.Value.TrimEnd("`r")})
    $value=$result[0].Groups[1].Value
    if(($value -eq 'REJECT' -and $details.Count -ne 1) -or ($value -eq 'PASS' -and $details.Count -ne 0)){throw 'HEADER_DETAIL_RESULT_MISMATCH'}
    return @{header=$value;classification=$(if($value -eq 'PASS'){'UNREPRODUCED_INCONCLUSIVE'}else{'HEADER_REJECTION_OBSERVED'});
        details=@($details);installationAccepted=$false;rollbackProven=$false;globalNoWritesProven=$false}
}
function Invoke-HeaderNativeProbe([string]$Msi,[string]$Log,[string]$Root) {
    $arguments='/i "'+$Msi+'" /qn /norestart /l*v "'+$Log+'" REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable'
    @{executable='msiexec.exe';arguments=$arguments;retry=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'COMMAND.json') -Encoding UTF8
    $process=Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -PassThru -WindowStyle Hidden
    if(-not $process.WaitForExit(300000)){throw 'INSTALLER_STILL_RUNNING_PRESERVE_NO_KILL_NO_RETRY'}
    $process.Refresh();return $process.ExitCode
}
function Invoke-HeaderProbeOnce([string]$Source,[string]$ExpectedManifestHash,[string]$HostConfirmedVmId,[bool]$Confirmed) {
    $root=$null;$createdRoot=$false;$result=@{status='PRECONDITION_NOT_COMPLETED';installerInvoked=$false;noCleanup=$true;retry=$false}
    try{
        $source=Assert-HeaderPath $Source
        if($ExpectedManifestHash -notmatch '^[a-f0-9]{64}$'){throw 'FIXED_MANIFEST_REQUIRED'}
        $manifestPath=Join-Path $source 'DELIVERY_IDENTITY.json'
        if((Get-HeaderFileProof $manifestPath).sha256 -ne $ExpectedManifestHash){throw 'MANIFEST_HASH_MISMATCH'}
        $manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json
        foreach($row in $manifest.files){
            if($row.name -notmatch '^[A-Za-z0-9_.-]+$'){throw 'MANIFEST_LEAF_REQUIRED'}
            $proof=Get-HeaderFileProof (Join-Path $source $row.name)
            if($proof.bytes -ne $row.bytes -or $proof.sha256 -ne $row.sha256){throw 'DELIVERY_FILE_MISMATCH'}
        }
        $identity=Get-Content -LiteralPath (Join-Path $source 'PROBE_IDENTITY.json') -Raw|ConvertFrom-Json
        if($identity.profile -ne 'HEADER_EARLY_STOP_ONLY_NOT_INSTALL' -or $identity.sourceSha256 -ne '5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79' -or
           $identity.fileName -notmatch '^[A-Za-z0-9_.-]+\.msi$'){throw 'FIXED_PROBE_IDENTITY_REQUIRED'}
        $guest=Get-HeaderGuest;Assert-HeaderGuest $guest $HostConfirmedVmId $Confirmed
        $baseline=Assert-HeaderPath (Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01')
        $baselineProof=@()
        foreach($name in @('COMMAND.json','RESULT.json','install-1112.log')){
            $proof=Get-HeaderFileProof (Join-Path $baseline $name)
            $baselineProof+=@{name=$name;bytes=$proof.bytes;sha256=$proof.sha256}
        }
        $baselineText=Get-Content -LiteralPath (Join-Path $baseline 'install-1112.log') -Raw
        if($baselineText -notmatch 'CCB_OPERATION_DENIED stage=ACTIVE_RESOURCE_CONTRACT'){throw 'FIXED_1112_FAILURE_BASELINE_REQUIRED'}
        $root=Assert-HeaderPath (Join-Path $env:LOCALAPPDATA 'CCB-Header-EarlyStop-1112-r01')
        if(Test-Path -LiteralPath $root){$root=$null;throw 'ONE_SHOT_OUTPUT_EXISTS_NO_RETRY'}
        if((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($root))).AvailableFreeSpace -lt 1GB){$root=$null;throw 'PROBE_NEEDS_1_GIB_FREE'}
        $null=New-Item -ItemType Directory -Path $root
        $createdRoot=$true
        $result.guest=$guest;$result.hostConfirmedVmId=$HostConfirmedVmId;$result.vmIdProvenByGuest=$false
        $result.baseline=$baselineProof;$result.source=(Get-HeaderFileProof (Join-Path $source $identity.fileName))
        $before=Get-HeaderRegistration $identity $guest.sid;$result.before=$before;Assert-HeaderAbsent $before
        # Collect the three explicit baseline files before the one call, so even
        # an unexpected native result retains the complete bounded collection.
        $null=New-Item -ItemType Directory -Path (Join-Path $root 'baseline')
        foreach($row in $baselineProof){
            $path=Join-Path $baseline $row.name
            if((Get-HeaderFileProof $path).sha256 -ne $row.sha256){throw 'BASELINE_CHANGED'}
            Copy-Item -LiteralPath $path -Destination (Join-Path (Join-Path $root 'baseline') $row.name)
            if((Get-HeaderFileProof (Join-Path (Join-Path $root 'baseline') $row.name)).sha256 -ne $row.sha256){throw 'BASELINE_COPY_MISMATCH'}
        }
        $msi=Assert-HeaderPath (Join-Path $source $identity.fileName) -File
        $log=Join-Path $root 'probe.log'
        $result.installerInvoked=$true
        $exitCode=Invoke-HeaderNativeProbe $msi $log $root;$result.nativeExit=$exitCode
        $after=Get-HeaderRegistration $identity $guest.sid;$result.after=$after;Assert-HeaderAbsent $after
        if((Get-HeaderFileProof $msi).sha256 -ne $identity.sha256){throw 'INPUT_PACKAGE_CHANGED'}
        $observed=Test-HeaderLog (Get-Content -LiteralPath $log -Raw) $exitCode
        $result.observed=$observed;$result.status='EARLY_STOP_DIAGNOSTIC_ONLY_NOT_INSTALL_ACCEPTANCE'
    }catch{
        $result.status='PRESERVE_STOP_NO_RETRY';$result.error=[string]$_.Exception.Message
        throw
    }finally{
        if($createdRoot){$result|ConvertTo-Json -Depth 14|Set-Content -LiteralPath (Join-Path $root 'RESULT.json') -Encoding UTF8
            Write-Output ('RESULT_DIRECTORY='+$root)}
    }
    return $result
}
