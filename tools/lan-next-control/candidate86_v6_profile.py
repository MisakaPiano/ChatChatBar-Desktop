"""Exact delivered V6/85 to metadata-repaired same-package86. No work at import."""
from dataclasses import replace
from types import MappingProxyType
from upgrade_series_engine import Role
from candidate85_group_profile import PROFILE as PRIOR_PROFILE
SOURCE = '49b7edf434178611c72d31f599b7300a3a3225f2'
BUILD_REQUEST_PATH = '.github/lan-delivered85-candidate86-v6-build-request.json'
SIGN_REQUEST_PATH = '.github/lan-delivered85-candidate86-v6-sign-request.json'
BASE = MappingProxyType({
 'sourceSha':'5c906e6d4a0f31a114326e35a331c4af4dfa6379','versionCode':85,
 'runId':43709620308,'artifactId':14159594293,'artifactName':'ccb-lan-delivered84-candidate85-group-inputs',
 'outerBytes':220556819,'outerSha256':'b9de5ff6896e6ff553e7a37eb2ed81ccc845102f419b81884120723601ae79df',
 'controlSha':'1964d3c932127d307ead1586adbd81e06419eb9d','member':'candidate85-input.apk',
 'rawBytes':109633691,'rawSha256':'d73bde4cd2b8436de286d6931772aaa17fd3ed3ef7e574413d6107f99584aff3',
 'deliveredBytes':109631063,'deliveredSha256':'cd4434719afbffac6ef22858d6499319600aa8c96f6a80b1781c4574fb1504f7'})
PROFILE = replace(PRIOR_PROFILE,
 name='delivered85-candidate86-v6',versions=(85,86),base=BASE,
 roles=(Role('base85','base85-input.apk',PRIOR_PROFILE.package,85),
        Role('seed85','seed85-tests-input.apk',PRIOR_PROFILE.package+'.test',None),
        Role('candidate86','candidate86-input.apk',PRIOR_PROFILE.package,86),
        Role('verify86','verify86-tests-input.apk',PRIOR_PROFILE.package+'.test',None)),
 base_role='base85',seed_role='seed85',candidate_role='candidate86',verify_role='verify86',
 base_manifest=MappingProxyType({'bytes':5641,'sha256':'84dfd3e68f8eb8f102ec68317c6370f8a4e399f82e82d022dadca9aef3be4aaf'}),
 base_public_control='6894d5cddf5bce3200b384c9a398c7b7c80da902',
 base_public_path='.github/lan-delivered84-candidate85-group-sign-request.json',base_public_bytes=13414,
 base_public_sha256='b426ecd2ecde49426c91a88540f6e15967f4397f7c34a551c826294b9ac1e8b0',base_public_role='candidate85',
 fixture_files=(('seed85','candidate86_v6_seed85.kt'),('verify86','candidate86_v6_verify86.kt')),
 fixture_target='app/app/src/androidTest/java/com/example/chatbar/lanSync/LanDelivered85UpgradeTest.kt',
 fixture_class='com.example.chatbar.lanSync.LanDelivered85UpgradeTest',argument='lanDelivered85UpgradeFixture',
 proof_root='lan-delivered85-upgrade-proof-v1',ui_class='com.example.chatbar.lanSync.LanDelivered85UpgradeTest',
 ui_argument='lanDelivered85UpgradeFixture',tamper_field='protectedCandidate86ByteTamperRejected',
 kinds=MappingProxyType({
 'build':'CCB_LAN_DELIVERED85_CANDIDATE86_V6_BUILD_V1','raw':'CCB_LAN_85_TO_86_V6_FOUR_RAW_INPUTS',
 'sign':'CCB_LAN_DELIVERED85_CANDIDATE86_V6_PUBLIC_SIGNING_V1','verify':'CCB_LAN_85_TO_86_V6_SIGNED_FOUR_INPUTS',
 'upgrade':'CCB_LAN_EXACT_DELIVERED85_TO_CANDIDATE86_V6_PROOF','ui':'CCB_LAN86_POST_UPGRADE_V6_GROUP_ACTIVITY_READ'}),
 artifact_name='ccb-lan-delivered85-candidate86-v6-inputs',
 ui_scope=PRIOR_PROFILE.ui_scope+' Original V6 ordinary conversation and exact stored files plus hash/length-bound derived cache compatibility; not archive UI, metadata-scale reproduction, physical-phone or human acceptance.')
