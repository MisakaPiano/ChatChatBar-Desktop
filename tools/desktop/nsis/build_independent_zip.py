"""Package an audited ordinary image with an isolated Windows profile launcher for manual migration tests."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'windows'))
from audit_app_image import audit, regular

LAUNCHER = '''@echo off
setlocal DisableDelayedExpansion
set "LOCALAPPDATA=%~dp0UserData\\Local"
set "APPDATA=%~dp0UserData\\Roaming"
set "TEMP=%~dp0UserData\\Temp"
set "TMP=%~dp0UserData\\Temp"
if not exist "%LOCALAPPDATA%" mkdir "%LOCALAPPDATA%"
if not exist "%APPDATA%" mkdir "%APPDATA%"
if not exist "%TEMP%" mkdir "%TEMP%"
start "" /D "%~dp0Program" "%~dp0Program\\ChatChatBarDesktop.exe"
'''.replace('\n', '\r\n')

def build(image, destination, version, commit, installer_version):
    image = image.absolute(); destination = destination.absolute()
    regular(destination.parent, directory=True)
    if destination.exists() or destination.is_relative_to(image):
        raise ValueError('NEW_EXTERNAL_OUTPUT_REQUIRED')
    expected = dict(version=version, commit=commit, installerVersion=installer_version,
                    installerVersionAssigned='true', packagingProfile='desktop')
    report = audit(image, expected)
    guide = ('ChatChatBar Desktop 独立资料版 ' + version + '\n\n'
        '完整解压到新的本机目录，再双击“启动独立资料版.cmd”。不要在压缩包内运行。\n'
        '请始终从这个入口启动；Program 内的 EXE 是普通程序，直接启动会使用通常的资料选择。\n'
        '本入口把资料选择、数据和 Windows 受保护凭据放在旁边的 UserData，保留你的日常版。\n'
        '导入：数据页 → 从手机备份合并数据 → 选择已有的 .cbbackup → 核对范围与冲突 → 选择一个新的独立目录。\n'
        '合并完成后正常退出，再双击同一启动入口；该入口会继续使用自己的资料选择。\n'
        '资料目录与程序目录分开。若需移动数据请使用应用内的数据目录迁移功能。\n'
        '本包是独立使用入口，不提供应用内自动安装重启或损坏程序修复。\n')
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for item in report['files']:
            source = image / item['path']
            if source.stat().st_size != item['bytes'] or hashlib.sha256(source.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('IMAGE_CHANGED_DURING_PACKAGING')
            archive.write(source, 'Program/' + item['path'])
        archive.writestr('启动独立资料版.cmd', LAUNCHER.encode('ascii'))
        archive.writestr('使用说明.txt', guide.encode('utf-8-sig'))
        archive.writestr('UserData/', b'')
    if audit(image, expected) != report:
        raise ValueError('IMAGE_CHANGED_DURING_PACKAGING')
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None or len(archive.namelist()) != report['fileCount'] + 3:
            raise ValueError('ARCHIVE_VERIFICATION_FAILED')
    receipt = dict(version=version, commit=commit, programFiles=report['fileCount'], bytes=destination.stat().st_size,
        sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
        kind='INDEPENDENT_ENVIRONMENT_PROFILE_NOT_PORTABLE_FLAG', nativeLifecycle='NOT_TESTED')
    destination.with_suffix('.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--installer-version', required=True)
    build(**vars(parser.parse_args()))
