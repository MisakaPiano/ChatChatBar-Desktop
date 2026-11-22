"""Android 86 to 87 control profile. The successor SHA comes from a frozen request."""

from dataclasses import replace
from types import MappingProxyType

from candidate86_v6_profile import PROFILE as PRIOR
from upgrade_series_engine import Role


BASE_CONTROL = "3bd17c5c78c7e1dd3a67e2b082675f292a57cf1d"
BASE_SOURCE = "f5fc060546eb9479b2566375761903d383c538b1"
BUILD_REQUEST = ".github/lan-delivered86-candidate87-build-request.json"
SIGN_REQUEST = ".github/lan-delivered86-candidate87-sign-request.json"
KOTLIN_CLASS = "com.example.chatbar.lanSync.LanDelivered86UpgradeTest"
ARGUMENT = "lanDelivered86UpgradeFixture"
PACKAGE = PRIOR.package

PROFILE = replace(
    PRIOR,
    name="delivered86-candidate87",
    versions=(86, 87),
    roles=(
        Role("base86", "base86-input.apk", PACKAGE, 86),
        Role("seed86", "seed86-tests-input.apk", PACKAGE + ".test", None),
        Role("candidate87", "candidate87-input.apk", PACKAGE, 87),
        Role("verify87", "verify87-tests-input.apk", PACKAGE + ".test", None),
    ),
    base_role="base86",
    seed_role="seed86",
    candidate_role="candidate87",
    verify_role="verify87",
    base=MappingProxyType({
        "sourceSha": BASE_SOURCE,
        "versionCode": 86,
        "runId": 44662039106,
        "artifactId": 14596631268,
        "artifactName": "ccb-lan-delivered85-candidate86-v6-inputs",
        "outerBytes": 221320913,
        "outerSha256": "9ce28f8084cd2e5f0c159992d4c5cf5708c6956d78743b06d1b8977367ed1136",
        "controlSha": BASE_CONTROL,
        "member": "candidate86-input.apk",
        "rawBytes": 109666459,
        "rawSha256": "66ad768651e69bbe08c26f87e83cbcb3986ea604f75f167601aa2137d1a8f1b1",
        "deliveredBytes": 109663831,
        "deliveredSha256": "ac8e1b84c6cc0327eb7c2dd5ccb4c2cab594298040939b68966d0379632ad7e0",
    }),
    base_manifest=MappingProxyType({
        "bytes": 5962,
        "sha256": "d4f3a0cb29f8bf37d0ba27e3669d1ed844717c487d7c2a419799c3f42060af0c",
    }),
    base_public_control="107d9049a720bdbc370a58fd2941514235964822",
    base_public_path=".github/lan-delivered85-candidate86-v6-sign-request.json",
    base_public_bytes=13726,
    base_public_sha256="e0c4f28b8fc62d919d621311aef7fbd95845e2c045dd77ca3d16d85344675b55",
    base_public_role="candidate86",
    fixture_files=(("seed86", "candidate87_seed86.kt"), ("verify87", "candidate87_verify87.kt")),
    fixture_target="app/app/src/androidTest/java/com/example/chatbar/lanSync/LanDelivered86UpgradeTest.kt",
    fixture_class=KOTLIN_CLASS,
    argument=ARGUMENT,
    proof_root="lan-delivered86-upgrade-proof-v1",
    seed_method="seedConversationGroupAndOldRecoveryCode",
    verify_method="verifyConversationGroupAndOldRecoveryCode",
    ui_class=KOTLIN_CLASS,
    ui_method="verifyConversationGroupAndOldRecoveryCode",
    ui_argument=ARGUMENT,
    kinds=MappingProxyType({
        "build": "CCB_LAN_DELIVERED86_CANDIDATE87_BUILD_V1",
        "raw": "CCB_LAN_86_TO_87_FOUR_RAW_INPUTS",
        "sign": "CCB_LAN_DELIVERED86_CANDIDATE87_PUBLIC_SIGNING_V1",
        "verify": "CCB_LAN_86_TO_87_SIGNED_FOUR_INPUTS",
        "upgrade": "CCB_LAN_EXACT_DELIVERED86_TO_CANDIDATE87_PROOF",
        "ui": "CCB_LAN87_COLD_OPEN_ORIGINAL_GROUP",
    }),
    artifact_name="ccb-lan-delivered86-candidate87-inputs",
    tamper_field="protectedCandidate87ByteTamperRejected",
    ui_scope="Explicit force-stop and cold app process. Open the real group Activity and unlock the same synthetic paused founder group with the pre-upgrade recovery code. Check original installation, conversation, PNG, protected group files, membership and ledger.",
)
