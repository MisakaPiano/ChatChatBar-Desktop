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
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', installer_version) or any(int(p) > 65535 for p in installer_version.split('.')):
        raise ValueError('WINDOWS_INSTALLER_VERSION_REQUIRED')
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
    required_space = max(805306368, sum(e['bytes'] for e in report['files']) + 67108864)
    launcher = output / 'ChatChatBar.exe'
    manager = output / 'install-manager.exe'
    csc = Path(os.environ.get('WINDIR', 'C:/Windows')) / 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    args = [str(csc), '/nologo', '/target:winexe', '/platform:x64', '/optimize+',
            '/reference:System.Windows.Forms.dll', '/reference:System.Web.Extensions.dll',
            '/win32icon:' + str(ROOT / 'app/desktopApp/src/main/resources/brand/ccb.ico'),
            '/out:' + str(manager)]
    if profile == 'synthetic':
        args += ['/define:SYNTHETIC']
    subprocess.run(args + [str(Path(__file__).with_name('Launcher.cs'))], check=True)
    stable_args = [a for a in args if not a.startswith('/out:')] + ['/out:' + str(launcher), '/define:STABLE']
    subprocess.run(stable_args + [str(Path(__file__).with_name('Launcher.cs'))], check=True)
    manifest = dict(product=product, version=version, commit=commit, files=[*report['files'],
                    dict(path=manager.name, bytes=manager.stat().st_size, sha256=sha(manager))])
    manifest_path = output / 'install-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    # Explicit file list: no wildcard can pull private or newly appeared files into the installer.
    longest = max(len(e['path'].encode('utf-16-le')) // 2 for e in report['files']) + len(version_id) + 11
    script = ['Unicode true', '!include "x64.nsh"', '!include "nsDialogs.nsh"', 'Var Repair', 'Var RepairCheck', 'Var TargetId', 'RequestExecutionLevel user', 'SetCompressor /SOLID lzma',
              f'VIProductVersion "{installer_version}.0"', f'VIAddVersionKey "ProductName" "{label}"',
              f'VIAddVersionKey "FileDescription" "{label} Installer"',
              f'VIAddVersionKey "ProductVersion" "{version}"', f'VIAddVersionKey "FileVersion" "{installer_version}.0"',
              'SetOverwrite off', f'Name "{label}"', 'OutFile ' + quote(output / 'ChatChatBar-Setup.exe'),
              f'InstallDir "$LOCALAPPDATA\\Programs\\{folder}"',
              f'InstallDirRegKey HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "InstallLocation"',
              'Page directory', 'Page custom RepairPage RepairPageLeave', 'Page instfiles',
              'UninstPage uninstConfirm', 'UninstPage instfiles']
    for function in ('.onInit', 'un.onInit'):
        script += [f'Function {function}', 'SetRegView 64', 'StrCpy $Repair 0', f'StrCpy $TargetId "{version_id}"',
                   '${IfNot} ${IsNativeAMD64}', 'SetErrorLevel 2', 'Quit', '${EndIf}',
                   f'System::Call \'kernel32::CreateMutexW(p 0, i 0, w "Local\\{folder}-Extraction") p.r9 ?e\'',
                   'Pop $0', 'IntCmp $0 183 0 +3 +3', 'SetErrorLevel 2', 'Quit', 'FunctionEnd']
    script += ['Function RepairPage', 'IfFileExists "$INSTDIR\\install-owner.txt" +2 0', 'Abort',
               'nsDialogs::Create 1018', 'Pop $0', '${If} $0 == error', 'Abort', '${EndIf}',
               '${NSD_CreateLabel} 0 0 100% 28u "升级会保留资料。若程序损坏，可以从这份安装包修复程序。"', 'Pop $0',
               '${NSD_CreateCheckbox} 0 36u 100% 36u "修复已有程序（保留原程序副本，不更改资料与凭据）"', 'Pop $RepairCheck',
               'nsDialogs::Show', 'FunctionEnd', 'Function RepairPageLeave',
               '${NSD_GetState} $RepairCheck $Repair', 'FunctionEnd']
    script += ['Section "Install"',
              'StrLen $0 "$INSTDIR"', f'IntOp $0 $0 + {longest}',
              'StrCmp $Repair 1 0 +2', 'IntOp $0 $0 + 34',
              'IntCmp $0 245 path_ok path_ok failed', 'path_ok:',
              'InitPluginsDir', 'SetOutPath "$PLUGINSDIR"', 'File /oname=manager.exe ' + quote(manager),
              'StrCmp $Repair 1 prepare_repair',
              f'ExecWait \'"$PLUGINSDIR\\manager.exe" --prepare "$INSTDIR" {required_space} --quiet\' $0', 'Goto prepared',
              'prepare_repair:',
              f'ExecWait \'"$PLUGINSDIR\\manager.exe" --prepare-repair "$INSTDIR" {required_space} --quiet\' $0',
              'SetOverwrite on', 'prepared:', 'IntCmp $0 0 +2', 'Goto failed',
              'ClearErrors', 'SetOutPath "$INSTDIR"', 'IfErrors failed', 'File ' + quote(launcher), 'IfErrors failed',
              'WriteUninstaller "$INSTDIR\\Uninstall.exe"', 'IfErrors failed',
              'ExecWait \'"$PLUGINSDIR\\manager.exe" --register "$INSTDIR" --quiet\' $0',
              'IntCmp $0 0 +2', 'Goto failed',
              'ClearErrors', 'SetOutPath "$PLUGINSDIR"', 'IfErrors failed', 'File ' + quote(manifest_path), 'IfErrors failed',
              'StrCmp $Repair 1 stage_repair',
              f'ExecWait \'"$PLUGINSDIR\\manager.exe" --stage "$INSTDIR" "{version_id}" "$PLUGINSDIR\\install-manifest.json" --quiet\' $0',
              'Goto staged', 'stage_repair:',
              f'ExecWait \'"$PLUGINSDIR\\manager.exe" --stage-repair "$INSTDIR" "{version_id}" "$PLUGINSDIR\\install-manifest.json" --quiet\' $0',
              'IntCmp $0 0 +2', 'Goto failed', 'ClearErrors', 'FileOpen $1 "$INSTDIR\\pending-repair.txt" r',
              'IfErrors failed', 'FileRead $1 $TargetId', 'FileClose $1',
              'staged:', 'IntCmp $0 10 already_installed',
              'IntCmp $0 0 +2', 'Goto failed']
    # Validate the variable before NSIS uses it as an extraction path. A mutable
    # local plan is not allowed to choose a directory, even inside an owned root.
    script += ['StrCmp $Repair 1 validate_repair_id normal_target_id', 'normal_target_id:',
               f'StrCmp $TargetId "{version_id}" target_id_valid failed', 'validate_repair_id:',
               'StrLen $4 $TargetId', f'IntCmp $4 {len(version_id) + 34} repair_prefix failed failed',
               'repair_prefix:', f'StrCpy $2 $TargetId {len(version_id)}',
               f'StrCmp $2 "{version_id}" repair_suffix failed', 'repair_suffix:',
               f'StrCpy $2 $TargetId 2 {len(version_id)}', 'StrCmp $2 "-r" repair_hex_begin failed',
               'repair_hex_begin:', f'StrCpy $3 {len(version_id) + 2}', 'repair_hex_loop:', 'StrCpy $2 $TargetId 1 $3']
    script += [f'StrCmp $2 "{character}" repair_hex_valid' for character in '0123456789abcdef']
    script += ['Goto failed', 'repair_hex_valid:', 'IntOp $3 $3 + 1',
               'IntCmp $3 $4 target_id_valid repair_hex_loop failed', 'target_id_valid:']
    for e in report['files']:
        relative = Path(e['path'])
        parent = str(relative.parent).replace('/', '\\')
        suffix = '' if parent == '.' else '\\' + parent
        script += ['ClearErrors', f'SetOutPath "$INSTDIR\\versions\\$TargetId{suffix}"', 'IfErrors failed',
                   'File ' + quote(image / relative), 'IfErrors failed']
    script += ['ClearErrors', 'SetOutPath "$INSTDIR\\versions\\$TargetId"', 'IfErrors failed', 'File ' + quote(manager), 'IfErrors failed',
               'already_installed:', 'SetOutPath "$INSTDIR"',
               f'ExecWait \'"$PLUGINSDIR\\manager.exe" --activate "$INSTDIR" "$TargetId" --quiet\' $0',
               'IntCmp $0 0 +2', 'Goto failed',
               'ClearErrors',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "DisplayName" "{label}"',
               'IfErrors failed',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "DisplayVersion" "{version}"',
               'IfErrors failed',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "UninstallString" \'"$INSTDIR\\Uninstall.exe"\'',
               'IfErrors failed',
               f'WriteRegStr HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "InstallLocation" "$INSTDIR"',
               'IfErrors failed',
               f'WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "NoModify" 1',
               'IfErrors failed',
               f'WriteRegDWORD HKCU "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{key}" "NoRepair" 1',
               'IfErrors failed',
               'ExecWait \'"$PLUGINSDIR\\manager.exe" --cleanup "$INSTDIR" --quiet\' $0',
               'FileOpen $0 "$INSTDIR\\cleanup-status.txt" r', 'FileRead $0 $1', 'FileClose $0',
               'StrCmp $1 "DEFERRED" 0 +2', 'DetailPrint "新版已确认；部分旧程序因占用、改动或未知文件保留。管理记录位于当前用户 LocalAppData 的 ChatChatBarDesktopNSISInstallState。"',
               'SetErrorLevel 0', 'Goto done', 'failed:', 'SetErrorLevel 2',
               'IfSilent +2', 'MessageBox MB_OK|MB_ICONSTOP "安装未完成。请检查程序与资料目录是否分离、剩余空间和程序占用；若有启动错误窗口，请先确认关闭。旧入口和用户资料保留。"',
               'Quit', 'done:', 'SectionEnd', 'Section "Uninstall"',
               'InitPluginsDir', 'SetOutPath "$PLUGINSDIR"', 'File /oname=manager.exe ' + quote(manager),
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
