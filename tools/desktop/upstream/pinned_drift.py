"""Offline upstream change inventory. Path/blob matches are not feature-parity verdicts."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import subprocess

MANIFEST = 'docs/desktop/10_UPSTREAM_BASELINE.json'
ANDROID = 'app/app/src/main/java/'
SHARED = 'app/sharedCore/src/main/kotlin/'
ANDROID_TEST = 'app/app/src/test/java/'
SHARED_TEST = 'app/sharedCore/src/test/kotlin/'


def git(repository, *arguments):
    return subprocess.check_output(['git', '--no-replace-objects', '-c', 'maintenance.auto=false', '-c', 'gc.auto=0',
        '-C', str(repository), *arguments], stderr=subprocess.PIPE, timeout=60)


def commit(repository, value):
    if not re.fullmatch('[0-9a-f]{40}', value):
        raise ValueError('EXPLICIT_FULL_COMMIT_REQUIRED')
    actual = git(repository, 'rev-parse', '--verify', value + '^{commit}').decode('ascii').strip()
    if actual != value:
        raise ValueError('COMMIT_IDENTITY_REFUSED')
    return actual


def parse_diff(raw):
    fields = raw.split(b'\0')
    result = []
    cursor = 0
    while cursor < len(fields) and fields[cursor]:
        header = fields[cursor].decode('ascii').split()
        if len(header) != 5 or not header[0].startswith(':'):
            raise ValueError('RAW_DIFF_RECORD_REFUSED')
        before_mode, after_mode, before, after, status = header
        if (not re.fullmatch(':[0-7]{6}', before_mode) or not re.fullmatch('[0-7]{6}', after_mode)
            or not all(re.fullmatch('[0-9a-f]{40}', value) for value in (before, after))
            or not re.fullmatch('[ACDMRTUXB](?:[0-9]{1,3})?', status)
            or (len(status) > 1 and int(status[1:]) > 100)):
            raise ValueError('RAW_DIFF_METADATA_REFUSED')
        cursor += 1
        if cursor >= len(fields) or not fields[cursor]:
            raise ValueError('RAW_DIFF_PATH_REQUIRED')
        first = fields[cursor].decode('utf-8', 'surrogateescape')
        cursor += 1
        second = first
        if status[0] in 'RC':
            if len(status) == 1 or cursor >= len(fields) or not fields[cursor]:
                raise ValueError('RAW_DIFF_RENAME_PATH_REQUIRED')
            second = fields[cursor].decode('utf-8', 'surrogateescape')
            cursor += 1
        result.append(dict(status=status, beforePath=None if status[0] == 'A' else first,
            afterPath=None if status[0] == 'D' else second,
            beforeBlob=None if set(before) == {'0'} else before,
            afterBlob=None if set(after) == {'0'} else after,
            beforeMode=before_mode[1:], afterMode=after_mode))
    if any(fields[cursor:]):
        raise ValueError('RAW_DIFF_TAIL_REFUSED')
    return result


def read_tree(raw):
    tree = {}
    for entry in raw.split(b'\0'):
        if not entry:
            continue
        metadata, path = entry.split(b'\t', 1)
        mode, kind, blob = metadata.decode('ascii').split()
        if kind == 'blob':
            tree[path.decode('utf-8', 'surrogateescape')] = dict(blob=blob, mode=mode)
    return tree


def path_flags(path):
    flags = []
    lower = path.lower()
    if path.startswith('.agents/') or path == 'AGENTS.md':
        flags.append('PROJECT_GUIDANCE_REVIEW')
    if '/prompt/' in lower or 'prompttemplates' in lower:
        flags.append('PROMPT_REVIEW_CANDIDATE_NOT_LITERAL_DIFF_PROOF')
    if '/data/' in lower or '/backup/' in lower or '/saveslot' in lower:
        flags.append('PERSISTENCE_OR_TRANSPORT_REVIEW')
    if 'saveslot' in lower:
        flags.append('SAVE_SLOT_RECOVERY_REVIEW')
    if '/memory/' in lower or '/rag/' in lower or 'longtermmemory' in lower:
        flags.append('MEMORY_RAG_REVIEW')
    if 'worldbook' in lower:
        flags.append('WORLD_BOOK_REVIEW')
    if '/domain/chat/' in lower:
        flags.append('PROMPT_CONTEXT_HISTORY_REVIEW')
    if '/domain/network/' in lower or '/domain/model/' in lower or any(name in lower for name in (
        'streamingchatservice', 'chatapirequest', 'openaiss', 'providercapabilit', 'proxyawareclient')):
        flags.append('PROVIDER_TRANSPORT_REVIEW')
    if '/image/' in lower or '/voice/' in lower or 'novelai' in lower or 'fish' in lower:
        flags.append('MEDIA_PROVIDER_REVIEW')
    if '/community/' in lower:
        flags.append('COMMUNITY_DEFERRED_SCOPE')
    if '/moment/' in lower or '/moments/' in lower:
        flags.append('MOMENTS_REVIEW')
    if '/ui/' in lower or path.endswith('AndroidManifest.xml'):
        flags.append('ANDROID_PLATFORM_EQUIVALENT_REVIEW')
    if '/test/' in lower or '/androidtest/' in lower:
        flags.append('UPSTREAM_TEST_REFERENCE')
    if '.gradle' in lower or path.endswith('libs.versions.toml'):
        flags.append('BUILD_DEPENDENCY_REVIEW')
    return flags or ['OTHER_CHANGED_PATH_REVIEW']


def build_report(repository, baseline, target, desktop):
    for value in (baseline, target, desktop):
        commit(repository, value)
    manifest_raw = git(repository, 'show', desktop + ':' + MANIFEST)
    declared = json.loads(manifest_raw)['upstream']
    raw_diff = git(repository, 'diff', '--no-ext-diff', '--no-textconv', '--raw', '--full-index', '--no-abbrev', '-z', '-M', baseline, target, '--')
    raw_tree = git(repository, 'ls-tree', '-r', '-z', desktop)
    tree = read_tree(raw_tree)
    blobs = defaultdict(list)
    for path, item in tree.items():
        blobs[item['blob']].append(path)
    changes = parse_diff(raw_diff)
    for change in changes:
        path = change['afterPath'] or change['beforePath']
        candidate_paths = {}
        archived = []
        if path in tree:
            candidate_paths[path] = 'SAME_REPOSITORY_PATH'
        for origin, destination, role in ((ANDROID, SHARED, 'SHARED_PACKAGE_PATH'),
            (ANDROID_TEST, SHARED_TEST, 'SHARED_TEST_PACKAGE_PATH')):
            if path.startswith(origin):
                shared = destination + path.removeprefix(origin)
                if shared in tree:
                    candidate_paths[shared] = role
        if change['afterBlob'] is not None:
            for matching in blobs.get(change['afterBlob'], []):
                if matching.startswith('docs/desktop/evidence/'):
                    archived.append(dict(path=matching, blob=tree[matching]['blob'], role='ARCHIVED_REFERENCE_NOT_ACTIVE_SOURCE'))
                else:
                    candidate_paths.setdefault(matching, 'EXACT_BLOB_OTHER_PATH')
        change.update(flags=path_flags(path), candidates=[dict(path=candidate, role=role,
            desktopBlob=tree[candidate]['blob'], desktopMode=tree[candidate]['mode'],
            exactTargetBlob=tree[candidate]['blob'] == change['afterBlob'])
            for candidate, role in sorted(candidate_paths.items())],
            archivedBlobReferences=sorted(archived, key=lambda item: item['path']),
            behaviorCompatibility='NOT_EVALUATED', desktopFeaturePresence='NOT_DEDUCED_FROM_PATHS')
        high_risk = {'PROMPT_REVIEW_CANDIDATE_NOT_LITERAL_DIFF_PROOF', 'PERSISTENCE_OR_TRANSPORT_REVIEW',
            'SAVE_SLOT_RECOVERY_REVIEW', 'MEMORY_RAG_REVIEW', 'WORLD_BOOK_REVIEW',
            'PROMPT_CONTEXT_HISTORY_REVIEW', 'PROVIDER_TRANSPORT_REVIEW'}
        change.update(reviewPriority='HIGH_PATH_CANDIDATE' if high_risk.intersection(change['flags']) else 'NORMAL_PATH_CANDIDATE',
            reviewPriorityBasis='PATH_ONLY_NOT_SEMANTIC_ASSESSMENT')
    return dict(schemaVersion=1, kind='PINNED_UPSTREAM_DRIFT_INVENTORY',
        upstreamRepository=declared['repository'], declaredBaseline=dict(commit=declared['commit'],
            version=declared['releaseVersion'], manifestGitBlobSha256=hashlib.sha256(manifest_raw).hexdigest()),
        baseline=baseline, target=target, desktop=desktop, baselineMatchesDeclared=baseline == declared['commit'],
        sourceEvidence=dict(diffSha256=hashlib.sha256(raw_diff).hexdigest(), desktopTreeSha256=hashlib.sha256(raw_tree).hexdigest(),
            reporterSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()),
        counts=dict(changedEntries=len(changes), statuses=dict(Counter(c['status'][0] for c in changes)),
            entriesWithExactBlobCandidate=sum(any(p['exactTargetBlob'] for p in c['candidates']) for c in changes),
            entriesWithArchivedBlobReference=sum(bool(c['archivedBlobReferences']) for c in changes),
            overlappingPathFlags=dict(Counter(flag for c in changes for flag in c['flags'])),
            highRiskPathCandidates=sum(c['reviewPriority'] == 'HIGH_PATH_CANDIDATE' for c in changes),
            nonDeletedEntriesWithoutPathOrBlobCandidate=sum(c['afterPath'] is not None and not c['candidates'] for c in changes)),
        limitations=['Pinned local Git objects only; no fresh latest-release assertion or network fetch.',
            'Same Android path or identical blob is not Desktop runtime or feature-parity proof.',
            'Archived docs/desktop/evidence blob copies are references, excluded from active-path/blob candidate counts.',
            'No path candidate does not mean a feature is missing; review platform/shared owners.',
            'Flags are path-based review hints, not prompt literal or behavioral classification.',
            'No merge, baseline promotion, source/prompt edit, monitor change or release publication.'],
        changes=changes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[3])
    for name in ('baseline', 'target', 'desktop'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = build_report(args.repository, args.baseline, args.target, args.desktop)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=True, indent=2)
        output.write('\n')
    print(json.dumps(dict(kind=report['kind'], counts=report['counts'], baselineMatchesDeclared=report['baselineMatchesDeclared'])))


if __name__ == '__main__':
    main()
