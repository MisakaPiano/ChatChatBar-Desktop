"""Pure synthetic identity contracts; native installation and signed metadata have separate proofs."""
import unittest
from version_identity import ReleaseIdentity, compare_display


class ReleaseIdentityTest(unittest.TestCase):
    def test_legacy_bridge_stable_and_next_stable_keep_distinct_monotonic_builds_and_legacy_safe_storage(self):
        old = ReleaseIdentity.create('0.1.0-preview.1229')
        bridge = ReleaseIdentity.create('0.1.0-preview.1234', 1234, 'preview')
        stable = ReleaseIdentity.create('1.0.0', 1235, 'stable')
        later = ReleaseIdentity.create('1.0.1', 1236, 'stable')
        self.assertEqual('0.1.0-preview.1229', old.storage_version)
        self.assertEqual('1.0.0-preview.1235', stable.storage_version)
        self.assertEqual('1.0.0', stable.display)
        for current, target in ((old, bridge), (bridge, stable), (stable, later)):
            self.assertTrue(current.permits_target(target))
            self.assertRegex(target.storage_version + '-' + 'a' * 12,
                             r'^[0-9]+\.[0-9]+\.[0-9]+-preview\.[1-9][0-9]*-[a-f0-9]{12}$')
        self.assertFalse(stable.permits_target(stable))

    def test_semver_order_is_not_build_order_or_prerelease_lexical_order(self):
        chain = ['1.0.0-alpha.2', '1.0.0-alpha.10', '1.0.0-beta.1', '1.0.0-rc.1', '1.0.0', '1.0.1']
        for left, right in zip(chain, chain[1:]):
            self.assertLess(compare_display(left, right), 0)
            self.assertGreater(compare_display(right, left), 0)
        self.assertEqual(0, compare_display('1.0.0', '1.0.0'))
        stable = ReleaseIdentity.create('1.0.0', 1235)
        for target in (ReleaseIdentity.create('0.9.9', 1236), ReleaseIdentity.create('1.0.1', 1234),
                       ReleaseIdentity.create('1.0.0', 1236),
                       ReleaseIdentity.create('1.0.1', 1235)):
            with self.assertRaises(ValueError):
                stable.permits_target(target)

    def test_coerced_unknown_channel_and_implicit_stable_build_are_refused(self):
        for display in ('01.0.0', '1.0', '1.0.0-preview.01', '1.0.0-', '1.0.0 ', '1.0.0/../x', '2147483648.0.0',
                        '1.0.0+metadata', '1.0.0-' + 'x' * 80):
            with self.assertRaises(ValueError):
                ReleaseIdentity.create(display, 1235)
        for display in ('1000000000.0.0', '1.0.0-' + 'a' * 21, '1.0.0-9223372036854775808'):
            with self.assertRaises(ValueError): ReleaseIdentity.create(display, 1235)
        for build in (True, False, '1235', 0, -1, 2147483648):
            with self.assertRaises(ValueError):
                ReleaseIdentity.create('1.0.0', build)
        with self.assertRaises(ValueError):
            ReleaseIdentity.create('1.0.0')
        with self.assertRaises(ValueError):
            ReleaseIdentity.create('1.0.0', 1235, 'preview')
        with self.assertRaises(ValueError):
            ReleaseIdentity.create('1.0.0-beta.1', 1235, 'stable')
        with self.assertRaises(ValueError):
            ReleaseIdentity.create('0.1.0-preview.1234', 9999, 'preview')


if __name__ == '__main__':
    unittest.main()
