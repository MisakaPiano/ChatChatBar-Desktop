"""Verify actual prepare-only/7-suite/launcher artifacts; no private-file search."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

base = Path(__file__).resolve().parent / 'raw'
assert 'Cannot mutate the dependencies' in (base/'red/prepare.log').read_text(encoding='utf-8-sig')
assert (base/'red/exit.txt').read_text(encoding='utf-8-sig').strip() == '1'
for name in ['prepare-before', 'prepare-after']:
    assert (base/name/'exit.txt').read_text(encoding='utf-8-sig').strip() == '0'
    log = (base/name/'output.log').read_text(encoding='utf-8-sig')
    assert 'BUILD SUCCESSFUL' in log and '> Task :desktopApp:test\n' not in log
    assert ':desktopApp:prepareRealArchiveReadOnlyR0' in log
rows = [ET.parse(p).getroot() for p in (base/'green/junit').glob('TEST-*.xml')]
assert len(rows) == 7 and sum(int(x.attrib['tests']) for x in rows) == 117
assert all(int(x.attrib[k]) == 0 for x in rows for k in ['failures', 'errors', 'skipped'])
assert (base/'green/exit.txt').read_text().strip() == '0'
assert json.loads((base/'launcher/exits.json').read_text(encoding='utf-8-sig')) == {'plain': 0, 'no-console': 2, 'dangerous-options': 2}
assert json.loads((base/'conpty-final/correct.json').read_text())['status'] == 'INCONCLUSIVE_NO_JAVA_CONSOLE'
print('PREPARE_ONLY_AND_JUNIT_AND_LAUNCHER=PASS;HUMAN_TERMINAL=NOT_TESTED')
