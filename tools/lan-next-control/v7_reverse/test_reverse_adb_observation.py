"""Pure ADB reply fixtures and mocked real-driver first-failure preservation."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import adb_observation as evidence
import v7_reverse_android_control as control


class AdbEvidenceTests(unittest.TestCase):
    def stat_missing(self,name='ready.json'):
        return ("stat: 'files/v7-reverse-lab/"+name+"': No such file or directory\n").encode()

    def test_exact_api26_stat_missing(self):
        for operation,name in (('PRIVATE_READY_STAT','ready.json'),('PRIVATE_PROGRESS_STAT','progress.json')):
            expected=self.stat_missing(name)
            for stderr in (expected,expected[:-1]+b'\r\n'):
                with self.subTest(operation=operation,stderrLength=len(stderr)):
                    observed=evidence.observation(operation,1,b'',stderr)
                    self.assertIsNone(evidence.interpret(observed,b'',missing=True))
                    self.assertEqual(observed['disposition'],'EXPECTED_MISSING')

    def test_wrong_file_or_package_enoent_is_fatal(self):
        for stderr in (self.stat_missing('progress.json'),b'run-as: No such file or directory\n',
                       b'Permission denied\n',self.stat_missing()+b'other\n'):
            observed=evidence.observation('PRIVATE_READY_STAT',1,b'',stderr)
            with self.assertRaises(evidence.AdbFailure):evidence.interpret(observed,b'',missing=True)

    def test_stat_stdout_or_wrong_exit_not_missing(self):
        for code,stdout in ((0,b''),(2,b''),(1,b'error')):
            observed=evidence.observation('PRIVATE_READY_STAT',code,stdout,self.stat_missing())
            self.assertEqual(observed['disposition'],'REFUSED')

    def test_cat_after_successful_stat_cannot_be_retried_as_missing(self):
        observed=evidence.observation('PRIVATE_READY_CAT',1,b'',self.stat_missing())
        with self.assertRaises(evidence.AdbFailure):evidence.interpret(observed,b'',missing=True)

    def test_success_and_nonempty_stderr_stay_distinct(self):
        self.assertEqual(evidence.interpret(evidence.observation('PRIVATE_READY_CAT',0,b'{}',b''),b'{}'),b'{}')
        with self.assertRaises(evidence.AdbFailure):
            evidence.interpret(evidence.observation('PRIVATE_READY_CAT',0,b'{}',b'warning'),b'{}')

    def test_counts_timeout_and_markers_do_not_emit_content(self):
        marker=b'NEVER_PUBLISH_SYNTHETIC_PRIVATE_PATH_OR_CONTENT'
        observed=evidence.observation('PRIVATE_READY_STAT',100000,marker,marker)
        self.assertNotIn(marker.decode(),json.dumps(observed))
        self.assertTrue(observed['exitCodeCapped'])
        timed=evidence.observation('PRIVATE_READY_STAT',None,b'',b'',transport='TIMEOUT')
        self.assertEqual(timed['errorClass'],'TIMEOUT');self.assertIsNone(timed['exitCode'])
        with self.assertRaises(ValueError):evidence.observation('raw command',0,b'',b'')

    def test_output_cap_is_fatal(self):
        value=evidence.observation('PRIVATE_READY_CAT',0,b'x'*65537,b'')
        self.assertTrue(value['stdoutCapped']);self.assertEqual(value['errorClass'],'OUTPUT_LIMIT')

    def test_native_snapshot_is_never_a_pass(self):
        raw=(b'INSTRUMENTATION_STATUS: class=Known.Class\r\nINSTRUMENTATION_STATUS: test=knownMethod\r\n'
             b'INSTRUMENTATION_STATUS_CODE: 1\r\njava.lang.VerifyError: PRIVATE_UNKNOWN_BODY\nINSTRUMENTATION_CODE: -1\n')
        value=evidence.instrumentation_summary(raw,b'',False,'Known.Class','knownMethod',{'startup'})
        self.assertTrue(value['expectedClassObserved']);self.assertTrue(value['expectedMethodObserved'])
        self.assertTrue(value['testStartCodeObserved']);self.assertEqual(value['exceptionMarker'],'VERIFY_ERROR')
        self.assertFalse(value['snapshotComplete']);self.assertFalse(value['passClaimed'])
        self.assertNotIn('PRIVATE_UNKNOWN_BODY',json.dumps(value))

    def test_unavailable_or_unknown_native_does_not_infer_failure_cause(self):
        value=evidence.instrumentation_summary(b'',b'',False,'Known.Class','knownMethod',{'startup'})
        self.assertEqual(value['exceptionMarker'],'UNKNOWN');self.assertIsNone(value['methodFailurePhase'])
        value=evidence.instrumentation_summary(b'x'*131073,b'',False,'Known.Class','knownMethod',{'startup'})
        self.assertEqual(value['availability'],'SIZE_LIMIT');self.assertIsNone(value['stdoutSha256'])

    def run_mock_driver(self,fail_cat):
        here=Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(prefix='pure-adb-',dir=here) as temporary:
            runner=Path(temporary).resolve();self.assertEqual(runner.parent,here)
            lab=runner/'ccb-v7-live';lab.mkdir();out=runner/'small';out.mkdir();result=out/'android.json'
            tools=here.parent
            operations=[]
            def fake_run(argv,**kwargs):
                args=argv[3:];operations.append(args)
                stdout=b'';stderr=b'';code=0
                if args==['shell','getprop','ro.kernel.qemu']:stdout=b'1\n'
                elif args==['shell','getprop','ro.build.version.sdk']:stdout=b'26\n'
                elif args[:3]==['shell','dumpsys','package']:stdout=b'versionCode=87 DEBUGGABLE\n'
                elif args[:3]==['shell','pm','path']:stdout=b'package:/data/app/synthetic/base.apk\n'
                elif args[:3]==['shell','toybox','sha256sum']:stdout=b'a'*64+b'  synthetic\n'
                elif args==['features']:stdout=b'cmd\r\nshell_v2\r\n'
                elif args[-1:] == ['pwd']:stdout=('/data/user/0/'+control.PACKAGE+'\n').encode()
                elif args[-1:] == [control.APP_LAB+'progress.json']:code=1;stderr=self.stat_missing('progress.json')
                elif args[-1:] == [control.APP_LAB+'ready.json']:
                    if 'stat' in args and fail_cat:stdout=b'36\n'
                    else:code=1;stderr=b'PRIVATE_FATAL_BODY Permission denied\n'
                elif 'rm' not in args:raise AssertionError('UNEXPECTED_MOCK_OPERATION')
                return types.SimpleNamespace(returncode=code,stdout=stdout,stderr=stderr)
            class FakeProcess:
                returncode=None
                def poll(self):return self.returncode
                def terminate(self):self.returncode=-15
                def kill(self):self.returncode=-9
                def wait(self,timeout=None):return self.returncode
            def fake_start(*args,**kwargs):
                kwargs['stdout'].write(b'java.lang.VerifyError: PRIVATE_NATIVE_BODY\n');kwargs['stdout'].flush()
                return FakeProcess()
            argv=['control','--adb',str(runner/'not-executed-adb'),'--control-tools',str(tools),
                '--serial','emulator-5554','--candidate-sha256','a'*64,'--test-sha256','a'*64,
                '--private-root',str(lab),'--result',str(result)]
            output=io.StringIO()
            with patch.object(sys,'argv',argv),patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_OS':'Windows','RUNNER_TEMP':str(runner)}),\
                 patch.object(control.subprocess,'run',fake_run),patch.object(control.subprocess,'Popen',fake_start),\
                 patch.dict(sys.modules,{'instrumentation_report':types.SimpleNamespace()}),\
                 contextlib.redirect_stdout(output):
                self.assertEqual(control.main(),2)
            value=json.loads(result.read_bytes())
            self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_READY_CAT' if fail_cat else 'PRIVATE_READY_STAT')
            self.assertEqual(value['lastAdbOperation'],'PRIVATE_PROTOCOL_CLEANUP')
            self.assertTrue(value['privateProtocolCleaned']);self.assertFalse(value['readyObserved'])
            self.assertFalse(value['instrumentationAtFailure']['processExitedBeforeCleanup'])
            self.assertEqual(value['instrumentationAtFailure']['exceptionMarker'],'VERIFY_ERROR')
            self.assertFalse(any(args[:1]==['forward'] for args in operations))
            self.assertNotIn('PRIVATE_FATAL_BODY',output.getvalue());self.assertNotIn('PRIVATE_NATIVE_BODY',output.getvalue())

    def test_real_driver_preserves_stat_failure_before_cleanup(self):self.run_mock_driver(False)
    def test_real_driver_preserves_cat_failure_before_cleanup(self):self.run_mock_driver(True)


if __name__=='__main__':unittest.main()
