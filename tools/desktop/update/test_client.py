"""No network: actual python-tuf consumes independent synthetic signed metadata."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
import desktop_update as client
import release_metadata
from github_transport import endpoints
from securesystemslib.signer import CryptoSigner
from tuf.api.metadata import Metadata, Root, Targets, TargetFile, Timestamp, Snapshot, MetaFile
from tuf.api.exceptions import DownloadHTTPError
from tuf.ngclient.fetcher import FetcherInterface


class Repository(FetcherInterface):
    def __init__(self, version=120, payload=b'SYNTHETIC EXECUTABLE NEVER RUN', installed=119):
        self.signers = {role: CryptoSigner.generate_ed25519() for role in ('root', 'timestamp', 'snapshot', 'targets')}
        root = Metadata(Root(version=1, consistent_snapshot=False,
                             expires=datetime.now(timezone.utc) + timedelta(days=10)))
        for role, signer in self.signers.items():
            root.signed.add_key(signer.public_key, role)
        root.sign(self.signers['root'])
        self.bootstrap = root.to_bytes()
        self.installed = client.Installed(installed, f'0.1.0-preview.{installed}', 'a' * 40, 'preview', 1, self.bootstrap)
        self.version, self.payload = version, payload
        self.custom = dict(product=client.PRODUCT, platform='windows-x86_64', channel='preview', version=version,
                           displayVersion=f'0.1.0-preview.{version}', sourceSha='b' * 40, minimum_reader=1,
                           maximum_reader=1, installedBytes=4096, notes='Synthetic update',
                           github=dict(tag=f'desktop-v0.1.0-preview.{version}',
                                       asset=f'ChatChatBar-Desktop-0.1.0-preview.{version}-windows-x64.exe'))
        self.requests = []
        self.authorized = False
        self.build()

    def build(self, metadata_version=1, timestamp_expired=False, targets_signer=None):
        future = datetime.now(timezone.utc) + timedelta(days=2)
        target = TargetFile(len(self.payload), {'sha256': hashlib.sha256(self.payload).hexdigest()},
                            'desktop-installer.exe', unrecognized_fields={'custom': self.custom})
        targets = Metadata(Targets(version=metadata_version, expires=future, targets={target.path: target}))
        targets.sign(targets_signer or self.signers['targets'])
        targets_bytes = targets.to_bytes()
        snapshot = Metadata(Snapshot(version=metadata_version, expires=future,
                            meta={'targets.json': MetaFile.from_data(metadata_version, targets_bytes, ['sha256'])}))
        snapshot.sign(self.signers['snapshot'])
        snapshot_bytes = snapshot.to_bytes()
        timestamp = Metadata(Timestamp(version=metadata_version,
                              expires=datetime.now(timezone.utc) - timedelta(days=1) if timestamp_expired else future,
                              snapshot_meta=MetaFile.from_data(metadata_version, snapshot_bytes, ['sha256'])))
        timestamp.sign(self.signers['timestamp'])
        self.files = {'timestamp.json': timestamp.to_bytes(), 'snapshot.json': snapshot_bytes, 'targets.json': targets_bytes}

    def authorize(self, target):
        self.authorized = True
        self.expected = target.hashes['sha256']

    def _fetch(self, url):
        self.requests.append(url)
        metadata, targets = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
        if url.startswith(metadata):
            name = url.removeprefix(metadata)
            if name not in self.files:
                raise DownloadHTTPError('synthetic unavailable metadata', 404)
            yield self.files[name]
        elif url == targets + 'desktop-installer.exe' and self.authorized:
            yield self.payload
        else:
            raise AssertionError('Unexpected transport request')


class ClientTest(unittest.TestCase):
    def test_offline_release_signing_is_consumed_by_the_actual_tuf_client(self):
        with tempfile.TemporaryDirectory(prefix='ccb-signing-synthetic-') as tmp:
            base = Path(tmp); keys = base / 'keys'; root = base / 'root.json'
            release_metadata.initialize(keys, root)
            package = base / 'fake-installer.exe'; package.write_bytes(b'SYNTHETIC ONLY NEVER EXECUTED')
            notes = base / 'notes.txt'; notes.write_text('Synthetic notes', encoding='utf-8')
            manifest = base / 'manifest.json'
            manifest.write_text(json.dumps(dict(product=client.PRODUCT, version='0.1.0-preview.120',
                commit='b' * 40, files=[dict(bytes=1234)])), encoding='utf-8')
            output = base / 'metadata'
            release_metadata.publish(keys, root, package, 120, 'b' * 40, notes, output, 1, manifest)
            repo = Repository(payload=package.read_bytes())
            repo.installed = client.Installed(119, '0.1.0-preview.119', 'a' * 40, 'preview', 1, root.read_bytes())
            repo.files = {name: (output / name).read_bytes() for name in ('timestamp.json', 'snapshot.json', 'targets.json')}
            result = client.execute(repo.installed, base / 'cache', download=True, fetcher=repo)
            self.assertEqual('DOWNLOADED_VERIFIED', result['status'])
            self.assertEqual(1234, result['installedBytes'])

    def test_signed_download_and_independent_reverification(self):
        repo = Repository()
        with tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            ready = client.execute(repo.installed, cache, download=True, fetcher=repo)
            self.assertEqual('DOWNLOADED_VERIFIED', ready['status'])
            self.assertEqual(repo.payload, Path(ready['installer']).read_bytes())
            before = len(repo.requests)
            reused = client.execute(repo.installed, cache, download=True, fetcher=repo)
            self.assertTrue(reused['reused'])
            _, targets_url = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
            self.assertFalse(any(u.startswith(targets_url) for u in repo.requests[before:]))
            again = client.execute(repo.installed, cache, verify_file=Path(ready['installer']), fetcher=repo)
            self.assertEqual(ready['sha256'], again['sha256'])
            Path(ready['installer']).write_bytes(b'CHANGED')
            with self.assertRaises(Exception):
                client.execute(repo.installed, cache, verify_file=Path(ready['installer']), fetcher=repo)
            replaced = client.execute(repo.installed, cache, download=True, fetcher=repo)
            self.assertTrue(replaced['cacheRejected'])
            self.assertEqual(b'CHANGED', Path(ready['installer']).read_bytes())
            self.assertEqual(repo.payload, Path(replaced['installer']).read_bytes())

    def test_expired_and_wrong_signer_metadata_never_authorize_payload(self):
        for kind in ('expiry', 'signer', 'snapshot-hash'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
                repo = Repository()
                if kind == 'expiry': repo.build(timestamp_expired=True)
                elif kind == 'signer': repo.build(targets_signer=CryptoSigner.generate_ed25519())
                else: repo.files['targets.json'] += b' '
                with self.assertRaises(Exception): client.execute(repo.installed, Path(tmp) / 'updates', download=True, fetcher=repo)
                self.assertFalse(repo.authorized)

    def test_product_reader_downgrade_and_changed_current_source_refuse(self):
        for change in ({'product': 'foreign'}, {'minimum_reader': 2}, {'version': 118, 'displayVersion': '0.1.0-preview.118'},
                       {'version': 119, 'displayVersion': '0.1.0-preview.119'}):
            with self.subTest(change=change), tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
                repo = Repository(); repo.custom.update(change); repo.build()
                with self.assertRaises(client.UpdateRejected): client.execute(repo.installed, Path(tmp) / 'updates', download=True, fetcher=repo)
                self.assertFalse(repo.authorized)

    def test_verified_current_version_does_not_download_or_install(self):
        repo = Repository(version=119)
        repo.custom['sourceSha'] = repo.installed.source_sha; repo.build()
        with tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
            result = client.execute(repo.installed, Path(tmp) / 'updates', download=True, fetcher=repo)
            self.assertEqual('UP_TO_DATE', result['status'])
            self.assertNotIn('installer', result)

    def test_new_signed_version_cannot_replace_a_different_confirmed_selection(self):
        repo = Repository()
        with tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
            with self.assertRaises(client.UpdateRejected):
                client.execute(repo.installed, Path(tmp) / 'updates', download=True, fetcher=repo,
                               expected_version='0.1.0-preview.121')
            self.assertFalse(repo.authorized)

    def test_payload_corruption_retains_unpublished_partial(self):
        repo = Repository(); repo.payload = b'CORRUPT'
        with tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            with self.assertRaises(Exception): client.execute(repo.installed, cache, download=True, fetcher=repo)
            stages = list((cache / 'downloads').iterdir())
            self.assertEqual(1, len(stages)); self.assertTrue(stages[0].name.endswith('.incomplete'))
            self.assertFalse((stages[0] / 'VERIFIED.json').exists())

    def test_metadata_rollback_remains_visible_across_client_invocations(self):
        repo = Repository(); repo.build(metadata_version=2)
        with tempfile.TemporaryDirectory(prefix='ccb-update-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            client.execute(repo.installed, cache, fetcher=repo)
            repo.build(metadata_version=1)
            with self.assertRaises(Exception): client.execute(repo.installed, cache, fetcher=repo)


if __name__ == '__main__':
    unittest.main()
