"""Synthetic-only preservation experiment. Not a Desktop importer or production schema.

Admission deliberately depends on the independently generated official synthetic fixture
as a closed schema example. This is NOT a universal schema validator or crypto codec.
Only the official plain v1 envelope is supported here; Android supplies both authorities.
"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import sys
import time
import unittest
import uuid
import warnings
import xml.etree.ElementTree as ET
import zipfile

MAX_ARCHIVE = 4 * 1024 * 1024
MAX_FILE = 1024 * 1024
MAX_TOTAL = 8 * 1024 * 1024
MAX_ENTRIES = 100
MAGIC = b'CBBACKUP'


class Refusal(Exception):
    pass


def require(ok, category):
    if not ok:
        raise Refusal(category)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strict(data):
    def pairs(items):
        obj = {}
        for key, val in items:
            require(key not in obj, 'JSON_DUPLICATE_KEY')
            obj[key] = val
        return obj
    try:
        return json.loads(data, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Refusal('JSON_NUMBER')))
    except Refusal:
        raise
    except (ValueError, UnicodeError, RecursionError):
        raise Refusal('JSON_INVALID') from None


def encode(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode()


def path_ok(path):
    parts = path.split('/')
    require(len(parts) > 1 and parts[0] in ('entities', 'images') and
            all(x not in ('', '.', '..') for x in parts) and
            '\\' not in path and ':' not in path and '\x00' not in path,
            'PATH_UNSAFE')


def unpack(data):
    require(len(data) <= MAX_ARCHIVE, 'ARCHIVE_LIMIT')
    require(data[:8] == MAGIC and len(data) >= 12, 'ENVELOPE')
    length = struct.unpack('>I', data[8:12])[0]
    require(0 < length <= 32768 and 12+length < len(data), 'ENVELOPE')
    envelope = strict(data[12:12+length])
    require(envelope == {'version': 1, 'encrypted': False, 'salt': None, 'wrappedKey': None}, 'ENVELOPE_UNSUPPORTED')
    try:
        with zipfile.ZipFile(io.BytesIO(data[12+length:])) as z:
            infos = z.infolist()
            require(len(infos) <= MAX_ENTRIES, 'ENTRY_LIMIT')
            require(len({i.filename for i in infos}) == len(infos), 'ZIP_DUPLICATE')
            require(len({i.filename.casefold() for i in infos}) == len(infos), 'PATH_ALIAS')
            require(sum(i.file_size for i in infos) <= MAX_TOTAL, 'TOTAL_LIMIT')
            for i in infos:
                require(i.file_size <= MAX_FILE, 'RESOURCE_LIMIT')
                require(not i.is_dir() and (i.external_attr >> 16) & 0o170000 != 0o120000, 'ENTRY_KIND')
                require(i.filename in ('manifest.json','inventory.jsonl','preferences.json') or i.filename.startswith('files/'), 'ENTRY_UNKNOWN')
                if i.filename.startswith('files/'):
                    path_ok(i.filename[6:])
            payload = {i.filename: z.read(i) for i in infos}
    except Refusal:
        raise
    except (zipfile.BadZipFile, EOFError, RuntimeError, ValueError):
        raise Refusal('ZIP_CORRUPT') from None
    require(all(k in payload for k in ('manifest.json','inventory.jsonl','preferences.json')), 'METADATA_MISSING')
    manifest = strict(payload['manifest.json'])
    require(set(manifest) == {'formatVersion','appVersionCode','appVersionName','createdAt','sourceFilesRoot','counts','fileCount','totalBytes','preferencesSha256'}, 'MANIFEST_FIELDS')
    require(manifest['formatVersion'] == 1 and manifest['appVersionCode'] == 84, 'VERSION')
    require(digest(payload['preferences.json']) == manifest['preferencesSha256'], 'PREFERENCES_HASH')
    prefs = strict(payload['preferences.json'])
    require(set(prefs) == {'novelAiToken','fishAudioKey','communitySession','brushSize','brushType'}, 'PREFERENCES_FIELDS')
    require(all(prefs[k] is None for k in ('novelAiToken','fishAudioKey','communitySession')), 'CREDENTIAL_REJECTED')
    inventory = [strict(line) for line in payload['inventory.jsonl'].splitlines()]
    files, counts = {}, {}
    for row in inventory:
        require(set(row) == {'path','size','sha256'}, 'INVENTORY_FIELDS')
        p = row['path']; path_ok(p)
        require(p not in files, 'INVENTORY_DUPLICATE')
        require('files/'+p in payload, 'RESOURCE_MISSING')
        raw = payload['files/'+p]
        require(len(raw) == row['size'] and digest(raw) == row['sha256'], 'RESOURCE_HASH')
        files[p] = raw
        if p.startswith('entities/'):
            kind = p.split('/')[1].removesuffix('.json')
            counts[kind] = counts.get(kind,0)+1
    require(len(payload) == len(files)+3 and len(files) == manifest['fileCount'] and
            sum(map(len, files.values())) == manifest['totalBytes'] and counts == manifest['counts'], 'INVENTORY_CLOSURE')
    return files, manifest, prefs


def pack(files, manifest, prefs, duplicate=None, bad_hash=False):
    """New output archive, not merely returning the original bytes."""
    manifest = copy.deepcopy(manifest)
    inventory, counts = [], {}
    for path, raw in sorted(files.items()):
        inventory.append({'path':path,'size':len(raw),'sha256':digest(raw)})
        if path.startswith('entities/'):
            kind=path.split('/')[1].removesuffix('.json'); counts[kind]=counts.get(kind,0)+1
    if bad_hash:
        inventory[-1]['sha256']='0'*64
    pref_bytes=encode(prefs)
    manifest.update(counts=counts,fileCount=len(files),totalBytes=sum(map(len,files.values())),
                    preferencesSha256=digest(pref_bytes),createdAt=manifest['createdAt']+1)
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as z:
        for path,raw in sorted(files.items()):
            z.writestr('files/'+path,raw)
        if duplicate:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                z.writestr('files/'+duplicate,files[duplicate])
        z.writestr('preferences.json',pref_bytes)
        z.writestr('inventory.jsonl',b'\n'.join(encode(x) for x in inventory)+b'\n')
        z.writestr('manifest.json',encode(manifest))
    header=encode({'version':1,'encrypted':False,'salt':None,'wrappedKey':None})
    return MAGIC+struct.pack('>I',len(header))+header+stream.getvalue()


def schema_of(files):
    # Trusted synthetic reference, separately produced with official serializers and validator.
    policy={}
    for path,raw in files.items():
        if path.startswith('entities/'):
            kind=path.split('/')[1].removesuffix('.json')
            policy.setdefault(kind,set()).update(strict(raw))
    return policy


def validate(files, policy):
    records, identities, refs = {}, set(), []
    for path,raw in files.items():
        if not path.startswith('entities/'):
            continue
        kind=path.split('/')[1].removesuffix('.json')
        require(kind in policy, 'ENTITY_UNKNOWN')
        obj=strict(raw)
        require(set(obj) == policy[kind], 'ENTITY_FIELDS')
        identity=(kind,obj.get('id',kind))
        require(identity[1] and identity not in identities,'ID_DUPLICATE')
        identities.add(identity); records[identity]=obj
        require(not obj.get('apiKey') and not obj.get('siliconFlowApiKey'),'CREDENTIAL_REJECTED')
        def resource(value, field):
            if value is None or value == '': return
            require(isinstance(value,str) and value.startswith('chatbar-backup-file:'),'RESOURCE_EXTERNAL')
            target=value.removeprefix('chatbar-backup-file:'); path_ok(target)
            require(target in files,'RESOURCE_MISSING')
            refs.append({'record':list(identity),'field':field,'token':value,'entry':target,
                         'bytes':len(files[target]),'sha256':digest(files[target])})
        if kind == 'moment_posts':
            resource(obj['senderAvatar'],'senderAvatar'); resource(obj['imagePath'],'imagePath')
            meta=obj['generatedImageMetadata']
            if meta is not None:
                require(set(meta)=={'imagePath','baseCaption','characterPrompts','negativePrompt','sizePreset','width','height','stylePrompt'},'NESTED_FIELDS')
                require(meta['characterPrompts']==[],'FIXTURE_SCOPE')
                resource(meta['imagePath'],'generatedImageMetadata.imagePath')
            if obj['generationCheckpoint']:
                checkpoint=strict(obj['generationCheckpoint'])
                require(set(checkpoint)=={'decision','draft','imagePrompt'} and checkpoint['imagePrompt'] is None,'CHECKPOINT_SCOPE')
                require(set(checkpoint['decision'])=={'shouldPost','reason'} and
                        set(checkpoint['draft'])=={'shouldPost','senderName','text','imageBrief','isPrivate','likeTier','reason'},'CHECKPOINT_FIELDS')
        if kind == 'character_cards':
            resource(obj['avatar'],'avatar')
            for i,c in enumerate(obj['characters']): resource(c['appearanceImage'],f'characters[{i}].appearanceImage')
    for (kind, rid), obj in records.items():
        if kind not in ('moment_posts','moment_tasks'): continue
        historical=(kind=='moment_posts' and rid=='historical' and obj['characterCardId']=='deleted-card' and
                    obj['sessionId']=='deleted-session' and obj['senderCharacterId']=='deleted-sender')
        if not historical:
            require(('character_cards',obj['characterCardId']) in records and ('chat_sessions',obj['sessionId']) in records,'ASSOCIATION_ORPHAN')
            require(records[('chat_sessions',obj['sessionId'])]['characterCardId']==obj['characterCardId'],'ASSOCIATION_MISMATCH')
            if kind=='moment_posts' and obj['senderCharacterId'] is not None:
                require(obj['senderCharacterId'] in {c['id'] for c in records[('character_cards',obj['characterCardId'])]['characters']},'SENDER_ORPHAN')
        if kind=='moment_tasks':
            require(obj['status'] in ('PENDING','RUNNING','COMPLETED','SKIPPED','FAILED'),'TASK_ENUM')
            if obj['postId'] is not None:
                post=records.get(('moment_posts',obj['postId']))
                require(post is not None,'POSTID_DANGLING')
                require(post['characterCardId']==obj['characterCardId'] and post['sessionId']==obj['sessionId'],'POSTID_MISMATCH')
    return refs


def save(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as f:
        f.write(data); f.flush(); os.fsync(f.fileno())


def fault(point, selected):
    if point == selected: os._exit(71)


def preserve(source, store, policy, crash=''):
    require((store/'.synthetic-poc').is_file(),'SCOPE_REQUIRED')
    raw=source.read_bytes(); files,manifest,prefs=unpack(raw); refs=validate(files,policy)
    gen=store/('generation-'+uuid.uuid4().hex); gen.mkdir()
    save(gen/'owner.json',encode({'kind':'synthetic-experiment','sourceSha256':digest(raw)}))
    fault('preserve-start',crash)
    save(gen/'source.cbbackup',raw)
    for index,(path,data) in enumerate(sorted(files.items())):
        save(gen/'payload'/path,data)
        if index==0: fault('preserve-copy',crash)
    seal={'sourceSha256':digest(raw),'sourceBytes':len(raw),'manifest':manifest,'preferences':prefs,
          'recordsAndResources':[{'path':p,'bytes':len(b),'sha256':digest(b)} for p,b in sorted(files.items())],
          'references':refs,'historicalPolicy':'exact-synthetic-deleted-card-session-sender-only','runtimeRegistration':False}
    save(gen/'seal.json',encode(seal))
    fault('preserve-seal',crash)
    save(store/'pointer.next',encode({'generation':gen.name,'sealSha256':digest((gen/'seal.json').read_bytes())}))
    fault('preserve-before-publish',crash)
    os.replace(store/'pointer.next',store/'current.json')
    return gen


def verified_generation(store):
    p=strict((store/'current.json').read_bytes()); gen=store/p['generation']
    require(gen.parent==store and gen.name.startswith('generation-'),'SEAL_PATH')
    raw=(gen/'seal.json').read_bytes(); require(digest(raw)==p['sealSha256'],'SEAL_HASH')
    seal=strict(raw)
    require(digest((gen/'source.cbbackup').read_bytes())==seal['sourceSha256'],'SOURCE_CHANGED')
    files={}
    for row in seal['recordsAndResources']:
        path_ok(row['path']); b=(gen/'payload'/row['path']).read_bytes()
        require(len(b)==row['bytes'] and digest(b)==row['sha256'],'SEALED_RESOURCE_CHANGED')
        files[row['path']]=b
    return files,seal


def restore_export(store, destination, policy, crash=''):
    files,seal=verified_generation(store); validate(files,policy)
    # Independent synthetic restoration target. Nothing under production data roots.
    stage=destination/('restored-'+uuid.uuid4().hex); stage.mkdir(parents=True)
    for index,(p,b) in enumerate(sorted(files.items())):
        save(stage/p,b)
        if index==0: fault('restore-copy',crash)
    restored={p:(stage/p).read_bytes() for p in files}
    require(restored==files,'RESTORE_BYTES')
    fault('restore-verified',crash)
    output=pack(restored,seal['manifest'],seal['preferences'])
    require(unpack(output)[0]==files,'REEXPORT_BYTES')
    save(destination/'export.part',output)
    fault('export-before-publish',crash)
    os.replace(destination/'export.part',destination/'host-reexport.cbbackup')
    return output


def run(source, scope):
    scope.mkdir(exist_ok=False)
    (scope/'.synthetic-poc').write_text('synthetic-only')
    original=source.read_bytes(); files,manifest,prefs=unpack(original); policy=schema_of(files)
    (scope/'trusted-fixture-fields.json').write_bytes(encode({k:sorted(v) for k,v in policy.items()}))
    cases=[]
    class Cases(unittest.TestCase):
        def test_positive_preservation_and_reexport(self):
            gen=preserve(source,scope,policy)
            output=restore_export(scope,scope/'output',policy)
            self.assertNotEqual(digest(original),digest(output))
            self.assertTrue(files==unpack(output)[0], 'SEALED_BYTES_DIFFER')
            self.assertTrue(original==(gen/'source.cbbackup').read_bytes(), 'SOURCE_BYTES_DIFFER')

    variants=[]
    def mutate(name, category, fn):
        changed=copy.deepcopy(files); fn(changed)
        variants.append((name,category,pack(changed,manifest,prefs)))
    def objedit(path,fn):
        def edit(fs):
            obj=strict(fs[path]); fn(obj); fs[path]=encode(obj)
        return edit
    post='entities/moment_posts/text.json'; task='entities/moment_tasks/pending.json'
    mutate('duplicate_post_id','ID_DUPLICATE',lambda f:f.update({'entities/moment_posts/duplicate.json':f[post]}))
    mutate('duplicate_task_id','ID_DUPLICATE',lambda f:f.update({'entities/moment_tasks/duplicate.json':f[task]}))
    mutate('duplicate_json_key','JSON_DUPLICATE_KEY',lambda f:f.update({post:f[post][:-1]+b',"id":"text"}'}))
    mutate('missing_image','RESOURCE_MISSING',lambda f:f.pop('images/shared.png'))
    mutate('dangling_post','POSTID_DANGLING',objedit(task,lambda o:o.update(postId='missing')))
    mutate('orphan','ASSOCIATION_ORPHAN',objedit(post,lambda o:o.update(characterCardId='other-deleted-card')))
    mutate('unknown_field','ENTITY_FIELDS',objedit(post,lambda o:o.update(futureField='synthetic-body-canary')))
    mutate('future_enum','TASK_ENUM',objedit(task,lambda o:o.update(status='FUTURE')))
    for name,value in [('absolute','C:/outside.png'),('content','content://synthetic/image'),('remote','https://invalid.example/image')]:
        mutate(name,'RESOURCE_EXTERNAL',objedit(post,lambda o,v=value:o.update(senderAvatar=v)))
    mutate('resource_limit','RESOURCE_LIMIT',lambda f:f.update({'images/large.png':b'x'*(MAX_FILE+1)}))
    variants += [('duplicate_zip','ZIP_DUPLICATE',pack(files,manifest,prefs,duplicate=post)),
                 ('wrong_hash','RESOURCE_HASH',pack(files,manifest,prefs,bad_hash=True)),
                 ('truncated','ZIP_CORRUPT',original[:-40]),('bad_magic','ENVELOPE',b'INVALID!'+original[8:])]
    mutate('post_mismatch','POSTID_MISMATCH',objedit(task,lambda o:o.update(postId='historical')))
    mutate('nested_unknown','NESTED_FIELDS',objedit('entities/moment_posts/image.json',lambda o:o['generatedImageMetadata'].update(future='synthetic')))
    mutate('traversal','PATH_UNSAFE',lambda f:f.update({'images/../outside.png':b'synthetic'}))
    variants.append(('archive_limit','ARCHIVE_LIMIT',b'0'*(MAX_ARCHIVE+1)))
    for name,category,data in variants:
        def test(self,n=name,c=category,b=data):
            item=scope/'negative'/f'{n}.cbbackup'; save(item,b)
            old=(scope/'current.json').read_bytes()
            with self.assertRaises(Refusal) as result: preserve(item,scope,policy)
            self.assertEqual(c,str(result.exception)); self.assertEqual(old,(scope/'current.json').read_bytes())
            self.assertTrue(b==item.read_bytes(),'NEGATIVE_SOURCE_CHANGED'); self.assertTrue(original==source.read_bytes(),'SOURCE_CHANGED')
            cases.append({'case':n,'expected':c,'observed':str(result.exception),'sourceUnchanged':True,'oldPointerUnchanged':True})
        setattr(Cases,'test_z_'+name,test)
    # Explicit ordered positive first; all negative attempts retain the old sealed generation.
    suite=unittest.TestSuite([Cases('test_positive_preservation_and_reexport')]+[Cases('test_z_'+n) for n,_,_ in variants])
    class XMLResult(unittest.TextTestResult):
        def __init__(self,*args): super().__init__(*args); self.xml=ET.Element('testsuite',name='SyntheticMomentsPreservation')
        def startTest(self,t): super().startTest(t); self.node=ET.SubElement(self.xml,'testcase',name=t._testMethodName)
        def addFailure(self,t,e): self.failures.append((t,e[0].__name__)); ET.SubElement(self.node,'failure',type=e[0].__name__)
        def addError(self,t,e): self.errors.append((t,e[0].__name__)); ET.SubElement(self.node,'error',type=e[0].__name__)
    result=unittest.TextTestRunner(verbosity=2,resultclass=XMLResult).run(suite)
    result.xml.set('tests',str(result.testsRun)); result.xml.set('failures',str(len(result.failures))); result.xml.set('errors',str(len(result.errors)))
    ET.ElementTree(result.xml).write(scope/'junit.xml',encoding='utf-8',xml_declaration=True)
    require(result.wasSuccessful(),'TESTS_FAILED')
    child_results=[]
    for point in ['preserve-start','preserve-copy','preserve-seal','preserve-before-publish','restore-copy','restore-verified','export-before-publish']:
        child=scope/('crash-'+point); child.mkdir(); (child/'.synthetic-poc').write_text('synthetic-only')
        preserve(source,child,policy)
        old=(child/'current.json').read_bytes()
        dest=child/'output'; dest.mkdir(); save(dest/'host-reexport.cbbackup',b'OLD_SYNTHETIC_OUTPUT')
        cmd=[sys.executable,__file__,'child',str(source),str(child),point]
        p=subprocess.run(cmd,capture_output=True,text=True)
        require(p.returncode==71,'CHILD_EXIT')
        require((child/'current.json').read_bytes()==old and (dest/'host-reexport.cbbackup').read_bytes()==b'OLD_SYNTHETIC_OUTPUT','PARTIAL_PUBLISHED')
        verified_generation(child)
        require(source.read_bytes()==original,'SOURCE_CHANGED')
        recovery_cmd=[sys.executable,__file__,'recover',str(source),str(child)]
        recovery=subprocess.run(recovery_cmd,capture_output=True,text=True)
        require(recovery.returncode==0,'RECOVERY_EXIT')
        require(unpack((child/'recovered-output/host-reexport.cbbackup').read_bytes())[0]==files,'RECOVERY_BYTES')
        child_results.append({'phase':point,'command':cmd,'exitCode':p.returncode,'recoveryCommand':recovery_cmd,'recoveryExitCode':recovery.returncode,'oldGenerationVerified':True,'oldExportUnchanged':True,'sourceUnchanged':True})
    (scope/'negative-results.json').write_bytes(encode(cases))
    (scope/'interruptions.json').write_bytes(encode(child_results))
    print('SYNTHETIC_EXPERIMENT_COMPLETED',result.testsRun,'tests',len(child_results),'child interruptions')


if __name__=='__main__':
    if sys.argv[1]=='run': run(Path(sys.argv[2]),Path(sys.argv[3]))
    elif sys.argv[1]=='child':
        source,store=map(Path,sys.argv[2:4]); crash=sys.argv[4]; policy=schema_of(unpack(source.read_bytes())[0])
        if crash.startswith('preserve'): preserve(source,store,policy,crash)
        else: restore_export(store,store/'output',policy,crash)
    elif sys.argv[1]=='recover':
        source,store=map(Path,sys.argv[2:4]); policy=schema_of(unpack(source.read_bytes())[0])
        restore_export(store,store/'recovered-output',policy)
    else: raise SystemExit('Use run or child')
