import json
import unittest

import group_ui_control as control


class GroupUiControlGuardsTest(unittest.TestCase):
    def request(self, **changes):
        value = {"format": 1, "sourceSha": "a" * 40, "fixtureSha256": "b" * 64}
        value.update(changes)
        return json.dumps(value).encode("utf-8")

    def test_exact_request_preserves_source_pin(self):
        value = control.request_bytes(self.request(), "b" * 64)
        self.assertEqual("a" * 40, value["sourceSha"])

    def test_invalid_source_and_fixture_pins_refuse(self):
        for sha in ["main", "a" * 39, "A" * 40, "a" * 41, None, 1]:
            with self.subTest(source=sha), self.assertRaises(control.ControlError):
                control.request_bytes(self.request(sourceSha=sha), "b" * 64)
        for sha in ["b" * 63, "B" * 64, "c" * 64, None, True]:
            with self.subTest(fixture=sha), self.assertRaises(control.ControlError):
                control.request_bytes(self.request(fixtureSha256=sha), "b" * 64)

    def test_request_cannot_change_target_or_hide_duplicate_fields(self):
        for raw in [self.request(format=True), self.request(format=2), self.request(extra="ignored"),
                    b'{"format":1,"sourceSha":"' + b'a' * 40 + b'","fixtureSha256":"' + b'b' * 64 +
                    b'","sourceSha":"' + b'c' * 40 + b'"}', b'[]']:
            with self.subTest(raw=raw), self.assertRaises(control.ControlError):
                control.request_bytes(raw, "b" * 64)

    def test_only_single_connected_emulator_is_accepted(self):
        self.assertEqual("emulator-5554", control.emulator_serial("List of devices attached\r\nemulator-5554\tdevice\r\n"))
        for text in ["", "List of devices attached\n", "List of devices attached\nphysical-serial device\n",
                     "List of devices attached\nemulator-5554 offline\n",
                     "List of devices attached\nemulator-5554 device\nemulator-5556 device\n",
                     "List of devices attached\nemulator-5554 device\nphysical device\n",
                     "unexpected header\nemulator-5554 device\n"]:
            with self.subTest(text=text), self.assertRaises(control.ControlError):
                control.emulator_serial(text)

    def test_runner_is_bound_to_exact_test_and_app_packages(self):
        row = "instrumentation:" + control.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner (target=" + control.PACKAGE + ")\n"
        self.assertEqual(control.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", control.instrumentation_runner(row))
        for text in ["", row + row, row.replace("target=" + control.PACKAGE, "target=com.example.chatbar"),
                     row.replace(control.PACKAGE + ".test/", "other.test/"),
                     row.replace("AndroidJUnitRunner", "OtherRunner"),
                     row.replace("AndroidJUnitRunner", "AndroidJUnitRunner;unexpected"),
                     row.replace("target=" + control.PACKAGE, "target=" + control.PACKAGE + "other")]:
            with self.subTest(text=text), self.assertRaises(control.ControlError):
                control.instrumentation_runner(text)

    def test_runtime_environment_is_cloud_linux_only(self):
        control.cloud_environment({"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux"})
        for environment in [{}, {"GITHUB_ACTIONS": "true"}, {"GITHUB_ACTIONS": "True", "RUNNER_OS": "Linux"},
                            {"GITHUB_ACTIONS": "false", "RUNNER_OS": "Linux"},
                            {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Windows"}]:
            with self.subTest(environment=environment), self.assertRaises(control.ControlError):
                control.cloud_environment(environment)


if __name__ == "__main__":
    unittest.main()
