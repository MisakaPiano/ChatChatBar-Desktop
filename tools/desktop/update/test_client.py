"""No network: actual python-tuf consumes independent synthetic signed metadata."""
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
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
        self.channel = 'preview'
        self.extra_channels = {}
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
        for channel in ('preview', 'stable'):
            metadata, targets = endpoints(client.REPOSITORY, channel, client.METADATA_BRANCH)
            files = self.files if channel == self.channel else self.extra_channels.get(channel)
            if url.startswith(metadata):
                name = url.removeprefix(metadata)
                if files is None or name not in files:
                    raise DownloadHTTPError('synthetic unavailable channel metadata', 404)
                yield files[name]
                return
            if url == targets + 'desktop-installer.exe' and files is not None and self.authorized:
                yield self.payload
                return
        raise AssertionError('Unexpected transport request')


class ClientTest(unittest.TestCase):
    def cache_history(self, cache):
        repo = Repository(version=119, installed=118, payload=b'MZ synthetic installer never executed')
        ready = {}
        for revision, build in enumerate(range(119, 125), start=1):
            repo.custom.update(version=build, displayVersion=f'0.1.0-preview.{build}', sourceSha='b' * 40,
                               github=dict(tag=f'desktop-v0.1.0-preview.{build}',
                                           asset=f'ChatChatBar-Desktop-0.1.0-preview.{build}-windows-x64.exe'))
            repo.payload = b'MZ synthetic installer never executed ' + str(build).encode()
            repo.build(metadata_version=revision)
            ready[build] = client.execute(repo.installed, cache, download=True, fetcher=repo)
        current = client.Installed(120, '0.1.0-preview.120', 'b' * 40, 'preview', 1, repo.bootstrap)
        root = cache.parent / 'program'
        package = root / 'versions' / '0.1.0-preview.120-bbbbbbbbbbbb'
        package.mkdir(parents=True)
        (root / 'install-owner.txt').write_text(client.PRODUCT)
        (root / 'current.txt').write_text(package.name)
        manifest = b'{"synthetic":true}'
        (package / 'install-manifest.json').write_bytes(manifest)
        (root / 'receipts').mkdir()
        digest = hashlib.sha256(manifest).hexdigest()
        (root / 'receipts' / (package.name + '.sha256')).write_text(digest)
        confirmations = cache / 'confirmations'; confirmations.mkdir()
        record = dict(format=1, product=client.PRODUCT, build=current.version, displayVersion=current.display_version,
                      sourceSha=current.source_sha, root=str(root), current=package.name, manifestSha256=digest)
        (confirmations / ('a' * 32 + '.json')).write_text(json.dumps(record))
        self.confirmed_package = package
        return repo, current, ready

    def test_explicit_cleanup_rechecks_real_signed_downloads_and_preserves_selected_current_recent_and_all_small_evidence(self):
        with tempfile.TemporaryDirectory(prefix='ccb-clean-cache-') as tmp:
            cache = Path(tmp) / 'updates'
            repo, installed, ready = self.cache_history(cache)
            records = {p: p.read_bytes() for p in cache.rglob('*') if p.is_file() and p.name != 'desktop-installer.exe'}
            request_count = len(repo.requests)
            cleaned = client.cleanup_downloads(installed, cache, '0.1.0-preview.121', package=self.confirmed_package)
            self.assertEqual(2, cleaned['removedCount'])
            self.assertEqual(len(repo.payload) * 2, cleaned['removedBytes'])
            self.assertEqual(4, cleaned['retainedCount'])
            self.assertEqual(0, cleaned['unavailableCount'])
            for build, value in ready.items():
                self.assertEqual(build not in (119, 120), Path(value['installer']).exists())
            self.assertEqual(records, {p: p.read_bytes() for p in cache.rglob('*') if p.is_file() and p.name != 'desktop-installer.exe'})
            self.assertEqual(request_count, len(repo.requests))
            self.assertGreaterEqual(cleaned['availableBytes'], 0)
            again = client.cleanup_downloads(installed, cache, '0.1.0-preview.121', package=self.confirmed_package)
            self.assertEqual(0, again['removedCount']); self.assertEqual(2, again['missingCount'])
            self.assertEqual(0, again['unavailableCount'])

    def test_cleanup_preserves_busy_hardlinked_changed_and_unknown_material_with_visible_reasons(self):
        for refusal in ('busy', 'hardlink', 'changed', 'unknown', 'duplicate-receipt'):
            with self.subTest(refusal=refusal), tempfile.TemporaryDirectory(prefix='ccb-clean-retain-') as tmp:
                cache = Path(tmp) / 'updates'
                _, installed, ready = self.cache_history(cache)
                file = Path(ready[119]['installer']); before = file.read_bytes(); held = None
                if refusal == 'busy':
                    held = file.open('rb')
                elif refusal == 'hardlink':
                    outside = Path(tmp) / 'external-user-file.exe'; outside.hardlink_to(file)
                elif refusal == 'changed':
                    file.write_bytes(b'NZ' + before[2:]); before = file.read_bytes()
                elif refusal == 'unknown':
                    (file.parent / 'unowned.txt').write_text('synthetic user content')
                else:
                    record = file.parent / 'VERIFIED.json'
                    record.write_text(record.read_text().rstrip().removesuffix('}') + ',"version":119}')
                record_before = (file.parent / 'VERIFIED.json').read_bytes()
                try:
                    result = client.cleanup_downloads(installed, cache, '0.1.0-preview.121', package=self.confirmed_package)
                finally:
                    if held is not None:
                        held.close()
                self.assertEqual(1, result['removedCount'])
                self.assertGreaterEqual(result['unavailableCount'], 1)
                self.assertEqual(before, file.read_bytes())
                if refusal == 'hardlink':
                    self.assertEqual(before, outside.read_bytes())
                self.assertTrue((file.parent / 'VERIFIED.json').is_file())
                self.assertEqual(record_before, (file.parent / 'VERIFIED.json').read_bytes())

    def test_other_channel_target_is_retained_and_unreadable_cached_roles_never_get_reset_or_authorize_cleanup(self):
        with tempfile.TemporaryDirectory(prefix='ccb-clean-channels-') as tmp:
            cache = Path(tmp) / 'updates'
            _, installed, ready = self.cache_history(cache)
            stable = cache / 'metadata-stable' / 'targets.json'
            stable.parent.mkdir(exist_ok=True)
            stable.write_text(json.dumps({'signed': {'targets': {'desktop-installer.exe': {'hashes': {'sha256': ready[119]['sha256']}}}}}))
            original = stable.read_bytes()
            result = client.cleanup_downloads(installed, cache, '0.1.0-preview.121', package=self.confirmed_package)
            self.assertEqual(2, result['removedCount']); self.assertFalse(Path(ready[119]['installer']).exists())
            self.assertEqual(original, stable.read_bytes())
            stable.write_bytes(b'{invalid role}')
            before = {p: p.read_bytes() for p in cache.rglob('*') if p.is_file()}
            with self.assertRaisesRegex(client.UpdateRejected, '^UPDATE_CACHE_METADATA_UNREADABLE$'):
                client.cleanup_downloads(installed, cache, package=self.confirmed_package)
            self.assertEqual(before, {p: p.read_bytes() for p in cache.rglob('*') if p.is_file()})

    def test_cleanup_respects_the_original_client_lock_and_refuses_foreign_ownership_without_deletion(self):
        with tempfile.TemporaryDirectory(prefix='ccb-clean-lock-') as tmp:
            cache = Path(tmp) / 'updates'
            _, installed, ready = self.cache_history(cache)
            original = {Path(v['installer']): Path(v['installer']).read_bytes() for v in ready.values()}
            with client.exclusive(cache):
                with self.assertRaisesRegex(client.UpdateRejected, '^UPDATE_BUSY$'):
                    client.cleanup_downloads(installed, cache, package=self.confirmed_package)
            self.assertTrue(all(path.read_bytes() == value for path, value in original.items()))
            (cache / 'owner.txt').write_text('foreign owner')
            with self.assertRaisesRegex(client.UpdateRejected, '^FOREIGN_UPDATE_CACHE$'):
                client.cleanup_downloads(installed, cache, package=self.confirmed_package)
            self.assertTrue(all(path.read_bytes() == value for path, value in original.items()))

    def test_unconfirmed_changed_or_missing_completion_never_deletes_pending_payloads(self):
        for defect in ('missing', 'current', 'manifest', 'record', 'duplicate'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory(prefix='ccb-unconfirmed-cache-') as tmp:
                cache = Path(tmp) / 'updates'
                _, installed, ready = self.cache_history(cache)
                record = cache / 'confirmations' / ('a' * 32 + '.json')
                if defect == 'missing': record.unlink()
                elif defect == 'current': (self.confirmed_package.parent.parent / 'current.txt').write_text('other')
                elif defect == 'manifest': (self.confirmed_package / 'install-manifest.json').write_bytes(b'changed')
                elif defect == 'record': record.write_text('{}')
                else: record.write_text(record.read_text()[:-1] + ',"build":120}')
                before = {Path(v['installer']): Path(v['installer']).read_bytes() for v in ready.values()}
                result = client.cleanup_downloads(installed, cache, package=self.confirmed_package)
                self.assertEqual(0, result['removedCount'])
                self.assertEqual(6, result['retainedCount'])
                self.assertTrue(all(p.read_bytes() == raw for p, raw in before.items()))

    def test_space_refusal_reports_the_actual_cache_and_counts_without_downloading_or_lowering_the_limit(self):
        repo = Repository()
        required = len(repo.payload) * 3 + 64 * 1024**2
        with tempfile.TemporaryDirectory(prefix='ccb-update-space-') as tmp:
            cache = Path(tmp) / 'updates'
            with patch.object(client.shutil, 'disk_usage', return_value=SimpleNamespace(free=required - 1)):
                with self.assertRaises(client.UpdateSpaceRequired) as raised:
                    client.execute(repo.installed, cache, download=True, fetcher=repo)
            result = client.failure_report(raised.exception)
            self.assertEqual({'status': 'FAILED', 'reason': 'UPDATE_SPACE_REQUIRED', 'cache': str(cache),
                              'requiredBytes': required, 'availableBytes': required - 1}, result)
            _, targets_url = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
            self.assertFalse(any(url.startswith(targets_url) for url in repo.requests))
            with patch.object(client.shutil, 'disk_usage', return_value=SimpleNamespace(free=required)):
                ready = client.execute(repo.installed, cache, download=True, fetcher=repo)
            self.assertEqual('DOWNLOADED_VERIFIED', ready['status'])
            self.assertEqual(repo.payload, Path(ready['installer']).read_bytes())

    def test_frozen_public1233_updater_downloads_and_reverifies_bridge_before_new_client_reads_stable(self):
        frozen = Path(__file__).parent / 'fixtures' / 'desktop_update_1233.py'
        origin = json.loads(frozen.with_suffix('.origin.json').read_text())
        self.assertEqual(origin['sha256'], hashlib.sha256(frozen.read_bytes()).hexdigest())
        spec = importlib.util.spec_from_file_location('ccb_frozen_public1233_update_client', frozen)
        legacy = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = legacy
        spec.loader.exec_module(legacy)
        repo = Repository(version=1234, installed=1233)
        repo.custom['storageVersion'] = '0.1.0-preview.1234'; repo.build(metadata_version=7)
        old = legacy.Installed(1233, '0.1.0-preview.1233', 'a' * 40, 'preview', 1, repo.bootstrap)
        with tempfile.TemporaryDirectory(prefix='ccb-frozen-client-bridge-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            bridge = legacy.execute(old, cache, download=True, fetcher=repo, expected_version='0.1.0-preview.1234')
            self.assertEqual('DOWNLOADED_VERIFIED', bridge['status'])
            checked = legacy.execute(old, cache, verify_file=Path(bridge['installer']), fetcher=repo, expected_version='0.1.0-preview.1234')
            self.assertEqual(bridge['sha256'], checked['sha256'])
            preview_state = (cache / 'metadata' / 'timestamp.json').read_bytes()
            installed_bridge = client.Installed(1234, '0.1.0-preview.1234', 'b' * 40, 'preview', 1, repo.bootstrap)
            repo.channel = 'stable'; repo.payload = b'SYNTHETIC STABLE AFTER ACTUAL OLD CLIENT BRIDGE NEVER RUN'
            repo.custom.update(channel='stable', version=1235, displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                               sourceSha='c' * 40, github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
            repo.build(metadata_version=1)
            stable = client.execute(installed_bridge, cache, download=True, fetcher=repo)
            self.assertEqual('DOWNLOADED_VERIFIED', stable['status']); self.assertEqual('1.0.0', stable['displayVersion'])
            self.assertEqual(preview_state, (cache / 'metadata' / 'timestamp.json').read_bytes())

    def test_stable_producer_and_installed_config_bind_true_display_build_storage_and_original_bootstrap(self):
        with tempfile.TemporaryDirectory(prefix='ccb-stable-signing-synthetic-') as tmp:
            base = Path(tmp); keys = base / 'keys'; root = base / 'root.json'
            release_metadata.initialize(keys, root)
            package = base / 'synthetic-installer.exe'; package.write_bytes(b'SYNTHETIC STABLE NEVER EXECUTED')
            notes = base / 'notes.txt'; notes.write_text('Synthetic stable notes', encoding='utf-8')
            manifest = base / 'manifest.json'
            inventory = dict(product=client.PRODUCT, version='1.0.0', commit='b' * 40, buildNumber=1235,
                             channel='stable', storageVersion='1.0.0-preview.1235', files=[dict(bytes=1234)])
            manifest.write_text(json.dumps(inventory), encoding='utf-8')
            output = base / 'metadata'
            release_metadata.publish(keys, root, package, 1235, 'b' * 40, notes, output, 1, manifest, display_version='1.0.0')
            repo = Repository(payload=package.read_bytes()); repo.channel = 'stable'
            repo.installed = client.Installed(1234, '0.1.0-preview.1234', 'a' * 40, 'preview', 1, root.read_bytes())
            repo.files = {name: (output / name).read_bytes() for name in ('timestamp.json', 'snapshot.json', 'targets.json')}
            result = client.execute(repo.installed, base / 'cache', download=True, fetcher=repo, expected_version='1.0.0')
            self.assertEqual('DOWNLOADED_VERIFIED', result['status']); self.assertEqual('1.0.0', result['displayVersion'])
            program = base / 'program'; folder = program / 'app' / 'desktop-updater'; folder.mkdir(parents=True)
            (folder / 'update-root.json').write_bytes(root.read_bytes())
            config = dict(format=1, product=client.PRODUCT, platform='windows-x86_64', version=1235, displayVersion='1.0.0',
                          channel='stable', storageVersion='1.0.0-preview.1235', sourceSha='b' * 40, reader=1,
                          rootSha256=hashlib.sha256(root.read_bytes()).hexdigest())
            (folder / 'update-config.json').write_text(json.dumps(config))
            loaded = client.Installed.load(program)
            self.assertEqual(1235, loaded.version); self.assertEqual('1.0.0', loaded.display_version)
            self.assertEqual('stable', loaded.channel); self.assertEqual(root.read_bytes(), loaded.bootstrap)
            for change in (dict(version=None), dict(version=True), dict(storageVersion='1.0.0-preview.9999'), dict(channel='preview')):
                (folder / 'update-config.json').write_text(json.dumps(dict(config, **change)))
                with self.assertRaises(client.UpdateRejected): client.Installed.load(program)
            manifest.write_text(json.dumps(dict(inventory, storageVersion='1.0.0-preview.9999')))
            with self.assertRaises(ValueError):
                release_metadata.publish(keys, root, package, 1235, 'b' * 40, notes, base / 'refused', 2, manifest, display_version='1.0.0')
            self.assertFalse((base / 'refused').exists())
            for index, change in enumerate((dict(buildNumber=None), dict(buildNumber=True), dict(channel=None), dict(storageVersion=None))):
                legacy = dict(inventory, version='0.1.0-preview.1234', buildNumber=1234, channel='preview', storageVersion='0.1.0-preview.1234')
                legacy.update(change); manifest.write_text(json.dumps(legacy))
                refused = base / ('refused-legacy-' + str(index))
                with self.assertRaises(ValueError):
                    release_metadata.publish(keys, root, package, 1234, 'b' * 40, notes, refused, 2, manifest)
                self.assertFalse(refused.exists())

    def test_bridge_stable_and_next_stable_keep_signed_channel_counters_and_reverification_separate(self):
        repo = Repository(version=1234, installed=1234)
        repo.custom['sourceSha'] = repo.installed.source_sha
        repo.build(metadata_version=7)
        with tempfile.TemporaryDirectory(prefix='ccb-stable-update-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            self.assertEqual('UP_TO_DATE', client.execute(repo.installed, cache, fetcher=repo)['status'])
            original_preview_state = (cache / 'metadata' / 'timestamp.json').read_bytes()
            repo.channel = 'stable'
            repo.custom.update(channel='stable', version=1235, displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                               sourceSha='b' * 40, github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
            repo.build(metadata_version=1)
            ready = client.execute(repo.installed, cache, download=True, fetcher=repo, expected_version='1.0.0')
            self.assertEqual('DOWNLOADED_VERIFIED', ready['status'])
            self.assertEqual('stable', ready['channel']); self.assertEqual('1.0.0', ready['displayVersion'])
            self.assertEqual(original_preview_state, (cache / 'metadata' / 'timestamp.json').read_bytes())
            verified = client.execute(repo.installed, cache, verify_file=Path(ready['installer']), fetcher=repo, expected_version='1.0.0')
            self.assertEqual(ready['sha256'], verified['sha256'])
            installed_stable = client.Installed(1235, '1.0.0', 'b' * 40, 'stable', 1, repo.bootstrap)
            repo.custom.update(version=1236, displayVersion='1.0.1', storageVersion='1.0.1-preview.1236', sourceSha='c' * 40,
                               github=dict(tag='desktop-v1.0.1', asset='ChatChatBar-Desktop-1.0.1-windows-x64.exe'))
            repo.payload = b'SYNTHETIC NEXT STABLE INSTALLER NEVER RUN'
            repo.build(metadata_version=2)
            later = client.execute(installed_stable, cache, download=True, fetcher=repo, expected_version='1.0.1')
            self.assertEqual('DOWNLOADED_VERIFIED', later['status'])
            self.assertEqual(1236, later['version'])
            self.assertEqual(original_preview_state, (cache / 'metadata' / 'timestamp.json').read_bytes())

    def test_bridge_ordinary_startup_finds_stable_and_only_unpublished_timestamp_returns_to_preview(self):
        repo = Repository(version=1235, installed=1234); repo.channel = 'stable'
        repo.custom.update(channel='stable', displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                           github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
        repo.build()
        with tempfile.TemporaryDirectory(prefix='ccb-startup-stable-synthetic-') as tmp:
            cache = Path(tmp) / 'updates'
            result = client.execute(repo.installed, cache, fetcher=repo)
            self.assertEqual('AVAILABLE', result['status']); self.assertEqual('1.0.0', result['displayVersion'])
            self.assertEqual('stable', result['channel'])
            timestamp = (cache / 'metadata-stable' / 'timestamp.json').read_bytes()
            del repo.files['timestamp.json']; before = len(repo.requests)
            with self.assertRaises(Exception): client.execute(repo.installed, cache, fetcher=repo)
            self.assertEqual(timestamp, (cache / 'metadata-stable' / 'timestamp.json').read_bytes())
            preview, _ = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
            self.assertFalse(any(request.startswith(preview) for request in repo.requests[before:]))
        absent = Repository()
        with tempfile.TemporaryDirectory(prefix='ccb-startup-preview-synthetic-') as tmp:
            phases = []
            result = client.execute(absent.installed, Path(tmp) / 'updates', fetcher=absent, report=phases.append)
            self.assertEqual('preview', result['channel'])
            self.assertIn(dict(phase='STABLE_NOT_PUBLISHED'), phases)
        for missing in ('snapshot.json', 'targets.json'):
            damaged = Repository(version=1235, installed=1234); damaged.channel = 'stable'
            damaged.custom.update(channel='stable', displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                                  github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
            damaged.build(); del damaged.files[missing]
            with tempfile.TemporaryDirectory(prefix='ccb-startup-damaged-synthetic-') as tmp:
                with self.assertRaises(Exception): client.execute(damaged.installed, Path(tmp) / 'updates', fetcher=damaged)
                preview, _ = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
                self.assertFalse(any(request.startswith(preview) for request in damaged.requests))
        invalid = Repository(version=1235, installed=1234); invalid.channel = 'stable'
        invalid.custom.update(channel='stable', displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                              github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
        invalid.build(targets_signer=CryptoSigner.generate_ed25519())
        with tempfile.TemporaryDirectory(prefix='ccb-startup-signature-synthetic-') as tmp:
            with self.assertRaises(Exception): client.execute(invalid.installed, Path(tmp) / 'updates', fetcher=invalid)
            preview, _ = endpoints(client.REPOSITORY, 'preview', client.METADATA_BRANCH)
            self.assertFalse(any(request.startswith(preview) for request in invalid.requests)); self.assertFalse(invalid.authorized)

    def test_valid_older_stable_does_not_hide_a_newer_preview_or_share_rollback_counters(self):
        repo = Repository(version=1235, installed=2000); repo.channel = 'stable'
        repo.installed = client.Installed(2000, '1.1.0-beta.1', 'a' * 40, 'preview', 1, repo.bootstrap)
        repo.custom.update(channel='stable', displayVersion='1.0.0', storageVersion='1.0.0-preview.1235',
                           github=dict(tag='desktop-v1.0.0', asset='ChatChatBar-Desktop-1.0.0-windows-x64.exe'))
        repo.build(metadata_version=1); stable_files = dict(repo.files)
        repo.custom.update(channel='preview', version=2001, displayVersion='1.1.0-beta.2', storageVersion='1.1.0-preview.2001',
                           github=dict(tag='desktop-v1.1.0-beta.2', asset='ChatChatBar-Desktop-1.1.0-beta.2-windows-x64.exe'))
        repo.build(metadata_version=7); repo.extra_channels['preview'] = dict(repo.files); repo.files = stable_files
        with tempfile.TemporaryDirectory(prefix='ccb-newer-preview-synthetic-') as tmp:
            phases = []
            result = client.execute(repo.installed, Path(tmp) / 'updates', fetcher=repo, report=phases.append)
            self.assertEqual('1.1.0-beta.2', result['displayVersion']); self.assertEqual('preview', result['channel'])
            self.assertIn(dict(phase='STABLE_NOT_NEWER'), phases)

    def test_stable_signed_source_storage_channel_and_two_ordering_axes_are_not_relaxed(self):
        installed_version = 1235
        for change in (dict(version=1236, displayVersion='0.9.9', storageVersion='0.9.9-preview.1236'),
                       dict(version=1234, displayVersion='1.0.1', storageVersion='1.0.1-preview.1234'),
                       dict(version=1236, displayVersion='1.0.0', storageVersion='1.0.0-preview.1236'),
                       dict(storageVersion='1.0.1-preview.9999'), dict(channel='preview')):
            repo = Repository(version=1236, installed=1234); repo.channel = 'stable'
            repo.installed = client.Installed(installed_version, '1.0.0', 'a' * 40, 'stable', 1, repo.bootstrap)
            repo.custom.update(version=1236, displayVersion='1.0.1', channel='stable', storageVersion='1.0.1-preview.1236',
                               github=dict(tag='desktop-v1.0.1', asset='ChatChatBar-Desktop-1.0.1-windows-x64.exe'))
            repo.custom.update(change); repo.build()
            with tempfile.TemporaryDirectory(prefix='ccb-stable-refusal-synthetic-') as tmp:
                with self.assertRaises(client.UpdateRejected):
                    client.execute(repo.installed, Path(tmp) / 'updates', fetcher=repo, expected_version='1.0.1')
                self.assertFalse(repo.authorized)

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
