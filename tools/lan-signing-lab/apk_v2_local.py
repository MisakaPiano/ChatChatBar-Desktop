"""Restricted APK v2 signing experiment; no installation or delivery actions.

Format: https://source.android.com/docs/security/features/apksigning/v2
Uses cryptography for RSA/X.509. Private keys exist only in memory and Windows
user-scope DPAPI storage. This cannot reproduce the first preview's signer.
Only ZIP32, no JAR signatures, no existing v3/rotation/source-stamp are accepted.
Official Android apksigner must independently verify any resulting candidate.
"""
import argparse
import base64
import ctypes
from ctypes import wintypes
import datetime
import hashlib
import json
import mmap
import os
from pathlib import Path
import struct
import zipfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

MAGIC = b"APK Sig Block 42"
ALGORITHM = 0x0103
V2_ID = 0x7109871A
PADDING_ID = 0x42726577
MAX_APK = 1024 * 1024 * 1024

def lp(value):
    return struct.pack("<I", len(value)) + value

def safe_file(path, exists):
    path = Path(path).absolute()
    for candidate in [path] + list(path.parents):
        if candidate.exists():
            attrs = candidate.lstat().st_file_attributes if os.name == 'nt' else 0
            if candidate.is_symlink() or attrs & 0x400:
                raise ValueError('SIGN_REPARSE_PATH')
    if exists and (not path.is_file() or path.stat().st_size > MAX_APK):
        raise ValueError('SIGN_INPUT_SIZE')
    if not exists and path.exists():
        raise ValueError('SIGN_OUTPUT_EXISTS')
    return path

def write_new(path, data):
    path = safe_file(path, False)
    with path.open('xb') as out:
        out.write(data); out.flush(); os.fsync(out.fileno())

def dpapi(value, protect):
    if os.name != 'nt':
        raise ValueError('SIGN_WINDOWS_DPAPI_REQUIRED')
    class Blob(ctypes.Structure):
        _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source = Blob(len(value), buffer); result = Blob()
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if protect:
        function = crypt.CryptProtectData
        function.argtypes = [ctypes.POINTER(Blob), wintypes.LPCWSTR, ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        description = 'CCB isolated APK signing experiment'
    else:
        function = crypt.CryptUnprotectData
        function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
        description = None
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), description, None, None, None, 1, ctypes.byref(result)):
        raise ValueError('SIGN_DPAPI_FAILED')
    try:
        return ctypes.string_at(result.data, result.size)
    finally:
        if result.data:
            ctypes.memset(result.data, 0, result.size)
            kernel.LocalFree(result.data)
        ctypes.memset(buffer, 0, len(value))

def initialize(path):
    path = safe_file(path, False)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'CCB isolated signing experiment only')])
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=3650)).sign(key, hashes.SHA256()))
    cert = certificate.public_bytes(serialization.Encoding.DER)
    # Never serialize a plaintext private key to disk, stdout, command arguments or a receipt.
    private = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    envelope = json.dumps({'format': 1, 'privateDerHex': private.hex(), 'certificateDerHex': cert.hex()}).encode()
    protected = dpapi(envelope, True)
    write_new(path, protected)
    return {'certificateSha256': hashlib.sha256(cert).hexdigest(), 'protectedBytes': len(protected)}

def load_identity(path):
    path = safe_file(path, True)
    if path.stat().st_size > 32768:
        raise ValueError('SIGN_KEY_SIZE')
    payload = json.loads(dpapi(path.read_bytes(), False))
    if set(payload) != {'format', 'privateDerHex', 'certificateDerHex'} or payload['format'] != 1:
        raise ValueError('SIGN_KEY_FORMAT')
    key = serialization.load_der_private_key(bytes.fromhex(payload['privateDerHex']), None)
    cert = bytes.fromhex(payload['certificateDerHex'])
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    certificate = x509.load_der_x509_certificate(cert)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size != 2048 or certificate.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != public:
        raise ValueError('SIGN_KEY_CERTIFICATE')
    return key, cert, public

def layout(data):
    # EOCD must be complete, single disk, without ZIP64, trailing data or a directory gap.
    tail = bytes(data[max(0, len(data) - 65557):])
    relative = tail.rfind(b'PK\x05\x06')
    if relative < 0:
        raise ValueError('SIGN_ZIP_EOCD')
    offset = max(0, len(data) - 65557) + relative
    eocd = bytes(data[offset:])
    if len(eocd) < 22:
        raise ValueError('SIGN_ZIP_EOCD')
    _, disk, cd_disk, n_disk, n_total, cd_size, cd_start, comment = struct.unpack('<4s4H2IH', eocd[:22])
    if disk or cd_disk or n_disk != n_total or n_total == 65535 or cd_size == 0xffffffff or cd_start == 0xffffffff or offset != cd_start + cd_size or len(eocd) != 22 + comment:
        raise ValueError('SIGN_ZIP_LAYOUT')
    block_start = cd_start
    if cd_start >= 24 and data[cd_start - 16:cd_start] == MAGIC:
        size = struct.unpack('<Q', data[cd_start - 24:cd_start - 16])[0]
        block_start = cd_start - size - 8
        if size < 24 or block_start < 0 or struct.unpack('<Q', data[block_start:block_start + 8])[0] != size:
            raise ValueError('SIGN_OLD_BLOCK_SIZE')
        cursor = block_start + 8
        seen = set()
        while cursor < cd_start - 24:
            length = struct.unpack('<Q', data[cursor:cursor + 8])[0]
            if length < 4 or cursor + 8 + length > cd_start - 24:
                raise ValueError('SIGN_OLD_BLOCK_PAIR')
            ident = struct.unpack('<I', data[cursor + 8:cursor + 12])[0]
            if ident not in (V2_ID, PADDING_ID) or ident in seen:
                raise ValueError('SIGN_UNSUPPORTED_OLD_SCHEME')
            seen.add(ident); cursor += 8 + length
        if cursor != cd_start - 24 or V2_ID not in seen:
            raise ValueError('SIGN_OLD_BLOCK_PAIR')
    return block_start, cd_start, offset, eocd

def content_digest(data, block_start, cd_start, eocd_offset, eocd):
    corrected = bytearray(eocd); struct.pack_into('<I', corrected, 16, block_start)
    sections = [(0, block_start), (cd_start, eocd_offset)]
    digests = []
    for start, end in sections:
        for offset in range(start, end, 1048576):
            chunk = data[offset:min(end, offset + 1048576)]
            digests.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(chunk)) + chunk).digest())
    for offset in range(0, len(corrected), 1048576):
        chunk = corrected[offset:offset + 1048576]
        digests.append(hashlib.sha256(b'\xa5' + struct.pack('<I', len(chunk)) + chunk).digest())
    return hashlib.sha256(b'\x5a' + struct.pack('<I', len(digests)) + b''.join(digests)).digest()

def prepare(source, keyfile):
    """Read an APK and emit a public detached block; never write an APK locally."""
    source = safe_file(source, True)
    before_hash = file_hash(source)
    key, cert, public = load_identity(keyfile)
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(n.upper().startswith('META-INF/') and n.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) for n in names):
            raise ValueError('SIGN_ZIP_DUPLICATES_OR_V1')
        if archive.testzip() is not None:
            raise ValueError('SIGN_ZIP_CRC')
    with source.open('rb') as original, mmap.mmap(original.fileno(), 0, access=mmap.ACCESS_READ) as data:
        block_start, cd_start, offset, eocd = layout(data)
        digest = content_digest(data, block_start, cd_start, offset, eocd)
        signed = lp(lp(struct.pack('<I', ALGORITHM) + lp(digest))) + lp(lp(cert)) + lp(b'')
        signature = key.sign(signed, padding.PKCS1v15(), hashes.SHA256())
        key.public_key().verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())
        signer = lp(signed) + lp(lp(struct.pack('<I', ALGORITHM) + lp(signature))) + lp(public)
        value = lp(lp(signer))
        pair = struct.pack('<Q', len(value) + 4) + struct.pack('<I', V2_ID) + value
        size = len(pair) + 24
        block = struct.pack('<Q', size) + pair + struct.pack('<Q', size) + MAGIC
        final_eocd = bytearray(eocd); struct.pack_into('<I', final_eocd, 16, block_start + len(block))
        expected = hashlib.sha256()
        for at in range(0, block_start, 1048576): expected.update(data[at:min(block_start, at + 1048576)])
        expected.update(block)
        for at in range(cd_start, offset, 1048576): expected.update(data[at:min(offset, at + 1048576)])
        expected.update(final_eocd)
        size = block_start + len(block) + offset - cd_start + len(final_eocd)
    if file_hash(source) != before_hash:
        raise ValueError('SIGN_SOURCE_CHANGED')
    return {'format': 1, 'purpose': 'ENGINEERING_SIGNING_PROOF_ONLY_NOT_AN_UPDATE',
        'sourceSha256': before_hash, 'outputSha256': expected.hexdigest(),
        'bytes': size, 'entries': len(names), 'certificateSha256': hashlib.sha256(cert).hexdigest(),
        'blockBase64': base64.b64encode(block).decode('ascii'),
        'scheme': 'v2', 'algorithm': 'RSA-2048-PKCS1-SHA256', 'officialVerifierPassed': False}

def file_hash(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init'); init.add_argument('--keyfile', type=Path, required=True)
    signing = sub.add_parser('prepare')
    for flag in ['source', 'keyfile', 'receipt']:
        signing.add_argument('--' + flag, type=Path, required=True)
    args = parser.parse_args()
    try:
        result = initialize(args.keyfile) if args.command == 'init' else prepare(args.source, args.keyfile)
        if args.command == 'prepare': write_new(args.receipt, json.dumps(result, indent=2).encode('utf8'))
        print(json.dumps({key: value for key, value in result.items() if key != 'blockBase64'}))
    except Exception:
        # Never render exceptions that may contain decrypted key material or paths.
        raise SystemExit('SIGN_REFUSED') from None

if __name__ == '__main__': main()
