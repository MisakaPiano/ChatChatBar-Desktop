param([switch]$InspectOnly,[switch]$ExecuteOnce,[switch]$ConfirmInstallerInternalWrites,
    [string]$ProbeGuestDirectory='C:\CCB-HEADER-PROBE-DL1-20261027-r02',
    [string]$OriginalGuestDirectory='C:\CCB-OP1-DL1-20261027-r01')
$ErrorActionPreference='Stop'
if(([bool]$InspectOnly -eq [bool]$ExecuteOnce) -or ($ExecuteOnce -and -not $ConfirmInstallerInternalWrites)){
    throw 'SELECT_INSPECT_ONLY_OR_SEPARATELY_APPROVED_EXECUTE_ONCE'
}
# The frozen attachment wrapper verifies this entry, both helper sources and the
# private baseline policy before invoking it. This entry is not for direct use.
if($env:CCB_1112_HEADER_VERIFIED_ENTRY -ne 'IN_PROCESS_FROZEN_WRAPPER') {throw 'USE_FIXED_ATTACHMENT_ENTRY'}
$commonPath=Join-Path $PSScriptRoot 'Invoke-HeaderEarlyStopProbe.ps1'
$guestPath=Join-Path $PSScriptRoot 'HeaderExisting1112Guest.ps1'
. $commonPath
. (Join-Path $PSScriptRoot 'HeaderExisting1112Host.ps1')
$policy=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'BASELINE_EXPECTED.json') -Raw|ConvertFrom-Json
$commonCode=Get-Content -LiteralPath $commonPath -Raw
$guestCode=Get-Content -LiteralPath $guestPath -Raw
try{
    $report=Invoke-Existing1112HostFlow $commonCode $guestCode $policy $ProbeGuestDirectory $OriginalGuestDirectory ([bool]$ExecuteOnce) ([bool]$ConfirmInstallerInternalWrites)
    if($report.succeeded -isnot [bool]){throw 'HOST_REPORT_SUCCESS_FLAG_INVALID'}
    $report|ConvertTo-Json -Depth 18
    if(-not $report.succeeded){exit 2}
    exit 0
}catch{
    @{status='ENTRY_STOP_PRESERVE_NO_RETRY';succeeded=$false;error=[string]$_.Exception.Message}|ConvertTo-Json -Depth 4
    exit 2
}
