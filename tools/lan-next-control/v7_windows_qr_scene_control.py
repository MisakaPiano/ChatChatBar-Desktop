"""Focused Windows V7 retained group and real Compose QR scene control."""
import argparse
import hashlib
import os
import subprocess
from pathlib import Path
import uuid

import desktop_lan8_control as retained

HERE = Path(__file__).resolve().parent
FIXTURE = HERE / 'fixtures/DesktopV7RetainedQrSceneControlTest.kt'
CLASS = 'com.example.chatbar.desktop.lanSync.DesktopV7RetainedQrSceneControlTest'
METHOD = 'originalRecoveryCodeRendersWholeQrAndCopiesFromNarrowScene'
TARGET = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/' + FIXTURE.name
BINDINGS = (
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopAppContainer.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/DesktopFishPickerWindow.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/DesktopLanSyncPanel.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/DesktopLanSyncGroupPanel.kt',
    'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/lanSync/DesktopLanSyncSensitiveClipboard.kt',
    'app/sharedCore/src/main/kotlin/com/example/chatbar/domain/lanSync/LanSyncGroupCoordinator.kt',
)
PHASES = frozenset((
    'GUARD', 'RETAINED_PROTECTED_FILES', 'ACQUIRE_RETAINED_ROOT', 'OPEN_RETAINED_GROUP',
    'OPEN_NATIVE_CLIPBOARD_OWNER', 'WAIT_LOCKED_PANEL', 'UNLOCK_WITH_ORIGINAL_CODE', 'CLICK_INVITE',
    'WAIT_INVITATION_PUBLISHED', 'WAIT_QR_NODE', 'CHECK_WIDE_QR_PIXELS',
    'CHECK_NARROW_COPY_ONLY', 'COPY_INVITE_THROUGH_NATIVE_PATH', 'CLOSE_INVITATION_AND_LOCK',
    'CLOSE_RETAINED_APP', 'VERIFY_RETAINED_AFTER_CLOSE', 'COMPLETE',
))
STAGES = frozenset(('EMPTY', 'PREPARED_LOCKED', 'NEEDS_RECOVERY_SAVE', 'JOINING', 'READY'))
CONTROL = retained.control


def make_request(repository, source):
    CONTROL.sha(source, 40)
    CONTROL.require(source != retained.BASE_SOURCE, 'NEW_CANDIDATE_REQUIRED')
    for revision in (retained.BASE_SOURCE, source):
        actual = CONTROL.git_bytes(repository, 'rev-parse', revision + '^{commit}').decode().strip()
        CONTROL.require(actual == revision, 'EXACT_SOURCE_COMMIT')
    bridge = retained.verify_bridge()
    old_fixture = retained.rebound_fixture(bridge, source)
    runtime = [
        {'path': path, 'sha256': hashlib.sha256(
            CONTROL.git_bytes(repository, 'show', source + ':' + path)).hexdigest()}
        for path in BINDINGS
    ]
    return {
        'kind': 'CCB_V7_WINDOWS_RETAINED_QR_SCENE_REQUEST_V1',
        'format': 1, 'oldSourceSha': retained.BASE_SOURCE, 'sourceSha': source,
        'oldGroupFixtureSha256': hashlib.sha256(old_fixture).hexdigest(),
        'retainedBridgeSha256': retained.BRIDGE_SHA,
        'fixtureSha256': CONTROL.file_hash(FIXTURE),
        'runtime': runtime,
    }


def read_observation(path):
    if not path.is_file():
        return None
    CONTROL.require(not path.is_symlink() and 0 < path.stat().st_size < 4096, 'SMALL_SCENE_OBSERVATION')
    value = CONTROL.load(path)
    base = {'kind', 'phase', 'passed'}
    state = {'groupStage', 'busy', 'managementUnlocked', 'invitationPresent'}
    size = {'imagePixels', 'gridModules', 'pixelsPerModule', 'narrowPanelPixels'}
    CONTROL.require(type(value) is dict and set(value).issuperset(base), 'SCENE_OBSERVATION_SCHEMA')
    CONTROL.require(value['kind'] == 'CCB_V7_RETAINED_QR_SCENE_V1'
                    and type(value['passed']) is bool and value['phase'] in PHASES, 'SCENE_OBSERVATION_IDENTITY')
    if value['passed']:
        CONTROL.require(set(value) == base | state | size and value['phase'] == 'COMPLETE', 'SCENE_SUCCESS_SCHEMA')
        CONTROL.require(all(type(value[key]) is int and 0 < value[key] <= 1000 for key in size), 'SCENE_DIMENSIONS')
        CONTROL.require(value['pixelsPerModule'] >= 2
                        and value['imagePixels'] == value['gridModules'] * value['pixelsPerModule']
                        and value['narrowPanelPixels'] == value['gridModules'] * 2 - 1,
                        'SCENE_INTEGER_MODULES_AND_NARROW_WIDTH')
        CONTROL.require(value['groupStage'] == 'READY' and value['managementUnlocked'] is False
                        and value['invitationPresent'] is False, 'SCENE_CLOSE_AND_LOCK')
    else:
        CONTROL.require(set(value) in (base, base | state) and value['phase'] != 'COMPLETE', 'SCENE_FAILURE_SCHEMA')
    if set(value) & state:
        CONTROL.require(value['groupStage'] in STAGES and all(type(value[key]) is bool for key in state - {'groupStage'}),
                        'SCENE_SAFE_STATE')
    return value


def worktree(bridge, checkout, target, revision):
    bridge.command('git', '-C', str(checkout), 'worktree', 'add', '--detach', str(target), revision)
    CONTROL.require(CONTROL.git_bytes(target, 'rev-parse', 'HEAD').decode().strip() == revision, 'CLOUD_SOURCE_CHECKOUT')
    return target


def seed_profile(bridge, modules, request, checkout, private, profile):
    old_sha = request['oldSourceSha']
    source = worktree(bridge, checkout, private / 'lan7-source', old_sha)
    fixture = private / bridge.FIXTURE.name
    fixture.write_bytes(retained.rebound_fixture(bridge, request['sourceSha']))
    CONTROL.require(CONTROL.file_hash(fixture) == request['oldGroupFixtureSha256'], 'EXACT_OLD_GROUP_FIXTURE')
    seed_output = private / 'old-seed-evidence'
    seed_output.mkdir()
    with retained.scoped(bridge, {'OLD': old_sha, 'NEW': request['sourceSha'],
                                  'FIXTURE': fixture, 'FIXTURE_SHA': request['oldGroupFixtureSha256']}):
        companion = bridge.Companion(modules, {old_sha: source}, private, seed_output)
        companion.prepare()
        companion.run(profile, 'lan5-seed')  # Existing fixture's internal alias for this exact LAN7 seed.
        return companion.original_descriptors[0]


def candidate_scene(bridge, modules, request, checkout, private, profile, result):
    ui = modules['desktop_group_ui_control']
    source = worktree(bridge, checkout, private / 'candidate-source', request['sourceSha'])
    locator = private / 'qr-overlay-request.json'
    CONTROL.write_new(locator, {key: request[key] for key in ('format', 'sourceSha', 'fixtureSha256')})
    evidence = private / 'qr-scene-evidence'

    def same_profile(environment):
        result = dict(environment)
        for key, leaf in (('LOCALAPPDATA', 'Local'), ('APPDATA', 'Roaming'), ('TEMP', 'Temp'), ('TMP', 'Temp')):
            result[key] = str((profile / 'UserData' / leaf).resolve(strict=True))
        result.update(CCB_V7_QR_SCENE_CONTROL='true', CCB_V7_QR_ROOT=str(profile),
                      CCB_V7_QR_PRIVATE=str(private))
        return result

    with retained.scoped(ui, {'CLASS': CLASS, 'METHOD': METHOD, 'TARGET': TARGET, 'FIXTURE': FIXTURE,
                              'isolated_environment': same_profile}):
        result['sceneInvocation'] = 'TEST_ONLY_OVERLAY_PREPARE'
        ui.prepare(locator, source, evidence)
        result['sceneInvocation'] = 'WINDOWS_NATIVE_TEST'
        try:
            ui.run(locator, source, evidence)
        except Exception:
            with retained.scoped(bridge, {'TARGET': TARGET, 'FIXTURE_BASENAME': FIXTURE.name}):
                result['candidateCompiler'] = bridge.compiler_diagnostic(evidence, source)
            raise
        result['sceneInvocation'] = 'VERIFY_JUNIT'
        verified = CONTROL.load(evidence / 'verified.json')
        CONTROL.require(verified['class'] == CLASS and verified['method'] == METHOD
                        and verified['sourceSha'] == request['sourceSha']
                        and verified['fixtureSha256'] == request['fixtureSha256'], 'EXACT_NEW_SCENE_JUNIT')
        return verified


def run(request_path, output):
    CONTROL.cloud()
    checkout = Path.cwd().resolve(strict=True)
    CONTROL.require(checkout == Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True)
                    and not CONTROL.git_bytes(checkout, 'status', '--porcelain', '--untracked-files=all'),
                    'CLEAN_CLOUD_CONTROL')
    control_sha = CONTROL.git_bytes(checkout, 'rev-parse', 'HEAD').decode().strip()
    CONTROL.require(control_sha == os.environ['GITHUB_SHA'], 'EXACT_CONTROL_SHA')
    request = CONTROL.load(request_path)
    CONTROL.require(request == make_request(checkout, request['sourceSha']), 'EXACT_QR_SCENE_REQUEST')
    runner = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
    CONTROL.require(not runner.is_relative_to(checkout) and not checkout.is_relative_to(runner)
                    and output.parent.resolve(strict=True) == checkout and not output.exists(), 'ISOLATED_RUNNER')
    bridge = retained.verify_bridge()
    private = runner / ('ccb-v7-qr-private-' + uuid.uuid4().hex)
    profile = runner / ('ccb-v7-qr-profile-' + uuid.uuid4().hex)
    private.mkdir()
    for leaf in ('Local/ChatChatBarDesktop', 'Roaming', 'Temp'):
        (profile / 'UserData' / leaf).mkdir(parents=True)
    output.mkdir()
    phase = 'SEED_EXACT_OLD_WINDOWS_GROUP'
    result = {
        'kind': 'CCB_V7_WINDOWS_RETAINED_QR_SCENE_CONTROL_V1', 'controlSha': control_sha,
        'request': request, 'passed': False, 'packagedMainExercised': False,
        'physicalCameraExercised': False, 'externalNetworkSettingsChanged': False,
    }
    try:
        with bridge.private_helpers() as modules:
            native = modules['prove_desktop_lan3_lan4']
            CONTROL.require(bridge.digest(native.LEGACY) == bridge.LEGACY_SHA, 'EXACT_SYNTHETIC_DATA_SEEDER')
            legacy = native.legacy()
            data = profile / 'UserData/Local/ChatChatBarDesktop'
            image = legacy.seed(data)
            result['oldGroupComponent'] = seed_profile(bridge, modules, request, checkout, private, profile)
            legacy.verify_seed(data, image)
            image_before = legacy.sha(image)
            phase = 'CANDIDATE_COMPOSE_QR_SCENE'
            result['candidateJUnit'] = candidate_scene(bridge, modules, request, checkout, private, profile, result)
            result['scene'] = read_observation(private / 'qr-scene-observation.json')
            CONTROL.require(result['scene'] is not None and result['scene']['passed'], 'EXACT_QR_SCENE_RECEIPT')
            phase = 'RETAINED_SYNTHETIC_DATA_AFTER_SCENE'
            legacy.verify_seed(data, image)
            CONTROL.require(legacy.sha(image) == image_before, 'RETAINED_SYNTHETIC_PNG')
        result.update(passed=True, sameIsolatedWindowsAccountAndRoot=True,
                      originalSyntheticConversationAndPngRetained=True,
                      originalDpapiGroupAndProtectedFilesRetained=True,
                      unlockedByOriginalCodeThroughScene=True, liveInvitationRendered=True,
                      actualSceneWholeModulesAndQuietZone=True, decodedEqualToLiveInvitation=True,
                      narrowSceneCopyReadbackAndPrivacyMarker=True)
    except Exception:
        result['failedAt'] = phase
        try:
            result['scene'] = read_observation(private / 'qr-scene-observation.json')
        except Exception:
            result['scene'] = None
    CONTROL.write_new(output / 'qr-scene-control-result.json', result)
    print('WINDOWS_QR_SCENE_PASS' if result['passed'] else 'WINDOWS_QR_SCENE_FAILED:' + phase)
    return 0 if result['passed'] else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operations = parser.add_subparsers(dest='action', required=True)
    request = operations.add_parser('make-request')
    request.add_argument('--repository', type=Path, required=True)
    request.add_argument('--source-sha', required=True)
    request.add_argument('--output', type=Path, required=True)
    execute = operations.add_parser('run')
    execute.add_argument('--request', type=Path, required=True)
    execute.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == 'make-request':
            CONTROL.write_new(args.output, make_request(args.repository, args.source_sha))
            return 0
        return run(args.request, args.output)
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as failure:
        print('WINDOWS_QR_SCENE_REFUSED:' + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
