"""Package an audited jpackage app-image with the Desktop-owned NSIS lifecycle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from audit_app_image import audit, regular

ROOT = Path(__file__).resolve().parents[3]


def sha(path):
    with path.open('rb') as source:
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
        return digest.hexdigest()


def quote(value):
    value = str(value).replace('/', '\\')
    if any(c in value for c in '$"\r\n'):
        raise ValueError('NSIS_UNSUPPORTED_PATH')
    return '"' + value + '"'


def build(image, output, version, commit, installer_version, profile, compiler):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-preview\.[1-9][0-9]*', version):
        raise ValueError('PREVIEW_VERSION_REQUIRED')
    image = image.absolute()
    output = output.absolute()
    regular(image, directory=True)
    regular(output.parent, directory=True)
    if output.exists() or output.is_relative_to(image):
        raise ValueError('NEW_EXTERNAL_OUTPUT_REQUIRED')
    expected = dict(version=version, commit=commit, installerVersion=installer_version,
                    installerVersionAssigned='true', packagingProfile='desktop')
    report = audit(image, expected)
    output.mkdir()
    (output / 'app-image-audit.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    label = 'ChatChatBar Desktop' + (' NSIS Synthetic' if profile == 'synthetic' else '')
    product = 'ccb-desktop-nsis-' + ('synthetic-v1' if profile == 'synthetic' else 'v1')
    folder = 'ChatChatBarDesktopNSIS' + ('Synthetic' if profile == 'synthetic' else '')
    key = folder
    version_id = version + '-' + commit[:12]
    launcher = output / 'ChatChatBar.exe'
    csc = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    args = [str(csc), '/nologo', '/target:winexe', '/platform:x64', '/optimize+',
            '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
            '/win32icon:' + str(ROOT / 'app/desktopApp/src/main/resources/brand/ccb.ico'),
            '/out:' + str(launcher)]
    if profile == 'synthetic':
        args += ['/define:SYNTHETIC']
    subprocess.run(args + [str(Path(__file__).with_name('Launcher.cs'))], check=True)
    manifest = dict(product=product, version=version, commit=commit, files=report['files'])
    manifest_path = output / 'install-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    # Explicit file list: no wildcard can pull private or newly appeared files into the installer.
    longest = max(len(e['path'].encode('utf-16-le')) // 2 for e in report['files']) + len(version_id) + 11
    script = ['Unicode true', 'RequestExecutionLevel user', 'SetCompressor /SOLID lzma',
              'SetOverwrite off', f'Name "{label}"', 'OutFile ' + quote(output / 'ChatChatBar-Setup.exe'),
              f'InstallDir "$LOCALAPPDATA\\Programs\\{folder}"', 'Page directory', 'Page instfiles',
              'UninstPage uninstConfirm', 'UninstPage instfiles']
    for function in ('.onInit', 'un.onInit'):
        script += [f'Function {function}',
                   f'System::Call \'kernel32::CreateMutexW(p 0, i 0, w "Local\\{folder}-Extraction") p.r9 ?e\'',
                   'Pop $0', 'IntCmp $0 183 0 +3 +3', 'SetErrorLevel 2', 'Quit', 'FunctionEnd']
    script += ['Section "Install"',
              'StrLen $0 "$INSTDIR"', f'IntOp $0 $0 + {longest}',
              'IntCmp $0 245 path_ok path_ok failed', 'path_ok:',
              'InitPluginsDir', 'SetOutPath "$PLUGINSDIR"', 'File /oname=manager.exe ' + quote(launcher),
              'ExecWait \'"$PLUGINSDIR\\manager.exe" --prepare "$INSTDIR" --quiet\' $0',
              'IntCmp $0 0 +2', 'Goto failed',
              'ClearErrors', 'SetOutPath "$INSTDIR"', 'IfErrors failed', 'File ' + quote(launcher), 'IfErrors failed',
              'WriteUninstaller "$INSTDIR\\Uninstall.exe"', 'IfErrors failed',
              'ExecWait \'"$PLUGINSDIR\\manager.exe" --register "$INSTDIR" --quiet\' $0',
              'IntCmp $0 0 +2', 'Goto failed',
              'ClearErrors', 'SetOutPath "$PLUGINSDIR"', 'IfErrors failed', 'File ' + quote(manifest_path), 'IfErrors failed',
              f'ExecWait \'"$PLUGINSDIR\\manager.exe" --stage "$INSTDIR" "{version_id}" "$PLUGINSDIR\\install-manifest.json" --quiet\' $0',
              'IntCmp $0 0 +2', 'Goto failed']
    for e in report['files']:
        relative = Path(e['path'])
        parent = str(relative.parent).replace('/', '\\')
        suffix = '' if parent == '.' else '\\' + parent
        script += ['ClearErrors', f'SetOutPath "$INSTDIR\\versions\\{version_id}{suffix}"', 'IfErrors failed',
                   'File ' + quote(image / relative), 'IfErrors failed']
    script += ['SetOutPath "$INSTDIR"',
               f'ExecWait \'"$PLUGINSDIR\\manager.exe" --activate "$INSTDIR" "{version_id}" --quiet\' $0',
               'IntCmp $0 0 +2', 'Goto failed',
               'FileOpen $0 "$INSTDIR\\cleanup-status.txt" r', 'FileRead $0 $1', 'FileClose $0',
               'StrCmp $1 "DEFERRED" 0 +2', 'DetailPrint "新版已确认；部分旧程序因占用、改动或未知文件保留。详情见安装目录 lifecycle.log。"',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "DisplayName" "{label}"',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "DisplayVersion" "{version}"',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "UninstallString" \'"$INSTDIR\\Uninstall.exe"\'',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "InstallLocation" "$INSTDIR"',
               f'WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "NoModify" 1',
               f'WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "NoRepair" 1',
               'SetErrorLevel 0', 'Goto done', 'failed:', 'SetErrorLevel 2',
               'IfSilent +2', 'MessageBox MB_OK|MB_ICONSTOP "安装未完成。请正常关闭程序后重试；旧入口和用户资料保留。"',
               'Quit', 'done:', 'SectionEnd', 'Section "Uninstall"',
               'InitPluginsDir', 'SetOutPath "$PLUGINSDIR"', 'File /oname=manager.exe ' + quote(launcher),
               'ExecWait \'"$PLUGINSDIR\\manager.exe" --uninstall "$INSTDIR" --quiet\' $0',
               'IntCmp $0 0 +2', 'Goto un_failed',
               f'ReadRegStr $0 HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "InstallLocation"',
               'StrCmp $0 "$INSTDIR" 0 un_failed',
               f'DeleteRegKey HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}"',
               'SetErrorLevel 0', 'Goto un_done', 'un_failed:', 'SetErrorLevel 2',
               'IfSilent +2', 'MessageBox MB_OK|MB_ICONEXCLAMATION "部分程序仍在使用或已改动，已保留。请关闭后重试。用户资料不会删除。"',
               'un_done:', 'SectionEnd']
    source = output / 'installer.nsi'
    source.write_text('\n'.join(script) + '\n', encoding='utf-8-sig')
    subprocess.run([str(compiler.absolute()), str(source)], check=True)
    # Recheck source image: the package must not claim the identity of changing inputs.
    if audit(image, expected) != report:
        raise ValueError('IMAGE_CHANGED_DURING_PACKAGING')
    receipt = dict(product=product, version=version, commit=commit, installerSha256=sha(output / 'ChatChatBar-Setup.exe'),
                   launcherSourceSha256=sha(Path(__file__).with_name('Launcher.cs')),
                   compilerSha256=sha(compiler), acceptance='NOT_TESTED')
    (output / 'package.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print('NSIS_PACKAGE_BUILT ' + receipt['installerSha256'])


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--image', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--version', required=True)
    p.add_argument('--commit', required=True)
    p.add_argument('--installer-version', required=True)
    p.add_argument('--profile', choices=['desktop', 'synthetic'], default='desktop')
    p.add_argument('--makensis', type=Path, required=True)
    options = vars(p.parse_args())
    options['compiler'] = options.pop('makensis')
    build(**options)
