"""Five actual cloud launches using the fixed V4 -> lan.5 profile and existing engine."""
import argparse
from pathlib import Path

from contract import load
from desktop_lan5_control import native, profile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with profile():
        native.run(load(args.request), args.output)


if __name__ == '__main__':
    main()
