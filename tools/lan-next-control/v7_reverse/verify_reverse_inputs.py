"""Exact additive reverse-only fixtures, borrowed parsers and source identity."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE=Path(__file__).resolve().parent
SOURCE='40981ae4045114d1d2a076a82b02b7c7f5275cc7'
ANDROID_TARGET='app/app/src/androidTest/java/com/example/chatbar/lanSync/LanV7ReverseSyntheticWriterTest.kt'
class Refused(ValueError):pass
def need(value,code):
    if not value:raise Refused(code)
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def git(root,*args):
    p=subprocess.run(['git','-C',str(root),*args],capture_output=True,timeout=30)
    need(p.returncode==0,'GIT_READ_FAILED');return p.stdout
def verify(request,android,windows,fixed_sha):
    root=HERE.parents[2];fixed=HERE/'fixed-inputs.json'
    need(digest(fixed)==fixed_sha,'FIXED_INPUTS_CHANGED')
    inputs=json.loads(fixed.read_bytes());value=json.loads(request.read_bytes())
    need(value=={'format':1,'sourceSha':SOURCE,'fixtureSha256':digest(HERE/'DesktopV7ReverseSyntheticHostDraftTest.kt')},'EXACT_REVERSE_REQUEST')
    need(inputs['sourceSha']==SOURCE and inputs['api']==26 and inputs['versionCode']==87 and inputs['nonDeliveryDebugOnly'] is True,'FIXED_SCOPE')
    for row in inputs['files']:
        relative=Path(row['path']);need(not relative.is_absolute() and '..' not in relative.parts,'PIN_PATH')
        p=root/relative;need(p.is_file() and not p.is_symlink() and p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'PINNED_CONTROL_CHANGED')
    for checkout in (android,windows):
        need(git(checkout,'rev-parse','HEAD').decode().strip()==SOURCE,'SOURCE_HEAD')
        need(not git(checkout,'status','--porcelain','--untracked-files=all').strip(),'FRESH_SOURCE_REQUIRED')
        for row in inputs['sourceFiles']:
            raw=git(checkout,'show',SOURCE+':'+row['path'])
            need(len(raw)==row['bytes'] and hashlib.sha256(raw).hexdigest()==row['sha256'] and (checkout/row['path']).read_bytes()==raw,'SOURCE_BLOB_CHANGED')
    return inputs
def prepare_android(source):
    target=source/ANDROID_TARGET;need(not target.exists() and not target.is_symlink(),'ANDROID_OVERLAY_EXISTS')
    target.parent.mkdir(parents=True,exist_ok=True)
    need(target.resolve().is_relative_to(source.resolve()),'ANDROID_OVERLAY_ESCAPE')
    with target.open('xb') as out:out.write((HERE/'LanV7ReverseSyntheticWriterTest.kt').read_bytes())
    android_overlay(source)
def android_overlay(source):
    need(git(source,'rev-parse','HEAD').decode().strip()==SOURCE,'ANDROID_SOURCE_HEAD_CHANGED')
    need(git(source,'status','--porcelain','--untracked-files=all').decode().strip()=='?? '+ANDROID_TARGET,'ANDROID_OVERLAY_NOT_EXCLUSIVE')
    need(digest(source/ANDROID_TARGET)==digest(HERE/'LanV7ReverseSyntheticWriterTest.kt'),'ANDROID_OVERLAY_CHANGED')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--request',type=Path,required=True);p.add_argument('--android-source',type=Path,required=True);p.add_argument('--windows-source',type=Path,required=True);p.add_argument('--fixed-sha256',required=True)
    args=p.parse_args();verify(args.request,args.android_source,args.windows_source,args.fixed_sha256)
    print('{"status":"REVERSE_EXACT_INPUTS_VERIFIED","nativeExecuted":false}')
