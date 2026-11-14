"""DRAFT: prepare fixed LAN6 original metadata/request on Windows cloud only.

No application/native/upgrade work. Existing profile/API/ZIP/bundle/contract
owners do the verification. No request can choose another source or URL.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from types import MappingProxyType

HERE = Path(__file__).resolve().parent
HELPERS = {'contract.py': '53f01f7ff3190ee0c677e9221240f77c9e4ab8e95675d7d8533782bc09e98eb2', 'desktop_control.py': '6fcd6ace566b67e1693fd4b35781d8a1feda3fa8de618c758c165ad701be0cfb', 'prove_desktop_lan3_lan4.py': 'cbb40b2fd689e986957f7956cb521956425a7885063ddc7724ee5af227d3d686', 'desktop_lan5_control.py': '6694974cf280141d637f2c3f5359c5609db668f598d6ae00eb69d55191801199', 'desktop_lan6_control.py': 'b2fb329933250c9312248a20d984dcc308bd0b4c61f5a259df3872c63d9a2f55'}
ORDER = ('contract', 'desktop_control', 'prove_desktop_lan3_lan4', 'desktop_lan5_control', 'desktop_lan6_control')
KIND = 'CCB_FIXED_LAN6_ORIGINAL_METADATA_PREPARATION_V1'
REPOSITORY = 'MisakaPiano/ChatChatBar-Desktop'
REQUEST = '.github/lan-next-desktop-lan6-original-metadata-request.json'
EVIDENCE = 'desktop-lan6-original-metadata-evidence'
CANDIDATE = MappingProxyType({
    'series': 'lan6', 'version': '0.1.0-lan.6',
    'sourceSha': 'db25f8c61fdf237449286b13812a622c9a9d2120',
    'controlSha': '203c8efaf473f9b424b0403e5bcc4aa4da6d89f7',
    'runId': 43649177105, 'artifactId': 14135996049,
    'artifactName': 'ccb-lan5-lan6-independent-desktop',
    'outerBytes': 210273575,
    'outerSha256': '82e8e6acad6ea5595fbea15924cc1319e2705db3bafdddbc39611c498ed6f1e6',
    'filename': 'CCB-0.1.0-lan.6-independent-windows-x64.zip',
    'innerBytes': 210158363,
    'innerSha256': 'd0a69c96c0e7bd6c5baa10ce4e52ac4d7ce56e29c20158bb108bd9a5b5c5160f',
    'installerVersion': '0.1.40006', 'buildNumber': '40006',
})
OUTPUTS = ('desktop-package-manifest.original.json', 'source-request.original.json',
           'independent-receipt.original.json', 'upgrade-request.derived.json', 'validation.json')
JSON_LIMIT = 65536
_phase = 'PROFILE'


def require(condition, code):
    if not condition:
        raise ValueError(code)


def unique(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def json_bytes(raw):
    require(0 < len(raw) < JSON_LIMIT and b'\x00' not in raw, 'SMALL_JSON_BUDGET')
    return json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=unique)


def digest(path):
    require(path.is_file() and not path.is_symlink(), 'REGULAR_PINNED_FILE_REQUIRED')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_profile(directory=None):
    here = Path(directory) if directory is not None else HERE
    raw = {}
    for name in ORDER:
        path = here / (name + '.py')
        require(digest(path) == HELPERS[name + '.py'], 'TRUSTED_HELPER_CHANGED')
        raw[name] = path.read_bytes()
    previous = {name: sys.modules.get(name) for name in ORDER}
    modules = {}
    try:
        for name in ORDER:
            path = here / (name + '.py')
            spec = importlib.util.spec_from_file_location(name, path)
            module = importlib.util.module_from_spec(spec)
            sys.modules[name] = module
            exec(compile(raw[name], str(path), 'exec'), module.__dict__)
            modules[name] = module
        return modules['desktop_lan6_control'], modules['desktop_control']
    finally:
        for name, original in previous.items():
            if original is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = original


def expected_request():
    return {'kind': KIND, 'format': 1, 'candidate': dict(CANDIDATE)}


def read_request(path):
    require(path.is_file() and not path.is_symlink(), 'FIXED_REQUEST_FILE_REQUIRED')
    value = json_bytes(path.read_bytes())
    require(type(value) is dict and type(value.get('format')) is int and value == expected_request(),
            'EXACT_FIXED_REQUEST_REQUIRED')
    # Equality alone accepts bool==1; original numeric identity fields must be ints.
    require(all(type(value['candidate'][key]) is int for key in ('runId', 'artifactId', 'outerBytes', 'innerBytes')),
            'INTEGER_IDENTITY_REQUIRED')
    return value


def validate_metadata(base, artifact, run):
    base.metadata(dict(CANDIDATE), artifact, run)
    require(run.get('status') == 'completed' and type(run.get('run_attempt')) is int and run.get('run_attempt') == 1, 'EXACT_COMPLETED_ATTEMPT_REQUIRED')
    require(artifact['workflow_run']['head_sha'] == CANDIDATE['controlSha'], 'FIXED_ARTIFACT_CONTROL_REQUIRED')


def bounded_download(base, destination):
    total = 0
    # Exact existing redirect/auth owner; no external URL is accepted here.
    with base.original_stream(CANDIDATE['artifactId']) as response, destination.open('xb') as output:
        while block := response.read(64 * 1024):
            total += len(block)
            require(total <= CANDIDATE['outerBytes'], 'ORIGINAL_DOWNLOAD_SIZE')
            output.write(block)
    require(total == CANDIDATE['outerBytes'], 'ORIGINAL_DOWNLOAD_TRUNCATED')


def original_json(path):
    require(path.is_file() and not path.is_symlink(), 'ORIGINAL_JSON_REGULAR_REQUIRED')
    with path.open('rb') as source:
        raw = source.read(JSON_LIMIT)
    json_bytes(raw)
    return raw


def authenticate_candidate(base, artifact, run, case, control_root):
    """Private work only. Real runtime always supplies immutable CANDIDATE constants."""
    global _phase
    _phase = 'API_METADATA'
    validate_metadata(base, artifact, run)
    archive = case / 'candidate.zip'
    _phase = 'ORIGINAL_DOWNLOAD'
    bounded_download(base, archive)
    _phase = 'FULL_OUTER_INNER_ORIGINAL_VERIFICATION'
    verified = base.verify_outer_and_extract(archive, case / 'bundle', dict(CANDIDATE))
    bundle = (case / 'bundle' / verified['innerRelativePath']).parent
    _phase = 'FULL_ORIGINAL_BUNDLE_VALIDATION'
    manifest = base.validate_bundle(bundle)
    require(manifest['sourceSha'] == CANDIDATE['sourceSha'] and manifest['controlSha'] == CANDIDATE['controlSha'],
            'FIXED_MANIFEST_BINDING')
    _phase = 'FIXED_SOURCE_BINDINGS'
    require(base.source_bindings(control_root, CANDIDATE['sourceSha']) == manifest['timestampSourceBindings'],
            'ORIGINAL_TIMESTAMP_BINDINGS_CHANGED')
    _phase = 'EXISTING_UPGRADE_REQUEST_DERIVATION'
    derived = base.upgrade_request(bundle, artifact, run)  # re-runs validate_bundle; original contract unchanged
    require(derived['packages'][0] == base.BASE and derived['packages'][1] == dict(CANDIDATE),
            'ORIGINAL_DERIVED_IDENTITY_CHANGED')
    _phase = 'ORIGINAL_SMALL_JSON_READBACK'
    originals = {
        OUTPUTS[0]: original_json(bundle / 'desktop-package-manifest.json'),
        OUTPUTS[1]: original_json(bundle / 'source-request.json'),
        OUTPUTS[2]: original_json(bundle / (base.FILENAME[:-4] + '.json')),
    }
    # These are exact original bytes, not reconstructed from fields/summary.
    require(json_bytes(originals[OUTPUTS[0]]) == manifest, 'ORIGINAL_MANIFEST_READBACK_CHANGED')
    return originals, derived, {
        'kind': 'CCB_FIXED_LAN6_ORIGINAL_METADATA_AUTHENTICATED',
        'candidate': dict(CANDIDATE), 'apiRunAttempt': run['run_attempt'],
        'fullOuterBytesShaVerified': True, 'fullInnerBytesShaVerified': True,
        'outerNamesPathsLinksAndCrcVerified': verified['outerCrcPassed'],
        'fullOriginalBundleValidated': True, 'originalTimestampSourceBindingsVerified': True,
        'derivedByExistingUpgradeRequest': True, 'v5BaselineUnchanged': True,
        'v5ArtifactDownloaded': False, 'originalApplicationRebuilt': False,
        'nativeOrUpgradeExecutedByPreparer': False, 'humanAcceptance': False,
        'originalJsonDescriptors': {name: {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
                                    for name, raw in originals.items()},
        'originalOuterMembers': verified['outerMembers'],
        'artifactControlSha': CANDIDATE['controlSha'],
    }


def canonical(path, exists=True):
    path = Path(os.path.abspath(path))
    require(all(not p.is_symlink() for p in (path, *path.parents)), 'PATH_SYMLINK')
    require(path.resolve(strict=exists) == path, 'PATH_ALIAS')
    return path


def cloud_layout(base, request_path, evidence, root, environment):
    base.cloud()  # First operation: existing NT/GITHUB_ACTIONS/repository guard.
    require(environment.get('RUNNER_OS') == 'Windows', 'WINDOWS_CLOUD_ONLY')
    root = canonical(root)
    require(canonical(Path(environment['GITHUB_WORKSPACE'])) == root, 'CONTROL_WORKSPACE_REQUIRED')
    require(request_path.resolve(strict=True) == root / REQUEST and evidence.absolute() == root / EVIDENCE,
            'FIXED_REQUEST_AND_EVIDENCE_PATHS')
    require(not evidence.exists() and not evidence.is_symlink(), 'NEW_EVIDENCE_DIRECTORY_REQUIRED')
    sha = environment.get('GITHUB_SHA', '')
    require(len(sha) == 40 and all(c in '0123456789abcdef' for c in sha), 'CONTROL_SHA_INVALID')
    require(base.git_bytes(root, 'rev-parse', 'HEAD').decode().strip() == sha and
            base.git_bytes(root, 'status', '--porcelain', '--untracked-files=all').strip() == b'',
            'CLEAN_FROZEN_CONTROL_REQUIRED')
    temporary = canonical(Path(environment['RUNNER_TEMP']))
    require(not temporary.is_relative_to(root) and not root.is_relative_to(temporary), 'SEPARATE_PRIVATE_TEMP_REQUIRED')
    return temporary, sha


def cleanup_private_case(case, temporary):
    # A scope/alias failure affects isolation and must fail closed. An ordinary
    # removal failure of this already-checked new cloud root is a retained item.
    require(case.resolve(strict=True) == case and case.parent == temporary and
            case.name.startswith('ccb-lan6-original-metadata-') and not case.is_symlink(),
            'PRIVATE_CLEANUP_SCOPE_CHANGED')
    try:
        shutil.rmtree(case)
        return {'status': 'REMOVED', 'removed': True, 'scope': 'OWNED_RUNNER_TEMP_DIRECT_CHILD'}
    except OSError as failure:
        return {'status': 'RETAINED_CLEANUP_FAILED', 'removed': False,
                'scope': 'OWNED_RUNNER_TEMP_DIRECT_CHILD', 'exceptionType': type(failure).__name__}


def write_evidence(base, evidence, originals, derived, result, preparing_control):
    base.validate_upgrade(derived)
    result = dict(result, preparerControlSha=preparing_control, privateBundleRemoved=result.get("privateBundleRemoved", False),
                  allowedOutputFiles=list(OUTPUTS), requestDispatched=False,
                  phaseScope='Original metadata preparation only; actual retained-data native upgrade remains separate.')
    for value in (derived, result):
        json_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf8'))
    for name in OUTPUTS[:3]:
        json_bytes(originals[name])
    evidence.mkdir(exist_ok=False)
    for name in OUTPUTS[:3]:
        raw = originals[name]; json_bytes(raw)
        with (evidence / name).open('xb') as output:
            output.write(raw)
    # Existing serializer/validator derives the contract; never writes a live .github request.
    base.validate_upgrade(derived)
    base.write_new(evidence / OUTPUTS[3], derived)
    json_bytes((evidence / OUTPUTS[3]).read_bytes())
    base.write_new(evidence / OUTPUTS[4], result)
    for path in evidence.iterdir():
        require(path.name in OUTPUTS and path.is_file() and not path.is_symlink(), 'OUTPUT_ALLOWLIST')
        json_bytes(path.read_bytes())
    require(set(path.name for path in evidence.iterdir()) == set(OUTPUTS), 'EXACT_FIVE_SMALL_OUTPUTS_REQUIRED')


def prepare(profile, base, request_path, evidence):
    global _phase
    root = HERE.parents[1]
    _phase = 'CLOUD_LAYOUT'
    temporary, preparing_control = cloud_layout(base, request_path, evidence, root, os.environ)
    _phase = 'REQUEST'
    read_request(request_path)
    with profile.profile():
        _phase = 'FIXED_API_READS'
        artifact = base.api('artifacts/' + str(CANDIDATE['artifactId']))
        run = base.api('runs/' + str(CANDIDATE['runId']))
        validate_metadata(base, artifact, run)
        case = Path(tempfile.mkdtemp(prefix='ccb-lan6-original-metadata-', dir=temporary)).resolve(strict=True)
        require(case.parent == temporary and not case.is_symlink(), 'OWNED_PRIVATE_TEMP_REQUIRED')
        try:
            originals, derived, result = authenticate_candidate(base, artifact, run, case, root)
        finally:
            before_cleanup = _phase
            _phase = 'PRIVATE_BUNDLE_CLEANUP'
            cleanup = cleanup_private_case(case, temporary)
            _phase = before_cleanup
        result = dict(result, privateBundleCleanup=cleanup, privateBundleRemoved=cleanup['removed'])
        _phase = 'SMALL_EVIDENCE_ONLY'
        write_evidence(base, evidence, originals, derived, result, preparing_control)


def main(argv=None):
    global _phase
    try:
        profile, base = load_profile()
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument('mode', choices=['check-request', 'prepare'])
        parser.add_argument('--request', type=Path, required=True)
        parser.add_argument('--evidence', type=Path)
        args = parser.parse_args(argv)
        if args.mode == 'check-request':
            read_request(args.request)
            print('FIXED_LAN6_ORIGINAL_PREPARATION_REQUEST_ACCEPTED')
        else:
            require(args.evidence is not None, 'EVIDENCE_REQUIRED')
            prepare(profile, base, args.request, args.evidence)
        return 0
    except BaseException as failure:
        # HTTP/redirect exceptions can contain signed URLs. Never echo str/repr/cause/argv.
        print('LAN6_ORIGINAL_PREPARATION_REFUSED phase=' + _phase + ' type=' + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
