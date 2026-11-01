"""Closed setup exit-code protocol. Never infer the current failure from a retained log tail."""
import re

DIAGNOSTICS = {
    20: ('PROGRAM_DATA_ROOT_OVERLAP', '安装位置与当前资料目录重叠。请先从现用程序迁移到安装目录之外的独立资料目录，正常退出重开后再更新；保留旧目录。'),
    21: ('DATA_INSTALL_STATE_OVERLAP', '资料目录与安装管理目录重叠。请保留原目录，从现用程序检查并迁移资料；不要删除管理文件来绕过。'),
    22: ('INSTALLATION_BUSY', '另一项安装或修复操作仍在运行。请等待该操作结束，再运行本安装包。'),
    23: ('INSTALL_BUSY', '此程序的安装管理操作尚未结束。请等待已有操作完成后再试。'),
    24: ('PARENT_BUSY', '程序目录仍被占用。请正常关闭相关程序后再试；不要强制移动或删除资料。'),
    25: ('FILE_BUSY', '程序文件仍被占用。请正常关闭相关程序后再试；原资料保留。'),
    26: ('INSTALL_SPACE_REQUIRED', '安装磁盘的可用空间不足。请释放空间或选择其他程序位置；保留原资料。'),
    27: ('NEW_VERSION_NOT_READY_OLD_ENTRY_RETAINED', '新版未完成启动确认，旧入口保留。请保留启动错误提示和日志，正常关闭已有程序；不要删除资料或修复标记。'),
    28: ('READY_IDENTITY_REFUSED', '新版启动回执身份未通过检查，旧入口保留。请保留日志和启动错误提示，勿删除资料或修复标记。'),
    29: ('READY_PROCESS_REFUSED', '新版启动进程回执未通过检查，旧入口保留。请保留日志和启动提示。'),
    30: ('READY_PROCESS_EXITED', '新版在启动确认前退出，旧入口保留。请保留启动错误提示和日志，勿删除资料或修复标记。'),
    31: ('PROGRAM_FILES_IN_USE', '程序文件仍被使用或锁定。请正常关闭已有程序和安装操作后再试；原资料保留。'),
}


def decorate_manager_calls(lines):
    result = []
    for line in lines:
        if line.startswith('ExecWait ') and '$PLUGINSDIR\\manager.exe' in line:
            match = re.search(r'manager\.exe" (--[a-z-]+)', line)
            if not match or " --quiet' $0" not in line:
                raise ValueError('MANAGER_DIAGNOSTIC_CALL_SHAPE')
            result += [f'StrCpy $DiagnosticPhase "{match[1]}"', 'StrCpy $0 -1', 'ClearErrors',
                       line.replace(" --quiet' $0", " --quiet --diagnostic-exit' $0"),
                       'StrCpy $DiagnosticExit $0']
        else:
            result.append(line)
    return result


def diagnostic_function(folder, uninstall=False):
    if not re.fullmatch(r'ChatChatBarDesktopNSIS(?:Synthetic)?', folder):
        raise ValueError('DIAGNOSTIC_REGISTRATION_REFUSED')
    name = ('un.' if uninstall else '') + 'ShowInstallerFailure'
    lines = [f'Function {name}', 'SetErrorLevel 2',
             'StrCpy $DiagnosticCode "SETUP_STEP_FAILED"',
             'StrCpy $DiagnosticText "本次安装未完成。请保留现场并检查下方日志目录；不要删除用户资料。"',
             'StrCmp $DiagnosticExit 0 diagnostic_files', 'StrCmp $DiagnosticExit 10 diagnostic_files diagnostic_codes',
             'diagnostic_files:', 'StrCpy $DiagnosticPhase "安装文件或设置"', 'diagnostic_codes:']
    lines += [f'StrCmp $DiagnosticExit {code} diagnostic_{code}' for code in DIAGNOSTICS]
    lines += ['Goto diagnostic_message']
    for code, (identity, message) in DIAGNOSTICS.items():
        lines += [f'diagnostic_{code}:', f'StrCpy $DiagnosticCode "{identity}"',
                  f'StrCpy $DiagnosticText "{message}"', 'Goto diagnostic_message']
    lines += ['diagnostic_message:', 'ReadEnvStr $DiagnosticLog "LOCALAPPDATA"',
              f'StrCpy $DiagnosticLog "$DiagnosticLog\\{folder}InstallState"',
              'DetailPrint "本次诊断代码: $DiagnosticCode"', 'DetailPrint "本次管理步骤: $DiagnosticPhase"',
              'DetailPrint "日志目录（若本次已写入）: $DiagnosticLog"', 'IfSilent diagnostic_quit',
              'MessageBox MB_OK|MB_ICONSTOP "$DiagnosticText$\\r$\\n$\\r$\\n诊断代码：$DiagnosticCode$\\r$\\n管理步骤：$DiagnosticPhase$\\r$\\n日志目录（若本次已写入）：$DiagnosticLog$\\r$\\n若目录不存在或无法打开，请保留此诊断代码。"',
              'diagnostic_quit:', 'Quit', 'FunctionEnd']
    return lines
