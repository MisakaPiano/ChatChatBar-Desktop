"""Trusted V4 lan.4 -> lan.5 profile for the established Desktop controls."""
from contextlib import contextmanager
from types import MappingProxyType

import desktop_control as control
import prove_desktop_lan3_lan4 as native


# Exact original delivered V4 artifact; never rebuild this baseline.
DELIVERED_V4 = MappingProxyType({
    'series': 'lan4', 'version': '0.1.0-lan.4',
    'sourceSha': '90ead92410ca4c5e240f75d120ed6a145fb6f1c8',
    'controlSha': '00b934506b09c25adffa3876c9da7636cc9e2532',
    'runId': 43547894348, 'artifactId': 14094727580,
    'artifactName': 'ccb-lan3-lan4-independent-desktop',
    'outerBytes': 207361405,
    'outerSha256': '9dc0cd166df7e68e61c5062720891161e20296d48e34464859ad13e91e471d09',
    'filename': 'CCB-0.1.0-lan.4-independent-windows-x64.zip',
    'innerBytes': 207247398,
    'innerSha256': 'df1c75eaa138915f3718487af4c09fb9eee14c2d0b525df904f778032ecbf829',
    'installerVersion': '0.1.40004', 'buildNumber': '40004',
})


@contextmanager
def profile():
    # No request field can choose a profile, baseline, output name or native phase.
    selected = {
        'BUILD_KIND': 'CCB_LAN4_LAN5_DESKTOP_BUILD_V1',
        'UPGRADE_KIND': 'CCB_EXACT_LAN4_LAN5_DESKTOP_PROFILE_UPGRADE_V1',
        'ARTIFACT_NAME': 'ccb-lan4-lan5-independent-desktop',
        'MANIFEST_KIND': 'CCB_LAN5_INDEPENDENT_DESKTOP_ORIGINAL_V1',
        'SERIES': 'lan5', 'VERSION': '0.1.0-lan.5',
        'INSTALLER': '0.1.40005', 'BUILD': '40005',
        'FILENAME': 'CCB-0.1.0-lan.5-independent-windows-x64.zip',
        'BASE': dict(DELIVERED_V4),
    }
    phases = {
        'BASE_SERIES': 'lan4', 'CANDIDATE_SERIES': 'lan5',
        'RESULT_KIND': 'EXACT_LAN4_LAN5_PROGRAM_ONLY_PROFILE_UPGRADE',
        'RESULT_FILE': 'native-lan4-lan5-upgrade-result.json',
        'SUCCESS_MARKER': 'LAN4_LAN5_NATIVE_UPGRADE_PASS',
    }
    previous = {key: getattr(control, key) for key in selected}
    old_phases = {key: getattr(native, key) for key in phases}
    try:
        for key, value in selected.items():
            setattr(control, key, value)
        for key, value in phases.items():
            setattr(native, key, value)
        yield
    finally:
        for key, value in old_phases.items():
            setattr(native, key, value)
        for key, value in previous.items():
            setattr(control, key, value)


def main():
    with profile():
        control.main()


if __name__ == '__main__':
    main()
