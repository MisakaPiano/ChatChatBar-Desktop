"""Cloud-only ordinary-installer completion observation; never packaged or run locally.

The cache fixtures are local receipt hints, not signed download/installation authority.
The actual new NSIS process must publish its own completion record and remove the
exact used copy. Synthetic signature validation is covered separately by test_client.
"""
import hashlib
import json
from pathlib import Path
import shutil
import uuid


PRODUCT = 'ccb-desktop-nsis-v1'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(case, image, installer):
    config = json.loads((image / 'app/desktop-updater/update-config.json').read_text(encoding='utf-8'))
    cache = case / 'local-appdata/ChatChatBarDesktopUpdates'
    cache.mkdir(exist_ok=True)
    owner = cache / 'owner.txt'
    if owner.exists():
        assert owner.read_text(encoding='utf-8') == PRODUCT
    else:
        owner.write_text(PRODUCT, encoding='utf-8')
    downloads = cache / 'downloads'; downloads.mkdir(exist_ok=True)
    used = downloads / uuid.uuid4().hex; used.mkdir()
    copied = used / 'desktop-installer.exe'; shutil.copyfile(installer, copied)
    assert digest(copied) == digest(installer)
    receipt = dict(status='AVAILABLE', version=config['version'], displayVersion=config['displayVersion'],
                   sourceSha=config['sourceSha'], bytes=copied.stat().st_size, sha256=digest(copied), channel=config['channel'])
    (used / 'VERIFIED.json').write_text(json.dumps(receipt), encoding='utf-8')
    future = downloads / uuid.uuid4().hex; future.mkdir()
    pending = future / 'desktop-installer.exe'; pending.write_bytes(b'MZ synthetic pending payload never executed')
    future_receipt = dict(receipt, version=config['version'] + 1,
                          displayVersion='0.1.0-preview.' + str(config['version'] + 1),
                          bytes=pending.stat().st_size, sha256=digest(pending), sourceSha='c' * 40)
    (future / 'VERIFIED.json').write_text(json.dumps(future_receipt), encoding='utf-8')
    unknown = downloads / ('unknown-' + uuid.uuid4().hex); unknown.mkdir()
    (unknown / 'retained.txt').write_text('Synthetic unknown material must remain', encoding='utf-8')
    retained = {str(p.relative_to(cache)): digest(p) for folder in (used, future, unknown)
                for p in folder.iterdir() if p != copied}
    return dict(cache=str(cache), used=str(copied), retained=retained, config=config,
                installerSha256=digest(installer), receiptFixtureIsSignedAuthority=False)


def verify(state, install_root):
    cache = Path(state['cache']); used = Path(state['used']); config = state['config']
    current = (install_root / 'current.txt').read_text(encoding='utf-8').strip()
    package = install_root / 'versions' / current
    manifest = package / 'install-manifest.json'
    expected = dict(format=1, product=PRODUCT, build=config['version'], displayVersion=config['displayVersion'],
                    sourceSha=config['sourceSha'], root=str(install_root), current=current,
                    manifestSha256=digest(manifest))
    records = list((cache / 'confirmations').glob('*.json'))
    assert any(json.loads(p.read_text(encoding='utf-8')) == expected for p in records), 'ORDINARY_COMPLETION_RECEIPT_NOT_OBSERVED'
    assert not used.exists(), 'ORDINARY_COMPLETED_INSTALLER_NOT_RECLAIMED'
    for relative, expected_sha in state['retained'].items():
        assert digest(cache / relative) == expected_sha, 'PENDING_OR_SMALL_CACHE_EVIDENCE_CHANGED'
    return dict(passed=True, current=current, sourceSha=config['sourceSha'], displayVersion=config['displayVersion'],
                realNsisTailPublishedCompletion=True, exactUsedInstallerRemoved=True,
                usedInstallerSha256=state['installerSha256'], pendingAndUnknownRetained=True,
                smallReceiptsRetained=True, confirmationCount=len(records),
                signedDownloadAuthorityTested=False, humanAcceptance=False, providerCalls=False)
