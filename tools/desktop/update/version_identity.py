"""Release labels, monotonic build order and legacy-root-safe storage are distinct identities."""
from dataclasses import dataclass
import re

_SEMVER = re.compile(r'(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})'
                     r'(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?')
MAX_NUMBER = 2_147_483_647


def parts(display):
    if not isinstance(display, str) or len(display) > 80:
        raise ValueError('RELEASE_SEMVER_REFUSED')
    match = _SEMVER.fullmatch(display)
    if not match:
        raise ValueError('RELEASE_SEMVER_REFUSED')
    core = tuple(int(match[i]) for i in (1, 2, 3))
    pre = tuple(match[4].split('.')) if match[4] is not None else ()
    if any(len(item) > 20 or item.isdigit() and (
            len(item) > 1 and item.startswith('0') or int(item) > 9_223_372_036_854_775_807) for item in pre):
        raise ValueError('RELEASE_SEMVER_REFUSED')
    return core, pre


def compare_display(left, right):
    left_core, left_pre = parts(left)
    right_core, right_pre = parts(right)
    if left_core != right_core:
        return (left_core > right_core) - (left_core < right_core)
    if not left_pre or not right_pre:
        return (not left_pre) - (not right_pre)
    for first, second in zip(left_pre, right_pre):
        if first == second:
            continue
        if first.isdigit() and second.isdigit():
            return (int(first) > int(second)) - (int(first) < int(second))
        if first.isdigit() != second.isdigit():
            return -1 if first.isdigit() else 1
        return (first > second) - (first < second)
    return (len(left_pre) > len(right_pre)) - (len(left_pre) < len(right_pre))


def release_channel(display):
    return 'preview' if parts(display)[1] else 'stable'


@dataclass(frozen=True)
class ReleaseIdentity:
    display: str
    build: int
    channel: str
    storage_version: str

    @classmethod
    def create(cls, display, build=None, channel=None):
        core, pre = parts(display)
        legacy = re.fullmatch(r'0\.1\.0-preview\.([1-9][0-9]*)', display)
        if build is None:
            # Compatibility only for the exact original producer contract.
            if not legacy:
                raise ValueError('RELEASE_BUILD_NUMBER_REQUIRED')
            build = int(legacy[1])
        if type(build) is not int or not 1 <= build <= MAX_NUMBER:
            raise ValueError('RELEASE_BUILD_NUMBER_REFUSED')
        if legacy and int(legacy[1]) != build:
            raise ValueError('LEGACY_BRIDGE_BUILD_MISMATCH')
        expected_channel = 'preview' if pre else 'stable'
        if channel is not None and channel != expected_channel:
            raise ValueError('RELEASE_CHANNEL_REFUSED')
        storage = '.'.join(str(number) for number in core) + '-preview.' + str(build)
        return cls(display, build, expected_channel, storage)

    def permits_target(self, target):
        """Source/hash/reader verification remains with the existing updater and installer owners."""
        comparison = compare_display(target.display, self.display)
        if target.build < self.build or comparison < 0:
            raise ValueError('RELEASE_BUILD_OR_VERSION_DOWNGRADE')
        if target.build == self.build:
            if target.display != self.display:
                raise ValueError('RELEASE_VERSION_CHANGED_WITHOUT_BUILD')
            return False
        if comparison == 0:
            raise ValueError('RELEASE_DISPLAY_REUSED')
        return True


if __name__ == '__main__':
    import argparse
    import json
    from dataclasses import asdict
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--display', required=True)
    parser.add_argument('--build-number', type=int)
    parser.add_argument('--channel')
    args = parser.parse_args()
    print(json.dumps(asdict(ReleaseIdentity.create(args.display, args.build_number, args.channel))))
