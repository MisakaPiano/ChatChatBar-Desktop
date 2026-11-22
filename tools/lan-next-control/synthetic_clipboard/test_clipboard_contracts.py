import json
from pathlib import Path
import shlex
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import clipboard_control as c
import build_clipboard_helper as b

SECRET=b'CCB-GROUP-JOIN1:'+b'a'*8192

def ime_dump(package=c.TARGET,active=True,connecting=False,shown=True,wrapper='wrapper',input_type='81'):
    return ('Current Input Method Manager state:\n  mInputShown='+str(shown).lower()+
        '\n  mStartInputHistory:\n    packageName='+c.TARGET+
        '\nInput method client state for synthetic:\n  mActive='+str(active).lower()+
        '\n  mServedConnecting='+str(connecting).lower()+'\n  mCurrentTextBoxAttribute:\n'+
        '    inputType=0x'+input_type+' imeOptions=0x0\n    packageName='+package+' fieldId=0\n'+
        '  mServedInputConnectionWrapper='+wrapper+'\nInput method service state:\n')

class Clock:
    def __init__(self):self.now=0
    def __call__(self):self.now+=.3;return self.now

def xml(text='',password='true',extra=''):
    return ET.fromstring('<hierarchy><node package="'+c.TARGET+'" text="'+c.FIELD_LABELS['invitation']+'"/><node package="'+c.TARGET+'" class="android.widget.EditText" enabled="true" password="'+password+'" text="'+text+'" bounds="[0,0][100,100]"/>'+extra+'</hierarchy>')

class Contracts(unittest.TestCase):
    def test_full_long_payload_envelope_without_argv(self):
        raw=c.envelope('a'*32,'put','invitation',SECRET)
        got=c.parse(raw)
        self.assertTrue(got['text'].encode()==SECRET)
        args=c.write_argv(['adb','-s','emulator-5554'])
        self.assertFalse(any(SECRET.decode() in x for x in args))
    def test_one_shell_quote_layer(self):
        args=c.write_argv(['adb','-s','emulator-5554'])
        self.assertTrue(args[3:8]==['shell','-T','run-as',c.HELPER,'sh'])
        self.assertTrue(shlex.split(args[-1])==[c.WRITE_SCRIPT])
        self.assertNotIn('exec-out',args)
    def test_payload_no_newlines_or_shell_syntax(self):
        for raw in (b'CCB-GROUP-JOIN1:x;bad',b'CCB-GROUP-JOIN1:x\n',b'CCB-MGMT1.bad value'):
            with self.assertRaises(c.Refused):c.payload_bytes(raw,'invitation' if b'JOIN1' in raw else 'recovery')
    def test_recovery_shape_does_not_parse_or_regenerate_keys(self):
        value=b'CCB-MGMT1.'+b'b'*512
        self.assertTrue(c.payload_bytes(value,'recovery').encode()==value)
    def test_envelope_clear_has_no_code(self):
        got=c.parse(c.envelope('b'*32,'clear','none'))
        self.assertTrue(got['text']=='')
    def test_duplicate_json_refuses(self):
        with self.assertRaisesRegex(c.Refused,'DUPLICATE_JSON_KEY'):c.parse(b'{"format":1,"format":2}')
    def test_payload_limit(self):
        with self.assertRaisesRegex(c.Refused,'SYNTHETIC_PAYLOAD_LIMIT'):c.payload_bytes(b'a'*16385,'invitation')
    def test_raw_exact_widget(self):
        v=c.observation('abc','abc');self.assertTrue(v['exactTextMatched'] is True and v['charactersMatched'] is True)
    def test_masked_length_is_not_exact_content(self):
        v=c.observation('\u2022'*8192,'a'*8192);self.assertIsNone(v['exactTextMatched']);self.assertIs(v['charactersMatched'],True)
    def test_truncated_mask_refuses_full_count(self):
        self.assertIs(c.observation('*'*31,'a'*8192)['charactersMatched'],False)
    def test_missing_widget_text_is_unknown_not_zero(self):
        v=c.observation('','abc');self.assertIsNone(v['charactersMatched']);self.assertIsNone(v['exactTextMatched'])
    def test_wrong_raw_same_length_is_mismatch(self):
        self.assertIs(c.observation('xyz','abc')['exactTextMatched'],False)
    def test_unique_field(self):
        self.assertEqual(c.bounds(c.widget(xml(),'invitation')),(0,0,100,100))
    def test_ambiguous_field(self):
        extra='<node package="'+c.TARGET+'" class="android.widget.EditText" enabled="true" bounds="[0,101][100,200]"/>'
        with self.assertRaisesRegex(c.Refused,'UNIQUE_TARGET_EDITTEXT_REQUIRED'):c.widget(xml(extra=extra),'invitation')
    def test_other_app_paste_menu_refused(self):
        root=ET.fromstring('<hierarchy><node package="other" text="PASTE" clickable="true" enabled="true" bounds="[0,0][100,100]"/></hierarchy>')
        self.assertIsNone(c.paste_target(root))
    def test_stat_enoent_is_distinct_from_denial(self):
        a=c.Adb('adb','emulator-5554')
        a.call=lambda argv:subprocess.CompletedProcess(argv,1,b'',('stat: '+c.INBOX+': No such file or directory\n').encode())
        self.assertTrue(a.absent(c.INBOX))
        a.call=lambda argv:subprocess.CompletedProcess(argv,1,b'',b'Permission denied\n')
        with self.assertRaisesRegex(c.Refused,'HELPER_STAT_FAILED'):a.absent(c.INBOX)
    def test_device_only_no_real_serial(self):
        with self.assertRaisesRegex(c.Refused,'EMULATOR_SERIAL_REQUIRED'):c.Adb('adb','real-phone')
    def test_local_build_rejected_before_tools(self):
        with patch.dict('os.environ',{},clear=True),patch.object(b,'run') as tool:
            with self.assertRaisesRegex(b.Refused,'CLOUD_RUNNER_REQUIRED'):b.build(None,{})
            tool.assert_not_called()
    def test_local_bridge_rejected_before_adb(self):
        with patch.dict('os.environ',{},clear=True),patch.object(c.Adb,'run') as adb:
            with self.assertRaisesRegex(c.Refused,'CLOUD_RUNNER_REQUIRED'):c.Bridge(None,None,None,26)
            adb.assert_not_called()
    def test_manifest_has_no_permissions_and_is_test_only(self):
        root=ET.parse(Path(__file__).with_name('AndroidManifest.xml')).getroot();ns='{http://schemas.android.com/apk/res/android}'
        self.assertEqual(root.get('package'),c.HELPER)
        self.assertFalse(root.findall('uses-permission'))
        app=root.find('application');self.assertEqual(app.get(ns+'debuggable'),'true');self.assertEqual(app.get(ns+'testOnly'),'true');self.assertEqual(app.get(ns+'allowBackup'),'false')
    def test_java_no_secret_logging_and_sensitive_flag(self):
        source=Path(__file__).with_name('ClipboardActivity.java').read_text(encoding='utf-8')
        for marker in ('android.util.Log','System.out','System.err','printStackTrace','ServerSocket','Socket('):self.assertNotIn(marker,source)
        self.assertIn('android.content.extra.IS_SENSITIVE',source)
        self.assertLess(source.index('removed = inbox.delete()'),source.index('manager.setPrimaryClip(data)'))
    def test_public_default_result_has_no_payload_or_hash(self):
        result=c.safe_result('paste')
        self.assertFalse(any(x in result for x in ('text','code','clipboard','payload','payloadSha256','operation')))
    def test_hidden_field_unknown_empty_requires_explicit_caller_scope(self):
        value=c.FieldTarget((0,0,100,100),'LABEL_BUTTON_RECTANGLES',c.TARGET,True,True,None)
        with self.assertRaisesRegex(c.Refused,'TARGET_EMPTY_UNOBSERVED'):c.validate_field_target(value,True,False)
        self.assertIs(c.validate_field_target(value,True,True),value)
    def test_hidden_rect_requires_label_and_submit_and_target(self):
        for value in (c.FieldTarget((0,0,100,100),'LABEL_BUTTON_RECTANGLES',c.TARGET,False,True,None),
                      c.FieldTarget((0,0,100,100),'LABEL_BUTTON_RECTANGLES',c.TARGET,True,False,None),
                      c.FieldTarget((0,0,100,100),'LABEL_BUTTON_RECTANGLES','other',True,True,None)):
            with self.assertRaises(c.Refused):c.validate_field_target(value,True,True)
    def test_secret_text_omitted_from_field_repr(self):
        value=c.FieldTarget((0,0,100,100),'EDITTEXT',c.TARGET,True,None,False,SECRET.decode())
        self.assertFalse(SECRET.decode() in repr(value))
    def test_target_length_indicator_is_real_and_local(self):
        root=ET.fromstring('<hierarchy><node package="'+c.TARGET+'" text="8192字" bounds="[70,70][99,99]"/><node package="'+c.TARGET+'" text="123字" bounds="[200,200][240,240]"/></hierarchy>')
        self.assertEqual(c.length_in_target(root,(0,0,100,100)),8192)
    def test_ambiguous_length_indicators_refuse(self):
        root=ET.fromstring('<hierarchy><node package="'+c.TARGET+'" text="8192字" bounds="[1,1][20,20]"/><node package="'+c.TARGET+'" text="123字" bounds="[40,40][80,80]"/></hierarchy>')
        with self.assertRaisesRegex(c.Refused,'FIELD_LENGTH_INDICATOR_AMBIGUOUS'):c.length_in_target(root,(0,0,100,100))
    def test_hidden_real_length_never_claims_exact_text(self):
        value=c.length_observation(None,8192,'a'*8192)
        self.assertEqual(value['representation'],'UI_LENGTH_ONLY');self.assertIs(value['charactersMatched'],True);self.assertIsNone(value['exactTextMatched'])
    def test_no_length_keeps_unknown(self):
        value=c.length_observation(None,None,'a'*8192)
        self.assertIsNone(value['charactersMatched']);self.assertIsNone(value['exactTextMatched'])
    def test_submit_observation_does_not_promote_hidden_content(self):
        bridge=object.__new__(c.Bridge);bridge.a=object();r=c.safe_result('paste')
        bridge.observe_product_submission('invitation',lambda *_:'JOINING_UI_OBSERVED',r)
        self.assertIs(r['productOperationAccepted'],True);self.assertIsNone(r['exactWidgetTextMatched']);self.assertFalse(r['pairedDeliveryProved'])
    def test_submit_observer_context_is_strict(self):
        bridge=object.__new__(c.Bridge);bridge.a=object()
        with self.assertRaisesRegex(c.Refused,'SUBMIT_CONTEXT_MISMATCH'):bridge.observe_product_submission('recovery',lambda *_:'JOINING_UI_OBSERVED',c.safe_result('paste'))
    def test_current_password_ime_is_positive(self):
        self.assertTrue(c.current_ccb_password_ime(ime_dump()))
    def test_history_or_wrong_current_client_does_not_prove_focus(self):
        self.assertFalse(c.current_ccb_password_ime('mInputShown=true\nmStartInputHistory:\npackageName='+c.TARGET))
        self.assertFalse(c.current_ccb_password_ime(ime_dump(package='other.app')))
    def test_inactive_or_stale_ime_is_refused(self):
        for args in ({'active':False},{'connecting':True},{'shown':False},{'wrapper':'null'},{'input_type':'1'}):
            self.assertFalse(c.current_ccb_password_ime(ime_dump(**args)))
        self.assertFalse(c.current_ccb_password_ime(ime_dump()+"Warning: Current input method client doesn't match the last focused window."))
    def test_ime_history_cannot_override_current_input_hidden(self):
        self.assertFalse(c.current_ccb_password_ime(ime_dump(shown=False)+'\n  oldService mInputShown=true\n'))
    def test_focused_edittext_must_be_in_resolved_field(self):
        root=xml();root[1].set('focused','true')
        target=c.FieldTarget((0,0,100,100),'EDITTEXT',c.TARGET,True,True,True,'')
        self.assertTrue(c.focused_target_edittext(root,target))
        outside=c.FieldTarget((101,101,200,200),'LABEL_BUTTON_RECTANGLES',c.TARGET,True,True,None,None)
        self.assertFalse(c.focused_target_edittext(root,outside))
    def test_hardware_paste_never_adds_to_nonempty_field(self):
        target=c.FieldTarget((0,0,100,100),'EDITTEXT',c.TARGET,True,True,True,'')
        with self.assertRaisesRegex(c.Refused,'HARDWARE_PASTE_FIELD_NOT_EMPTY'):c.hardware_empty_evidence(target,xml(),'invitation',1)
        conflict=c.FieldTarget((0,0,100,100),'EDITTEXT',c.TARGET,True,True,False,'existing')
        with self.assertRaisesRegex(c.Refused,'HARDWARE_PASTE_FIELD_NOT_EMPTY'):c.hardware_empty_evidence(conflict,xml(),'invitation',0)
    def test_hidden_empty_needs_real_zero_or_disabled_submit(self):
        target=c.FieldTarget((0,0,100,100),'LABEL_BUTTON_RECTANGLES',c.TARGET,True,True,None,None)
        self.assertIsNone(c.hardware_empty_evidence(target,xml(),'invitation',None))
        self.assertEqual(c.hardware_empty_evidence(target,xml(),'invitation',0),'SCOPED_UI_ZERO_LENGTH')
        disabled='<node package="'+c.TARGET+'" text="检查并加入" enabled="false" clickable="true" bounds="[0,101][100,140]"/>'
        self.assertEqual(c.hardware_empty_evidence(target,xml(extra=disabled),'invitation',None),'EXACT_SUBMIT_DISABLED')
    def hardware_bridge(self,mode='EDITTEXT',focus=True):
        class FakeAdb:
            def __init__(self):self.commands=[];self.taps=[]
            def run(self,*args,**kw):
                self.commands.append(args)
                if args[-1]=='ro.kernel.qemu':return b'1\n'
                if args[-1]=='ro.build.version.sdk':return b'26\n'
                if args[-1]=='input_method':return (ime_dump() if focus else ime_dump(package='other')).encode()
                return b''
            def ui(self):
                root=xml();root[1].set('focused',str(focus and mode=='EDITTEXT').lower());return root
            def tap(self,rectangle):self.taps.append(rectangle)
        bridge=object.__new__(c.Bridge);bridge.a=FakeAdb();bridge.expected_api=26;bridge.target_foreground=lambda:True
        target=c.FieldTarget((0,0,100,100),mode,c.TARGET,True,True,True if mode=='EDITTEXT' else None,'' if mode=='EDITTEXT' else None)
        return bridge,lambda *_:target
    def test_dedicated_key_pair_once_for_visible_and_hidden_modes(self):
        for mode in ('EDITTEXT','LABEL_BUTTON_RECTANGLES'):
            bridge,resolver=self.hardware_bridge(mode);result=c.safe_result('paste')
            with patch.dict('os.environ',{'GITHUB_ACTIONS':'true','RUNNER_OS':'Linux'}),patch.object(c,'installed_hash'),patch.object(c.time,'sleep'),patch.object(c.time,'monotonic',Clock()):
                bridge.hardware_paste('invitation',next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),resolver,lambda *_:0,True,result)
                with self.assertRaisesRegex(c.Refused,'HARDWARE_PASTE_ALREADY_ATTEMPTED'):
                    bridge.hardware_paste('invitation',next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),resolver,lambda *_:0,True,result)
            events=[a for a in bridge.a.commands if 'keyevent' in a]
            self.assertEqual(events,[('shell','-T','input','keyevent','KEYCODE_PASTE')])
            self.assertEqual(result['ordinaryPasteMenuClicks'],0);self.assertEqual(result['ordinaryPasteHardwarePairs'],1)
            self.assertIsNone(result['exactWidgetTextMatched']);self.assertFalse(result['productOperationAccepted'])
    def test_keyboard_scope_refuses_api36_other_apk_and_local(self):
        for api,sha,env in ((36,next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),{'GITHUB_ACTIONS':'true','RUNNER_OS':'Linux'}),
                            (26,'f'*64,{'GITHUB_ACTIONS':'true','RUNNER_OS':'Windows'}),(26,next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),{})):
            bridge,resolver=self.hardware_bridge();bridge.expected_api=api
            with patch.dict('os.environ',env,clear=True),patch.object(c,'installed_hash'):
                with self.assertRaises(c.Refused):bridge.hardware_paste('invitation',sha,resolver,lambda *_:0,True,c.safe_result('paste'))
            self.assertFalse(any('keyevent' in a for a in bridge.a.commands))
    def test_keyboard_stale_focus_sends_no_key(self):
        bridge,resolver=self.hardware_bridge('LABEL_BUTTON_RECTANGLES',focus=False)
        with patch.dict('os.environ',{'GITHUB_ACTIONS':'true','RUNNER_OS':'Linux'}),patch.object(c,'installed_hash'),patch.object(c.time,'sleep'),patch.object(c.time,'monotonic',Clock()):
            with self.assertRaisesRegex(c.Refused,'HARDWARE_PASTE_FOCUS_OR_EMPTY_UNPROVED'):bridge.hardware_paste('invitation',next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),resolver,lambda *_:0,True,c.safe_result('paste'))
        self.assertFalse(any('keyevent' in a for a in bridge.a.commands))
    def test_key_failure_records_attempt_without_claiming_accepted_pair(self):
        bridge,resolver=self.hardware_bridge();original=bridge.a.run
        def failed(*args,**kw):
            if 'keyevent' in args:raise c.Refused('ADB_COMMAND_FAILED')
            return original(*args,**kw)
        bridge.a.run=failed;result=c.safe_result('paste')
        with patch.dict('os.environ',{'GITHUB_ACTIONS':'true','RUNNER_OS':'Linux'}),patch.object(c,'installed_hash'),patch.object(c.time,'sleep'),patch.object(c.time,'monotonic',Clock()):
            with self.assertRaisesRegex(c.Refused,'ADB_COMMAND_FAILED'):bridge.hardware_paste('invitation',next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),resolver,lambda *_:0,True,result)
        self.assertEqual(result['ordinaryPasteHardwareAttempts'],1);self.assertEqual(result['ordinaryPasteHardwarePairs'],0);self.assertIsNone(result['hardwarePasteCommandAccepted'])
    def test_observable_menu_remains_first_and_does_not_send_key(self):
        expected=b'CCB-GROUP-JOIN1:synthetic'
        menu='<node package="'+c.TARGET+'" text="PASTE" clickable="true" enabled="true" bounds="[0,101][100,140]"/>'
        class FakeAdb:
            pasted=False
            def ui(self):return xml(text=expected.decode() if self.pasted else '',extra=menu)
            def tap(self,rectangle):
                if rectangle==(0,101,100,140):self.pasted=True
            def run(self,*args,**kw):return b''
        bridge=object.__new__(c.Bridge);bridge.a=FakeAdb();bridge.expected_api=26;bridge.target_foreground=lambda:True;bridge.put_bytes=lambda *_:None
        result=c.safe_result('paste')
        with patch.object(c,'installed_hash'),patch.object(c.time,'sleep'),patch.object(c.time,'monotonic',Clock()),patch.object(bridge,'hardware_paste') as keyboard:
            rc=bridge.paste_bytes(expected,'invitation','invitation',next(iter(c.HARDWARE_PASTE_TARGET_SHAS)),result)
            keyboard.assert_not_called()
        self.assertEqual(rc,0);self.assertEqual(result['ordinaryPasteAction'],'MENU');self.assertEqual(result['ordinaryPasteMenuClicks'],1);self.assertEqual(result['ordinaryPasteHardwareAttempts'],0)

if __name__=='__main__':unittest.main()
