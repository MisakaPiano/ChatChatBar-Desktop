"""Adapted from the user-owned Studio frozen R2 transport. Public GitHub transport. TUF, not GitHub discovery, authorizes each payload.

No account credentials, API latest lookup or implicit redirect following. The
installed config pins the repository; signed target metadata pins the release.
"""
import re
from urllib.parse import urlsplit
import urllib3
from tuf.api.exceptions import DownloadHTTPError
from tuf.ngclient.fetcher import FetcherInterface


def endpoints(repository, channel, metadata_branch='codex/update-metadata'):
    if not isinstance(repository, str) or not re.fullmatch(r'[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid release repository')
    if repository.split('/')[1] in ('.', '..') or channel not in ('preview', 'stable'):
        raise ValueError('Invalid release channel')
    if metadata_branch not in ('codex/update-metadata',):
        raise ValueError('Invalid pinned metadata branch')
    return (f'https://raw.githubusercontent.com/{repository}/{metadata_branch}/{channel}/windows-x86_64/metadata/',
            f'https://github.com/{repository}/releases/download/')


class GitHubFetcher(FetcherInterface):
    def __init__(self, repository, channel, pool=None, metadata_branch='codex/update-metadata'):
        self.metadata, self.targets = endpoints(repository, channel, metadata_branch)
        self.pool = pool or urllib3.PoolManager()
        self.authorized = {}

    def authorize(self, target):
        # Called only after python-tuf authenticates metadata and inspect checks identity.
        location = target.unrecognized_fields.get('custom', {}).get('github', {})
        tag, asset = location.get('tag'), location.get('asset')
        if (not isinstance(tag, str) or not re.fullmatch(r'desktop-v[0-9][A-Za-z0-9._-]{0,79}', tag)
                or not isinstance(asset, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.exe', asset)):
            raise ValueError('Invalid signed release location')
        if target.path != 'desktop-installer.exe':
            raise ValueError('Unexpected installer target')
        url = self.targets + tag + '/' + asset
        self.authorized = {self.targets + target.path: url}
        # TUF consistent-snapshot repositories prefix target files with a hash.
        for value in target.hashes.values():
            if re.fullmatch(r'[0-9a-f]{32,128}', value):
                self.authorized[self.targets + value + '.' + target.path] = url

    def _fetch(self, url):
        if url.startswith(self.metadata) and re.fullmatch(r'(?:[1-9][0-9]*\.)?(?:root|timestamp|snapshot|targets)\.json', url[len(self.metadata):]):
            return self._stream(url, metadata=True)
        if url in self.authorized:
            return self._stream(self.authorized[url], metadata=False)
        raise ValueError('Unapproved update resource')

    def _stream(self, url, metadata):
        for attempt in range(4):
            response = self.pool.request('GET', url, preload_content=False, redirect=False,
                                         retries=False, timeout=urllib3.Timeout(connect=15, read=30),
                                         headers={'User-Agent': 'ChatChatBarDesktop', 'Accept-Encoding': 'identity'})
            if response.status in (301, 302, 303, 307, 308):
                next_url = response.headers.get('Location', '')
                response.close()
                parts = urlsplit(next_url)
                # Raw metadata must not change repo/path. Asset redirects are CDN-only.
                if (metadata or attempt == 3 or parts.scheme != 'https' or parts.port not in (None, 443)
                        or parts.username or parts.password or parts.fragment
                        or parts.hostname not in ('release-assets.githubusercontent.com', 'objects.githubusercontent.com')
                        or any(c in next_url for c in '\r\n\0')):
                    raise ValueError('Unapproved update redirect')
                url = next_url
                continue
            if response.status != 200:
                status = response.status
                response.close()
                raise DownloadHTTPError('Update download HTTP failure', status)
            try:
                yield from response.stream(256 * 1024, decode_content=False)
            finally:
                response.close()
            return
        raise ValueError('Too many update redirects')
