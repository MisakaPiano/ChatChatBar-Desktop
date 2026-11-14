"""Fixed delivered V5 -> lan.6 trusted profile; source/build/upgrade are not executed here."""
from contextlib import contextmanager
from types import MappingProxyType

import desktop_control as control
import desktop_lan5_control as prior

native = prior.native

# Exact independently reviewed, interim-delivered V5 original; never rebuild this baseline.
# V5 humanAcceptance=False and fullCloudScopeDelivered=False remain separate facts.
DELIVERED_V5 = MappingProxyType({
    'series': 'lan5', 'version': '0.1.0-lan.5',
    'sourceSha': 'e606bf2a2723fbc896323a8071cfd52dde99a4c6',
    'controlSha': 'fc20ab621cae06e8cb7b6bb0f177751253e51238',
    'runId': 43602954624, 'artifactId': 14117541676,
    'artifactName': 'ccb-lan4-lan5-independent-desktop',
    'outerBytes': 209117556,
    'outerSha256': 'cc0ceff09b889d6f316d627fc9773e310f4d3371e442d01477ff1811b8bf8299',
    'filename': 'CCB-0.1.0-lan.5-independent-windows-x64.zip',
    'innerBytes': 209002343,
    'innerSha256': 'a3620bd2892b8f5fdfe6aaace2fd65b2ac9ee5caad33c0d85adbf73c7f659d84',
    'installerVersion': '0.1.40005', 'buildNumber': '40005',
})


@contextmanager
def profile():
    # Request fields cannot select a profile, baseline, output identity or native phase.
    # Nest the established profile so its defaults/finally restoration remain untouched.
    with prior.profile():
        selected = {
            'BUILD_KIND': 'CCB_LAN5_LAN6_DESKTOP_BUILD_V1',
            'UPGRADE_KIND': 'CCB_EXACT_LAN5_LAN6_DESKTOP_PROFILE_UPGRADE_V1',
            'ARTIFACT_NAME': 'ccb-lan5-lan6-independent-desktop',
            'MANIFEST_KIND': 'CCB_LAN6_INDEPENDENT_DESKTOP_ORIGINAL_V1',
            'SERIES': 'lan6', 'VERSION': '0.1.0-lan.6',
            'INSTALLER': '0.1.40006', 'BUILD': '40006',
            'FILENAME': 'CCB-0.1.0-lan.6-independent-windows-x64.zip',
            'BASE': dict(DELIVERED_V5),
        }
        phases = {
            'BASE_SERIES': 'lan5', 'CANDIDATE_SERIES': 'lan6',
            'RESULT_KIND': 'EXACT_LAN5_LAN6_PROGRAM_ONLY_PROFILE_UPGRADE',
            'RESULT_FILE': 'native-lan5-lan6-upgrade-result.json',
            'SUCCESS_MARKER': 'LAN5_LAN6_NATIVE_UPGRADE_PASS',
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
