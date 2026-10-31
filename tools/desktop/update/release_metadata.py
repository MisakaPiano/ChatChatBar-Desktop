"""Offline Desktop metadata signing. Private role keys must remain outside Git.

Run with the locked updater Python runtime. This tool never uploads anything.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import stat
import subprocess
from pathlib import Path
import re
from securesystemslib.signer import CryptoSigner
from tuf.api.metadata import Metadata, Root, Targets, TargetFile, Snapshot, Timestamp, MetaFile

ROLES = ('root', 'timestamp', 'snapshot', 'targets')
REPOSITORY = Path(__file__).resolve().parents[3]


def external_keys(path):
    path = Path(os.path.abspath(path))
    for ancestor in (path, *path.parents):
        if ancestor.exists():
            info = ancestor.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                raise ValueError('SIGNING_CUSTODY_REPARSE_REFUSED')
    if path == REPOSITORY or path.is_relative_to(REPOSITORY):
        raise ValueError('PRIVATE_KEYS_MUST_STAY_OUTSIDE_REPOSITORY')
    return path


def initialize(keys, root_path):
    keys = external_keys(keys)
    keys.mkdir(parents=True, exist_ok=True)
    if any(keys.iterdir()) or root_path.exists():
        raise ValueError('NEW_KEY_CUSTODY_AND_ROOT_REQUIRED')
    if os.name == 'nt':
        script = r'''
$ErrorActionPreference='Stop'
$acl=New-Object Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
$inherit=[Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
$trusted=@([Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')),(New-Object Security.Principal.SecurityIdentifier('S-1-5-32-544')))
foreach($sid in $trusted){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl',$inherit,'None','Allow')))}
Set-Acl -LiteralPath $env:CCB_SIGNING_CUSTODY -AclObject $acl
'''
        subprocess.run(['powershell.exe', '-NoProfile', '-Command', script],
                       env=dict(os.environ, CCB_SIGNING_CUSTODY=str(keys),
                                PSModulePath=str(Path(os.environ.get('WINDIR', 'C:/Windows')) / 'System32/WindowsPowerShell/v1.0/Modules')),
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        keys.chmod(0o700)
    root = Metadata(Root(version=1, consistent_snapshot=False,
                         expires=datetime.now(timezone.utc) + timedelta(days=1095)))
    signers = {role: CryptoSigner.generate_ed25519() for role in ROLES}
    for role, signer in signers.items():
        with os.fdopen(os.open(keys / (role + '.pem'), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as output:
            output.write(signer.private_bytes)
        root.signed.add_key(signer.public_key, role)
    root.sign(signers['root'])
    root_path.parent.mkdir(parents=True, exist_ok=True)
    root_path.write_bytes(root.to_bytes())
    return hashlib.sha256(root_path.read_bytes()).hexdigest()


def publish(keys, root_path, installer, version, commit, notes, output, revision, manifest):
    keys = external_keys(keys)
    if output.exists() or not re.fullmatch('[0-9a-f]{40}', commit) or version < 1 or revision < 1:
        raise ValueError('NEW_SIGNED_OUTPUT_REQUIRED')
    root = Metadata.from_file(str(root_path))
    signers = {role: CryptoSigner.from_priv_key_uri('file2:' + str(keys / (role + '.pem')),
               root.signed.keys[root.signed.roles[role].keyids[0]]) for role in ROLES}
    now = datetime.now(timezone.utc)
    display = f'0.1.0-preview.{version}'
    inventory = json.loads(manifest.read_text(encoding='utf-8'))
    if inventory['product'] != 'ccb-desktop-nsis-v1' or inventory['version'] != display or inventory['commit'] != commit:
        raise ValueError('SIGNED_INSTALLER_MANIFEST_IDENTITY_REFUSED')
    installed_bytes = sum(e['bytes'] for e in inventory['files'])
    if not 0 < installed_bytes <= 4 * 1024**3: raise ValueError('SIGNED_INSTALLER_SIZE_REFUSED')
    target = TargetFile.from_file('desktop-installer.exe', str(installer), ['sha256'])
    target.unrecognized_fields['custom'] = dict(product='ccb-desktop-nsis-v1', platform='windows-x86_64',
        channel='preview', version=version, displayVersion=display, sourceSha=commit, minimum_reader=1,
        maximum_reader=1, data_version=1, installedBytes=installed_bytes, notes=notes.read_text(encoding='utf-8'),
        github=dict(tag='desktop-v' + display, asset=f'ChatChatBar-Desktop-{display}-windows-x64.exe'))
    targets = Metadata(Targets(version=revision, expires=now + timedelta(days=180), targets={target.path: target}))
    targets.sign(signers['targets'])
    targets_bytes = targets.to_bytes()
    snapshot = Metadata(Snapshot(version=revision, expires=now + timedelta(days=90),
        meta={'targets.json': MetaFile.from_data(revision, targets_bytes, ['sha256'])}))
    snapshot.sign(signers['snapshot'])
    snapshot_bytes = snapshot.to_bytes()
    timestamp = Metadata(Timestamp(version=revision, expires=now + timedelta(days=30),
        snapshot_meta=MetaFile.from_data(revision, snapshot_bytes, ['sha256'])))
    timestamp.sign(signers['timestamp'])
    output.mkdir(parents=True)
    for name, value in [('root.json', root_path.read_bytes()), ('1.root.json', root_path.read_bytes()),
                        ('targets.json', targets_bytes), ('snapshot.json', snapshot_bytes), ('timestamp.json', timestamp.to_bytes())]:
        (output / name).write_bytes(value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--keys', type=Path, required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--initialize', action='store_true')
    parser.add_argument('--installer', type=Path)
    parser.add_argument('--version', type=int)
    parser.add_argument('--commit')
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--revision', type=int)
    parser.add_argument('--manifest', type=Path)
    args = parser.parse_args()
    if args.initialize:
        print('PUBLIC_ROOT_SHA256=' + initialize(args.keys, args.root))
    else:
        publish(args.keys, args.root, args.installer, args.version, args.commit, args.notes, args.output, args.revision, args.manifest)
        print('SIGNED_METADATA_READY')
