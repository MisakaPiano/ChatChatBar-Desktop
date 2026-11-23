"""One isolated Windows cloud job: debug build, production LAN pair, two exact tests."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from verify_reverse_inputs import HERE,SOURCE,need,verify,prepare_android,android_overlay,Refused
from v7_reverse_android_control import publish as publish_stop

CERT_801F='801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'
def safe_windows_diagnostics(value):
    if value is None:return
    flags={'oraclePresent','oracleEverPresent','repositorySessionPresent','ordinaryCountCapped','ordinaryReverseEverPresent',
        'readOnlyCountCapped','readOnlyReverseEverPresent','groupBusy','groupPaused','groupErrorPresent','nearbyLastSuccessPresent'}
    counts={'ordinaryMessageCount','ordinaryReverseIdCount','readOnlyMessageCount','readOnlyReverseIdCount'}
    need(set(value)==flags|counts|{'seconds','readOnlyStatus','waitState'},'WINDOWS_DIAGNOSTIC_FIELDS')
    need(all(value[x] is None or type(value[x]) is bool for x in flags) and
         all(value[x] is None or type(value[x]) is int and 0<=value[x]<=100 for x in counts),'WINDOWS_DIAGNOSTIC_VALUES')
    need(type(value['seconds']) is int and 0<=value['seconds']<=180 and value['readOnlyStatus'] in {'READ_ERROR','READABLE'} and
         value['waitState'] in {'BOTH_PRESENT','ORACLE_ONLY','MESSAGE_ONLY','MISSING_BOTH'},'WINDOWS_DIAGNOSTIC_ENUM')
def safe_windows_result(value):
    allowed={'kind','sourceSha','passed','phase','peerApproved','reverseMessageAndPngMatched','session','message',
        'forwardContextImageSha256','productionNetworkLocal','udpDiscoveryProved','packagedMainTested','humanAcceptance',
        'reverseDiagnostics','reverseObservations','reverseStep'}
    phases={'native-guard','isolated-app-root','new-synthetic-group','ordinary-repository-message-and-real-png',
        'automatic-authenticated-source-and-exact-local-receipt','wait-for-real-android-preflight','production-local-invitation',
        'real-android-proof-and-explicit-member-confirmation','ordinary-reverse-repository-message-and-png','normal-app-close',
        'app-close-cleanup','private-offer-cleanup','synthetic-root-cleanup','safe-result-write','complete'}
    need(set(value)<=allowed and value.get('sourceSha')==SOURCE and value.get('phase') in phases,'WINDOWS_SAFE_RESULT_FIELDS')
    need(value.get('reverseStep','NOT_REACHED') in {'NOT_REACHED','WAIT_ORACLE_AND_MESSAGE','ORACLE_VALIDATE',
         'REPOSITORY_VALIDATE','PNG_VALIDATE','ACK_WRITE','COMPLETED'},'WINDOWS_REVERSE_STEP_ENUM')
    safe_windows_diagnostics(value.get('reverseDiagnostics'))
    rows=value.get('reverseObservations',[]);need(isinstance(rows,list) and len(rows)<=8,'WINDOWS_DIAGNOSTIC_HISTORY_LIMIT')
    for row in rows:safe_windows_diagnostics(row)
    return value
def write(path,value):
    with path.open('x',encoding='utf-8',newline='\n') as out:json.dump(value,out,indent=2);out.write('\n')
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('request','android-source','windows-source','evidence'):p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--fixed-sha256',required=True);args=p.parse_args()
    state={'kind':'CCB_V7_FROZEN_REVERSE_CLOUD_CONTROL','sourceSha':SOURCE,'status':'NOT_RUN','phase':'INPUTS','errorCode':None,
        'nonDeliveryDebugOnly':True,'nativeTargetStartAttempts':{'android':0,'windows':0},'androidPassed':False,'windowsPassed':False,
        'ordinaryAndroidJoinUiProved':False,'packagedWindowsMainProved':False,'rawOriginalReportsUploaded':False,
        'ownedProcessAndKeyCleanupComplete':False,'privateWorkspaceRetainedUntilRunnerDisposal':True,'humanAcceptance':False}
    children=[];handles=[];vm=None;phone=None;adb=None;lab=None;key=None;env=dict(os.environ);evidence=args.evidence.resolve()
    try:
        need(env.get('GITHUB_ACTIONS')=='true' and env.get('RUNNER_OS')=='Windows' and os.name=='nt','WINDOWS_GITHUB_ONLY')
        need(not evidence.exists() and evidence.parent.is_dir(),'NEW_SAFE_EVIDENCE_REQUIRED');evidence.mkdir()
        workspace=Path(env['GITHUB_WORKSPACE']).resolve(strict=True);runner=Path(env['RUNNER_TEMP']).resolve(strict=True)
        android=args.android_source.resolve(strict=True);windows=args.windows_source.resolve(strict=True)
        need(android!=windows and android.is_relative_to(workspace) and windows.is_relative_to(workspace),'TWO_OWNED_SOURCE_CHECKOUTS')
        inputs=verify(args.request,android,windows,args.fixed_sha256)
        state['targets']=inputs['targets'];state['fixedInputsSha256']=args.fixed_sha256
        private=runner/'ccb-v7-reverse-build';lab=runner/'ccb-v7-live'
        need(not private.exists() and not lab.exists(),'NEW_PRIVATE_ROOTS_REQUIRED');private.mkdir();lab.mkdir()
        need(private.resolve().parent==runner and lab.resolve().parent==runner,'PRIVATE_ROOT_ESCAPE')
        key=private/'cloud-debug.jks';env['CCB_REVERSE_DEBUG_KEYSTORE']=str(key)
        sdk=Path(env.get('ANDROID_HOME') or env['ANDROID_SDK_ROOT']).resolve(strict=True)
        java=Path(env['JAVA_HOME']).resolve(strict=True);adb=sdk/'platform-tools/adb.exe'
        env.update(CCB_V7_SOURCE_SHA=SOURCE,CCB_V7_LIVE_SYNTHETIC='true',CCB_V7_REVERSE_SYNTHETIC='true',
            PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(HERE.parent),ANDROID_AVD_HOME=str(private/'avd'))
        (private/'avd').mkdir();env['CCB_V7_SOURCE_SHA']=SOURCE
        def command(argv,phase,timeout=60,stdin=None,cwd=None,read=False):
            state['phase']=phase
            with (private/(phase+'.out')).open('xb') as out,(private/(phase+'.err')).open('xb') as err:
                q=subprocess.run([str(x) for x in argv],cwd=cwd,env=env,input=stdin,stdout=out,stderr=err,timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
            need(q.returncode==0,phase.upper().replace('-','_')+'_FAILED')
            if read:
                need((private/(phase+'.out')).stat().st_size<=65536,'COMMAND_METADATA_LIMIT')
                return (private/(phase+'.out')).read_bytes()
            return b''
        def batch(path,*arguments):return ['cmd.exe','/d','/c',str(path),*arguments]
        def adb_call(*arguments,timeout=20):
            q=subprocess.run([str(adb),'-s','emulator-5554',*arguments],capture_output=True,env=env,timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
            need(q.returncode==0 and len(q.stdout)<=65536 and len(q.stderr)<=4096,'OWNED_ADB_COMMAND_FAILED');return q.stdout
        command([java/'bin/keytool.exe','-genkeypair','-keystore',key,'-storetype','JKS','-storepass','android','-keypass','android',
                 '-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=CCB Synthetic Cloud Debug,O=CCB,C=ZZ'], 'temporary-debug-key')
        prepare_android(android)
        command(batch(android/'app/gradlew.bat','-I',str(HERE/'cloud-debug-signing.init.gradle'),':app:assembleLanPreview',':app:assembleLanPreviewAndroidTest',
                 '-PlanSyncDeviceTests=true','-PlanSyncPreviewSeries=next','-PlanSyncPreviewCode=87','--no-daemon','--no-build-cache'),
                 'android-debug-build',timeout=1800,cwd=android/'app')
        android_overlay(android)
        apps=list((android/'app/app/build/outputs/apk/lanPreview').glob('*.apk'))
        tests=list((android/'app/app/build/outputs/apk/androidTest/lanPreview').glob('*.apk'))
        need(len(apps)==len(tests)==1,'EXACT_TWO_DEBUG_APK_OUTPUTS')
        build_tools=sdk/'build-tools/36.0.0';apksigner=build_tools/'apksigner.bat'
        certs=[];apks=[]
        for role,apk in zip(('app87','test87'),(apps[0],tests[0])):
            raw=command(batch(apksigner,'verify','--print-certs',str(apk)),role+'-certificate',read=True)
            matches=re.findall(rb'^Signer #1 certificate SHA-256 digest: ([a-f0-9]{64})\r?$',raw,re.M)
            need(len(matches)==1,'SINGLE_DEBUG_CERTIFICATE_REQUIRED');certs.append(matches[0].decode())
            apks.append({'role':role,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest()})
        need(certs[0]==certs[1] and certs[0]!=CERT_801F,'ISOLATED_DEBUG_SIGNER_REQUIRED')
        write(evidence/'debug-build.json',{'kind':'CCB_V7_FROZEN_TEMPORARY_DEBUG_BUILD','sourceSha':SOURCE,'versionCode':87,
              'apks':apks,'temporaryAppTestSignerMatched':True,'deliveredSignerUsed':False,'distributable':False})
        command([sys.executable,'-B',str(HERE/'v7_windows_reverse_control.py'),'prepare','--request',str(args.request.resolve()),
                 '--source',str(windows),'--evidence',str(evidence/'windows-work')],'windows-overlay')
        command(batch(sdk/'cmdline-tools/latest/bin/avdmanager.bat','create','avd','--name','CCBV7Reverse','--package','system-images;android-26;google_apis;x86_64'),
                 'new-api26-avd',timeout=120,stdin=b'no\n')
        state['phase']='EMULATOR_BOOT'
        for name in ('emulator.out','emulator.err'):handles.append((private/name).open('xb'))
        vm=subprocess.Popen([str(sdk/'emulator/emulator.exe'),'-avd','CCBV7Reverse','-port','5554','-no-window','-no-audio','-no-snapshot',
            '-no-boot-anim','-accel','on','-gpu','swiftshader_indirect','-memory','1536','-cores','2'],env=env,stdout=handles[-2],stderr=handles[-1],creationflags=subprocess.CREATE_NO_WINDOW)
        until=time.monotonic()+310;booted=False
        while time.monotonic()<until:
            need(vm.poll() is None,'OWNED_EMULATOR_EXITED')
            try:booted=adb_call('shell','getprop','sys.boot_completed').strip()==b'1'
            except Refused:booted=False
            if booted:break
            time.sleep(4)
        need(booted,'OWNED_EMULATOR_BOOT_TIMEOUT')
        need(adb_call('shell','getprop','ro.kernel.qemu').strip()==b'1' and adb_call('shell','getprop','ro.build.version.sdk').strip()==b'26','OWNED_API26_REQUIRED')
        adb_call('shell','svc','power','stayon','true');adb_call('shell','input','keyevent','82')
        need(not adb_call('shell','pm','list','packages','com.example.chatbar').strip(),'FRESH_NO_CCB_PACKAGE_REQUIRED')
        command([adb,'-s','emulator-5554','install',apps[0]],'install-debug-app',timeout=180)
        command([adb,'-s','emulator-5554','install',tests[0]],'install-debug-test',timeout=180)
        def start(argv,role):
            out=(private/(role+'.out')).open('xb');err=(private/(role+'.err')).open('xb');handles.extend((out,err))
            child=subprocess.Popen([str(x) for x in argv],env=env,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
            children.append(child);return child
        state['phase']='TWO_EXACT_NATIVE_TARGETS'
        state['nativeTargetStartAttempts']['android']=1
        phone=start([sys.executable,'-B',HERE/'v7_reverse_android_control.py','--adb',adb,'--serial','emulator-5554','--control-tools',HERE.parent,
            '--candidate-sha256',apks[0]['sha256'],'--test-sha256',apks[1]['sha256'],'--private-root',lab,'--result',evidence/'android.json'],'android-control')
        until=time.monotonic()+150
        while not (lab/'android-ready.json').exists() and time.monotonic()<until:
            need(phone.poll() is None,'ANDROID_PREFLIGHT_FAILED');time.sleep(.3)
        need((lab/'android-ready.json').is_file(),'ANDROID_REAL_READY_TIMEOUT')
        state['nativeTargetStartAttempts']['windows']=1
        desktop=start([sys.executable,'-B',HERE/'v7_windows_reverse_control.py','run','--request',args.request.resolve(),
            '--source',windows,'--evidence',evidence/'windows-work'],'windows-control')
        # Bound the whole test tree BEFORE the borrowed runner's own1800s timeout.
        until=time.monotonic()+1500
        while time.monotonic()<until and (phone.poll() is None or desktop.poll() is None):
            need(phone.poll() in (None,0),'ANDROID_NATIVE_TARGET_FAILED')
            need(desktop.poll() in (None,0),'WINDOWS_NATIVE_TARGET_FAILED')
            need(vm.poll() is None,'OWNED_EMULATOR_EXITED_DURING_CASE');time.sleep(.5)
        need(phone.poll() is not None and desktop.poll() is not None,'TWO_TARGET_DEADLINE_EXPIRED')
        need(phone.returncode==desktop.returncode==0,'TWO_TARGET_NATIVE_FAILURE')
        android_result=json.loads((evidence/'android.json').read_bytes())
        need(android_result['sourceSha']==SOURCE and android_result['status']=='REVERSE_DEBUG_CASE_PASSED' and
             android_result.get('terminal') is True and android_result['oneNativeMethodPassed'] is True and
             android_result['windowsReceived'] is True,'ANDROID_STRICT_METHOD_NOT_PASSED')
        raw=(lab/'windows-result.json').read_bytes();need(len(raw)<=16384,'WINDOWS_SAFE_RESULT_LIMIT')
        windows_result=safe_windows_result(json.loads(raw))
        need(windows_result['kind']=='CCB_V7_WINDOWS_REVERSE_SYNTHETIC_RESULT' and windows_result['sourceSha']==SOURCE and
             windows_result['passed'] is True and windows_result['reverseMessageAndPngMatched'] is True and
             windows_result['peerApproved'] is True and windows_result['productionNetworkLocal'] is True,'WINDOWS_ACTUAL_REVERSE_NOT_PASSED')
        write(evidence/'windows.json',windows_result)
        native=json.loads((evidence/'windows-work/verified.json').read_bytes())
        need(native['sourceSha']==SOURCE and native['passed']==1 and native['executed']==1 and native['skipped']==0,'WINDOWS_STRICT_METHOD_NOT_PASSED')
        write(evidence/'windows-junit.json',native)
        android_overlay(android);state.update(androidPassed=True,windowsPassed=True,status='TWO_NATIVE_REVERSE_TARGETS_PASSED',phase='DONE')
    except Exception as error:
        state.update(status='REFUSED',errorCode=str(error) if isinstance(error,Refused) else type(error).__name__)
    finally:
        cleanup=True
        # Keep the first peer/control error. Give the Android controller a bounded
        # diagnostic/finally drain before its owned tree is forcibly terminated.
        if phone is not None and phone.poll() is None and lab is not None:
            state['androidDiagnosticDrainRequested']=True
            try:
                stop=lab/'android-stop-requested.json'
                if not stop.exists():publish_stop(stop,{'kind':'CCB_V7_REVERSE_STOP','reason':'PEER_OR_CONTROL_FAILED'})
                until=time.monotonic()+45
                while phone.poll() is None and time.monotonic()<until:time.sleep(.25)
                state['androidControllerReturnedDuringDrain']=phone.poll() is not None
            except Exception:state['androidControllerReturnedDuringDrain']=False
        for child in children:
            if child.poll() is None:
                if child is phone:state['androidControllerForcedStop']=True
                try:subprocess.run(['taskkill.exe','/PID',str(child.pid),'/T','/F'],capture_output=True,timeout=20,creationflags=subprocess.CREATE_NO_WINDOW);child.wait(timeout=10)
                except Exception:cleanup=False
        if vm is not None and vm.poll() is None:
            try:
                if adb is not None:subprocess.run([str(adb),'-s','emulator-5554','emu','kill'],capture_output=True,timeout=10,creationflags=subprocess.CREATE_NO_WINDOW)
                vm.wait(timeout=10)
            except Exception:
                try:subprocess.run(['taskkill.exe','/PID',str(vm.pid),'/T','/F'],capture_output=True,timeout=20,creationflags=subprocess.CREATE_NO_WINDOW);vm.wait(timeout=10)
                except Exception:cleanup=False
        for handle in handles:
            try:handle.close()
            except Exception:cleanup=False
        if key is not None and key.is_file():
            try:
                need(key.resolve().parent==Path(env['RUNNER_TEMP']).resolve()/'ccb-v7-reverse-build','DEBUG_KEY_CLEANUP_BOUNDARY')
                key.unlink()
            except Exception:cleanup=False
        # Collect only fixed small producer objects even on failure. Raw reports,
        # protocol, Gradle logs, APKs and the private key are never upload inputs.
        try:
            if (evidence/'android.json').is_file():
                need((evidence/'android.json').stat().st_size<=65536,'ANDROID_RESULT_LIMIT')
                phone_result=json.loads((evidence/'android.json').read_bytes())
                need(phone_result['sourceSha']==SOURCE,'ANDROID_RESULT_SOURCE')
                state['androidPassed']=phone_result.get('oneNativeMethodPassed') is True
                state['androidControllerTerminal']=phone_result.get('terminal') is True
            if lab is not None and (lab/'windows-result.json').is_file() and not (evidence/'windows.json').exists():
                raw=(lab/'windows-result.json').read_bytes();need(len(raw)<=16384,'WINDOWS_RESULT_LIMIT')
                value=safe_windows_result(json.loads(raw))
                write(evidence/'windows.json',value)
            proof=evidence/'windows-work/verified.json'
            if proof.is_file() and not (evidence/'windows-junit.json').exists():
                need(proof.stat().st_size<=16384,'WINDOWS_PROOF_LIMIT')
                value=json.loads(proof.read_bytes());need(value.get('sourceSha')==SOURCE and value.get('passed')==1,'WINDOWS_PROOF_SOURCE')
                write(evidence/'windows-junit.json',value)
            if (evidence/'windows.json').is_file() and (evidence/'windows-junit.json').is_file():
                state['windowsPassed']=json.loads((evidence/'windows.json').read_bytes()).get('passed') is True
        except Exception:state['smallEvidenceCollectionIncomplete']=True
        state['ownedProcessAndKeyCleanupComplete']=cleanup
        if evidence.is_dir() and not (evidence/'run.json').exists():write(evidence/'run.json',state)
    print(json.dumps({'status':state['status'],'phase':state['phase'],'errorCode':state['errorCode']}))
    return 0 if state['status']=='TWO_NATIVE_REVERSE_TARGETS_PASSED' else 2
if __name__=='__main__':raise SystemExit(main())
