"""Five cloud launches using the fixed delivered V5 -> lan.6 profile and existing engine."""
import argparse
from pathlib import Path

from contract import load
from desktop_lan6_control import native, profile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with profile():
        native.run(load(args.request), args.output)


if __name__ == '__main__':
    main()
