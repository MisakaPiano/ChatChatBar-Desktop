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
$report=Invoke-Existing1112HostFlow $commonCode $guestCode $policy $ProbeGuestDirectory $OriginalGuestDirectory ([bool]$ExecuteOnce) ([bool]$ConfirmInstallerInternalWrites)
$report|ConvertTo-Json -Depth 16
if($report.status -eq 'HOST_OR_GUEST_STOP_PRESERVE_NO_RETRY'){exit 2}
exit 0
