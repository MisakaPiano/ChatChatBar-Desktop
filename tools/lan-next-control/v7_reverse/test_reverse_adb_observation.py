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


    def test_ready_retry_exact_predicate_refuses_other_conditions(self):
        value=evidence.observation('PRIVATE_READY_STAT',255,b'',b'')
        self.assertTrue(control.protocol_stat_255_retry_allowed('ready.json','ONE_INSTRUMENTATION',True,True,False,value))
        for name,phase,before,after,used in [('progress.json','ONE_INSTRUMENTATION',True,True,False),
            ('ready.json','PRIVATE_OFFER',True,True,False),('ready.json','ONE_INSTRUMENTATION',False,True,False),
            ('ready.json','ONE_INSTRUMENTATION',True,False,False),('ready.json','ONE_INSTRUMENTATION',True,True,True)]:
            self.assertFalse(control.protocol_stat_255_retry_allowed(name,phase,before,after,used,value))
        for code,out,err in ((255,b'x',b''),(255,b'',b'x'),(1,b'',b'')):
            row=evidence.observation('PRIVATE_READY_STAT',code,out,err)
            self.assertFalse(control.protocol_stat_255_retry_allowed('ready.json','ONE_INSTRUMENTATION',True,True,False,row))
        row=dict(value);row['stdoutCapped']=0
        self.assertFalse(control.protocol_stat_255_retry_allowed('ready.json','ONE_INSTRUMENTATION',True,True,False,row))
        row=evidence.observation('PRIVATE_READY_CAT',255,b'',b'')
        self.assertFalse(control.protocol_stat_255_retry_allowed('ready.json','ONE_INSTRUMENTATION',True,True,False,row))

    def route_progress(self,published=True):
        keys=('initialSessionPresent','initialMessageExact','initialImageMatched','initialMessageCountCapped',
            'durableSessionVerified','appendAttempted','appendCommitted','appendReturned','oracleWritten',
            'groupBusy','groupPaused','groupErrorPresent','diagnosticWriteFailed','listenerObserved','relayBytesBothDirections',
            'sourceSignedVersionObserved','sourceNativeFrontierObserved','sourceLocalReceiptObserved','publicationReadAvailable',
            'initialMessageCount','localReverseCount','relayAccepted','relayConnected')
        value={key:None for key in keys}
        value.update(kind='CCB_V7_ANDROID_REVERSE_PROGRESS',phase='ordinary-repository-append' if published else 'real-production-lan-join',
            sequence=1,firstFailurePhase=None,groupStage='READY' if published else 'JOINING',
            isolatedRouteMode='ADB_FORWARD_TRANSPARENT_TLS',groupBusy=False,groupPaused=False,groupErrorPresent=False,
            diagnosticWriteFailed=False,listenerObserved=published)
        if published:value.update(initialSessionPresent=True,initialMessageExact=True,initialImageMatched=True,
            initialMessageCount=1,initialMessageCountCapped=False)
        value['listenerObservation']=dict(step='PRIVATE_ROUTE_WRITTEN' if published else 'NOT_REACHED',
            firstFailureClass=None,samples=1 if published else 0,backgroundPhase='RUNNING',foregroundProtected=True,
            **{key:True if published else None for key in ('accessAttached','nodeRootRead','portPresent','nearbyReady','socketBoundAndOpen')})
        return value

    def run_stat_retry_driver(self,continuous=False,initially_active=True,exit_during_wait=False,cat_error=False,
                              fault_file='ready.json',consume_ready_budget=False,progress_scenario='PUBLISHED',route_missing=False):
        here=Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory(prefix='pure-ready-',dir=here) as temporary:
            runner=Path(temporary).resolve();self.assertEqual(runner.parent,here)
            lab=runner/'ccb-v7-live';lab.mkdir();out=runner/'small';out.mkdir();result=out/'android.json'
            calls=[];sleep_calls=[];state={'stat':0,'active':initially_active,'ready.json':0,'route.json':0,'now':1000.,
                'ready_delivered':False,'offer_written':False,'progress_reads':0,'published':False,'early_route_reads':0,'route_start':None}
            class FakeProcess:
                def poll(self):return None if state['active'] else 1
                def terminate(self):state['active']=False
                def kill(self):state['active']=False
                def wait(self,timeout=None):return self.poll()
            def fake_run(argv,**kwargs):
                args=argv[3:];calls.append(args);code=0;stdout=b'';stderr=b''
                if args==['shell','getprop','ro.kernel.qemu']:stdout=b'1\n'
                elif args==['shell','getprop','ro.build.version.sdk']:stdout=b'26\n'
                elif args[:3]==['shell','dumpsys','package']:stdout=b'versionCode=87 DEBUGGABLE\n'
                elif args[:3]==['shell','pm','path']:stdout=b'package:/data/app/synthetic/base.apk\n'
                elif args[:3]==['shell','toybox','sha256sum']:stdout=b'a'*64+b'  synthetic\n'
                elif args==['features']:stdout=b'shell_v2\ncmd\n'
                elif args[-1:]==['pwd']:stdout=('/data/user/0/'+control.PACKAGE+'\n').encode()
                elif args[-1:]==[control.APP_LAB+'progress.json']:
                    if fault_file!='route.json' or not state['offer_written']:code=1;stderr=self.stat_missing('progress.json')
                    elif 'stat' in args:
                        if state['route_start'] is None:state['route_start']=state['now']
                        state['progress_reads']+=1
                        if (progress_scenario=='UNAVAILABLE_BEFORE' and state['progress_reads']==1 or
                            progress_scenario=='UNAVAILABLE_AFTER' and state['progress_reads']>1):code=255
                        else:stdout=b'1024\n'
                    else:
                        n=state['progress_reads']
                        published=not (progress_scenario=='LATE_PUBLISHED' and n<3 or
                            progress_scenario=='NEAR_DEADLINE' and n==1 or progress_scenario=='NEVER_PUBLISHED')
                        value=self.route_progress(published)
                        value['sequence']=n
                        if progress_scenario=='INVALID_HEADER':value['kind']='INVALID_NATIVE_PROGRESS_HEADER'
                        elif progress_scenario=='BRANCH_AFTER' and n>1:value=self.route_progress(False);value['sequence']=n
                        elif progress_scenario=='FAILURE_AFTER' and n>1:value['firstFailurePhase']='ordinary-repository-append'
                        if published and progress_scenario!='INVALID_HEADER':state['published']=True
                        stdout=json.dumps(value).encode()

                elif args[-1:] in ([control.APP_LAB+'ready.json'],[control.APP_LAB+'route.json']):
                    name=args[-1].removeprefix(control.APP_LAB)
                    if 'stat' in args:
                        state[name]+=1
                        if name=='route.json' and not state['published']:state['early_route_reads']+=1
                        if name==fault_file:
                            state['stat']+=1
                            if name=='route.json' and route_missing:code=1;stderr=self.stat_missing(name)
                            elif state['stat']==1 or continuous:code=255
                            else:stdout=b'36\n'
                        elif consume_ready_budget and name=='ready.json' and state[name]==1:code=255
                        else:stdout=b'36\n'
                    elif cat_error and name==fault_file:code=255
                    elif name=='ready.json':
                        state['ready_delivered']=True
                        if fault_file=='route.json':state['now']+=1.1
                        stdout=b'{"kind":"CCB_V7_ANDROID_READY","ready":true}'
                        if fault_file=='route.json':
                            (lab/'live-offer.json').write_text(json.dumps(dict(kind='CCB_V7_LIVE_SYNTHETIC_OFFER',
                                code='CCB-GROUP-JOIN1:synthetic',session='ccb-v7-lab-session',message='ccb-v7-lab-message',
                                text='CCB-V7-synthetic-message',imageSha256='b'*64)))
                        else:
                            (lab/'android-stop-requested.json').write_text('{"kind":"CCB_V7_REVERSE_STOP","reason":"PEER_OR_CONTROL_FAILED"}')
                    else:stdout=b'{}' # Deliberately refuse route contents after real driver read; no forward/native claim.
                elif args[:6]==['shell','-T','run-as',control.PACKAGE,'sh','-c']:
                    state['offer_written']=True;state['now']+=1.1
                elif 'rm' not in args:raise AssertionError('UNEXPECTED_MOCK_OPERATION')
                return types.SimpleNamespace(returncode=code,stdout=stdout,stderr=stderr)
            def fake_sleep(seconds):
                sleep_calls.append(seconds)
                if fault_file=='route.json' and state['route_start'] is not None:
                    if progress_scenario in ('INVALID_HEADER','NEVER_PUBLISHED'):state['now']+=180
                    elif progress_scenario=='NEAR_DEADLINE' and state['progress_reads']==1:state['now']+=179.9
                    elif progress_scenario in ('UNAVAILABLE_AFTER','BRANCH_AFTER','FAILURE_AFTER'):state['now']+=1.1
                    else:state['now']+=seconds
                else:state['now']+=seconds
                if exit_during_wait:state['active']=False
            argv=['control','--adb',str(runner/'not-executed-adb'),'--control-tools',str(here.parent),
                '--serial','emulator-5554','--candidate-sha256','a'*64,'--test-sha256','a'*64,
                '--private-root',str(lab),'--result',str(result)]
            with patch.object(sys,'argv',argv),patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_OS':'Windows','RUNNER_TEMP':str(runner)}),\
                 patch.object(control.subprocess,'run',fake_run),patch.object(control.subprocess,'Popen',lambda *a,**k:FakeProcess()),\
                 patch.object(control.time,'sleep',fake_sleep),patch.object(control.time,'monotonic',lambda:state['now']),patch.dict(sys.modules,{'instrumentation_report':types.SimpleNamespace()}),\
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(control.main(),2) # The pure test deliberately stops before any pairing/native claim.
            value=json.loads(result.read_bytes());self.assertFalse(value['oneNativeMethodPassed'])
            self.assertFalse(any(args[:1]==['forward'] for args in calls))
            self.last_driver_state=state;self.last_driver_calls=calls
            return value,state['stat'],sleep_calls

    def test_ready_255_once_can_reach_exact_ready_then_controlled_stop(self):
        value,reads,sleeps=self.run_stat_retry_driver()
        self.assertTrue(value['readyObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['protocolStatRetry']['retryAttempts'],1)
        self.assertEqual(value['protocolStatRetry']['firstObservation']['exitCode'],255)
        self.assertEqual(value['protocolStatRetry']['outcome'],'STAT_SUCCESS')
        self.assertIsNone(value['firstFatalAdb']);self.assertEqual(sleeps,[.3])

    def test_second_255_is_fatal_without_third_stat(self):
        value,reads,_=self.run_stat_retry_driver(continuous=True)
        self.assertFalse(value['readyObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['protocolStatRetry']['outcome'],'RETRY_FAILED')
        self.assertEqual(value['protocolStatRetry']['retryAttempts'],1)
        self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_READY_STAT')
        self.assertEqual(value['protocolStatRetry']['retryObservation']['exitCode'],255)

    def test_inactive_instrumentation_does_not_get_retry(self):
        value,reads,sleeps=self.run_stat_retry_driver(initially_active=False)
        self.assertEqual(reads,1);self.assertEqual(sleeps,[])
        self.assertFalse(value['protocolStatRetry']['budgetUsed']);self.assertFalse(value['readyObserved'])

    def test_exit_before_retry_prevents_second_stat(self):
        value,reads,_=self.run_stat_retry_driver(exit_during_wait=True)
        self.assertEqual(reads,1);self.assertFalse(value['readyObserved'])
        self.assertEqual(value['protocolStatRetry']['retryAttempts'],0)
        self.assertEqual(value['protocolStatRetry']['outcome'],'REFUSED_INACTIVE')

    def test_cat_255_after_retried_stat_stays_fatal(self):
        value,reads,_=self.run_stat_retry_driver(cat_error=True)
        self.assertEqual(reads,2);self.assertFalse(value['readyObserved'])
        self.assertEqual(value['protocolStatRetry']['outcome'],'STAT_SUCCESS')
        self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_READY_CAT')


    def test_route_retry_predicate_only_exact_file_phase_and_operation(self):
        value=evidence.observation('PRIVATE_ROUTE_STAT',255,b'',b'')
        self.assertTrue(control.protocol_stat_255_retry_allowed('route.json','ISOLATED_REVERSE_ROUTE',True,True,False,value))
        for name,phase,before,after,used in [('ready.json','ISOLATED_REVERSE_ROUTE',True,True,False),
            ('route.json','ONE_INSTRUMENTATION',True,True,False),('route.json','ISOLATED_REVERSE_ROUTE',False,True,False),
            ('route.json','ISOLATED_REVERSE_ROUTE',True,False,False),('route.json','ISOLATED_REVERSE_ROUTE',True,True,True),
            ('oracle.json','ISOLATED_REVERSE_ROUTE',True,True,False),('progress.json','ISOLATED_REVERSE_ROUTE',True,True,False)]:
            self.assertFalse(control.protocol_stat_255_retry_allowed(name,phase,before,after,used,value))
        for operation,code,out,err in [('PRIVATE_ROUTE_CAT',255,b'',b''),('PRIVATE_ROUTE_STAT',1,b'',b''),
            ('PRIVATE_ROUTE_STAT',255,b'x',b''),('PRIVATE_ROUTE_STAT',255,b'',b'x')]:
            row=evidence.observation(operation,code,out,err)
            self.assertFalse(control.protocol_stat_255_retry_allowed('route.json','ISOLATED_REVERSE_ROUTE',True,True,False,row))
        for field in ('exitCode','stdoutBytes','stderrBytes'):
            row=dict(value);row[field]=True
            self.assertFalse(control.protocol_stat_255_retry_allowed('route.json','ISOLATED_REVERSE_ROUTE',True,True,False,row))
        for field in ('exitCodeCapped','stdoutCapped','stderrCapped'):
            row=dict(value);row[field]=0
            self.assertFalse(control.protocol_stat_255_retry_allowed('route.json','ISOLATED_REVERSE_ROUTE',True,True,False,row))

    def test_route_255_once_reaches_stat_and_cat_then_refuses_synthetic_route(self):
        value,reads,sleeps=self.run_stat_retry_driver(fault_file='route.json')
        self.assertTrue(value['readyObserved']);self.assertEqual(reads,2)
        retry=value['protocolStatRetry']
        self.assertTrue(retry['budgetUsed']);self.assertEqual(retry['retryAttempts'],1)
        self.assertEqual(retry['firstObservation']['operation'],'PRIVATE_ROUTE_STAT')
        self.assertTrue(retry['firstProcessActive']);self.assertTrue(retry['processActiveForRetry'])
        self.assertEqual(retry['outcome'],'STAT_SUCCESS');self.assertEqual(sleeps,[.3])
        self.assertIsNone(value['firstFatalAdb']);self.assertEqual(value['errorCode'],'PRIVATE_ROUTE_FIELDS')

    def test_route_continuous_255_preserves_fatal_without_third_stat(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',continuous=True)
        self.assertEqual(reads,2);self.assertEqual(value['protocolStatRetry']['retryAttempts'],1)
        self.assertEqual(value['protocolStatRetry']['outcome'],'RETRY_FAILED')
        self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_ROUTE_STAT')
        self.assertEqual(value['protocolStatRetry']['retryObservation']['exitCode'],255)

    def test_ready_and_route_share_one_whole_flow_budget(self):
        value,reads,sleeps=self.run_stat_retry_driver(fault_file='route.json',consume_ready_budget=True)
        self.assertTrue(value['readyObserved']);self.assertEqual(reads,1);self.assertEqual(sleeps,[.3])
        retry=value['protocolStatRetry']
        self.assertEqual(retry['firstObservation']['operation'],'PRIVATE_READY_STAT')
        self.assertEqual(retry['retryAttempts'],1);self.assertEqual(retry['outcome'],'STAT_SUCCESS')
        self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_ROUTE_STAT')

    def test_route_inactive_instrumentation_refuses_before_first_stat(self):
        value,reads,sleeps=self.run_stat_retry_driver(fault_file='route.json',initially_active=False)
        self.assertEqual(reads,0);self.assertEqual(sleeps,[])
        self.assertFalse(value['protocolStatRetry']['budgetUsed'])
        self.assertIsNone(value['firstFatalAdb']);self.assertEqual(value['errorCode'],'INSTRUMENTATION_ENDED_BEFORE_PROTOCOL')

    def test_route_exit_before_retry_prevents_second_stat(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',exit_during_wait=True)
        self.assertEqual(reads,1);self.assertEqual(value['protocolStatRetry']['retryAttempts'],0)
        self.assertEqual(value['protocolStatRetry']['outcome'],'NOT_ATTEMPTED_STOP')
        self.assertFalse(value['routeReadGate']['processActiveForRead'])
        self.assertIsNone(value['firstFatalAdb']);self.assertEqual(value['errorCode'],'INSTRUMENTATION_ENDED_BEFORE_PROTOCOL')

    def test_route_cat_255_is_not_retried(self):
        value,reads,sleeps=self.run_stat_retry_driver(fault_file='route.json',cat_error=True)
        self.assertEqual(reads,2);self.assertEqual(sleeps,[.3])
        self.assertEqual(value['protocolStatRetry']['retryAttempts'],1)
        self.assertEqual(value['protocolStatRetry']['outcome'],'STAT_SUCCESS')
        self.assertEqual(value['firstFatalAdb']['operation'],'PRIVATE_ROUTE_CAT')


    def test_route_marker_requires_exact_header_and_all_completed_witnesses(self):
        value=self.route_progress();self.assertTrue(control.published_route_observed(value))
        for key,item in [('kind','INVALID'),('isolatedRouteMode','INVALID'),('firstFailurePhase','startup'),
            ('listenerObserved',1),('initialMessageExact',None),('initialImageMatched',False)]:
            row=dict(value);row[key]=item
            self.assertFalse(control.published_route_observed(row))
        for key,item in [('step','RELAY_READY'),('firstFailureClass','IO'),('socketBoundAndOpen',False),('portPresent',1)]:
            row=dict(value);row['listenerObservation']=dict(value['listenerObservation']);row['listenerObservation'][key]=item
            self.assertFalse(control.published_route_observed(row))

    def test_route_never_read_during_joining_before_published_signal(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='LATE_PUBLISHED')
        self.assertEqual(self.last_driver_state['early_route_reads'],0)
        self.assertGreaterEqual(self.last_driver_state['progress_reads'],3)
        self.assertTrue(value['routeReadGate']['signalObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['errorCode'],'PRIVATE_ROUTE_FIELDS');self.assertFalse(value['oneNativeMethodPassed'])

    def test_invalid_progress_header_cannot_latch_or_read_route(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='INVALID_HEADER')
        self.assertEqual(reads,0);self.assertFalse(value['routeReadGate']['signalObserved'])
        self.assertEqual(value['errorCode'],'PRIVATE_PROTOCOL_TIMEOUT')
        self.assertFalse(value['protocolStatRetry']['budgetUsed'])

    def test_unavailable_progress_before_signal_cannot_early_read(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='UNAVAILABLE_BEFORE')
        self.assertEqual(self.last_driver_state['early_route_reads'],0)
        self.assertTrue(value['routeReadGate']['signalObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['protocolStatRetry']['firstObservation']['operation'],'PRIVATE_ROUTE_STAT')

    def test_published_fact_survives_later_unavailable_progress(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='UNAVAILABLE_AFTER')
        self.assertTrue(value['routeReadGate']['signalObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['nativeProgressAvailability'],'UNAVAILABLE')
        self.assertEqual(value['errorCode'],'PRIVATE_ROUTE_FIELDS');self.assertFalse(value['oneNativeMethodPassed'])

    def test_published_fact_survives_later_nonfailure_branch_sample(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='BRANCH_AFTER')
        self.assertTrue(value['routeReadGate']['signalObserved']);self.assertEqual(reads,2)
        self.assertEqual(value['lastNativeProgress']['listenerObservation']['step'],'NOT_REACHED')
        self.assertEqual(value['errorCode'],'PRIVATE_ROUTE_FIELDS');self.assertFalse(value['oneNativeMethodPassed'])

    def test_native_first_failure_cannot_be_overridden_by_latched_fact(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='FAILURE_AFTER')
        self.assertEqual(reads,1);self.assertTrue(value['routeReadGate']['firstFailureObserved'])
        self.assertEqual(value['errorCode'],'NATIVE_FAILURE_BEFORE_ROUTE_READ')
        self.assertEqual(value['protocolStatRetry']['retryAttempts'],0)
        self.assertEqual(value['protocolStatRetry']['firstObservation']['operation'],'PRIVATE_ROUTE_STAT')

    def test_gate_does_not_start_new_deadline_when_signal_arrives_late(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='NEAR_DEADLINE',route_missing=True)
        self.assertEqual(reads,1);self.assertTrue(value['routeReadGate']['signalObserved'])
        self.assertEqual(value['errorCode'],'PRIVATE_PROTOCOL_TIMEOUT')
        elapsed=self.last_driver_state['now']-self.last_driver_state['route_start']
        self.assertGreaterEqual(elapsed,180);self.assertLess(elapsed,181)
        self.assertFalse(value['protocolStatRetry']['budgetUsed']);self.assertFalse(value['oneNativeMethodPassed'])

    def test_absent_publication_signal_expires_without_any_route_adb(self):
        value,reads,_=self.run_stat_retry_driver(fault_file='route.json',progress_scenario='NEVER_PUBLISHED')
        self.assertEqual(reads,0);self.assertFalse(value['routeReadGate']['signalObserved'])
        self.assertFalse(value['routeReadGate']['statReadAttempted'])
        self.assertEqual(value['errorCode'],'PRIVATE_PROTOCOL_TIMEOUT')


if __name__=='__main__':unittest.main()
