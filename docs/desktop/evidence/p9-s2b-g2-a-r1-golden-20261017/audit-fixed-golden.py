"""Read-only independent raw-byte and cross-reference audit of frozen resources."""
from pathlib import Path
import hashlib, json
base=Path('app/desktopApp/src/test/resources/g2-a-j-v1-golden')
sha=lambda b:hashlib.sha256(b).hexdigest()
manifestBytes=(base/'manifest.json').read_bytes()
assert sha(manifestBytes)=='b4afa563dc58b9830188d0d8d76b1d08a42259cfba795aacebaa2d4d8ad09bde'
m=json.loads(manifestBytes);root=base/'root'
raw={entry['path']:(root/entry['path']).read_bytes() for entry in m['files']}
assert set(raw)=={p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file()}
for entry in m['files']:
    b=raw[entry['path']];assert len(b)==entry['bytes'] and sha(b)==entry['sha256']
controls={p:json.loads(raw[p]) for p in m['controlKinds']}
B='entities/document_index_transactions/history/'+m['txId']
D='entities/document_index_transactions'
def evidence(path):return dict(bytes=len(raw[path]),sha256=sha(raw[path]))
intent,seal,marker,receipt,head=(controls[f'{B}/intent.json'],controls[f'{B}/seal.json'],controls[f'{B}/commit.json'],controls[f'{B}/receipt.json'],controls[f'{D}/heads/golden_card.json'])
for path,kind in m['controlKinds'].items():
    c=controls[path];assert c['format']=='CCB_DOCUMENT_INDEX_J' and c['version']==1 and c['kind']==kind
    if kind!='DOMAIN':assert c['txId']==m['txId']
for field,path in dict(oldCard='old/card.json',newCard='stage/card.json').items():assert intent[field]==evidence(f'{B}/{path}')
assert intent['source']==dict(path='documents/golden.md',**evidence(f'{B}/source.bin'))
for name,field in [('old-set.jsonl','oldSet'),('new-set.jsonl','newSet'),('unrelated.jsonl','unrelated')]:
    path=f'{B}/{name}';b=raw[path];assert not b or b.endswith(b'\n')
    rows=[json.loads(line) for line in b.splitlines()]
    assert [r['storageId'] for r in rows]==m['jsonlOrder'][path]
    assert intent[field]['file']==evidence(path)
    assert intent[field]['records']==len(rows)
    assert intent[field]['payloadBytes']==sum(r['bytes'] for r in rows)
    for r in rows:
        v=f"{B}/stage/vectors/{r['storageId']}.json";assert dict(bytes=r['bytes'],sha256=r['sha256'])==evidence(v)
for field,path in dict(intentFile='intent.json',oldManifestFile='old-set.jsonl',newManifestFile='new-set.jsonl',unrelatedManifestFile='unrelated.jsonl',oldCardFile='old/card.json',newCardFile='stage/card.json',sourceFile='source.bin').items():assert seal[field]==evidence(f'{B}/{path}')
for c,refs in [(marker,['intent','seal']),(receipt,['intent','seal','commit']),(head,['intent','seal','commit','receipt'])]:
    for ref in refs:assert c[ref+'File']==evidence(f'{B}/{ref}.json')
assert intent['expectedHead'] is None and seal['oldHeadFile'] is None
assert marker['predecessor'] is receipt['predecessor'] is head['predecessor'] is None
assert intent['next']==marker['next']==receipt['next']==head['state']
assert head['state']['revision']==1 and head['state']['card']==evidence(f'{B}/stage/card.json')
assert raw[f'{B}/stage/card.json']==raw['entities/character_cards/golden_card.json']
assert raw[f'{B}/source.bin']==raw['documents/golden.md']
for identifier in ['golden_vector_01','golden_vector_02']:
    assert raw[f'{B}/stage/vectors/{identifier}.json']==raw[f'entities/vector_chunks/{identifier}.json']
report=dict(filesVerified=len(raw),controlFilesVerified=len(controls),manifestBytes=len(manifestBytes),manifestSha256=sha(manifestBytes),allRawBytesAndReferencesMatch=True,productionParserOrSerializerUsed=False)
out=Path('app/desktopApp/build/p9-s2b-g2-a-r1-golden-20261017/golden-independent-audit.json')
out.write_bytes((json.dumps(report,indent=2)+'\n').encode('utf-8'))
print(json.dumps(report))
