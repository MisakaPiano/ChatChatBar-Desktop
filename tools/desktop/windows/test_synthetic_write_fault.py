"""Synthetic fault evidence reader; fake Registry and tiny file, no MSI execution."""
import ctypes as C
import hashlib
import os
from pathlib import Path
import unittest
from build_directory_guard import pe_exports

FILE=C.WINFUNCTYPE(C.c_int,C.c_wchar_p,C.c_wchar_p)
REGISTRY=C.WINFUNCTYPE(C.c_int,C.c_wchar_p,C.c_wchar_p,C.c_wchar_p)


class SyntheticWriteEvidence(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lib=C.WinDLL(os.environ['CCB_WRITE_FAULT_TEST_DLL'])
        cls.lib.CcbTestSyntheticWriteEvidence.argtypes=[C.c_wchar_p,FILE,REGISTRY]
        cls.lib.CcbTestSyntheticFileHash.argtypes=[C.c_wchar_p,C.c_wchar_p]

    def data(self):
        return ['CCB_SYNTHETIC_AFTER_WRITES_V1','{11111111-1111-4111-8111-111111111111}','0.1.1114',r'C:\Synthetic\Program',r'app\new.jar','b'*64]

    def invoke(self,parts,file_state=1,registry_state=1):
        calls=[]
        @REGISTRY
        def registry(product,version,root):
            calls.append(('registry',product,version,root));return registry_state
        @FILE
        def file(path,digest):
            calls.append(('file',path,digest));return file_state
        return self.lib.CcbTestSyntheticWriteEvidence('|'.join(parts),file,registry),calls

    def test_both_new_resource_proofs_are_required(self):
        passed,calls=self.invoke(self.data());self.assertEqual(1,passed);self.assertEqual(['registry','file'],[r[0] for r in calls])
        self.assertEqual(r'C:\Synthetic\Program\app\new.jar',calls[1][1])
        for file_state,registry_state in [(0,1),(-1,1),(1,0),(1,-1)]:
            self.assertEqual(0,self.invoke(self.data(),file_state,registry_state)[0])

    def test_old_or_unavailable_registry_never_reads_file(self):
        for status in (0,-1,2):
            accepted,calls=self.invoke(self.data(),registry_state=status)
            self.assertEqual(0,accepted);self.assertEqual(['registry'],[r[0] for r in calls])

    def test_invalid_protocol_identity_or_paths_do_not_touch_resources(self):
        for index,value in [(0,'UNKNOWN'),(1,'not-a-guid'),(2,'1.2'),(3,r'\\server\share'),(3,'C:'+chr(92)),
                            (3,r'C:\Synthetic\..\Data'),(4,r'..\new.jar'),(4,r'C:\outside.jar'),(4,'CON'),(4,'NUL.txt'),(5,'not-a-digest')]:
            parts=self.data();parts[index]=value
            accepted,calls=self.invoke(parts);self.assertEqual((0,[]),(accepted,calls))
        for parts in (self.data()[:-1],self.data()+['extra']):
            self.assertEqual((0,[]),self.invoke(parts))

    def test_install_directory_trailing_separator_is_normalized(self):
        parts=self.data();parts[3]+='\\'
        accepted,calls=self.invoke(parts);self.assertEqual(1,accepted);self.assertEqual(r'C:\Synthetic\Program',calls[0][3])

    def test_real_file_hash_is_readonly_and_wrong_or_missing_bytes_refuse(self):
        root=Path(os.environ['CCB_WRITE_FAULT_TEST_ROOT']);root.mkdir()
        file=root/'new.jar';payload=b'INVENTED_NEW_PAYLOAD_ONLY';file.write_bytes(payload)
        digest=hashlib.sha256(payload).hexdigest();before=file.stat().st_mtime_ns
        self.assertEqual(1,self.lib.CcbTestSyntheticFileHash(str(file),digest))
        self.assertEqual(0,self.lib.CcbTestSyntheticFileHash(str(file),'0'*64))
        self.assertEqual(0,self.lib.CcbTestSyntheticFileHash(str(root/'missing.jar'),digest))
        self.assertEqual(payload,file.read_bytes());self.assertEqual(before,file.stat().st_mtime_ns)
        alias=root/'hardlink.jar';os.link(file,alias)
        self.assertEqual(0,self.lib.CcbTestSyntheticFileHash(str(alias),digest))

    def test_packaged_form_has_only_synthetic_failure_export(self):
        dll=Path(os.environ['CCB_WRITE_FAULT_PRODUCTION_DLL'])
        self.assertEqual({'CcbSyntheticFailAfterWrites'},pe_exports(dll))
        for forbidden in [b'RegSetValue',b'CryptProtectData',b'CryptUnprotectData',b'MsiInstallProduct',b'DeleteFile',b'RemoveDirectory']:
            self.assertNotIn(forbidden,dll.read_bytes())


if __name__=='__main__':unittest.main()
