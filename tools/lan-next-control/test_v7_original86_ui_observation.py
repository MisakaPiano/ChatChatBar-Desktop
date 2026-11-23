"""Pure synthetic accessibility trees and bounded mock UI transport only."""
import json
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import v7_original86_ui_observation as obs


def page(labels,package=obs.PACKAGE,enabled='true',scroll=False):
    root=ET.Element('hierarchy');container=ET.SubElement(root,'node',package=package,
        bounds='[0,0][720,1200]',scrollable='true' if scroll else 'false')
    for i,label in enumerate(labels):
        button=ET.SubElement(container,'node',package=package,clickable='true',enabled=enabled,
            bounds=f'[20,{20+i*70}][600,{70+i*70}]')
        ET.SubElement(button,'node',package=package,text=label,enabled='true',
            bounds=f'[30,{30+i*70}][590,{60+i*70}]')
    return root


class GroupObservationTests(unittest.TestCase):
    def test_ready_exact_controls_are_not_management_enablement(self):
        value=obs.classify(page(obs.READY,enabled='false'),True)
        self.assertEqual(value['controlStage'],'READY_CONTROLS')
        self.assertEqual(value['readyControlsEnabled'],{'check':False,'cloud':False})

    def test_unknown_foreground_and_foreign_app_do_not_qualify(self):
        for root,foreground in ((page(obs.READY),None),(page(obs.READY),False),(page(obs.READY,package='foreign.app'),True)):
            value=obs.classify(root,foreground)
            self.assertEqual(value['controlStage'],'UNKNOWN_SURFACE');self.assertIsNone(value['readyControlPairVisible'])

    def test_password_or_input_content_cannot_supply_stage(self):
        root=page(['设备组同步'])
        for label in obs.READY:
            ET.SubElement(root,'node',package=obs.PACKAGE,text=label,password='true',
                attrib={'class':'android.widget.EditText','bounds':'[20,300][600,350]'})
        self.assertEqual(obs.classify(root,True)['controlStage'],'NOT_OBSERVED')

    def test_duplicate_or_conflicting_controls_are_not_ready(self):
        self.assertFalse(obs.classify(page([*obs.READY,obs.READY[0]]),True)['readyControlPairVisible'])
        observer=obs.Observer();observer.add(page([*obs.READY,'继续加入']),True,'AFTER_READBACK',1)
        self.assertEqual(observer.history[-1]['controlStage'],'CONFLICTING_CONTROLS')
        self.assertFalse(observer.ready)

    def test_status_and_background_running_do_not_prove_ready(self):
        value=obs.classify(page(['设备组同步','同步检查完成','设备组后台服务已运行；离开此页后仍可处理同网同步']),True)
        self.assertEqual(value['statusClasses'],['CHECK_COMPLETE']);self.assertFalse(value['readyControlPairVisible'])

    def test_review_status_is_exact_closed_shape(self):
        value=obs.classify(page(['设备组同步','同步检查完成，2 项需要审阅或补齐依赖']),True)
        self.assertEqual(value['statusClasses'],['CHECK_NEEDS_REVIEW'])
        value=obs.classify(page(['设备组同步','同步检查完成，PRIVATE 会话需要审阅或补齐依赖']),True)
        self.assertEqual(value['statusClasses'],[]);self.assertNotIn('PRIVATE',json.dumps(value))

    def test_known_source_error_and_explanation(self):
        code='SYNC_METADATA_WORKING_BUDGET'
        for body in (code,obs.EXPLANATIONS[code]+'（'+code+'）'):
            value=obs.classify(page(['设备组同步','本机同步准备：'+body]),True)
            self.assertEqual(value['sourceErrors'],[{'action':'LOCAL_PREPARE','class':'METADATA_WORKING_LIMIT'}])
            self.assertFalse(value['unclassifiedAppTokenSeen'])

    def test_unknown_code_or_non_source_prefix_stays_unknown_without_content(self):
        for text in ('本机同步准备：SYNC_PRIVATE_UNRECOGNIZED','设备名：SYNC_METADATA_WORKING_BUDGET',
                     'PRIVATE_PATH /private/secret SYNC_UNKNOWN'):
            value=obs.classify(page(['设备组同步',text]),True)
            encoded=json.dumps(value)
            self.assertNotIn('PRIVATE_PATH',encoded);self.assertNotIn('/private/secret',encoded)
            self.assertNotIn('SYNC_PRIVATE_UNRECOGNIZED',encoded)
        value=obs.classify(page(['设备组同步','本机同步准备：SYNC_PRIVATE_UNRECOGNIZED']),True)
        self.assertEqual(value['sourceErrors'][0]['class'],'UNCLASSIFIED_SOURCE_CODE')

    def test_foreground_component_is_exact(self):
        self.assertTrue(obs.active_group('mResumedActivity: ActivityRecord{0 '+obs.GROUP+' t1}'))
        self.assertFalse(obs.active_group('mResumedActivity: ActivityRecord{0 '+obs.PACKAGE+'/com.example.chatbar.MainActivity t1}'))
        self.assertIsNone(obs.active_group('unrelated dump'))

    def test_history_preserves_ever_versus_latest_and_data_independence(self):
        observer=obs.Observer();observer.add(page(obs.READY),True,'BEFORE_READBACK',1)
        for i in range(12):observer.add(page(['继续加入']),True,'AFTER_READBACK',i+2)
        result=observer.summary();self.assertTrue(result['readyControlsEverConfirmed'])
        self.assertEqual(result['lastControlStage'],'JOINING_CONTROLS')
        self.assertFalse(result['dataReadbackVerified']);self.assertEqual(len(result['lastSamples']),8)
        self.assertEqual(result['firstSeen'][0]['relativePhase'],'BEFORE_READBACK')

    def test_post_readback_only_observes_and_scrolls_bounded(self):
        class FakeAdb:
            def __init__(self):self.commands=[]
            def run(self,*args,timeout=20):
                self.commands.append((args,timeout))
                if args[:3]==('shell','dumpsys','activity'):return 'mResumedActivity: ActivityRecord{0 '+obs.GROUP+' t1}'
                return ''
            def ui(self):return page(['设备组同步'],scroll=True)
        adb=FakeAdb();original=adb.run;observer=obs.Observer()
        with patch.object(obs.time,'sleep',lambda _:None):obs.after_readback(adb,observer,obs.time.monotonic())
        self.assertEqual(adb.run,original)
        self.assertEqual(sum(args[:3]==('shell','input','swipe') for args,_ in adb.commands),2)
        self.assertTrue(all(args[:3] in {('shell','dumpsys','activity'),('shell','input','swipe')} for args,_ in adb.commands))
        self.assertFalse(observer.data);self.assertFalse(observer.ready)

    def test_post_readback_failure_is_only_unavailable_and_restores_driver(self):
        class FakeAdb:
            def run(self,*args,timeout=20):return ''
            def ui(self):raise RuntimeError('/PRIVATE/raw/message')
        adb=FakeAdb();original=adb.run;observer=obs.Observer();observer.data=True
        obs.after_readback(adb,observer,obs.time.monotonic())
        self.assertEqual(adb.run,original);result=observer.summary()
        self.assertTrue(result['dataReadbackVerified']);self.assertEqual(result['readFailures'],1)
        self.assertNotIn('PRIVATE',json.dumps(result))


if __name__=='__main__':unittest.main()
