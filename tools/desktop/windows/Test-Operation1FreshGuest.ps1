param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
. (Join-Path $PSScriptRoot 'Invoke-Operation1FreshGuest.ps1')
if(Test-Path -LiteralPath $Evidence){throw 'NEW_TEST_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
$Evidence=[IO.Path]::GetFullPath($Evidence)
$script:cases=@();$script:counter=0
function Get-Op1Guest {
    $value=@{computer='FAKE_GUEST';manufacturer='Microsoft Corporation';model='Virtual Machine';build='19045';edition='Professional';sid='SYNTHETIC_ONLY';bits=64}
    if($script:mode -eq 'host'){$value.computer='DD1'}
    if($script:mode -eq 'wrong-build'){$value.build='22631'}
    return $value
}
function Get-Op1Identity([string]$Source,[string]$Hash){
    if($script:mode -eq 'hash-fail'){throw 'PACKAGE_HASH_MISMATCH'}
    return @{productCode='{11111111-1111-4111-8111-111111111111}';packageCode='{22222222-2222-4222-8222-222222222222}';fileName='FAKE_NOT_OPENED.msi'}
}
function Get-Op1Registration($Identity,[string]$Sid){
    $state=@{related=@();state=$null;feature=-1;location=$null;cache=$null;packageCode=$null}
    if($script:mode -eq 'existing'){$state.state='5';$state.related=@($Identity.productCode)}
    if($script:mode -eq 'read-fail'){throw 'EXACT_PRODUCT_QUERY_FAILED_5'}
    if($script:installs -gt 0 -and $script:mode -ne 'install-fail'){
        $state.state='5';$state.feature=3;$state.related=@($Identity.productCode);$state.location=$script:program;$state.cache=$script:cache;$state.packageCode=$Identity.packageCode
        if($script:mode -eq 'bad-after'){$state.feature=1}
    }
    return $state
}
function Get-Op1Output{return $script:root}
function Assert-Op1FreeSpace([string]$Root){if($script:mode -eq 'low-space'){throw 'GUEST_NEEDS_2_GIB_FREE'}}
function Invoke-Op1Install([string]$Source,[string]$Root,$Identity){
    $script:installs++
    'CCB_OPERATION_ADMITTED operation=F contract=1 inventory=NOT_REQUIRED rollback=ENABLED'|Set-Content -LiteralPath (Join-Path $Root 'install-1112.log') -Encoding ASCII
    if($script:mode -eq 'timeout'){throw 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'}
    if($script:mode -eq 'install-fail'){return 1603}
    return 0
}
function Get-Op1InstalledEvidence([string]$Program,$Identity,[string]$Sid){if($script:mode -eq 'file-fail'){throw 'INSTALLED_FILE_MISMATCH'};return @{kind='FAKE_RESOURCE_BOUNDARY_NO_MSI';files=@()}}
function Open-Op1SyntheticWindow([string]$Program,[string]$Root){$script:windows++;return @{kind='FAKE_NO_WINDOW';mainWindowObserved=$true;descendantDrain='NOT_TESTED'}}
$savedLocal=$env:LOCALAPPDATA
try{
    $env:LOCALAPPDATA=Join-Path $Evidence 'fake-local';$null=New-Item -ItemType Directory -Path $env:LOCALAPPDATA
    $script:program=Join-Path $env:LOCALAPPDATA 'ChatChatBarDesktopSyntheticApp';$script:cache=Join-Path $Evidence 'fake-cache.msi'
    [IO.File]::WriteAllText($script:cache,'INVENTED_NOT_AN_MSI')
    function Case([string]$Name,[string]$Mode,$Expected,[bool]$Confirmed=$true,[bool]$Open=$false){
        $script:counter++;$script:mode=$Mode;$script:installs=0;$script:windows=0;$script:root=Join-Path $Evidence ('case-'+$script:counter)
        $caught=$null
        try{ $null=Invoke-Operation1FreshGuest $Evidence ('0'*64) $Confirmed $Open }catch{$caught=$_.Exception.Message}
        $pass=($caught -eq $Expected)
        if($Name -like 'pre-*' -and ($script:installs -ne 0 -or (Test-Path -LiteralPath $script:root))){$pass=$false}
        if($Name -like 'post-*' -and ($script:installs -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $script:root 'RESULT.json')))){$pass=$false}
        if($Name -eq 'post-success-ui' -and $script:windows -ne 1){$pass=$false}
        if($Name -eq 'post-success' -and $script:windows -ne 0){$pass=$false}
        $script:cases+=@{name=$Name;pass=$pass;error=$caught;installerBoundaryCalls=$script:installs;windowBoundaryCalls=$script:windows}
    }
    Case 'pre-confirmation' 'success' 'CONFIRM_INDEPENDENT_CLEAN_GUEST_REQUIRED' $false
    Case 'pre-host' 'host' 'WIN10_PRO_19045_INTERACTIVE_HYPERV_GUEST_REQUIRED'
    Case 'pre-build' 'wrong-build' 'WIN10_PRO_19045_INTERACTIVE_HYPERV_GUEST_REQUIRED'
    Case 'pre-hash' 'hash-fail' 'PACKAGE_HASH_MISMATCH'
    Case 'pre-registration' 'existing' 'FRESH_REGISTRATION_REQUIRED_NO_RETRY'
    Case 'pre-read-error' 'read-fail' 'EXACT_PRODUCT_QUERY_FAILED_5'
    Case 'pre-space' 'low-space' 'GUEST_NEEDS_2_GIB_FREE'
    Case 'post-install-failure' 'install-fail' 'NATIVE_INSTALL_FAILED_PRESERVE_NO_RETRY'
    Case 'post-timeout' 'timeout' 'INSTALLER_STILL_RUNNING_PRESERVE_NO_RETRY'
    Case 'post-incomplete-after' 'bad-after' 'PRODUCT_FEATURE_OR_PACKAGE_REGISTRATION_MISMATCH'
    Case 'post-file-failure' 'file-fail' 'INSTALLED_FILE_MISMATCH'
    Case 'post-success' 'success' $null
    Case 'post-success-ui' 'success' $null $true $true
    # Real filesystem oracle on an independent tiny fixture, without MSI/registry.
    $mini=Join-Path $Evidence 'tiny-program';$null=New-Item -ItemType Directory -Path $mini
    $file=Join-Path $mini 'sample.txt';[IO.File]::WriteAllText($file,'INVENTED_TEST_CONTENT')
    $length=(Get-Item -LiteralPath $file).Length;$hash=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach($kind in @('valid','wrong-size','wrong-hash','escape')){
        $row=@{path='sample.txt';bytes=$length;sha256=$hash}
        if($kind -eq 'wrong-size'){$row.bytes++};if($kind -eq 'wrong-hash'){$row.sha256='0'*64};if($kind -eq 'escape'){$row.path='..\outside.txt'}
        $caught=$null;try{$inventory=@(Get-Op1FileInventory $mini @($row))}catch{$caught=$_.Exception.Message}
        $pass=if($kind -eq 'valid'){$null -eq $caught -and $inventory.Count -eq 1}else{$null -ne $caught}
        $script:cases+=@{name=('real-file-oracle-'+$kind);pass=$pass;error=$caught;installerBoundaryCalls=0;windowBoundaryCalls=0}
    }
    # Compile the actual C# declarations only; no native method or installer is called.
    Initialize-Op1Msi
    $report=@{powershell=$PSVersionTable.PSVersion.ToString();cases=$script:cases;tests=$script:cases.Count;failed=@($script:cases|Where-Object {-not $_.pass}).Count;actualInstallerExecuted=$false;actualRegistryQueries=$false;actualWindowOpened=$false;csharpDeclarationsCompiled=$true}
    $report|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
    $xml=New-Object Xml.XmlDocument
    $suite=$xml.CreateElement('testsuite');$suite.SetAttribute('name','Operation1GuestFakeBoundaries');$suite.SetAttribute('tests',[string]$report.tests);$suite.SetAttribute('failures',[string]$report.failed);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0');$null=$xml.AppendChild($suite)
    foreach($case in $script:cases){$item=$xml.CreateElement('testcase');$item.SetAttribute('name',$case.name);$null=$suite.AppendChild($item);if(-not $case.pass){$failure=$xml.CreateElement('failure');$failure.InnerText=[string]$case.error;$null=$item.AppendChild($failure)}}
    $xml.Save((Join-Path $Evidence 'TEST-Operation1GuestFakeBoundaries.xml'))
    if($report.failed){throw 'OPERATION1_GUEST_ORCHESTRATION_TEST_FAILED'}
    Write-Output 'OPERATION1_GUEST_ORCHESTRATION_FAKE_BOUNDARIES_PASS'
}finally{$env:LOCALAPPDATA=$savedLocal}
