"""Independent all-field and resource comparison of original/host/official return archives."""
import json
import sys
import re
from pathlib import Path
from poc import unpack, digest, strict, validate, schema_of, require

source, host, returned, output = map(Path,sys.argv[1:5])
a, am, _ = unpack(source.read_bytes())
b, bm, _ = unpack(host.read_bytes())
c, cm, _ = unpack(returned.read_bytes())
require(a == b, 'HOST_RAW_BYTES_DIFFER')
require(set(a) == set(c), 'OFFICIAL_ENTRY_SET_DIFFER')
policy=schema_of(a)
ar=validate(a,policy); cr=validate(c,policy)
def canonical(value):
    # Known registered image fields only. No removal of unknown fields or business data.
    if isinstance(value,dict): return {k:canonical(v) for k,v in value.items()}
    if isinstance(value,list): return [canonical(v) for v in value]
    return value
rows=[]
for path in sorted(a):
    if path.startswith('entities/'):
        require(canonical(strict(a[path]))==canonical(strict(c[path])),'OFFICIAL_LOGICAL_FIELDS_DIFFER')
        rows.append({'path':path,'allLogicalFieldsEqual':True,'originalRawSha256':digest(a[path]),'officialReturnRawSha256':digest(c[path])})
    else:
        require(a[path]==c[path],'OFFICIAL_RESOURCE_BYTES_DIFFER')
        rows.append({'path':path,'bytes':len(a[path]),'sha256':digest(a[path]),'officialReturnSha256':digest(c[path])})
# Archive inventory iteration order is not a reference identity. Compare every
# complete edge, retaining duplicates, without normalizing any entity field.
edge_key=lambda row: json.dumps(row,sort_keys=True,separators=(',',':'))
require(sorted(ar,key=edge_key)==sorted(cr,key=edge_key),'REFERENCE_MAP_DIFFER')
path_rows=[]
if len(sys.argv)==7:
    for label, directory, manifest, files, refs in (
        ('original',Path(sys.argv[5]),am,a,ar),
        ('official-return',Path(sys.argv[6]),cm,c,cr)):
        for ref in refs:
            kind, identity=ref['record']
            obj=strict((directory/'entities'/kind/(identity+'.json')).read_bytes())
            value=obj
            for part in re.findall(r'[^.\[\]]+',ref['field']):
                value=value[int(part)] if isinstance(value,list) else value[part]
            expected=manifest['sourceFilesRoot']+'/'+ref['entry']
            # Official BackupDataRegistry.target canonicalizes /data/user/0 to
            # /data/data on this emulator. Keep both paths in the proof; allow
            # only that exact package-root alias, not arbitrary path rewriting.
            canonical_expected=expected.replace('/data/user/0/com.example.chatbar/',
                '/data/data/com.example.chatbar/',1)
            require(value in (expected,canonical_expected),'RESTORED_PATH_MAPPING_DIFFER')
            require((directory/ref['entry']).read_bytes()==files[ref['entry']], 'RESTORED_RESOURCE_DIFFER')
            path_rows.append({'stage':label,'record':ref['record'],'field':ref['field'],
                'physicalPath':value,'manifestBasedPath':expected,'entry':ref['entry'],'token':ref['token'],
                'bytes':ref['bytes'],'sha256':ref['sha256']})
output.write_text(json.dumps({'verdict':'FEASIBLE_WITH_PROVEN_SCOPE','officialFirstCreate':am,
    'officialSecondCreate':cm,'hostIsNewArchive':digest(source.read_bytes())!=digest(host.read_bytes()),
    'rows':rows,'reversiblePathRule':'token entry -> official targetFilesRoot/entry -> same token entry',
    'references':ar,'actualPhysicalPathMappings':path_rows,
    'proofScope':'exact five-post/five-task/two-image synthetic fixture; not all future schema'},indent=2),encoding='utf-8')
print('ALL_FIELDS_AND_RESOURCES_EQUAL',len(rows),'entries',len(ar),'references')
