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
            sequence=1,firstFailurePhase=None,groupStage='READY',isolatedRouteMode='ADB_FORWARD_TRANSPARENT_TLS')
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


if __name__=='__main__':unittest.main()
