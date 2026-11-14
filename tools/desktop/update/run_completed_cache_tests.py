"""Cloud-only original unittest event receipts; no replacement oracles or test filtering."""
import json
import os
from pathlib import Path
import sys
import time
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_client
import test_handoff

class RecordedResult(unittest.TextTestResult):
    def startTest(self, test):
        self.started = time.monotonic()
        super().startTest(test)
    def stopTest(self, test):
        failures = [(str(t), text) for t, text in self.failures if t is test or getattr(t, 'test_case', None) is test]
        errors = [(str(t), text) for t, text in self.errors if t is test or getattr(t, 'test_case', None) is test]
        skips = [(str(t), why) for t, why in self.skipped if t is test]
        events.append(dict(method=test.id(), seconds=time.monotonic()-self.started,
                           failures=failures, errors=errors, skips=skips))
        super().stopTest(test)

events = []
suite = unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromModule(test_client),
                           unittest.defaultTestLoader.loadTestsFromModule(test_handoff)])
result = unittest.TextTestRunner(verbosity=2, resultclass=RecordedResult).run(suite)
output = Path(os.environ['CCB_COMPLETED_CACHE_REPORT'])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(dict(tests=result.testsRun, successful=result.wasSuccessful(),
    failures=[str(x) for x, _ in result.failures], errors=[str(x) for x, _ in result.errors],
    skipped=[str(x) for x, _ in result.skipped], methods=events), indent=2), encoding='utf-8')
raise SystemExit(0 if result.wasSuccessful() else 1)
