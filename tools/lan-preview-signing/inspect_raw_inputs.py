"""Read-only APK inspection; emit only public source/hash/manifest metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = 'com.example.chatbar.lansyncnext'
ROLES = {'base-input.apk': (PACKAGE, 81), 'upgrade-input.apk': (PACKAGE, 82),
         'tests-input.apk': (PACKAGE+'.test', None)}


def inspect(directory, control_sha, build_tools):
    assert re.fullmatch('[a-f0-9]{40}', control_sha)
    frozen = json.loads((directory/'source-request.json').read_text(encoding='utf8'))
    assert frozen['series'] == 'next' and frozen['versions'] == [81, 82]
    assert re.fullmatch('[a-f0-9]{40}', frozen['sourceSha'])
    rows = []
    for name, (package, version) in ROLES.items():
        apk = directory/name
        assert apk.is_file() and not apk.is_symlink()
        tool = build_tools/('aapt.exe' if os.name == 'nt' else 'aapt')
        text = subprocess.check_output([str(tool), 'dump', 'badging', str(apk)], text=True, encoding='utf8')
        match = re.search(r"^package: name='([^']+)' versionCode='([0-9]*)'", text, re.M)
        assert match and match.group(1) == package, name
        actual_version = int(match.group(2)) if match.group(2) else None
        if version is not None:
            assert actual_version == version, name
        assert "sdkVersion:'26'" in text
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        rows.append({'filename': name, 'package': package, 'versionCode': actual_version,
                     'sha256': digest, 'bytes': apk.stat().st_size})
    result = {'sourceSha': frozen['sourceSha'], 'controlSha': control_sha, 'inputs': rows,
              'testFixtureOverlay': frozen.get('testFixtureOverlay'),
              'persistentSigningApplied': False, 'deviceUpgradeTested': False, 'userDelivered': False}
    (directory/'raw-manifest.json').write_text(json.dumps(result, indent=2), encoding='utf8')
    print(json.dumps(result))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--control-sha', required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    args = parser.parse_args()
    inspect(args.directory, args.control_sha, args.build_tools)
