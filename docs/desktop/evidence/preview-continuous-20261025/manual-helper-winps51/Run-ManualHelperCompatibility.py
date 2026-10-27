"""Task-owned compatibility regression; never launches UI or an installer."""
import hashlib
import json
from pathlib import Path
import subprocess

BASE = Path(__file__).resolve().parent
OUT = BASE / 'manual-helper-winps51-20261025'
OUT.mkdir(exist_ok=False)
PS = Path(r'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe')
PARSER = OUT / 'Parse-Only.ps1'
PARSER.write_bytes(b'''param([string]$Target)
$ErrorActionPreference = 'Stop'
$tokens = $null
$errors = $null
$null = [Management.Automation.Language.Parser]::ParseFile($Target, [ref]$tokens, [ref]$errors)
@{version=$PSVersionTable.PSVersion.ToString();errors=@($errors | ForEach-Object { @{id=$_.ErrorId;line=$_.Extent.StartLineNumber;message=$_.Message} })} | ConvertTo-Json -Depth 5 -Compress
if ($errors.Count -ne 0) { exit 1 }
exit 0
''')

def run(label, args):
    command = [str(PS), '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', *args]
    (OUT / (label + '-command.json')).write_text(json.dumps(command, indent=2), encoding='utf-8')
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180, check=False)
    (OUT / (label + '-stdout.log')).write_bytes(result.stdout)
    (OUT / (label + '-stderr.log')).write_bytes(result.stderr)
    (OUT / (label + '-exit.txt')).write_text(str(result.returncode) + '\n', encoding='ascii')
    return result

rows = []
for version in ('1006', '1008'):
    script = BASE / ('windows-runtime-merge-ui-' + version) / ('Open-Isolated-Preview-' + version + '.ps1')
    rows.append((version, script, {
        "Write-Output '已打开" + version + "隔离界面样本；其中账号为假值。查看后请正常关闭窗口。'":
        "Write-Output 'ISOLATED_PREVIEW_" + version + "_OPENED_FAKE_ACCOUNTS_CLOSE_WINDOW_NORMALLY'"
    }))
rows.append(('admin-repair', BASE / 'Run-AdminSyntheticRepair-Once.ps1', {
    "throw '必须明确选择本次合成产品修复；尚未执行安装器。'": "throw 'EXPLICIT_SYNTHETIC_PRODUCT_CONFIRMATION_REQUIRED_INSTALLER_NOT_RUN'",
    "throw '需要你在管理员 PowerShell 中执行此一次合成产品修复。本脚本不会自行提权或重试。'": "throw 'ADMINISTRATOR_POWERSHELL_REQUIRED_NO_AUTOMATIC_ELEVATION_OR_RETRY'"
}))

summary = []
for label, script, replacements in rows:
    original = script.read_bytes()
    (OUT / (label + '-BEFORE.ps1')).write_bytes(original)
    before = run(label + '-before-parse', ['-File', str(PARSER), '-Target', str(script)])
    text = original.decode('utf-8')
    for old, new in replacements.items():
        if text.count(old) != 1:
            raise RuntimeError('EXPECTED_EXACT_SINGLE_REPLACEMENT_' + label)
        text = text.replace(old, new)
    fixed = text.encode('ascii')
    # Compare-and-write only the exact helper this run just captured.
    if script.read_bytes() != original:
        raise RuntimeError('CONCURRENT_HELPER_CHANGE_' + label)
    script.write_bytes(fixed)
    (OUT / (label + '-AFTER.ps1')).write_bytes(fixed)
    after = run(label + '-after-parse', ['-File', str(PARSER), '-Target', str(script)])
    if after.returncode != 0:
        raise RuntimeError('FIXED_PARSE_FAILED_' + label)
    preflight = run(label + '-preflight', ['-File', str(script), '-PreflightOnly'])
    if preflight.returncode != 0:
        raise RuntimeError('PREFLIGHT_FAILED_' + label)
    output = preflight.stdout.decode('utf-8-sig').strip()
    if label == 'admin-repair':
        info = json.loads(output)
        if info['exactSyntheticProductVerified'] is not True or info['installerExecuted'] is not False:
            raise RuntimeError('ADMIN_PREFLIGHT_SCOPE_FAILED')
    elif output != 'ISOLATED_PREVIEW_' + label + '_READY_NO_APPLICATION_LAUNCHED':
        raise RuntimeError('UNEXPECTED_PREFLIGHT_OUTPUT_' + label)
    summary.append(dict(helper=label, beforeParseExit=before.returncode, afterParseExit=after.returncode,
                        preflightExit=preflight.returncode, asciiOnly=True,
                        beforeSha256=hashlib.sha256(original).hexdigest(),
                        afterSha256=hashlib.sha256(fixed).hexdigest(),
                        replacementCount=len(replacements)))

(OUT / 'RESULT.json').write_text(json.dumps(dict(helpers=summary, uiLaunched=False, installerExecuted=False,
    humanVisualAcceptance='NOT_TESTED', administratorRepair='AWAITING_USER_RESULT'), indent=2) + '\n', encoding='ascii')
print(json.dumps(summary))
