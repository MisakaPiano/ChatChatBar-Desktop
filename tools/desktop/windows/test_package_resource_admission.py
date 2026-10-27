"""Pure resource admission tests: no packages, processes or system settings are changed."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_directory_contract_fixture as fixture


class PackageResourceAdmission(unittest.TestCase):
    def budget(self, available=900 * 1024 * 1024):
        return {"availableMemory": available, "hFree": 2 * 1024 ** 3, "iFree": 2 * 1024 ** 3}

    def test_default_memory_floor_is_preserved(self):
        with self.assertRaisesRegex(AssertionError, "INSUFFICIENT_MEMORY"):
            fixture.check_resource_budget(self.budget())

    def test_explicit_pressure_option_reports_actual_standard_gate(self):
        low = self.budget()
        fixture.check_resource_budget(low, True)
        self.assertTrue(low["memoryPressureExplicitlyAccepted"])
        self.assertFalse(low["standardMemoryGatePassed"])
        enough = self.budget(2 * 1024 ** 3)
        fixture.check_resource_budget(enough, True)
        self.assertTrue(enough["standardMemoryGatePassed"])

    def test_memory_option_never_waives_either_disk_floor(self):
        for key in ("hFree", "iFree"):
            value = self.budget(); value[key] = 0
            with self.subTest(drive=key), self.assertRaisesRegex(AssertionError, "INSUFFICIENT_PACKAGE_DISK"):
                fixture.check_resource_budget(value, True)


if __name__ == "__main__":
    unittest.main()
