"""Tiny-file fault-authoring tests; no compiler, installer, JVM or native execution."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import ordinary_stable_chain as chain


class OrdinaryStableChainAuthoringTest(unittest.TestCase):
    def test_fault_keeps_ordinary_inputs_and_real_jar_reference_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'ordinary'
            source.mkdir()
            commit = 'a' * 40
            for name in ['ChatChatBar.exe', 'OpenChatBarFile.exe', 'install-manager.exe']:
                (source / name).write_bytes(('synthetic ' + name).encode())
            manifest = dict(version='1.0.1', buildNumber=1236, storageVersion='1.0.1-preview.1236', channel='stable',
                            commit=commit, files=[dict(path='app/ChatChatBarDesktop-1.0.1.jar', sha256='b' * 64, bytes=17)])
            (source / 'install-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            native_path = str(source).replace('/', '\\')
            script = '\n'.join(['OutFile "' + native_path + '\\ChatChatBar-Setup.exe"',
                                'File "' + native_path + '\\install-manifest.json"',
                                'File "image\\app\\ChatChatBarDesktop-1.0.1.jar"',
                                'StrCmp $TargetId "1.0.1-preview.1236-' + commit[:12] + '" okay failed'])
            (source / 'installer.nsi').write_text(script, encoding='utf-8-sig')
            before = {p.name: p.read_bytes() for p in source.iterdir()}
            output = root / 'fault'
            def compile_fixture(command, log):
                self.assertEqual(root / 'synthetic-compiler.exe', command[0])
                self.assertEqual(output / 'installer.nsi', command[1])
                (output / 'ChatChatBar-Setup.exe').write_bytes(b'synthetic compiler output')
            with patch.object(chain, 'run', side_effect=compile_fixture):
                receipt = chain.create_fault(source, output, root / 'synthetic-compiler.exe', commit, root / 'compile.log')
            self.assertEqual(before, {p.name: p.read_bytes() for p in source.iterdir()})
            actual = json.loads((output / 'install-manifest.json').read_text())
            self.assertEqual(dict(manifest, version='1.0.2', buildNumber=1237, storageVersion='1.0.2-preview.1237'), actual)
            self.assertIn('image\\app\\ChatChatBarDesktop-1.0.1.jar', (output / 'installer.nsi').read_text(encoding='utf-8-sig'))
            self.assertFalse(receipt['productionFaultSwitch'])
            self.assertEqual('1.0.1', receipt['ordinaryJarVersion'])

    def test_unexpected_ordinary_identity_never_reaches_compiler(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'ordinary'; source.mkdir()
            for name in ['ChatChatBar.exe', 'OpenChatBarFile.exe', 'install-manager.exe']:
                (source / name).write_bytes(b'synthetic')
            (source / 'install-manifest.json').write_text(json.dumps(dict(version='1.0.0', buildNumber=1235)))
            with patch.object(chain, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'FAULT_SOURCE_IDENTITY'):
                    chain.create_fault(source, root / 'fault', root / 'compiler', 'a' * 40, root / 'log')
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
