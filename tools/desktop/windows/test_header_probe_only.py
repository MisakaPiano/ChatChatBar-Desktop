"""Direct metadata/native fake-context tests. Never executes a real Installer CA."""
import copy
import ctypes as C
import json
import os
from pathlib import Path
import shutil
import unittest
import msi_safety as m
import build_header_probe_package as p
from build_directory_guard import pe_exports

SINK = C.WINFUNCTYPE(None, C.c_wchar_p)


class HeaderProbeOnly(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.package = Path(os.environ['CCB_HEADER_PROBE_PACKAGE'])
        cls.identity = json.loads(cls.package.with_name('PROBE_IDENTITY.json').read_text(encoding='utf-8'))
        cls.copy = Path(os.environ['CCB_HEADER_PROBE_EVIDENCE']) / 'native-metadata-only-uncommitted.msi'
        shutil.copyfile(cls.package, cls.copy)
        cls.hash = m.sha(cls.copy)
        cls.lib = C.WinDLL(os.environ['CCB_HEADER_PROBE_TEST_DLL'])
        cls.lib.CcbTestHeaderProbe.argtypes = [C.c_uint, C.c_uint, SINK]
        cls.lib.CcbTestHeaderProbe.restype = C.c_uint
        cls.lib.CcbTestHeaderProbeCloses.restype = C.c_uint
        cls.observations = []

    @classmethod
    def tearDownClass(cls):
        assert m.sha(cls.copy) == cls.hash == m.sha(cls.package)
        (cls.copy.parent / 'NATIVE_PROBE_OBSERVATIONS.json').write_text(json.dumps({
            'context': 'Direct database handle plus fake identity/log/close callbacks; not real CA or DPAPI.',
            'cases': cls.observations, 'inputUnchanged': True}, indent=2) + '\n', encoding='utf-8')

    def run_probe(self, sql=(), mode=0):
        lines = []
        sink = SINK(lambda line: lines.append(line))
        with m.Database(self.copy, bool(sql)) as db:
            for query in sql:
                db.sql(query)
            result = self.lib.CcbTestHeaderProbe(db.handle, mode, sink)
        self.assertEqual(1603, result)
        self.assertEqual('CCB_HEADER_PROBE_BEGIN contract=1', lines[0])
        self.assertEqual('CCB_HEADER_PROBE_STOP result=1603', lines[-1])
        self.assertEqual(0 if mode == 1 else 1, self.lib.CcbTestHeaderProbeCloses())
        self.assertEqual(self.hash, m.sha(self.copy))
        self.observations.append({'mode': mode, 'result': result, 'logs': lines})
        return lines

    def test_header_pass_always_fails_and_closes_borrowed_database_once(self):
        self.assertEqual(['CCB_HEADER_PROBE_BEGIN contract=1', 'CCB_HEADER_PROBE_RESULT result=PASS',
                          'CCB_HEADER_PROBE_STOP result=1603'], self.run_probe())

    def test_header_reject_emits_detail_but_still_stops(self):
        lines = self.run_probe(["UPDATE `CcbOperationTable` SET `Digest`='" + '0'*64 + "' WHERE `TableName`='File'"])
        self.assertEqual(1, sum(s.startswith('CCB_HEADER_REJECT ') for s in lines))
        self.assertIn('CCB_HEADER_PROBE_RESULT result=REJECT', lines)

    def test_missing_database_is_precondition_not_header_pass(self):
        self.assertIn('CCB_HEADER_PROBE_PRECONDITION stage=ACTIVE_DATABASE', self.run_probe(mode=1))

    def test_context_failure_is_precondition_not_header_result(self):
        self.assertIn('CCB_HEADER_PROBE_PRECONDITION stage=EFFECTIVE_USER_CONTEXT', self.run_probe(mode=2))

    def test_unmarked_package_never_runs_header(self):
        self.assertIn('CCB_HEADER_PROBE_PRECONDITION stage=PROBE_MARKER',
                      self.run_probe(["DELETE FROM `Property` WHERE `Property`='CCB_HEADER_PROBE_ONLY'"]))

    def test_invalid_product_identity_is_precondition(self):
        self.assertIn('CCB_HEADER_PROBE_PRECONDITION stage=AUTHORED_PRODUCT_HEADER',
                      self.run_probe(["UPDATE `Property` SET `Value`='PrivateSentinel' WHERE `Property`='ProductName'"]))

    def test_repeat_after_failure_has_fresh_trace_and_context(self):
        self.run_probe(mode=2)
        self.assertIn('CCB_HEADER_PROBE_RESULT result=PASS', self.run_probe())

    def test_private_values_not_in_diagnostics(self):
        lines = self.run_probe(["UPDATE `CcbOperationStream` SET `Digest`='" + 'f'*64 + "' WHERE `TableName`='Binary'"])
        text = '\n'.join(lines)
        for private in (str(self.package), self.identity['productCode'], self.identity['packageCode'], 'f'*64):
            self.assertNotIn(private, text)

    def test_static_author_and_exact_seals_accept_only_probe(self):
        with m.Database(self.package) as db:
            tables = p.validate(db, self.identity['dll'])
            self.assertEqual(216, len(tables['File']))
            self.assertEqual(218, len(tables['Component']))
            self.assertEqual(220, len(tables['Registry']))
            with self.assertRaises(AssertionError):
                m.validate_operation_actions(tables, 'synthetic')

    def test_any_extra_action_or_sequence_is_refused_even_false_condition(self):
        with m.Database(self.package) as db:
            tables = m.operation_tables(db)
        for name in p.SEQUENCES:
            for action in ('CostInitialize', 'InstallInitialize', 'InstallFiles', 'WriteRegistryValues',
                           'RemoveExistingProducts', 'RegisterProduct', 'InstallFinalize'):
                trial = copy.deepcopy(tables)
                trial[name].append([action, '0', '1500'])
                with self.assertRaises(AssertionError):
                    p.validate_actions(trial)

    def test_async_continue_on_error_nonimpersonated_and_missing_insurance_refused(self):
        with m.Database(self.package) as db:
            tables = m.operation_tables(db)
        for action_type in ('65', '129', '2049', '3073', '1025', '51'):
            trial = copy.deepcopy(tables)
            next(r for r in trial['CustomAction'] if r[0] == 'CcbHeaderDiagnosticOnly')[1] = action_type
            with self.assertRaises(AssertionError): p.validate_actions(trial)
        for name in p.SEQUENCES:
            trial = copy.deepcopy(tables);trial[name].pop()
            with self.assertRaises(AssertionError): p.validate_actions(trial)

    def test_zero_is_preserved_as_zero_not_null_in_author_wire(self):
        self.assertEqual("'0'", p.quoted(0));self.assertEqual('NULL', p.quoted(''))

    def test_distributed_binary_has_only_probe_export(self):
        self.assertEqual({'CcbHeaderDiagnosticOnly'}, pe_exports(Path(os.environ['CCB_HEADER_PROBE_NATIVE_DLL'])))

    def test_probe_binary_has_no_install_mutation_or_inventory_imports(self):
        data = Path(os.environ['CCB_HEADER_PROBE_NATIVE_DLL']).read_bytes()
        for name in (b'MsiSetProperty', b'MsiSetTargetPath', b'MsiInstallProduct', b'MsiConfigureProduct',
                     b'MsiDoAction', b'MsiEnumClientsEx', b'MsiEnumComponentsEx', b'RegSetValue',
                     b'RegCreateKey', b'DeleteFile', b'RemoveDirectory', b'MoveFile'):
            self.assertNotIn(name, data)

    def test_normal_binary_still_has_only_the_two_production_entries(self):
        self.assertEqual({'CcbResolveInstallDirectory', 'CcbValidateOperation'},
                         pe_exports(Path(os.environ['CCB_GUARD_PRODUCTION_DLL'])))

    def test_header_and_normal_author_policy_source_are_unchanged(self):
        import subprocess
        root = Path(__file__).resolve().parents[3]
        for name in ('operation_guard.h', 'msi_safety.py'):
            old = subprocess.check_output(['git', 'show', '9f37cc0698672d2d56fea8efaf8fa822f01961e7:tools/desktop/windows/' + name], cwd=root)
            self.assertEqual(old.replace(b'\r\n', b'\n'), Path(__file__).with_name(name).read_bytes().replace(b'\r\n', b'\n'))

    def test_source_1112_and_payload_container_unchanged(self):
        source = Path(os.environ['CCB_OPERATION_MSI'])
        self.assertEqual(p.SOURCE_SHA, m.sha(source))
        with m.Database(source) as before, m.Database(self.package) as after:
            self.assertEqual(before.cabinets(), after.cabinets())
            for table in ('File', 'Component', 'Registry', 'Directory', 'Feature', 'FeatureComponents', 'Upgrade', 'AppSearch', 'RegLocator'):
                self.assertEqual(before.rows(table, m.OP_COLUMNS[table]), after.rows(table, m.OP_COLUMNS[table]))
            self.assertNotEqual(before.package_code(), after.package_code())

    def test_source_entry_contains_no_root_or_execution_call(self):
        text = Path(__file__).with_name('header_probe_only.h').read_text(encoding='utf-8')
        body = text.split('static UINT header_probe_run', 1)[1].split('__declspec(dllexport)', 1)[0]
        for forbidden in ('operation_run(', 'real_set(', 'family(', 'cache_product(', 'runtime_targets(',
                          'MsiSetProperty', 'MsiInstallProduct', 'MsiDoAction', 'MsiConfigureProduct', 'CreateProcess', 'DeleteFile'):
            self.assertNotIn(forbidden, body)
        self.assertIn('return ERROR_INSTALL_FAILURE;', body)


if __name__ == '__main__':
    unittest.main()
