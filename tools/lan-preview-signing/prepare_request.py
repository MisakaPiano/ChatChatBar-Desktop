"""Local read-only APK signing preparation. The output contains public data only."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
# The local portable Python uses isolated module search paths.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from apk_v2_local import prepare, write_new, file_hash

CERTIFICATE = '801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
PACKAGE = 'com.example.chatbar.lansyncnext'
ROLES = {'base': ('base-input.apk', PACKAGE, 81),
         'upgrade': ('upgrade-input.apk', PACKAGE, 82),
         'tests': ('tests-input.apk', PACKAGE+'.test', None)}


def main(directory, artifact_path, keyfile, output):
    # A protected identity must never live under the checkout, even though it is encrypted.
    checkout = Path(__file__).resolve().parents[2]
    assert not keyfile.resolve().is_relative_to(checkout)
    manifest = json.loads((directory/'raw-manifest.json').read_text(encoding='utf8'))
    frozen = json.loads((directory/'source-request.json').read_text(encoding='utf8'))
    artifact = json.loads(artifact_path.read_text(encoding='utf8'))
    fields = {'id', 'runId', 'name', 'bytes', 'outerSha256', 'controlSha'}
    assert set(artifact) in (fields, fields | {'knownDiagnosticFailure'})
    if 'knownDiagnosticFailure' in artifact:
        assert artifact['knownDiagnosticFailure'] == 'INSTRUMENTATION_APK_VERSION_OMITTED'
        assert artifact['runId'] == 43330457689
        assert artifact['controlSha'] == 'a2ce60578f4f1aea08bf3ff481ed2cc6b738f65c'
    assert artifact['name'] == 'ccb-lan-next-signing-inputs'
    assert all(type(artifact[k]) is int and artifact[k] > 0 for k in ('id', 'runId', 'bytes'))
    assert re.fullmatch('[a-f0-9]{64}', artifact['outerSha256'])
    assert re.fullmatch('[a-f0-9]{40}', artifact['controlSha'])
    assert manifest['controlSha'] == artifact['controlSha']
    source = manifest['sourceSha']
    assert source == frozen['sourceSha'] and re.fullmatch('[a-f0-9]{40}', source)
    assert frozen['series'] == 'next' and frozen['versions'] == [81, 82]
    assert len(manifest['inputs']) == 3
    rows = {row['filename']: row for row in manifest['inputs']}
    assert set(rows) == {entry[0] for entry in ROLES.values()}
    entries = {}
    for role, (name, package, version) in ROLES.items():
        row = rows[name]; apk = directory/name
        assert row['package'] == package and apk.stat().st_size == row['bytes']
        assert file_hash(apk) == row['sha256']
        if version is not None:
            assert row['versionCode'] == version
        # Separate reopen/prepare calls must reproduce the same signature and final hash.
        first = prepare(apk, keyfile)
        second = prepare(apk, keyfile)
        assert first == second and first['certificateSha256'] == CERTIFICATE
        assert first['purpose'] == 'ISOLATED_LAN_NEXT_PREVIEW'
        if role == 'base' and frozen.get('previousDeliveredBase'):
            base = frozen['previousDeliveredBase']
            assert row['sha256'] == base['rawBaseSha256']
            assert first['outputSha256'] == base['deliveredBaseSha256']
        entries[role] = {'filename': name, 'publicSigning': first}
    request = {'kind': 'CCB_LAN_NEXT_PREVIEW_PUBLIC_SIGNING', 'sourceSha': source,
               'testFixtureOverlay': frozen.get('testFixtureOverlay'), 'previousDeliveredBase': frozen.get('previousDeliveredBase'),
               'originalArtifact': artifact, 'certificateSha256': CERTIFICATE,
               'entries': entries, 'sameIdentityAcrossReopen': True,
               'firstPreviewUpgradeCompatible': False, 'userDelivered': False}
    write_new(output, (json.dumps(request, indent=2)+'\n').encode('utf8'))
    print(json.dumps({'sourceSha': source, 'certificateSha256': CERTIFICATE,
                      'roles': {role: {'outputSha256': e['publicSigning']['outputSha256'],
                                       'bytes': e['publicSigning']['bytes']}
                                for role, e in entries.items()},
                      'privateKeyUploaded': False, 'localApkWritten': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ['input-dir', 'artifact-metadata', 'keyfile', 'output']:
        parser.add_argument('--'+flag, type=Path, required=True)
    args = parser.parse_args()
    try:
        main(args.input_dir, args.artifact_metadata, args.keyfile, args.output)
    except Exception:
        raise SystemExit('SIGN_INPUT_REFUSED') from None
