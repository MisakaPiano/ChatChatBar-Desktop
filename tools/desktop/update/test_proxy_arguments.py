"""Bounded transport decoding; route policy remains owned by GitHubFetcher."""
import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from desktop_update import parse_proxy_routes, UpdateRejected


class ProxyArgumentsTest(unittest.TestCase):
    def test_encoded_and_legacy_keep_destination_types_and_unicode(self):
        routes = {'github.com': {'mode': 'HTTP', 'host': '代理.example', 'port': 10809},
                  'objects.githubusercontent.com': {'mode': 'SOCKS', 'host': '::1', 'port': 1080}}
        raw = json.dumps(routes, ensure_ascii=False, separators=(',', ':'))
        encoded = base64.b64encode(raw.encode()).decode('ascii')
        self.assertEqual(routes, parse_proxy_routes(encoded=encoded))
        self.assertEqual(routes, parse_proxy_routes(raw=raw))
        self.assertIsNone(parse_proxy_routes())

    def test_bad_base64_utf8_json_or_conflicting_inputs_are_visible_refusals(self):
        values = ['!', 'e30', 'e31=', base64.b64encode(b'\xff').decode(),
                  base64.b64encode(b'{broken}').decode(), '']
        for value in values:
            with self.subTest(value=value), self.assertRaisesRegex(UpdateRejected, 'UPDATE_PROXY_ROUTES_INVALID'):
                parse_proxy_routes(encoded=value)
        with self.assertRaisesRegex(UpdateRejected, 'UPDATE_PROXY_ROUTES_INVALID'):
            parse_proxy_routes(raw='{}', encoded='e30=')
        with self.assertRaisesRegex(UpdateRejected, 'UPDATE_PROXY_ROUTES_INVALID'):
            parse_proxy_routes(raw='{mode:DIRECT}')

    def test_both_decoded_and_encoded_limits_are_enforced_before_parse(self):
        for value in ['A' * 10925, base64.b64encode(b' ' * 8193).decode()]:
            with self.subTest(length=len(value)), self.assertRaisesRegex(UpdateRejected, 'UPDATE_PROXY_ROUTES_LIMIT'):
                parse_proxy_routes(encoded=value)
        with self.assertRaisesRegex(UpdateRejected, 'UPDATE_PROXY_ROUTES_LIMIT'):
            parse_proxy_routes(raw=' ' * 8193)
        raw = ' ' * 8190 + '{}'
        self.assertEqual({}, parse_proxy_routes(encoded=base64.b64encode(raw.encode()).decode()))


    def test_actual_cli_rejects_bad_arguments_before_identity_or_cache_access(self):
        import desktop_update
        with tempfile.TemporaryDirectory(prefix='ccb-update-argv-') as temporary:
            root = Path(temporary)
            config = root / 'package/app/desktop-updater'
            config.mkdir(parents=True)
            (config / 'update-config.json').write_text(json.dumps(dict(format=1,
                product='synthetic-stop-before-network', platform='windows-x86_64')), encoding='utf-8')
            command = [sys.executable, '-B', str(Path(desktop_update.__file__)), '--package', str(root / 'package'),
                       '--cache', str(root / 'unused-cache')]
            java_classpath = os.environ.get('CCB_UPDATE_PROXY_JAVA_CLASSPATH')
            if java_classpath:
                command = [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'), '-cp', java_classpath,
                           'UpdateCommandBridge', *command]
            for value, reason in [('!', 'INVALID'), ('e31=', 'INVALID'),
                                  (base64.b64encode(b'\xff').decode(), 'INVALID'),
                                  (base64.b64encode(b'{broken}').decode(), 'INVALID'),
                                  ('A' * 10925, 'LIMIT'),
                                  (base64.b64encode(b' ' * 8193).decode(), 'LIMIT')]:
                with self.subTest(length=len(value), reason=reason):
                    result = subprocess.run(command + ['--proxy-routes-base64', value],
                        capture_output=True, text=True, timeout=30)
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertEqual(dict(status='FAILED', reason='UPDATE_PROXY_ROUTES_' + reason),
                                     json.loads(result.stdout.strip()))
            result = subprocess.run(command + ['--proxy-routes-base64', 'e30='],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(dict(status='FAILED', reason='INSTALLED_UPDATE_IDENTITY_REFUSED'),
                             json.loads(result.stdout.strip()))
            self.assertFalse((root / 'unused-cache').exists())


if __name__ == '__main__':
    unittest.main()
