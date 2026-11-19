"""Thin source-bound V6/85->86 adapter over the unchanged generic series engine."""
import hashlib
from pathlib import Path
from types import FunctionType
import candidate85_group_control as prior_control
import candidate85_group_profile as prior_group_profile
import candidate85_profile as prior_profile
import upgrade_series_engine as series
import instrumentation_report as parser
import candidate86_v6_profile as current_profile
from candidate86_v6_profile import PROFILE, SOURCE

class V6Engine(series.SeriesEngine):
    def build_request(self, source):
        series.require(source == SOURCE, 'EXACT_V6_REPAIR_SOURCE_REQUIRED')
        value = super().build_request(source)
        paths = [Path(__file__), Path(current_profile.__file__), Path(series.__file__),
                 Path(prior_control.__file__), Path(prior_group_profile.__file__),
                 Path(prior_profile.__file__), Path(parser.__file__)]
        files = {}
        for path in paths:
            series.require(path.is_file() and not path.is_symlink(), 'V6_PINNED_REGULAR_FILE_REQUIRED')
            raw = path.read_bytes(); files[path.name] = {'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        value['trustedV6Control'] = {'format':1,'files':files,'expectedSuccessfulInvocations':3,
            'uniqueMethods':2,'seedExecutions':1,'verifierExecutions':2}
        return value

    def validate_base(self, directory):
        series.require(self.manifest_descriptor(directory/'raw-manifest.json') == dict(self.p.base_manifest), 'EXACT_BASE_MANIFEST')
        old = self.load(directory/'source-request.json')
        series.require(old['sourceSha'] == self.p.base['sourceSha'], 'EXACT_BASE_SOURCE')
        prior_control.GroupEngine(prior_group_profile.PROFILE, self.t, self.here).validate_raw(directory, old, self.p.base['controlSha'])
        path = directory/self.p.base['member']
        series.require(path.is_file() and not path.is_symlink() and path.stat().st_size == self.p.base['rawBytes'] and
                       self.c.file_hash(path) == self.p.base['rawSha256'], 'EXACT_BASE_RAW_BYTES')
        return path

def main():
    # Reuse the pinned CLI code with private globals; never mutate old profiles/modules.
    private = dict(prior_control.main.__globals__, GroupEngine=V6Engine, PROFILE=PROFILE, __file__=__file__)
    return FunctionType(prior_control.main.__code__, private, 'v6_main')()

if __name__ == '__main__':
    main()
