"""LR6 opt-in metadata census. No general cleanup command or credential access.

Only the exact 199 allowlist is considered; deletion is deliberately NOT implemented.
Unproved scratch provenance / alternate streams / exclusive ownership retain files.
Full paths stay in an ignored local ledger. Public output is aggregate only.
"""
from pathlib import Path
from collections import defaultdict
import csv
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import zipfile

REPO = Path(__file__).resolve().parents[4]
RAW = REPO / 'docs/desktop/evidence/p11-lr6-20261024/raw'
ROUNDS = ('first-protocol', 'native-reader', 'protocol-green')
SCRATCH = tuple(f'app/{m}/build/tmp/{t}' for m in ('desktopApp', 'sharedCore')
                for t in ('compileKotlin', 'compileTestKotlin'))
ALLOWLIST = SCRATCH + tuple(f'app/desktopApp/build/p11-lr5/{r}' for r in ROUNDS)

def reparse(s):
    return stat.S_ISLNK(s.st_mode) or bool(getattr(s, 'st_file_attributes', 0) & 0x400)

def identity(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns,
            s.st_ctime_ns, getattr(s, 'st_file_attributes', 0), s.st_nlink)

def contained(root, target):
    # Check lexical ancestry first, then every component without following links.
    root, target = Path(os.path.abspath(root)), Path(os.path.abspath(target))
    if not target.is_relative_to(root):
        return False
    current = root
    for piece in (None, *target.relative_to(root).parts):
        if piece is not None:
            current /= piece
        try:
            if reparse(current.lstat()):
                return False
        except OSError:
            return False
    return True

def category(parts):
    if parts[0] == '.git':
        if len(parts) > 2 and parts[1:3] == ( 'objects', 'pack'):
            return '.git/objects/pack'
        if len(parts) > 1 and parts[1] == 'lfs':
            return '.git/lfs'
        return '.git/objects/other' if len(parts) > 1 and parts[1] == 'objects' else '.git/other'
    if parts[:3] == ('docs', 'desktop', 'evidence'):
        return 'docs/desktop/evidence'
    if parts[0] in ('.gradle', '.kotlin', '.idea'):
        return parts[0]
    if len(parts) > 1 and parts[0] == 'app' and parts[1] in ('.gradle', '.kotlin', '.idea'):
        return '/'.join(parts[:2])
    if len(parts) > 3 and parts[0] == 'app' and parts[2] == 'build':
        known = ('classes', 'tmp', 'generated', 'distributions', 'compose', 'test-results', 'reports', 'kotlin')
        bucket = parts[3] if parts[3] in known or parts[3].startswith('p11-lr') else 'other'
        return f'app/{parts[1]}/build/{bucket}'
    return 'source-and-project-docs' if parts[0] in ('app', 'docs', 'tools', '.agents') else 'unknown-or-root-retained'

def walk(root):
    stack = [root]
    while stack:
        parent = stack.pop()
        try:
            with os.scandir(parent) as entries:
                nodes = sorted(entries, key=lambda e: e.name)
            for entry in nodes:
                path = Path(entry.path)
                try:
                    # Windows DirEntry.stat may return st_ino/st_nlink=0 even
                    # where a direct lstat obtains them. Never weaken the guard.
                    s = path.lstat()
                    kind = 'REPARSE_SKIPPED' if reparse(s) else 'DIR' if stat.S_ISDIR(s.st_mode) else 'FILE' if stat.S_ISREG(s.st_mode) else 'OTHER_SKIPPED'
                    yield path, s, kind
                    if kind == 'DIR':
                        stack.append(path)
                except OSError:
                    yield path, None, 'STAT_UNAVAILABLE'
        except OSError:
            yield parent, None, 'ENUM_UNAVAILABLE'

def census(repo, ledger):
    if not contained(repo, repo):
        raise RuntimeError('ROOT_IDENTITY_UNAVAILABLE')
    start = identity(repo.lstat())[:2]
    totals = defaultdict(lambda: dict(logicalBytes=0, files=0, directories=0, skipped=0))
    with ledger.open('x', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(('relativePath_LOCAL_ONLY', 'kind', 'logicalBytes', 'identity_LOCAL_ONLY'))
        for p, s, kind in walk(repo):
            rel = p.relative_to(repo)
            row = totals[category(rel.parts)]
            size = s.st_size if kind == 'FILE' else 0
            row['logicalBytes'] += size
            row['files' if kind == 'FILE' else 'directories' if kind == 'DIR' else 'skipped'] += 1
            writer.writerow((str(rel), kind, size, identity(s) if s else 'UNKNOWN'))
    if identity(repo.lstat())[:2] != start:
        raise RuntimeError('ROOT_CHANGED')
    disk = shutil.disk_usage(repo)
    return dict(measure='LOGICAL_FILE_BYTES_NOT_ALLOCATION', categories=dict(sorted(totals.items())),
                totalLogicalBytes=sum(r['logicalBytes'] for r in totals.values()),
                volumeFreeBytes=disk.free, volumeTotalBytes=disk.total,
                globalCaches='OUT_OF_SCOPE_NOT_TRAVERSED', reparsePolicy='SKIP_NO_DESCENT',
                concurrency='METADATA_CENSUS_NOT_ATOMIC_SNAPSHOT')

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def verify_lr5(repo):
    index = repo / 'docs/desktop/evidence/p11-lr5-20261024/P11_LR5_PROJECT_REVIEW_PACKET/RAW_INDEX.json'
    rows = json.loads(index.read_text(encoding='utf-8'))
    git = subprocess.check_output(['git', 'ls-tree', '-r', 'HEAD', '--',
                                  'docs/desktop/evidence/p11-lr5-20261024', 'tools/desktop/interop/lr5'], cwd=repo).decode()
    blobs = {line.split('\t', 1)[1]: line.split('\t', 1)[0].split()[2] for line in git.splitlines()}
    checks = rows['raw'] + rows['currentToolFiles']
    passed = 0
    for row in checks:
        p = repo / row['path']
        if not contained(repo, p) or p.stat().st_size != row['bytes'] or sha(p) != row['sha256'] or blobs.get(row['path']) != row['gitBlob']:
            raise RuntimeError('LR5_RETAINED_EVIDENCE_MISMATCH')
        passed += 1
    return dict(attribution='CODEX_SELF_CHECK_NOT_PROJECT', checked=passed,
                raw=len(rows['raw']), tools=len(rows['currentToolFiles']), failures=0,
                headTreeBinding=True, historicalIndexUnmodified=True)

def compare_round(repo, round_name):
    if round_name not in ROUNDS:
        raise ValueError('NOT_ALLOWLISTED')
    relative = f'app/desktopApp/build/p11-lr5/{round_name}'
    root = repo / relative
    if not root.exists():
        return dict(candidate=relative, decision='NOT_PRESENT', logicalBytes=0)
    if not contained(repo, root):
        return dict(candidate=relative, decision='RETAIN_UNKNOWN', reason='UNSAFE_PATH')
    nodes = list(walk(root))
    total = sum(s.st_size for _, s, k in nodes if k == 'FILE')
    result = dict(candidate=relative, files=sum(k == 'FILE' for _, _, k in nodes), logicalBytes=total,
                  decision='RETAIN_UNKNOWN', reason='EXCLUSIVE_OWNERSHIP_AND_ALTERNATE_STREAMS_NOT_PROVEN')
    if any(k not in ('DIR', 'FILE') or (k == 'FILE' and (s.st_nlink != 1 or not s.st_ino)) for _, s, k in nodes):
        return dict(result, reason='UNSAFE_OR_UNAVAILABLE_NODE_IDENTITY')
    archive = repo / f'docs/desktop/evidence/p11-lr5-20261024/raw/{round_name}/synthetic-final-state.zip'
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        if len(names) != len(set(names)) or any(n.startswith('/') or '\\' in n or '..' in Path(n).parts or ':' in n for n in names):
            return dict(result, reason='UNSAFE_RETAINED_ARCHIVE')
        expected = set(names)
        actual = {p.relative_to(root).as_posix() for p, _, k in nodes if k == 'FILE'}
        if expected != actual:
            return dict(result, reason='ADDITIONAL_OR_MISSING_FILES', expectedFiles=len(expected))
        for p, s, k in nodes:
            if k != 'FILE':
                continue
            n = p.relative_to(root).as_posix()
            info = z.getinfo(n)
            if s.st_size != info.file_size or sha(p) != hashlib.sha256(z.read(n)).hexdigest():
                return dict(result, reason='CONTENT_CHANGED_FROM_ACCEPTED_SNAPSHOT')
    return dict(result, acceptedSnapshotFilesMatch=True,
                note='Bytes preserved in Git; file/stream/active-use deletion authority remains unproven. No delete attempted.')

def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ('before', 'after', 'candidates'):
        raise SystemExit('USE_BEFORE_AFTER_OR_CANDIDATES_ONLY')
    local = Path((REPO / 'app/desktopApp/build/p11-lr6-private-space/current-task-pointer.txt').read_text().strip())
    if not contained(REPO, local):
        raise SystemExit('LOCAL_LEDGER_PATH_UNSAFE')
    if sys.argv[1] == 'candidates':
        result = dict(lr5Evidence=verify_lr5(REPO), candidates=[])
        for rel in SCRATCH:
            p = REPO / rel
            result['candidates'].append(dict(candidate=rel,
                decision='RETAIN_UNKNOWN' if p.exists() else 'NOT_PRESENT',
                reason='GENERATED_PROVENANCE_NOT_INDEPENDENTLY_PROVEN' if p.exists() else 'NO_ITEM'))
        result['candidates'] += [compare_round(REPO, r) for r in ROUNDS]
        result['deletedFiles'] = result['deletedLogicalBytes'] = 0
        name = 'CLEANUP_DRY_RUN.json'
    else:
        phase = sys.argv[1]
        result = census(REPO, local / f'{phase}-local-only.csv')
        name = f'LOCAL_DISK_AUDIT_{phase.upper()}.json'
    (RAW / name).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(stage=sys.argv[1], status='COMPLETE',
                         logicalBytes=result.get('totalLogicalBytes'), deletedLogicalBytes=result.get('deletedLogicalBytes'))))

if __name__ == '__main__':
    main()
