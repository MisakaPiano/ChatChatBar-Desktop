from pathlib import Path
import hashlib,json,os,re,subprocess,sys

root=Path(os.environ['GITHUB_WORKSPACE']);base=Path(os.environ['CCB_ARGV_FIX_BASE'])
assert os.environ.get('GITHUB_ACTIONS')=='true' and os.environ.get('GITHUB_REPOSITORY')=='MisakaPiano/ChatChatBar-Desktop' and os.name=='nt'
assert base.parent==Path(base.anchor) and base.name.startswith('ccbargvfix-')
runtime=base/'runtime';reports=base/'reports';python=runtime/'python.exe'
classes=base/'bridge-classes';classes.mkdir()
java=Path(os.environ['JAVA_HOME'])/'bin/javac.exe'
subprocess.run([str(java),'-d',str(classes),str(root/'tools/desktop/update/fixtures/UpdateCommandBridge.java')],check=True,capture_output=True)
environment=dict(os.environ,CCB_UPDATE_PROXY_EXPECTED_MODULE_HOME=str(runtime/'lib'),CCB_UPDATE_PROXY_JAVA_CLASSPATH=str(classes))
results=[]
for name in ['test_proxy_arguments.py','test_proxy_cli.py','test_proxy_transport.py','test_client.py']:
    target=root/'tools/desktop/update'/name
    result=subprocess.run([str(python),'-B',str(target),'-v'],env=environment,capture_output=True,timeout=240)
    (reports/(name+'.stdout.log')).write_bytes(result.stdout)
    (reports/(name+'.stderr.log')).write_bytes(result.stderr)
    text=result.stderr.decode('utf-8',errors='replace')
    count=re.search(r'Ran (\d+) tests?',text)
    results.append(dict(script=name,exitCode=result.returncode,methods=int(count[1]) if count else None,
        sourceSha256=hashlib.sha256(target.read_bytes()).hexdigest(),passed=result.returncode==0))
    print(json.dumps(results[-1]),flush=True)
report=dict(kind='FIXED_WINDOWS_JAVA_PROCESSBUILDER_PREPARED_CLI_REGRESSION',
    source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
    actualJavaBridge=True,syntheticOnly=True,userDataAccessed=False,installerExecuted=False,results=results,
    passed=all(item['passed'] for item in results))
(reports/'helper-regression-result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
assert report['passed']
