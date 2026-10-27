"""Required SummaryInfo output buffers at the RPC boundary; no installer execution.

Direct database handles can tolerate NULL that the real CA RPC server rejects.
The strict named API shim models that documented boundary, not an actual CA run.
"""
import ctypes as C
import json
import os
from pathlib import Path
import unittest
import msi_safety as m

SINK = C.WINFUNCTYPE(None, C.c_wchar_p)


class SummaryOutputContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = Path(os.environ['CCB_OPERATION_MSI'])
        cls.source_sha = m.sha(cls.source)
        assert cls.source_sha == '5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'
        cls.libs = {}
        for kind in ('BASELINE', 'CURRENT'):
            lib = C.WinDLL(os.environ['CCB_HEADER_' + kind + '_HARNESS'])
            lib.HarnessConfigure.argtypes = [C.c_uint] * 4
            lib.HarnessRun.argtypes = [C.c_uint] + [C.c_wchar_p] * 4 + [SINK]
            lib.HarnessRun.restype = C.c_int
            lib.HarnessSummaryContract.argtypes = [C.c_uint] * 3
            lib.HarnessSummaryContract.restype = C.c_uint
            cls.libs[kind] = lib
        cls.observations = []

    @classmethod
    def tearDownClass(cls):
        assert m.sha(cls.source) == cls.source_sha
        (Path(os.environ['CCB_HEADER_EVIDENCE']) / 'SUMMARY_OUTPUT_CONTRACT.json').write_text(
            json.dumps({'sourceUnchanged': True, 'actualInstallerOrCaExecuted': False,
                        'strictRpcContractIsTestShim': True, 'observations': cls.observations}, indent=2) + '\n')

    def header(self, kind, occurrence):
        with m.Database(self.source) as db:
            props = dict(db.rows('Property', 2))
            lines = []
            lib = self.libs[kind]
            lib.HarnessConfigure(7, occurrence, 0, 3)
            answer = lib.HarnessRun(db.handle, *(props[k] for k in
                ('ProductCode', 'ProductName', 'UpgradeCode', 'ProductVersion')), SINK(lines.append))
        self.observations.append({'kind': kind, 'summaryOccurrence': occurrence, 'answer': answer, 'logs': lines})
        return answer, lines

    def test_native_read_with_all_required_buffers(self):
        with m.Database(self.source) as db:
            for property_id in (7, 9):
                self.assertEqual(0, self.libs['CURRENT'].HarnessSummaryContract(db.handle, property_id, 0))

    def test_each_null_output_is_rejected_before_native_forwarding(self):
        with m.Database(self.source) as db:
            for property_id in (7, 9):
                for mask in (1, 2, 4, 8, 16):
                    with self.subTest(property_id=property_id, omitted_output=mask):
                        self.assertEqual(1780, self.libs['CURRENT'].HarnessSummaryContract(db.handle, property_id, mask))

    def test_python_package_reader_supplies_all_getter_outputs(self):
        with m.Database(self.source) as db:
            native = db.lib.MsiSummaryInfoGetPropertyW
            calls = []
            def strict(*args):
                self.assertTrue(all(args[i] is not None for i in range(2, 7)))
                native.argtypes = strict.argtypes
                calls.append(args[1])
                return native(*args)
            db.lib.MsiSummaryInfoGetPropertyW = strict
            try:
                code = db.package_code()
                self.assertRegex(code, r'^\{[0-9A-Fa-f-]{36}\}$')
                self.assertEqual([9], calls)
            finally:
                db.lib.MsiSummaryInfoGetPropertyW = native

    def test_fixed_1112_caller_is_rejected_at_template_output(self):
        answer, _ = self.header('BASELINE', 1)
        self.assertEqual(0, answer)

    def test_current_template_uses_full_output_contract(self):
        answer, lines = self.header('CURRENT', 1)
        self.assertEqual(1, answer, lines)
        self.assertEqual([], lines)

    def test_current_package_code_uses_full_output_contract(self):
        answer, lines = self.header('CURRENT', 2)
        self.assertEqual(1, answer, lines)
        self.assertEqual([], lines)


if __name__ == '__main__':
    unittest.main()
