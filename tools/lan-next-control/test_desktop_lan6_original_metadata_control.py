"""Pure contracts/FS/mock tests only; miniature ZIPs are never original artifacts."""
from pathlib import Path
from contextlib import contextmanager
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
from types import MappingProxyType
import unittest
from unittest.mock import patch
import zipfile
import desktop_lan6_original_metadata_control as helper

HERE = Path(__file__).resolve().parent
TEMP_ROOT = Path(os.environ.get('CCB_PURE_TEMP_ROOT', str(HERE)))
FIXED_ROOT = r'I:\CCB-LanSigning-Proof-20261113'
FIXED_CONTROL = '368e7d3c7e2ef87f79d6022718cdb5cb81fcebb4'


def hashed(raw): return hashlib.sha256(raw).hexdigest()
def encoded(value): return (json.dumps(value, indent=2) + '\n').encode()


class PreparerPureGuards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='.p-', dir=TEMP_ROOT)
        self.root = Path(self.temp.name)
        self.helpers = self.root / 'h'; self.helpers.mkdir()
        for name, pin in helper.HELPERS.items():
            local = HERE / name
            raw = local.read_bytes() if local.is_file() else subprocess.run(
                ['git', '-C', FIXED_ROOT, 'show', FIXED_CONTROL + ':tools/lan-next-control/' + name],
                check=True, capture_output=True, timeout=30).stdout
            self.assertEqual(pin, hashed(raw)); (self.helpers / name).write_bytes(raw)
        self.profile, self.base = helper.load_profile(self.helpers)
    def tearDown(self):
        self.temp.cleanup()
    def bundle(self, bad_flag=False):
        base = self.base
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('synthetic-only.txt', 'PURE MOCK ONLY; not a runtime or native proof')
        inner = inner.getvalue(); request = base.build_request(helper.CANDIDATE['sourceSha'])
        bindings = {'baseSourceSha':base.BASE['sourceSha'], 'candidateSourceSha':request['sourceSha'],
                    'files':[{'path':p,'baseSha256':'a'*64,'candidateSha256':'a'*64} for p in base.TIMESTAMP_PATHS]}
        receipt = {'version':base.VERSION,'commit':request['sourceSha'],'programFiles':1,
                   'bytes':len(inner),'sha256':hashed(inner),'kind':'INDEPENDENT_ENVIRONMENT_PROFILE_NOT_PORTABLE_FLAG','nativeLifecycle':'NOT_TESTED'}
        audit = {'identity':base.identity(request),'fileCount':1,'files':[{'synthetic':True}]}
        native = {'kind':'CI_INDEPENDENT_ZIP_ACTUAL_LAUNCHER','version':base.VERSION,'source':request['sourceSha'],
                  'programFilesVerified':1, 'passed':not bad_flag,'ownDataAndLockCreated':True,
                  'normalCloseAndAllProgramProcessesExited':True,'outsideBootstrapUnchanged':True}
        contents={'source-request.json':encoded(request),'app-image-audit.json':encoded(audit),
                  base.FILENAME[:-4]+'.json':encoded(receipt),'native-result.json':encoded(native),base.FILENAME:inner}
        desc=lambda n:{'filename':n,'bytes':len(contents[n]),'sha256':hashed(contents[n])}
        manifest={'kind':base.MANIFEST_KIND,'format':1,'sourceSha':request['sourceSha'],'controlSha':helper.CANDIDATE['controlSha'],
                  'buildRequest':request,'timestampSourceBindings':bindings,'archive':desc(base.FILENAME),
                  'files':[desc(n) for n in ['source-request.json','app-image-audit.json',base.FILENAME[:-4]+'.json','native-result.json']],
                  'applicationImageBuiltFromFrozenSource':True,'oldDeliveredPackageRebuilt':False,
                  'profileUpgradeTested':False,'humanAcceptance':False,'userDelivered':False}
        contents['desktop-package-manifest.json']=encoded(manifest)
        outer=io.BytesIO()
        with zipfile.ZipFile(outer,'w',zipfile.ZIP_STORED) as z:
            for name,raw in contents.items(): z.writestr(name,raw)
        outer=outer.getvalue()
        row=dict(helper.CANDIDATE,outerBytes=len(outer),outerSha256=hashed(outer),innerBytes=len(inner),innerSha256=hashed(inner))
        artifact={'id':row['artifactId'],'name':row['artifactName'],'expired':False,'size_in_bytes':len(outer),
                  'digest':'sha256:'+hashed(outer),'workflow_run':{'id':row['runId'],'head_sha':row['controlSha']}}
        run={'id':row['runId'],'head_sha':row['controlSha'],'conclusion':'success','status':'completed','run_attempt':1}
        return outer,row,artifact,run,bindings,contents
    @contextmanager
    def mocked_original(self,bad_flag=False):
        with self.profile.profile():
            outer,row,artifact,run,bindings,contents=self.bundle(bad_flag)
            case=Path(tempfile.mkdtemp(prefix='c',dir=self.root))
            with patch.object(helper,'CANDIDATE',MappingProxyType(row)), patch.object(self.base,'original_stream',lambda _id:io.BytesIO(outer)), patch.object(self.base,'source_bindings',lambda root,source:bindings):
                yield outer,row,artifact,run,bindings,contents,case
    def test_helpers_are_pinned_and_public_namespaces_are_restored(self):
        before={name:sys.modules.get(name) for name in helper.ORDER};run=subprocess.run
        other,b=helper.load_profile(self.helpers)
        self.assertIsNot(b,self.base);self.assertIs(run,subprocess.run)
        for name,value in before.items(): self.assertIs(sys.modules.get(name),value)
        for name in helper.HELPERS:
            p=self.helpers/name;raw=p.read_bytes();p.write_bytes(raw+b'\n# changed\n')
            with self.assertRaises(ValueError): helper.load_profile(self.helpers)
            p.write_bytes(raw)
    def test_fixed_request_has_no_url_or_identity_selection(self):
        path=self.root/'trigger.example';value=helper.expected_request();path.write_bytes(encoded(value))
        self.assertEqual(value,helper.read_request(path))
        for bad in [dict(value,format=True),dict(value,url='https://example.invalid/'),dict(value,candidate=dict(value['candidate'],artifactId=1)),dict(value,candidate=dict(value['candidate'],sourceSha='0'*40))]:
            path.write_bytes(encoded(bad))
            with self.assertRaises(ValueError):helper.read_request(path)
        path.write_bytes(encoded(value).rstrip()[:-1]+b',"format":1}')
        with self.assertRaises(ValueError):helper.read_request(path)
    def test_real_cloud_guard_refuses_locally_before_api_or_fs(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaises(ValueError):helper.cloud_layout(self.base,Path('absent'),Path('absent'),Path('absent'),{})
    def test_profile_defaults_restore_after_exception_and_keep_v5_baseline(self):
        base=self.base;before=(base.BUILD_KIND,base.BASE,base.SERIES,base.UPGRADE_KIND)
        with self.assertRaises(RuntimeError):
            with self.profile.profile():
                self.assertEqual(dict(self.profile.DELIVERED_V5),base.BASE)
                self.assertEqual('lan6',base.SERIES);raise RuntimeError('pure mock only')
        self.assertEqual(before,(base.BUILD_KIND,base.BASE,base.SERIES,base.UPGRADE_KIND))
    def test_actual_existing_zip_bundle_and_upgrade_contract_on_miniature_mock(self):
        with self.mocked_original() as (_,row,artifact,run,bindings,contents,case):
            originals,derived,result=helper.authenticate_candidate(self.base,artifact,run,case,self.root)
            self.assertEqual(row,derived['packages'][1]);self.assertEqual(dict(self.profile.DELIVERED_V5),derived['packages'][0])
            self.assertEqual(bindings,derived['timestampSourceBindings'])
            self.assertTrue(result['fullOuterBytesShaVerified']);self.assertFalse(result['nativeOrUpgradeExecutedByPreparer'])
            self.assertEqual(contents['desktop-package-manifest.json'],originals[helper.OUTPUTS[0]])
            self.assertEqual(contents[self.base.FILENAME[:-4]+'.json'],originals[helper.OUTPUTS[2]])
    def test_exact_metadata_mismatch_refuses_before_stream(self):
        with self.mocked_original() as (_,row,artifact,run,_,_,case):
            for bad in [dict(artifact,id=1),dict(artifact,size_in_bytes=row['outerBytes']+1),dict(artifact,digest='sha256:'+'0'*64),dict(artifact,name='other'),dict(artifact,expired=True)]:
                with patch.object(self.base,'original_stream',side_effect=AssertionError('must not download')),self.assertRaises(ValueError):
                    helper.authenticate_candidate(self.base,bad,run,case,self.root)
    def test_bounded_stream_rejects_extra_or_missing_bytes(self):
        with self.mocked_original() as (raw,row,_,_,_,_,case):
            for number,body in enumerate([raw+b'extra',raw[:-1]]):
                with patch.object(self.base,'original_stream',lambda _id,body=body:io.BytesIO(body)),self.assertRaises(ValueError):
                    helper.bounded_download(self.base,case/('a'+str(number)))
    def test_actual_original_verifier_rejects_corrupt_outer_or_inner_identity(self):
        with self.mocked_original() as (raw,row,_,_,_,_,case):
            for number,changes in enumerate([{'outerSha256':'0'*64},{'innerSha256':'0'*64}]):
                p=case/('a'+str(number)+'.zip');p.write_bytes(raw)
                with self.assertRaises(ValueError):self.base.verify_outer_and_extract(p,case/('b'+str(number)),dict(row,**changes))
    def test_bad_native_original_or_source_binding_cannot_generate_upgrade_request(self):
        with self.mocked_original(bad_flag=True) as (_,_,artifact,run,_,_,case):
            with patch.object(self.base,'upgrade_request',side_effect=AssertionError('must not derive')),self.assertRaises(ValueError):
                helper.authenticate_candidate(self.base,artifact,run,case,self.root)
        # Previous context's case is only test-owned; a fresh child prevents using prior extraction.
        with self.mocked_original() as (_,_,artifact,run,_,_,case):
            with patch.object(self.base,'source_bindings',lambda *a:{}),patch.object(self.base,'upgrade_request',side_effect=AssertionError('must not derive')),self.assertRaises(ValueError):
                helper.authenticate_candidate(self.base,artifact,run,case,self.root)
    def test_only_five_small_outputs_and_original_bytes_are_preserved(self):
        with self.mocked_original() as (_,_,artifact,run,_,contents,case):
            originals,derived,result=helper.authenticate_candidate(self.base,artifact,run,case,self.root)
            with self.assertRaises(ValueError):helper.write_evidence(self.base,self.root/'too-big',originals,derived,dict(result,originalOuterMembers=['x'*65536]),'a'*40)
            self.assertFalse((self.root/'too-big').exists())
            output=self.root/'out';helper.write_evidence(self.base,output,originals,derived,result,'a'*40)
            self.assertEqual(set(helper.OUTPUTS),{p.name for p in output.iterdir()})
            self.assertEqual(contents['source-request.json'],(output/helper.OUTPUTS[1]).read_bytes())
            validation=json.loads((output/'validation.json').read_bytes());self.assertFalse(validation['requestDispatched'])
            self.assertTrue(all(p.stat().st_size<65536 for p in output.iterdir()))
    def test_cleanup_failure_is_retained_but_scope_failure_is_closed(self):
        owned=Path(tempfile.mkdtemp(prefix='ccb-lan6-original-metadata-',dir=self.root)).resolve()
        with patch.object(helper.shutil,'rmtree',side_effect=PermissionError('pure mock only')):
            cleanup=helper.cleanup_private_case(owned,self.root.resolve())
        self.assertEqual('RETAINED_CLEANUP_FAILED',cleanup['status'])
        self.assertEqual('PermissionError',cleanup['exceptionType'])
        self.assertFalse(cleanup['removed']);self.assertTrue(owned.is_dir())
        with patch.object(helper.shutil,'rmtree',side_effect=AssertionError('must not touch wrong scope')),self.assertRaises(ValueError):
            helper.cleanup_private_case(owned,self.root/'other')
        with self.mocked_original() as (_,_,artifact,run,_,_,case):
            originals,derived,result=helper.authenticate_candidate(self.base,artifact,run,case,self.root)
            output=self.root/'retained-out'
            helper.write_evidence(self.base,output,originals,derived,dict(result,privateBundleCleanup=cleanup,privateBundleRemoved=False),'a'*40)
            value=json.loads((output/'validation.json').read_bytes())
            self.assertFalse(value['privateBundleRemoved'])
            self.assertEqual('RETAINED_CLEANUP_FAILED',value['privateBundleCleanup']['status'])
            self.assertEqual(set(helper.OUTPUTS),{p.name for p in output.iterdir()})

    def test_large_or_duplicate_original_json_is_refused(self):
        for raw in [b' '*65536,b'{"x":1,"x":2}',b'\x00{}']:
            with self.assertRaises((ValueError,UnicodeError)):helper.json_bytes(raw)
    def test_thin_wrapper_does_not_implement_zip_or_native_upgrader(self):
        text=(HERE/'desktop_lan6_original_metadata_control.py').read_text(encoding='utf8')
        for value in ['base.original_stream(', 'base.verify_outer_and_extract(', 'base.validate_bundle(', 'base.source_bindings(', 'base.upgrade_request(', 'with profile.profile():']:
            self.assertIn(value,text)
        for denied in ['zipfile.ZipFile(', 'extractall(', 'subprocess.Popen(', 'legacy()', 'native.run(', 'failure.args', 'str(failure)', 'repr(failure)']:
            self.assertNotIn(denied,text)
        self.assertNotIn('java ',text);self.assertEqual(5,len(helper.OUTPUTS))


if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PreparerPureGuards))
    value={'kind':'CCB_LAN6_ORIGINAL_METADATA_PREPARER_PURE_GUARDS','tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'successful':result.wasSuccessful(),'nativeExecuted':0,'realArtifactDownloadBytes':0,'apiOrCiRequestsDispatched':0,'scope':'Pure FS and miniature synthetic ZIP/manifest/contracts/mock; no original artifact or native success claimed.'}
    Path(os.environ.get('CCB_PURE_RESULT',str(HERE/'pure-guard-results.json'))).write_text(json.dumps(value,indent=2)+'\n',encoding='utf8',newline='\n')
    raise SystemExit(0 if result.wasSuccessful() else 1)
