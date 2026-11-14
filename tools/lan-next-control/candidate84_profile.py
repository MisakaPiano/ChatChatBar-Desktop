"""Fixed delivered V4/83 -> candidate84 contract; no signing identity access."""
from pathlib import Path
import json

from contract import (CERTIFICATE, PACKAGE, file_hash, fixture_bytes, fixture_hash,
                      require, sha, validate_public_block, write_new)

HERE = Path(__file__).resolve().parent
BUILD_KIND = 'CCB_LAN_DELIVERED83_CANDIDATE84_BUILD_V1'
RAW_KIND = 'CCB_LAN_83_TO_84_FOUR_RAW_INPUTS'
SIGN_KIND = 'CCB_LAN_DELIVERED83_CANDIDATE84_PUBLIC_SIGNING_V1'
VERIFY_KIND = 'CCB_LAN_83_TO_84_SIGNED_FOUR_INPUTS'
UPGRADE_KIND = 'CCB_LAN_EXACT_DELIVERED83_TO_CANDIDATE84_PROOF'
ARTIFACT_NAME = 'ccb-lan-delivered83-candidate84-inputs'
FIXTURE_TARGET = 'app/app/src/androidTest/java/com/example/chatbar/lanSync/LanDelivered83UpgradeTest.kt'
FIXTURE_CLASS = 'com.example.chatbar.lanSync.LanDelivered83UpgradeTest'
ARGUMENT = 'lanDelivered83UpgradeFixture'
PROOF_ROOT = 'lan-delivered83-upgrade-proof-v1'
SEED_METHOD = 'seedSyntheticRepositoryAndProtectedPeerIdentity'
VERIFY_METHOD = 'verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade'
UI_CLASS = 'com.example.chatbar.lanSync.LanSyncUiTest'
UI_METHOD = 'syncActivityReopensAndOfflineSampleUsesActualRepositories'
BASE = {
    'sourceSha': '90ead92410ca4c5e240f75d120ed6a145fb6f1c8', 'versionCode': 83,
    'runId': 43547894406, 'artifactId': 14095022954,
    'artifactName': 'ccb-lan-delivered82-candidate83-inputs',
    'outerBytes': 216618510,
    'outerSha256': 'd3a4cd8ae1387e013337d3008d8ba9f74d8808ed747e9bf92a065ba48eaa80ba',
    'controlSha': '00b934506b09c25adffa3876c9da7636cc9e2532',
    'member': 'candidate83-input.apk', 'rawBytes': 108388391,
    'rawSha256': '696f6fc17d65d0d86e4884cc5a2a0260ec8b47d1a7ec8af78d62af88c847f1d6',
    'deliveredBytes': 108385763,
    'deliveredSha256': 'ee602f895d31cb5f965821a6107c6584e057c5f0192faf8225a5e84e96e4af7b',
}
BASE_PUBLIC_REQUEST = '.github/lan-next-sync-control-sign-r02-request.json'
BASE_PUBLIC_REQUEST_SHA256 = '514b6c2d906558e557149fa91cd5f77c3815e25a6b0b5c6a6c9826dea861542a'
ROLES = {
    'base83': ('base83-input.apk', PACKAGE, 83),
    'seed83': ('seed83-tests-input.apk', PACKAGE + '.test', None),
    'candidate84': ('candidate84-input.apk', PACKAGE, 84),
    'verify84': ('verify84-tests-input.apk', PACKAGE + '.test', None),
}


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique_object)


def fixtures():
    return {role: {'path': 'candidate84_' + name + '.kt',
                   'sha256': fixture_hash(HERE / ('candidate84_' + name + '.kt'))}
            for role, name in [('seed83', 'seed83'), ('verify84', 'verify84')]}


def build_request(source):
    sha(source, 40)
    require(source != BASE['sourceSha'], 'CANDIDATE_MUST_BE_NEW_SOURCE')
    return {'kind': BUILD_KIND, 'format': 1, 'sourceSha': source, 'series': 'next',
            'versions': [83, 84], 'package': PACKAGE, 'certificateSha256': CERTIFICATE,
            'deliveredBase': dict(BASE), 'fixtures': fixtures(), 'allowedStateMigrations': []}


def validate_build(value):
    require(isinstance(value, dict), 'BUILD_REQUEST')
    require(type(value.get('format')) is int and value['format'] == 1, 'BUILD_FORMAT')
    require(value == build_request(value.get('sourceSha')), 'CANDIDATE84_BUILD_CONTRACT')
    return value


def artifact_metadata(value):
    require(isinstance(value, dict) and set(value) == {
        'id', 'runId', 'name', 'bytes', 'outerSha256', 'controlSha'}, 'ARTIFACT_FIELDS')
    require(value['name'] == ARTIFACT_NAME, 'ARTIFACT_NAME')
    for key in ('id', 'runId', 'bytes'):
        require(type(value[key]) is int and value[key] > 0, 'ARTIFACT_' + key)
    sha(value['outerSha256']); sha(value['controlSha'], 40)
    return value


def base_artifact():
    return {'id': BASE['artifactId'], 'runId': BASE['runId'], 'name': BASE['artifactName'],
            'bytes': BASE['outerBytes'], 'outerSha256': BASE['outerSha256'], 'controlSha': BASE['controlSha']}


def block(value, raw_hash, base=False):
    # Reuse the unchanged strict v2/certificate/public-receipt contract, but never
    # ask its82-only base branch to authenticate the actual delivered83 bytes.
    validate_public_block(value, raw_hash, signed_base=False)
    require(type(value['format']) is int, 'PUBLIC_BLOCK_FORMAT')
    if base:
        require(value['bytes'] == BASE['deliveredBytes'] and
                value['outputSha256'] == BASE['deliveredSha256'], 'DELIVERED83_CHANGED')


def base_public_block():
    path = HERE.parents[1] / BASE_PUBLIC_REQUEST
    import hashlib
    require(hashlib.sha256(fixture_bytes(path)).hexdigest() == BASE_PUBLIC_REQUEST_SHA256,
            'FROZEN83_PUBLIC_REQUEST_CHANGED')
    value = load(path)['entries']['candidate83']['publicSigning']
    block(value, BASE['rawSha256'], True)
    return value


def validate_sign(value):
    require(isinstance(value, dict) and set(value) == {
        'kind', 'format', 'buildRequest', 'originalArtifact', 'rawManifest', 'entries'}, 'SIGN_FIELDS')
    require(value['kind'] == SIGN_KIND and type(value['format']) is int and value['format'] == 1, 'SIGN_KIND')
    validate_build(value['buildRequest']); artifact_metadata(value['originalArtifact'])
    require(set(value['rawManifest']) == {'bytes', 'sha256'} and
            type(value['rawManifest']['bytes']) is int and value['rawManifest']['bytes'] > 0, 'RAW_MANIFEST_DESCRIPTOR')
    sha(value['rawManifest']['sha256'])
    require(set(value['entries']) == set(ROLES), 'FOUR_INPUTS_REQUIRED')
    for role, (filename, _, _) in ROLES.items():
        entry = value['entries'][role]
        require(set(entry) == {'filename', 'publicSigning'} and entry['filename'] == filename, 'ROLE_FILENAME')
        public = entry['publicSigning']
        block(public, BASE['rawSha256'] if role == 'base83' else public.get('sourceSha256'), role == 'base83')
        sha(public['sourceSha256'])
    require(value['entries']['base83']['publicSigning'] == base_public_block(), 'EXACT83_PUBLIC_BLOCK')
    return value
