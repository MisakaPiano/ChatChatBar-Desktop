"""Cloud-only frozen public client validates exact privately prepared signed update bytes; never installs."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import re

def require(value, code):
    if not value: raise RuntimeError(code)

def main():
    require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop', 'EXPLICIT_REPOSITORY_CI_REQUIRED')
    control=Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True)
    request=json.loads((control/'.github/desktop-candidate-request.json').read_text(encoding='utf-8'))
    require(request.get('signedUpdateOnly') is True and request.get('reuseBuildRun'), 'EXACT_SIGNED_REUSE_REQUEST_REQUIRED')
    metadata=request['signedValidation']; require(metadata['kind']=='PUBLIC_SIGNED_METADATA_INPUTS_NOT_PRIVATE_KEYS', 'SIGNED_INPUT_KIND_REQUIRED')
    candidate_revision=metadata['candidateRevision']
    require(type(candidate_revision) is int and type(metadata['priorRevision']) is int and
            candidate_revision > metadata['priorRevision'], 'INCREASING_CANDIDATE_REVISION_REQUIRED')
    base=Path(os.environ['CCB_BUILD_BASE']).resolve(strict=True)
    require(base.parent==Path(base.anchor) and base.name.startswith('ccbpack-'), 'OWNED_CLOUD_BASE_REQUIRED')
    output=Path(os.environ['CCB_BUILD_OUTPUT']).resolve(strict=True);require(output.parent==base,'OWNED_OUTPUT_REQUIRED')
    case=base/'signed-validation';case.mkdir()
    update=control/'tools/desktop/update';sys.path.insert(0,str(update))
    frozen=update/'fixtures/desktop_update_1233.py'
    origin=json.loads(frozen.with_suffix('.origin.json').read_text(encoding='utf-8'))
    require(hashlib.sha256(frozen.read_bytes()).hexdigest()==origin['sha256'],'FROZEN_PUBLIC_CLIENT_BYTES_REQUIRED')
    old_transport=subprocess.check_output(['git','show',origin['source']+':tools/desktop/update/github_transport.py'],cwd=control)
    current_transport=subprocess.check_output(['git','show','HEAD:tools/desktop/update/github_transport.py'],cwd=control)
    require(old_transport==current_transport,'ORIGINAL_PUBLIC_TRANSPORT_REQUIRED')
    spec=importlib.util.spec_from_file_location('ccb_actual_frozen_public1233_client',frozen)
    client=importlib.util.module_from_spec(spec);sys.modules[spec.name]=client;spec.loader.exec_module(client)
    from tuf.ngclient.fetcher import FetcherInterface
    from tuf.api.exceptions import DownloadHTTPError
    from github_transport import endpoints
    folders=[]
    for key in ['prior','candidate']:
        rows=metadata[key];require(set(rows)=={'root.json','1.root.json','targets.json','snapshot.json','timestamp.json'},'EXACT_ROLE_SET_REQUIRED')
        folder=case/key;folder.mkdir();folders.append(folder)
        for name,row in rows.items():
            value=base64.b64decode(row['base64'],validate=True)
            require(len(value)<=65536 and hashlib.sha256(value).hexdigest()==row['sha256'],'SIGNED_INPUT_BYTES_REQUIRED')
            (folder/name).write_bytes(value)
    prior,candidate=folders;bootstrap=(update/'update-root.json').read_bytes()
    require(bootstrap==(prior/'root.json').read_bytes()==(candidate/'root.json').read_bytes(),'PUBLIC_BOOTSTRAP_UNCHANGED_REQUIRED')
    installer=output/'nsis/ChatChatBar-Setup.exe'
    expected=metadata['installerSha256'];length=metadata['installerBytes']
    require(installer.stat().st_size==length and hashlib.file_digest(installer.open('rb'),'sha256').hexdigest()==expected,'FINAL_INSTALLER_BYTES_REQUIRED')
    class LocalSignedBytes(FetcherInterface):
        def __init__(self): self.folder=prior; self.authorized=False
        def authorize(self,target):
            self.authorized=self.folder==candidate
            if self.authorized: require(target.length==length and target.hashes['sha256']==expected,'SIGNED_TARGET_BINDING_REQUIRED')
        def _fetch(self,url):
            root,targets=endpoints(client.REPOSITORY,'preview',client.METADATA_BRANCH)
            if url.startswith(root):
                name=url.removeprefix(root);require('/' not in name and '\\' not in name,'EXACT_ROLE_PATH_REQUIRED')
                path=self.folder/name
                if not path.is_file():raise DownloadHTTPError('metadata unavailable',404)
                yield path.read_bytes()
            elif url==targets+'desktop-installer.exe' and self.authorized:
                with installer.open('rb') as stream:
                    while block:=stream.read(1024*1024):yield block
            else: raise RuntimeError('UNEXPECTED_FETCH_REFUSED')
    installed=client.Installed(1233,'0.1.0-preview.1233',origin['source'],'preview',1,bootstrap)
    fetcher=LocalSignedBytes();cache=case/'cache'
    first=client.execute(installed,cache,fetcher=fetcher)
    before=json.loads((cache/'metadata/timestamp.json').read_text())['signed']['version']
    require(before==metadata['priorRevision']==15 and first['displayVersion']=='0.1.0-preview.1233','PUBLIC_PRIOR_CACHE_REQUIRED')
    fetcher.folder=candidate
    discovered=client.execute(installed,cache,fetcher=fetcher,expected_version=request['desktopVersion'])
    require(discovered['status']=='AVAILABLE' and discovered['sourceSha']==request['sourceSha'] and discovered['sha256']==expected,'CANDIDATE_DISCOVERY_REQUIRED')
    require(hashlib.sha256(discovered['notes'].encode()).hexdigest()==metadata['notesSha256'],'FINAL_NOTES_REQUIRED')
    downloaded=client.execute(installed,cache,download=True,fetcher=fetcher,expected_version=request['desktopVersion'])
    checked=client.execute(installed,cache,verify_file=Path(downloaded['installer']),fetcher=fetcher,expected_version=request['desktopVersion'])
    require(downloaded['status']==checked['status']=='DOWNLOADED_VERIFIED' and checked['sha256']==expected,'FULL_DOWNLOAD_AND_SECOND_VERIFY_REQUIRED')
    after=json.loads((cache/'metadata/timestamp.json').read_text())['signed']['version']
    require(after==candidate_revision and after>before,'INCREASING_CACHE_REVISION_REQUIRED')
    result=dict(kind='ACTUAL_FROZEN_PUBLIC1233_CLIENT_FINAL_SIGNED_CANDIDATE',passed=True,applicationSource=request['sourceSha'],
        controlSource=subprocess.check_output(['git','rev-parse','HEAD'],cwd=control,encoding='utf-8').strip(),
        frozenClientSource=origin['source'],frozenClientSha256=origin['sha256'],publicTransportUnchanged=True,
        version=request['desktopVersion'],priorRevision=before,currentRevision=after,installerBytes=length,installerSha256=expected,
        discovery=discovered['status'],download=downloaded['status'],secondVerification=checked['status'],notesMatch=True,publicRootUnchanged=True,
        privateKeyInputs=False,localMetadataFetcher=True,publicNetworkUpdateDiscovery=False,installerExecuted=False,
        applicationLaunched=False,dailyHAccessed=False,humanAcceptance=False)
    (output/'signed-client-result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':
    try:main()
    except Exception as error:
        fixed = str(error) if type(error) is RuntimeError and re.fullmatch('[A-Z0-9_]+',str(error)) else None
        result=dict(kind='SIGNED_CANDIDATE_VALIDATION_FAILED',errorType=type(error).__name__,code=fixed)
        if os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop':
            folder=Path(os.environ['CCB_BUILD_OUTPUT']).resolve(strict=True)
            if folder.name=='output' and folder.parent.name.startswith('ccbpack-'):
                (folder/'signed-client-result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(result))
        raise SystemExit(2)
