"""Non-delivery candidate87 debug pair, one real instrumentation case, private protocol only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

from adb_observation import AdbFailure, observation, interpret, instrumentation_summary

SOURCE='40981ae4045114d1d2a076a82b02b7c7f5275cc7'
PACKAGE='com.example.chatbar.lansyncnext'
CLASS='com.example.chatbar.lanSync.LanV7ReverseSyntheticWriterTest'
METHOD='appendOneMessageAndPngAfterProductionLanJoin'
APP_LAB='files/v7-reverse-lab/'
NATIVE_PHASES={'opt-in','startup','private-synthetic-offer','real-production-lan-join','initial-repository-receive',
    'durable-imported-session','ordinary-repository-append','normal-runtime-reverse-receipt','activity-close',
    'isolated-reverse-route','isolated-route-close'}
class Refused(Exception):pass
def need(value,code):
    if not value:raise Refused(code)
def publish(path,value,replace=False):
    need(replace or not path.exists(),'NEW_PRIVATE_FILE_REQUIRED')
    temp=path.with_name(path.name+'.tmp')
    with temp.open('x',encoding='utf-8',newline='\n') as out:
        json.dump(value,out,separators=(',',':'));out.write('\n');out.flush();os.fsync(out.fileno())
    need(replace or not path.exists(),'NEW_PRIVATE_FILE_REQUIRED');os.replace(temp,path)

def safe_native_progress(raw):
    need(len(raw)<=4096,'NATIVE_PROGRESS_LIMIT');value=json.loads(raw)
    booleans={'initialSessionPresent','initialMessageExact','initialImageMatched','initialMessageCountCapped','durableSessionVerified','appendAttempted','appendCommitted',
        'appendReturned','oracleWritten','groupBusy','groupPaused','groupErrorPresent','diagnosticWriteFailed',
        'listenerObserved','relayBytesBothDirections','sourceSignedVersionObserved','sourceNativeFrontierObserved',
        'sourceLocalReceiptObserved','publicationReadAvailable'}
    integers={'initialMessageCount','localReverseCount','relayAccepted','relayConnected'}
    need(set(value)==booleans|integers|{'kind','phase','sequence','firstFailurePhase','groupStage','isolatedRouteMode'},'NATIVE_PROGRESS_FIELDS')
    need(value['isolatedRouteMode']=='ADB_FORWARD_TRANSPARENT_TLS','NATIVE_ROUTE_MODE')
    need(value['kind']=='CCB_V7_ANDROID_REVERSE_PROGRESS' and value['phase'] in NATIVE_PHASES and
         value['firstFailurePhase'] in NATIVE_PHASES|{None},'NATIVE_PROGRESS_PHASE')
    need(type(value['sequence']) is int and 0<=value['sequence']<=10000,'NATIVE_PROGRESS_SEQUENCE')
    need(value['groupStage'] in {None,'EMPTY','NEEDS_RECOVERY_SAVE','PREPARED_LOCKED','JOINING','READY'},'NATIVE_PROGRESS_GROUP_STAGE')
    need(all(value[x] is None or type(value[x]) is bool for x in booleans) and
         all(value[x] is None or type(value[x]) is int and 0<=value[x]<=100 for x in integers),'NATIVE_PROGRESS_VALUES')
    return value

def isolated_route(raw):
    need(len(raw)<=2048,'PRIVATE_ROUTE_LIMIT');value=json.loads(raw)
    need(set(value)=={'kind','sourceSha','group','device','relayPort'} and
         value['kind']=='CCB_V7_ANDROID_ISOLATED_ROUTE' and value['sourceSha']==SOURCE and
         re.fullmatch(r'[a-f0-9]{32}',value['group']) and re.fullmatch(r'[a-f0-9]{64}',value['device']) and
         type(value['relayPort']) is int and 1024<=value['relayPort']<=65535,'PRIVATE_ROUTE_FIELDS')
    return value

def owned_forward(rows,serial,port,relay_port):
    return sum(line.split()==[serial,'tcp:'+str(port),'tcp:'+str(relay_port)]
               for line in rows.splitlines())==1
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('adb','control-tools','private-root','result'):p.add_argument('--'+key,type=Path,required=True)
    for key in ('serial','candidate-sha256','test-sha256'):p.add_argument('--'+key,required=True)
    args=p.parse_args();r={'kind':'CCB_V7_REVERSE_DEBUG_ANDROID_SAFE_RESULT','sourceSha':SOURCE,
        'status':'NOT_RUN','phase':'PRECHECK','errorCode':None,'nonDeliveryDebugOnly':True,
        'ordinaryAndroidPasteUiProved':False,'readyObserved':False,'oracleCopied':False,
        'windowsReceived':False,'oneNativeMethodPassed':False,'privateProtocolCleaned':False,'humanAcceptance':False,
        'terminal':False,'nativeProgressAvailability':'UNREAD','lastNativeProgress':None,'checkpointWriteFailed':False,
        'firstFatalAdb':None,'lastAdbOperation':None,'lastProgressAdbFailure':None,'instrumentationAtFailure':None,
        'shellV2Observed':False,'runAsRootObserved':False}
    proc=None;logs=[];prefix=[str(args.adb),'-s',args.serial];result_owned=False;last_progress=0;forward=None
    def checkpoint():
        if result_owned:
            try:publish(args.result,r,replace=True)
            except Exception:r['checkpointWriteFailed']=True
    def call(operation,*command,stdin=None,missing=False):
        r['lastAdbOperation']=operation;checkpoint()
        try:q=subprocess.run(prefix+list(command),input=stdin,capture_output=True,timeout=20)
        except subprocess.TimeoutExpired as failure:
            raise AdbFailure(observation(operation,None,failure.stdout or b'',failure.stderr or b'',
                             transport='TIMEOUT'),'ADB_TRANSPORT_TIMEOUT') from None
        except OSError:
            raise AdbFailure(observation(operation,None,b'',b'',transport='SPAWN_ERROR'),'ADB_SPAWN_FAILED') from None
        evidence=observation(operation,q.returncode,q.stdout,q.stderr)
        return interpret(evidence,q.stdout,missing=missing)
    def native_snapshot():
        try:
            paths=(args.private_root/'android-reverse-stdout.txt',args.private_root/'android-reverse-stderr.txt')
            if not all(path.is_file() for path in paths):return {'availability':'MISSING','passClaimed':False}
            data=[]
            for path in paths:
                with path.open('rb') as stream:data.append(stream.read(131073))
            return instrumentation_summary(*data,proc is not None and proc.poll() is not None,CLASS,METHOD,NATIVE_PHASES)
        except Exception:return {'availability':'READ_ERROR','passClaimed':False}
    def private_read(name):
        need(name in ('ready.json','oracle.json','progress.json','route.json'),'PRIVATE_READ_ENUM')
        key={'ready.json':'READY','oracle.json':'ORACLE','progress.json':'PROGRESS','route.json':'ROUTE'}[name]
        if call('PRIVATE_'+key+'_STAT','shell','-T','run-as',PACKAGE,'toybox','stat','-c','%s',APP_LAB+name,missing=True) is None:return None
        return call('PRIVATE_'+key+'_CAT','shell','-T','run-as',PACKAGE,'cat',APP_LAB+name)
    def observe_progress(force=False):
        nonlocal last_progress
        if not logs or not force and time.monotonic()-last_progress<1:return
        last_progress=time.monotonic()
        try:raw=private_read('progress.json')
        except AdbFailure as error:
            r['nativeProgressAvailability']='UNAVAILABLE';r['lastProgressAdbFailure']=error.evidence
        except Exception:r['nativeProgressAvailability']='UNAVAILABLE'
        else:
            if raw is None:r['nativeProgressAvailability']='MISSING'
            else:
                try:r['lastNativeProgress']=safe_native_progress(raw);r['nativeProgressAvailability']='VALID'
                except Exception:r['nativeProgressAvailability']='INVALID'
        checkpoint()
    def private_write(name,raw):
        need(name in ('offer.json','windows-received.json') and len(raw)<=20000,'PRIVATE_WRITE_ENUM_OR_LIMIT')
        script='umask 077; set -C; cat > '+APP_LAB+name
        call('PRIVATE_OFFER_WRITE' if name=='offer.json' else 'PRIVATE_ACK_WRITE',
             'shell','-T','run-as',PACKAGE,'sh','-c',shlex.quote(script),stdin=raw)
    def wait(read,seconds):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            observe_progress()
            stop=args.private_root/'android-stop-requested.json'
            if stop.is_file():
                need(stop.stat().st_size<=512,'STOP_REQUEST_LIMIT')
                value=json.loads(stop.read_bytes())
                need(set(value)=={'kind','reason'} and value['kind']=='CCB_V7_REVERSE_STOP' and value['reason']=='PEER_OR_CONTROL_FAILED','STOP_REQUEST_CONTRACT')
                raise Refused('PEER_OR_CONTROL_FAILED')
            value=read()
            if value is not None:return value
            need(proc is None or proc.poll() is None,'INSTRUMENTATION_ENDED_BEFORE_PROTOCOL')
            time.sleep(.3)
        raise Refused('PRIVATE_PROTOCOL_TIMEOUT')
    try:
        need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_OS')=='Windows','WINDOWS_CLOUD_ONLY')
        need(re.fullmatch(r'emulator-[0-9]+',args.serial),'OWNED_EMULATOR_REQUIRED')
        root=Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
        lab=args.private_root.resolve(strict=True)
        need(lab==root/'ccb-v7-live' and args.result.parent.is_dir() and not args.result.exists()
             and not args.result.resolve().is_relative_to(lab),'SEPARATE_NEW_SAFE_OUTPUT_REQUIRED')
        need(not any((lab/x).exists() for x in ('live-offer.json','android-ready.json','reverse-expected.json',
             'windows-reverse-received.json','android-isolated-route.json')),'NEW_PRIVATE_PROTOCOL_REQUIRED')
        result_owned=True;r['status']='IN_PROGRESS';checkpoint()
        sys.path.insert(0,str(args.control_tools.resolve(strict=True)))
        import instrumentation_report
        need(call('GET_EMULATOR_FLAG','shell','getprop','ro.kernel.qemu').strip()==b'1' and call('GET_API_LEVEL','shell','getprop','ro.build.version.sdk').strip()==b'26','API26_EMULATOR_REQUIRED')
        package=call('GET_PACKAGE_DESCRIPTOR','shell','dumpsys','package',PACKAGE)
        need(re.search(rb'\bversionCode=87\b',package) and b'DEBUGGABLE' in package,'DEBUG_CANDIDATE87_REQUIRED')
        for package,sha in ((PACKAGE,args.candidate_sha256),(PACKAGE+'.test',args.test_sha256)):
            need(re.fullmatch(r'[a-f0-9]{64}',sha),'APK_HASH_PIN_REQUIRED')
            paths=call('GET_APK_PATH','shell','pm','path',package).decode('ascii').splitlines()
            need(len(paths)==1 and paths[0].startswith('package:/data/app/') and paths[0].endswith('/base.apk'),'SINGLE_INSTALLED_APK_REQUIRED')
            need(call('GET_APK_HASH','shell','toybox','sha256sum',paths[0][8:]).split()[0].decode('ascii')==sha,'INSTALLED_DEBUG_APK_HASH_MISMATCH')
        features=call('VERIFY_SHELL_FEATURES','features').splitlines()
        need(b'shell_v2' in features,'SHELL_V2_REQUIRED');r['shellV2Observed']=True
        app_root=call('VERIFY_RUN_AS_ROOT','shell','-T','run-as',PACKAGE,'pwd').strip()
        need(app_root in (('/data/user/0/'+PACKAGE).encode(),('/data/data/'+PACKAGE).encode()),'APP_RUN_AS_ROOT_NOT_EXPECTED')
        r['runAsRootObserved']=True
        r['phase']='ONE_INSTRUMENTATION'
        checkpoint()
        logs=[(lab/'android-reverse-stdout.txt').open('xb'),(lab/'android-reverse-stderr.txt').open('xb')]
        proc=subprocess.Popen(prefix+['shell','-T','am','instrument','-w','-r','-e','v7ReverseSynthetic','true',
            '-e','sourceSha',SOURCE,'-e','class',CLASS+'#'+METHOD,PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=logs[0],stderr=logs[1])
        ready=json.loads(wait(lambda:private_read('ready.json'),150))
        need(ready=={'kind':'CCB_V7_ANDROID_READY','ready':True},'ANDROID_READY_INVALID')
        publish(lab/'android-ready.json',ready);r['readyObserved']=True;r['phase']='PRIVATE_OFFER';checkpoint()
        offer=wait(lambda:(lab/'live-offer.json').read_bytes() if (lab/'live-offer.json').is_file() else None,900)
        value=json.loads(offer)
        need(set(value)=={'kind','code','session','message','text','imageSha256'} and value['kind']=='CCB_V7_LIVE_SYNTHETIC_OFFER' and
             value['session']=='ccb-v7-lab-session' and value['message']=='ccb-v7-lab-message' and value['text']=='CCB-V7-synthetic-message' and
             isinstance(value['code'],str) and len(value['code'])<=16384 and re.fullmatch(r'CCB-GROUP-JOIN1:[A-Za-z0-9_-]+',value['code']) and
             re.fullmatch(r'[a-f0-9]{64}',value['imageSha256']),'SYNTHETIC_OFFER_INVALID')
        private_write('offer.json',offer);offer=value=None
        r['phase']='ISOLATED_REVERSE_ROUTE';checkpoint()
        route=isolated_route(wait(lambda:private_read('route.json'),180))
        allocated=call('FORWARD_CREATE','forward','--no-rebind','tcp:0','tcp:'+str(route['relayPort'])).strip()
        need(re.fullmatch(rb'[0-9]{4,5}',allocated),'FORWARD_ALLOCATED_PORT')
        forward=int(allocated);need(1024<=forward<=65535,'FORWARD_PORT_RANGE')
        rows=call('FORWARD_LIST','forward','--list').decode('ascii')
        need(owned_forward(rows,args.serial,forward,route['relayPort']),'OWNED_FORWARD_BINDING')
        publish(lab/'android-isolated-route.json',{'kind':'CCB_V7_HOST_ISOLATED_ROUTE','sourceSha':SOURCE,
                'group':route['group'],'device':route['device'],'forwardPort':forward})
        r['isolatedForwardBound']=True;checkpoint()
        r['phase']='ORDINARY_REPOSITORY_REVERSE';checkpoint()
        raw=wait(lambda:private_read('oracle.json'),300);oracle=json.loads(raw)
        need(set(oracle)=={'kind','sourceSha','session','message','text','imageSha256','imageBytes','width','height'},'ORACLE_FIELDS')
        need(oracle['kind']=='CCB_V7_ANDROID_REVERSE_ORACLE' and oracle['sourceSha']==SOURCE and
             oracle['session']=='ccb-v7-lab-session' and oracle['message']=='ccb-v7-reverse-message' and
             oracle['text']=='CCB-V7-synthetic-android-reverse' and oracle['width']==oracle['height']==8 and
             type(oracle['imageBytes']) is int and 0<oracle['imageBytes']<=4194304 and
             re.fullmatch(r'[a-f0-9]{64}',oracle['imageSha256']),'ORACLE_FIXED_SCOPE')
        publish(lab/'reverse-expected.json',oracle);r['oracleCopied']=True;r['phase']='WINDOWS_ACTUAL_RECEIPT';checkpoint()
        raw=wait(lambda:(lab/'windows-reverse-received.json').read_bytes() if (lab/'windows-reverse-received.json').is_file() else None,180)
        need(json.loads(raw)=={'kind':'CCB_V7_WINDOWS_REVERSE_RECEIVED','received':True},'WINDOWS_RECEIPT_INVALID')
        private_write('windows-received.json',raw);r['windowsReceived']=True;checkpoint()
        need(proc.wait(timeout=30)==0,'INSTRUMENTATION_EXIT_FAILED')
        for stream in logs:stream.close()
        report=(lab/'android-reverse-stdout.txt').read_bytes();need(len(report)<=131072,'INSTRUMENTATION_REPORT_LIMIT')
        execution=instrumentation_report.execution(report.decode('utf-8'),CLASS,METHOD)
        r.update(oneNativeMethodPassed=True,execution=execution,reportSha256=hashlib.sha256(report).hexdigest(),status='REVERSE_DEBUG_CASE_PASSED',phase='DONE')
    except Exception as error:
        r.update(status='REFUSED',errorCode=str(error) if isinstance(error,(Refused,AdbFailure)) else 'CONTROL_EXCEPTION')
        if isinstance(error,AdbFailure):r['firstFatalAdb']=error.evidence
        if logs:r['instrumentationAtFailure']=native_snapshot()
        checkpoint() # Freeze the first failure before progress/cleanup commands can run.
    finally:
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:proc.wait(timeout=10)
            except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
        for stream in logs:
            if not stream.closed:stream.close()
        if logs:
            try:
                report=args.private_root/'android-reverse-stdout.txt'
                if report.stat().st_size<=131072:
                    failures=re.findall(rb'V7_REVERSE_ANDROID_PHASE_FAILED:([a-z-]+)',report.read_bytes())
                    if failures and all(x.decode('ascii') in NATIVE_PHASES for x in failures):r['instrumentationFailurePhase']=failures[0].decode('ascii')
            except Exception:pass
        observe_progress(force=True)
        if forward is not None:
            try:
                need(owned_forward(call('FORWARD_LIST','forward','--list').decode('ascii'),args.serial,forward,route['relayPort']),
                     'FORWARD_OWNERSHIP_CHANGED')
                call('FORWARD_REMOVE','forward','--remove','tcp:'+str(forward));r['isolatedForwardRemoved']=True
            except Exception:r['isolatedForwardRemoved']=False
        if proc is not None:
            try:
                call('PRIVATE_PROTOCOL_CLEANUP','shell','-T','run-as',PACKAGE,'rm','-f',*(APP_LAB+x for x in ('ready.json','offer.json','oracle.json','windows-received.json','progress.json','progress.tmp','route.json')))
                r['privateProtocolCleaned']=True
            except Exception:pass
    r['terminal']=True
    try:publish(args.result,r,replace=result_owned)
    except Exception:
        print('{"status":"REFUSED","errorCode":"RESULT_WRITE_FAILED"}');return 2
    print(json.dumps(r,sort_keys=True));return 0 if r['status']=='REVERSE_DEBUG_CASE_PASSED' else 2
if __name__=='__main__':raise SystemExit(main())
