"""Freeze an offline, not-yet-authorized diagnostic delivery. No VM/MSI execution."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile
import msi_safety as m
import build_header_probe_package as p

README = '''# Header early-stop probe: PREPARED / EXECUTION NOT AUTHORIZED

This is an unpublished synthetic diagnostic, NOT an installation candidate or a
read-only MSI operation. Do not run it until the user confirms a NEW independent
Windows 10 Pro 19045 x64 guest branch and its exact host-observed VM ID.
This preparation creates no VM and never invokes Windows Installer.

Preserve the original1112 failure guest, original1102 copy, old packages, caches,
logs and checkpoints. Neither those guests nor DD1/DL1 hosts are eligible. The
guest script refuses the three known preserved VM IDs, but a guest model or an
ID typed by a person is NOT cryptographic proof of the host VM identity. The
future operator must check that exact new ID in Hyper-V before any execution.
Do not reuse old remote sessions. No VM-creation script is included.

Reproduction prerequisite: copy/branch the1112 failure state independently, or
explicitly document a different clean-start state. The guest needs the three
existing files under %LOCALAPPDATA%\\CCB-OP1-First-1112-r01:
COMMAND.json, RESULT.json, install-1112.log. The new script only reads/copies these
three named files. They are private local evidence and must never be uploaded.
Missing baseline or unknown registration causes a stop, not another installer run.

After separate run confirmation, the fixed Run-HeaderProbe-Once.ps1 entry accepts
only -ConfirmNewIndependentGuest and -HostConfirmedVmId (the checked new GUID).
Do not pass msiexec arguments, rename/replace files or rerun after any error. The
wrapper pins DELIVERY_IDENTITY.json before sourcing its hashed helper. Verify-
HeaderProbe-Bundle.ps1 checks delivery hashes only and never starts MSI. Outer ZIP
SHA pins the wrapper/verifier themselves; these are local integrity checks, not
an OS security boundary or trusted publisher signature.

Exact execute sequence: FindRelatedProducts25, downgrade refusal26, AppSearch50,
CcbHeaderDiagnosticOnly60 (Type1, synchronous impersonated), unconditional Type19
insurance stop61. All four UI/admin/advertisement sequences contain only a Type19
refusal. No costing, InstallInitialize, old removal, payload/Registry/shortcut
write or delete, registration/publication, application launch or finalization.
The dedicated DLL always returns1603 after Header PASS, REJECT or precondition
failure. Insurance61 being reached is an invalid diagnostic result.

Windows Installer can still cache/process MSI, extract a temporary DLL, create
internal source/processing Registry state, logs/events/restore metadata and hold
mutexes BEFORE CA60. PowerShell/Add-Type and this collector create local files.
Do not claim zero writes or rollback. Do not clean/uninstall afterward. A timeout
does not kill the installer; preserve the VM and report once.

Expected report: %LOCALAPPDATA%\\CCB-Header-EarlyStop-1112-r01\\RESULT.json,
probe.log, COMMAND.json, plus byte-checked copies of the three baseline files.
1603 alone is insufficient. BEGIN, one Header result, STOP, actual64bit
impersonated CA marker, completed failure log and absence of all other action
starts are mandatory. PASS means UNREPRODUCED_INCONCLUSIVE, never installation
accepted; REJECT includes fixed phase/check/API return details. Only explicitly
queried synthetic product/family/feature state is observed before/after, not the
whole Installer inventory or global Windows state. No retry, uninstall, cleanup,
data root, SecretStore, DPAPI, Provider or user backup input.

Changed from1112: fresh ProductCode and PackageCode; test-only Property marker;
Binary/entry; CustomAction and all five sequence tables; exact table/stream seals.
Full File/Component/Registry/Directory graphs, Icon and CAB bytes are retained.
Version remains0.1.1112 to retain that dataset; product name/family remain the
synthetic family. The new code/hash/identity/sequence/server initialization may
change the failure. Original-context equivalence is NOT PROVEN. A Header PASS
does not establish an1112 fix, first install, maintenance, upgrade or rollback.
The normal production author policy deliberately rejects this diagnostic profile.

This round ends at a frozen PREPARATION checkpoint. No transfer, run, host/VM
change, push, release or cleanup is authorized by this README.
'''


def proof(path):
    return {'name': path.name, 'bytes': path.stat().st_size, 'sha256': m.sha(path)}


def build(package_directory, output):
    package_directory, output = (Path(v).resolve() for v in (package_directory, output))
    assert output.drive.upper() == 'I:' and not output.exists() and not output.with_suffix('.zip').exists(), 'FRESH_I_DELIVERY_REQUIRED'
    for path in (output, package_directory):
        assert not any(v.is_symlink() or (v.exists() and v.stat().st_file_attributes & 0x400) for v in [path, *path.parents])
    identity = json.loads((package_directory / 'PROBE_IDENTITY.json').read_text(encoding='utf-8'))
    package = package_directory / identity['fileName']
    assert m.sha(package) == identity['sha256'] and package.stat().st_size == identity['bytes']
    with m.Database(package) as db:
        p.validate(db, identity['dll'])
    output.mkdir()
    for source in (package, package_directory / 'PROBE_IDENTITY.json', Path(__file__).with_name('Invoke-HeaderEarlyStopProbe.ps1')):
        shutil.copyfile(source, output / source.name)
    (output / 'READ_ME_FIRST.md').write_text(README, encoding='utf-8')
    sources = []
    for name in ('directory_guard.c', 'operation_guard.h', 'header_probe_only.h', 'build_header_probe.py',
                 'build_header_probe_package.py', 'build_header_probe_bundle.py', 'Invoke-HeaderEarlyStopProbe.ps1',
                 'Test-HeaderEarlyStopProbe.ps1', 'test_header_probe_only.py'):
        path = Path(__file__).with_name(name)
        sources.append({**proof(path), 'gitBlob': subprocess.check_output(['git', 'hash-object', str(path)], text=True).strip()})
    (output / 'BUILD_PROVENANCE.json').write_text(json.dumps({'sources': sources,
        'baseCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'sourceTreeHasUncommittedProbePreparation': True, 'original1112Sha256': p.SOURCE_SHA,
        'realCaExecuted': False, 'vmChanged': False, 'pushed': False}, indent=2) + '\n', encoding='utf-8')
    manifest = output / 'DELIVERY_IDENTITY.json'
    manifest.write_text(json.dumps({'profile': 'HEADER_EARLY_STOP_ONLY_NOT_INSTALL',
        'files': [proof(f) for f in sorted(output.iterdir())]}, indent=2) + '\n', encoding='utf-8')
    digest = m.sha(manifest)
    prefix = "param([switch]$ConfirmNewIndependentGuest,[string]$HostConfirmedVmId)\n$ErrorActionPreference='Stop'\n"
    common = "$expected='" + digest + "'\n$manifest=Join-Path $PSScriptRoot 'DELIVERY_IDENTITY.json'\n" + \
        "if((Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected){throw 'FIXED_MANIFEST_MISMATCH'}\n" + \
        "$identity=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json\n" + \
        "foreach($row in $identity.files){if($row.name -notmatch '^[A-Za-z0-9_.-]+$'){throw 'MANIFEST_LEAF_REQUIRED'};" + \
        "$path=Join-Path $PSScriptRoot $row.name;$item=Get-Item -LiteralPath $path -Force;" + \
        "if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -ne $row.bytes -or " + \
        "(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $row.sha256){throw 'DELIVERY_FILE_MISMATCH'}}\n"
    # No implicit execution when the helper is sourced. Only this explicit entry runs.
    (output / 'Run-HeaderProbe-Once.ps1').write_text(prefix + common +
        "if(-not $ConfirmNewIndependentGuest){throw 'EXPLICIT_NEW_GUEST_CONFIRMATION_REQUIRED'}\n" +
        ". (Join-Path $PSScriptRoot 'Invoke-HeaderEarlyStopProbe.ps1')\n" +
        "Invoke-HeaderProbeOnce $PSScriptRoot $expected $HostConfirmedVmId ([bool]$ConfirmNewIndependentGuest)|ConvertTo-Json -Depth 14\n", encoding='ascii')
    (output / 'Verify-HeaderProbe-Bundle.ps1').write_text("$ErrorActionPreference='Stop'\n" + common +
        "Write-Output 'FROZEN_HEADER_PROBE_DELIVERY_VERIFIED_NO_INSTALL_OR_VM_CHANGE'\n", encoding='ascii')
    archive = output.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        for path in sorted(output.iterdir()):
            z.write(path, path.name)
    receipt = {'files': [proof(f) for f in sorted(output.iterdir())], 'archive': proof(archive),
        'executionAuthorized': False, 'actualMsiExecuted': False, 'vmChanged': False, 'pushed': False}
    (output.parent / (output.name + '-FREEZE.json')).write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.package_directory, args.output), indent=2))
