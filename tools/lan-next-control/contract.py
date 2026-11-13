"""Public, frozen inputs for an additive delivered82 -> candidate83 proof."""
import hashlib
import json
from pathlib import Path
import re

PACKAGE = 'com.example.chatbar.lansyncnext'
CERTIFICATE = '801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
BUILD_KIND = 'CCB_LAN_DELIVERED82_CANDIDATE83_BUILD_V1'
SIGN_KIND = 'CCB_LAN_DELIVERED82_CANDIDATE83_PUBLIC_SIGNING_V1'
BASE = {
    'sourceSha': '10b14a8c89d2fc4b2ed2188546af4339b0e42b57',
    'versionCode': 82,
    'runId': 43349245949,
    'artifactId': 14004090088,
    'artifactName': 'ccb-lan-next-signing-inputs',
    'controlSha': '8946caa73a1c6346a1a4f81bb0135795241c7738',
    'outerBytes': 212918205,
    'outerSha256': '0238df5c5c77121aa1a0e29ad687f41ccc303cea90844b0413692dc8047cadce',
    'member': 'upgrade-input.apk',
    'rawSha256': '7d65f385078626788215f3230ade235269a4993eb5159bc8e10536c0568ec844',
    'deliveredSha256': '7d6b489ffd3529c5dfef7be4673eae8efdf96f38dfbeffb92a760ba70c47343f',
    'deliveredBytes': 106321320,
}
ROLES = {
    'base82': ('base82-input.apk', PACKAGE, 82),
    'seed82': ('seed82-tests-input.apk', PACKAGE + '.test', None),
    'candidate83': ('candidate83-input.apk', PACKAGE, 83),
    'verify83': ('verify83-tests-input.apk', PACKAGE + '.test', None),
}
FIXTURE_TARGET = 'app/app/src/androidTest/java/com/example/chatbar/lanSync/LanDelivered82UpgradeTest.kt'
FIXTURE_CLASS = 'com.example.chatbar.lanSync.LanDelivered82UpgradeTest'
PROOF_ROOT = 'lan-delivered82-upgrade-proof-v1'
HERE = Path(__file__).resolve().parent


def require(condition, label):
    if not condition:
        raise ValueError(label)


def sha(value, length=64):
    require(isinstance(value, str) and re.fullmatch('[a-f0-9]{' + str(length) + '}', value), 'SHA_FORMAT')
    return value


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def fixture_bytes(path):
    # Requests made on Windows must bind the same source bytes used on Linux.
    value = Path(path).read_bytes().replace(b'\r\n', b'\n')
    require(b'\r' not in value, 'FIXTURE_LINE_ENDINGS')
    value.decode('utf8')
    return value


def fixture_hash(path):
    return hashlib.sha256(fixture_bytes(path)).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf8'))


def write_new(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def fixtures():
    return {role: {'path': 'fixtures/' + role + '.kt',
                   'sha256': fixture_hash(HERE / 'fixtures' / (role + '.kt'))}
            for role in ('seed82', 'verify83')}


def build_request(source):
    sha(source, 40)
    require(source != BASE['sourceSha'], 'CANDIDATE_MUST_BE_NEW_SOURCE')
    return {'kind': BUILD_KIND, 'format': 1, 'sourceSha': source, 'series': 'next',
            'versions': [82, 83], 'package': PACKAGE, 'certificateSha256': CERTIFICATE,
            'deliveredBase': dict(BASE), 'fixtures': fixtures(), 'allowedStateMigrations': []}


def validate_build(request):
    require(isinstance(request, dict), 'BUILD_REQUEST')
    expected = build_request(request.get('sourceSha'))
    require(request == expected, 'BUILD_CONTRACT_MISMATCH')
    return request


def artifact_metadata(value):
    require(isinstance(value, dict) and set(value) == {'id', 'runId', 'name', 'bytes', 'outerSha256', 'controlSha'}, 'ARTIFACT_FIELDS')
    for field in ('id', 'runId', 'bytes'):
        require(type(value[field]) is int and value[field] > 0, 'ARTIFACT_' + field)
    require(value['name'] == 'ccb-lan-delivered82-candidate83-inputs', 'ARTIFACT_NAME')
    sha(value['outerSha256']); sha(value['controlSha'], 40)
    return value


def validate_public_block(block, raw_hash, signed_base=False):
    require(isinstance(block, dict), 'PUBLIC_BLOCK')
    require(set(block) == {'format', 'purpose', 'sourceSha256', 'outputSha256', 'bytes',
                          'entries', 'certificateSha256', 'blockBase64', 'scheme',
                          'algorithm', 'officialVerifierPassed'}, 'PUBLIC_BLOCK_FIELDS')
    require(block.get('format') == 1 and block.get('purpose') == 'ISOLATED_LAN_NEXT_PREVIEW', 'PUBLIC_BLOCK_PURPOSE')
    require(block.get('certificateSha256') == CERTIFICATE, 'SIGNER_CHANGED')
    require(block.get('sourceSha256') == raw_hash, 'PUBLIC_BLOCK_SOURCE')
    sha(block.get('outputSha256'))
    require(type(block.get('bytes')) is int and block['bytes'] > 0, 'PUBLIC_BLOCK_SIZE')
    require(type(block.get('entries')) is int and block['entries'] > 0, 'PUBLIC_BLOCK_ENTRIES')
    require(block['scheme'] == 'v2' and block['algorithm'] == 'RSA-2048-PKCS1-SHA256', 'PUBLIC_BLOCK_SCHEME')
    require(block['officialVerifierPassed'] is False, 'SDK_PROOF_MUST_BE_INDEPENDENT')
    require(isinstance(block.get('blockBase64'), str) and 32 <= len(block['blockBase64']) <= 45000, 'PUBLIC_BLOCK_ENCODING')
    if signed_base:
        require(block['outputSha256'] == BASE['deliveredSha256'] and block['bytes'] == BASE['deliveredBytes'], 'DELIVERED82_CHANGED')


def validate_sign(request):
    require(set(request) == {'kind', 'format', 'buildRequest', 'originalArtifact', 'entries'}, 'SIGN_REQUEST_FIELDS')
    require(request['kind'] == SIGN_KIND and request['format'] == 1, 'SIGN_REQUEST_KIND')
    validate_build(request['buildRequest']); artifact_metadata(request['originalArtifact'])
    require(set(request['entries']) == set(ROLES), 'FOUR_INPUTS_REQUIRED')
    for role, (filename, _, _) in ROLES.items():
        entry = request['entries'][role]
        require(set(entry) == {'filename', 'publicSigning'} and entry['filename'] == filename, 'ROLE_FILENAME')
        block = entry['publicSigning']
        validate_public_block(block, BASE['rawSha256'] if role == 'base82' else block.get('sourceSha256'), role == 'base82')
        sha(block['sourceSha256'])
    return request
