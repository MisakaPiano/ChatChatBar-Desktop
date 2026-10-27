param([Parameter(Mandatory=$true)][string]$Evidence,[string]$CommonSource)
$ErrorActionPreference='Stop'
Set-StrictMode -Version 2
if($PSVersionTable.PSVersion.Major -ne 5){throw 'PS51_REQUIRED'}
if(-not $CommonSource){$CommonSource=Join-Path $PSScriptRoot 'Invoke-HeaderEarlyStopProbe.ps1'}
if(Test-Path -LiteralPath $Evidence){throw 'FRESH_EVIDENCE_REQUIRED'}
$null=New-Item -ItemType Directory -Path $Evidence
. $CommonSource
$rows=New-Object Collections.Generic.List[object]
function Case([string]$Name,[scriptblock]$Body){
    $row=@{name=$Name;failure=$null};try{&$Body}catch{$row.failure=[string]$_};$rows.Add($row)
}
function Equal($A,$B){if($A -cne $B){throw ('NOT_EQUAL: '+$A+' / '+$B)}}
function Refuses([scriptblock]$Body,[string]$Code){
    try{&$Body|Out-Null}catch{if($_.Exception.ToString().Contains($Code)){return};throw}
    throw ('DID_NOT_REFUSE: '+$Code)
}
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($CommonSource,[ref]$tokens,[ref]$errors)
if($errors){throw 'SOURCE_PARSE_FAILED'}
$literal=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.StringConstantExpressionAst] -and $n.Value.Contains('public static class HeaderProbeNative')},$true))
if($literal.Count -ne 1){throw 'EXACT_CSHARP_SOURCE_REQUIRED'}
$cs=$literal[0].Value.Replace('HeaderProbeNative','SyntheticHeaderProbeNative')
$product=' [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiGetProductInfoExW(string p,string sid,uint c,string n,StringBuilder b,ref uint z);'
$related=' [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern uint MsiEnumRelatedProductsW(string p,uint r,uint i,StringBuilder b);'
$feature=' [DllImport("msi.dll",CharSet=CharSet.Unicode)] public static extern int MsiQueryFeatureStateW(string p,string f);'
foreach($declaration in @($product,$related,$feature)){if(-not $cs.Contains($declaration)){throw 'EXACT_NATIVE_SEAM_DECLARATION_REQUIRED'}}
$cs=$cs.Replace($product,@'
 public static uint Status=1605;public static int Queries,MachineQueries,MachineNulls,EmptySids;public static string LastSid;
 public static string NullKind(string sid){return sid==null?"NULL":sid.Length==0?"EMPTY":"EXPLICIT";}
 public static uint MsiGetProductInfoExW(string p,string sid,uint c,string n,StringBuilder b,ref uint z){
  Queries++;LastSid=sid;if(sid!=null&&sid.Length==0)EmptySids++;if(c==4){MachineQueries++;if(sid==null)MachineNulls++;}
  b.Append("5");return Status;
 }
'@).Replace($related,' public static uint MsiEnumRelatedProductsW(string p,uint r,uint i,StringBuilder b){return 259;}').Replace($feature,' public static int MsiQueryFeatureStateW(string p,string f){return -1;}')
Add-Type -TypeDefinition $cs
function Initialize-HeaderNative {}
$registration=@($ast.FindAll({param($n)$n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Get-HeaderRegistration'},$true))
if($registration.Count -ne 1){throw 'EXACT_REGISTRATION_CALLER_REQUIRED'}
. ([scriptblock]::Create($registration[0].Extent.Text.Replace('Get-HeaderRegistration','Get-SyntheticHeaderRegistration').Replace('[HeaderProbeNative]','[SyntheticHeaderProbeNative]')))
$productId='{11111111-2222-4333-8444-555555555555}'
Case 'PS51-null-string-conversion-is-demonstrated' {Equal ([SyntheticHeaderProbeNative]::NullKind($null)) 'EMPTY'}
Case 'managed-machine-helper-passes-native-null' {
    [SyntheticHeaderProbeNative]::Status=1605
    Equal ([SyntheticHeaderProbeNative]::MachineState($productId)) 'ABSENT'
    Equal ([SyntheticHeaderProbeNative]::MachineNulls) 1
}
Case 'PowerShell-empty-machine-sid-is-rejected-before-query' {
    $before=[SyntheticHeaderProbeNative]::Queries
    Refuses {[SyntheticHeaderProbeNative]::State($productId,$null,4)} 'MACHINE_NATIVE_NULL_SID_REQUIRED'
    Equal ([SyntheticHeaderProbeNative]::Queries) $before
}
Case 'actual-registration-caller-uses-managed-machine-helper' {
    [SyntheticHeaderProbeNative]::MachineQueries=0;[SyntheticHeaderProbeNative]::MachineNulls=0;[SyntheticHeaderProbeNative]::EmptySids=0
    $r=Get-SyntheticHeaderRegistration @{productCode=$productId;sourceProductCode='{99999999-8888-4777-8666-555555555555}';upgradeCode=$productId} 'S-1-5-21-111-222-333-1001'
    Assert-HeaderAbsent $r;Equal ([SyntheticHeaderProbeNative]::MachineQueries) 2
    Equal ([SyntheticHeaderProbeNative]::MachineNulls) 2;Equal ([SyntheticHeaderProbeNative]::EmptySids) 0
}
Case 'only1605-is-absence-and-other-status-keeps-safe-context' {
    foreach($rc in @(2,5,87,1610,1627)){
        [SyntheticHeaderProbeNative]::Status=[uint32]$rc
        Refuses {[SyntheticHeaderProbeNative]::MachineState($productId)} ('product='+$productId+' context=4 sidKind=NULL rc='+$rc)
    }
    [SyntheticHeaderProbeNative]::Status=0;Equal ([SyntheticHeaderProbeNative]::MachineState($productId)) '5'
}
$hello="Hello, I'm your 64bit Impersonated custom action server."
Case 'actual-engine-hello-line-is-recognized' {Equal (Test-HeaderCaContextMarker ($hello+"`r`n")) $true}
Case 'timestamped-engine-hello-line-is-recognized' {Equal (Test-HeaderCaContextMarker ("MSI (c) (48:A0) [23:54:36:120]: "+$hello+"`r`n")) $true}
Case 'full-engine-protocol-keeps-summary-api-error-distinct' {
    $detail='CCB_HEADER_REJECT phase=SUMMARY_TEMPLATE check=SUMMARY_PROPERTY group=0 group_ordinal=0 ordinal=7 last_api=MsiSummaryInfoGetPropertyW last_kind=1 last_code=1780 last_field=7 first_api=MsiSummaryInfoGetPropertyW first_kind=1 first_code=1780 first_field=7'
    $text="Action start 12:00:00: INSTALL.`r`nAction start 12:00:00: FindRelatedProducts.`r`nAction start 12:00:00: AppSearch.`r`nAction start 12:00:00: CcbHeaderDiagnosticOnly.`r`n$hello`r`nCCB_HEADER_PROBE_BEGIN contract=1`r`n$detail`r`nCCB_HEADER_PROBE_RESULT result=REJECT`r`nCCB_HEADER_PROBE_STOP result=1603`r`nAction ended 12:00:00: CcbHeaderDiagnosticOnly. Return value 3.`r`nAction ended 12:00:00: INSTALL. Return value 3.`r`nMainEngineThread is returning 1603`r`n"
    $r=Test-HeaderLog $text 1603;Equal $r.classification 'HEADER_REJECTION_OBSERVED';Equal $r.details[0] $detail
}
$negative=@(
    "Hello, I'm your 32bit Impersonated custom action server.",
    "Hello, I'm your 64bit NonImpersonated custom action server.",
    'Invoking 64bit Impersonated custom action server.',
    ('prefix '+$hello),
    "Hello, I'm your 64bit Impersonated custom action server",
    "Hello, I'm your 64 bit Impersonated custom action server.",
    "Hello, I'm your 64bit Impersonated custom action servers.",
    "Hello, I'm your 64bit Impersonated custom action server. extra",
    ($hello+"`r`n"+$hello),
    ($hello+"`r`nHello, I'm your 32bit Impersonated custom action server."),
    'CCB_HEADER_PROBE_BEGIN contract=1',
    ''
)
for($index=0;$index -lt $negative.Count;$index++){
    $value=$negative[$index];Case ('wrong-or-ambiguous-engine-marker-'+$index) {Equal (Test-HeaderCaContextMarker $value) $false}
}
$xml=New-Object Xml.XmlDocument;$suite=$xml.CreateElement('testsuite');$null=$xml.AppendChild($suite)
$failures=@($rows|Where-Object {$null -ne $_.failure}).Count
foreach($kv in @{name='HeaderNativeContractsPS51Synthetic';tests=[string]$rows.Count;failures=[string]$failures;errors='0';skipped='0'}.GetEnumerator()){$suite.SetAttribute($kv.Key,$kv.Value)}
foreach($r in $rows){$c=$xml.CreateElement('testcase');$c.SetAttribute('name',$r.name);$null=$suite.AppendChild($c);if($r.failure){$f=$xml.CreateElement('failure');$f.InnerText=$r.failure;$null=$c.AppendChild($f)}}
$xml.Save((Join-Path $Evidence 'TEST-HeaderNativeContracts.xml'))
@{tests=$rows.Count;failures=$failures;actualRegistryOrInstallerCalls=$false;nativePInvokesReplacedOnlyInTestAssembly=$true;
  commonSourceSha256=(Get-FileHash -LiteralPath $CommonSource -Algorithm SHA256).Hash.ToLowerInvariant();rows=@($rows.ToArray())}|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $Evidence 'RESULT.json') -Encoding UTF8
if($failures){exit 1}
