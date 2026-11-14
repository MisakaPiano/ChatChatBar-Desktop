"""Thin, trusted group85 entry point. Requests cannot choose the legacy/group profile or UI selector."""
import argparse
import hashlib
from pathlib import Path
import candidate85_group_profile as group_profile
import candidate85_profile as legacy_profile
import upgrade_series_engine as series_engine
from candidate85_group_profile import PROFILE
from upgrade_series_engine import LegacyTools, SeriesEngine


class GroupEngine(SeriesEngine):
    """Only this variant binds its exact orchestration bytes into its build contract."""
    def build_request(self, source):
        value = super().build_request(source)
        files = {}
        for name, path in (
            ('candidate85_group_control.py', Path(__file__)),
            ('candidate85_group_profile.py', Path(group_profile.__file__)),
            ('upgrade_series_engine.py', Path(series_engine.__file__)),
            ('candidate85_profile.py', Path(legacy_profile.__file__)),
            ('instrumentation_report.py', Path(self.t.instrumentation_report.__file__)),
        ):
            require_file = path.is_file() and not path.is_symlink()
            if not require_file:
                raise ValueError('GROUP_CONTROL_REGULAR_FILE_REQUIRED')
            raw = path.read_bytes()
            files[name] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
        value['trustedGroupControl'] = {'format': 1, 'files': files,
            'expectedSuccessfulInvocations': 3, 'uniqueMethods': 2,
            'seedExecutions': 1, 'verifierExecutions': 2}
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=(
        'validate-build', 'prepare-build', 'fetch-base', 'collect-build', 'validate-raw',
        'prepare-sign', 'validate-sign', 'fetch-raw', 'verify', 'upgrade', 'ui'))
    for flag in ('request', 'seed-dir', 'candidate-dir', 'original-dir', 'output', 'archive', 'build-tools',
                 'raw-dir', 'destination', 'evidence', 'signed-dir', 'artifact-metadata', 'public-blocks'):
        parser.add_argument('--' + flag, type=Path)
    parser.add_argument('--control-sha')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    engine = GroupEngine(PROFILE, LegacyTools(here.parents[1]), here)
    load = engine.load
    if args.mode == 'validate-build':
        print(engine.validate_build(load(args.request))['sourceSha'])
    elif args.mode == 'prepare-build':
        engine.prepare(load(args.request), args.seed_dir, args.candidate_dir, args.output)
    elif args.mode == 'fetch-base':
        engine.fetch_base(args.archive, args.output)
    elif args.mode == 'collect-build':
        engine.collect(load(args.request), args.seed_dir, args.candidate_dir, args.original_dir,
                       args.output, args.control_sha, args.build_tools)
    elif args.mode == 'validate-raw':
        engine.validate_raw(args.original_dir, load(args.request), args.control_sha)
    elif args.mode == 'prepare-sign':
        engine.prepare_sign(args.raw_dir, load(args.artifact_metadata), load(args.public_blocks), args.output)
    elif args.mode == 'validate-sign':
        print(engine.validate_sign(load(args.request))['buildRequest']['sourceSha'])
    elif args.mode == 'fetch-raw':
        engine.fetch_raw(load(args.request), args.archive, args.destination, args.evidence)
    elif args.mode == 'verify':
        engine.verify(args.raw_dir, load(args.request), args.output, args.build_tools, args.control_sha)
    elif args.mode == 'upgrade':
        engine.upgrade(args.signed_dir)
    else:
        engine.ui(args.signed_dir)


if __name__ == '__main__':
    main()
