"""Cloud-only five-launch lan.3 -> lan.4 Program swap using frozen native proof owners."""
import argparse
import hashlib
import importlib.util
import os
from pathlib import Path
import uuid

from contract import load, require
from desktop_control import cloud, source_bindings, timestamp_exception_enabled, validate_upgrade

LEGACY = Path(__file__).resolve().parents[1] / 'lan-preview-signing/prove_desktop_profile_upgrade.py'


def legacy():
    spec = importlib.util.spec_from_file_location('frozen_desktop_profile_proof', LEGACY)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def retained_entities(old, request, before, after, bodies, directory, old_control):
    if not timestamp_exception_enabled(request):
        require(all(after.get(path) == digest for path, digest in before.items()), 'TIMESTAMP_SOURCE_CHANGED_NO_BYTE_WAIVER')
    return old.verify_retained_entities(before, after, bodies, directory, old_control)


def run(request, output):
    cloud(); validate_upgrade(request)
    require(load(output / 'source-request.json') == request and
            len(load(output / 'original-input-bytes.json')['packages']) == 2, 'ORIGINAL_DOWNLOAD_PROOF_REQUIRED')
    require(source_bindings(Path.cwd(), request['packages'][1]['sourceSha']) == request['timestampSourceBindings'], 'TIMESTAMP_SOURCE_CHANGED')
    old = legacy()
    temporary = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    case = temporary / ('ccb-lan3-lan4-profile-' + uuid.uuid4().hex); case.mkdir(); old.regular(case)
    require(case.resolve() == case and case.parent == temporary, 'CASE_SCOPE')
    root = case / 'independent'; replacement = case / 'replacement'
    counts = [old.package(row, target) for row, target in zip(request['packages'], (root, replacement))]
    require((root / '启动独立资料版.cmd').read_bytes() == (replacement / '启动独立资料版.cmd').read_bytes(), 'LAUNCHER_MISMATCH')
    outside = case / 'outside-profile'; environment = os.environ.copy()
    for key, leaf in [('LOCALAPPDATA', 'Local'), ('APPDATA', 'Roaming'), ('TEMP', 'Temp'), ('TMP', 'Temp')]:
        path = outside / leaf; path.mkdir(parents=True, exist_ok=True); environment[key] = str(path)
    old.write_json(outside / 'Local/ChatChatBarDesktop.bootstrap.json', {'formatVersion': 1, 'mode': 'DEFAULT'})
    outside_before = old.inventory(outside); native = old.Native(root); phases = []
    result = {'kind': 'EXACT_LAN3_LAN4_PROGRAM_ONLY_PROFILE_UPGRADE', 'passed': False, 'packages': request['packages'],
              'programFilesVerified': counts, 'phases': phases, 'snapshots': {}, 'seedInvocations': 0,
              'providerCalls': False, 'actualUserRootTouched': False, 'applicationRebuilt': False,
              'pixelsVerified': False, 'peerOrIdentitySeeded': False, 'humanAcceptance': False,
              'processDrainScope': 'EXECUTABLE_PATH_UNDER_OWN_PROGRAM', 'externalExecutableDescendantsVerified': False,
              'timestampExceptionEnabled': timestamp_exception_enabled(request), 'allowedStateMigrations': []}
    try:
        phases.append(native.launch('lan3-fresh', environment))
        data = root / 'UserData/Local/ChatChatBarDesktop'; require(data.is_dir(), 'INITIAL_DATA_ROOT')
        image = old.seed(data); result['seedInvocations'] += 1
        phases.append(native.launch('lan3-populated', environment)); old.verify_seed(data, image)
        control_before = old.inventory(data / 'entities'); control_bodies = old.json_bodies(data / 'entities', control_before)
        result['snapshots']['lan3-populated'] = control_before
        phases.append(native.launch('lan3-warm-control', environment)); old.verify_seed(data, image)
        before = old.inventory(data / 'entities'); bodies = old.json_bodies(data / 'entities', before); image_before = old.sha(image)
        result['snapshots']['lan3-warm-control'] = before
        result['oldWarmControlChanges'] = old.changes(control_before, before, control_bodies, data / 'entities')
        backup = root / 'Program-lan3-preserved'
        for path in (root, root / 'Program', replacement / 'Program'):
            old.regular(path); require(path.resolve().is_relative_to(case), 'PROGRAM_SWAP_SCOPE')
        require(not backup.exists() and not native.owned(), 'PROGRAM_SWAP_BUSY')
        (root / 'Program').rename(backup); (replacement / 'Program').rename(root / 'Program')

        def retain(stage):
            current = old.inventory(data / 'entities'); result['snapshots'][stage] = current
            result[stage + '-changes'] = old.changes(before, current, bodies, data / 'entities')
            result[stage + '-retention'] = retained_entities(old, request, before, current, bodies, data / 'entities', result['oldWarmControlChanges'])
            old.verify_seed(data, image)

        phases.append(native.launch('lan4-upgraded', environment)); retain('lan4-upgraded')
        phases.append(native.launch('lan4-cold-reopen', environment)); retain('lan4-cold-reopen')
        require(old.sha(image) == image_before == hashlib.sha256(old.PNG).hexdigest(), 'MEDIA_CHANGED')
        require(old.inventory(outside) == outside_before, 'OUTSIDE_PROFILE_CHANGED')
        require(len(phases) == 5 and result['seedInvocations'] == 1, 'EXACT_NATIVE_PHASES')
        result.update(passed=True, preexistingEntityFilesRetained=len(before), entityHashesBefore=before,
                      newEntityPaths=sorted(set(result['snapshots']['lan4-cold-reopen']) - set(before)),
                      absoluteMediaReferenceUnchanged=True, imageSha256=image_before, userDataRootNotMoved=True,
                      oldProgramPreserved=backup.is_dir(), outsideProfileInventoryUnchanged=True, syntheticOnly=True)
    except Exception as failure:
        result['failure'] = {'type': type(failure).__name__, 'message': str(failure)}; raise
    finally:
        old.write_json(output / 'native-lan3-lan4-upgrade-result.json', result)
    print('LAN3_LAN4_NATIVE_UPGRADE_PASS: five native launches; one old-version seed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); run(load(args.request), args.output)
