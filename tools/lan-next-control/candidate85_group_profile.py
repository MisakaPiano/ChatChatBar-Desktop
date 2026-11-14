"""Trusted actual-app-group84->85 variant; old85 defaults and delivered84 stay intact.

No source, request, signing material or native execution is produced at import.
"""
from dataclasses import replace
from types import MappingProxyType
from candidate85_profile import PROFILE as LEGACY_PROFILE

BUILD_REQUEST_PATH = '.github/lan-delivered84-candidate85-group-build-request.json'
SIGN_REQUEST_PATH = '.github/lan-delivered84-candidate85-group-sign-request.json'
UI_SCOPE = ('Actual paused founder-group LanSyncActivity launch/close and authenticated original group/key/author/native/catalog pins, '
            'complete protected group hashes and the original sixteen repository/Keystore upgrade checks. '
            'Across upgrade plus UI phases: three actual invocations, two unique methods (seed once, verifier twice); '
            'the UI phase is a repeat of the verifier, not a third distinct function or an additional measured cold PID. '
            'Not legacy runtime/sample interaction, Activity recreate, FGS, joined/recovery/rotation/revoked states, '
            'remote/cloud transfer, OS death, physical phone or human acceptance.')

PROFILE = replace(LEGACY_PROFILE,
    name='delivered84-candidate85-group',
    fixture_files=(('seed84', 'candidate85_group_seed84.kt'), ('verify85', 'candidate85_group_verify85.kt')),
    ui_class=LEGACY_PROFILE.fixture_class,
    ui_method=LEGACY_PROFILE.verify_method,
    ui_scope=UI_SCOPE,
    ui_argument=LEGACY_PROFILE.argument,
    kinds=MappingProxyType({
        'build': 'CCB_LAN_DELIVERED84_CANDIDATE85_GROUP_BUILD_V1',
        'raw': 'CCB_LAN_84_TO_85_GROUP_FOUR_RAW_INPUTS',
        'sign': 'CCB_LAN_DELIVERED84_CANDIDATE85_GROUP_PUBLIC_SIGNING_V1',
        'verify': 'CCB_LAN_84_TO_85_GROUP_SIGNED_FOUR_INPUTS',
        'upgrade': 'CCB_LAN_EXACT_DELIVERED84_TO_CANDIDATE85_GROUP_PROOF',
        'ui': 'CCB_LAN85_POST_UPGRADE_GROUP_ACTIVITY_READ',
    }),
    artifact_name='ccb-lan-delivered84-candidate85-group-inputs',
)
