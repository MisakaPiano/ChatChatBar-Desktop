from pathlib import Path
import shutil,hashlib
G=Path(__file__).resolve().parent;old=G.parent/'p8-s2b-f2-r2-20261018'
for name in ['F2UiSupport.java','manual-supervisor.py','runtime-watch.ps1']:
    text=(old/name).read_text(encoding='utf-8-sig').replace('p8-s2b-f2-r2-20261018',G.name)
    (G/name).write_text(text,encoding='utf-8')
fixtures={'live-official-sample.mp3':'c730e4c467f8dcf465acca9e426ea6349137e3302ff65e648f66dc2a63e3301f','synthetic-tts-fixture.mp3':'7b057f372a03dcb208e0b0e40fd10278ca19baa9faa47b9be9c1e140bc8eb530'}
for name,expected in fixtures.items():
    assert hashlib.sha256((old/name).read_bytes()).hexdigest()==expected
    shutil.copyfile(old/name,G/name)
print('Reused only validation helper text and two hash-verified non-user audio fixtures; no live request.')
