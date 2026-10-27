param([Parameter(Mandatory=$true)][string]$Evidence)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'WINDOWS_POWERSHELL_51_REQUIRED'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_SYNTHETIC_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
$Evidence=[IO.Path]::GetFullPath($Evidence)
. (Join-Path $PSScriptRoot 'Operation1CopyMetadata-r02.ps1')
$script:cases=@()
function Case([string]$Name,[scriptblock]$Action){
    try{&$Action;$script:cases+=@{name=$Name;pass=$true;error=$null}}
    catch{$script:cases+=@{name=$Name;pass=$false;error=$_.Exception.Message}}
}
function Rejects($InputValue,[string]$Expected){
    $caught=$null;try{$null=Get-Op1DiskBytes $InputValue}catch{$caught=$_.Exception.Message}
    if($caught -ne $Expected){throw ('WRONG_REFUSAL:'+ $caught)}
}
Case 'hashtable-array' {if((Get-Op1DiskBytes @(@{bytes=[long]12},@{bytes=[long]34})) -ne 46){throw 'WRONG_SUM'}}
Case 'single-hashtable' {if((Get-Op1DiskBytes @{bytes=[long]12}) -ne 12){throw 'WRONG_SINGLE'}}
Case 'pscustomobject-array' {if((Get-Op1DiskBytes @([pscustomobject]@{bytes=[long]12},[pscustomobject]@{bytes=[long]34})) -ne 46){throw 'WRONG_OBJECT_SUM'}}
Case 'mixed-object-shapes' {if((Get-Op1DiskBytes @(@{bytes=[int]12},[pscustomobject]@{bytes=[long]34})) -ne 46){throw 'WRONG_MIXED_SUM'}}
Case 'empty-array' {Rejects @() 'SOURCE_DISK_SIZE_UNAVAILABLE'}
Case 'null-evidence' {Rejects $null 'SOURCE_DISK_SIZE_UNAVAILABLE'}
Case 'nested-array' {$nested=New-Object 'object[]' 1;$nested[0]=@(@{bytes=[long]12},@{bytes=[long]34});Rejects $nested 'SOURCE_DISK_EVIDENCE_SHAPE_UNSUPPORTED'}
Case 'missing-dictionary-field' {Rejects @{path='FAKE'} 'SOURCE_DISK_BYTES_MISSING'}
Case 'missing-object-field' {Rejects ([pscustomobject]@{path='FAKE'}) 'SOURCE_DISK_BYTES_MISSING'}
Case 'null-bytes' {Rejects @{bytes=$null} 'SOURCE_DISK_BYTES_NOT_INTEGER'}
Case 'string-bytes' {Rejects @{bytes='12'} 'SOURCE_DISK_BYTES_NOT_INTEGER'}
Case 'floating-bytes' {Rejects @{bytes=12.5} 'SOURCE_DISK_BYTES_NOT_INTEGER'}
Case 'boolean-bytes' {Rejects @{bytes=$true} 'SOURCE_DISK_BYTES_NOT_INTEGER'}
Case 'zero-bytes' {Rejects @{bytes=[long]0} 'SOURCE_DISK_BYTES_OUT_OF_RANGE'}
Case 'negative-bytes' {Rejects @{bytes=[long]-1} 'SOURCE_DISK_BYTES_OUT_OF_RANGE'}
Case 'uint64-out-of-range' {Rejects @{bytes=[uint64]::MaxValue} 'SOURCE_DISK_BYTES_OUT_OF_RANGE'}
Case 'sum-overflow' {Rejects @(@{bytes=[long]::MaxValue},@{bytes=[long]1}) 'SOURCE_DISK_BYTES_OVERFLOW'}
Case 'capacity-exact-integer' {$n=Get-Op1CopyRequiredBytes 46 ([long]1GB);if($n -ne (8GB+92) -or $n.GetType() -ne [long]){throw 'WRONG_CAPACITY'}}
Case 'capacity-rejects-unknown-memory' {$caught=$null;try{$null=Get-Op1CopyRequiredBytes 46 $null}catch{$caught=$_.Exception.Message};if($caught -ne 'COPY_CAPACITY_INPUT_UNVERIFIED'){throw 'UNKNOWN_MEMORY_NOT_REFUSED'}}
Case 'capacity-overflow' {$caught=$null;try{$null=Get-Op1CopyRequiredBytes ([long]::MaxValue) ([long]1GB)}catch{$caught=$_.Exception.Message};if($caught -ne 'COPY_CAPACITY_OVERFLOW'){throw 'CAPACITY_OVERFLOW_NOT_REFUSED'}}

# Execute the exact original metadata function with mocked Hyper-V readers and
# real tiny files. No top-level host script, Hyper-V module or VM cmdlet is run.
$child=Join-Path $Evidence 'child.fake';$parent=Join-Path $Evidence 'parent.fake'
[IO.File]::WriteAllBytes($child,[byte[]](0..16));[IO.File]::WriteAllBytes($parent,[byte[]](0..28))
$script:child=$child;$script:parent=$parent
function Get-VMHardDiskDrive($VM){return @([pscustomobject]@{Path=$script:child},[pscustomobject]@{Path=$script:parent})}
function Get-VHD([string]$Path){return [pscustomobject]@{ParentPath=$(if($Path -eq $script:child){$script:parent}else{$null})}}
function Get-VMSnapshot($VM){return @([pscustomobject]@{Id='SYNTHETIC_CHECKPOINT';Name='SYNTHETIC_ONLY'})}
$tokens=$null;$errors=$null
$oldPath=Join-Path $PSScriptRoot 'Create-Operation1TestCopy.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($oldPath,[ref]$tokens,[ref]$errors)
if($errors){throw 'ORIGINAL_SCRIPT_PARSE_FAILED'}
$function=$ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-Op1SourceEvidence'},$true)
if(@($function).Count -ne 1){throw 'ORIGINAL_METADATA_FUNCTION_NOT_UNIQUE'}
. ([scriptblock]::Create($function[0].Extent.Text))
$sample=Get-Op1SourceEvidence ([pscustomobject]@{Id='FAKE_VM_NOT_QUERIED'})
$sampleShape=@{containerType=$sample.disks.GetType().FullName;entries=@($sample.disks|ForEach-Object {@{type=$_.GetType().FullName;bytesType=$_['bytes'].GetType().FullName;bytes=$_['bytes'];keys=@($_.Keys|Sort-Object)}})}
$sampleShape|ConvertTo-Json -Depth 6|Set-Content -LiteralPath (Join-Path $Evidence 'ACTUAL_FUNCTION_SYNTHETIC_SHAPE.json') -Encoding UTF8
$red=$null
try{$null=($sample.disks|Measure-Object -Property bytes -Sum).Sum}catch{$red=@{message=$_.Exception.Message;id=$_.FullyQualifiedErrorId}}
$red|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Evidence 'ORIGINAL_PS51_RED.json') -Encoding UTF8
Case 'original-function-reproduces-measure-error' {if($null -eq $red -or $red.id -notlike 'GenericMeasurePropertyNotFound*'){throw 'ORIGINAL_RED_NOT_REPRODUCED'}}
Case 'original-function-output-sums-and-deduplicates-chain' {if((Get-Op1DiskBytes $sample.disks) -ne 46 -or @($sample.disks).Count -ne 2){throw 'SOURCE_CHAIN_SHAPE_NOT_SUPPORTED'}}
Case 'no-original-write-command-before-failure-line' {
    $mutators=@('New-Item','Export-VMSnapshot','Import-VM','Rename-VM','Disconnect-VMNetworkAdapter','Start-VM','Stop-VM','Remove-VM','Set-VM','Remove-Item','Set-Content','Out-File')
    $early=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.CommandAst]},$true)|Where-Object {$_.Extent.StartLineNumber -le 34 -and $_.GetCommandName() -in $mutators})
    if($early.Count){throw 'MUTATING_COMMAND_BEFORE_REPORTED_FAILURE'}
}
Case 'new-and-inspection-script-parse' {
    foreach($file in @('Create-Operation1TestCopy-r02.ps1','Operation1CopyMetadata-r02.ps1','Inspect-Operation1PrepareFailure-r01.ps1')){
        $parseErrors=$null;$parseTokens=$null;$null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $file),[ref]$parseTokens,[ref]$parseErrors)
        if($parseErrors){throw ('PARSE_FAILED_'+$file)}
    }
}
$report=@{powershell=$PSVersionTable.PSVersion.ToString();tests=$script:cases.Count;failed=@($script:cases|Where-Object {-not $_.pass}).Count;cases=$script:cases;realHyperVModuleLoaded=$false;realVmCommandExecuted=$false;installerExecuted=$false;systemSettingsChanged=$false}
$report|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$suite.SetAttribute('name','Operation1CopySpacePS51');$suite.SetAttribute('tests',[string]$report.tests);$suite.SetAttribute('failures',[string]$report.failed);$suite.SetAttribute('errors','0');$suite.SetAttribute('skipped','0');$null=$xml.AppendChild($suite)
foreach($case in $script:cases){$node=$xml.CreateElement('testcase');$node.SetAttribute('name',$case.name);$null=$suite.AppendChild($node);if(-not $case.pass){$failure=$xml.CreateElement('failure');$failure.InnerText=[string]$case.error;$null=$node.AppendChild($failure)}}
$xml.Save((Join-Path $Evidence 'TEST-Operation1CopySpacePS51.xml'))
if($report.failed){throw 'COPY_SPACE_SYNTHETIC_TEST_FAILED'}
Write-Output 'COPY_SPACE_PS51_SYNTHETIC_PASS_NO_VM_OR_INSTALLER_ACTION'
