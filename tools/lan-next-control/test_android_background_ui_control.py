import json
from pathlib import Path
import unittest
from unittest.mock import patch

import android_background_ui_control as background
import group_ui_control as control


class BackgroundProfileTests(unittest.TestCase):
    def test_only_trusted_api35_profile_is_selected_and_original_is_restored(self):
        original = (control.CLASS, control.METHOD, control.FIXTURE, control.API,
                    control.TARGET, control.INSTRUMENTATION_ARGUMENT, control.PROOF_SCOPE)

        def inspect(argv):
            self.assertEqual(argv, ["request", "--request", "synthetic.json"])
            self.assertEqual(control.API, 35)
            self.assertEqual(control.CLASS, "com.example.chatbar.lanSync.LanSyncGroupBackgroundUiControlTest")
            self.assertEqual(control.METHOD, "backgroundServicePauseSurvivesForegroundAndResumesExplicitly")
            self.assertEqual(control.TARGET.rsplit("/", 1)[1], control.FIXTURE.name)
            self.assertEqual(control.INSTRUMENTATION_ARGUMENT, "lanSyncGroupBackgroundFixture")
            self.assertIn("not OS death/Doze", control.PROOF_SCOPE)
            return 7

        with patch.object(control, "main", inspect):
            self.assertEqual(background.main(["request", "--request", "synthetic.json"]), 7)
        self.assertEqual((control.CLASS, control.METHOD, control.FIXTURE, control.API,
                          control.TARGET, control.INSTRUMENTATION_ARGUMENT, control.PROOF_SCOPE), original)

    def test_profile_is_restored_after_refusal(self):
        original = control.API, control.FIXTURE
        with patch.object(control, "main", side_effect=control.ControlError("synthetic refusal")):
            with self.assertRaises(control.ControlError):
                background.main([])
        self.assertEqual((control.API, control.FIXTURE), original)

    def test_old_preparation_cannot_start_a_different_api_emulator(self):
        request = {"sourceSha": "a" * 40, "fixtureSha256": "b" * 64}
        prepared = dict(request, api=26)
        with patch.object(control, "cloud_environment"), \
                patch.object(control, "read_request", return_value=request), \
                patch.object(Path, "read_text", return_value=json.dumps(prepared)), \
                patch.object(control, "API", 35), patch.object(control, "command") as command:
            with self.assertRaisesRegex(control.ControlError, "PREPARATION_PROFILE_CHANGED"):
                control.run(Path("request.json"), Path("source"), Path("evidence"))
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
