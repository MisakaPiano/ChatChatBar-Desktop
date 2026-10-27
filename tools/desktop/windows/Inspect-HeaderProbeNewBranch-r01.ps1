param([string]$BaselineDirectory=(Join-Path $PSScriptRoot 'BASELINE_1112'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'HeaderProbeBranchPreflight-r01.ps1')
$result=Get-HeaderBranchPreflight $BaselineDirectory
$result|ConvertTo-Json -Depth 12|Write-Output
if(-not $result.creationPreconditionsObserved){exit 2}
Write-Output 'READONLY_PREFLIGHT_ONLY_NO_VM_CREATED_OR_STARTED'
exit 0
