#!/usr/bin/env python3
"""Cloud-only manual Android SDK build, ephemeral independent signer, no Gradle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import zipfile

PACKAGE='org.ccb.synthetic.clipboard'
CANDIDATE_CERT='801f54622926f9ca6710086ac2732854a31b546f8ec2fd38cd0c6c11837a1aa7'

class Refused(Exception):pass
def need(v,c):
    if not v:raise Refused(c)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,v):
    with p.open('x',encoding='utf-8',newline='\n') as f:json.dump(v,f,sort_keys=True,indent=2);f.write('\n')
def tool(p):need(p.is_file(),'SDK_OR_JDK_TOOL_MISSING');return str(p)

def run(argv,env=None):
    try:r=subprocess.run(argv,env=env,capture_output=True,timeout=120)
    except (OSError,subprocess.SubprocessError):raise Refused('BUILD_TOOL_EXECUTION_FAILED') from None
    need(r.returncode==0,'BUILD_TOOL_FAILED')
    need(len(r.stdout)+len(r.stderr)<=2*1024*1024,'BUILD_OUTPUT_LIMIT')
    return r.stdout.decode('utf-8',errors='replace')+r.stderr.decode('utf-8',errors='replace')

def build(a,result):
    need(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('RUNNER_OS') in ('Linux','Windows'),'CLOUD_RUNNER_REQUIRED')
    temp=Path(os.environ['RUNNER_TEMP']).resolve(strict=True);root=a.private_root.resolve()
    need(root.is_relative_to(temp) and root!=temp and not root.exists(),'NEW_PRIVATE_RUNNER_ROOT_REQUIRED')
    need(a.safe_result.parent.is_dir() and not a.safe_result.exists() and not a.safe_result.resolve().is_relative_to(root),'SEPARATE_SAFE_OUTPUT_REQUIRED')
    source=Path(__file__).resolve().parent;exe='.exe' if os.name=='nt' else ''
    bt=a.sdk/'build-tools/36.0.0';jar=a.sdk/'platforms/android-36/android.jar';need(jar.is_file(),'ANDROID36_JAR_REQUIRED')
    java=tool(a.java_home/'bin'/('java'+exe));javac=tool(a.java_home/'bin'/('javac'+exe));keytool=tool(a.java_home/'bin'/('keytool'+exe))
    aapt=tool(bt/('aapt'+exe));align=tool(bt/('zipalign'+exe));d8=tool(bt/'lib/d8.jar');signer=tool(bt/'lib/apksigner.jar')
    root.mkdir();classes=root/'classes';classes.mkdir();dex=root/'dex';dex.mkdir()
    unsigned=root/'unsigned.apk';aligned=root/'aligned.apk';apk=root/'clipboard-helper.apk';key=root/'ephemeral.jks'
    password=secrets.token_hex(24);env=dict(os.environ,CCB_CLIPBOARD_EPHEMERAL_PASSWORD=password)
    result['phase']='PACKAGE_AND_COMPILE'
    try:
        run([aapt,'package','-f','-M',str(source/'AndroidManifest.xml'),'-I',str(jar),'-F',str(unsigned)])
        run([javac,'--release','8','-encoding','UTF-8','-classpath',str(jar),'-d',str(classes),str(source/'ClipboardActivity.java')])
        files=sorted(classes.rglob('*.class'));need(bool(files),'NO_CLASSES')
        run([java,'-cp',d8,'com.android.tools.r8.D8','--min-api','26','--lib',str(jar),'--output',str(dex)]+[str(p) for p in files])
        need([p.name for p in dex.iterdir()]==['classes.dex'],'SINGLE_DEX_REQUIRED')
        with zipfile.ZipFile(unsigned,'a') as z:z.writestr('classes.dex',(dex/'classes.dex').read_bytes(),compress_type=zipfile.ZIP_STORED)
        run([align,'-f','4',str(unsigned),str(aligned)])
        result['phase']='EPHEMERAL_SIGNER'
        run([keytool,'-genkeypair','-noprompt','-keystore',str(key),'-alias','synthetic','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=CCB Synthetic Clipboard Helper','-storepass:env','CCB_CLIPBOARD_EPHEMERAL_PASSWORD','-keypass:env','CCB_CLIPBOARD_EPHEMERAL_PASSWORD'],env)
        run([java,'-jar',signer,'sign','--ks',str(key),'--ks-key-alias','synthetic','--ks-pass','env:CCB_CLIPBOARD_EPHEMERAL_PASSWORD','--key-pass','env:CCB_CLIPBOARD_EPHEMERAL_PASSWORD','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','false','--out',str(apk),str(aligned)],env)
        result['phase']='OFFICIAL_VERIFY'
        verified=run([java,'-jar',signer,'verify','--verbose','--print-certs',str(apk)])
        certs=re.findall(r'^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$',verified,re.M)
        need(len(certs)==1 and certs[0].lower()!=CANDIDATE_CERT and 'Verified using v2 scheme (APK Signature Scheme v2): true' in verified and 'Number of signers: 1' in verified,'INDEPENDENT_SINGLE_V2_SIGNER_REQUIRED')
        run([align,'-c','4',str(apk)])
        manifest=run([aapt,'dump','badging',str(apk)])
        need("package: name='"+PACKAGE+"' versionCode='1'" in manifest and 'application-debuggable' in manifest,'TEST_HELPER_IDENTITY_REQUIRED')
        permissions=run([aapt,'dump','permissions',str(apk)])
        need('uses-permission:' not in permissions,'NO_PERMISSIONS_REQUIRED')
        private={'kind':'CCB_SYNTHETIC_CLIPBOARD_HELPER_BUILD_V1','format':1,'package':PACKAGE,'apk':'clipboard-helper.apk','bytes':apk.stat().st_size,'sha256':sha(apk),'certificateSha256':certs[0].lower(),'singleOfficialV2Verified':True,'candidateCertificateDifferent':True,'debuggable':True,'testOnly':True,'minApi':26,'sourceFiles':{n:sha(source/n) for n in ['AndroidManifest.xml','ClipboardActivity.java']}}
        write(root/'helper-build.json',private)
        result.update(phase='DONE',status='HELPER_BUILT',officialSingleV2Verified=True,candidateCertificateDifferent=True,noPermissions=True,helperApkBytes=apk.stat().st_size)
    finally:
        # Only this job's exact, resolved file; no recursive or alternate cleanup.
        if key.exists():
            need(key.resolve().parent==root,'PRIVATE_KEY_PATH_CHANGED');key.unlink()
        result['ephemeralPrivateKeyAbsent']=not key.exists()
        env.pop('CCB_CLIPBOARD_EPHEMERAL_PASSWORD',None);password=None

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['sdk','java-home','private-root','safe-result']:p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args(argv);r={'kind':'CCB_SYNTHETIC_CLIPBOARD_BUILD_SAFE_RESULT','status':'NOT_RUN','phase':'PRECHECK','errorCode':None,'officialSingleV2Verified':False,'candidateCertificateDifferent':False,'noPermissions':False,'ephemeralPrivateKeyAbsent':None,'helperApkBytes':None,'candidateOrUserKeysAccessed':False}
    try:build(a,r);rc=0
    except Refused as e:r.update(status='REFUSED',errorCode=str(e));rc=2
    except Exception:r.update(status='REFUSED',errorCode='BUILD_CONTROL_EXCEPTION');rc=2
    try:write(a.safe_result,r)
    except OSError:print(json.dumps({'status':'REFUSED','errorCode':'SAFE_RESULT_WRITE_FAILED'}));return 2
    print(json.dumps(r,sort_keys=True));return rc

if __name__=='__main__':raise SystemExit(main())
