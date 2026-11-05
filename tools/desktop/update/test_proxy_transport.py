"""Real loopback first-byte checks for the isolated updater's public transport; no remote service."""
import concurrent.futures
import os
from pathlib import Path
import socket
import unittest
from unittest.mock import patch

import github_transport
from github_transport import GitHubFetcher, SIGNED_PROXY_HOSTS, validate_proxy_routes


class Recorder:
    def __init__(self):
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(4)
        self.listener.settimeout(3)
        self.port = self.listener.getsockname()[1]
        self.seen = []
        self.executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.future = self.executor.submit(self.record)

    def record(self):
        try:
            while True:
                connection, _ = self.listener.accept()
                with connection:
                    connection.settimeout(3)
                    first = connection.recv(1)
                    if not first:
                        continue
                    if first == b'\x05':
                        count = connection.recv(1)
                        if count:
                            connection.recv(count[0])
                        self.seen.append('SOCKS')
                        connection.sendall(b'\x05\xff')
                    elif first == b'C':
                        data = first
                        while not data.endswith(b'\r\n\r\n') and len(data) < 16384:
                            block = connection.recv(1)
                            if not block:
                                break
                            data += block
                        self.seen.append('CONNECT' if data.startswith(b'CONNECT ') else 'OTHER')
                        connection.sendall(b'HTTP/1.1 502 Fixture refusal\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                    else:
                        self.seen.append('TLS' if first == b'\x16' else 'OTHER')
                        # A deliberately closed TLS endpoint proves the direct physical route.
        except (OSError, TimeoutError):
            pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.listener.close()
        self.future.result(timeout=5)
        self.executor.shutdown(wait=True)


class UpdateProxyTransportTest(unittest.TestCase):
    def routes(self, mode, port=10809):
        return {host: ({'mode': 'DIRECT'} if mode == 'DIRECT' else
                       {'mode': mode, 'host': '127.0.0.1', 'port': port}) for host in SIGNED_PROXY_HOSTS}

    def test_http_ingress_receives_connect_not_socks_and_no_retry(self):
        with Recorder() as proxy:
            fetcher = GitHubFetcher('MisakaPiano/ChatChatBar-Desktop', 'preview', proxy_routes=self.routes('HTTP', proxy.port))
            with self.assertRaises(Exception):
                list(fetcher.fetch(fetcher.metadata + 'timestamp.json'))
            self.assertEqual(['CONNECT'], proxy.seen)

    def test_explicit_socks_ingress_receives_only_socks_and_no_retry(self):
        with Recorder() as proxy:
            fetcher = GitHubFetcher('MisakaPiano/ChatChatBar-Desktop', 'preview', proxy_routes=self.routes('SOCKS', proxy.port))
            with self.assertRaises(Exception):
                list(fetcher.fetch(fetcher.metadata + 'timestamp.json'))
            self.assertEqual(['SOCKS'], proxy.seen)

    def test_direct_ignores_environment_proxy_and_sends_tls_to_target(self):
        with Recorder() as wrong_proxy, Recorder() as target:
            env = {'HTTPS_PROXY': f'http://127.0.0.1:{wrong_proxy.port}',
                   'ALL_PROXY': f'socks5h://127.0.0.1:{wrong_proxy.port}'}
            with patch.dict(os.environ, env):
                fetcher = GitHubFetcher('MisakaPiano/ChatChatBar-Desktop', 'preview', proxy_routes=self.routes('DIRECT'))
                with self.assertRaises(Exception):
                    fetcher._pool_for(fetcher.metadata).request('GET', f'https://127.0.0.1:{target.port}/fixture',
                        retries=False, redirect=False, timeout=2)
                self.assertEqual(['TLS'], target.seen)
                self.assertEqual([], wrong_proxy.seen)

    def test_each_signed_domain_keeps_its_resolved_protocol_and_route(self):
        with Recorder() as first, Recorder() as second:
            routes = self.routes('DIRECT')
            routes['raw.githubusercontent.com'] = {'mode': 'HTTP', 'host': '127.0.0.1', 'port': first.port}
            routes['release-assets.githubusercontent.com'] = {'mode': 'SOCKS', 'host': '127.0.0.1', 'port': second.port}
            fetcher = GitHubFetcher('MisakaPiano/ChatChatBar-Desktop', 'preview', proxy_routes=routes)
            for url in [fetcher.metadata + 'timestamp.json', 'https://release-assets.githubusercontent.com/fixture']:
                with self.assertRaises(Exception):
                    list(fetcher._stream(url, metadata=False))
            self.assertEqual(['CONNECT'], first.seen)
            self.assertEqual(['SOCKS'], second.seen)

    def test_unapproved_destinations_and_malformed_routes_are_refused(self):
        valid = self.routes('HTTP')
        for invalid in [{}, dict(valid, **{'evil.invalid': {'mode': 'DIRECT'}}),
                        dict(valid, **{'github.com': {'mode': 'SYSTEM'}}),
                        dict(valid, **{'github.com': {'mode': 'HTTP', 'host': 'user@host', 'port': 1}}),
                        dict(valid, **{'github.com': {'mode': 'HTTP', 'host': '127.0.0.1', 'port': True}})]:
            with self.assertRaises(ValueError):
                validate_proxy_routes(invalid)
        fetcher = GitHubFetcher('MisakaPiano/ChatChatBar-Desktop', 'preview', proxy_routes=valid)
        with self.assertRaises(ValueError):
            list(fetcher.fetch('https://evil.invalid/fixture'))
        expected = os.environ.get('CCB_UPDATE_PROXY_EXPECTED_MODULE_HOME')
        if expected:
            self.assertEqual(Path(expected).resolve(), Path(github_transport.__file__).resolve().parent)


if __name__ == '__main__':
    unittest.main()
