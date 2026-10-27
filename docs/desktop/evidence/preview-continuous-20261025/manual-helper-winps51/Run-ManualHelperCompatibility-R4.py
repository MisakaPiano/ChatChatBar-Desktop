"""Actual WinPS5 helper validation; preflight only, no GUI or installer."""
import hashlib
import json
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
OUT = BASE / 'manual-helper-winps51-r4-20261025'
OUT.mkdir(exist_ok=False)
PS = Path(r'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe')
PARSER = BASE / 'manual-helper-winps51-20261025/Parse-Only.ps1'
HASHER = '''
function Get-IsolatedSha256([string]$LiteralPath) {
    $stream = [IO.File]::OpenRead($LiteralPath)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
'''

def run(label, args):
    command = [str(PS), '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', *args]
    (OUT / (label + '-command.json')).write_text(json.dumps(command, indent=2), encoding='utf-8')
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=240, check=False)
    (OUT / (label + '-stdout.log')).write_bytes(result.stdout)
    (OUT / (label + '-stderr.log')).write_bytes(result.stderr)
    (OUT / (label + '-exit.txt')).write_text(str(result.returncode) + '\n', encoding='ascii')
    return result

summary = []
for label in ('1006', '1008', 'admin-repair'):
    script = BASE / ('Run-AdminSyntheticRepair-Once.ps1' if label == 'admin-repair'
                     else 'windows-runtime-merge-ui-' + label + '/Open-Isolated-Preview-' + label + '.ps1')
    original = script.read_bytes()
    (OUT / (label + '-BEFORE.ps1')).write_bytes(original)
    before = run(label + '-before-parse', ['-File', str(PARSER), '-Target', str(script)])
    text = original.decode('utf-8')
    if label == 'admin-repair':
        text = text.replace('必须明确选择本次合成产品修复；尚未执行安装器。',
                            'EXPLICIT_SYNTHETIC_PRODUCT_CONFIRMATION_REQUIRED_INSTALLER_NOT_RUN')
        text = text.replace('需要你在管理员 PowerShell 中执行此一次合成产品修复。本脚本不会自行提权或重试。',
                            'ADMINISTRATOR_POWERSHELL_REQUIRED_NO_AUTOMATIC_ELEVATION_OR_RETRY')
        for name in ('probe', 'msi'):
            text = text.replace('(Get-FileHash -LiteralPath $' + name + ' -Algorithm SHA256).Hash',
                                '(Get-IsolatedSha256 -LiteralPath $' + name + ').ToUpperInvariant()')
    else:
        text = text.replace('已打开' + label + '隔离界面样本；其中账号为假值。查看后请正常关闭窗口。',
                            'ISOLATED_PREVIEW_' + label + '_OPENED_FAKE_ACCOUNTS_CLOSE_WINDOW_NORMALLY')
        text = text.replace('$rows = @(Get-Content -LiteralPath $inventory -Raw | ConvertFrom-Json)',
                            '$rows = Get-Content -LiteralPath $inventory -Raw | ConvertFrom-Json')
        text = text.replace('(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()',
                            '(Get-IsolatedSha256 -LiteralPath $file)')
    split = text.index('\n', text.index('$ErrorActionPreference')) + 1
    text = text[:split] + HASHER + text[split:]
    fixed = text.encode('ascii')
    if script.read_bytes() != original:
        raise RuntimeError('CONCURRENT_CHANGE_REFUSED')
    script.write_bytes(fixed)
    (OUT / (label + '-AFTER.ps1')).write_bytes(fixed)
    after = run(label + '-after-parse', ['-File', str(PARSER), '-Target', str(script)])
    if after.returncode:
        raise RuntimeError('FIXED_PARSE_FAILED_' + label)
    preflight = run(label + '-preflight', ['-File', str(script), '-PreflightOnly'])
    if preflight.returncode:
        raise RuntimeError('PREFLIGHT_FAILED_' + label)
    output = preflight.stdout.decode('utf-8-sig').strip()
    if label == 'admin-repair':
        info = json.loads(output)
        assert info['exactSyntheticProductVerified'] is True and info['installerExecuted'] is False
    else:
        assert output == 'ISOLATED_PREVIEW_' + label + '_READY_NO_APPLICATION_LAUNCHED'
    summary.append(dict(helper=label, beforeParseExit=before.returncode, afterParseExit=after.returncode,
                        preflightExit=preflight.returncode, asciiOnly=True,
                        beforeSha256=hashlib.sha256(original).hexdigest(),
                        afterSha256=hashlib.sha256(fixed).hexdigest()))

(OUT / 'RESULT.json').write_text(json.dumps(dict(helpers=summary, uiLaunched=False, installerExecuted=False,
    humanVisualAcceptance='NOT_TESTED', administratorRepair='AWAITING_USER_RESULT'), indent=2) + '\n', encoding='ascii')
print(json.dumps(summary))
