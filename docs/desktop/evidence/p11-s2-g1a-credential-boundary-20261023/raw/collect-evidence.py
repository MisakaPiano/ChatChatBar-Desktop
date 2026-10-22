"""Only explicit task source paths and newly generated synthetic probe outputs are read."""
from pathlib import Path
import hashlib, io, json, re, shutil, subprocess, sys, tarfile, xml.etree.ElementTree as ET
RAW = Path(__file__).resolve().parent
REPO = RAW.parents[4]
CONTROL = 'ed8f54236cd29b822f3a8875218b3ba7043eab5f'
OFFICIAL = '1198a13cf3008de1358b8a69aa5e40b64797c8d9'
TEST = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/P11S2CredentialTransactionBoundaryProbeTest.kt'
sha = lambda value: hashlib.sha256(value).hexdigest()
def git(*args): return subprocess.check_output(['git', *args], cwd=REPO)
def save(name, value): (RAW / name).write_text(json.dumps(value, indent=2), encoding='utf-8')

def capture(label):
    target = RAW / label
    target.mkdir(exist_ok=False)
    (target / 'xml').mkdir()
    suites = []
    for path in sorted((REPO / 'app/desktopApp/build/test-results/test').glob('TEST-*.xml')):
        shutil.copyfile(path, target / 'xml' / path.name)
        root = ET.parse(path).getroot()
        suites.append(dict(name=root.attrib['name'], **{k:int(root.attrib[k]) for k in ['tests','failures','errors','skipped']}))
    shutil.copytree(REPO / 'app/desktopApp/build/p11-s2-g1a-credential-probe', target / 'probe-records')
    shutil.copyfile(REPO / TEST, target / 'probe-source.kt.txt')
    results = dict(suites=suites, totals={k:sum(v[k] for v in suites) for k in ['tests','failures','errors','skipped']})
    (target / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
    print(json.dumps(dict(label=label, suites=len(suites), **results['totals'])))

def identities():
    prefix = 'app/desktopApp/src/main/kotlin/com/example/chatbar/desktop/'
    paths = [prefix + value for value in ['security/WindowsSecretStore.kt', 'security/DesktopSecretStore.kt',
        'security/WindowsDpapiSecretProtector.kt', 'security/WindowsSecretRootResolver.kt', 'security/DesktopRepositoryCredentialPolicies.kt',
        'backup/DesktopFullBackupArchive.kt', 'backup/DesktopBackupPolicy.kt', 'backup/DesktopBackupCompatibility.kt',
        'backup/DesktopFullBackupMapping.kt', 'backup/DesktopBackupScratch.kt', 'backup/DesktopBackupReadOnlyInspector.kt',
        'backup/AppBackupModels.kt', 'backup/AppBackupCodec.kt', 'DesktopFullBackupService.kt', 'DesktopFullBackupCommitJournal.kt',
        'DesktopStartupGate.kt', 'DesktopDataOperationCoordinator.kt', 'DesktopDataRootOwnership.kt']]
    paths += ['app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/ModelConfig.kt',
              'app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/entity/AppSettings.kt',
              'app/sharedCore/src/main/kotlin/com/example/chatbar/data/local/JsonFileStorage.kt']
    rows = []
    for path in paths:
        data = git('show', f'{CONTROL}:{path}')
        current = (REPO / path).read_bytes()
        rows.append(dict(path=path, control=CONTROL, blob=git('rev-parse',f'{CONTROL}:{path}').decode().strip(),
            bytes=len(data), sha256=sha(data), workspaceBytes=len(current), workspaceSha256=sha(current),
            workspaceDiffEmpty=not git('diff',CONTROL,'--',path).strip(),
            sourceUrl=f'https://github.com/MisakaPiano/ChatChatBar-Desktop/blob/{CONTROL}/{path}'))
    save('production-source-identities.json',rows)
    trees = [dict(path=p, controlTree=git('rev-parse',f'{CONTROL}:{p}').decode().strip(),
                  headTree=git('rev-parse',f'HEAD:{p}').decode().strip(),
                  workspaceDiffEmpty=not git('diff',CONTROL,'--',p).strip())
             for p in ['app/desktopApp/src/main','app/sharedCore/src/main','app/app/src/main']]
    assert all(t['controlTree']==t['headTree'] and t['workspaceDiffEmpty'] for t in trees)
    assert all(r['workspaceDiffEmpty'] for r in rows)
    save('production-tree-identities.json',trees)
    test_prefix = 'app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/'
    test_paths = [TEST] + [test_prefix + p + '.kt' for p in ['security/WindowsSecretStoreTest', 'security/WindowsSecretRootResolverTest',
        'security/DesktopSecureModelRepositoryTest', 'security/DesktopSecureEmbeddingRepositoryTest',
        'security/DesktopSecureSettingsRepositoryTest', 'security/DesktopCredentialTestFixtures',
        'DesktopFullBackupArchiveTest', 'backup/DesktopBackupReadOnlyInspectorTest',
        'DesktopFullBackupServiceTest', 'DesktopFullBackupCommitJournalTest', 'DesktopStartupGateTest',
        'DesktopFullBackupProcessTest', 'backup/OfficialBackupWireFixtures', 'backup/Official147FixtureCodec']]
    save('test-source-identities.json',[dict(path=p,bytes=len((REPO/p).read_bytes()),sha256=sha((REPO/p).read_bytes()),
        gitBlob=git('hash-object','--',p).decode().strip()) for p in test_paths])
    official_paths = ['.agents/skills/chatbar-app-backup/SKILL.md',
        'app/app/src/main/java/com/example/chatbar/domain/backup/AppBackupModels.kt',
        'app/app/src/main/java/com/example/chatbar/domain/backup/BackupPreferenceTransfer.kt',
        'app/app/src/main/java/com/example/chatbar/domain/backup/BackupDataRegistry.kt',
        'app/app/src/main/java/com/example/chatbar/domain/backup/BackupEntityValidator.kt',
        'app/app/src/main/java/com/example/chatbar/data/security/NovelAiCredentialStore.kt',
        'app/app/src/main/java/com/example/chatbar/data/security/FishAudioCredentialStore.kt',
        'app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt',
        'app/app/src/main/java/com/example/chatbar/domain/community/CommunityModels.kt']
    target = RAW / 'official-source'; target.mkdir(exist_ok=True)
    official_rows = []
    for path in official_paths:
        data = git('show', f'{OFFICIAL}:{path}')
        (target / (Path(path).name + '.txt')).write_bytes(data)
        official_rows.append(dict(path=path, commit=OFFICIAL, blob=git('rev-parse',f'{OFFICIAL}:{path}').decode().strip(),
            bytes=len(data), sha256=sha(data), sourceUrl=f'https://github.com/SaltyFishOTL/ChatChatBar/blob/{OFFICIAL}/{path}'))
    save('official-source-identities.json',official_rows)

def index():
    save('evidence-sha256.json',[dict(path=p.relative_to(RAW).as_posix(),bytes=len(p.read_bytes()),sha256=sha(p.read_bytes()))
         for p in sorted(RAW.rglob('*')) if p.is_file() and p.name!='evidence-sha256.json'])

def diagnostics():
    values = [f'FAKE_P11_G1A_{name}_NOT_A_REAL_KEY' for name in ['OLD_A','OLD_B','NEW_A','NEW_B']]
    values += ['FAKE_P11_G1A_ARCHIVE_PASSWORD','FAKE_WRONG_PASSWORD']
    for entry in json.loads((RAW/'test-source-identities.json').read_text()):
        values += re.findall(r'"(fake-[^"\r\n]+)"', (REPO/entry['path']).read_text(encoding='utf-8'))
    values = sorted(set(values))
    paths = list(RAW.glob('*run.log')) + list((RAW/'green/xml').glob('*.xml'))
    for path in paths:
        data = path.read_bytes()
        assert all(v.encode() not in data for v in values), f'Diagnostic value leak in {path.name}'
    save('diagnostic-secrecy.json',dict(filesScanned=len(paths),knownSyntheticLiteralsScanned=len(values),newSyntheticCredentialValuesAbsent=True,
                                     realCredentialsRead=False,fixtureBytesExcludedFromDiagnosticScan=True))

if __name__=='__main__':
    if sys.argv[1]=='capture': capture(sys.argv[2])
    elif sys.argv[1]=='identities': identities()
    elif sys.argv[1]=='index': index()
    elif sys.argv[1]=='diagnostics': diagnostics()
    elif sys.argv[1]=='source-diff': (RAW/'test-source.patch').write_bytes(git('diff','--cached','--binary','--',TEST))
    else: raise SystemExit('Explicit mode required')
