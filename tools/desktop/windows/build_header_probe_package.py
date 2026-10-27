"""Offline authoring for one fixed synthetic early-stop probe; not an installer runner.

The production author/validator is deliberately NOT relaxed to accept this package.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import uuid
from build_directory_guard import pe_exports
import msi_safety as m

SOURCE_SHA = '5b2f58f9f722ac3f962d28a74d71ae7a591755c0c669c81a25cf57a3c1237e79'
SOURCE_PRODUCT = '{57CF060B-D0A1-3993-834A-15B88CB00D5D}'
SOURCE_PACKAGE = '{432781D2-6756-4AB8-A829-88E6AD89D32D}'
SEQUENCES = ('InstallExecuteSequence', 'InstallUISequence', 'AdminExecuteSequence', 'AdminUISequence', 'AdvtExecuteSequence')
ACTIONS = [
    ['CcbHeaderDiagnosticOnly', '1', 'CcbHeaderProbe', 'CcbHeaderDiagnosticOnly', ''],
    ['CcbStopAfterHeaderProbe', '19', '', 'Header probe insurance stop; unexpected entry return.', ''],
    ['CcbProbeUnsupportedMode', '19', '', 'Only the fixed quiet Header probe is supported.', ''],
    ['CcbDisallowDowngrade', '19', '', 'A newer CCB Desktop version is installed. Use a newer installer.', ''],
]
EXECUTE = [['FindRelatedProducts', '', '25'], ['CcbDisallowDowngrade', 'JP_DOWNGRADABLE_FOUND', '26'],
           ['AppSearch', '', '50'], ['CcbHeaderDiagnosticOnly', '', '60'], ['CcbStopAfterHeaderProbe', '', '61']]


def validate_actions(tables):
    assert sorted(tables['CustomAction']) == sorted(ACTIONS), 'PROBE_CA_EXACT_ALLOWLIST_REQUIRED'
    for name in SEQUENCES:
        expected = EXECUTE if name == 'InstallExecuteSequence' else [['CcbProbeUnsupportedMode', '', '1']]
        assert sorted(tables[name]) == sorted(expected), 'PROBE_SEQUENCE_EXACT_ALLOWLIST_REQUIRED:' + name
    props = dict(tables['Property'])
    assert props.get('CCB_HEADER_PROBE_ONLY') == '1', 'PROBE_MARKER_REQUIRED'
    assert props['ProductName'] == 'ChatChatBarDesktopSynthetic' and props['ProductVersion'] == '0.1.1112'
    assert props['UpgradeCode'] == '{58E95FD1-709D-5813-9B5B-F518A4612BAE}'
    assert props['ProductCode'] != SOURCE_PRODUCT
    assert not any(k in props for k in ('ALLUSERS', 'TRANSFORMS', 'PATCH', 'MSINEWINSTANCE'))
    m.operation_resource_graph(tables, props)


def validate(db, dll_identity):
    tables = m.operation_tables(db)
    validate_actions(tables)
    assert db.package_code() != SOURCE_PACKAGE
    sealed = {name: [str(m.OP_COLUMNS[name]), str(len(rows)), m.operation_table_digest(rows)] for name, rows in tables.items()}
    assert {r[0]: r[1:] for r in db.rows('CcbOperationTable', 4)} == sealed, 'PROBE_TABLE_SEAL_MISMATCH'
    streams = {}
    for table in ('Binary', 'Icon'):
        for name, proof in db.sql('SELECT `Name`,`Data` FROM `' + table + '`', 2, streams=True):
            streams[(table, name)] = [str(proof['bytes']), proof['sha256']]
    assert streams == {(r[0], r[1]): r[2:] for r in db.rows('CcbOperationStream', 4)}, 'PROBE_STREAM_SEAL_MISMATCH'
    assert {name for table, name in streams if table == 'Binary'} == {'CcbHeaderProbe'}, 'PROBE_BINARY_EXCLUSIVE'
    assert streams[('Binary', 'CcbHeaderProbe')] == [str(dll_identity['bytes']), dll_identity['sha256']]
    # Calling the independent production policy MUST still refuse the test package.
    try:
        m.validate_operation_actions(tables, 'synthetic')
    except AssertionError:
        pass
    else:
        raise AssertionError('PRODUCTION_POLICY_MUST_REJECT_PROBE')
    return tables


def quoted(value):
    return 'NULL' if value is None or value == '' else "'" + str(value).replace("'", "''") + "'"


def build(source, native, output):
    source, native, output = (Path(p).resolve() for p in (source, native, output))
    owned = Path(__file__).resolve().parents[3] / 'app/desktopApp/build'
    assert output.is_relative_to(owned) and not output.exists(), 'FRESH_OWNED_PROBE_PACKAGE_REQUIRED'
    for path in [source, native, output]:
        assert not any(p.is_symlink() or (p.exists() and p.stat().st_file_attributes & 0x400)
                       for p in [path, *path.parents]), 'PROBE_PATH_REPARSE_REFUSED'
    assert m.sha(source) == SOURCE_SHA, 'FROZEN_1112_SOURCE_REQUIRED'
    identity = json.loads((native / 'IDENTITY.json').read_text(encoding='utf-8'))
    dll = native / 'header_probe.dll'
    assert identity['probeOnly'] and identity['testExports'] is False
    assert m.sha(dll) == identity['sha256'] and dll.stat().st_size == identity['bytes']
    assert pe_exports(dll) == {'CcbHeaderDiagnosticOnly'}
    with m.Database(source) as db:
        before = m.operation_tables(db); cabinets = db.cabinets()
        assert dict(before['Property'])['ProductCode'] == SOURCE_PRODUCT and db.package_code() == SOURCE_PACKAGE
        m.validate_operation_actions(before, 'synthetic')
        m.operation_resource_graph(before, dict(before['Property']))
        assert len(before['File']) == 216 and len(before['Component']) == 218 and len(before['Registry']) == 220
    output.mkdir(parents=True)
    package = output / 'CCB-Header-EarlyStop-Probe-1112.msi'
    shutil.copyfile(source, package)
    product = '{' + str(uuid.uuid4()).upper() + '}'
    package_code = '{' + str(uuid.uuid4()).upper() + '}'
    with m.Database(package, True) as db:
        db.sql("UPDATE `Property` SET `Value`='" + product + "' WHERE `Property`='ProductCode'")
        db.sql("INSERT INTO `Property` (`Property`,`Value`) VALUES ('CCB_HEADER_PROBE_ONLY','1')")
        db.sql('DELETE FROM `CustomAction`')
        for row in ACTIONS:
            db.sql('INSERT INTO `CustomAction` (`Action`,`Type`,`Source`,`Target`,`ExtendedType`) VALUES (' + ','.join(quoted(v) for v in row) + ')')
        for table in SEQUENCES:
            db.sql('DELETE FROM `' + table + '`')
            rows = EXECUTE if table == 'InstallExecuteSequence' else [['CcbProbeUnsupportedMode', '', '1']]
            for row in rows:
                db.sql('INSERT INTO `' + table + '` (`Action`,`Condition`,`Sequence`) VALUES (' + ','.join(quoted(v) for v in row) + ')')
        db.sql('DELETE FROM `Binary`')
        db.add_binary('CcbHeaderProbe', dll)
        db.sql('DELETE FROM `CcbOperationTable`'); db.sql('DELETE FROM `CcbOperationStream`')
        tables = m.operation_tables(db)
        validate_actions(tables)
        for name, rows in sorted(tables.items()):
            db.sql('INSERT INTO `CcbOperationTable` (`TableName`,`Columns`,`RowCount`,`Digest`) VALUES (' +
                ','.join(quoted(v) for v in (name, m.OP_COLUMNS[name], len(rows), m.operation_table_digest(rows))) + ')')
        for table in ('Binary', 'Icon'):
            for name, proof in db.sql('SELECT `Name`,`Data` FROM `' + table + '`', 2, streams=True):
                db.sql('INSERT INTO `CcbOperationStream` (`TableName`,`StreamName`,`ByteLength`,`Digest`) VALUES (' +
                    ','.join(quoted(v) for v in (table, name, proof['bytes'], proof['sha256'])) + ')')
        db.package_code(package_code)
        assert db.lib.MsiDatabaseCommit(db.handle) == 0, 'PROBE_DATABASE_COMMIT_FAILED'
    with m.Database(package) as db:
        after = validate(db, identity)
        assert db.package_code() == package_code and db.cabinets() == cabinets, 'PROBE_CABINET_CHANGED'
    changed = {name for name in set(before) | set(after) if before.get(name) != after.get(name)}
    assert changed == {'Property', 'CustomAction', *SEQUENCES}, 'PROBE_UNEXPECTED_RESOURCE_TABLE_CHANGE'
    assert m.sha(source) == SOURCE_SHA
    report = {'profile': 'HEADER_EARLY_STOP_ONLY_NOT_INSTALL', 'fileName': package.name,
        'bytes': package.stat().st_size, 'sha256': m.sha(package), 'productCode': product, 'packageCode': package_code,
        'sourceSha256': SOURCE_SHA, 'sourceProductCode': SOURCE_PRODUCT, 'sourcePackageCode': SOURCE_PACKAGE,
        'productName': 'ChatChatBarDesktopSynthetic', 'version': '0.1.1112',
        'upgradeCode': '{58E95FD1-709D-5813-9B5B-F518A4612BAE}', 'dll': identity,
        'changedTables': sorted(changed | {'Binary', 'CcbOperationTable', 'CcbOperationStream', '_SummaryInformation'}),
        'cabinets': cabinets, 'sourceUnchanged': True, 'productionAuthorPolicyRejectsProbe': True,
        'normalResourceActionsScheduled': False, 'actualInstallerExecuted': False,
        'reproductionEquivalence': 'NOT_PROVEN_IDENTITY_BINARY_SEQUENCES_AND_SEALS_CHANGED',
        'executionAuthorization': 'NOT_GRANTED_IN_PREPARATION_TASK',
        'expectedExit': 1603, 'notReadOnly': 'Installer may cache, extract DLL, write internal processing/source state, logs/events before CA60.'}
    (output / 'PROBE_IDENTITY.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return package


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(build(args.source, args.native, args.output))
