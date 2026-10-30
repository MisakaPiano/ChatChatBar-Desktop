"""Attach only the audited updater runtime and public bootstrap to a build image."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from audit_app_image import regular


def files(runtime):
    regular(runtime, directory=True)
    receipt = json.loads((runtime / 'RUNTIME_MANIFEST.json').read_text(encoding='utf-8'))
    known = {}
    for entry in receipt['files']:
        path = runtime / entry['path']
        if not path.resolve().is_relative_to(runtime.resolve()): raise ValueError('RUNTIME_PATH_ESCAPE')
        regular(path)
        if path.stat().st_size != entry['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('RUNTIME_CHANGED')
        known[entry['path']] = path
    actual = {p.relative_to(runtime).as_posix() for p in runtime.rglob('*') if p.is_file()}
    if actual != set(known) | {'RUNTIME_MANIFEST.json'}: raise ValueError('RUNTIME_UNKNOWN_FILE')
    return known


def attach(image, runtime, root, version, commit):
    image, runtime = image.absolute(), runtime.absolute()
    regular(image, directory=True)
    known = files(runtime)
    target = image / 'app' / 'desktop-updater'
    if target.exists():
        # Only verified prior build files in this exact generated image location may be replaced.
        prior = json.loads((target / 'RUNTIME_MANIFEST.json').read_text(encoding='utf-8'))
        prior_files = {e['path']: e for e in prior['files']}
        expected = set(prior_files) | {'RUNTIME_MANIFEST.json', 'update-root.json', 'update-config.json'}
        actual = {p.relative_to(target).as_posix() for p in target.rglob('*') if p.is_file()}
        if actual != expected: raise ValueError('PRIOR_RUNTIME_UNKNOWN_FILE')
        for name, entry in prior_files.items():
            path = target / name; regular(path)
            if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']: raise ValueError('PRIOR_RUNTIME_CHANGED')
        for name in expected:
            path = target / name; regular(path); path.unlink()
        for directory in sorted((p for p in target.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
            regular(directory, directory=True); directory.rmdir()
        target.rmdir()
    target.mkdir()
    for name, source in known.items():
        destination = target / name; destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    shutil.copyfile(runtime / 'RUNTIME_MANIFEST.json', target / 'RUNTIME_MANIFEST.json')
    shutil.copyfile(root, target / 'update-root.json')
    (target / 'update-config.json').write_text(json.dumps(dict(format=1, product='ccb-desktop-nsis-v1',
        platform='windows-x86_64', channel='preview', version=int(version.removeprefix('0.1.0-preview.')),
        displayVersion=version, sourceSha=commit, reader=1, rootSha256=hashlib.sha256(root.read_bytes()).hexdigest()), indent=2), encoding='utf-8')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('image', 'runtime', 'root'): p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('--version', required=True); p.add_argument('--commit', required=True)
    a = p.parse_args(); attach(a.image, a.runtime, a.root, a.version, a.commit)
    print('DESKTOP_UPDATER_ATTACHED')
