import io
import json
import struct
import unittest
import zipfile
from archive_census import census, Refused


class CensusTests(unittest.TestCase):
    def header(self, body):
        raw = body.encode()
        return io.BytesIO(b'CBBACKUP' + struct.pack('>I', len(raw)) + raw)

    def archive(self, rows):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as z:
            for name, body in rows:
                z.writestr(name, body)
        stream.seek(0)
        return stream

    def test_encrypted_header_never_claims_authentication(self):
        r = census(self.header('{"version":2,"encrypted":true,"salt":"dummy","wrappedKey":"dummy"}'))
        self.assertEqual('NOT_PERFORMED', r['authentication'])
        self.assertFalse(r['restore_authorized'])

    def test_duplicate_header(self):
        with self.assertRaises(Refused):
            census(self.header('{"version":1,"version":2}'))

    def test_header_types_and_unknowns(self):
        for body in ['{"version":true}', '{"encrypted":"true"}', '{"private":"sentinel"}', '[]']:
            with self.subTest(body=body), self.assertRaises(Refused):
                census(self.header(body))

    def test_zip_crc_and_redaction(self):
        r = census(self.archive([('manifest.json', '{"schemaVersion":8,"name":"PRIVATE_SENTINEL"}'),
                                 ('messages.jsonl', 'PRIVATE_SENTINEL'), ('media/images/a.png', b'bytes')]))
        self.assertEqual('ALL_ENTRIES_READ', r['crc'])
        self.assertEqual(8, r['schema_claim'])
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(r))
        self.assertEqual('NOT_PERFORMED', r['semantic_validation'])

    def test_corrupt_zip(self):
        stream = self.archive([('messages.jsonl', 'SENTINEL_UNIQUE')])
        data = stream.getvalue().replace(b'SENTINEL_UNIQUE', b'BROKEN__UNIQUE_')
        with self.assertRaises(zipfile.BadZipFile):
            census(io.BytesIO(data))

    def test_traversal_and_duplicate(self):
        for names in [('A', 'a'), ('../secret',), ('C:secret',), ('/absolute',), ('x\\y',)]:
            with self.subTest(names=names), self.assertRaises(Refused):
                census(self.archive([(name, b'body') for name in names]))

    def test_future_version_uninterpreted(self):
        r = census(self.header('{"version":99,"encrypted":false}'))
        self.assertEqual('UNKNOWN', r['header_claim_version'])

    def test_truncated_and_unrecognized(self):
        self.assertEqual('UNRECOGNIZED', census(io.BytesIO(b'PRIVATE'))['kind'])
        with self.assertRaises(Refused):
            census(io.BytesIO(b'PK\x03\x04'))


if __name__ == '__main__':
    unittest.main()
