"""Read-only inspection of a frozen APK; only run in cloud verification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--build-tools', type=Path, required=True)
args = parser.parse_args()
apks = list(args.directory.glob('*.apk'))
if len(apks) != 1:
    raise SystemExit('APK_COUNT')
apk = apks[0]
def inspect(tool, parameters, receipt):
    result = subprocess.run([str(args.build_tools / tool), *parameters, str(apk)], check=True,
                            text=True, encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (args.directory / receipt).write_text(result.stdout, encoding='utf-8')
    return result.stdout
badging = inspect('aapt', ['dump', 'badging'], 'aapt-badging.txt')
signature = inspect('apksigner', ['verify', '--verbose', '--print-certs'], 'apksigner-verify.txt')
package_line = next(line for line in badging.splitlines() if line.startswith('package:'))
package = dict(re.findall(r"(\w+)='([^']*)'", package_line))
assert package['name'] == 'com.example.chatbar.lansync', 'APK_PACKAGE'
assert package['versionName'].endswith('-lan-preview'), 'APK_PREVIEW_VERSION'
minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M).group(1)
assert minimum == '26', 'APK_MINIMUM'
label = re.search(r"^application-label:'([^']*)'", badging, re.M).group(1)
assert label == 'CCB 局域网预览', 'APK_LABEL'
certificate = re.search(r'^Signer #1 certificate SHA-256 digest: (\S+)', signature, re.M).group(1)
receipt = {'file': apk.name, 'bytes': apk.stat().st_size,
           'sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
           'package': package, 'minSdk': int(minimum), 'label': label,
           'signerCertificateSha256': certificate,
           'originalSource': json.loads((args.directory / 'source.json').read_text())}
(args.directory / 'apk-identity.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({key: value for key, value in receipt.items() if key != 'originalSource'}, ensure_ascii=False))
