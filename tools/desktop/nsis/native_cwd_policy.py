"""Identity proof for the one temporary updater cwd allowed by an explicit native test mode."""
import hashlib
import json
from pathlib import Path


def owned_update_helper_cwd(program, executable, directory):
    program = Path(program).resolve(strict=True)
    runtime = program / 'app/desktop-updater'
    expected = runtime / 'python.exe'
    if not runtime.resolve(strict=True).is_relative_to(program):
        return False
    if expected.resolve(strict=True).parent != runtime.resolve(strict=True):
        return False
    if Path(executable).resolve(strict=True) != expected.resolve(strict=True):
        return False
    if Path(directory).resolve(strict=True) != runtime.resolve(strict=True):
        return False
    manifest = json.loads((program / 'install-manifest.json').read_text(encoding='utf-8'))
    matches = [row for row in manifest['files'] if row['path'] == 'app/desktop-updater/python.exe']
    if len(matches) != 1 or expected.stat().st_size != matches[0]['bytes']:
        return False
    digest = hashlib.sha256()
    with expected.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest() == matches[0]['sha256']
