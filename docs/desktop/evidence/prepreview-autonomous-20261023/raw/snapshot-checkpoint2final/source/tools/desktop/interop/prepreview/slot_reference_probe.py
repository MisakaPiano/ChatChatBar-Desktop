"""Private category-only diagnostic for an already CRC-checked v8 SaveSlot.

Not an admission/decoder/restore authority. Keeps compact reference identities in
memory; never prints a source value, name, path, digest or exception text.
"""
import json, sys, zipfile
from pathlib import Path
from archive_census import source_handle, digest, census, strict_object, Refused

def references(z):
    def record(data):
        if len(data)>1024*1024: raise Refused('RECORD_LIMIT')
        value=json.loads(data.decode('utf-8','strict'),object_pairs_hook=strict_object)
        if not isinstance(value,dict): raise Refused('RECORD_TYPE')
        return value
    def rows(name):
        if name not in z.namelist(): return
        with z.open(name) as f:
            for count in range(20001):
                b=f.readline(1024*1024+1)
                if not b: return
                if count==20000: raise Refused('RECORD_COUNT')
                if b.strip(): yield record(b)
    if z.getinfo('manifest.json').file_size>256*1024:raise Refused('MANIFEST_LIMIT')
    m=record(z.read('manifest.json'))
    if m.get('schemaVersion')!=8: raise Refused('SCHEMA')
    counts={}
    def hit(code): counts[code]=counts.get(code,0)+1
    ref=m.get('packageRef') or {}
    filename=ref.get('fileName','')
    if len(filename)>300 or any(c in filename for c in '/\\:') or filename in ('.','..'):hit('PACKAGE_FILENAME')
    media={};used=set();messages={};derived=[];turns=set()
    for name in z.namelist():
        if name.startswith(('media/images/','media/audio/')) and not name.endswith('/'):
            key=name.rsplit('.',1)[0]
            if key in media:hit('MEDIA_ALIAS')
            media[key]=name
    def resource(value,prefix,folder):
        if not isinstance(value,str) or not value.startswith(prefix):hit('RESOURCE_PREFIX');return
        key=folder+value[len(prefix):]
        if key not in media:hit('RESOURCE_MISSING')
        else:used.add(key)
    def image(value):
        if isinstance(value,str) and value.startswith('chatbar-save-slot-omitted-image:'):return
        resource(value,'chatbar-save-slot-image:','media/images/')
    for v in rows('messages.jsonl'):
        images=v.get('images',[])
        for value in images:image(value)
        if any(x.get('imagePath') not in images for x in v.get('generatedImageMetadata',[])):hit('GENERATED_IMAGE_NOT_IN_IMAGES')
        if v.get('generatedFromMessageId') is not None:derived.append(v['generatedFromMessageId'])
        versions=set(v.get('alternativeVersionIds',[]))
        if v.get('currentAlternativeVersionId') is not None:versions.add(v['currentAlternativeVersionId'])
        messages[v['id']]=(versions,v.get('sourceTurnId'), bool(v.get('alternativeVersionIds')) or (not v.get('alternatives') and bool(v.get('currentAlternativeVersionId'))))
        if v.get('sourceTurnId') is not None:turns.add(v['sourceTurnId'])
    for value in derived:
        if value not in messages:hit('DERIVED_MESSAGE_MISSING')
    for v in rows('rag.jsonl'):
        if v.get('sourceType')!='CHAT_MEMORY' or v.get('sourceId')!=m.get('sessionId'):hit('RAG_SOURCE')
        metadata=v.get('metadata',{})
        if metadata.get('sessionId') is not None and metadata['sessionId']!=m.get('sessionId'):hit('RAG_SESSION')
        ids=([v['messageId']] if v.get('messageId') is not None else [])+[x for x in metadata.get('messageIds','').split(',') if x.strip()]
        for value in ids:
            if value not in messages:hit('RAG_MESSAGE_MISSING')
        turn=metadata.get('sourceTurnId')
        if turn is not None:
            if turn not in turns:hit('RAG_TURN_MISSING')
            for value in ids:
                actual=messages.get(value,(None,None))[1]
                if actual is not None and actual!=turn:hit('RAG_TURN_CONFLICT')
    for v in rows('voices.jsonl'):
        mid=v.get('messageId')
        if mid not in messages:hit('VOICE_MESSAGE_MISSING')
        elif v.get('messageVersionId') is not None and v['messageVersionId'] not in messages[mid][0]:
            hit('VOICE_VERSION_MISSING')
            hit('VOICE_VERSION_EXPLICIT_SET_MISMATCH' if messages[mid][2] else 'VOICE_VERSION_LEGACY_REQUIRES_OFFICIAL_NORMALIZATION')
        resource(v.get('audioPath'),'chatbar-save-slot-audio:','media/audio/')
    if m.get('chatBackground') is not None:image(m['chatBackground'])
    if len(used)!=len(media):hit('UNREFERENCED_MEDIA')
    snap=m.get('memorySnapshot') or {};nodes=snap.get('nodes',[]);ids={v['id'] for v in nodes}
    for v in nodes:
        if v.get('sessionId')!=m.get('sessionId') or any(x not in ids for x in v.get('childIds',[])):hit('MEMORY_NODE_REFERENCE')
    state=snap.get('state') or {}
    for key in ('episodeNodeIds','arcNodeIds','eraNodeIds','legacyReferenceNodeIds'):
        if any(x not in ids for x in state.get(key,[])):hit('MEMORY_STATE_REFERENCE')
    return {'diagnostic':'PARTIAL_REFERENCE_CATEGORIES_ONLY','counts':counts,'restore_authorized':False}

def main():
    try:
        if len(sys.argv)!=2:raise Refused('SINGLE_FILE_REQUIRED')
        with source_handle(Path(sys.argv[1])) as f:
            if f.seek(0,2)>256*1024*1024:raise Refused('SOURCE_LIMIT')
            before=digest(f);census(f);f.seek(0)
            with zipfile.ZipFile(f) as z:result=references(z)
            if digest(f)!=before:raise Refused('SOURCE_CHANGED')
            result['source_bytes']='UNCHANGED'
            print(json.dumps(result))
    except Exception:
        print('SLOT_DIAGNOSTIC=REFUSED');return 2
    return 0

if __name__=='__main__':sys.exit(main())
