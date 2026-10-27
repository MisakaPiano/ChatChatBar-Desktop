param([switch]$PreflightOnly,[switch]$ConfirmSyntheticProductOnly)
$ErrorActionPreference='Stop'
$repo='H:\ChatChatBar-Desktop'
$base=Join-Path $repo 'app\desktopApp\build\preview-continuous-private-20261025\windows-runtime-corrected-20261025'
$msi=Join-Path $repo 'app\desktopApp\build\preview-continuous-private-20261025\windows-package-corrected-a-20261025\ChatChatBarDesktopSynthetic-0.1.1002.msi'
$probe=Join-Path $repo 'tools\desktop\windows\native_package_probe.py'
$python='C:\Users\1\AppData\Local\Programs\Python\Python310\python.exe'
if((Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash -ne 'D11C3BA17E819014A241619FDCAAA8E70D64B116B2E43F022DDEC15A0787E18D'){throw 'REVIEWED_PROBE_CHANGED'}
if((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ne '388F4E8F83377F17BC1194940EC77A7E5FEDA394D3BF585B570B44AFF42B6197'){throw 'REVIEWED_SYNTHETIC_MSI_CHANGED'}
$owner=Get-Content -LiteralPath (Join-Path $base 'TASK_OWNER.json') -Raw | ConvertFrom-Json
if($owner.kind -ne 'CCB_DESKTOP_SYNTHETIC_WINDOWS_RUNTIME'){throw 'SYNTHETIC_TASK_REQUIRED'}
$infoText=& $python $probe info --installer $msi
if($LASTEXITCODE -ne 0){throw 'READONLY_MSI_IDENTITY_CHECK_FAILED'}
$info=$infoText | ConvertFrom-Json
if($info.ProductName -ne 'ChatChatBarDesktopSynthetic' -or $info.ProductCode -ne '{F73ABD7F-5CA1-3C9F-B00A-20F2A58E50B4}' -or $info.UpgradeCode -ne '{58E95FD1-709D-5813-9B5B-F518A4612BAE}' -or $info.ProductVersion -ne '0.1.1002' -or $info.safetyVersion -ne '1'){throw 'EXACT_SYNTHETIC_PRODUCT_REQUIRED'}
$admin=([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if($PreflightOnly){@{exactSyntheticProductVerified=$true;administratorToken=$admin;installerExecuted=$false}|ConvertTo-Json;exit 0}
if(-not $ConfirmSyntheticProductOnly){throw '必须明确选择本次合成产品修复；尚未执行安装器。'}
if(-not $admin){throw '需要你在管理员 PowerShell 中执行此一次合成产品修复。本脚本不会自行提权或重试。'}
# The existing bounded probe enforces exact retained MSI/registration identity and
# unchanged program file sets/bytes; no user data/SecretStore/DPAPI or reboot path.
& $python $probe repair-registration --base $base --installer $msi --label admin-user-repair-once-20261025
exit $LASTEXITCODE
