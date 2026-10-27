"""Old/new read-only native database predicates, with test-only API result shims."""
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import shutil
import unittest
import msi_safety as m

SINK=C.WINFUNCTYPE(None,C.c_wchar_p)


class HeaderDiagnosticEquivalence(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root=Path(os.environ['CCB_HEADER_EVIDENCE']);cls.root=root
        cls.source=Path(os.environ['CCB_OPERATION_MSI']);cls.source_hash=m.sha(cls.source)
        assert cls.source_hash=='5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'
        cls.copy=root/'uncommitted-mutations.msi';shutil.copyfile(cls.source,cls.copy)
        cls.libs=[]
        for kind in ('BASELINE','CURRENT'):
            lib=C.WinDLL(os.environ['CCB_HEADER_'+kind+'_HARNESS'])
            lib.HarnessRun.argtypes=[C.c_uint,C.c_wchar_p,C.c_wchar_p,C.c_wchar_p,C.c_wchar_p,SINK];lib.HarnessRun.restype=C.c_int
            lib.HarnessConfigure.argtypes=[C.c_uint]*4;lib.HarnessTrace.argtypes=[C.c_uint];lib.HarnessTrace.restype=C.c_uint
            cls.libs.append(lib)
        assert cls.libs[0]._handle!=cls.libs[1]._handle
        with m.Database(cls.copy) as db:
            props=dict(db.rows('Property',2));cls.args=[props[k] for k in ('ProductCode','ProductName','UpgradeCode','ProductVersion')]
            cls.components=db.rows('Component',6)
        cls.records=[]

    @classmethod
    def tearDownClass(cls):
        assert m.sha(cls.source)==cls.source_hash and m.sha(cls.copy)==cls.source_hash
        (cls.root/'DIFFERENTIAL_OBSERVATIONS.json').write_text(json.dumps({'cases':cls.records,'sourceAndUncommittedCopyUnchanged':True,
            'nativeContext':'Direct database handles in one Windows Python process; test-only named API wrappers. No installer service, CA server, registration, DPAPI or user root.',
            'actual1112FailureRootCause':'NOT_REPRODUCED'},indent=2)+'\n',encoding='utf-8')

    def compare(self,label,sql=(),fault=(0,0,0,0),expected=0,phase=None,check=None,output=True):
        results=[];logs=[];traces=[]
        with m.Database(self.copy,writable=bool(sql)) as db:
            for statement in sql:db.sql(statement)
            for lib in self.libs:
                lines=[];sink=SINK(lambda line:lines.append(line)) if output else SINK()
                lib.HarnessConfigure(*fault)
                results.append(lib.HarnessRun(db.handle,*self.args,sink))
                size=lib.HarnessTrace(65536);self.assertLess(size,65536)
                traces.append([lib.HarnessTrace(i) for i in range(size)]);logs.append(lines)
        self.assertEqual([expected,expected],results,label)
        self.assertEqual(traces[0],traces[1],label+' API call order/field ordinals changed')
        self.assertEqual([],logs[0])
        if expected or not output:self.assertEqual([],logs[1])
        else:
            self.assertEqual(1,len(logs[1]));self.assertTrue(logs[1][0].startswith('CCB_HEADER_REJECT '))
            if phase:self.assertIn('phase='+phase+' ',logs[1][0])
            if check:self.assertIn('check='+check+' ',logs[1][0])
            for secret in [*self.args,'PRIVATE_SYNTHETIC_SENTINEL','C:\\Users\\',str(self.source)]:self.assertNotIn(secret,logs[1][0])
        self.assertEqual(self.source_hash,m.sha(self.copy))
        self.records.append({'label':label,'faultApiOccurrenceValueMode':list(fault),'baseline':results[0],'current':results[1],
            'apiTraceLength':len(traces[0]),'apiTraceSha256':hashlib.sha256(json.dumps(traces[0]).encode()).hexdigest(),
            'apiCallSequenceAndFieldOrdinalsEqual':True,'diagnostics':logs[1]})
        return logs[1],traces[1]

    def test_valid_static_database_remains_allowed_without_diagnostic(self):
        self.compare('valid-static',expected=1)

    def test_property_read_api_errors_preserve_raw_return_and_rejection(self):
        for api in (1,2,3,4):
            lines,_=self.compare('property-api-'+str(api),fault=(api,1,6,0),phase='PRODUCT_CODE_READ')
            self.assertIn('first_code=6 ',lines[0])

    def test_property_value_mismatch_is_distinct_and_redacted(self):
        self.compare('name-mismatch',["UPDATE `Property` SET `Value`='PRIVATE_SYNTHETIC_SENTINEL' WHERE `Property`='ProductName'"],phase='PRODUCT_NAME_MATCH')

    def test_summary_open_errors_are_not_reported_as_empty_or_wrong_architecture(self):
        for occurrence,phase in ((1,'SUMMARY_TEMPLATE'),(2,'SUMMARY_PACKAGE')):
            for status in (6,120,1627):
                lines,_=self.compare('summary-open-'+str(occurrence)+'-'+str(status),fault=(6,occurrence,status,0),phase=phase,check='SUMMARY_OPEN')
                self.assertIn('first_api=MsiGetSummaryInformationW ',lines[0]);self.assertIn('first_code='+str(status)+' ',lines[0])

    def test_summary_property_api_error_keeps_property_id(self):
        for occurrence,field in ((1,7),(2,9)):
            lines,_=self.compare('summary-property-'+str(field),fault=(7,occurrence,1627,0),check='SUMMARY_PROPERTY')
            self.assertIn('first_field='+str(field),lines[0])

    def test_summary_variant_type_remains_strict(self):
        lines,_=self.compare('summary-wrong-type',fault=(7,1,3,1),phase='SUMMARY_TEMPLATE',check='SUMMARY_TYPE')
        self.assertIn('last_kind=2 ',lines[0]);self.assertIn('last_code=3 ',lines[0])

    def test_summary_architecture_and_guid_mismatches_remain_distinct(self):
        self.compare('summary-architecture',fault=(7,1,0,2),phase='SUMMARY_ARCHITECTURE')
        self.compare('summary-package-guid',fault=(7,2,0,2),phase='SUMMARY_PACKAGE_GUID')

    def test_missing_component_marker_still_refuses_with_fetch_end_code(self):
        lines,_=self.compare('component-marker-missing',["DELETE FROM `Property` WHERE `Property`='CCB_COMPONENT_CONTRACT'"],phase='COMPONENTS',check='COMPONENT_MARKER_READ')
        self.assertIn('last_code=259 ',lines[0])

    def test_component_null_integer_and_invalid_guid_still_refuse(self):
        self.compare('component-null-attributes',fault=(10,1,0x80000000,0),phase='COMPONENTS',check='COMPONENT_ATTRIBUTES')
        self.compare('component-invalid-guid',["UPDATE `Component` SET `ComponentId`='NOT_A_GUID' WHERE `Component`='"+self.components[0][0]+"'"],phase='COMPONENTS',check='COMPONENT_GUID_VALUE')

    def test_component_condition_and_marker_conflicts_still_refuse(self):
        self.compare('component-unknown-condition',["UPDATE `Component` SET `Condition`='UNKNOWN_CHOICE' WHERE `Component`='"+self.components[0][0]+"'"],phase='COMPONENTS',check='COMPONENT_CONDITION_VALUE')
        self.compare('component-marker-value',["UPDATE `Registry` SET `Value`='PRIVATE_SYNTHETIC_SENTINEL' WHERE `Name`='Installed'"],phase='COMPONENTS',check='COMPONENT_MARKER_CONTENT')

    def test_registry_parameter_allocation_and_bind_failure_remain_refusals(self):
        lines,_=self.compare('registry-null-record',fault=(11,1,0,0),phase='COMPONENTS',check='REGISTRY_PARAMETER_RECORD')
        self.assertIn('first_kind=4 ',lines[0]);self.assertIn('first_code=0 ',lines[0])
        self.compare('registry-bind-api-error',fault=(5,1,87,0),phase='COMPONENTS',check='REGISTRY_PARAMETER_BIND')

    def test_missing_product_owner_remains_refused(self):
        self.compare('product-owner-missing',["DELETE FROM `Registry` WHERE `Registry`='CcbComponentProductOwnerV1'"],phase='COMPONENTS',check='REGISTRY_FETCH')

    def test_table_contract_marker_and_hash_remain_mandatory(self):
        self.compare('table-marker-missing',["DELETE FROM `Property` WHERE `Property`='CCB_OPERATION_CONTRACT'"],phase='SEALED_TABLES',check='TABLE_CONTRACT_OPEN')
        self.compare('table-hash-mismatch',["UPDATE `CcbOperationTable` SET `Digest`='"+'f'*64+"' WHERE `TableName`='Property'"],phase='SEALED_TABLES',check='TABLE_HASH_MATCH')

    def test_crypto_provider_failure_keeps_ntstatus_and_table_ordinal(self):
        for api in (13,14,15,16):
            lines,_=self.compare('crypto-api-'+str(api),fault=(api,1,0xc00000bb,0),phase='SEALED_TABLES')
            self.assertIn('first_kind=3 ',lines[0]);self.assertIn('first_code=3221225659 ',lines[0]);self.assertIn('group=1 ',lines[0])

    def test_stream_read_failure_and_digest_mismatch_remain_distinct(self):
        lines,_=self.compare('stream-read-api',fault=(8,1,1627,0),phase='SEALED_TABLES',check='STREAM_DATA_READ')
        self.assertIn('group=2 ',lines[0]);self.assertIn('first_api=MsiRecordReadStream ',lines[0])
        self.compare('stream-digest-mismatch',["UPDATE `CcbOperationStream` SET `Digest`='"+'f'*64+"' WHERE `TableName`='Binary'"],phase='SEALED_TABLES',check='STREAM_HASH_MATCH')

    def test_stream_null_length_keeps_signed_integer_semantics(self):
        _,trace=self.compare('trace-for-stream-length',expected=1)
        stream=next(i for i,x in enumerate(trace) if x>>16==8)
        target=next(i for i in range(stream+1,len(trace)) if trace[i]>>16==10)
        occurrence=sum(x>>16==10 for x in trace[:target+1])
        self.compare('stream-null-length',fault=(10,occurrence,0x80000000,0),phase='SEALED_TABLES',check='STREAM_LENGTH_SIGN')

    def test_stream_persistence_query_error_is_not_logged_as_win32_or_absence(self):
        for occurrence in (1,2):
            lines,_=self.compare('stream-persistence-error-'+str(occurrence),fault=(12,occurrence,3,0),phase='SEALED_TABLES',check='STREAM_BACKMAP_COUNT')
            self.assertIn('first_api=MsiDatabaseIsTablePersistentW ',lines[0]);self.assertIn('first_kind=5 ',lines[0]);self.assertIn('first_code=3 ',lines[0])

    def test_no_sink_or_previous_failure_cannot_authorize_or_poison_next_call(self):
        self.compare('no-sink-failure',fault=(6,1,120,0),output=False)
        self.compare('no-sink-success',expected=1,output=False)
        self.compare('after-failure-success',expected=1)


if __name__=='__main__':unittest.main()
