from pathlib import Path
import base64,hashlib,json,os,subprocess,sys

def main():
    assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop' and os.name=='nt'
    root=Path(os.environ['GITHUB_WORKSPACE']);base=Path(os.environ['CCB_ARGV_PROBE_BASE'])
    assert base.parent==Path(base.anchor) and base.name.startswith('ccbargv-')
    reports=root/'argv-reports';reports.mkdir(exist_ok=True)
    source='db7ee78387f150be806e79d42c216e3fa8aa9694'
    control=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    original=base/'original1244';original.mkdir()
    script=original/'desktop_update.py'
    raw=subprocess.check_output(['git','show',source+':tools/desktop/update/desktop_update.py'],cwd=root)
    windows=raw.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n')
    assert hashlib.sha256(windows).hexdigest()=='5efbbdbcdb37fb042d08dac3c0e2e34cb1b15f24619c20d3a4a46e28b3af5862'
    script.write_bytes(windows)
    runtime=base/'runtime';python=runtime/'python.exe'
    # These library modules are unchanged between the installed1244 and control parent.
    for name in ['github_transport.py','version_identity.py']:
        old=subprocess.check_output(['git','show',source+':tools/desktop/update/'+name],cwd=root)
        current=subprocess.check_output(['git','show','HEAD:tools/desktop/update/'+name],cwd=root)
        assert old==current
    home=base/'synthetic-package';config=home/'app/desktop-updater';config.mkdir(parents=True)
    (config/'update-config.json').write_text(json.dumps(dict(format=1,product='synthetic-refuse-before-network',platform='windows-x86_64')),encoding='utf-8')
    cache=base/'synthetic-unused-cache'
    routes=json.dumps({h:dict(mode='DIRECT')for h in ['raw.githubusercontent.com','github.com','release-assets.githubusercontent.com','objects.githubusercontent.com']},separators=(',',':'))
    route_file=base/'synthetic-routes.json';route_file.write_text(routes,encoding='utf-8')
    helper=root/'.github/update-argv1244/probe-argv.py'
    arguments=['--package',str(home),'--cache',str(cache),'--proxy-routes',routes,'--download','--expected-version','0.1.0-preview.1245']
    def invoke(args):
        r=subprocess.run(args,cwd=runtime,capture_output=True,timeout=45)
        assert len(r.stdout)+len(r.stderr)<=65536
        return dict(exitCode=r.returncode,stdout=r.stdout.decode('utf-8-sig').strip(),stderr=r.stderr.decode('utf-8-sig').strip())
    python_original=invoke([str(python),'-B',str(script),*arguments])
    python_argv=invoke([str(python),'-B',str(helper),*arguments])
    java=Path(os.environ['JAVA_HOME'])/'bin/java.exe';javac=Path(os.environ['JAVA_HOME'])/'bin/javac.exe'
    classes=base/'classes';classes.mkdir()
    subprocess.run([str(javac),'-d',str(classes),str(root/'.github/update-argv1244/UpdateArgvProbe.java')],check=True,capture_output=True)
    j=invoke([str(java),'-cp',str(classes),'UpdateArgvProbe',str(python),str(script),str(helper),str(runtime),str(home),str(cache),str(route_file)])
    assert j['exitCode']==0 and not j['stderr']
    cases={};version=None;property_value=None
    for line in j['stdout'].splitlines():
        parts=line.split('\t')
        if parts[0]=='JVM':version,property_value=parts[1:]
        elif parts[0] in ['original1244','argvOnly']:
            text=base64.b64decode(parts[2],validate=True).decode('utf-8-sig').strip()
            cases[parts[0]]=dict(exitCode=int(parts[1]),result=json.loads(text))
    assert set(cases)=={'original1244','argvOnly'}
    po=json.loads(python_original['stdout']);pa=json.loads(python_argv['stdout'])
    assert po==dict(status='FAILED',reason='INSTALLED_UPDATE_IDENTITY_REFUSED')
    assert pa['jsonParsed'] and pa['routeSha256']==hashlib.sha256(routes.encode()).hexdigest()
    result=dict(kind='WINDOWS_JDK17_PROCESSBUILDER_ORIGINAL1244_ARGV_OBSERVATION',controlSource=control,
        originalApplicationSource=source,originalInstalledHelperSha256=hashlib.sha256(windows).hexdigest(),javaVersion=version,
        ambiguousCommandsProperty=property_value,syntheticRoutesExpectedSha256=hashlib.sha256(routes.encode()).hexdigest(),
        syntheticExpectedQuoteCount=routes.count('"'),pythonOriginalCli=dict(exitCode=python_original['exitCode'],result=po),
        pythonArgvProbe=pa,javaCases=cases,networkReached=False,syntheticCacheCreated=cache.exists(),
        localCacheOrRealDataAccessed=False,applicationOrInstallerRun=False,
        scope='Actual cloud JDK ProcessBuilder and original frozen1244 CLI; deliberately invalid synthetic product refuses before network if routes survive')
    (reports/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))
if __name__=='__main__':
    try:main()
    except Exception as error:
        folder=Path(os.environ['GITHUB_WORKSPACE'])/'argv-reports';folder.mkdir(exist_ok=True)
        result=dict(kind='ARGV_OBSERVATION_FAILED',errorType=type(error).__name__)
        (folder/'failure.json').write_text(json.dumps(result),encoding='utf-8');print(json.dumps(result));raise
