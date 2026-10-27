param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. (Join-Path $PSScriptRoot 'Invoke-Operation1FreshGuest.ps1')
. (Join-Path $PSScriptRoot 'Invoke-Operation1UpgradeGuest.ps1')
if(Test-Path -LiteralPath $Evidence){throw 'NEW_TEST_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
$Evidence=[IO.Path]::GetFullPath($Evidence)
$script:cases=@();$script:counter=0
function Get-Op1Guest {
    $g=@{computer='FAKE_GUEST';manufacturer='Microsoft Corporation';model='Virtual Machine';build='19045';edition='Professional';sid='INVENTED_SID';bits=64}
    if($script:mode -eq 'host'){$g.computer='DD1'};return $g
}
function Get-Op1UpgradeIdentity([string]$Source,[string]$Name,[string]$Hash,[string]$Version,[string]$Product){
    if($script:mode -eq 'identity-fail'){throw 'IDENTITY_HASH_MISMATCH'}
    return @{productCode=$Product;packageCode=if($Version -eq '0.1.1112'){'{432781D2-6756-4AB8-A829-88E6AD89D32D}'}else{'{8FDA211F-872C-402B-A8DF-C1D711B1E667}'};version=$Version}
}
function Assert-Op1UpgradePackage([string]$Source,$Id){if($script:mode -eq 'source-change' -and $script:installs){throw 'B_PACKAGE_HASH_MISMATCH'}}
function Get-Op1FirstReceipt {
    return @{status=if($script:mode -eq 'bad-first'){'STOP'}else{'FULL_FRESH_INSTALL_FILES_REGISTRY_COMPONENTS_OBSERVED'};installExit=0;guest=@{sid='INVENTED_SID'};after=@{location=$script:program}}
}
function Assert-Op1UpgradeProcessIdle {if($script:mode -eq 'running'){throw 'CLOSE_SYNTHETIC_WINDOW_BEFORE_UPGRADE'}}
function Get-Op1Registration($Id,[string]$Sid){
    if($script:mode -eq 'read-fail'){throw 'EXACT_PRODUCT_QUERY_FAILED_5'}
    $present=if($script:installs){$Id.version -eq '0.1.1113'}else{$Id.version -eq '0.1.1112'}
    if($script:mode -eq 'b-present' -and $Id.version -eq '0.1.1113'){$present=$true}
    if($script:mode -eq 'old-remains' -and $script:installs -and $Id.version -eq '0.1.1112'){$present=$true}
    $g=@{state=$null;feature=-1;related=@('{57CF060B-D0A1-3993-834A-15B88CB00D5D}');cache=$script:cache;location=$script:program;packageCode=$Id.packageCode}
    if($present){$g.state='5';$g.feature=3}
    if($script:installs){$g.related=@('{CCBBAAD8-9942-3FC4-9765-929D03E3511F}')}
    if($script:mode -eq 'bad-after' -and $script:installs -and $Id.version -eq '0.1.1113'){$g.feature=1}
    if($script:mode -eq 'bad-location' -and $script:installs){$g.location=Join-Path $Evidence 'WRONG_NOT_CREATED'}
    return $g
}
function Get-Op1InstalledEvidence([string]$Program,$Id,[string]$Sid){if($script:mode -eq 'bad-files'){throw 'INSTALLED_FILE_MISMATCH'};return @{kind='FAKE_NO_NATIVE_RESOURCE_QUERY'}}
function Get-Op1UpgradeOutput {return $script:root}
function Assert-Op1FreeSpace([string]$Root){if($script:mode -eq 'low-space'){throw 'GUEST_NEEDS_2_GIB_FREE'}}
function Invoke-Op1UpgradeInstaller([string]$Source,[string]$Root,$Id){
    $script:installs++
    if($script:mode -eq 'timeout'){throw 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'}
    if($script:mode -eq 'native-fail'){return 1603}
    return 0
}
function Assert-Op1OldOnlyRegistryAbsent($A,$B){if($script:mode -eq 'old-reg-remains'){throw 'OLD_ONLY_REGISTRY_VALUE_REMAINED'}}
function Assert-Op1UpgradeAdmissionLog([string]$Root){if($script:mode -eq 'no-child-admission'){throw 'PARENT_UPGRADE_AND_OLD_CHILD_ADMISSION_REQUIRED'}}
function Case([string]$Name,[string]$Mode,$Expected,[bool]$Confirmed=$true){
    $script:counter++;$script:mode=$Mode;$script:installs=0
    $script:root=Join-Path $Evidence ('result-'+$script:counter)
    $script:program=Join-Path $Evidence ('tiny-program-'+$script:counter);$null=New-Item -ItemType Directory -Path $script:program
    if($Mode -eq 'existing-output'){$null=New-Item -ItemType Directory -Path $script:root}
    $caught=$null
    try{$null=Invoke-Operation1UpgradeGuest $Evidence ('0'*64) ('1'*64) $Confirmed}catch{$caught=$_.Exception.Message}
    $pass=($caught -eq $Expected)
    if($Name -like 'pre-*'){$pass=$pass -and $script:installs -eq 0 -and -not (Test-Path -LiteralPath (Join-Path $script:program 'CCB_OPERATION1_UNMANAGED_CANARY_1112_1113.txt'))}
    if($Name -like 'post-*'){$pass=$pass -and $script:installs -eq 1 -and (Test-Path -LiteralPath (Join-Path $script:root 'RESULT.json'))}
    $script:cases+=@{name=$Name;pass=$pass;error=$caught;installerBoundaryCalls=$script:installs}
}
$script:cache=Join-Path $Evidence 'FAKE_NOT_AN_MSI.txt';[IO.File]::WriteAllText($script:cache,'INVENTED_CACHE_BOUNDARY_ONLY')
Case 'pre-confirmation' 'success' 'CONFIRM_SAME_INDEPENDENT_GUEST_REQUIRED' $false
Case 'pre-host' 'host' 'WIN10_PRO_19045_INTERACTIVE_HYPERV_GUEST_REQUIRED'
Case 'pre-identity' 'identity-fail' 'IDENTITY_HASH_MISMATCH'
Case 'pre-first-receipt' 'bad-first' 'SUCCESSFUL_SAME_USER_1112_RECEIPT_REQUIRED'
Case 'pre-open-window' 'running' 'CLOSE_SYNTHETIC_WINDOW_BEFORE_UPGRADE'
Case 'pre-native-read-failure' 'read-fail' 'EXACT_PRODUCT_QUERY_FAILED_5'
Case 'pre-b-present' 'b-present' 'B_ALREADY_REGISTERED_NO_RETRY'
Case 'pre-files' 'bad-files' 'INSTALLED_FILE_MISMATCH'
Case 'pre-space' 'low-space' 'GUEST_NEEDS_2_GIB_FREE'
Case 'pre-existing-output' 'existing-output' 'NEW_UPGRADE_RESULT_REQUIRED_NO_RETRY'
Case 'post-success' 'success' $null
Case 'post-native-failure' 'native-fail' 'NATIVE_UPGRADE_FAILED_PRESERVE_NO_RETRY'
Case 'post-timeout' 'timeout' 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'
Case 'post-b-incomplete' 'bad-after' 'EXACT_INSTALLED_PRODUCT_FEATURE_PACKAGE_REQUIRED'
Case 'post-old-remained' 'old-remains' 'OLD_PRODUCT_REGISTRATION_REMAINED'
Case 'post-location' 'bad-location' 'UPGRADE_SELECTED_DIRECTORY_NOT_PRESERVED'
Case 'post-old-registry' 'old-reg-remains' 'OLD_ONLY_REGISTRY_VALUE_REMAINED'
Case 'post-source-change' 'source-change' 'B_PACKAGE_HASH_MISMATCH'
Case 'post-child-not-admitted' 'no-child-admission' 'PARENT_UPGRADE_AND_OLD_CHILD_ADMISSION_REQUIRED'
# The real tiny canary is retained. No installed program or user data is used.
$mini=Join-Path $Evidence 'tiny-canary';$null=New-Item -ItemType Directory -Path $mini
$canary=New-Op1UnmanagedCanary $mini
$caught=$null;try{Assert-Op1CanaryUnchanged $canary}catch{$caught=$_.Exception.Message}
$script:cases+=@{name='real-canary-byte-match';pass=($null -eq $caught);error=$caught}
$caught=$null;try{$null=New-Op1UnmanagedCanary $mini}catch{$caught=$_.Exception.Message}
$script:cases+=@{name='real-canary-exclusive-create';pass=($null -ne $caught);error=$caught}
[IO.File]::AppendAllText($canary.path,'CHANGED_SYNTHETIC_BYTES')
$caught=$null;try{Assert-Op1CanaryUnchanged $canary}catch{$caught=$_.Exception.Message}
$script:cases+=@{name='real-canary-change-refused';pass=($caught -eq 'NONPACKAGE_CANARY_CHANGED');error=$caught}
# Exercise the actual script entry under Windows PowerShell with a fake common
# library. The fake overrides every orchestration/native path, never installs.
$entry=Join-Path $Evidence 'fake-entry';$null=New-Item -ItemType Directory -Path $entry
$fake=Join-Path $entry 'Invoke-Operation1FreshGuest.ps1'
@'
param([switch]$ConfirmIsolatedGuest)
function Invoke-Operation1UpgradeGuest([string]$Source,[string]$A,[string]$B,[bool]$Confirmed){
 if(-not $Confirmed){throw 'CONFIRMATION_LOST_DURING_COMMON_DOT_SOURCE'}
 Write-Output 'FAKE_ENTRY_CONFIRMATION_PRESERVED_NO_NATIVE_CALL'
}
'@|Set-Content -LiteralPath $fake -Encoding ASCII
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Invoke-Operation1UpgradeGuest.ps1') -Destination $entry
$hash=(Get-FileHash -LiteralPath $fake -Algorithm SHA256).Hash.ToLowerInvariant()
$arguments='-NoProfile -ExecutionPolicy Bypass -File "'+(Join-Path $entry 'Invoke-Operation1UpgradeGuest.ps1')+'" -ExpectedAIdentitySha256 '+('0'*64)+' -ExpectedBIdentitySha256 '+('1'*64)+' -ExpectedCommonHelperSha256 '+$hash+' -ConfirmIsolatedGuest'
$child=Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList $arguments -PassThru -Wait -WindowStyle Hidden -RedirectStandardOutput (Join-Path $Evidence 'fake-entry-output.log') -RedirectStandardError (Join-Path $Evidence 'fake-entry-error.log')
$nativeExit=$child.ExitCode;$output=Get-Content -LiteralPath (Join-Path $Evidence 'fake-entry-output.log') -Raw
$script:cases+=@{name='real-powershell-entry-confirmation-survives-dot-source';pass=($nativeExit -eq 0 -and ($output|Out-String).Contains('FAKE_ENTRY_CONFIRMATION_PRESERVED_NO_NATIVE_CALL'));exit=$nativeExit}
$report=@{powershell=$PSVersionTable.PSVersion.ToString();tests=$script:cases.Count;failed=@($script:cases|Where-Object {-not $_.pass}).Count;cases=$script:cases;actualInstallerExecuted=$false;actualRegistryQueried=$false;actualWindowOpened=$false}
$report|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$suite.SetAttribute('name','Operation1UpgradeFakeBoundaries');$suite.SetAttribute('tests',[string]$report.tests);$suite.SetAttribute('failures',[string]$report.failed);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0');$null=$xml.AppendChild($suite)
foreach($c in $script:cases){$t=$xml.CreateElement('testcase');$t.SetAttribute('name',$c.name);$null=$suite.AppendChild($t);if(-not $c.pass){$f=$xml.CreateElement('failure');$f.InnerText=($c|ConvertTo-Json -Compress);$null=$t.AppendChild($f)}}
$xml.Save((Join-Path $Evidence 'TEST-Operation1UpgradeFakeBoundaries.xml'))
if($report.failed){throw 'UPGRADE_ORCHESTRATION_TEST_FAILED'}
Write-Output 'UPGRADE_ORCHESTRATION_FAKE_BOUNDARIES_PASS'
