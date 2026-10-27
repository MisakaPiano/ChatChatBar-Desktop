"""Build a separately exported, always-failing test-only Header probe. Never runs MSI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from build_directory_guard import pe_exports


def build(output, test=False):
    source = Path(__file__).resolve().parent
    output = Path(output).resolve()
    owned = source.parents[2] / 'app/desktopApp/build'
    assert output.is_relative_to(owned) and not output.exists(), 'FRESH_OWNED_PROBE_BUILD_REQUIRED'
    assert not any(p.is_symlink() or (p.exists() and p.stat().st_file_attributes & 0x400)
                   for p in [output, *output.parents]), 'PROBE_REPARSE_REFUSED'
    output.mkdir(parents=True)
    identities = {}
    for name in ('directory_guard.c', 'operation_guard.h', 'header_probe_only.h'):
        data = (source / name).read_bytes()
        (output / name).write_bytes(data)
        identities[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    vswhere = Path(os.environ['ProgramFiles(x86)']) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    installation = subprocess.check_output([str(vswhere), '-latest', '-products', '*', '-requires',
        'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'], text=True).strip()
    setup = Path(installation) / 'VC/Auxiliary/Build/vcvars64.bat'
    assert setup.is_file(), 'NATIVE_COMPILER_REQUIRED'
    arguments = ['cl.exe', '/nologo', '/W4', '/WX', '/O2', '/MT', '/LD',
        '/DWINVER=0x0601', '/D_WIN32_WINNT=0x0601', '/DCCB_HEADER_PROBE_ONLY=1']
    if test:
        arguments.append('/DCCB_GUARD_TEST=1')
    arguments += [str(output / 'directory_guard.c'), '/link', '/OUT:' + str(output / 'header_probe.dll'),
                  'msi.lib', 'advapi32.lib', 'shell32.lib', 'ole32.lib', 'uuid.lib', 'bcrypt.lib']
    assert all(not any(c in str(v) for c in '&|<>^%!\r\n') for v in [setup, *arguments])
    command = 'cmd.exe /d /s /c "call "' + str(setup) + '" >nul && ' + subprocess.list2cmdline(arguments) + '"'
    (output / 'compile-command.json').write_text(json.dumps(command) + '\n', encoding='utf-8')
    with (output / 'compile.log').open('xb') as log:
        result = subprocess.run(command, cwd=output, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    (output / 'compile.exit').write_text(str(result.returncode), encoding='ascii')
    assert result.returncode == 0, 'HEADER_PROBE_COMPILE_FAILED'
    dll = output / 'header_probe.dll'
    exports = pe_exports(dll)
    assert 'CcbHeaderDiagnosticOnly' in exports
    assert not {'CcbResolveInstallDirectory', 'CcbValidateOperation'} & exports
    if not test:
        assert exports == {'CcbHeaderDiagnosticOnly'}, 'PROBE_EXCLUSIVE_EXPORT_REQUIRED'
    (output / 'IDENTITY.json').write_text(json.dumps({'probeOnly': True, 'testExports': test,
        'sources': identities, 'sha256': hashlib.sha256(dll.read_bytes()).hexdigest(),
        'bytes': dll.stat().st_size, 'exports': sorted(exports), 'msiExecuted': False}, indent=2) + '\n', encoding='utf-8')
    return dll


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--test', action='store_true')
    args = parser.parse_args()
    print(build(args.output, args.test))
