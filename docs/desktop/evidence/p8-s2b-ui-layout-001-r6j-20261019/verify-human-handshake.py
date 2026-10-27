from pathlib import Path
import argparse
import json
import math
import re
import uuid

G = Path(__file__).resolve().parent

def fields(text):
    result = {}
    for line in text.splitlines():
        key, sep, value = line.partition('=')
        if not sep or not key or key in result:
            raise ValueError('Invalid or duplicate handshake field')
        result[key] = value
    return result

def armed_valid(run_id, record, log, terminal=False):
    if terminal or record is None or record.get('runId') != run_id or record.get('status') != 'ARMED':
        return False
    try:
        value = float(record['initialRange'])
        x, y, width, height = map(int, record['thumb'].split(','))
        if not math.isfinite(value) or value != 0 or width <= 0 or height <= 0:
            return False
        if any(record.get(k) != 'true' for k in ['listenerRegistered', 'ownerDisabled', 'windowShowing']):
            return False
        if [record.get(k) for k in ['queries', 'http', 'bindings']] != ['1', '0', '0']:
            return False
        if not record.get('first'):
            return False
        if not all(re.fullmatch(r'OWNED:0x[1-9a-f][0-9a-f]*', record.get(k, '')) for k in ['ownedHwnd', 'logicalOwnerHwnd']):
            return False
        if not re.fullmatch(r'AWT:[0-9a-f]+', record.get('recorderId', '')):
            return False
        matches = [s for s in log.splitlines() if s.startswith('FISH_R6J_ARMED ') and 'runId=' + run_id + ' ' in s]
        return len(matches) == 1 and all(token in matches[0] for token in [
            'status=ARMED', 'initialRange=0.0', 'listenerRegistered=true',
            'recorderId=' + record['recorderId'], 'ownedHwnd=' + record['ownedHwnd']])
    except (KeyError, ValueError):
        return False

def self_check():
    run_id = '00000000-0000-0000-0000-000000000001'
    other = '00000000-0000-0000-0000-000000000002'
    record = dict(runId=run_id, status='ARMED', initialRange='0.0', thumb='10,20,8,80',
                  listenerRegistered='true', ownerDisabled='true', windowShowing='true',
                  queries='1', http='0', bindings='0', first='SYNTHETIC_FIRST',
                  ownedHwnd='OWNED:0x1', logicalOwnerHwnd='OWNED:0x2', recorderId='AWT:abc')
    log = f"FISH_R6J_ARMED runId={run_id} status=ARMED initialRange=0.0 recorderId=AWT:abc listenerRegistered=true ownedHwnd=OWNED:0x1"
    cases = {
        'no-file': not armed_valid(run_id, None, log),
        'no-log': not armed_valid(run_id, record, ''),
        'stale-file-id': not armed_valid(run_id, dict(record, runId=other), log),
        'stale-log-id': not armed_valid(run_id, record, log.replace(run_id, other)),
        'nonzero-baseline': not armed_valid(run_id, dict(record, initialRange='1545.0'), log),
        'listener-invalid': not armed_valid(run_id, dict(record, listenerRegistered='false'), log),
        'thumb-invalid': not armed_valid(run_id, dict(record, thumb='10,20,0,80'), log),
        'owner-invalid': not armed_valid(run_id, dict(record, ownedHwnd='OTHER_PROCESS'), log),
        'already-complete': not armed_valid(run_id, record, log, terminal=True),
        'window-visible-only': not armed_valid(run_id, dict(record, status='WINDOW_VISIBLE_NOT_ARMED'), log),
        'file-and-matching-log': armed_valid(run_id, record, log),
    }
    assert all(cases.values()), cases
    report = dict(mode='SYNTHETIC_PURE_CHECKS_NOT_RUNTIME_ARMED', cases=cases,
                  actualManualStart=False, physicalInput=False, gui=False)
    (G / 'operator-handshake-checks.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))

def actual_check(run_id, kind):
    assert str(uuid.UUID(run_id)) == run_id
    control = G / 'manual-control' / run_id
    log_path = G / '02-human-physical' / 'gradle.stdout.log'
    raw = log_path.read_bytes() if log_path.exists() else b''
    try:
        log = raw.decode('utf-8', errors='strict')
    except UnicodeDecodeError:
        log = raw.decode('cp936', errors='strict')
    armed_path = control / 'armed.txt'
    record = fields(armed_path.read_text(encoding='utf-8')) if armed_path.exists() else None
    same_run_lines = [s for s in log.splitlines() if 'runId=' + run_id in s]
    terminal = any((control / p).exists() for p in ['capture-complete.txt', 'recorder-error.txt', 'result.txt']) or any(
        any(tag in s for tag in ['ERROR', 'PREARM_REJECT', 'CAPTURE_COMPLETE', 'DISPOSING', 'RESULT']) for s in same_run_lines)
    allowed = (control / 'started.lock').exists() and armed_valid(run_id, record, log, terminal=terminal)
    if kind == 'capture':
        path = control / 'capture-complete.txt'
        cap = fields(path.read_text(encoding='utf-8')) if path.exists() else {}
        allowed = (control / 'started.lock').exists() and armed_valid(run_id, record, log) and cap.get('runId') == run_id and cap.get('status') == 'CAPTURE_COMPLETE'
        allowed = allowed and any(s.startswith('FISH_R6J_CAPTURE_COMPLETE ') for s in same_run_lines) and not (control / 'result.txt').exists()
        output = dict(runId=run_id, mayAskUserCompletion=allowed, classification=cap.get('classification'),
                      instruction='Collect explicit user completion/signoff before finish.request; do not re-drag')
    else:
        output = dict(runId=run_id, dragInstructionAllowed=allowed, armedFile=record is not None,
                      terminal=terminal, instruction='No drag instruction unless this exact fresh run passes all checks')
    print(json.dumps(output))
    return 0 if allowed else 2

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-check', action='store_true')
    parser.add_argument('--run-id')
    parser.add_argument('--kind', choices=['armed', 'capture'], default='armed')
    args = parser.parse_args()
    if args.self_check:
        self_check()
    else:
        assert args.run_id
        raise SystemExit(actual_check(args.run_id, args.kind))
