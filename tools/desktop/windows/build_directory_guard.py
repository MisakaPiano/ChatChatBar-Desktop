"""Compile only the small immediate MSI directory guard; never builds or executes an MSI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

if not __debug__:
    raise RuntimeError("DIRECTORY_GUARD_VALIDATION_REQUIRED")


def reuse_production(directory, expected_hash):
    source = Path(__file__).with_name("directory_guard.c").resolve()
    directory = Path(directory).resolve()
    root = source.parents[3] / "app/desktopApp/build"
    assert directory.is_relative_to(root), "OWNED_GUARD_BUILD_REQUIRED"
    for path in [directory, *directory.parents]:
        assert not path.is_symlink() and not (path.stat().st_file_attributes & 0x400), "GUARD_REPARSE_REFUSED"
    identity = json.loads((directory / "IDENTITY.json").read_text())
    dll = directory / "directory_guard.dll"
    assert not dll.is_symlink() and not dll.stat().st_file_attributes & 0x400, "GUARD_REPARSE_REFUSED"
    assert identity["testExports"] is False, "TEST_GUARD_CANNOT_BE_PACKAGED"
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    assert identity["sourceSha256"] == source_hash and (directory / source.name).read_bytes() == source.read_bytes(), "GUARD_SOURCE_CHANGED"
    assert dll.stat().st_size == identity["bytes"], "GUARD_LENGTH_CHANGED"
    assert hashlib.sha256(dll.read_bytes()).hexdigest() == identity["sha256"] == expected_hash, "GUARD_HASH_CHANGED"
    return dll


def build(output, test=False):
    source = Path(__file__).with_name("directory_guard.c").resolve()
    output = Path(output).resolve()
    root = source.parents[3] / "app/desktopApp/build"
    assert output.is_relative_to(root) and not output.exists(), "FRESH_GUARD_BUILD_REQUIRED"
    assert not any(p.is_symlink() or (p.exists() and p.stat().st_file_attributes & 0x400)
                   for p in [output, *output.parents])
    vswhere = Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = subprocess.check_output([str(vswhere), "-latest", "-products", "*", "-requires",
                                            "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"], text=True).strip()
    setup = Path(installation) / "VC/Auxiliary/Build/vcvars64.bat"
    assert installation and setup.is_file(), "NATIVE_COMPILER_REQUIRED"
    output.mkdir(parents=True)
    (output / source.name).write_bytes(source.read_bytes())
    arguments = ["cl.exe", "/nologo", "/W4", "/WX", "/O2", "/MT", "/LD", "/DWINVER=0x0601", "/D_WIN32_WINNT=0x0601"]
    if test:
        arguments.append("/DCCB_GUARD_TEST=1")
    arguments += [str(output / source.name), "/link", "/OUT:" + str(output / "directory_guard.dll"), "msi.lib", "advapi32.lib"]
    # cmd is needed only to enter the installed compiler environment. Reject shell metacharacters.
    for value in [str(setup), *arguments]:
        assert not any(c in value for c in '&|<>^%!\r\n'), "COMPILER_ARGUMENT_REFUSED"
    command = 'call "' + str(setup) + '" >nul && ' + subprocess.list2cmdline(arguments)
    invocation = 'cmd.exe /d /s /c "' + command + '"'
    (output / "compile-command.json").write_text(json.dumps(invocation) + "\n")
    with (output / "compile.log").open("xb") as log:
        result = subprocess.run(invocation, cwd=output, stdout=log, stderr=subprocess.STDOUT, timeout=120)
    (output / "compile.exit").write_text(str(result.returncode))
    assert result.returncode == 0, "DIRECTORY_GUARD_COMPILE_FAILED"
    dll = output / "directory_guard.dll"
    digest = hashlib.sha256(dll.read_bytes()).hexdigest()
    (output / "IDENTITY.json").write_text(json.dumps({"sha256": digest, "bytes": dll.stat().st_size,
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(), "testExports": test,
        "msiBuiltOrExecuted": False}, indent=2) + "\n")
    return dll


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    print(build(args.output, args.test))
