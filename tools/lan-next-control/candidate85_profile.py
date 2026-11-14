"""Only trusted84->85 profile. No candidate source or request is frozen here."""
from types import MappingProxyType
from upgrade_series_engine import Role, SeriesProfile

PACKAGE = 'com.example.chatbar.lansyncnext'
CERTIFICATE = '801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
BASE = MappingProxyType({
    'sourceSha': 'e606bf2a2723fbc896323a8071cfd52dde99a4c6', 'versionCode': 84,
    'runId': 43602954622, 'artifactId': 14117481315,
    'artifactName': 'ccb-lan-delivered83-candidate84-inputs',
    'outerBytes': 219240639,
    'outerSha256': '58b420da0c51e6147f29dd374773f451da9eebf23d671447b989cb8b0188c495',
    'controlSha': 'fc20ab621cae06e8cb7b6bb0f177751253e51238',
    'member': 'candidate84-input.apk', 'rawBytes': 108961919,
    'rawSha256': '4db30d4ce465cf78f0b8d73c46d29e163163fd160659593db3fffa23b8ef50e7',
    'deliveredBytes': 108959291,
    'deliveredSha256': '94a26127971cd88375b23fdb4981d5e2238fb2253640c62c62a3e70787603572',
})
PROFILE = SeriesProfile(
    name='delivered84-candidate85', package=PACKAGE, certificate=CERTIFICATE, versions=(84, 85),
    roles=(Role('base84', 'base84-input.apk', PACKAGE, 84),
           Role('seed84', 'seed84-tests-input.apk', PACKAGE + '.test', None),
           Role('candidate85', 'candidate85-input.apk', PACKAGE, 85),
           Role('verify85', 'verify85-tests-input.apk', PACKAGE + '.test', None)),
    base_role='base84', seed_role='seed84', candidate_role='candidate85', verify_role='verify85', base=BASE,
    base_manifest=MappingProxyType({'bytes': 4587, 'sha256': 'ac66ac5b2ebf9dc504c1a8fae2330bffc83fef9843fd968eb7a75fce0989d7ff'}),
    base_public_control='31efb7602f725357b6e1bb430571c1f03279a6df',
    base_public_path='.github/lan-delivered83-candidate84-sign-request.json',
    base_public_bytes=12366,
    base_public_sha256='62559df2528768b5d17106aed6c1a6ec1bd709e7c65924e22f4c7fc35c306806',
    base_public_role='candidate84',
    fixture_files=(('seed84', 'candidate85_seed84.kt'), ('verify85', 'candidate85_verify85.kt')),
    fixture_target='app/app/src/androidTest/java/com/example/chatbar/lanSync/LanDelivered84UpgradeTest.kt',
    fixture_class='com.example.chatbar.lanSync.LanDelivered84UpgradeTest', argument='lanDelivered84UpgradeFixture',
    proof_root='lan-delivered84-upgrade-proof-v1',
    seed_method='seedSyntheticRepositoryAndProtectedPeerIdentity',
    verify_method='verifySyntheticRepositoryAndProtectedPeerIdentityAfterUpgrade',
    ui_class='com.example.chatbar.lanSync.LanSyncUiTest',
    ui_method='syncActivityReopensAndOfflineSampleUsesActualRepositories',
    kinds=MappingProxyType({
        'build': 'CCB_LAN_DELIVERED84_CANDIDATE85_BUILD_V1',
        'raw': 'CCB_LAN_84_TO_85_FOUR_RAW_INPUTS',
        'sign': 'CCB_LAN_DELIVERED84_CANDIDATE85_PUBLIC_SIGNING_V1',
        'verify': 'CCB_LAN_84_TO_85_SIGNED_FOUR_INPUTS',
        'upgrade': 'CCB_LAN_EXACT_DELIVERED84_TO_CANDIDATE85_PROOF',
        'ui': 'CCB_LAN85_POST_UPGRADE_ACTIVITY_REPOSITORY_SMOKE',
    }), artifact_name='ccb-lan-delivered84-candidate85-inputs',
    tamper_field='protectedCandidate85ByteTamperRejected',
)
