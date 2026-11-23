"""Pure private-protocol/allowlist checks only; no sockets, SDK or native execution."""
import json
from pathlib import Path
import unittest

import run_reverse_cloud as cloud
import v7_reverse_android_control as android


class ReverseRouteContracts(unittest.TestCase):
    def route(self):
        return dict(kind='CCB_V7_ANDROID_ISOLATED_ROUTE',sourceSha=android.SOURCE,
                    group='a'*32,device='b'*64,relayPort=34567)

    def rejects(self,value):
        with self.assertRaises(android.Refused):android.isolated_route(json.dumps(value).encode())

    def test_exact_private_route(self):
        value=self.route();self.assertEqual(android.isolated_route(json.dumps(value).encode()),value)

    def test_no_url_or_extra_fields(self):
        value=self.route();value['url']='https://invalid.example/';self.rejects(value)

    def test_source_and_fingerprint_binding(self):
        for field,value in [('sourceSha','0'*40),('device','c'*63),('group','d'*33)]:
            route=self.route();route[field]=value;self.rejects(route)

    def test_port_is_not_boolean_or_out_of_range(self):
        for port in (True,0,1023,65536,'34567'):
            route=self.route();route['relayPort']=port;self.rejects(route)

    def test_route_size_bound(self):
        with self.assertRaises(android.Refused):android.isolated_route(b' '*2049)

    def test_forward_requires_one_exact_serial_and_endpoints(self):
        self.assertTrue(android.owned_forward('emulator-5554\ttcp:45678 tcp:34567\n','emulator-5554',45678,34567))
        for row in ('emulator-5556 tcp:45678 tcp:34567','emulator-5554 tcp:45679 tcp:34567',
                    'emulator-5554 tcp:45678 tcp:34568','emulator-5554 tcp:45678 tcp:34567 extra'):
            self.assertFalse(android.owned_forward(row,'emulator-5554',45678,34567))

    def test_duplicate_forward_is_ambiguous(self):
        row='emulator-5554 tcp:45678 tcp:34567\n'
        self.assertFalse(android.owned_forward(row*2,'emulator-5554',45678,34567))

    def progress(self):
        values={key:None for key in ('initialSessionPresent','initialMessageExact','initialImageMatched',
            'initialMessageCountCapped','durableSessionVerified','appendAttempted','appendCommitted',
            'appendReturned','oracleWritten','groupBusy','groupPaused','groupErrorPresent','diagnosticWriteFailed',
            'listenerObserved','relayBytesBothDirections','sourceSignedVersionObserved','sourceNativeFrontierObserved',
            'sourceLocalReceiptObserved','publicationReadAvailable','initialMessageCount','localReverseCount',
            'relayAccepted','relayConnected')}
        values.update(kind='CCB_V7_ANDROID_REVERSE_PROGRESS',phase='normal-runtime-reverse-receipt',
            sequence=1,firstFailurePhase=None,groupStage='READY',isolatedRouteMode='ADB_FORWARD_TRANSPARENT_TLS',
            listenerObservation=None)
        return values

    def test_unknown_publication_stays_unknown(self):
        got=android.safe_native_progress(json.dumps(self.progress()).encode())
        self.assertIsNone(got['sourceLocalReceiptObserved']);self.assertIsNone(got['relayAccepted'])

    def test_progress_no_identity_or_raw_error(self):
        for key in ('device','port','errorMessage'):
            value=self.progress();value[key]='synthetic'
            with self.assertRaises(android.Refused):android.safe_native_progress(json.dumps(value).encode())

    def test_progress_counter_cap_and_closed_route(self):
        for key,value in [('relayAccepted',101),('isolatedRouteMode','OTHER')]:
            row=self.progress();row[key]=value
            with self.assertRaises(android.Refused):android.safe_native_progress(json.dumps(row).encode())

    def test_two_relays_share_exact_transport_implementation(self):
        root=Path(__file__).parent
        marker='/** Test-only byte relay;'
        phone=(root/'LanV7ReverseSyntheticWriterTest.kt').read_text(encoding='utf-8').split(marker,1)[1]
        host=(root/'DesktopV7ReverseSyntheticHostDraftTest.kt').read_text(encoding='utf-8').split(marker,1)[1]
        host=host.split('/** Exactly one synthetic target',1)[0]
        self.assertEqual(phone.strip(),host.strip())
        for relay in (phone,host):
            gate='if (incoming.inetAddress != bind) { incoming.close(); continue }'
            self.assertIn(gate,relay)
            self.assertLess(relay.index(gate),relay.index('slots.tryAcquire()'))
            self.assertLess(relay.index(gate),relay.index('accepted.incrementAndGet()'))
            self.assertLess(relay.index(gate),relay.index('transfer(incoming)'))

    def test_existing_strict_reverse_acceptance_retained(self):
        source=(Path(__file__).parent/'DesktopV7ReverseSyntheticHostDraftTest.kt').read_text(encoding='utf-8')
        for fragment in ('await(180_000)', 'oraclePresent && ordinaryCount > 0', 'received.size == 2',
                         'reverse.content == REVERSE_TEXT', 'actualImage.startsWith(imageRoot)',
                         'lanSyncHash(Files.readAllBytes(actualImage)) == reverseHash', 'bitmap.getRGB(x, y)',
                         'ordinary-reverse-repository-message-and-png'):
            self.assertTrue(fragment in source,fragment)

    def test_new_publication_observer_cannot_recover_journal(self):
        source=(Path(__file__).parent/'LanV7ReverseSyntheticWriterTest.kt').read_text(encoding='utf-8')
        for forbidden in ('.sourceOperations(', '.receipt(', '.inspect(', '.recover(', '.upload(', '.commit('):
            self.assertNotIn(forbidden,source)
        self.assertIn('LanSyncGroupPublishReceipt.serializer()',source)
        self.assertIn('receipt.planHash == lanSyncHash(raw)',source)
        self.assertIn('receipt.target == target',source)

    def test_windows_route_unknown_is_not_zero(self):
        flags={'oraclePresent','oracleEverPresent','repositorySessionPresent','ordinaryCountCapped','ordinaryReverseEverPresent',
            'readOnlyCountCapped','readOnlyReverseEverPresent','groupBusy','groupPaused','groupErrorPresent',
            'nearbyLastSuccessPresent','routeRelayBytesBothDirections'}
        counts={'ordinaryMessageCount','ordinaryReverseIdCount','readOnlyMessageCount','readOnlyReverseIdCount',
            'routeHintReplies','routeRelayAccepted','routeRelayConnected'}
        value={key:None for key in flags|counts}
        value.update(seconds=0,readOnlyStatus='READ_ERROR',waitState='ORACLE_ONLY')
        cloud.safe_windows_diagnostics(value)
        self.assertIsNone(value['routeRelayConnected'])
        value['routeRelayConnected']=-1
        with self.assertRaises(cloud.Refused):cloud.safe_windows_diagnostics(value)

    def test_windows_result_scope_cannot_be_omitted(self):
        value=dict(sourceSha=android.SOURCE,phase='isolated-reverse-route',
                   isolatedRouteMode='ADB_FORWARD_TRANSPARENT_TLS',isolatedRouteInstalled=False)
        cloud.safe_windows_result(value)
        del value['isolatedRouteMode']
        with self.assertRaises(cloud.Refused):cloud.safe_windows_result(value)


    def listener(self):
        return dict(step='WAIT_ORIGINAL_LISTENER',firstFailureClass=None,accessAttached=True,nodeRootRead=True,
                    portPresent=False,nearbyReady=False,socketBoundAndOpen=False,samples=4,
                    backgroundPhase='STARTING',foregroundProtected=False)

    def test_listener_sample_preserves_not_ready_and_unknown(self):
        value=self.progress();value['listenerObservation']=self.listener()
        got=android.safe_native_progress(json.dumps(value).encode())
        self.assertFalse(got['listenerObservation']['portPresent'])
        value['listenerObservation']=None
        self.assertIsNone(android.safe_native_progress(json.dumps(value).encode())['listenerObservation'])

    def test_listener_output_closed_and_capped(self):
        for field,item in [('port',34567),('firstFailureClass','RAW_PRIVATE_ERROR'),('samples',1001),('backgroundPhase','UNKNOWN_RAW')]:
            value=self.listener();value[field]=item
            with self.assertRaises(android.Refused):android.safe_listener_observation(value)

    def test_original_listener_wait_only_reads_existing_state(self):
        source=(Path(__file__).parent/'LanV7ReverseSyntheticWriterTest.kt').read_text(encoding='utf-8')
        block=source.split('phase = "isolated-wait-production-listener"',1)[1].split('val ordinaryPort =',1)[0]
        for required in ('await(30_000)','synchronized(lifecycle)','portField.get(coordinator)',
                         'nearbyField.getBoolean(coordinator)','it.isBound && !it.isClosed'):
            self.assertIn(required,block)
        for forbidden in ('.start(','.open(','.resumeIfConfigured(','.inspect(','.recover(','.connect(','.upload('):
            self.assertNotIn(forbidden,block)


    def nearby(self):
        return dict(availability='READABLE',statusClass='NOT_CONNECTED_PROJECTION',
            loop=dict(present=True,active=True,completed=False,cancelled=False),
            worker=dict(present=False,active=None,completed=None,cancelled=None),admissionLocked=False)

    def test_nearby_optional_field_keeps_legacy_original_schema(self):
        old=self.progress();got=android.safe_native_progress(json.dumps(old).encode())
        self.assertEqual(got,old);self.assertNotIn('nearbyObservation',got)
        old['nearbyObservation']=None
        self.assertIsNone(android.safe_native_progress(json.dumps(old).encode())['nearbyObservation'])

    def test_nearby_readable_preserves_absent_unknown_and_false(self):
        value=self.progress();value['nearbyObservation']=self.nearby()
        got=android.safe_native_progress(json.dumps(value).encode())['nearbyObservation']
        self.assertFalse(got['worker']['present']);self.assertIsNone(got['worker']['active'])
        self.assertFalse(got['admissionLocked']);self.assertTrue(got['loop']['active'])
        self.assertEqual(got['statusClass'],'NOT_CONNECTED_PROJECTION')
        self.assertLess(len(json.dumps(value).encode()),4096)

    def test_nearby_job_properties_are_nontransactional_not_a_dead_worker_claim(self):
        value=self.nearby();value['loop'].update(active=True,completed=True,cancelled=True)
        android.safe_nearby_observation(value) # Sequential property reads may straddle a transition.
        self.assertTrue(value['loop']['active']);self.assertTrue(value['loop']['completed'])

    def test_nearby_unavailable_keeps_all_private_reads_unknown(self):
        for reason in ('FIELD_UNAVAILABLE','REFLECTION_REJECTED','TYPE_MISMATCH','READ_ERROR'):
            value=self.nearby();value.update(availability=reason,statusClass='UNKNOWN',admissionLocked=None)
            value['loop']=dict.fromkeys(value['loop']);value['worker']=dict.fromkeys(value['worker'])
            android.safe_nearby_observation(value)
            value['admissionLocked']=False
            with self.assertRaises(android.Refused):android.safe_nearby_observation(value)

    def test_nearby_closed_fields_and_enum_reject_raw_text_without_echo(self):
        marker='NEVER_EMIT_PRIVATE_ADDRESS_EXCEPTION_OR_TEXT'
        for key,item in [('statusClass',marker),('availability',marker),('rawAddress',marker),('statusClass',[]),('availability',{})]:
            value=self.nearby();value[key]=item
            with self.assertRaises(android.Refused) as failure:android.safe_nearby_observation(value)
            self.assertNotIn(marker,str(failure.exception))
        value=self.nearby();value['worker']['stack']=marker
        with self.assertRaises(android.Refused):android.safe_nearby_observation(value)

    def test_nearby_job_types_and_presence_remain_strict(self):
        for key,item in [('present',1),('active',1),('completed','false'),('cancelled',0)]:
            value=self.nearby();value['loop'][key]=item
            with self.assertRaises(android.Refused):android.safe_nearby_observation(value)
        value=self.nearby();value['worker']['active']=False
        with self.assertRaises(android.Refused):android.safe_nearby_observation(value)
        value=self.nearby();value['admissionLocked']=0
        with self.assertRaises(android.Refused):android.safe_nearby_observation(value)

    def test_nearby_nonreadable_cannot_report_false_job_or_mutex(self):
        value=self.nearby();value.update(availability='READ_ERROR',admissionLocked=None)
        value['loop']=dict.fromkeys(value['loop']);value['worker']=dict.fromkeys(value['worker'])
        value['worker']['present']=False
        with self.assertRaises(android.Refused):android.safe_nearby_observation(value)

    def test_nearby_diagnostic_does_not_weaken_progress_header_or_size_bound(self):
        value=self.progress();value['nearbyObservation']=self.nearby();value['kind']='INVALID'
        with self.assertRaises(android.Refused):android.safe_native_progress(json.dumps(value).encode())
        value=self.progress();value['nearbyObservation']=self.nearby();value['unexpected']='x'
        with self.assertRaises(android.Refused):android.safe_native_progress(json.dumps(value).encode())
        with self.assertRaises(android.Refused):android.safe_native_progress(b' '*4097)

    def test_nearby_kotlin_probe_only_reads_existing_fields_and_properties(self):
        source=(Path(__file__).parent/'LanV7ReverseSyntheticWriterTest.kt').read_text(encoding='utf-8')
        block=source.split('private fun nearbyReadOnly(',1)[1].split('private suspend fun await(',1)[0]
        for required in ('field("loop")','field("nearbyWorker")','field("nearbyAdmission")',
            'current.isActive','current.isCompleted','current.isCancelled','admission.isLocked',
            'NOT_CONNECTED_PROJECTION','LISTENING_WAITING','RETRY_UNAVAILABLE','NO_PEER_FOUND','INDEX_READ','UNKNOWN'):
            self.assertIn(required,block)
        for forbidden in ('.start(','.open(','.resume', '.trySend(','.cancel(','.join(','.connect(',
            '.inspect(','.recover(','.tryLock(','.withLock(', 'Mutex()', 'SupervisorJob()', 'synchronized('):
            self.assertNotIn(forbidden,block)
        self.assertIn('.put("nearbyObservation", readNearby?.invoke() ?: JSONObject.NULL)',source)


if __name__=='__main__':unittest.main()
