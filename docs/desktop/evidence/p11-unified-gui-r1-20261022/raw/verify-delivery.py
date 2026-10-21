from pathlib import Path
import hashlib,json,zipfile,subprocess,sys
root=Path.cwd()
scope=root/'app/desktopApp/build/p11-gui-r1-20261022'
result=json.loads((scope/'12-windows-package/result.json').read_text(encoding='utf-8-sig'))
sha=result['sourceCommit']
dest=scope/('windows-frozen-'+sha[:8])
proof=json.loads((dest/'verification.json').read_text(encoding='utf-8'))
manifest=json.loads((dest/'package-manifest.json').read_text(encoding='utf-8'))
archive=Path(proof['portableZip']['path'])
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert set(z.namelist())=={'ChatChatBarDesktop/'+row['path'] for row in manifest}
    assert len(z.namelist())==len(manifest)
    for row in manifest:
        data=z.read('ChatChatBarDesktop/'+row['path'])
        assert len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256']
proof.update(zipVerifiedFiles=len(manifest),zipMissing=0,zipExtra=0,zipMismatch=0)
(dest/'delivery-verification.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
evidence=root/'docs/desktop/evidence/p11-unified-gui-r1-20261022'
rows=json.loads((evidence/'evidence-manifest.json').read_text(encoding='utf-8'))
evidence_ref = sys.argv[1] if len(sys.argv) > 1 else 'HEAD'
prefix = '' if evidence_ref == 'INDEX' else evidence_ref
for row in rows:
    data=subprocess.check_output(['git','show',prefix+':'+evidence.relative_to(root).as_posix()+'/'+row['path']],cwd=root)
    assert len(data)==row['size'] and hashlib.sha256(data).hexdigest()==row['sha256'],row['path']
print(json.dumps({'sourceCommit':sha,'runtimeVerified':proof['verified'],'zipVerified':len(manifest),'committedRawEvidenceVerified':len(rows),'zipSha256':proof['portableZip']['sha256']},ensure_ascii=False))
