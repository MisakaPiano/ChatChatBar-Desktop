"""Desktop-owned TUF adapter; python-tuf owns signature, expiry and rollback policy.

The installed program supplies the immutable bootstrap. Release discovery, writable
receipts and downloaded installers cannot introduce a trust root. No data access.
"""
from contextlib import contextmanager
from dataclasses import dataclass
import argparse
import base64
import binascii
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


class UpdateSpaceRequired(UpdateRejected):
    def __init__(self, cache, required, available):
        super().__init__('UPDATE_SPACE_REQUIRED')
        self.cache, self.required, self.available = cache, required, available


def failure_report(failure):
    reason = str(failure) if isinstance(failure, UpdateRejected) else type(failure).__name__
    result = {'status': 'FAILED', 'reason': reason}
    if isinstance(failure, UpdateSpaceRequired):
        result.update(cache=str(failure.cache), requiredBytes=failure.required, availableBytes=failure.available)
    return result


def _cache_receipt_bytes(path):
    safe(path)
    with path.open('rb') as stream:
        raw = stream.read(32769)
    if len(raw) > 32768:
        raise UpdateRejected('UPDATE_METADATA_LIMIT')
    return raw


def _cache_receipt_object(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise UpdateRejected('UPDATE_CACHE_RECORD_DUPLICATE')
            value[key] = item
        return value
    return json.loads(raw.decode('utf-8'), object_pairs_hook=unique)


def _delete_verified_cached_installer(path, expected_bytes, expected_sha, remove=True):
    """Delete only the exact, exclusively leased regular file; no path-based unlink race."""
    if os.name != 'nt':
        return 'PLATFORM_UNAVAILABLE'
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p,
                                  wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.GetFileInformationByHandle.argtypes = [wintypes.HANDLE, ctypes.c_void_p]
    kernel.GetFinalPathNameByHandleW.argtypes = [wintypes.HANDLE, wintypes.LPWSTR, wintypes.DWORD, wintypes.DWORD]
    kernel.ReadFile.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p]
    kernel.SetFileInformationByHandle.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    class Info(ctypes.Structure):
        _fields_ = [('attributes', wintypes.DWORD), ('created', wintypes.FILETIME), ('accessed', wintypes.FILETIME),
                    ('written', wintypes.FILETIME), ('volume', wintypes.DWORD), ('high', wintypes.DWORD),
                    ('low', wintypes.DWORD), ('links', wintypes.DWORD), ('index_high', wintypes.DWORD), ('index_low', wintypes.DWORD)]
    handle = kernel.CreateFileW(str(path), 0x80000000 | (0x10000 if remove else 0), 0, None, 3, 0x00200000 | 0x08000000, None)
    if handle == ctypes.c_void_p(-1).value:
        return 'BUSY_OR_DENIED'
    try:
        info = Info()
        if not kernel.GetFileInformationByHandle(handle, ctypes.byref(info)):
            return 'IDENTITY_UNAVAILABLE'
        if info.attributes & (0x400 | 0x10) or info.links != 1 or (info.high << 32 | info.low) != expected_bytes:
            return 'IDENTITY_REFUSED'
        def matches_path():
            buffer = ctypes.create_unicode_buffer(32768)
            count = kernel.GetFinalPathNameByHandleW(handle, buffer, len(buffer), 0)
            if not 0 < count < len(buffer):
                return False
            name = buffer.value
            if name.startswith('\\\\?\\UNC\\'):
                name = '\\\\' + name[8:]
            elif name.startswith('\\\\?\\'):
                name = name[4:]
            return os.path.normcase(os.path.normpath(name)) == os.path.normcase(str(path))
        if not matches_path():
            return 'LOCATION_REFUSED'
        digest = hashlib.sha256(); total = 0; buffer = ctypes.create_string_buffer(256 * 1024)
        count = wintypes.DWORD(); first = True
        while True:
            if not kernel.ReadFile(handle, buffer, len(buffer), ctypes.byref(count), None):
                return 'READ_FAILED'
            if not count.value:
                break
            chunk = buffer.raw[:count.value]
            if first and chunk[:2] != b'MZ':
                return 'NOT_INSTALLER'
            first = False; total += count.value
            if total > expected_bytes:
                return 'CHANGED'
            digest.update(chunk)
        if total != expected_bytes or digest.hexdigest() != expected_sha or not matches_path():
            return 'CHANGED'
        if not remove:
            return 'VERIFIED'
        remove = ctypes.c_ubyte(1)
        if not kernel.SetFileInformationByHandle(handle, 4, ctypes.byref(remove), ctypes.sizeof(remove)):
            return 'DELETE_REFUSED'
    finally:
        kernel.CloseHandle(handle)
    return 'REMOVED' if not path.exists() else 'DELETE_UNCONFIRMED'


def cleanup_downloads(installed, cache, protected_version=None):
    """Explicit cache maintenance. Receipt hints never authorize installation or reset TUF state."""
    cache = safe(cache)
    if cache.exists() and any(cache.iterdir()):
        owner = safe(cache / 'owner.txt')
        if not owner.is_file() or owner.stat().st_nlink != 1 or owner.stat().st_size != len(PRODUCT) or owner.read_text(encoding='utf-8') != PRODUCT:
            raise UpdateRejected('FOREIGN_UPDATE_CACHE')
    if protected_version is not None:
        try:
            release_channel(protected_version)
        except ValueError:
            raise UpdateRejected('UPDATE_SELECTION_CHANGED') from None
    result = dict(status='CACHE_CLEANED', cache=str(cache), removedBytes=0, removedCount=0,
                  retainedCount=0, missingCount=0, unavailableCount=0, issues=[])
    with exclusive(cache):
        # Cached roles only add protection; they never authorize deletion or installation.
        # This also retains the current target of the other signed channel/profile.
        protected_hashes = set()
        for name in ('metadata', 'metadata-stable'):
            target_metadata = cache / name / 'targets.json'
            if not target_metadata.exists():
                continue
            try:
                envelope = read_json(target_metadata, 256 * 1024)
                target = envelope['signed']['targets'].get('desktop-installer.exe')
                if target is not None:
                    digest = target['hashes']['sha256']
                    if not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest):
                        raise ValueError('INVALID_TARGET_HINT')
                    protected_hashes.add(digest)
            except Exception:
                raise UpdateRejected('UPDATE_CACHE_METADATA_UNREADABLE') from None
        home = cache / 'downloads'
        if home.exists():
            safe(home)
            entries = list(home.iterdir())
            if len(entries) > 1000:
                raise UpdateRejected('UPDATE_CACHE_LIMIT')
            candidates = []
            def issue(stage, reason):
                result['unavailableCount'] += 1
                if len(result['issues']) < 20:
                    result['issues'].append(dict(stage=stage if re.fullmatch('[a-f0-9]{32}', stage) else 'UNRECOGNIZED', reason=reason))
            for stage in entries:
                try:
                    if not re.fullmatch('[a-f0-9]{32}', stage.name):
                        issue(stage.name, 'UNKNOWN_STAGE'); continue
                    safe(stage)
                    if not stage.is_dir():
                        issue(stage.name, 'INCOMPLETE_OR_UNKNOWN_MEMBERS'); continue
                    members = {item.name for item in stage.iterdir()}
                    if members not in ({'VERIFIED.json', 'desktop-installer.exe'}, {'VERIFIED.json'}):
                        issue(stage.name, 'INCOMPLETE_OR_UNKNOWN_MEMBERS'); continue
                    receipt = stage / 'VERIFIED.json'; safe(receipt)
                    if not receipt.is_file() or receipt.stat().st_nlink != 1:
                        issue(stage.name, 'RECEIPT_IDENTITY'); continue
                    receipt_raw = _cache_receipt_bytes(receipt)
                    hint = _cache_receipt_object(receipt_raw)
                    build = hint.get('version'); display = hint.get('displayVersion', f'0.1.0-preview.{build}')
                    channel = hint.get('channel', 'preview')
                    if type(build) is not int or hint.get('status') != 'AVAILABLE':
                        issue(stage.name, 'RECEIPT_IDENTITY'); continue
                    ReleaseIdentity.create(display, build, channel)
                    size = hint.get('bytes'); digest = hint.get('sha256')
                    if (type(size) is not int or not 2 <= size <= 1024**3 or not isinstance(digest, str)
                            or not re.fullmatch('[a-f0-9]{64}', digest) or not re.fullmatch('[a-f0-9]{40}', hint.get('sourceSha', ''))):
                        issue(stage.name, 'RECEIPT_IDENTITY'); continue
                    if 'desktop-installer.exe' not in members:
                        result['missingCount'] += 1; continue
                    file = stage / 'desktop-installer.exe'; safe(file)
                    if not file.is_file() or file.stat().st_size != size:
                        issue(stage.name, 'INSTALLER_IDENTITY'); continue
                    verified = _delete_verified_cached_installer(file, size, digest, remove=False)
                    if verified != 'VERIFIED':
                        issue(stage.name, verified); continue
                    candidates.append(dict(stage=stage, file=file, bytes=size, sha256=digest, build=build,
                                           display=display, receipt=receipt, receipt_sha=hashlib.sha256(receipt_raw).hexdigest()))
                except Exception:
                    issue(stage.name, 'SOURCE_UNVERIFIED')
            recent = {item['stage'] for item in sorted(candidates, key=lambda x: (x['build'], x['stage'].name), reverse=True)[:2]}
            for item in candidates:
                if (item['stage'] in recent or item['sha256'] in protected_hashes or item['build'] == installed.version or
                        item['display'] in (installed.display_version, protected_version)):
                    result['retainedCount'] += 1; continue
                try:
                    if (safe(item['receipt']).stat().st_size > 32768 or
                            hashlib.sha256(_cache_receipt_bytes(item['receipt'])).hexdigest() != item['receipt_sha'] or
                            {p.name for p in item['stage'].iterdir()} != {'VERIFIED.json', 'desktop-installer.exe'}):
                        issue(item['stage'].name, 'SOURCE_CHANGED'); continue
                    state = _delete_verified_cached_installer(safe(item['file']), item['bytes'], item['sha256'])
                except Exception:
                    state = 'SOURCE_UNVERIFIED'
                if state == 'REMOVED':
                    result['removedCount'] += 1; result['removedBytes'] += item['bytes']
                else:
                    issue(item['stage'].name, state)
        try:
            result['availableBytes'] = shutil.disk_usage(cache).free
        except OSError:
            result['spaceReadFailed'] = True
    return result


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
            required = target.length * 3 + 64 * 1024**2
            available = shutil.disk_usage(cache).free
            if available < required:
                raise UpdateSpaceRequired(cache, required, available)
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


def parse_proxy_routes(raw=None, encoded=None):
    if raw is not None and encoded is not None:
        raise UpdateRejected('UPDATE_PROXY_ROUTES_INVALID')
    if encoded is not None:
        if len(encoded) > 10924:
            raise UpdateRejected('UPDATE_PROXY_ROUTES_LIMIT')
        try:
            data = base64.b64decode(encoded, validate=True)
            if len(data) > 8192:
                raise UpdateRejected('UPDATE_PROXY_ROUTES_LIMIT')
            # Refuse noncanonical encodings rather than guessing at malformed argv.
            if base64.b64encode(data).decode('ascii') != encoded:
                raise UpdateRejected('UPDATE_PROXY_ROUTES_INVALID')
            raw = data.decode('utf-8')
        except (ValueError, binascii.Error, UnicodeError) as error:
            if isinstance(error, UpdateRejected):
                raise
            raise UpdateRejected('UPDATE_PROXY_ROUTES_INVALID') from error
    if raw is None:
        return None
    if len(raw) > 8192:
        raise UpdateRejected('UPDATE_PROXY_ROUTES_LIMIT')
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError) as error:
        raise UpdateRejected('UPDATE_PROXY_ROUTES_INVALID') from error


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--clean-cache', action='store_true')
    parser.add_argument('--verify-file', type=Path)
    parser.add_argument('--expected-version')
    routes_group = parser.add_mutually_exclusive_group()
    routes_group.add_argument('--proxy-routes')
    routes_group.add_argument('--proxy-routes-base64')
    args = parser.parse_args()
    package, cache = safe(args.package), safe(args.cache)
    try:
        if cache == package or cache.is_relative_to(package) or package.is_relative_to(cache):
            raise UpdateRejected('UPDATE_CACHE_OVERLAP')
        routes = None if args.clean_cache else parse_proxy_routes(args.proxy_routes, args.proxy_routes_base64)
        if args.clean_cache:
            if args.download or args.verify_file:
                raise UpdateRejected('UPDATE_ARGUMENTS_REFUSED')
            print(json.dumps(cleanup_downloads(Installed.load(package), cache, args.expected_version), ensure_ascii=True), flush=True)
        else:
            execute(Installed.load(package), cache, args.download, args.verify_file,
                    report=lambda value: print(json.dumps(value, ensure_ascii=True), flush=True),
                    expected_version=args.expected_version, proxy_routes=routes)
        return 0
    except Exception as failure:
        print(json.dumps(failure_report(failure)), flush=True)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
