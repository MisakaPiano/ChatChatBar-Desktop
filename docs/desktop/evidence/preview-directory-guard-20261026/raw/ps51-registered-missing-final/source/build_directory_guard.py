"""Compile only the small immediate MSI directory guard; never builds or executes an MSI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

if not __debug__:
    raise RuntimeError("DIRECTORY_GUARD_VALIDATION_REQUIRED")


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
