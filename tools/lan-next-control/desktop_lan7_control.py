"""Exact delivered LAN6 -> LAN7 profile; all previous profiles remain unchanged."""
from contextlib import contextmanager
from types import MappingProxyType
import desktop_control as control
import desktop_lan6_control as prior
native = prior.native
SOURCE = "8b4e88553243a910bc5682da48e72cd8e25220c3"
DELIVERED_LAN6 = MappingProxyType({'series': 'lan6', 'version': '0.1.0-lan.6', 'sourceSha': '5c906e6d4a0f31a114326e35a331c4af4dfa6379', 'controlSha': '1964d3c932127d307ead1586adbd81e06419eb9d', 'runId': 43709620276, 'artifactId': 14160026856, 'artifactName': 'ccb-lan5-lan6-independent-desktop', 'outerBytes': 210710242, 'outerSha256': '1c3f623a3c76ab27c6f7c2e6b3e70d51afab17f829b3edcaf92ac1a132b7f43a', 'filename': 'CCB-0.1.0-lan.6-independent-windows-x64.zip', 'innerBytes': 210595028, 'innerSha256': '9aa08f32971a004fc34fdbb6ecb47a1e69c7fb5a7f6d44452d5631f10054d2c1', 'installerVersion': '0.1.40006', 'buildNumber': '40006'})

@contextmanager
def profile():
    with prior.profile():
        selected = {
            'BUILD_KIND': 'CCB_LAN6_LAN7_DESKTOP_BUILD_V1',
            'UPGRADE_KIND': 'CCB_EXACT_LAN6_LAN7_DESKTOP_PROFILE_UPGRADE_V1',
            'ARTIFACT_NAME': 'ccb-lan6-lan7-independent-desktop',
            'MANIFEST_KIND': 'CCB_LAN7_INDEPENDENT_DESKTOP_ORIGINAL_V1',
            'SERIES': 'lan7', 'VERSION': '0.1.0-lan.7',
            'INSTALLER': '0.1.40007', 'BUILD': '40007',
            'FILENAME': 'CCB-0.1.0-lan.7-independent-windows-x64.zip',
            'BASE': dict(DELIVERED_LAN6),
        }
        phases = {
            'BASE_SERIES': 'lan6', 'CANDIDATE_SERIES': 'lan7',
            'RESULT_KIND': 'EXACT_LAN6_LAN7_PROGRAM_ONLY_PROFILE_UPGRADE',
            'RESULT_FILE': 'native-lan6-lan7-upgrade-result.json',
            'SUCCESS_MARKER': 'LAN6_LAN7_NATIVE_UPGRADE_PASS',
        }
        previous = {key: getattr(control, key) for key in selected}
        previous_phases = {key: getattr(native, key) for key in phases}
        old_validate = control.validate_build
        def validate_exact_build(request):
            old_validate(request)
            control.require(request['sourceSha'] == SOURCE, 'LAN7_EXACT_PACKAGE_SOURCE')
            return request
        try:
            for key, value in selected.items(): setattr(control, key, value)
            for key, value in phases.items(): setattr(native, key, value)
            control.validate_build = validate_exact_build
            yield
        finally:
            control.validate_build = old_validate
            for key, value in previous_phases.items(): setattr(native, key, value)
            for key, value in previous.items(): setattr(control, key, value)

def main():
    with profile(): control.main()

if __name__ == '__main__': main()
