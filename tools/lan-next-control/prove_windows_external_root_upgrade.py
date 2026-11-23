"""Hosted Windows proof for an existing CUSTOM data root across an exact Program swap."""

import argparse
import copy
import hashlib
import importlib.util
import inspect
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap


LEGACY_SHA = "a3b1db7cea226b14c872ba222607ccae5925f449de2e18521a521af61e5753ce"
LOCK_EXPR = "self.root/'UserData/Local/ChatChatBarDesktop/.ccb-desktop.lock'"
BOOTSTRAP = "ChatChatBarDesktop.bootstrap.json"
LAUNCHER = "启动独立资料版.cmd"
RESULT = "external-root-upgrade-result.json"
MAIN_OWNER = """
$ErrorActionPreference = 'Stop'
$p = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [uint32]$env:CCB_PROOF_PID) -OperationTimeoutSec 8
if ($null -eq $p -or $p.ExecutablePath -ine $env:CCB_PROOF_EXE) { exit 2 }
$sid = Invoke-CimMethod -InputObject $p -MethodName GetOwnerSid -OperationTimeoutSec 8
if ($sid.ReturnValue -ne 0 -or $sid.Sid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) { exit 3 }
"""


def require(condition, code):
    if not condition:
        raise ValueError(code)


def load_controls(root):
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(root / "tools/lan-next-control"))
    import desktop_lan8_control as lan8

    legacy_path = root / "tools/lan-preview-signing/prove_desktop_profile_upgrade.py"
    require(lan8.control.file_hash(legacy_path) == LEGACY_SHA, "NATIVE_HELPER_CHANGED")
    spec = importlib.util.spec_from_file_location("ccb_external_root_native", legacy_path)
    legacy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(legacy)
    return lan8, legacy


def bind_native_launch(legacy):
    # Change the expected lock only. Reuse the frozen exact launcher, HWND, lock and close logic.
    original = textwrap.dedent(inspect.getsource(legacy.Native.launch))
    require(original.count(LOCK_EXPR) == 1, "NATIVE_LOCK_ANCHOR_CHANGED")
    bound = original.replace(LOCK_EXPR, "self.custom_root / '.ccb-desktop.lock'")
    scope = dict(vars(legacy))
    exec(compile(bound, str(legacy.__file__), "exec"), scope)
    return scope["launch"]


def verify_process_identity(pid, executable):
    env = {name: value for name, value in os.environ.items() if name.upper() not in {"GH_TOKEN", "GITHUB_TOKEN"}}
    env.update(CCB_PROOF_PID=str(pid), CCB_PROOF_EXE=str(executable))
    powershell = Path(env["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    checked = subprocess.run(
        [str(powershell), "-NoProfile", "-NonInteractive", "-Command", MAIN_OWNER],
        env=env, creationflags=subprocess.CREATE_NO_WINDOW, timeout=24,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
    )
    require(checked.returncode == 0, "EXACT_MAIN_IMAGE_OR_RUNNER_ACCOUNT_CHANGED")


def native_for(legacy, root, custom_root, require_bypass_unchanged):
    class ExternalRootNative(legacy.Native):
        launch = bind_native_launch(legacy)

        def windows(self):
            windows = super().windows()
            if len(windows) == 1:
                pid = legacy.W.DWORD()
                self.user.GetWindowThreadProcessId(windows[0], legacy.C.byref(pid))
                require(pid.value in self.owned(), "MAIN_WINDOW_PROCESS_CHANGED")
                if self.observed_pid is None:
                    verify_process_identity(pid.value, root / "Program/ChatChatBarDesktop.exe")
                    self.observed_pid = pid.value
                require(pid.value == self.observed_pid, "MAIN_WINDOW_PROCESS_CHANGED")
                require_bypass_unchanged()
            return windows

    native = ExternalRootNative(root)
    native.custom_root = custom_root
    return native


def snapshot(directory, legacy, file_hash):
    legacy.regular(directory)
    tree = {}
    for path in directory.rglob("*"):
        legacy.regular(path)
        name = path.relative_to(directory).as_posix()
        if path.is_dir():
            tree[name + "/"] = None
        else:
            require(path.is_file(), "ORDINARY_TREE_ENTRY_REQUIRED")
            tree[name] = file_hash(path)
    return tree


def bootstrap_values(custom_root):
    require(custom_root.is_absolute(), "CUSTOM_ROOT_MUST_BE_ABSOLUTE")
    return {"formatVersion": 1, "mode": "CUSTOM", "customRoot": str(custom_root)}


def run(lan8, legacy, request, root, output):
    control = lan8.control
    control.cloud()
    require(os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted", "HOSTED_WINDOWS_REQUIRED")
    require(root == Path.cwd().resolve() == Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True),
            "EXACT_CONTROL_WORKSPACE_REQUIRED")
    control_sha = control.git_bytes(root, "rev-parse", "HEAD").decode().strip()
    require(control_sha == os.environ["GITHUB_SHA"] and
            not control.git_bytes(root, "status", "--porcelain", "--untracked-files=all"),
            "EXACT_CLEAN_CONTROL_HEAD_REQUIRED")
    runner = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    require(not runner.is_relative_to(root) and not root.is_relative_to(runner), "UNRELATED_RUNNER_TEMP_REQUIRED")
    require(output.parent.resolve() == root and not output.exists() and
            all(not os.path.lexists(root / ("input-" + row["series"])) for row in request["packages"]),
            "NEW_OUTPUT_AND_INPUTS_REQUIRED")
    legacy.regular(runner)
    case = Path(tempfile.mkdtemp(prefix="ccb-custom-upgrade-", dir=runner))
    legacy.regular(case)
    require(case.resolve(strict=True).parent == runner, "SYNTHETIC_CASE_SCOPE")
    old_root = case / "independent"
    new_root = case / "incoming"
    custom = case / "selected data"
    outside = case / "outside-profile"
    bootstrap = old_root / "UserData/Local" / BOOTSTRAP
    default = bootstrap.parent / "ChatChatBarDesktop"
    rows = request["packages"]
    result = {
        "kind": "EXACT_WINDOWS_CUSTOM_ROOT_PROGRAM_ONLY_UPGRADE_V1",
        "passed": False,
        "controlSha": control_sha,
        "packages": rows,
        "syntheticOnly": True,
        "selectionMethod": "CONTROL_WRITTEN_CUSTOM_BOOTSTRAP",
        "dataDirectoryUiSelectionExercised": False,
        "groupCredentialsExercised": False,
        "rollbackExercised": False,
        "phases": [],
    }
    output.mkdir()
    phase = "ORIGINAL_PACKAGES"
    try:
        control.fetch_originals(request, case / "original-evidence")
        counts = [legacy.package(row, path) for row, path in zip(rows, (old_root, new_root))]
        old_tree = snapshot(old_root / "Program", legacy, control.file_hash)
        new_tree = snapshot(new_root / "Program", legacy, control.file_hash)
        launcher = (old_root / LAUNCHER).read_bytes()
        require(not (old_root / "Program/portable.flag").exists() and
                not (new_root / "Program/portable.flag").exists(), "PORTABLE_INPUT_REFUSED")

        phase = "SYNTHETIC_CUSTOM_PROFILE"
        custom.mkdir()
        expected_bootstrap = bootstrap_values(custom)
        control.write_new(bootstrap, expected_bootstrap)
        raw_bootstrap = bootstrap.read_bytes()
        environment = {name: value for name, value in os.environ.items()
                       if not name.upper().startswith(("CCB_", "CHATBAR_")) and name.upper() not in
                       {"GH_TOKEN", "GITHUB_TOKEN", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}}
        for name, leaf in (("LOCALAPPDATA", "Local"), ("APPDATA", "Roaming"), ("TEMP", "Temp"), ("TMP", "Temp")):
            location = outside / leaf
            location.mkdir(parents=True, exist_ok=True)
            environment[name] = str(location)
        control.write_new(outside / "Local" / BOOTSTRAP, {"formatVersion": 1, "mode": "DEFAULT"})
        outside_before = snapshot(outside, legacy, control.file_hash)
        image = legacy.seed(custom)
        seeded = snapshot(custom / "entities", legacy, control.file_hash)
        image_sha = control.file_hash(image)

        def require_bypass_unchanged():
            require(not os.path.lexists(default), "DEFAULT_DATA_ROOT_WRITTEN")
            require(bootstrap.read_bytes() == raw_bootstrap and control.load(bootstrap) == expected_bootstrap,
                    "CUSTOM_BOOTSTRAP_CHANGED")
            require(snapshot(outside, legacy, control.file_hash) == outside_before, "OUTSIDE_PROFILE_WRITTEN")

        native = native_for(legacy, old_root, custom, require_bypass_unchanged)
        for stage in ("lan7-custom-existing", "lan8-custom-cold-reopen"):
            phase = stage
            native.observed_pid = None
            observation = native.launch(stage, environment)
            require(native.observed_pid is not None, "EXACT_MAIN_IDENTITY_NOT_OBSERVED")
            require_bypass_unchanged()
            require((old_root / LAUNCHER).read_bytes() == launcher, "ORIGINAL_ROOT_LAUNCHER_CHANGED")
            legacy.verify_seed(custom, image)
            require(control.file_hash(image) == image_sha, "SYNTHETIC_PNG_CHANGED")
            actual = snapshot(custom / "entities", legacy, control.file_hash)
            result["phases"].append(dict(observation, exactMainImage=True, sameRunnerAccount=True))
            if stage == "lan7-custom-existing":
                require(all(name in actual for name in seeded), "OLD_MAIN_DID_NOT_RETAIN_SEED")
                retained = {name: actual[name] for name in seeded if not name.endswith("/")}
                require(snapshot(old_root / "Program", legacy, control.file_hash) == old_tree,
                        "OLD_PROGRAM_CHANGED_BEFORE_SWAP")
                phase = "PROGRAM_ONLY_SWAP"
                saved_program = case / "preserved-old-program"
                (old_root / "Program").rename(saved_program)
                (new_root / "Program").rename(old_root / "Program")
                require(snapshot(old_root / "Program", legacy, control.file_hash) == new_tree,
                        "CANDIDATE_PROGRAM_TREE_CHANGED_ON_SWAP")
            else:
                require(all(actual.get(name) == digest for name, digest in retained.items()),
                        "PREEXISTING_SYNTHETIC_ENTITIES_CHANGED")
                require(snapshot(old_root / "Program", legacy, control.file_hash) == new_tree and
                        snapshot(saved_program, legacy, control.file_hash) == old_tree,
                        "ORIGINAL_PROGRAM_TREE_RETENTION_CHANGED")

        result.update(
            passed=True,
            programFilesVerified=counts,
            originalProgramTreesVerified=True,
            rootLauncherSha256=hashlib.sha256(launcher).hexdigest(),
            customBootstrapSha256=hashlib.sha256(raw_bootstrap).hexdigest(),
            bootstrapCustomRootAbsoluteAndRetained=True,
            absoluteImageReferenceAndBytesRetained=True,
            syntheticImageSha256=image_sha,
            seedEntityFilesRetained=len(retained),
            defaultRootAbsent=True,
            outsideProfileUnchanged=True,
        )
    except Exception as error:
        code = str(error)
        result["failure"] = {"stage": phase, "code": code if re.fullmatch("[A-Z][A-Z0-9_]{0,95}", code) else "CONTROL_FAILED"}
    control.write_new(output / RESULT, result)
    require(result["passed"], "EXTERNAL_ROOT_UPGRADE_FAILED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--self-check", action="store_true")
    mode.add_argument("--run", action="store_true")
    parser.add_argument("--control-root", type=Path, required=True)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = args.control_root.resolve(strict=True)
    lan8, legacy = load_controls(root)
    control = lan8.control
    with lan8.scoped(control, lan8.PROFILE):
        request = control.validate_upgrade(control.load(args.request))
        build = control.validate_build(control.load(args.build))
        require(build["sourceSha"] == request["packages"][1]["sourceSha"], "CANDIDATE_BUILD_SOURCE_MISMATCH")
        if args.self_check:
            require(args.output is None, "SELF_CHECK_WRITES_NO_FILES")
            require(bind_native_launch(legacy).__name__ == "launch", "NATIVE_HELPER_NOT_BOUND")
            fixture_path = args.request.resolve().parent
            require(bootstrap_values(fixture_path)["customRoot"] == str(fixture_path), "CUSTOM_ROOT_CHANGED")
            bad = copy.deepcopy(request)
            bad["packages"][0]["innerSha256"] = "0" * 64
            try:
                control.validate_upgrade(bad)
            except ValueError:
                print("PASS request, mismatched base refusal, native helper and absolute CUSTOM authority")
            else:
                raise ValueError("ALTERED_BASE_ACCEPTED")
        else:
            require(args.output is not None, "OUTPUT_REQUIRED")
            run(lan8, legacy, request, root, args.output)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        message = str(error)
        print(message if re.fullmatch("[A-Z][A-Z0-9_]{0,95}", message) else "EXTERNAL_ROOT_CONTROL_FAILED", file=sys.stderr)
        sys.exit(1)
