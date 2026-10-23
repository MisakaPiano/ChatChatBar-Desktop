import io,json,unittest,zipfile
from slot_reference_probe import references

class ReferenceProbeTest(unittest.TestCase):
    def probe(self, change=None):
        records={'manifest.json':{'schemaVersion':8,'sessionId':'s','packageRef':{'fileName':'fake.cbsave'}},
          'messages.jsonl':{'id':'m','currentAlternativeVersionId':'v','images':['chatbar-save-slot-image:i']},
          'voices.jsonl':{'messageId':'m','messageVersionId':'v','audioPath':'chatbar-save-slot-audio:a'}}
        if change:change(records)
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            for name,value in records.items():z.writestr(name,json.dumps(value))
            z.writestr('rag.jsonl','');z.writestr('media/images/i.png',b'fake');z.writestr('media/audio/a.mp3',b'fake')
        data.seek(0)
        with zipfile.ZipFile(data) as z:return references(z)
    def test_valid_is_only_partial_diagnostic(self):
        r=self.probe();self.assertEqual({},r['counts']);self.assertFalse(r['restore_authorized'])
    def test_voice_version(self):
        r=self.probe(lambda x:x['voices.jsonl'].update(messageVersionId='missing-secret-looking-value'))
        self.assertEqual({'VOICE_VERSION_MISSING':1,'VOICE_VERSION_EXPLICIT_SET_MISMATCH':1},r['counts']);self.assertNotIn('secret',json.dumps(r))
    def test_derived_link(self):
        r=self.probe(lambda x:x['messages.jsonl'].update(generatedFromMessageId='missing'))
        self.assertEqual({'DERIVED_MESSAGE_MISSING':1},r['counts'])
    def test_metadata_link(self):
        r=self.probe(lambda x:x['messages.jsonl'].update(generatedImageMetadata=[{'imagePath':'absent'}]))
        self.assertEqual({'GENERATED_IMAGE_NOT_IN_IMAGES':1},r['counts'])

if __name__=='__main__':unittest.main()
