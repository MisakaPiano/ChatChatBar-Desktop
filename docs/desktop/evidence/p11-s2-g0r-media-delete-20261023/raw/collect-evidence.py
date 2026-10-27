"""Collect only this task's synthetic outputs and explicitly selected source identities."""
from pathlib import Path
import hashlib, json, shutil, subprocess, sys, xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[5]
RAW = Path(__file__).resolve().parent
CONTROL = 'e0923fc625052c1454663d429d1520b794c022bf'
OFFICIAL = '1198a13cf3008de1358b8a69aa5e40b64797c8d9'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=REPO)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def save(name, value):
    (RAW / name).write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')

def capture_run(label):
    target = RAW / label
    target.mkdir(exist_ok=False)
    xml_target = target / 'xml'
    xml_target.mkdir()
    summaries = []
    for source in sorted((REPO / 'app/desktopApp/build/test-results/test').glob('TEST-*.xml')):
        shutil.copyfile(source, xml_target / source.name)
        root = ET.parse(source).getroot()
        summaries.append(dict(name=root.attrib['name'], **{k:int(root.attrib[k]) for k in ['tests','failures','errors','skipped']}))
    output = REPO / 'app/desktopApp/build/p11-s2-g0r-media-probe'
    if output.exists():
        shutil.copytree(output, target / 'probe-records')
    source = REPO / 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2OwnedMediaSafetyProbeTest.kt'
    shutil.copyfile(source, target / 'probe-source.kt.txt')
    (target / 'results.json').write_text(json.dumps(dict(suites=summaries, totals={k:sum(v[k] for v in summaries) for k in ['tests','failures','errors','skipped']}), indent=2), encoding='utf-8')
    print(json.dumps(dict(label=label, suites=len(summaries), tests=sum(v['tests'] for v in summaries))))

def identities():
    desktop = 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'
    sources = [desktop + v for v in [
        'DesktopOwnedImageCleanup.kt', 'DesktopCharacterResourceStore.kt', 'DesktopCharacterBackgrounds.kt',
        'DesktopDataOperationCoordinator.kt', 'DesktopDataRootOwnership.kt', 'DesktopFullBackupService.kt',
        'DesktopCoordinatedSnapshotService.kt', 'DesktopChatImages.kt',
        'backup/DesktopFullBackupMapping.kt', 'backup/DesktopFullBackupArchive.kt', 'backup/DesktopBackupPolicy.kt',
        'backup/DesktopBackupScratch.kt', 'backup/DesktopBackupReadOnlyInspector.kt',
        'backup/MomentArchiveReadOnlyValidator.kt', 'DesktopFullBackupCommitJournal.kt']]
    sources += ['app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/JsonFileStorage.kt',
                'app/sharedCore/src/main/kotlin/com/example/chatbar/data/snapshot/AppDataSnapshotService.kt',
                'app/app/src/main/java/com/example/chatbar/domain/deletion/DeletionCoordinator.kt']
    rows = []
    for path in sources:
        original = git('show', f'{CONTROL}:{path}')
        current = (REPO / path).read_bytes()
        rows.append(dict(path=path, control=CONTROL, blob=git('rev-parse', f'{CONTROL}:{path}').decode().strip(),
                         sourceUrl=f'https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/{CONTROL}/{path}',
                         bytes=len(original), sha256=sha(original), workspaceBytes=len(current), workspaceSha256=sha(current),
                         gitDiffEmpty=not git('diff', CONTROL, '--', path).strip()))
    save('production-source-identities.json', rows)
    trees = []
    for path in ['app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main']:
        expected = git('rev-parse', f'{CONTROL}:{path}').decode().strip()
        actual = git('rev-parse', f'HEAD:{path}').decode().strip()
        trees.append(dict(path=path, controlTree=expected, headTree=actual,
                          equal=expected==actual, workspaceDiffEmpty=not git('diff', CONTROL, '--', path).strip()))
    save('production-tree-identities.json', trees)
    official_dir = RAW / 'official-source'
    official_dir.mkdir(exist_ok=True)
    official_rows = []
    for path in ['app/app/src/main/java/com/example/chatbar/domain/deletion/DeletionCoordinator.kt',
                 'app/app/src/main/java/com/example/chatbar/data/repository/MomentRepository.kt',
                 'app/app/src/main/java/com/example/chatbar/domain/image/NovelAiImageStorage.kt']:
        data = git('show', f'{OFFICIAL}:{path}')
        (official_dir / (Path(path).name + '.txt')).write_bytes(data)
        official_rows.append(dict(path=path, commit=OFFICIAL, blob=git('rev-parse', f'{OFFICIAL}:{path}').decode().strip(),
                                  bytes=len(data), sha256=sha(data),
                                  sourceUrl=f'https://github.com/SaltyFishOTL/ChatChatBar/blob/{OFFICIAL}/{path}'))
    save('official-source-identities.json', official_rows)
    test_names = ['P11S2OwnedMediaSafetyProbeTest', 'DesktopSaveSlotInspectorTest',
                  'DesktopCharacterResourceStoreTest', 'DesktopPhase7R2Test', 'DesktopFullBackupMappingTest',
                  'DesktopFullBackupArchiveTest', 'DesktopFullBackupServiceTest',
                  'DesktopCoordinatedSnapshotServiceTest']
    test_paths = [f'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/{name}.kt' for name in test_names]
    test_paths += [f'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/backup/{name}.kt'
                   for name in ['OfficialBackupWireFixtures', 'Official147FixtureCodec',
                                'DesktopBackupReadOnlyInspectorTest', 'DesktopReadOnlyBackupRedTest',
                                'MomentArchiveReadOnlyValidatorTest']]
    test_rows = []
    for path in test_paths:
        p = REPO / path
        assert p.is_file(), path
        data = p.read_bytes()
        test_rows.append(dict(path=path, bytes=len(data), sha256=sha(data), gitBlob=git('hash-object', '--', path).decode().strip()))
    save('test-source-identities.json', test_rows)
    save('baseline-read-identity.json', dict(path='docs/desktop/10_UPSTREAM_BASELINE.json',
         blob=git('rev-parse', f'{CONTROL}:docs/desktop/10_UPSTREAM_BASELINE.json').decode().strip(),
         formalValidated='1.4.1 @ 5e76a9cb841736bbbf3499a2e35e5789af4c5ca8', changed=False))
    assert all(v['gitDiffEmpty'] for v in rows)
    assert all(v['equal'] and v['workspaceDiffEmpty'] for v in trees)

def index():
    records = []
    for path in sorted(RAW.rglob('*')):
        if path.is_file() and path.name != 'evidence-sha256.json':
            data = path.read_bytes()
            records.append(dict(path=path.relative_to(RAW).as_posix(), bytes=len(data), sha256=sha(data)))
    save('evidence-sha256.json', records)
    print(f'Indexed {len(records)} raw files')

if __name__ == '__main__':
    if sys.argv[1] == 'capture': capture_run(sys.argv[2])
    elif sys.argv[1] == 'identities': identities()
    elif sys.argv[1] == 'index': index()
    elif sys.argv[1] == 'source-diff':
        (RAW / 'test-source.patch').write_bytes(git('diff', '--cached', '--binary', '--',
            'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2OwnedMediaSafetyProbeTest.kt'))
    else: raise SystemExit('Unknown explicit collection mode')
