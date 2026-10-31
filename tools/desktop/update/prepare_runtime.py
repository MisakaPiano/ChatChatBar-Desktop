"""Assemble an isolated updater using explicitly hash-locked public artifacts."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import os
import subprocess
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent


def prepare(destination, artifact_cache, download=False):
    if destination.exists() and any(destination.iterdir()):
        raise FileExistsError('NEW_RUNTIME_DESTINATION_REQUIRED')
    lock = json.loads((HERE / 'artifacts.json').read_text(encoding='utf-8'))
    artifact_cache.mkdir(parents=True, exist_ok=True)
    sources = []
    for item in lock['artifacts']:
        path = artifact_cache / item['fileName']
        if not path.exists() and download:
            partial = path.with_suffix(path.suffix + '.partial')
            request = urllib.request.Request(item['sourceUrl'], headers={'User-Agent': 'ChatChatBarDesktop-Build'})
            with urllib.request.urlopen(request, timeout=60) as source, partial.open('xb') as target:
                received = 0
                while block := source.read(1024 * 1024):
                    received += len(block)
                    if received > item['sizeBytes']:
                        raise ValueError('ARTIFACT_LENGTH_REFUSED')
                    target.write(block)
            if partial.stat().st_size != item['sizeBytes'] or hashlib.sha256(partial.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('ARTIFACT_HASH_REFUSED')
            partial.rename(path)
        if path.stat().st_size != item['sizeBytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('ARTIFACT_IDENTITY_REFUSED')
        sources.append((item, path))
    destination.mkdir(parents=True, exist_ok=True)
    for item, path in sources:
        base = destination if item['kind'] == 'runtime' else destination / 'lib'
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                relative = PurePosixPath(entry.filename)
                if relative.is_absolute() or '..' in relative.parts or '\\' in entry.filename or ':' in entry.filename or stat.S_ISLNK(entry.external_attr >> 16):
                    raise ValueError('ARTIFACT_ENTRY_REFUSED')
                target = base.joinpath(*relative.parts)
                if entry.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with target.open('xb') as stream:
                        stream.write(archive.read(entry))
    (destination / 'python313._pth').write_text('python313.zip\n.\nlib\n', encoding='utf-8')
    for name in ('desktop_update.py', 'github_transport.py'):
        shutil.copyfile(HERE / name, destination / 'lib' / name)
    compiler = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    subprocess.run([str(compiler), '/nologo', '/target:winexe', '/platform:x64', '/reference:System.Windows.Forms.dll',
                    '/out:' + str(destination / 'update-handoff.exe'), str(HERE / 'UpdateHandoff.cs')], check=True)
    files = [{'path': p.relative_to(destination).as_posix(), 'bytes': p.stat().st_size,
              'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(destination.rglob('*')) if p.is_file()]
    (destination / 'RUNTIME_MANIFEST.json').write_text(json.dumps({'artifacts': lock['artifacts'], 'files': files}, indent=2), encoding='utf-8')
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--artifact-cache', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    print(json.dumps({'files': len(prepare(args.destination, args.artifact_cache, args.download))}))
