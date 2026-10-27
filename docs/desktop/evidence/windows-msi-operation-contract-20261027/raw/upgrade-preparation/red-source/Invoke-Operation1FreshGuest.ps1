param([string]$ExpectedIdentitySha256,[switch]$ConfirmIsolatedGuest,[switch]$OpenSyntheticWindow)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2

function Assert-Op1Path([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    if($full -notmatch '^[A-Za-z]:\\' -or $full -match '["\r\n]'){throw 'LOCAL_PATH_REQUIRED'}
    $cursor=$full
    while($cursor){
        if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'REPARSE_REFUSED'}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    return $full
}
function Get-Op1Guest {
    $system=Get-CimInstance Win32_ComputerSystem
    $os=Get-CimInstance Win32_OperatingSystem
    $edition=(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').EditionID
    return @{computer=$env:COMPUTERNAME;manufacturer=$system.Manufacturer;model=$system.Model;build=[string]$os.BuildNumber;edition=$edition;sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value;bits=[IntPtr]::Size*8}
}
function Get-Op1Identity([string]$Source,[string]$ExpectedHash) {
    if($ExpectedHash -notmatch '^[a-f0-9]{64}$'){throw 'FIXED_IDENTITY_HASH_REQUIRED'}
    $path=Assert-Op1Path (Join-Path $Source 'PACKAGE_IDENTITY.json')
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash){throw 'IDENTITY_HASH_MISMATCH'}
    $identity=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json
    if($identity.productName -ne 'ChatChatBarDesktopSynthetic' -or $identity.version -ne '0.1.1112' -or $identity.upgradeCode -ne '{58E95FD1-709D-5813-9B5B-F518A4612BAE}' -or $identity.operationContract -ne 1){throw 'FIXED_SYNTHETIC_1112_REQUIRED'}
    if($identity.fileName -ne 'ChatChatBarDesktopSynthetic-0.1.1112.msi'){throw 'FIXED_PACKAGE_NAME_REQUIRED'}
    $msi=Assert-Op1Path (Join-Path $Source $identity.fileName)
    if((Get-Item -LiteralPath $msi).Length -ne $identity.bytes -or (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash.ToLowerInvariant() -ne $identity.sha256){throw 'PACKAGE_HASH_MISMATCH'}
    return $identity
}
function Initialize-Op1Msi {
    if('Op1MsiNative' -as [type]){return}
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class Op1MsiNative {
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiGetProductInfoExW(string p,string sid,uint context,string name,StringBuilder result,ref uint size);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiEnumRelatedProductsW(string p,uint reserved,uint i,StringBuilder result);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern int MsiQueryFeatureStateW(string p,string feature);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiQueryComponentStateW(string p,string sid,uint context,string component,out int state);
 [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern int MsiGetComponentPathExW(string p,string component,string sid,uint context,StringBuilder path,ref uint size);
 public static string Info(string p,string sid,string name,bool absentAllowed) {
   uint size=32768;var b=new StringBuilder((int)size);uint c=MsiGetProductInfoExW(p,sid,2,name,b,ref size);
   if(c==1605 && absentAllowed)return null;
   if(c!=0)throw new InvalidOperationException("EXACT_PRODUCT_QUERY_FAILED_"+c);
   return b.ToString();
 }
}
'@
}
function Get-Op1Registration($Identity,[string]$Sid) {
    Initialize-Op1Msi
    $related=@()
    for($i=0;$i -lt 32;$i++){
        $value=New-Object Text.StringBuilder 39
        $code=[Op1MsiNative]::MsiEnumRelatedProductsW($Identity.upgradeCode,0,$i,$value)
        if($code -eq 259){break};if($code -ne 0){throw ('RELATED_QUERY_FAILED_'+$code)}
        $related+= $value.ToString()
    }
    if($i -eq 32){throw 'RELATED_LIMIT'}
    $state=[Op1MsiNative]::Info($Identity.productCode,$Sid,'State',$true)
    $answer=@{related=@($related);state=$state;feature=[Op1MsiNative]::MsiQueryFeatureStateW($Identity.productCode,'DefaultFeature');location=$null;cache=$null;packageCode=$null}
    if($null -ne $state){
        $answer.location=[Op1MsiNative]::Info($Identity.productCode,$Sid,'InstallLocation',$false)
        $answer.cache=[Op1MsiNative]::Info($Identity.productCode,$Sid,'LocalPackage',$false)
        $answer.packageCode=[Op1MsiNative]::Info($Identity.productCode,$Sid,'PackageCode',$false)
    }
    return $answer
}
function Get-Op1Output {return (Join-Path $env:LOCALAPPDATA 'CCB-OP1-First-1112-r01')}
function Assert-Op1FreeSpace([string]$Root){if((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($Root))).AvailableFreeSpace -lt 2GB){throw 'GUEST_NEEDS_2_GIB_FREE'}}
function Invoke-Op1Install([string]$Source,[string]$Root,$Identity){
    $args='/i "'+(Join-Path $Source $Identity.fileName)+'" /qn /norestart /l*v "'+(Join-Path $Root 'install-1112.log')+'" REBOOT=ReallySuppress MSIRESTARTMANAGERCONTROL=Disable'
    @{exe='msiexec.exe';arguments=$args;defaultDirectory=$true;retry=$false}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'COMMAND.json') -Encoding UTF8
    $process=Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $args -PassThru -WindowStyle Hidden
    if(-not $process.WaitForExit(600000)){throw 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'}
    $process.Refresh();return $process.ExitCode
}
function Get-Op1FileInventory([string]$Program,$ExpectedFiles){
    $files=@()
    foreach($row in $ExpectedFiles){
        $path=Assert-Op1Path (Join-Path $Program $row.path)
        if(-not $path.StartsWith($Program.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'EXPECTED_FILE_ESCAPE'}
        $file=Get-Item -LiteralPath $path -Force
        $hash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if($file.PSIsContainer -or $file.Length -ne $row.bytes -or $hash -ne $row.sha256){throw 'INSTALLED_FILE_MISMATCH'}
        $files+=@{path=$row.path;bytes=$file.Length;sha256=$hash}
    }
    return $files
}
function Get-Op1InstalledEvidence([string]$Program,$Identity,[string]$Sid){
    $components=@();$registry=@()
    if(@($Identity.files).Count -ne 216 -or @($Identity.components).Count -ne 218){throw 'EXPECTED_RESOURCE_COUNTS_CHANGED'}
    $files=@(Get-Op1FileInventory $Program $Identity.files)
    foreach($row in $Identity.components){
        $state=0;$code=[Op1MsiNative]::MsiQueryComponentStateW($Identity.productCode,$Sid,2,$row.guid,[ref]$state)
        $text=New-Object Text.StringBuilder 32768;[uint32]$length=32768
        $pathState=[Op1MsiNative]::MsiGetComponentPathExW($Identity.productCode,$row.guid,$Sid,2,$text,[ref]$length)
        if($code -ne 0 -or $state -ne 3 -or $pathState -ne 3){throw 'COMPONENT_REGISTRATION_INCOMPLETE'}
        $components+=@{guid=$row.guid;queryStatus=$code;state=$state;keyPathState=$pathState;nativeKeyPath=$text.ToString()}
    }
    $hkcu=[Microsoft.Win32.RegistryKey]::OpenBaseKey([Microsoft.Win32.RegistryHive]::CurrentUser,[Microsoft.Win32.RegistryView]::Registry64)
    try{
        foreach($row in $Identity.registry){
            if(-not $row.key.StartsWith('Software\ChatChatBar\ChatChatBarDesktopSynthetic\',[StringComparison]::OrdinalIgnoreCase)){throw 'REGISTRY_SCOPE_REFUSED'}
            $key=$hkcu.OpenSubKey($row.key,$false);if($null -eq $key){throw 'OWN_REGISTRY_KEY_MISSING'}
            try{
                if($key.GetValueKind($row.name) -ne [Microsoft.Win32.RegistryValueKind]::String){throw 'OWN_REGISTRY_TYPE_MISMATCH'}
                $value=[string]$key.GetValue($row.name,$null,[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
                $expected=[string]$row.value
                if($expected -eq '[ProductCode]'){$expected=$Identity.productCode}
                elseif($expected -eq '[INSTALLDIR]'){$value=$value.TrimEnd('\');$expected=$Program.TrimEnd('\')}
                elseif($expected -ne '1'){throw 'REGISTRY_VALUE_CONTRACT_UNSUPPORTED'}
                if($value -ne $expected){throw 'OWN_REGISTRY_VALUE_MISMATCH'}
                $registry+=@{key=$row.key;name=$row.name;verified=$true}
            }finally{$key.Dispose()}
        }
    }finally{$hkcu.Dispose()}
    return @{files=$files;components=$components;registry=$registry}
}
function Open-Op1SyntheticWindow([string]$Program,[string]$Root){
    foreach($name in @('app-local','app-roaming','app-home','app-temp')){$null=New-Item -ItemType Directory -Path (Join-Path $Root $name) -ErrorAction Stop}
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=Join-Path $Program 'ChatChatBarDesktopSynthetic.exe';$info.WorkingDirectory=$Program;$info.UseShellExecute=$false
    $info.EnvironmentVariables['LOCALAPPDATA']=Join-Path $Root 'app-local';$info.EnvironmentVariables['APPDATA']=Join-Path $Root 'app-roaming'
    $temp=Join-Path $Root 'app-temp';$homePath=Join-Path $Root 'app-home'
    $info.EnvironmentVariables['TEMP']=$temp;$info.EnvironmentVariables['TMP']=$temp
    $info.EnvironmentVariables['JAVA_TOOL_OPTIONS']='-Duser.home="'+$homePath+'" -Djava.io.tmpdir="'+$temp+'" -Dfile.encoding=UTF-8'
    $info.EnvironmentVariables.Remove('_JAVA_OPTIONS');$info.EnvironmentVariables.Remove('JDK_JAVA_OPTIONS')
    $process=[Diagnostics.Process]::Start($info)
    Write-Host 'ISOLATED_FAKE_DATA_WINDOW_OPENING_CLOSE_NORMALLY_NO_PROVIDER_ACTION'
    $title='';$deadline=[DateTime]::UtcNow.AddSeconds(90)
    while([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited){$process.Refresh();$title=$process.MainWindowTitle;if($title){break};Start-Sleep -Milliseconds 200}
    $observed=($title -eq 'ChatChatBar')
    if(-not $process.WaitForExit(180000)){return @{mainWindowObserved=$observed;windowTitle=$title;launcherPid=$process.Id;launcherStillRunning=$true;descendantDrain='NOT_TESTED';rootLockRelease='NOT_TESTED'}}
    $process.Refresh();return @{mainWindowObserved=$observed;windowTitle=$title;launcherExit=$process.ExitCode;launcherStillRunning=$false;descendantDrain='NOT_TESTED';rootLockRelease='NOT_TESTED'}
}
function Invoke-Operation1FreshGuest([string]$Source,[string]$IdentityHash,[bool]$Confirmed,[bool]$OpenWindow){
    if(-not $Confirmed){throw 'CONFIRM_INDEPENDENT_CLEAN_GUEST_REQUIRED'}
    $guest=Get-Op1Guest
    if($guest.manufacturer -ne 'Microsoft Corporation' -or $guest.model -ne 'Virtual Machine' -or $guest.computer -in @('DD1','DL1') -or $guest.sid -in @('S-1-5-18','S-1-5-19','S-1-5-20') -or $guest.build -ne '19045' -or $guest.edition -ne 'Professional' -or $guest.bits -ne 64){throw 'WIN10_PRO_19045_INTERACTIVE_HYPERV_GUEST_REQUIRED'}
    $Source=Assert-Op1Path $Source;$identity=Get-Op1Identity $Source $IdentityHash
    $before=Get-Op1Registration $identity $guest.sid
    if($before.related.Count -ne 0 -or $null -ne $before.state -or $before.feature -ne -1){throw 'FRESH_REGISTRATION_REQUIRED_NO_RETRY'}
    $root=Assert-Op1Path (Get-Op1Output);if(Test-Path -LiteralPath $root){throw 'NEW_RESULT_DIRECTORY_REQUIRED_NO_RETRY'}
    Assert-Op1FreeSpace $root;$null=New-Item -ItemType Directory -Path $root
    $result=@{status='PREPARED';guest=$guest;before=$before;installerInvoked=$false;retry=$false;installExit=$null;uiRequested=$OpenWindow;vmIdentity='USER_SELECTED_COPY_NOT_PROVEN_BY_GUEST_MODEL'}
    try{
        $result.installerInvoked=$true;$result.installExit=Invoke-Op1Install $Source $root $identity
        $result.after=Get-Op1Registration $identity $guest.sid
        if($result.installExit -ne 0){throw 'NATIVE_INSTALL_FAILED_PRESERVE_NO_RETRY'}
        if($result.after.state -ne '5' -or $result.after.feature -ne 3 -or $result.after.related.Count -ne 1 -or $result.after.related[0] -ne $identity.productCode -or [Guid]$result.after.packageCode -ne [Guid]$identity.packageCode){throw 'PRODUCT_FEATURE_OR_PACKAGE_REGISTRATION_MISMATCH'}
        $program=Assert-Op1Path $result.after.location
        $expected=Assert-Op1Path (Join-Path $env:LOCALAPPDATA 'ChatChatBarDesktopSyntheticApp')
        if($program.TrimEnd('\') -ne $expected.TrimEnd('\')){throw 'DEFAULT_INSTALL_LOCATION_MISMATCH'}
        if(-not [IO.File]::Exists((Assert-Op1Path $result.after.cache))){throw 'NATIVE_CACHE_MISSING'}
        Get-Op1InstalledEvidence $program $identity $guest.sid|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'INSTALLED_RESOURCES.json') -Encoding UTF8
        $log=Get-Content -LiteralPath (Join-Path $root 'install-1112.log') -Raw
        if($log -notmatch 'CCB_OPERATION_ADMITTED operation=F contract=1 inventory=NOT_REQUIRED rollback=ENABLED'){throw 'FORWARD_ADMISSION_OR_ROLLBACK_MODE_NOT_OBSERVED'}
        $result.status='FULL_FRESH_INSTALL_FILES_REGISTRY_COMPONENTS_OBSERVED'
        if($OpenWindow){$result.application=Open-Op1SyntheticWindow $program $root}
        $result.lifecycle='UPGRADE_REPAIR_UNINSTALL_ROLLBACK_AND_JOB_DRAIN_NOT_TESTED'
    }catch{$result.status='STOP_PRESERVE_SCENE_NO_RETRY';$result.error=$_.Exception.Message;throw}
    finally{$result|ConvertTo-Json -Depth 10|Set-Content -LiteralPath (Join-Path $root 'RESULT.json') -Encoding UTF8;Write-Output ('RESULT_DIRECTORY='+$root);$result|ConvertTo-Json -Depth 10|Write-Output}
}
if($MyInvocation.InvocationName -ne '.'){
    Invoke-Operation1FreshGuest $PSScriptRoot $ExpectedIdentitySha256 ([bool]$ConfirmIsolatedGuest) ([bool]$OpenSyntheticWindow)
}
