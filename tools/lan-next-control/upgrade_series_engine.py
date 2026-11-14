"""Trusted-profile orchestration over unchanged LAN control primitives.

This file owns no identity and performs no native work at import time. Profiles
are code-owned objects, never a path, selector, version or role supplied by JSON.
"""
from copy import deepcopy
from dataclasses import dataclass
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile


def require(value, code):
    if not value:
        raise ValueError(code)


@dataclass(frozen=True)
class Role:
    name: str
    filename: str
    package: str
    version: object


@dataclass(frozen=True)
class SeriesProfile:
    name: str
    package: str
    certificate: str
    versions: tuple
    roles: tuple
    base_role: str
    seed_role: str
    candidate_role: str
    verify_role: str
    base: object
    base_manifest: object
    base_public_control: str
    base_public_path: str
    base_public_bytes: int
    base_public_sha256: str
    base_public_role: str
    fixture_files: tuple
    fixture_target: str
    fixture_class: str
    argument: str
    proof_root: str
    seed_method: str
    verify_method: str
    ui_class: str
    ui_method: str
    kinds: object
    artifact_name: str
    tamper_field: str
    api: int = 26

    def __post_init__(self):
        names = (self.base_role, self.seed_role, self.candidate_role, self.verify_role)
        require(len(set(names)) == 4 and tuple(r.name for r in self.roles) == names, 'PROFILE_ROLES')
        require(self.versions[0] == self.base['versionCode'] and self.versions[1] > self.versions[0], 'PROFILE_VERSIONS')
        require(self.roles[0].version == self.versions[0] and self.roles[2].version == self.versions[1], 'PROFILE_ROLE_VERSIONS')
        require(tuple(role for role, _ in self.fixture_files) == (self.seed_role, self.verify_role), 'PROFILE_FIXTURES')
        require(self.api == 26, 'UNSUPPORTED_DEVICE_PROFILE')


class LegacyTools:
    """Load existing, inert modules by their verified file locations.

    Their own namespaces stay intact. In particular the old84 validator still
    sees old84 constants; no function is retained from mutated module globals.
    """
    def __init__(self, control_root):
        self.root = Path(control_root).resolve(strict=True)
        self.here = self.root / 'tools/lan-next-control'
        before = list(sys.path)
        try:
            sys.path.insert(0, str(self.here))
            for name in ('contract', 'build_control', 'candidate84_profile', 'candidate84_build',
                         'candidate84_sign', 'candidate84_upgrade', 'desktop_control', 'provenance',
                         'instrumentation_report', 'sign_control', 'fixture_only_control', 'group_ui_control'):
                module = importlib.import_module(name)
                require(Path(module.__file__).resolve() == (self.here / (name + '.py')).resolve(),
                        'LEGACY_MODULE_SHADOW_' + name)
                setattr(self, name, module)
            assembler = sys.modules[self.candidate84_sign.assemble.__module__]
            require(Path(assembler.__file__).resolve() ==
                    (self.root / 'tools/lan-preview-signing/assemble_public_apk.py').resolve(), 'PUBLIC_ASSEMBLER_SHADOW')
            require(Path(self.candidate84_upgrade._install_module.__file__).resolve() ==
                    (self.here / 'prove_upgrade.py').resolve(), 'INSTALL_STATE_HELPER_SHADOW')
        finally:
            sys.path[:] = before


class SeriesEngine:
    def __init__(self, profile, tools, fixture_directory):
        require(isinstance(profile, SeriesProfile), 'TRUSTED_PROFILE_REQUIRED')
        self.p, self.t, self.here = profile, tools, Path(fixture_directory).resolve(strict=True)
        self.c = tools.contract
        self.load = tools.candidate84_profile.load
        require(profile.package == self.c.PACKAGE and profile.certificate == self.c.CERTIFICATE,
                'UNSUPPORTED_IDENTITY_PROFILE')

    def fixtures(self):
        return {role: {'path': name, 'sha256': self.c.fixture_hash(self.here / name)}
                for role, name in self.p.fixture_files}

    def build_request(self, source):
        self.c.sha(source, 40)
        require(source != self.p.base['sourceSha'], 'CANDIDATE_MUST_BE_NEW_SOURCE')
        return {'kind': self.p.kinds['build'], 'format': 1, 'sourceSha': source, 'series': 'next',
                'versions': list(self.p.versions), 'package': self.p.package,
                'certificateSha256': self.p.certificate, 'deliveredBase': dict(self.p.base),
                'fixtures': self.fixtures(), 'allowedStateMigrations': []}

    def validate_build(self, value):
        require(isinstance(value, dict) and type(value.get('format')) is int and value['format'] == 1,
                'BUILD_FORMAT')
        require(value == self.build_request(value.get('sourceSha')), 'TRUSTED_BUILD_CONTRACT')
        return value

    def base_artifact(self):
        b = self.p.base
        return {'id': b['artifactId'], 'runId': b['runId'], 'name': b['artifactName'],
                'bytes': b['outerBytes'], 'outerSha256': b['outerSha256'], 'controlSha': b['controlSha']}

    def artifact_metadata(self, value):
        require(isinstance(value, dict) and set(value) == {'id', 'runId', 'name', 'bytes', 'outerSha256', 'controlSha'},
                'ARTIFACT_FIELDS')
        require(value['name'] == self.p.artifact_name, 'ARTIFACT_NAME')
        for key in ('id', 'runId', 'bytes'):
            require(type(value[key]) is int and value[key] > 0, 'ARTIFACT_' + key)
        self.c.sha(value['outerSha256']); self.c.sha(value['controlSha'], 40)
        return value

    def block(self, value, raw_hash, base=False):
        self.c.validate_public_block(value, raw_hash, signed_base=False)
        require(type(value['format']) is int, 'PUBLIC_BLOCK_FORMAT')
        if base:
            require(value['bytes'] == self.p.base['deliveredBytes'] and
                    value['outputSha256'] == self.p.base['deliveredSha256'], 'EXACT_DELIVERED_BASE')

    def base_public_block(self):
        p = self.p
        body = subprocess.run(['git', '-C', str(self.t.root), 'show', p.base_public_control + ':' + p.base_public_path],
                              check=True, capture_output=True, timeout=30).stdout
        require(len(body) == p.base_public_bytes and hashlib.sha256(body).hexdigest() == p.base_public_sha256,
                'FROZEN_PUBLIC_REQUEST_CHANGED')
        value = json.loads(body.decode('utf-8', errors='strict'), object_pairs_hook=self.t.candidate84_profile.unique_object)
        require(value['originalArtifact'] == self.base_artifact() and
                value['buildRequest']['sourceSha'] == p.base['sourceSha'] and
                value['rawManifest'] == dict(p.base_manifest), 'FROZEN_BASE_SOURCE')
        block = value['entries'][p.base_public_role]['publicSigning']
        self.block(block, p.base['rawSha256'], True)
        return deepcopy(block)

    def validate_sign(self, value):
        require(isinstance(value, dict) and set(value) == {
            'kind', 'format', 'buildRequest', 'originalArtifact', 'rawManifest', 'entries'}, 'SIGN_FIELDS')
        require(value['kind'] == self.p.kinds['sign'] and type(value['format']) is int and value['format'] == 1,
                'SIGN_KIND')
        self.validate_build(value['buildRequest']); self.artifact_metadata(value['originalArtifact'])
        manifest = value['rawManifest']
        require(isinstance(manifest, dict) and set(manifest) == {'bytes', 'sha256'} and
                type(manifest['bytes']) is int and manifest['bytes'] > 0, 'RAW_MANIFEST_DESCRIPTOR')
        self.c.sha(manifest['sha256'])
        require(isinstance(value['entries'], dict) and set(value['entries']) == {r.name for r in self.p.roles}, 'FOUR_INPUTS_REQUIRED')
        for role in self.p.roles:
            entry = value['entries'][role.name]
            require(set(entry) == {'filename', 'publicSigning'} and entry['filename'] == role.filename, 'ROLE_FILENAME')
            public = entry['publicSigning']
            self.block(public, self.p.base['rawSha256'] if role.name == self.p.base_role else public.get('sourceSha256'),
                       role.name == self.p.base_role)
            self.c.sha(public['sourceSha256'])
        require(value['entries'][self.p.base_role]['publicSigning'] == self.base_public_block(), 'EXACT_BASE_PUBLIC_BLOCK')
        return value

    def overlay_path(self, directory):
        root = Path(directory).resolve(strict=True)
        target = root
        for part in Path(self.p.fixture_target).parts:
            target /= part
            require(not target.is_symlink(), 'OVERLAY_SYMLINK')
        require(target.resolve(strict=False).is_relative_to(root), 'OVERLAY_ESCAPE')
        return target

    def check_source(self, directory, source, fixture=None):
        require(self.t.build_control.git(directory, 'rev-parse', 'HEAD') == source, 'SOURCE_HEAD_CHANGED')
        expected = '' if fixture is None else '?? ' + self.p.fixture_target
        require(self.t.build_control.git(directory, 'status', '--porcelain', '--untracked-files=all') == expected,
                'SOURCE_NOT_EXCLUSIVE')
        if fixture is not None:
            require(self.c.fixture_hash(self.overlay_path(directory)) == fixture['sha256'], 'BUILT_FIXTURE_CHANGED')

    def prepare(self, build, seed, candidate, output):
        self.t.candidate84_build.cloud(); self.validate_build(build)
        output.mkdir(parents=True, exist_ok=False)
        for role, directory, source in ((self.p.seed_role, seed, self.p.base['sourceSha']),
                                        (self.p.verify_role, candidate, build['sourceSha'])):
            self.check_source(directory, source)
            fixture = build['fixtures'][role]; path = self.here / fixture['path']
            require(not path.is_symlink() and self.c.fixture_hash(path) == fixture['sha256'], 'FIXTURE_CHANGED')
            target = self.overlay_path(directory)
            require(not target.exists(), 'OVERLAY_EXISTS')
            target.parent.mkdir(parents=True, exist_ok=True)
            require(self.overlay_path(directory) == target, 'OVERLAY_CHANGED')
            with target.open('xb') as stream: stream.write(self.c.fixture_bytes(path))
            self.check_source(directory, source, fixture)
        self.c.write_new(output / 'source-request.json', build)
        self.c.write_new(output / 'fixture-overlays.json', {'seedSourceSha': self.p.base['sourceSha'],
            'candidateSourceSha': build['sourceSha'], 'fixtures': build['fixtures'],
            'target': self.p.fixture_target, 'productionFilesChanged': []})

    def manifest_descriptor(self, path):
        return self.t.candidate84_sign.manifest_descriptor(path)

    def validate_base(self, directory):
        # Authenticate this full original artifact with the UNMODIFIED old84
        # validator in its own namespace, not its older base83 fetch helper.
        require(self.manifest_descriptor(directory / 'raw-manifest.json') == dict(self.p.base_manifest), 'EXACT_BASE_MANIFEST')
        old = self.load(directory / 'source-request.json')
        require(old['sourceSha'] == self.p.base['sourceSha'], 'EXACT_BASE_SOURCE')
        self.t.candidate84_build.validate_raw(directory, old, self.p.base['controlSha'])
        path = directory / self.p.base['member']
        require(path.is_file() and not path.is_symlink() and path.stat().st_size == self.p.base['rawBytes'] and
                self.c.file_hash(path) == self.p.base['rawSha256'], 'EXACT_BASE_RAW_BYTES')
        return path

    def _fetch(self, expected, descriptor, archive, destination, evidence):
        self.t.candidate84_build.cloud()
        evidence.mkdir(parents=True, exist_ok=False)
        self.c.write_new(evidence / 'original-provenance.json', self.t.provenance.check(expected))
        count = 0
        with self.t.desktop_control.original_stream(expected['id']) as source, archive.open('xb') as target:
            while True:
                data = source.read(1024 * 1024)
                if not data: break
                count += len(data)
                require(count <= expected['bytes'], 'RAW_ARCHIVE_TOO_LONG')
                target.write(data)
        require(count == expected['bytes'], 'RAW_ARCHIVE_LENGTH')
        receipt = self.t.desktop_control.verify_outer_and_extract(archive, destination, {
            'outerBytes': expected['bytes'], 'outerSha256': expected['outerSha256'],
            'filename': 'raw-manifest.json', 'innerBytes': descriptor['bytes'],
            'innerSha256': descriptor['sha256'], 'series': self.p.name})
        require(receipt['innerRelativePath'] == 'raw-manifest.json', 'RAW_MANIFEST_NOT_AT_ROOT')
        self.c.write_new(evidence / 'original-input-bytes.json', receipt)

    def fetch_base(self, archive, output):
        self._fetch(self.base_artifact(), self.p.base_manifest, archive, output,
                    output.with_name(output.name + '-provenance'))
        self.validate_base(output)

    def collect(self, build, seed, candidate, original, output, control, tools):
        self.t.candidate84_build.cloud(); self.validate_build(build); self.c.sha(control, 40)
        require(self.load(output / 'source-request.json') == build, 'FROZEN_BUILD_REQUEST')
        for role, directory, source in ((self.p.seed_role, seed, self.p.base['sourceSha']),
                                        (self.p.verify_role, candidate, build['sourceSha'])):
            self.check_source(directory, source, build['fixtures'][role])
        one = self.t.build_control.one
        paths = {self.p.base_role: self.validate_base(original),
                 self.p.seed_role: one(seed / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk'),
                 self.p.candidate_role: one(candidate / 'app/app/build/outputs/apk', 'lanPreview/*.apk'),
                 self.p.verify_role: one(candidate / 'app/app/build/outputs/apk', 'androidTest/lanPreview/*.apk')}
        # Keep API provenance and full-ZIP authentication in the resulting raw
        # artifact, not only in the runner's ephemeral original84 directory.
        evidence = original.with_name(original.name + '-provenance')
        for source, target in (('original-provenance.json', 'delivered-base-provenance.json'),
                               ('original-input-bytes.json', 'delivered-base-input-bytes.json')):
            self.c.write_new(output / target, self.load(evidence / source))
        rows = []
        for role in self.p.roles:
            target = output / role.filename
            actual = self.t.build_control.inspect_apk(paths[role.name], role.package, role.version, tools,
                                                     output / (role.name + '-badging.txt'))
            self.t.build_control.copy_new(paths[role.name], target)
            rows.append({'role': role.name, 'filename': role.filename, 'package': role.package, 'versionCode': actual,
                'sourceSha': self.p.base['sourceSha'] if role.name in (self.p.base_role, self.p.seed_role) else build['sourceSha'],
                'testFixture': build['fixtures'].get(role.name), 'bytes': target.stat().st_size,
                'sha256': self.c.file_hash(target), 'deliveredBase': dict(self.p.base) if role.name == self.p.base_role else None})
        self.c.write_new(output / 'raw-manifest.json', {'kind': self.p.kinds['raw'], 'sourceSha': build['sourceSha'],
            'controlSha': control, 'buildRequest': build, 'inputs': rows, 'baseArtifact': self.base_artifact(),
            'persistentSigningApplied': False, 'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})
        self.validate_raw(output, build, control)

    def validate_raw(self, directory, build, control=None):
        self.validate_build(build)
        manifest = self.load(directory / 'raw-manifest.json')
        require(manifest['kind'] == self.p.kinds['raw'] and manifest['sourceSha'] == build['sourceSha'] and
                manifest['buildRequest'] == build and self.load(directory / 'source-request.json') == build and
                manifest['baseArtifact'] == self.base_artifact(), 'RAW_BINDING')
        self.c.sha(manifest['controlSha'], 40)
        if control is not None: require(manifest['controlSha'] == control, 'RAW_CONTROL')
        rows = manifest['inputs']
        require(len(rows) == 4 and {r['role'] for r in rows} == {r.name for r in self.p.roles}, 'FOUR_RAW_INPUTS')
        result = {r['role']: r for r in rows}
        for role in self.p.roles:
            row = result[role.name]; path = directory / role.filename
            require(row['filename'] == role.filename and row['package'] == role.package and
                    (role.version is None or type(row['versionCode']) is int and row['versionCode'] == role.version), 'RAW_ROLE_IDENTITY')
            require(row['sourceSha'] == (self.p.base['sourceSha'] if role.name in (self.p.base_role, self.p.seed_role) else build['sourceSha']) and
                    row['testFixture'] == build['fixtures'].get(role.name) and
                    row['deliveredBase'] == (dict(self.p.base) if role.name == self.p.base_role else None), 'RAW_LINEAGE')
            require(path.is_file() and not path.is_symlink() and type(row['bytes']) is int and row['bytes'] > 0 and
                    path.stat().st_size == row['bytes'] and self.c.file_hash(path) == row['sha256'], 'RAW_BYTES')
            if role.name == self.p.base_role:
                require(row['bytes'] == self.p.base['rawBytes'] and row['sha256'] == self.p.base['rawSha256'], 'EXACT_BASE_RAW')
        return manifest, result

    def prepare_sign(self, raw, artifact, public, output):
        build = self.load(raw / 'source-request.json')
        _, rows = self.validate_raw(raw, build, artifact['controlSha']); self.artifact_metadata(artifact)
        require(set(public) == {self.p.seed_role, self.p.candidate_role, self.p.verify_role}, 'THREE_NEW_PUBLIC_BLOCKS')
        blocks = dict(public); blocks[self.p.base_role] = self.base_public_block()
        entries = {}
        for role in self.p.roles:
            self.block(blocks[role.name], rows[role.name]['sha256'], role.name == self.p.base_role)
            entries[role.name] = {'filename': role.filename, 'publicSigning': blocks[role.name]}
        self.c.write_new(output, self.validate_sign({'kind': self.p.kinds['sign'], 'format': 1, 'buildRequest': build,
            'originalArtifact': artifact, 'rawManifest': self.manifest_descriptor(raw / 'raw-manifest.json'), 'entries': entries}))

    def fetch_raw(self, request, archive, destination, evidence):
        self.validate_sign(request)
        self._fetch(request['originalArtifact'], request['rawManifest'], archive, destination, evidence)
        self.validate_raw(destination, request['buildRequest'], request['originalArtifact']['controlSha'])

    def verify(self, raw, request, output, tools, control):
        self.t.candidate84_build.cloud(); self.validate_sign(request); self.c.sha(control, 40)
        _, rows = self.validate_raw(raw, request['buildRequest'], request['originalArtifact']['controlSha'])
        require(self.manifest_descriptor(raw / 'raw-manifest.json') == request['rawManifest'], 'RAW_MANIFEST_CHANGED')
        output.mkdir(parents=True, exist_ok=False)
        apksigner, aapt, zipalign = (tools / name for name in ('apksigner', 'aapt', 'zipalign'))
        tool = self.t.candidate84_sign.tool
        signed = []
        for role in self.p.roles:
            public = request['entries'][role.name]['publicSigning']
            self.block(public, rows[role.name]['sha256'], role.name == self.p.base_role)
            target = output / (role.name + '.apk')
            self.t.candidate84_sign.assemble(raw / role.filename, public, target)
            signature = tool(apksigner, ['verify', '--verbose', '--print-certs', str(target)], output / (role.name + '-apksigner.txt'))
            self.t.candidate84_sign.require_single_v2(signature)
            actual = self.t.build_control.inspect_apk(target, role.package, role.version, tools, output / (role.name + '-badging.txt'))
            tool(zipalign, ['-c', '4', str(target)], output / (role.name + '-zipalign.txt'))
            if role.name in (self.p.seed_role, self.p.verify_role):
                manifest = tool(aapt, ['dump', 'xmltree', str(target), 'AndroidManifest.xml'], output / (role.name + '-manifest.txt'))
                require(re.search(r'android:targetPackage[^\n]*="' + re.escape(self.p.package) + '"', manifest), 'COMPANION_TARGET')
            digest = self.c.file_hash(target)
            if role.name == self.p.base_role:
                require(digest == self.p.base['deliveredSha256'] and target.stat().st_size == self.p.base['deliveredBytes'], 'EXACT_DELIVERED_BASE')
            signed.append(dict(rows[role.name], signedSha256=digest, signedBytes=target.stat().st_size,
                versionCode=actual, certificateSha256=self.p.certificate, officialV2Verified=True, zipalign4Passed=True))
        # Existing executable-byte oracle; the selected role is profile-owned.
        tampered = output / ('tampered-' + self.p.candidate_role + '.apk')
        shutil.copyfile(output / (self.p.candidate_role + '.apk'), tampered)
        with zipfile.ZipFile(tampered) as bundle: dex = bundle.getinfo('classes.dex')
        require(dex.compress_size > 0, 'TAMPER_DEX')
        with tampered.open('r+b') as stream:
            stream.seek(dex.header_offset); header = stream.read(30)
            require(len(header) == 30 and header[:4] == b'PK\x03\x04', 'TAMPER_HEADER')
            names, extra = struct.unpack_from('<HH', header, 26)
            stream.seek(dex.header_offset + 30 + names + extra)
            value = stream.read(1); require(len(value) == 1, 'TAMPER_BYTE')
            stream.seek(-1, 1); stream.write(bytes([value[0] ^ 1]))
        tool(apksigner, ['verify', '--min-sdk-version', str(self.p.api), str(tampered)], output / 'tamper-rejection.txt', False)
        tampered.unlink()
        self.c.write_new(output / 'verification.json', {'kind': self.p.kinds['verify'], 'verificationControlSha': control,
            'buildRequest': request['buildRequest'], 'originalArtifact': request['originalArtifact'],
            'rawManifest': request['rawManifest'], 'entries': signed, self.p.tamper_field: True,
            'deviceUpgradeTested': False, 'humanAcceptance': False, 'userDelivered': False})

    def verified_inputs(self, directory, control):
        value = self.load(directory / 'verification.json')
        require(value['kind'] == self.p.kinds['verify'] and value['verificationControlSha'] == control and
                value[self.p.tamper_field] is True, 'SAME_SDK_VERIFICATION_REQUIRED')
        self.validate_build(value['buildRequest']); self.artifact_metadata(value['originalArtifact'])
        rows = value['entries']
        require(len(rows) == 4 and {r['role'] for r in rows} == {r.name for r in self.p.roles}, 'FOUR_VERIFIED_APKS')
        by_role = {r['role']: r for r in rows}
        for role in self.p.roles:
            row = by_role[role.name]; path = directory / (role.name + '.apk')
            require(row['package'] == role.package and (role.version is None or row['versionCode'] == role.version) and
                    row['sourceSha'] == (self.p.base['sourceSha'] if role.name in (self.p.base_role, self.p.seed_role) else value['buildRequest']['sourceSha']) and
                    row['testFixture'] == value['buildRequest']['fixtures'].get(role.name), 'VERIFIED_APK_LINEAGE')
            require(path.is_file() and not path.is_symlink() and path.stat().st_size == row['signedBytes'] and
                    self.c.file_hash(path) == row['signedSha256'], 'VERIFIED_APK_CHANGED')
            require(row['officialV2Verified'] is True and row['zipalign4Passed'] is True and
                    row['certificateSha256'] == self.p.certificate, 'OFFICIAL_SDK_PROOF_REQUIRED')
        base = by_role[self.p.base_role]
        require(base['signedBytes'] == self.p.base['deliveredBytes'] and base['signedSha256'] == self.p.base['deliveredSha256'],
                'EXACT_DELIVERED_BASE_REQUIRED')
        return value

    def unchanged_install(self, before, after):
        require(type(before['versionCode']) is int and type(after['versionCode']) is int and
                (before['versionCode'], after['versionCode']) == self.p.versions, 'VERSION_PAIR')
        for field in ('uid', 'firstInstallTime', 'dataDir'):
            require(before[field] == after[field], 'INSTALL_IDENTITY_CHANGED_' + field)

    def device(self, directory):
        p = self.p
        class ProfileDevice(self.t.candidate84_upgrade.Device):
            def proof(self, name):
                return self.command(['shell', 'run-as', p.package, 'cat', 'files/' + p.proof_root + '/proof.json'], name)[0]
        return ProfileDevice(directory)

    def native_control(self):
        self.t.candidate84_build.cloud()
        require(os.environ.get('CCB_LAN_SYNTHETIC_UPGRADE_CLOUD') == 'true', 'EXPLICIT_UPGRADE_CONTROL_ONLY')
        return self.c.sha(os.environ.get('GITHUB_SHA'), 40)

    def upgrade(self, directory):
        control = self.native_control(); p = self.p
        directory = directory.resolve(strict=True)
        verification = self.verified_inputs(directory, control)
        result = {'kind': p.kinds['upgrade'], 'passed': False, 'sourceSha': verification['buildRequest']['sourceSha'],
            'verificationControlSha': control, 'upgradeControlSha': control, 'buildRequest': verification['buildRequest'],
            'originalArtifact': verification['originalArtifact'], 'api': p.api, 'package': p.package, 'versions': list(p.versions),
            'stages': [], 'uninstallOrClearData': False, 'verifyReseeded': False, 'allowedStateMigrations': [],
            'syntheticPrivateRootOnly': True, 'normalUserProfileTested': False, 'physicalPhoneTested': False,
            'humanAcceptance': False, 'userDelivered': False}
        require(not (directory / 'upgrade-proof.json').exists() and not (directory / 'ui-smoke-result.json').exists(), 'PROOF_ALREADY_RECORDED')
        device = self.device(directory); phase = 'fresh-emulator'
        try:
            device.select('upgrade')
            require(not device.command(['shell', 'pm', 'list', 'packages', 'com.example.chatbar'], 'fresh-ccb-packages')[0].strip(), 'FRESH_DEVICE_NO_UNINSTALL')
            phase = 'install-base-and-seed-companion'
            device.install(p.base_role + '.apk', 'install-exact-base')
            device.install(p.seed_role + '.apk', 'install-seed-tests')
            phase = p.seed_role
            result['stages'].append(device.instrument(p.fixture_class, p.seed_method, p.seed_role + '-instrumentation', p.argument))
            before = device.state('seed-package')
            require(before['versionCode'] == p.versions[0], 'SEEDED_VERSION')
            text = device.proof('seed-proof')
            proof = json.loads(text, object_pairs_hook=self.t.candidate84_profile.unique_object)
            require(proof['package'] == p.package and proof['seedVersionCode'] == p.versions[0] and
                    proof['uid'] == before['uid'] and proof['files'], 'ACTUAL_SEED_PROOF')
            phase = 'preserving-upgrade'
            device.command(['shell', 'am', 'force-stop', p.package], 'stop-base-before-upgrade')
            device.install(p.candidate_role + '.apk', 'install-candidate-preserving-data', True)
            after = device.state('installed-candidate-package'); self.unchanged_install(before, after)
            require(device.proof('before-verifier-proof') == text, 'PROOF_CHANGED_BEFORE_VERIFIER')
            device.install(p.verify_role + '.apk', 'install-verifier-tests', True)
            phase = p.verify_role
            result['stages'].append(device.instrument(p.fixture_class, p.verify_method, p.verify_role + '-instrumentation', p.argument))
            final = device.state('verified-candidate-package'); self.unchanged_install(before, final)
            require(device.proof('verified-candidate-proof') == text, 'PROOF_REWRITTEN')
            result.update(passed=True, fixtureStagesPassed=2, executedTests=2, skippedTests=0,
                installationBefore=before, installationAfter=final, sameUid=True, sameFirstInstallTime=True,
                sameDataDir=True, strictSyntheticFileHashesPreserved=True,
                seedProofSha256=hashlib.sha256(text.encode('utf-8')).hexdigest(), entries=verification['entries'])
        except Exception as error:
            result['failure'] = {'phase': phase, 'type': type(error).__name__}
            raise
        finally:
            self.c.write_new(directory / 'upgrade-proof.json', result)
        # UI is deliberately a separate CLI phase; never silently run it twice.

    def ui(self, directory):
        control = self.native_control(); p = self.p
        proof = self.load(directory / 'upgrade-proof.json')
        require(proof['kind'] == p.kinds['upgrade'] and proof['passed'] is True and proof['api'] == p.api and
                proof['versions'] == list(p.versions) and proof['verificationControlSha'] == proof['upgradeControlSha'] == control,
                'UI_REQUIRES_ACTUAL_SAME_UPGRADE')
        self.validate_build(proof['buildRequest'])
        stages = [self.t.instrumentation_report.execution((directory / (role + '-instrumentation.txt')).read_text(encoding='utf-8'), p.fixture_class, method)
                  for role, method in ((p.seed_role, p.seed_method), (p.verify_role, p.verify_method))]
        require(proof['stages'] == stages and proof['fixtureStagesPassed'] == proof['executedTests'] == 2 and
                proof['skippedTests'] == 0, 'UI_REQUIRES_TWO_ACTUAL_PASSES')
        self.unchanged_install(proof['installationBefore'], proof['installationAfter'])
        verification = self.verified_inputs(directory, control)
        require(proof['buildRequest'] == verification['buildRequest'] and
                proof['originalArtifact'] == verification['originalArtifact'] and
                proof['entries'] == verification['entries'], 'UI_SAME_VERIFIED_INPUTS')
        result = {'kind': p.kinds['ui'], 'passed': False, 'sourceSha': proof['buildRequest']['sourceSha'],
            'controlSha': control, 'class': p.ui_class, 'method': p.ui_method,
            'scope': 'Actual legacy LanSyncActivity launch/recreate and synthetic repository/image decode after upgrade; not group UI, FGS, remote/cloud, OS death or human acceptance.',
            'installUninstallOrClearPerformed': False, 'humanAcceptance': False}
        phase = 'ui-before'
        try:
            device = self.device(directory); device.select('ui')
            before = device.state('ui-before-package')
            require(before == proof['installationAfter'] and before['versionCode'] == p.versions[1], 'UI_INSTALL_CHANGED')
            text = device.proof('ui-before-proof')
            require(hashlib.sha256(text.encode('utf-8')).hexdigest() == proof['seedProofSha256'], 'UI_PROOF_CHANGED')
            for index, path in enumerate(self.t.candidate84_upgrade.SAMPLE_PATHS):
                stdout, stderr, code = device.command(['shell', 'run-as', p.package, 'ls', path], 'ui-sample-absence-' + str(index), (0, 1))
                self.t.candidate84_upgrade.require_sample_absent(stdout, stderr, code, path)
            phase = 'actual-live-activity'
            actual = device.instrument(p.ui_class, p.ui_method, 'ui-smoke-instrumentation')
            after = device.state('ui-after-package')
            require(before == after and device.proof('ui-after-proof') == text, 'UI_INSTALL_OR_PROOF_CHANGED')
            result.update(passed=True, execution=actual, priorUpgradeTestsPassed=2, priorUpgradeSkipped=0,
                installationBefore=before, installationAfter=after, upgradeSeedProofUnchanged=True,
                samplePathsAbsentBefore=list(self.t.candidate84_upgrade.SAMPLE_PATHS))
        except Exception as error:
            result['failure'] = {'phase': phase, 'type': type(error).__name__}
            raise
        finally:
            self.c.write_new(directory / 'ui-smoke-result.json', result)
