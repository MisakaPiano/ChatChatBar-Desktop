"""Read-only jpackage image audit. This is not installer or secret-content acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import zipfile

PRIVATE_SUFFIXES = ('.cbbackup', '.cbback', '.cbsave', '.ccbsecret', '.protected')
PRIVATE_PARTS = {'userdata', 'app-data', 'desktop_preservation', 'desktop_historical_voice',
                 'portable.flag', '.ccb-desktop.lock', 'desktop-settings.json'}
WITNESS = re.compile(r'^com/example/chatbar/desktop/(?:Real[A-Za-z0-9_]*Local|DesktopWindowsPackageFixture)(?:\$|\.class)')
MAX_FILE = 256 * 1024**2
MAX_TOTAL = 2 * 1024**3
MAX_FILES = 5000
MAX_ENTRIES = 200000


class Refused(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Refused(code)


def regular(path, directory=False):
    for ancestor in [path, *path.parents]:
        value = ancestor.stat(follow_symlinks=False)
        require(not stat.S_ISLNK(value.st_mode) and not getattr(value, 'st_file_attributes', 0) & 0x400,
                'IMAGE_REPARSE_REFUSED')
    value = path.stat(follow_symlinks=False)
    require(stat.S_ISDIR(value.st_mode) if directory else stat.S_ISREG(value.st_mode), 'IMAGE_TYPE_REFUSED')
    return value


def stamp(value):
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def private_name(name):
    parts = PurePosixPath(name).parts
    return (any(part.casefold() in PRIVATE_PARTS for part in parts) or
            any(part.casefold().endswith(PRIVATE_SUFFIXES) for part in parts))


def properties(raw):
    require(len(raw) <= 4096, 'IMAGE_IDENTITY_LIMIT')
    result = {}
    for line in raw.decode('utf-8', errors='strict').splitlines():
        require('=' in line, 'IMAGE_IDENTITY_INVALID')
        key, value = line.split('=', 1)
        require(key not in result, 'IMAGE_IDENTITY_DUPLICATE_FIELD')
        result[key] = value
    return result


def audit(image, expected):
    image = Path(os.path.abspath(image))
    regular(image, directory=True)
    require(re.fullmatch(r'[0-9a-f]{40}', expected['commit']), 'EXPECTED_COMMIT_INVALID')
    require(expected['packagingProfile'] in {'desktop', 'synthetic'}, 'EXPECTED_PROFILE_INVALID')
    product = 'ChatChatBarDesktop' + ('Synthetic' if expected['packagingProfile'] == 'synthetic' else '')
    require({path.name for path in image.iterdir()} == {'app', 'runtime', product + '.exe'}, 'IMAGE_LAYOUT_REFUSED')
    files = []
    for parent, directories, names in os.walk(image, followlinks=False):
        regular(Path(parent), directory=True)
        for name in directories:
            path = Path(parent) / name
            require(not private_name(path.relative_to(image).as_posix()), 'IMAGE_PRIVATE_ENTRY')
            regular(path, directory=True)
        for name in names:
            path = Path(parent) / name
            require(not private_name(path.relative_to(image).as_posix()), 'IMAGE_PRIVATE_ENTRY')
            regular(path)
            files.append(path)
            require(len(files) <= MAX_FILES, 'IMAGE_FILE_LIMIT')
    require((image / 'runtime/bin/server/jvm.dll') in files, 'IMAGE_RUNTIME_MISSING')
    identities, inventory, jars, classes, entries, expanded, total = [], [], 0, 0, 0, 0, 0
    cfg = None
    for path in sorted(files):
        relative = path.relative_to(image).as_posix()
        before = regular(path)
        require(before.st_size <= MAX_FILE, 'IMAGE_FILE_LIMIT')
        digest = hashlib.sha256()
        with path.open('rb') as source:
            require(stamp(os.fstat(source.fileno())) == stamp(before), 'IMAGE_CHANGED')
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(chunk)
            if relative == 'app/' + product + '.cfg':
                require(before.st_size <= 65536, 'IMAGE_LAUNCHER_LIMIT')
                source.seek(0)
                cfg = source.read().decode('utf-8', errors='strict')
            if path.parent == image / 'app' and path.suffix.lower() == '.jar':
                jars += 1
                source.seek(0)
                with zipfile.ZipFile(source) as archive:
                    seen = set()
                    for entry in archive.infolist():
                        entries += 1
                        require(entries <= MAX_ENTRIES, 'IMAGE_JAR_ENTRY_LIMIT')
                        name = entry.filename
                        require(name not in seen, 'IMAGE_DUPLICATE_JAR_ENTRY')
                        seen.add(name)
                        require('\\' not in name and ':' not in name and not name.startswith('/') and
                                all(part not in {'', '..', '.'} for part in name.rstrip('/').split('/')), 'IMAGE_JAR_PATH_REFUSED')
                        require(not private_name(name) and not WITNESS.match(name), 'IMAGE_PRIVATE_ENTRY')
                        require(not entry.flag_bits & 1, 'IMAGE_ENCRYPTED_JAR_ENTRY')
                        require(entry.file_size <= MAX_FILE, 'IMAGE_JAR_SIZE_LIMIT')
                        expanded += entry.file_size
                        require(expanded <= MAX_TOTAL, 'IMAGE_JAR_SIZE_LIMIT')
                        classes += name.endswith('.class')
                        if name == 'desktop-build.properties':
                            require(entry.file_size <= 4096, 'IMAGE_IDENTITY_LIMIT')
                            identities.append(properties(archive.read(entry)))
                        else:
                            with archive.open(entry) as data:
                                while data.read(65536):
                                    pass  # EOF validates ZIP CRC without extraction.
            require(stamp(os.fstat(source.fileno())) == stamp(before), 'IMAGE_CHANGED')
        require(stamp(regular(path)) == stamp(before), 'IMAGE_CHANGED')
        inventory.append({'path': relative, 'bytes': before.st_size, 'sha256': digest.hexdigest()})
        total += before.st_size
        require(total <= MAX_TOTAL, 'IMAGE_TOTAL_LIMIT')
    require(len(identities) == 1, 'IMAGE_IDENTITY_COUNT')
    require(identities[0] == expected, 'IMAGE_IDENTITY_MISMATCH')
    require(cfg is not None and 'app.mainclass=com.example.chatbar.desktop.MainKt' in cfg.splitlines(), 'IMAGE_LAUNCHER_REFUSED')
    classpath = [line.split('=', 1)[1] for line in cfg.splitlines() if line.startswith('app.classpath=')]
    wanted = {'$APPDIR\\' + path.name for path in files if path.parent == image / 'app' and path.suffix.lower() == '.jar'}
    require(len(classpath) == len(wanted) and set(classpath) == wanted, 'IMAGE_CLASSPATH_REFUSED')
    final_files = {Path(parent).joinpath(name).relative_to(image).as_posix()
                   for parent, _, names in os.walk(image, followlinks=False) for name in names}
    require(final_files == {row['path'] for row in inventory}, 'IMAGE_CHANGED')
    return {'kind': 'DESKTOP_APP_IMAGE_READONLY_AUDIT', 'identity': identities[0], 'fileCount': len(files),
            'jarCount': jars, 'classEntryCount': classes, 'jarEntryCount': entries, 'files': inventory,
            'installerAcceptance': 'NOT_TESTED', 'arbitrarySecretContentAbsence': 'NOT_PROVEN',
            'limitations': ['No application launch, installer, native account proof or recursive nested-JAR inspection.',
                            'Known private names and companion classes are rejected; this is not a universal secret scanner.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--installer-version', required=True)
    parser.add_argument('--profile', choices=['desktop', 'synthetic'], required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    try:
        output = Path(os.path.abspath(args.report))
        require(not output.is_relative_to(Path(os.path.abspath(args.image))), 'REPORT_INSIDE_IMAGE_REFUSED')
        require(not output.exists(), 'REPORT_EXISTS')
        regular(output.parent, directory=True)
        result = audit(args.image, {'version': args.version, 'commit': args.commit,
            'installerVersion': args.installer_version, 'installerVersionAssigned': 'true', 'packagingProfile': args.profile})
        with output.open('x', encoding='utf-8') as target:
            json.dump(result, target, indent=2)
            target.write('\n')
        print('APP_IMAGE_AUDIT_PASS files=%s jars=%s classes=%s' % (result['fileCount'], result['jarCount'], result['classEntryCount']))
        return 0
    except Refused as failure:
        print(str(failure), file=sys.stderr)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, EOFError):
        print('APP_IMAGE_READ_OR_FORMAT_REFUSED', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main())
