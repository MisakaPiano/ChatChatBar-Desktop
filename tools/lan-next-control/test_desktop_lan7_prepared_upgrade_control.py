import copy, hashlib, json, os, pathlib, re, sys, tempfile, types, unittest
from unittest.mock import patch
import desktop_lan7_prepared_upgrade_control as d
ORIGINAL_CLOSE_BLOCK = b'                        var notices = 0\n                        withContext(NonCancellable) {\n                            withTimeout(60_000) {\n                                closeDesktopWithLanSyncCompletionNotice(close = { app.close() }, showNotice = { notices++ })\n                            }\n                        }\n                        // Hold the root ownership until AppContainer teardown is confirmed.\n                        canRelease = true\n                        check(notices == 0)\n'
ROOT=pathlib.Path(__file__).resolve().parents[2]
REQUEST=json.loads((ROOT/d.REQUEST).read_bytes())
def first(action='SEED'):
 v={'kind':'CCB_V6_PREPARED_DPAPI_COMPONENT_V1','sourceSha':d.OLD if action=='SEED' else d.NEW,'action':action,
 'passed':True,'realDpapi':True,'realAppContainer':True,'packagedMainExercised':False,'networkDeliveryExercised':False,'closeReturned':True,'closeNoticeKnown':True,'closeNoticeCount':0,'closeOutcome':'RETURNED_WITHOUT_NOTICE',
 'baseMetadataWorkingBudgetObserved':action=='SEED','samePreparedSetupResumed':action=='RESUME','heapBytes':256*1024**2,
 'recordWindow':64*1024**2,'rawFixtureBytes':25*1024**2,'aggregateQuotedCostLowerBound':96*16384*6*8,
 'rawFixtureSha256':'1'*64,'retainedProfileSha256':'2'*64}
 if action=='RESUME':v['localReceiptSha256']='3'*64
 return v
def additional(action):
 details={'coldReady':True,'sameGroupAndDailyIdentity':True,'originalDpapiRecoveryCodeUsed':True,'coordinatorInvitationIssued':True,
 'managementLockedAfterward':True,'immutableProtectedOriginsRetained':True,'originalSyntheticEntitiesUnchanged':True,'originalAuthenticatedDeliveryObjectsRetained':True,'profileSha256':'2'*64} if action=='COLD_INVITE' else {
 'unusedSetupOnly':True,'explicitArchiveAction':action=='ARCHIVE','completedArchiveRetained':True,'liveEntitiesUnchanged':True,'coldEmptyStateVerified':action=='ARCHIVE_COLD','archiveSha256':'4'*64}
 return {'kind':'CCB_LAN6_LAN7_ADDITIONAL_DPAPI_COMPONENT_V1','sourceSha':d.NEW,'action':action,'passed':True,'realDpapi':True,
 'realAppContainer':True,'packagedMainExercised':False,'visibleUiExercised':False,'networkDeliveryExercised':False,'closeReturned':True,'closeNoticeKnown':True,'closeNoticeCount':0,'closeOutcome':'RETURNED_WITHOUT_NOTICE','details':details}
class PreparedTests(unittest.TestCase):
 def test_exact_real_candidate_and_all_baseline_fields(self):
  with d.borrowed() as (bridge,modules,p):
   with p.profile():
    self.assertEqual(d.validate_request(modules['desktop_control'],REQUEST),REQUEST)
    for role in (0,1):
     for key,value in REQUEST['packages'][role].items():
      bad=copy.deepcopy(REQUEST);bad['packages'][role][key]=value+1 if type(value) is int else value+'changed'
      with self.subTest(role=role,key=key),self.assertRaises(ValueError):d.validate_request(modules['desktop_control'],bad)
 def test_timestamp_diff_and_waiver_refused(self):
  with d.borrowed() as (bridge,modules,p):
   with p.profile():
    for mutate in (lambda v:v['timestampSourceBindings']['files'][0].update(candidateSha256='0'*64),lambda v:v.update(allowedStateMigrations=['*'])):
     bad=copy.deepcopy(REQUEST);mutate(bad)
     with self.assertRaises(ValueError):d.validate_request(modules['desktop_control'],bad)
 def test_borrowed_modules_and_profile_restore_on_exception(self):
  previous={k:sys.modules.get(k) for k in ('desktop_control','desktop_lan6_control','desktop_group_ui_control')}
  with self.assertRaisesRegex(RuntimeError,'synthetic'):
   with d.borrowed() as (bridge,modules,p):
    c=modules['desktop_control'];before=c.BASE;validate=c.validate_build
    with self.assertRaisesRegex(RuntimeError,'inner'):
     with p.profile():raise RuntimeError('inner')
    self.assertIs(c.BASE,before);self.assertIs(c.validate_build,validate)
    raise RuntimeError('synthetic')
  for k,v in previous.items():self.assertIs(sys.modules.get(k),v)
 def test_scale_and_profile_receipt_schemas(self):
  for action in ('SEED','RESUME'):self.assertEqual(d.report(first(action),action)['passed'],True)
  for change in ({'passed':False},{'recordWindow':True},{'aggregateQuotedCostLowerBound':1},{'rawFixtureSha256':'private-secret'},{'recoveryCode':'private-secret'},{'networkDeliveryExercised':True}):
   bad=first();bad.update(change)
   with self.subTest(change=change),self.assertRaises(ValueError):d.report(bad,'SEED')
  bad=first('RESUME');bad.pop('localReceiptSha256')
  with self.assertRaises(ValueError):d.report(bad,'RESUME')
 def test_extra_components_do_not_overclaim(self):
  for action in ('COLD_INVITE','ARCHIVE','ARCHIVE_COLD'):d.report(additional(action),action)
  for action in ('COLD_INVITE','ARCHIVE','ARCHIVE_COLD'):
   bad=additional(action);bad['visibleUiExercised']=True
   with self.assertRaises(ValueError):d.report(bad,action)
   bad=additional(action);bad['details']['recoveryCode']='secret'
   with self.assertRaises(ValueError):d.report(bad,action)
   bad=additional(action);bad['details'][next(k for k in bad['details'] if k not in ('profileSha256','archiveSha256'))]=False if action=='COLD_INVITE' else 'false'
   with self.assertRaises(ValueError):d.report(bad,action)
 def test_failure_marker_closed_and_poison_is_unavailable(self):
  with tempfile.TemporaryDirectory() as td:
   p=pathlib.Path(td);bridge=types.SimpleNamespace(compiler_diagnostic=lambda *a:{'status':'NO_ERROR','fixtureLocations':[]})
   def write(message):
    from xml.sax.saxutils import escape
    (p/'original-test.xml').write_text('<testsuite name="'+d.CLASS+'" tests="1" failures="1" errors="0" skipped="0"><testcase classname="'+d.CLASS+'" name="'+d.METHODS['SEED']+'"><failure type="java.lang.AssertionError" message="'+escape(message,{'"':'&quot;'})+'"/></testcase></testsuite>')
   write('java.lang.AssertionError: CCB_V6_PREPARED_COMPONENT:CLOSE_REAL_APP:ACTUAL_BASE_METADATA_REFUSAL')
   v=d.failure_projection(p,None,bridge,p);self.assertEqual(v['safePrimaryPhase'],'ACTUAL_BASE_METADATA_REFUSAL');self.assertEqual(v['counts']['failures'],'1')
   write('CCB_V6_PREPARED_COMPONENT:SECRET_CODE:PRIVATE_PATH')
   v=d.failure_projection(p,None,bridge,p);self.assertIsNone(v['safePhase']);self.assertNotIn('SECRET_CODE',json.dumps(v))
   (p/'original-test.xml').write_text('<broken')
   self.assertEqual(d.failure_projection(p,None,bridge,p)['status'],'UNAVAILABLE')
 def test_small_output_allowlist_rejects_raw_or_private(self):
  with tempfile.TemporaryDirectory() as td:
   p=pathlib.Path(td);d.write_new(p/d.RESULT,{'passed':False});d.audit_output(p)
   (p/'raw-test.xml').write_text('private')
   with self.assertRaises(ValueError):d.audit_output(p)
 def test_environment_same_user_data_all_four_names(self):
  with tempfile.TemporaryDirectory() as td:
   p=pathlib.Path(td);root=p/'independent';private=p/'private';private.mkdir()
   for leaf in ('Local','Roaming','Temp'):(root/'UserData'/leaf).mkdir(parents=True,exist_ok=True)
   v=d.environment_for(root,private,d.OLD,'SEED',private/'report.json')
   self.assertEqual(pathlib.Path(v['LOCALAPPDATA']),root/'UserData/Local');self.assertEqual(v['TEMP'],v['TMP'])
   self.assertEqual(v['CCB_V6_PREPARED_ROOT'],str(root));self.assertEqual(v['CCB_V6_PREPARED_SOURCE'],d.OLD)
   with self.assertRaises(ValueError):d.environment_for(root,private,d.OLD,'UNKNOWN',private/'report.json')
 def test_original_compiled_fixture_body_and_helpers_unchanged(self):
  fixture=(ROOT/'tools/lan-next-control/fixtures/DesktopGroupPreparedUpgradeControlTest.kt').read_bytes()
  candidate_line=('    private val candidate = "'+d.NEW+'"\n').encode('ascii')
  self.assertEqual(fixture.count(candidate_line),1)
  fixture=fixture.replace(candidate_line,b'    private val candidate = "49b7edf434178611c72d31f599b7300a3a3225f2"\n',1)
  fixture=re.sub(rb'                    // LAN7_COLD_BASELINE_BEGIN\n.*?                    // LAN7_COLD_BASELINE_END\n',b'',fixture,flags=re.S)
  fixture=fixture.replace(b'    private var lastClose: SafeCloseResult? = null\n',b'',1)
  fixture=re.sub(rb'                        // LAN7_EXPECTED_CLOSE_BEGIN\n.*?                        // LAN7_EXPECTED_CLOSE_END\n',ORIGINAL_CLOSE_BLOCK,fixture,count=1,flags=re.S)
  fixture=fixture.replace(b'                        closeFields()\n',b'',1)
  fixture=fixture.replace(b'} catch (failure: Throwable) {\n            // Neither',b'} catch (_: Throwable) {\n            // Neither',1)
  fixture=fixture.replace(b'${primary ?: phase}:${safeThrowableType(failure)}',b'${primary ?: phase}',1)
  marker=b'    private fun messageId(index: Int)'
  self.assertEqual(hashlib.sha256(fixture.split(b'    @Test fun coldReadyUsesOriginalDpapiCodeAndIssuesInvitation',1)[0].rstrip()).hexdigest(),'73c27ebac1c3653ee8633b3deaa2b9ba540af6799e55ed5c7f65a161b6e8c8a7')
  self.assertEqual(hashlib.sha256(fixture.split(marker,1)[1]).hexdigest(),'59846e23d86c89f1e678c1678e0caa583f869ca72cf8f182c7c59862bb9816da')
  self.assertEqual(fixture.count(b'@Test fun '),3);self.assertIn(b'coordinator.createInvitation()',fixture)
 def test_exact_one_method_xml_parser_rejects_skip_fail_hidden(self):
  with d.borrowed() as (bridge,modules,p):
   ui=modules['desktop_group_ui_control'];ui.CLASS=d.CLASS;ui.METHOD=d.METHODS['SEED']
   good=('<testsuite name="'+d.CLASS+'" tests="1" failures="0" errors="0" skipped="0"><testcase classname="'+d.CLASS+'" name="'+d.METHODS['SEED']+'" time="1.1"/></testsuite>').encode()
   self.assertEqual(ui.junit_result(good)['executed'],1)
   for bad in (good.replace(b'skipped="0"',b'skipped="1"'),good.replace(b'</testsuite>',b'<system-out><testcase/></system-out></testsuite>'),good.replace(b'/></testsuite>',b'><failure/></testcase></testsuite>')):
    with self.assertRaises(Exception):ui.junit_result(bad)
 def test_five_components_unique_methods_and_no_local_invocation(self):
  self.assertEqual(len(d.ACTIONS),5);self.assertEqual(len(set(d.METHODS.values())),3)
  self.assertEqual(d.MAIN_STAGES,('lan6-fresh','lan6-populated','lan6-warm-control','lan7-upgraded','lan7-cold-reopen'))
  self.assertEqual(len(d.OUTPUTS),10)
 def test_init_is_only_test_heap_and_valid_newline(self):
  self.assertTrue(d.INIT.endswith('\n'));self.assertNotIn('\\n',d.INIT)
  self.assertIn("maxHeapSize = '256m'",d.INIT);self.assertNotIn('apply',d.INIT)
 def test_component_runner_executes_fresh_exact_method_and_restores_on_failure(self):
  with tempfile.TemporaryDirectory() as td, d.borrowed() as (bridge,modules,p):
   base=pathlib.Path(td);private=base/'private';private.mkdir();output=base/'output';output.mkdir();source=base/'source';source.mkdir();root=base/'root'
   for leaf in ('Local','Roaming','Temp'):(root/'UserData'/leaf).mkdir(parents=True,exist_ok=True)
   companion=d.Companion(bridge,modules,{d.OLD:source},private,output)
   companion.requests[d.OLD]=private/'r.json';companion.templates[d.OLD]=b'{}'
   ui=companion.ui;old_env,old_command,old_method=ui.isolated_environment,ui.native_command,ui.METHOD
   seen=[]
   def fail(*args):
    argv=ui.native_command(source);seen.append(argv)
    raise RuntimeError('private-not-exported')
   with patch.object(ui,'run',side_effect=fail):
    with self.assertRaisesRegex(RuntimeError,'private-not-exported'):companion.run(root,'SEED')
   self.assertEqual(companion.reports,[]);self.assertEqual(seen[0].count('--rerun'),1);self.assertNotIn('--rerun-tasks',seen[0])
   self.assertEqual(seen[0][seen[0].index('--tests')+1],d.CLASS+'.'+d.METHODS['SEED'])
   self.assertIs(ui.isolated_environment,old_env);self.assertIs(ui.native_command,old_command);self.assertEqual(ui.METHOD,old_method)
   self.assertNotIn('private-not-exported',json.dumps(companion.failure))

 def test_native_hooks_delegate_restore_and_do_not_finish_at_two_components(self):
  with d.borrowed() as (bridge,modules,p), tempfile.TemporaryDirectory() as td:
   root=pathlib.Path(td); data=root/'UserData/Local/ChatChatBarDesktop';data.mkdir(parents=True)
   calls=[];written=[];answer={'nativePassed':True}
   class User:
    def PostMessageW(self,*args):calls.append(('post',args));return 1
   class Native:
    def __init__(self,root):self.root=root;self.user=User()
    def owned(self):calls.append(('owned',));return []
    def launch(self,stage,environment):
     calls.append(('launch',stage));self.user.PostMessageW(1,0x10,0,0);self.owned();return answer
   old=types.SimpleNamespace(seed=lambda data:'original-image',Native=Native,write_json=lambda path,value:written.append(value))
   native=types.SimpleNamespace(legacy=lambda:old,RESULT_FILE='old.json')
   original=(old.seed,old.Native,old.write_json,native.legacy,native.RESULT_FILE)
   comp=types.SimpleNamespace(reports=[],failure=None,run=lambda root,action:calls.append(('component',action)))
   with d.attach({'prove_desktop_lan3_lan4':native},bridge,comp):
    self.assertEqual(old.seed(data),'original-image')
    self.assertIs(old.Native(root).launch('lan7-upgraded',{}),answer)
    old.write_json(root/d.RESULT,{'passed':True})
   self.assertEqual((old.seed,old.Native,old.write_json,native.legacy,native.RESULT_FILE),original)
   self.assertIn(('component','SEED'),calls);self.assertIn(('component','RESUME'),calls)
   self.assertFalse(written[0]['passed']);self.assertFalse(written[0]['proofComplete']);self.assertTrue(written[0]['mainMatrixPassed'])
 def test_workflow_is_exact_json_only_and_hash_bound(self):
  workflow=(ROOT/'.github/workflows/lan-next-desktop-lan7-prepared-upgrade.yml').read_text()
  self.assertIn(hashlib.sha256(pathlib.Path(d.__file__).read_bytes()).hexdigest(),workflow)
  self.assertIn('persist-credentials: false',workflow);self.assertIn('core.longpaths true',workflow)
  paths=[x.strip() for x in workflow.split('path: |',1)[1].splitlines() if x.strip()]
  self.assertEqual(paths,[d.EVIDENCE+'/'+name for name in d.OUTPUTS]);self.assertEqual(len(paths),10)
  self.assertNotIn('*.zip',workflow);self.assertNotIn('gradle-original.log',workflow);self.assertNotIn('UserData/',workflow)


 def test_cold_delivery_uses_authenticated_post_resume_subset_not_seed_full_pin(self):
  fixture=(ROOT/'tools/lan-next-control/fixtures/DesktopGroupPreparedUpgradeControlTest.kt').read_text(encoding='utf8')
  cold=fixture.split('@Test fun coldReadyUsesOriginalDpapiCodeAndIssuesInvitation()',1)[1].split('@Test fun archiveUnusedSetupThenVerifyColdArchive()',1)[0]
  self.assertIn('cold-oracle.private.dpapi',cold);self.assertIn('verifyDeliveryDescriptors',cold)
  self.assertIn('pinnedReplicaFiles.all',cold);self.assertNotIn('check(deliveryPin(',cold)
  self.assertIn('if (!seeding)',fixture);self.assertIn('replica.readTo(item.key, java.io.OutputStream.nullOutputStream(), item.descriptor)',fixture)
  self.assertIn('rootDeliveries == retained.getValue("rootDelivery")',fixture)


 def test_115_entity_five_hash_maps_result_survives_both_reads_and_complete_audit(self):
  with tempfile.TemporaryDirectory() as td:
   directory=pathlib.Path(td)
   names=['character_cards/synthetic-card.json','chat_sessions/synthetic-v6-prepared.json']+[f'chat_messages/synthetic-v6-prepared_v6-message-{i:03d}.json' for i in range(1,97)]+[f'old_control_entity/retained-{i:02d}.json' for i in range(17)]
   hashes={name:'1'*64 for name in names}
   value={'snapshots':{stage:hashes for stage in d.MAIN_STAGES[1:]},'entityHashesBefore':hashes,'passed':True}
   d.write_new(directory/d.RESULT,value)
   self.assertEqual(len(names),115);self.assertGreater((directory/d.RESULT).stat().st_size,65536)
   self.assertEqual(d.small(directory/d.RESULT),value);self.assertEqual(d.small(directory/d.RESULT),value)
   for name in d.OUTPUTS:
    if name!=d.RESULT:d.write_new(directory/name,{'passed':True})
   d.audit_output(directory,complete=True)
 def test_result_bound_is_still_finite_and_only_for_exact_native_filename(self):
  with tempfile.TemporaryDirectory() as td:
   directory=pathlib.Path(td)
   self.assertEqual(d.MAX_NATIVE_RESULT_BYTES,256*1024)
   result=directory/d.RESULT
   result.write_bytes(b'"'+b'x'*(d.MAX_NATIVE_RESULT_BYTES-2)+b'"')
   self.assertEqual(result.stat().st_size,d.MAX_NATIVE_RESULT_BYTES)
   with self.assertRaises(ValueError):d.small(result)
   for name in d.OUTPUTS:
    if name==d.RESULT:continue
    path=directory/name;path.write_bytes(b'"'+b'x'*65534+b'"')
    with self.subTest(name=name),self.assertRaises(ValueError):d.small(path)
 def test_non_result_75kb_cannot_hide_in_allowlisted_json(self):
  with tempfile.TemporaryDirectory() as td:
   directory=pathlib.Path(td)
   d.write_new(directory/d.RESULT,{'passed':False})
   d.write_new(directory/'component-resume.json',{'privatePadding':'x'*75000})
   with self.assertRaises(ValueError):d.audit_output(directory)


 def test_expected_notice_only_for_cold_invite_resume_requires_none(self):
  for action in d.ACTIONS:
   value=first(action) if action in ('SEED','RESUME') else additional(action)
   d.report(value,action)
   value.update(closeNoticeCount=1,closeOutcome='RETURNED_WITH_EXPECTED_PENDING_SYNC_NOTICE')
   if action == 'COLD_INVITE':d.report(value,action)
   else:
    with self.subTest(action=action),self.assertRaises(ValueError):d.report(value,action)
  for field,invalid in [('closeReturned',False),('closeNoticeKnown',False),('closeNoticeCount',2),('closeNoticeCount',True),('closeOutcome','UNKNOWN_NOTICE')]:
   value=first('RESUME');value[field]=invalid
   with self.subTest(field=field,invalid=invalid),self.assertRaises(ValueError):d.report(value,'RESUME')
 def test_close_callback_records_known_flag_then_validates_outside_swallow_boundary(self):
  fixture=(ROOT/'tools/lan-next-control/fixtures/DesktopGroupPreparedUpgradeControlTest.kt').read_text(encoding='utf8')
  helper=fixture.split('private suspend fun closeObserved(',1)[1].split('private fun safeThrowableType',1)[0]
  self.assertIn('withTimeout(60_000)',helper)
  self.assertIn('closeObserved(app, allowExpectedNotice = false) { canRelease = true }',fixture)
  self.assertNotIn('closeDesktopWithRecovery',helper)
  self.assertLess(helper.index('closeDesktopWithLanSyncCompletionNotice'),helper.index('onReturned()'))
  self.assertLess(helper.index('onReturned()'),helper.index('check(knownNotice'))
  callback=helper.split('showNotice = { message ->',1)[1].split('})',1)[0]
  self.assertIn('message == DesktopLanSyncIncompleteNotice.message',callback);self.assertNotIn('check(',callback)
  self.assertNotIn('message)',callback);self.assertIn('VERIFY_CLOSE_NOTICE',helper)
 def test_archive_root_is_runner_temp_sibling_and_old_nested_pair_refuses(self):
  with tempfile.TemporaryDirectory() as td:
   temporary=pathlib.Path(td).resolve();private=temporary/'private';private.mkdir()
   root=d.make_archive_root(temporary,private)
   self.assertEqual(root.parent,temporary);self.assertNotEqual(root,private)
   self.assertTrue((root/'UserData/Local/ChatChatBarDesktop').is_dir())
   for a,b in [(private/'nested',private),(private,private/'nested')]:
    with self.assertRaises(ValueError):d.isolated_archive_pair(a,b,temporary)
 def test_safe_failure_type_backward_and_new_marker_closed(self):
  with tempfile.TemporaryDirectory() as td:
   directory=pathlib.Path(td);bridge=types.SimpleNamespace(compiler_diagnostic=lambda *args:{'status':'NO_ERROR','fixtureLocations':[]})
   def value(marker):
    (directory/'original-test.xml').write_text('<testsuite name="'+d.CLASS+'" tests="1" failures="1" errors="0" skipped="0"><testcase><failure type="java.lang.AssertionError" message="'+marker+'"/></testcase></testsuite>')
    return d.failure_projection(directory,None,bridge,directory)
   self.assertEqual(value('CCB_V6_PREPARED_COMPONENT:CLOSE_REAL_APP:CLOSE_REAL_APP')['safePhase'],'CLOSE_REAL_APP')
   self.assertEqual(value('CCB_V6_PREPARED_COMPONENT:VERIFY_CLOSE_NOTICE:VERIFY_CLOSE_NOTICE:IllegalStateException')['safeCaughtType'],'IllegalStateException')
   self.assertIsNone(value('CCB_V6_PREPARED_COMPONENT:VERIFY_CLOSE_NOTICE:VERIFY_CLOSE_NOTICE:SecretToken')['safePhase'])

if __name__=='__main__':unittest.main()
