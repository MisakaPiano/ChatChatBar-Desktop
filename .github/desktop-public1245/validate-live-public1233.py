"""Cloud-only actual frozen public1233 client against the already-public1245 channel."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,urllib.request

def require(value,code):
    if not value:raise RuntimeError(code)

def main():
    require(os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop' and os.name=='nt','EXACT_WINDOWS_REPOSITORY_CLOUD_REQUIRED')
    control=Path(os.environ['GITHUB_WORKSPACE']).resolve(strict=True)
    base=Path(os.environ['CCB_PUBLIC_PROBE_BASE']).resolve(strict=True)
    require(base.parent==Path(base.anchor) and base.name.startswith('ccbpub-'),'OWN_CLOUD_BASE_REQUIRED')
    reports=control/'public-client-reports';reports.mkdir(exist_ok=True)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=control,encoding='utf-8').strip()
    request_path=control/'.github/desktop-public1245/request.json'
    request=json.loads(request_path.read_text(encoding='utf-8'))
    require(request['kind']=='EXPLICIT_PUBLIC1245_FROZEN1233_CLIENT_NETWORK_PROBE' and request['version']=='0.1.0-preview.1245','EXACT_PUBLIC_REQUEST_REQUIRED')
    require(request['source']=='4c7c6a6c61439fb30bcf4f28c08f09fe476bcfee' and request['metadataCommit']=='7a979b43efa78d6efd9892afdc3a375cc9d03c04','EXACT_PUBLIC_SOURCE_REQUIRED')
    source=dict(kind='PUBLIC1245_READONLY_CLOUD_PROBE_SOURCE',controlSource=head,applicationSource=request['source'],
        requestSha256=hashlib.sha256(request_path.read_bytes()).hexdigest(),
        helperSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        runtimeArtifactsSha256=hashlib.sha256((control/'tools/desktop/update/artifacts.json').read_bytes()).hexdigest(),
        candidateRebuilt=False,applicationLaunched=False,installerExecuted=False)
    (reports/'source.json').write_text(json.dumps(source,indent=2)+'\n',encoding='utf-8')
    update=control/'tools/desktop/update'
    frozen=update/'fixtures/desktop_update_1233.py'
    origin=json.loads(frozen.with_suffix('.origin.json').read_text(encoding='utf-8'))
    require(origin['source']=='2ba39c165cb42ca1b043e5c6b4a504406188c13e' and hashlib.sha256(frozen.read_bytes()).hexdigest()==origin['sha256']=='7752dc8538247d8a7cc9e9edf3fc69953d6bdd8ae1340167b93f10cbedb94c61','FROZEN_CLIENT_IDENTITY_REQUIRED')
    raw=subprocess.check_output(['git','show',origin['source']+':tools/desktop/update/github_transport.py'],cwd=control)
    blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
    require(blob==origin['unchangedTransportBlob']=='82307b31f10472eb7a4dfc963a7f354e1e2c2dd6' and hashlib.sha256(raw).hexdigest()=='f842290bc69f9bbb958cdcc89476f96df442e6e63ebefdf17717a597857b9dd2','FROZEN_TRANSPORT_IDENTITY_REQUIRED')
    modules=base/'frozen-public-modules';modules.mkdir()
    original=modules/'github_transport.py';original.write_bytes(raw)
    require('github_transport' not in sys.modules,'NEW_MODULE_NAMESPACE_REQUIRED')
    spec=importlib.util.spec_from_file_location('github_transport',original)
    transport=importlib.util.module_from_spec(spec);sys.modules[spec.name]=transport;spec.loader.exec_module(transport)
    spec=importlib.util.spec_from_file_location('ccb_actual_public1233_client',frozen)
    client=importlib.util.module_from_spec(spec);sys.modules[spec.name]=client;spec.loader.exec_module(client)
    require(client.GitHubFetcher is transport.GitHubFetcher and client.endpoints is transport.endpoints,'ORIGINAL_TRANSPORT_BINDING_REQUIRED')
    bootstrap=(update/'update-root.json').read_bytes()
    require(hashlib.sha256(bootstrap).hexdigest()==request['roles']['root.json']['sha256'],'PUBLIC_ROOT1_IDENTITY_REQUIRED')
    role_proofs=[]
    for ref in [request['metadataCommit'],'codex/update-metadata']:
        for name,expected in request['roles'].items():
            url='https://raw.githubusercontent.com/MisakaPiano/ChatChatBar-Desktop/'+ref+'/preview/windows-x86_64/metadata/'+name
            req=urllib.request.Request(url,headers={'User-Agent':'ChatChatBarDesktop-Public-Verification','Accept-Encoding':'identity'})
            with urllib.request.urlopen(req,timeout=45) as response:body=response.read(65537)
            require(len(body)==expected['bytes'] and hashlib.sha256(body).hexdigest()==expected['sha256'],'ACTUAL_PUBLIC_ROLE_BYTES_REQUIRED')
            role_proofs.append(dict(name=name,ref=ref,bytes=len(body),sha256=hashlib.sha256(body).hexdigest()))
    installed=client.Installed(1233,'0.1.0-preview.1233',origin['source'],'preview',1,bootstrap)
    cache=base/'own-public-update-cache'
    require(not cache.exists(),'NEW_TASK_CACHE_REQUIRED')
    phases=[]
    def progress(value):
        phase=value.get('phase')
        if phase and (not phases or phases[-1]!=phase):phases.append(phase)
    # No fetcher argument: these calls create the original GitHubFetcher and its normal TLS PoolManager.
    # No local metadata, target override, copied installer, credential or daily installation is supplied.
    discovered=client.execute(installed,cache,report=progress,expected_version=request['version'])
    require(discovered['status']=='AVAILABLE' and discovered['sourceSha']==request['source'],'ACTUAL_PUBLIC_DISCOVERY_REQUIRED')
    require(discovered['bytes']==request['installerBytes'] and discovered['sha256']==request['installerSha256'],'ACTUAL_SIGNED_PUBLIC_TARGET_REQUIRED')
    require(hashlib.sha256(discovered['notes'].encode()).hexdigest()==request['notesSha256'],'ACTUAL_PUBLIC_NOTES_REQUIRED')
    downloaded=client.execute(installed,cache,download=True,report=progress,expected_version=request['version'])
    require(downloaded['status']=='DOWNLOADED_VERIFIED' and not downloaded.get('reused',False),'ACTUAL_FRESH_NETWORK_DOWNLOAD_REQUIRED')
    installer=Path(downloaded['installer']).resolve(strict=True)
    require(installer.parent.parent==cache/'downloads','OWN_NETWORK_DOWNLOAD_LOCATION_REQUIRED')
    require(installer.stat().st_size==request['installerBytes'],'ACTUAL_NETWORK_FULL_LENGTH_REQUIRED')
    with installer.open('rb') as stream:require(hashlib.file_digest(stream,'sha256').hexdigest()==request['installerSha256'],'ACTUAL_NETWORK_FULL_HASH_REQUIRED')
    checked=client.execute(installed,cache,verify_file=installer,report=progress,expected_version=request['version'])
    require(checked['status']=='DOWNLOADED_VERIFIED' and checked['sha256']==request['installerSha256'],'ACTUAL_PUBLIC_SECOND_VERIFY_REQUIRED')
    require(json.loads((cache/'metadata/timestamp.json').read_text())['signed']['version']==27,'ACTUAL_AUTHENTICATED_PUBLIC_REVISION_REQUIRED')
    result=dict(kind='ACTUAL_FROZEN_PUBLIC1233_CLIENT_LIVE_PUBLIC1245_NETWORK_COMPLETE',passed=True,
        controlSource=head,applicationSource=request['source'],version=request['version'],metadataCommit=request['metadataCommit'],
        frozenClientSource=origin['source'],frozenClientSha256=origin['sha256'],frozenTransportBlob=blob,
        frozenTransportSha256=hashlib.sha256(raw).hexdigest(),loadedFrozenPublicTransport=True,
        explicitFetcherOverride=False,localMetadataFetcher=False,publicNetworkUpdateDiscovery=True,
        credentialInputs=False,syntheticInstalledIdentity=1233,newTaskOwnedCache=True,currentRevision=27,
        roles=role_proofs,discovery=discovered['status'],download=downloaded['status'],secondVerification=checked['status'],
        installerBytes=installer.stat().st_size,installerSha256=request['installerSha256'],notesSha256=request['notesSha256'],
        rootUnchanged=True,observedPhases=phases,candidateRebuilt=False,installerExecuted=False,
        applicationLaunched=False,dailyHAccessed=False,realUserDataAccessed=False,humanAcceptance=False,
        scope='Original old1233 Python client/transport live public channel with synthetic1233 Installed identity and fresh cloud-owned cache; no actual daily installation or native handoff')
    (reports/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['kind','passed','currentRevision','discovery','download','secondVerification','installerBytes','installerSha256']}))

if __name__=='__main__':
    try:main()
    except Exception as error:
        code=str(error) if type(error)is RuntimeError and str(error).replace('_','').isalnum() else None
        result=dict(kind='LIVE_PUBLIC1233_CLIENT_PROBE_FAILURE',passed=False,errorType=type(error).__name__,code=code)
        if os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop':
            folder=Path(os.environ['GITHUB_WORKSPACE'])/'public-client-reports';folder.mkdir(exist_ok=True)
            (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(result));raise SystemExit(2)
