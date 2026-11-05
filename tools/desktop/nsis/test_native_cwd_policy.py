import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from native_cwd_policy import owned_update_helper_cwd


class NativeCwdPolicyTest(unittest.TestCase):
    def test_only_inventoried_updater_image_and_exact_runtime_directory_are_admitted(self):
        with tempfile.TemporaryDirectory(prefix='ccb-native-cwd-fixture-') as temporary:
            program = Path(temporary) / 'program'
            runtime = program / 'app/desktop-updater'
            runtime.mkdir(parents=True)
            executable = runtime / 'python.exe'
            data = b'SYNTHETIC_NOT_EXECUTABLE'
            executable.write_bytes(data)
            (program / 'install-manifest.json').write_text(json.dumps(dict(files=[dict(
                path='app/desktop-updater/python.exe', bytes=len(data), sha256=hashlib.sha256(data).hexdigest())])))
            self.assertTrue(owned_update_helper_cwd(program, executable, runtime))
            self.assertFalse(owned_update_helper_cwd(program, executable, program))
            other = program / 'other.exe'
            other.write_bytes(data)
            self.assertFalse(owned_update_helper_cwd(program, other, runtime))
            executable.write_bytes(b'X' + data[1:])
            self.assertFalse(owned_update_helper_cwd(program, executable, runtime))


if __name__ == '__main__':
    unittest.main(verbosity=2)
