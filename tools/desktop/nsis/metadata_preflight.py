"""Cloud-only tiny Windows path/handle metadata check; no app launch or user data."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
import audit_app_image


def require(value, code):
    if not value:
        raise RuntimeError(code)


def inspect(path):
    def fields(value):
        return {key: getattr(value, key) for key in
                ['st_dev', 'st_ino', 'st_size', 'st_mtime_ns', 'st_ctime_ns', 'st_birthtime_ns']
                if hasattr(value, key)}
    before = path.stat(follow_symlinks=False)
    with path.open('rb') as stream:
        opened = os.fstat(stream.fileno())
        stream.read()
        after = os.fstat(stream.fileno())
    path_after = path.stat(follow_symlinks=False)
    return dict(python=sys.version.split()[0], before=fields(before), opened=fields(opened),
                after=fields(after), pathAfter=fields(path_after),
                rawCrossApiEqual=audit_app_image.stamp(before) == audit_app_image.stamp(opened),
                pathStable=audit_app_image.stamp(before) == audit_app_image.stamp(path_after),
                handleStable=audit_app_image.stamp(opened) == audit_app_image.stamp(after),
                sameIdentity=len({audit_app_image.identity_stamp(value)
                                  for value in [before, opened, after, path_after]}) == 1)


def main():
    require(os.name == 'nt' and os.environ.get('GITHUB_ACTIONS') == 'true', 'WINDOWS_CI_REQUIRED')
    require(os.environ.get('GITHUB_REPOSITORY') == 'MisakaPiano/ChatChatBar-Desktop', 'REPOSITORY_REQUIRED')
    base = Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True)
    require(base.parent == Path(base.anchor) and base.name.startswith('ccbpack-'), 'OWNED_BASE_REQUIRED')
    sample = base / 'synthetic-metadata-preflight.bin'
    if len(sys.argv) == 3 and sys.argv[1] == '--inspect':
        require(Path(sys.argv[2]).resolve(strict=True) == sample, 'SAMPLE_SCOPE')
        print(json.dumps(inspect(sample)))
        return
    require(len(sys.argv) == 1, 'ARGUMENTS_REFUSED')
    with sample.open('xb') as stream:
        stream.write(b'SYNTHETIC')
    time.sleep(.05)
    with sample.open('ab') as stream:
        stream.write(b'_MODIFIED')
    results = []
    for executable in [os.environ['CCB_LEGACY_PYTHON'], sys.executable]:
        run = subprocess.run([executable, '-B', str(Path(__file__).resolve()), '--inspect', str(sample)],
                             check=True, capture_output=True, text=True)
        results.append(json.loads(run.stdout))
    report = dict(kind='SYNTHETIC_PATH_HANDLE_METADATA_IDENTITY', results=results)
    (Path(os.environ['CCB_BUILD_OUTPUT']) / 'metadata-preflight.json').write_text(
        json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
    require(all(row['pathStable'] and row['handleStable'] and row['sameIdentity'] for row in results),
            'PACKAGING_INTERPRETER_METADATA_INCONSISTENT')


if __name__ == '__main__':
    main()
