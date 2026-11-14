"""Thin, fixed85 entry point. Requests cannot choose a profile or native selector."""
import argparse
from pathlib import Path
from candidate85_profile import PROFILE
from upgrade_series_engine import LegacyTools, SeriesEngine


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
    engine = SeriesEngine(PROFILE, LegacyTools(here.parents[1]), here)
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
