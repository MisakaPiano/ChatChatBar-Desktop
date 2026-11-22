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

if __name__=='__main__':unittest.main()
