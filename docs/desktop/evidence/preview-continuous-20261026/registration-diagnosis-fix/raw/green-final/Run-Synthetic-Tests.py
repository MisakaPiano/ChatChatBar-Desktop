"""Freeze and run only the two synthetic Python suites; emit actual unittest and XML results."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,time,unittest,xml.etree.ElementTree as ET

repo=Path('H:/ChatChatBar-Desktop');out=Path(__file__).parent/'green-final'
out.mkdir(exist_ok=False)
sources=['tools/desktop/windows/native_package_probe.py',
         'tools/desktop/windows/test_native_registration_diagnosis.py',
         'tools/desktop/windows/test_native_probe_validation.py']
rows=[]
for relative in sources:
    path=repo/relative;data=path.read_bytes();(out/path.name).write_bytes(data)
    blob=subprocess.check_output(['git','hash-object','--path='+relative,relative],cwd=repo).decode().strip()
    rows.append({'path':relative,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'gitBlob':blob})
(out/'Run-Synthetic-Tests.py').write_bytes(Path(__file__).read_bytes())
def save(name,value):
    with (out/name).open('x',encoding='utf-8',newline='\n') as f:json.dump(value,f,indent=2);f.write('\n')
save('SOURCE_IDENTITY.json',{'baseHead':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo).decode().strip(),'files':rows})
save('COMMAND.json',{'argv':sys.argv,'python':sys.version,'executable':sys.executable,'cwd':str(Path.cwd()),'nativeApis':'Forbidden/mocked in new suite; old regression only Python help/import child processes'})

class Recorded(unittest.TextTestResult):
    def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self.records={}
    def startTest(self,test):
        self.records[test.id()]={'id':test.id(),'start':time.monotonic(),'status':'PASS'};super().startTest(test)
    def stopTest(self,test):self.records[test.id()]['seconds']=time.monotonic()-self.records[test.id()]['start'];super().stopTest(test)
    def addFailure(self,test,err):self.records[test.id()].update(status='FAIL',detail=self._exc_info_to_string(err,test));super().addFailure(test,err)
    def addError(self,test,err):self.records[test.id()].update(status='ERROR',detail=self._exc_info_to_string(err,test));super().addError(test,err)
    def addSkip(self,test,reason):self.records[test.id()].update(status='SKIP',detail=reason);super().addSkip(test,reason)
    def addSubTest(self,test,subtest,err):
        if err:self.records[test.id()].update(status='FAIL',detail=self._exc_info_to_string(err,test))
        super().addSubTest(test,subtest,err)

suite=unittest.TestSuite()
for index,relative in enumerate(sources[1:]):
    spec=importlib.util.spec_from_file_location('synthetic_suite_'+str(index),repo/relative)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    suite.addTests(unittest.defaultTestLoader.loadTestsFromModule(module))
with (out/'unittest.log').open('x',encoding='utf-8',newline='\n') as log:
    result=unittest.TextTestRunner(stream=log,verbosity=2,resultclass=Recorded).run(suite)
counts={'suites':2,'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'skipped':len(result.skipped)}
counts['pass']=counts['tests']-counts['failures']-counts['errors']-counts['skipped'];counts['exit']=0 if result.wasSuccessful() else 1
root=ET.Element('testsuites',tests=str(result.testsRun),failures=str(len(result.failures)),errors=str(len(result.errors)),skipped=str(len(result.skipped)))
groups={}
for row in result.records.values():groups.setdefault(row['id'].rsplit('.',1)[0],[]).append(row)
for classname,tests in groups.items():
    node=ET.SubElement(root,'testsuite',name=classname,tests=str(len(tests)),failures=str(sum(r['status']=='FAIL' for r in tests)),errors=str(sum(r['status']=='ERROR' for r in tests)),skipped=str(sum(r['status']=='SKIP' for r in tests)))
    for row in tests:
        item=ET.SubElement(node,'testcase',classname=classname,name=row['id'].rsplit('.',1)[1],time='%.6f'%row.get('seconds',0))
        if row['status']!='PASS':ET.SubElement(item,{'FAIL':'failure','ERROR':'error','SKIP':'skipped'}[row['status']]).text=row.get('detail','')
ET.indent(root);ET.ElementTree(root).write(out/'junit-unittest.xml',encoding='utf-8',xml_declaration=True)
for row in rows:
    data=(repo/row['path']).read_bytes();assert len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'],'SOURCE_CHANGED_DURING_TEST'
save('RESULT.json',counts)
(out/'exit.txt').write_bytes(str(counts['exit']).encode('ascii'))
print(json.dumps(counts));sys.exit(counts['exit'])
