"""Desktop-owned TUF adapter; python-tuf owns signature, expiry and rollback policy.

The installed program supplies the immutable bootstrap. Release discovery, writable
receipts and downloaded installers cannot introduce a trust root. No data access.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import uuid
from tuf.ngclient import Updater
from tuf.ngclient.config import UpdaterConfig
from tuf.ngclient.fetcher import FetcherInterface
from tuf.api.exceptions import DownloadHTTPError
from github_transport import GitHubFetcher, endpoints
from version_identity import ReleaseIdentity, release_channel, compare_display

PRODUCT = 'ccb-desktop-nsis-v1'
REPOSITORY = 'MisakaPiano/ChatChatBar-Desktop'
METADATA_BRANCH = 'codex/update-metadata'


class UpdateRejected(ValueError):
    pass


def safe(path):
    path = Path(os.path.abspath(path))
    for ancestor in (path, *path.parents):
        if ancestor.exists():
            info = ancestor.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                raise UpdateRejected('UPDATE_REPARSE_REFUSED')
    return path


def read_json(path, limit=1024 * 1024):
    safe(path)
    if path.stat().st_size > limit:
        raise UpdateRejected('UPDATE_METADATA_LIMIT')
    return json.loads(path.read_text(encoding='utf-8'))


@dataclass(frozen=True)
class Installed:
    version: int
    display_version: str
    source_sha: str
    channel: str
    reader: int
    bootstrap: bytes

    @staticmethod
    def load(package):
        folder = safe(package) / 'app' / 'desktop-updater'
        data = read_json(folder / 'update-config.json', 16384)
        if type(data.get('format')) is not int or data['format'] != 1 or data.get('product') != PRODUCT or data.get('platform') != 'windows-x86_64':
            raise UpdateRejected('INSTALLED_UPDATE_IDENTITY_REFUSED')
        version = data.get('version')
        try:
            if type(version) is not int:
                raise ValueError('INSTALLED_BUILD_NUMBER_REQUIRED')
            identity = ReleaseIdentity.create(data.get('displayVersion'), version, data.get('channel'))
            if data.get('storageVersion', data.get('displayVersion')) != identity.storage_version:
                raise ValueError('INSTALLED_STORAGE_IDENTITY_REFUSED')
        except ValueError:
            raise UpdateRejected('INSTALLED_UPDATE_VERSION_REFUSED')
        if not re.fullmatch('[0-9a-f]{40}', data.get('sourceSha', '')) or data.get('channel') != identity.channel or type(data.get('reader')) is not int or data['reader'] != 1:
            raise UpdateRejected('INSTALLED_UPDATE_CONTRACT_REFUSED')
        root_file = folder / 'update-root.json'
        safe(root_file)
        if root_file.stat().st_size > 256 * 1024:
            raise UpdateRejected('BOOTSTRAP_SIZE_REFUSED')
        root = root_file.read_bytes()
        if hashlib.sha256(root).hexdigest() != data.get('rootSha256'):
            raise UpdateRejected('INSTALLED_BOOTSTRAP_CHANGED')
        return Installed(version, data['displayVersion'], data['sourceSha'], data['channel'], data['reader'], root)


@contextmanager
def exclusive(cache):
    import msvcrt
    safe(cache)
    if cache.exists() and any(cache.iterdir()):
        if (cache / 'owner.txt').read_text(encoding='utf-8') != PRODUCT:
            raise UpdateRejected('FOREIGN_UPDATE_CACHE')
    else:
        cache.mkdir(parents=True, exist_ok=True)
        (cache / 'owner.txt').write_text(PRODUCT, encoding='utf-8')
    with (cache / 'client.lock').open('a+b') as stream:
        if stream.seek(0, 2) == 0:
            stream.write(b'0'); stream.flush()
        stream.seek(0)
        try:
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise UpdateRejected('UPDATE_BUSY') from None
        try:
            yield
        finally:
            stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def inspect(updater, installed, channel=None, observe_obsolete=False):
    updater.refresh()
    target = updater.get_targetinfo('desktop-installer.exe')
    if target is None:
        raise UpdateRejected('SIGNED_TARGET_MISSING')
    custom = target.unrecognized_fields.get('custom', {})
    if (custom.get('product') != PRODUCT or custom.get('platform') != 'windows-x86_64'
            or custom.get('channel') != (channel or installed.channel) or type(custom.get('version')) is not int
            or not observe_obsolete and custom['version'] < installed.version):
        raise UpdateRejected('SIGNED_TARGET_IDENTITY_REFUSED')
    try:
        current = ReleaseIdentity.create(installed.display_version, installed.version, installed.channel)
        identity = ReleaseIdentity.create(custom.get('displayVersion'), custom['version'], custom.get('channel'))
        obsolete = observe_obsolete and identity.build <= current.build and compare_display(identity.display, current.display) <= 0
        if not obsolete:
            current.permits_target(identity)
        if custom.get('storageVersion', custom.get('displayVersion')) != identity.storage_version:
            raise ValueError('SIGNED_STORAGE_IDENTITY_REFUSED')
    except ValueError:
        raise UpdateRejected('SIGNED_TARGET_VERSION_REFUSED')
    if not re.fullmatch('[0-9a-f]{40}', custom.get('sourceSha', '')):
        raise UpdateRejected('SIGNED_TARGET_VERSION_REFUSED')
    minimum, maximum = custom.get('minimum_reader'), custom.get('maximum_reader')
    if type(minimum) is not int or type(maximum) is not int or not minimum <= installed.reader <= maximum:
        raise UpdateRejected('UPDATE_READER_INCOMPATIBLE')
    # Data-format migrations are separate explicit workflows, never inferred by an updater.
    output_reader = custom.get('data_version', installed.reader)
    if type(output_reader) is not int or output_reader != installed.reader:
        raise UpdateRejected('UPDATE_DATA_TRANSITION_REQUIRED')
    if (type(custom.get('installedBytes')) is not int or not 0 < custom['installedBytes'] <= 4 * 1024**3
            or not isinstance(custom.get('notes'), str) or len(custom['notes']) > 16000
            or not 0 < target.length <= 1024**3 or not re.fullmatch('[0-9a-f]{64}', target.hashes.get('sha256', ''))):
        raise UpdateRejected('SIGNED_TARGET_PROPERTIES_REFUSED')
    location = custom.get('github', {})
    if (location.get('tag') != 'desktop-v' + custom['displayVersion']
            or location.get('asset') != f"ChatChatBar-Desktop-{custom['displayVersion']}-windows-x64.exe"):
        raise UpdateRejected('SIGNED_TARGET_LOCATION_REFUSED')
    if custom['version'] == installed.version and custom['sourceSha'] != installed.source_sha:
        raise UpdateRejected('CURRENT_TARGET_SOURCE_CHANGED')
    return target, custom


class ProgressFetcher(FetcherInterface):
    def __init__(self, delegate, report):
        self.delegate, self.report, self.total = delegate, report, None
        self.last_url = None

    def _fetch(self, url):
        self.last_url = url
        received = 0
        last = time.monotonic()
        for chunk in self.delegate.fetch(url):
            received += len(chunk)
            now = time.monotonic()
            if self.total is not None and (now - last >= .25 or received >= self.total):
                self.report({'phase': 'DOWNLOADING', 'received': received, 'total': self.total})
                last = now
            yield chunk


def execute(installed, cache, download=False, verify_file=None, fetcher=None, report=lambda _: None, expected_version=None, proxy_routes=None):
    cache = safe(cache)
    report({'phase': 'CHECKING'})
    try:
        channel = release_channel(expected_version) if expected_version is not None else installed.channel
    except ValueError:
        raise UpdateRejected('UPDATE_SELECTION_CHANGED')
    with exclusive(cache):
        downloads = cache / 'downloads'
        downloads.mkdir(exist_ok=True); safe(downloads)
        startup_bridge = expected_version is None and installed.channel == 'preview'
        for channel in (['stable', 'preview'] if startup_bridge else [channel]):
            # Retain the original Preview rollback state; each signed channel has its own counter history.
            metadata = cache / ('metadata' if channel == 'preview' else 'metadata-stable')
            metadata.mkdir(exist_ok=True); safe(metadata)
            had_timestamp = safe(metadata / 'timestamp.json').exists()
            delegate = fetcher or GitHubFetcher(REPOSITORY, channel, metadata_branch=METADATA_BRANCH, proxy_routes=proxy_routes)
            observed = ProgressFetcher(delegate, report)
            metadata_url, target_url = endpoints(REPOSITORY, channel, METADATA_BRANCH)
            updater = Updater(str(metadata), metadata_url, str(downloads), target_url,
                              bootstrap=installed.bootstrap, fetcher=observed,
                              config=UpdaterConfig(app_user_agent='ChatChatBarDesktop'))
            try:
                target, custom = inspect(updater, installed, channel, observe_obsolete=startup_bridge and channel == 'stable')
            except DownloadHTTPError as error:
                if (startup_bridge and channel == 'stable' and not had_timestamp and error.status_code == 404
                        and observed.last_url == metadata_url + 'timestamp.json'):
                    report({'phase': 'STABLE_NOT_PUBLISHED'})
                    continue
                raise
            if (startup_bridge and channel == 'stable' and custom['version'] <= installed.version
                    and compare_display(custom['displayVersion'], installed.display_version) <= 0):
                report({'phase': 'STABLE_NOT_NEWER'})
                continue
            break
        if expected_version is not None and custom['displayVersion'] != expected_version:
            raise UpdateRejected('UPDATE_SELECTION_CHANGED')
        delegate.authorize(target)
        result = {k: custom[k] for k in ('version', 'displayVersion', 'sourceSha', 'channel', 'notes', 'installedBytes')}
        result.update(status='AVAILABLE', bytes=target.length, sha256=target.hashes['sha256'])
        if verify_file is not None:
            file = safe(verify_file)
            if file.parent.parent != downloads or not re.fullmatch('[a-f0-9]{32}', file.parent.name) or file.name != 'desktop-installer.exe':
                raise UpdateRejected('DOWNLOADED_FILE_LOCATION_REFUSED')
            with file.open('rb') as stream:
                target.verify_length_and_hashes(stream)
            result.update(status='DOWNLOADED_VERIFIED', installer=str(file))
        elif custom['version'] == installed.version:
            result['status'] = 'UP_TO_DATE'
        elif download:
            # A receipt is never authority: reauthenticate the target above, then hash bytes again.
            rejected = False
            stages = list(downloads.iterdir())
            if len(stages) > 1000: raise UpdateRejected('UPDATE_CACHE_LIMIT')
            for prior in stages:
                if not re.fullmatch('[a-f0-9]{32}', prior.name): continue
                safe(prior)
                try:
                    hint = read_json(prior / 'VERIFIED.json', 32768)
                except Exception:
                    rejected = True
                    continue
                if hint.get('sha256') != target.hashes['sha256'] or hint.get('bytes') != target.length: continue
                candidate = prior / 'desktop-installer.exe'
                if not candidate.is_file(): continue
                safe(candidate)
                try:
                    with candidate.open('rb') as stream: target.verify_length_and_hashes(stream)
                except Exception:
                    rejected = True
                    continue
                result.update(status='DOWNLOADED_VERIFIED', installer=str(candidate), reused=True, cacheRejected=rejected)
                report(result)
                return result
            if rejected:
                report({'phase': 'CACHE_REJECTED'})
            if shutil.disk_usage(cache).free < target.length * 3 + 64 * 1024**2:
                raise UpdateRejected('UPDATE_SPACE_REQUIRED')
            stage = downloads / (uuid.uuid4().hex + '.incomplete')
            stage.mkdir()
            file = stage / 'desktop-installer.exe'
            observed.total = target.length
            updater.download_target(target, filepath=str(file))
            report({'phase': 'VERIFYING', 'received': target.length, 'total': target.length})
            with file.open('rb+') as stream:
                os.fsync(stream.fileno()); stream.seek(0); target.verify_length_and_hashes(stream)
            with (stage / 'VERIFIED.json').open('x', encoding='utf-8') as stream:
                json.dump(result, stream); stream.flush(); os.fsync(stream.fileno())
            final = stage.with_suffix('')
            os.rename(stage, final)
            result.update(status='DOWNLOADED_VERIFIED', installer=str(final / file.name), cacheRejected=rejected)
        report(result)
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--verify-file', type=Path)
    parser.add_argument('--expected-version')
    parser.add_argument('--proxy-routes')
    args = parser.parse_args()
    package, cache = safe(args.package), safe(args.cache)
    try:
        if cache == package or cache.is_relative_to(package) or package.is_relative_to(cache):
            raise UpdateRejected('UPDATE_CACHE_OVERLAP')
        if args.proxy_routes is not None and len(args.proxy_routes) > 8192:
            raise UpdateRejected('UPDATE_PROXY_ROUTES_LIMIT')
        routes = json.loads(args.proxy_routes) if args.proxy_routes is not None else None
        execute(Installed.load(package), cache, args.download, args.verify_file,
                report=lambda value: print(json.dumps(value, ensure_ascii=True), flush=True),
                expected_version=args.expected_version, proxy_routes=routes)
        return 0
    except Exception as failure:
        reason = str(failure) if isinstance(failure, UpdateRejected) else type(failure).__name__
        print(json.dumps({'status': 'FAILED', 'reason': reason}), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
