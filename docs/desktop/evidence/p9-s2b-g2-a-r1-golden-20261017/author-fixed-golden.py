"""One-time authoring, not a test/runtime generator. No Kotlin/JWire/Inspector imports.
The emitted UTF-8 bytes and manifest are frozen test resources after authoring.
"""
from pathlib import Path
import json, hashlib, copy

base=Path('app/desktopApp/src/test/resources/g2-a-j-v1-golden')
payload=base/'root'
assert not base.exists()
files={}
def raw(o): return (json.dumps(o,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')
def sha(b): return hashlib.sha256(b).hexdigest()
def ev(b): return dict(bytes=len(b),sha256=sha(b))
def put(p,b): files[p]=b
def control(kind,**body): return raw(dict(format='CCB_DOCUMENT_INDEX_J',version=1,kind=kind,**body))
tx='66666666-6666-4666-8666-666666666666'
D='entities/document_index_transactions'
B=f'{D}/history/{tx}'
root=dict(lineageId='11111111-1111-4111-8111-111111111111',epoch=1)
writer=dict(authorityId='22222222-2222-4222-8222-222222222222',epoch=1)
config=dict(store='SINGLETON',id='default',generation='33333333-3333-4333-8333-333333333333',revision=1,publicFingerprint='b'*64,credentialGeneration='44444444-4444-4444-8444-444444444444',dimensions=2,embeddingKey='a'*64)
source=b'# North\nA fixed northern record.\n\n# South\nA fixed southern record.\n'
sourceClaim=dict(path='documents/golden.md',**ev(source))
doc=dict(id='golden_doc',fileName='golden.md',filePath=sourceClaim['path'],fileType='md',addedAt=1,contentHash=None,indexedHash=None,ragStatus='PENDING',ragChunkCount=0,ragIndexedAt=None,ragError=None)
old=dict(id='golden_card',name='Synthetic Golden',customDocuments=[doc],createdAt=1,updatedAt=1,ragIndexStatus='NOT_INDEXED',ragIndexDone=0,ragIndexTotal=0)
new=copy.deepcopy(old)
new.update(updatedAt=2,ragIndexStatus='COMPLETE',ragIndexDone=2,ragIndexTotal=2)
new['customDocuments'][0].update(contentHash=sha(source),indexedHash=sha(source),ragStatus='INDEXED',ragChunkCount=2,ragIndexedAt=2)
oldRaw,newRaw=raw(old),raw(new)
put(f'{D}/domain.json',control('DOMAIN',root=root,writer=writer))
put(f'{B}/old/card.json',oldRaw);put(f'{B}/stage/card.json',newRaw)
put(f'{B}/source.bin',source);put(sourceClaim['path'],source)
put('entities/character_cards/golden_card.json',newRaw)
vectors=[]
# Manually specified expected official section output; no ChunkingEngine calls.
for identifier,label,text,embedding in [('golden_vector_01','golden.md > North','【来源】golden.md > North\nA fixed northern record.',[1.0,0.0]),('golden_vector_02','golden.md > South','【来源】golden.md > South\nA fixed southern record.',[0.0,1.0])]:
    v=raw(dict(id=identifier,sourceType='DOCUMENT',sourceId='golden_card',messageId=None,content=text,embedding=embedding,metadata=dict(documentId='golden_doc',sourceLabel=label,fileName='golden.md',originalDocId='golden_doc',contentHash=sha(source),embeddingKey='a'*64),createdAt=1))
    vectors.append((identifier,v));put(f'{B}/stage/vectors/{identifier}.json',v);put(f'entities/vector_chunks/{identifier}.json',v)
rows=b''.join(raw(dict(storageId=i,**ev(b))) for i,b in vectors)
put(f'{B}/old-set.jsonl',b'');put(f'{B}/new-set.jsonl',rows);put(f'{B}/unrelated.jsonl',b'')
payloadBytes=sum(len(b) for _,b in vectors)
def manifest(b,count,size): return dict(file=ev(b),records=count,payloadBytes=size)
binding=dict(documentId='golden_doc',generation=1,producerTxId=tx,source=sourceClaim,config=config,set=dict(records=2,payloadBytes=payloadBytes,rowsSha256=sha(rows)))
state=dict(cardId='golden_card',revision=1,card=ev(newRaw),documents=[binding])
intent=control('INTENT',txId=tx,taskId='55555555-5555-4555-8555-555555555555',root=root,writer=writer,operation='INDEX_DOCUMENT',cardId='golden_card',documentId='golden_doc',source=sourceClaim,config=config,expectedHead=None,oldCard=ev(oldRaw),newCard=ev(newRaw),oldSet=manifest(b'',0,0),newSet=manifest(rows,2,payloadBytes),unrelated=manifest(b'',0,0),next=state)
put(f'{B}/intent.json',intent)
seal=control('SEAL',txId=tx,intentFile=ev(intent),oldManifestFile=ev(b''),newManifestFile=ev(rows),unrelatedManifestFile=ev(b''),oldCardFile=ev(oldRaw),newCardFile=ev(newRaw),sourceFile=ev(source),oldHeadFile=None)
put(f'{B}/seal.json',seal)
marker=control('COMMIT_MARKER',txId=tx,root=root,writer=writer,intentFile=ev(intent),sealFile=ev(seal),predecessor=None,next=state)
put(f'{B}/commit.json',marker)
receipt=control('HISTORY_RECEIPT',txId=tx,root=root,writer=writer,intentFile=ev(intent),sealFile=ev(seal),commitFile=ev(marker),predecessor=None,next=state)
put(f'{B}/receipt.json',receipt)
put(f'{D}/heads/golden_card.json',control('HEAD',txId=tx,root=root,writer=writer,intentFile=ev(intent),sealFile=ev(seal),commitFile=ev(marker),receiptFile=ev(receipt),predecessor=None,state=state))
dirs=set(['',f'{D}/active',f'{B}/old/vectors'])
for name in files:
    for parent in Path(name).parents: dirs.add(parent.as_posix() if parent.as_posix()!='.' else '')
for name,b in files.items():
    path=payload/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b)
controls={f'{D}/domain.json':'DOMAIN',**{f'{B}/{n}.json':k for n,k in [('intent','INTENT'),('seal','SEAL'),('commit','COMMIT_MARKER'),('receipt','HISTORY_RECEIPT')]},f'{D}/heads/golden_card.json':'HEAD'}
m=dict(profile='J-v1-independent-golden-1',txId=tx,cardId='golden_card',documentId='golden_doc',directories=sorted(dirs),files=[dict(path=p,**ev(b)) for p,b in sorted(files.items())],controlKinds=controls,jsonlOrder={f'{B}/old-set.jsonl':[],f'{B}/new-set.jsonl':[i for i,_ in vectors],f'{B}/unrelated.jsonl':[]})
base.joinpath('manifest.json').write_bytes(raw(m))
base.joinpath('.gitattributes').write_bytes(b'* -text\n')
base.joinpath('README.md').write_bytes(('''# Independent J-v1 synthetic golden 1

Frozen first-generation, two-section Markdown transaction for doc 134 section 9.
All identities, timestamps, fields, paths, JSONL order and raw UTF-8/LF bytes are fixed.
The complete manifest is pinned by its literal SHA-256 in the test source.
Empty directories are listed in the manifest and created only in the synthetic test root.

Authored once with Python standard JSON and SHA-256 from manually specified fields
and two literal chunk texts derived from reading the shared ChunkingEngine contract.
No production JWire, Inspector, Kotlin Entity serializer or runtime fixture builder
was used to produce these resources or expected values. No generator runs in tests.
The one-time authoring script is retained in independent validation evidence.
The manifest hashes actual file bytes, not JSON reserialization. Git text conversion
is disabled here so the frozen bytes survive checkout and commit unchanged.

Root/config/writer claims are synthetic and NOT YET TRUSTED. Structural completion
must remain business BLOCKED. This fixture grants no production authority.
''').encode('utf-8'))
print(json.dumps(dict(files=len(files),manifestSha256=sha(raw(m)),manifestBytes=len(raw(m)))))
