"""Offline, hash-bound LAN6 workflow derivation. Never writes workflows or request files."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path

PLAN = Path(__file__).with_name('desktop-lan6-workflow-edit-plan.json')


def lf_text(raw):
    text = raw.decode('utf8').replace('\r\n', '\n')
    if '\r' in text:
        raise ValueError('WORKFLOW_BARE_CR')
    return text


def render_one(text, spec):
    if hashlib.sha256(text.encode('utf8')).hexdigest() != spec['templateLfSha256']:
        raise ValueError('WORKFLOW_TEMPLATE_CHANGED')
    for old, new, count in spec['replacements']:
        if text.count(old) != count:
            raise ValueError('WORKFLOW_REPLACEMENT_COUNT:' + old)
        text = text.replace(old, new)
    if spec['output'] == spec['template']:
        raise ValueError('OLD_WORKFLOW_OVERWRITE')
    return text


def render_workflows(control_root):
    plan = json.loads(PLAN.read_text(encoding='utf8'))
    return {spec['output']: render_one(lf_text((Path(control_root) / spec['template']).read_bytes()), spec)
            for spec in plan['workflows']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control-root', type=Path, required=True)
    parser.add_argument('--diff', action='store_true')
    args = parser.parse_args()
    plan = json.loads(PLAN.read_text(encoding='utf8'))
    rendered = render_workflows(args.control_root)
    if args.diff:
        for spec in plan['workflows']:
            original = lf_text((args.control_root / spec['template']).read_bytes())
            print(''.join(difflib.unified_diff(original.splitlines(True),
                rendered[spec['output']].splitlines(True),
                fromfile=spec['template'], tofile=spec['output'])), end='')
    else:
        print(json.dumps({'kind': plan['kind'], 'sourceFrozen': False, 'requestCreated': False,
            'workflowsWritten': False, 'rendered': {
                path: {'lfBytes': len(text.encode('utf8')),
                       'lfSha256': hashlib.sha256(text.encode('utf8')).hexdigest()}
                for path, text in rendered.items()}}, indent=2))


if __name__ == '__main__':
    main()
