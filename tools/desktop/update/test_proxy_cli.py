"""Prepared helper CLI: real verified TLS through HTTP and SOCKS, synthetic signed TUF only."""
from datetime import datetime, timedelta, timezone
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import urlsplit

import github_transport
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID


def exact(connection, count):
    data = b''
    while len(data) < count:
        part = connection.recv(count - len(data))
        if not part:
            raise EOFError('Synthetic connection closed')
        data += part
    return data


def header(connection):
    data = b''
    while not data.endswith(b'\r\n\r\n'):
        if len(data) >= 16384:
            raise ValueError('Synthetic header limit')
        data += exact(connection, 1)
    return data.decode('ascii')


class TlsProxy:
    def __init__(self, context, repository, kind):
        self.context, self.repository, self.kind = context, repository, kind
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0)); self.listener.listen(8); self.listener.settimeout(.2)
        self.port = self.listener.getsockname()[1]
        self.stopping = threading.Event(); self.hosts = []; self.errors = []
        self.thread = threading.Thread(target=self.run, daemon=True); self.thread.start()

    def run(self):
        while not self.stopping.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                with connection:
                    connection.settimeout(5)
                    if self.kind == 'HTTP':
                        first = header(connection).splitlines()[0].split(' ')
                        if first[0] != 'CONNECT' or not first[1].endswith(':443'):
                            raise ValueError('Expected actual CONNECT')
                        host = first[1][:-4]
                        connection.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
                    else:
                        version, methods = exact(connection, 2)
                        if version != 5 or 0 not in exact(connection, methods):
                            raise ValueError('Expected actual SOCKS5 greeting')
                        connection.sendall(b'\x05\x00')
                        version, command, reserved, address_type = exact(connection, 4)
                        if (version, command, reserved, address_type) != (5, 1, 0, 3):
                            raise ValueError('Expected SOCKS5 remote domain')
                        host = exact(connection, exact(connection, 1)[0]).decode('ascii')
                        if int.from_bytes(exact(connection, 2), 'big') != 443:
                            raise ValueError('Expected HTTPS port')
                        connection.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                    if host not in github_transport.SIGNED_PROXY_HOSTS:
                        raise ValueError('Unexpected destination')
                    self.hosts.append(host)
                    with self.context.wrap_socket(connection, server_side=True) as secure:
                        raw_request = header(secure)
                        if any(line.lower().startswith(('authorization:', 'cookie:')) for line in raw_request.splitlines()):
                            raise ValueError('Unexpected credentials')
                        request = raw_request.splitlines()[0].split(' ')
                        if request[0] != 'GET':
                            raise ValueError('Expected unauthenticated GET')
                        path = urlsplit(request[1]).path
                        filename = path.rsplit('/', 1)[-1]
                        response_headers = ''
                        if host == 'raw.githubusercontent.com':
                            body = self.repository.files.get(filename) if '/preview/' in path else None
                            status = '200 OK' if body is not None else '404 Not Found'; body = body or b''
                        elif host == 'github.com':
                            status = '302 Found'; body = b''
                            response_headers = 'Location: https://release-assets.githubusercontent.com/synthetic-installer.exe\r\n'
                        else:
                            status = '200 OK'; body = self.repository.payload
                        secure.sendall((f'HTTP/1.1 {status}\r\n{response_headers}Content-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode('ascii') + body)
            except Exception as error:
                self.errors.append(type(error).__name__)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.stopping.set(); self.listener.close(); self.thread.join(6)
        if self.thread.is_alive():
            raise RuntimeError('Synthetic proxy failed to drain')


class PreparedUpdaterProxyCliTest(unittest.TestCase):
    def test_prepared_cli_discovers_downloads_and_reverifies_signed_fixture_through_typed_proxy_routes(self):
        module_home = Path(github_transport.__file__).resolve().parent
        self.assertEqual(Path(os.environ['CCB_UPDATE_PROXY_EXPECTED_MODULE_HOME']).resolve(), module_home)
        # Only the synthetic signer/metadata fixture is imported from source; the child executes
        # the actual prepared helper and its isolated import path.
        spec = importlib.util.spec_from_file_location('ccb_proxy_signed_fixture', Path(__file__).with_name('test_client.py'))
        fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)
        repository = fixture.Repository(payload=b'SYNTHETIC INSTALLER NEVER EXECUTED' * 2048)
        with tempfile.TemporaryDirectory(prefix='ccb-update-proxy-cli-') as temporary:
            root = Path(temporary)
            ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic update transport CA')])
            now = datetime.now(timezone.utc)
            certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(ca_key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
                .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, None, None), critical=True)
                .sign(ca_key, hashes.SHA256()))
            leaf = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'Synthetic HTTPS origin')]))
                .issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=1))
                .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
                .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
                .add_extension(x509.SubjectAlternativeName([x509.DNSName(host) for host in github_transport.SIGNED_PROXY_HOSTS]), critical=False)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .add_extension(x509.KeyUsage(True, False, True, False, False, False, False, None, None), critical=True)
                .sign(ca_key, hashes.SHA256()))
            ca = root / 'synthetic-ca.pem'; secret = root / 'synthetic-key.pem'; server_cert = root / 'synthetic-server.pem'
            ca.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
            server_cert.write_bytes(leaf.public_bytes(serialization.Encoding.PEM))
            secret.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(server_cert, secret)
            package = root / 'package'; config_home = package / 'app' / 'desktop-updater'; config_home.mkdir(parents=True)
            installed = repository.installed
            config = dict(format=1, product='ccb-desktop-nsis-v1', platform='windows-x86_64', version=installed.version,
                displayVersion=installed.display_version, storageVersion=installed.display_version, channel=installed.channel,
                sourceSha=installed.source_sha, reader=1, rootSha256=hashlib.sha256(repository.bootstrap).hexdigest())
            config_file = config_home / 'update-config.json'; root_file = config_home / 'update-root.json'
            config_file.write_text(json.dumps(config), encoding='utf-8'); root_file.write_bytes(repository.bootstrap)
            before = config_file.read_bytes()
            with TlsProxy(context, repository, 'HTTP') as http, TlsProxy(context, repository, 'SOCKS') as socks:
                routes = {host: dict(mode='SOCKS' if host == 'release-assets.githubusercontent.com' else 'HTTP',
                    host='127.0.0.1', port=socks.port if host == 'release-assets.githubusercontent.com' else http.port)
                    for host in github_transport.SIGNED_PROXY_HOSTS}
                environment = dict(os.environ, SSL_CERT_FILE=str(ca))
                route_json = json.dumps(routes, separators=(',', ':'))
                encoded_routes = base64.b64encode(route_json.encode('utf-8')).decode('ascii')
                command = [sys.executable, '-B', str(module_home / 'desktop_update.py'), '--package', str(package),
                    '--cache', str(root / 'cache'), '--proxy-routes-base64', encoded_routes]
                # An opt-in cloud harness invokes this exact command through Java ProcessBuilder.
                # The ordinary test still uses the isolated prepared Python CLI directly.
                java_classpath = os.environ.get('CCB_UPDATE_PROXY_JAVA_CLASSPATH')
                if java_classpath:
                    command = [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'), '-cp', java_classpath,
                        'UpdateCommandBridge', *command]

                def execute(*extra):
                    result = subprocess.run(command + list(extra), env=environment, capture_output=True, text=True, timeout=30)
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
                    return next(row for row in reversed(rows) if 'status' in row)

                discovered = execute(); self.assertEqual('AVAILABLE', discovered['status'])
                expected = discovered['displayVersion']
                downloaded = execute('--download', '--expected-version', expected)
                self.assertEqual('DOWNLOADED_VERIFIED', downloaded['status'])
                self.assertEqual(repository.payload, Path(downloaded['installer']).read_bytes())
                checked = execute('--verify-file', downloaded['installer'], '--expected-version', expected)
                self.assertEqual('DOWNLOADED_VERIFIED', checked['status']); self.assertEqual(downloaded['sha256'], checked['sha256'])
                # Retain compatibility for direct Python callers using the legacy JSON option.
                legacy = [sys.executable, '-B', str(module_home / 'desktop_update.py'), '--package', str(package),
                    '--cache', str(root / 'legacy-cache'), '--proxy-routes', route_json]
                legacy_result = subprocess.run(legacy, env=environment, capture_output=True, text=True, timeout=30)
                self.assertEqual(0, legacy_result.returncode, legacy_result.stdout + legacy_result.stderr)
                self.assertEqual('AVAILABLE', json.loads(legacy_result.stdout.splitlines()[-1])['status'])
                self.assertIn('raw.githubusercontent.com', http.hosts); self.assertIn('github.com', http.hosts)
                self.assertEqual(['release-assets.githubusercontent.com'], socks.hosts)
                self.assertEqual([], http.errors); self.assertEqual([], socks.errors)
            self.assertEqual(before, config_file.read_bytes()); self.assertEqual(repository.bootstrap, root_file.read_bytes())


if __name__ == '__main__':
    unittest.main()
