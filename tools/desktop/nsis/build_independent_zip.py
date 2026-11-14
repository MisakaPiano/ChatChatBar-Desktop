"""Package an audited ordinary image with an isolated Windows profile launcher for manual migration tests."""
import argparse
import hashlib
import json
from pathlib import Path
import re
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

def build(image, destination, version, commit, installer_version, lan_sync_preview=False):
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
    if lan_sync_preview:
        series = re.fullmatch(r'0\.1\.0-lan\.([1-9][0-9]*)', version)
        if series is None:
            raise ValueError('LAN_PREVIEW_VERSION_REQUIRED')
        guide = ('CCB 局域网独立预览 ' + version + '\n\n'
            '完整解压到新的本机目录，双击“启动独立资料版.cmd”。不要直接运行 Program 内的 EXE。\n'
            '本入口仅使用旁边的 UserData，保留日常版资料；本包不连接公开更新通道。\n'
            '电脑：数据 → 局域网同步。安卓旁装预览：管理 → 局域网同步。保持两端页面打开。\n'
            '电脑生成二维码，手机扫码，核对短设备身份并确认；之后点击同步／接续即可。\n'
            '可先在一端点“载入离线验收样例”，同步后打开会话，编辑样例消息并再次同步；无需密钥或付费生成。\n'
            '同步时无需手工导出、搬运或选择备份。具体冲突在电脑逐项选择，未支持的数据不参与删除。\n'
            '这是独立资料预览，不代表官方安卓已支持，也未接管原版手机资料。\n')
        if int(series.group(1)) >= 5:
            guide = ('CCB 设备组独立预览 ' + version + '\n\n'
                '完整解压到新的本机目录，双击“启动独立资料版.cmd”。不要直接运行 Program 内的 EXE。\n'
                '本入口使用旁边的 UserData，保留日常版资料；本包不连接公开更新通道。保留既有预览的完整 UserData，升级时不要覆盖或清空它。\n'
                '电脑：数据 → 局域网同步。安卓独立预览：管理 → 局域网同步，进入设备组同步。\n'
                '首次只在一台设备创建新设备组，妥善保存恢复码并完成创建；其他设备扫描或粘贴已有组的短时邀请。\n'
                '在已有设备核对新设备身份并确认加入。管理锁定时需临时输入恢复码；页面关闭会清除临时管理信息。\n'
                '加入完成后可离开同步页面，在应用内编辑资料。电脑需保持程序运行；安卓请查看同步保护状态和通知，不能保证被系统结束后仍在线。\n'
                '两端在同一局域网时检查已加入设备和同步状态。手机通知或同步页可停止设备组同步，停止后需明确恢复，普通切回应用不会取消暂停。\n'
                '新增或普通修改会自动检查。删除、覆盖缩减和冲突请打开待审阅项，核对冻结详情后确认；未确认的删除继续保留。\n'
                '“本机保全与恢复”可浏览已保全内容，先准备恢复目标，再核对并确认；保留两端原始资料与 UserData。\n'
                '本候选的网盘送达仍待后续接入与验证；本机副本记录不代表其他设备或网盘已收到。\n'
                '这是独立资料预览，不接管日常版或原版手机资料；具体增量验收以随包交付记录为准。\n')
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
    parser.add_argument('--lan-sync-preview', action='store_true')
    build(**vars(parser.parse_args()))
