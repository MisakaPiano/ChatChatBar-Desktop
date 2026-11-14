"""Pure Python/FS/Git guards only; fake device transcripts are not native evidence."""
from copy import deepcopy
from dataclasses import FrozenInstanceError, replace
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
CONTROL = Path(os.environ.get('CCB_EXISTING_CONTROL_ROOT', HERE.parents[1]))
sys.path.insert(1, str(CONTROL / 'tools/lan-next-control'))
from candidate85_profile import PROFILE as OLD
from candidate85_group_profile import PROFILE, UI_SCOPE, BUILD_REQUEST_PATH, SIGN_REQUEST_PATH
from candidate85_group_control import GroupEngine
from upgrade_series_engine import LegacyTools, SeriesEngine

BASE_CONTROL = 'b538f48b085193a3c7f938dd4a05c25088697061'
OLD_SCOPE = 'Actual legacy LanSyncActivity launch/recreate and synthetic repository/image decode after upgrade; not group UI, FGS, remote/cloud, OS death or human acceptance.'


def original(path):
    return subprocess.check_output(['git', '-C', str(CONTROL), 'show', BASE_CONTROL + ':' + path]).decode().replace('\r\n', '\n')


def transcript(class_name, method, finish=0):
    def block(code):
        return '\n'.join(('INSTRUMENTATION_STATUS: class=' + class_name,
            'INSTRUMENTATION_STATUS: test=' + method, 'INSTRUMENTATION_STATUS: numtests=1',
            'INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner',
            'INSTRUMENTATION_STATUS_CODE: ' + str(code)))
    return block(1) + '\n' + block(finish) + '\nINSTRUMENTATION_RESULT: stream=\nTime: 0.125\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n'


class GroupControlGuards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tools = LegacyTools(CONTROL)
        cls.engine = GroupEngine(PROFILE, cls.tools, HERE)
        cls.old_engine = SeriesEngine(OLD, cls.tools, CONTROL / 'tools/lan-next-control')

    def temp(self):
        directory = tempfile.TemporaryDirectory(prefix='pure-group85-', dir=HERE)
        self.addCleanup(directory.cleanup)
        return Path(directory.name)

    def test_01_old_profile_defaults_original_fixture_bytes_and_namespaces_unchanged(self):
        self.assertEqual(OLD_SCOPE, OLD.ui_scope)
        self.assertIsNone(OLD.ui_argument)
        self.assertEqual('com.example.chatbar.lanSync.LanSyncUiTest', OLD.ui_class)
        self.assertEqual('syncActivityReopensAndOfflineSampleUsesActualRepositories', OLD.ui_method)
        self.assertEqual('ccb-lan-delivered84-candidate85-inputs', OLD.artifact_name)
        snapshot=(OLD, self.tools.candidate84_profile.BUILD_KIND, deepcopy(self.tools.contract.BASE))
        self.engine.build_request('a'*40)
        self.assertEqual(snapshot,(OLD,self.tools.candidate84_profile.BUILD_KIND,self.tools.contract.BASE))
        for _,name in OLD.fixture_files:
            self.assertEqual(original('tools/lan-next-control/'+name), (CONTROL/'tools/lan-next-control'/name).read_text(encoding='utf-8'))

    def test_02_group_profile_exact_base_four_roles_certificate_and_immutable(self):
        for name in ('base','base_manifest','base_public_control','base_public_path','base_public_bytes','base_public_sha256',
                     'base_public_role','roles','package','certificate','versions','fixture_target','fixture_class','argument','proof_root','tamper_field','api'):
            self.assertEqual(getattr(OLD,name),getattr(PROFILE,name))
        self.assertEqual('31efb7602f725357b6e1bb430571c1f03279a6df', PROFILE.base_public_control)
        self.assertEqual('94a26127971cd88375b23fdb4981d5e2238fb2253640c62c62a3e70787603572',PROFILE.base['deliveredSha256'])
        self.assertEqual(108959291,PROFILE.base['deliveredBytes'])
        self.assertEqual((84,85),PROFILE.versions)
        self.assertEqual(PROFILE.fixture_class,PROFILE.ui_class)
        self.assertEqual(PROFILE.verify_method,PROFILE.ui_method)
        self.assertEqual(PROFILE.argument,PROFILE.ui_argument)
        self.assertEqual(26,PROFILE.api)
        with self.assertRaises(FrozenInstanceError): PROFILE.name='untrusted'
        with self.assertRaises(TypeError): PROFILE.base['versionCode']=83
        with self.assertRaises(ValueError): replace(PROFILE,roles=tuple(reversed(PROFILE.roles)))

    def test_03_group_build_exact_bindings_and_cross_profile_unknown_field_rejection(self):
        value=self.engine.build_request('a'*40)
        self.assertEqual(value,self.engine.validate_build(value))
        self.assertEqual({name for _,name in PROFILE.fixture_files},{x['path'] for x in value['fixtures'].values()})
        bind=value['trustedGroupControl']
        self.assertEqual((3,2,1,2),(bind['expectedSuccessfulInvocations'],bind['uniqueMethods'],bind['seedExecutions'],bind['verifierExecutions']))
        self.assertEqual(5,len(bind['files']))
        for name in ('upgrade_series_engine.py','candidate85_group_control.py','candidate85_group_profile.py'):
            raw=(HERE/name).read_bytes()
            self.assertEqual({'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()},bind['files'][name])
        for bad in (self.old_engine.build_request('a'*40),dict(value,profile='legacy'),dict(value,api=35)):
            with self.assertRaises(ValueError):self.engine.validate_build(bad)
        with self.assertRaises(ValueError): self.old_engine.validate_build(value)
        bad=deepcopy(value);bad['trustedGroupControl']['files']['upgrade_series_engine.py']['sha256']='0'*64
        with self.assertRaises(ValueError): self.engine.validate_build(bad)
        bad=deepcopy(value);bad['fixtures']['seed84']['path']='../other.kt'
        with self.assertRaises(ValueError): self.engine.validate_build(bad)

    def test_04_artifact_kind_names_refuse_old_group_mix(self):
        value={'id':1,'runId':2,'name':PROFILE.artifact_name,'bytes':3,'outerSha256':'a'*64,'controlSha':'b'*40}
        self.engine.artifact_metadata(value)
        self.assertNotEqual(OLD.artifact_name,PROFILE.artifact_name)
        with self.assertRaises(ValueError):self.engine.artifact_metadata(dict(value,name=OLD.artifact_name))
        with self.assertRaises(ValueError):self.old_engine.artifact_metadata(value)
        for key in OLD.kinds:self.assertNotEqual(OLD.kinds[key],PROFILE.kinds[key])
        with self.assertRaisesRegex(ValueError,'SIGN_KIND'):
            self.engine.validate_sign({'kind':OLD.kinds['sign'],'format':1,'buildRequest':{},'originalArtifact':{},'rawManifest':{},'entries':{}})

    def test_05_exact_original84_public_block_retained_without_signing(self):
        block=self.engine.base_public_block()
        self.assertEqual(block,self.old_engine.base_public_block())
        self.assertEqual(PROFILE.base['rawSha256'],block['sourceSha256'])
        self.assertEqual(PROFILE.base['deliveredSha256'],block['outputSha256'])
        with self.assertRaises(ValueError):self.engine.block(dict(block,bytes=block['bytes']+1),PROFILE.base['rawSha256'],True)

    def test_06_common_engine_is_only_the_three_authorized_additive_changes(self):
        before=original('tools/lan-next-control/upgrade_series_engine.py')
        expected=before.replace('    api: int = 26\n','    api: int = 26\n    ui_scope: str = '+repr(OLD_SCOPE)+'\n    ui_argument: str | None = None\n')
        expected=expected.replace("'scope': "+repr(OLD_SCOPE),"'scope': p.ui_scope")
        expected=expected.replace("device.instrument(p.ui_class, p.ui_method, 'ui-smoke-instrumentation')","device.instrument(p.ui_class, p.ui_method, 'ui-smoke-instrumentation', p.ui_argument)")
        after=(HERE/'upgrade_series_engine.py').read_text(encoding='utf-8')
        self.assertEqual(expected,after)
        def methods(text):
            return {n.name:ast.dump(n,include_attributes=False) for n in ast.walk(ast.parse(text)) if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef))}
        old,new=methods(before),methods(after)
        self.assertEqual(old.keys(),new.keys())
        for name in old:
            if name!='ui':self.assertEqual(old[name],new[name],name)
        self.assertEqual({'build_request'}, {k for k,v in GroupEngine.__dict__.items() if callable(v)})

    def test_07_workflows_exact_hash_count_transform_and_sdk_install_guard_unchanged(self):
        transform=json.loads((HERE/'candidate85_group_workflow_transform_bindings.json').read_bytes())
        for row in transform['workflows']:
            raw=subprocess.check_output(['git','-C',str(CONTROL),'show',BASE_CONTROL+':'+row['originalPath']])
            self.assertEqual(row['originalSha256'],hashlib.sha256(raw).hexdigest())
            text=raw.decode().replace('\r\n','\n')
            self.assertEqual(text,(CONTROL/row['originalPath']).read_text(encoding='utf-8'))
            for change in row['replacements']:
                self.assertEqual(change['count'],text.count(change['old']))
                text=text.replace(change['old'],change['new'])
            actual=(CONTROL/row['newPath']).read_text(encoding='utf-8')
            self.assertEqual(text,actual)
            self.assertEqual(row['newSha256'],hashlib.sha256(actual.encode()).hexdigest())
            self.assertNotIn('candidate85_control.py',actual)
            self.assertNotIn('paths: [.github/lan-delivered84-candidate85-build-request.json]',actual)
            self.assertIn('if: always()',actual)
            self.assertNotIn('adb ',actual)
        self.assertNotEqual(BUILD_REQUEST_PATH,SIGN_REQUEST_PATH)
        self.assertEqual([],list(HERE.glob('*request.json')))

    def test_08_group_fixture_bytes_are_exact_prior_frozen_and_legacy16_not_rewritten(self):
        originals=HERE.parent/'candidate85-group-upgrade-fixture-draft'
        manifest=json.loads((originals/'FROZEN_CONTRIBUTION.json').read_bytes()) if originals.is_dir() else None
        expected={'candidate85_group_seed84.kt':('aa852fb36d125a630533bbabcab15f4d8fd5eeffaaa9dd02fb47e7657265f5af',16969),
                  'candidate85_group_verify85.kt':('cea706fce45ea24a7ec23411266ed996dbb454fbfc58be8cb00bd39ff483fccd',16724)}
        for _,name in PROFILE.fixture_files:
            raw=(HERE/name).read_bytes();self.assertEqual(expected[name],(hashlib.sha256(raw).hexdigest(),len(raw)))
        seed=(HERE/PROFILE.fixture_files[0][1]).read_text(encoding='utf-8')
        verify=(HERE/PROFILE.fixture_files[1][1]).read_text(encoding='utf-8')
        self.assertIn('owner.pauseBackground()',seed);self.assertNotIn('pauseBackground().join()',seed)
        self.assertIn('ActivityScenario.launch(LanSyncActivity::class.java)',verify)
        self.assertNotIn('owner.resumeBackground()',verify)
        self.assertIn('assertTrue(ready.paused)',verify)

    def ui_with_recorded_device(self,profile,engine_type,failed=False):
        root=self.temp();real=self.tools;calls=[]
        fake=SimpleNamespace(**vars(real))
        fake.candidate84_upgrade=SimpleNamespace(SAMPLE_PATHS=(),require_sample_absent=real.candidate84_upgrade.require_sample_absent)
        engine=engine_type(profile,fake,HERE if engine_type is GroupEngine else CONTROL/'tools/lan-next-control')
        engine.native_control=lambda:'b'*40
        build=engine.build_request('a'*40)
        verification={'buildRequest':build,'originalArtifact':{'synthetic':True},'entries':{'synthetic':True}}
        engine.verified_inputs=lambda directory,control:verification
        text='synthetic public proof only';after={'versionCode':85,'uid':12,'firstInstallTime':'same','dataDir':'/synthetic'}
        stages=[real.instrumentation_report.execution(transcript(profile.fixture_class,m),profile.fixture_class,m) for m in (profile.seed_method,profile.verify_method)]
        proof=dict(kind=profile.kinds['upgrade'],passed=True,api=26,versions=[84,85],verificationControlSha='b'*40,upgradeControlSha='b'*40,
            buildRequest=build,stages=stages,fixtureStagesPassed=2,executedTests=2,skippedTests=0,
            installationBefore=dict(after,versionCode=84),installationAfter=after,originalArtifact=verification['originalArtifact'],
            entries=verification['entries'],seedProofSha256=hashlib.sha256(text.encode()).hexdigest())
        real.contract.write_new(root/'upgrade-proof.json',proof)
        # Synthetic prior phase originals are required even for an inert device adapter.
        for role,method in ((profile.seed_role,profile.seed_method),(profile.verify_role,profile.verify_method)):
            (root/(role+'-instrumentation.txt')).write_text(transcript(profile.fixture_class,method),encoding='utf-8')
        device=SimpleNamespace(select=lambda mode:calls.append(('select',mode)),state=lambda name:dict(after),proof=lambda name:text)
        def command(argv,name):
            calls.append((argv,name))
            self.assertEqual('am',argv[1]);self.assertEqual('instrument',argv[2])
            return transcript(profile.ui_class,profile.ui_method,-3 if failed else 0),b'',0
        device.command=command
        device.instrument=lambda *args:real.candidate84_upgrade.Device.instrument(device,*args)
        engine.device=lambda directory:device
        if failed:
            with self.assertRaises(ValueError):engine.ui(root)
        else:engine.ui(root)
        return json.loads((root/'ui-smoke-result.json').read_bytes()),calls

    def test_09_group_ui_reuses_actual_instrument_builder_parser_and_required_optin(self):
        result,calls=self.ui_with_recorded_device(PROFILE,GroupEngine)
        command=calls[1][0]
        self.assertIn(PROFILE.argument,command)
        self.assertEqual(['-e',PROFILE.argument,'true'],command[5:8])
        self.assertIn(PROFILE.fixture_class+'#'+PROFILE.verify_method,command)
        self.assertEqual(UI_SCOPE,result['scope']);self.assertTrue(result['passed'])
        self.assertEqual(2,result['priorUpgradeTestsPassed']);self.assertEqual(1,result['execution']['passed'])

    def test_10_legacy_ui_default_still_no_upgrade_optin_and_original_scope(self):
        result,calls=self.ui_with_recorded_device(OLD,SeriesEngine)
        command=calls[1][0]
        self.assertNotIn(OLD.argument,command)
        self.assertIn(OLD.ui_class+'#'+OLD.ui_method,command)
        self.assertEqual(OLD_SCOPE,result['scope']);self.assertTrue(result['passed'])

    def test_11_group_ui_bad_original_status_never_reports_success(self):
        result,_=self.ui_with_recorded_device(PROFILE,GroupEngine,True)
        self.assertFalse(result['passed']);self.assertEqual('actual-live-activity',result['failure']['phase'])
        self.assertNotIn('execution',result)

    def test_12_native_entry_refuses_local_and_install_identity_guards_are_inherited(self):
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(ValueError,'CLOUD_LINUX_ONLY'):self.engine.native_control()
        before={'versionCode':84,'uid':12,'firstInstallTime':'same','dataDir':'/synthetic'}
        after=dict(before,versionCode=85);self.engine.unchanged_install(before,after)
        for field,value in (('uid',13),('firstInstallTime','later'),('dataDir','/other'),('versionCode',84)):
            with self.assertRaises(ValueError):self.engine.unchanged_install(before,dict(after,**{field:value}))
        device=self.engine.device(self.temp())
        for name in ('command','select','install','state','instrument'):
            self.assertIs(getattr(type(device),name),getattr(self.tools.candidate84_upgrade.Device,name))

if __name__=='__main__':unittest.main(verbosity=2)
