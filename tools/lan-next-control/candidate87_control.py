"""Exact Android 86 baseline and frozen-source candidate 87 orchestration."""

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys
from types import FunctionType

import candidate85_group_control as prior_cli
from candidate86_v6_control import V6Engine
from candidate86_v6_profile import BUILD_REQUEST_PATH as PREVIOUS_BUILD_REQUEST
from candidate86_v6_profile import PROFILE as PREVIOUS_PROFILE
from candidate87_profile import BASE_CONTROL, BASE_SOURCE, PROFILE
from upgrade_series_engine import LegacyTools, SeriesEngine, require


HERE = Path(__file__).resolve().parent
FROZEN_MODULES = (
    "build_control.py", "candidate84_build.py", "candidate84_profile.py", "candidate84_sign.py",
    "candidate84_upgrade.py", "candidate85_group_control.py", "candidate85_group_profile.py",
    "candidate85_profile.py", "candidate86_v6_control.py", "candidate86_v6_profile.py", "contract.py",
    "desktop_control.py", "fixture_only_control.py", "group_ui_control.py", "instrumentation_report.py",
    "prove_upgrade.py", "provenance.py", "sign_control.py", "upgrade_series_engine.py",
)
GRADLE_PATH = "app/app/build.gradle.kts"


def git_output(repo, *args):
    return subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, check=True, timeout=30,
    ).stdout


def frozen_blob(repo, path):
    return git_output(repo, "show", f"{BASE_CONTROL}:{path}")


class Candidate87Engine(SeriesEngine):
    def build_request(self, source):
        request = super().build_request(source)
        files = {}
        for name in ("candidate87_control.py", "candidate87_profile.py", "upgrade_series_engine.py"):
            path = self.here / name
            require(path.is_file() and not path.is_symlink(), "CONTROL_REGULAR_FILE")
            body = self.c.fixture_bytes(path)
            files[name] = {"bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}
        request["trustedCandidate87Control"] = {
            "format": 1, "files": files, "expectedSuccessfulInvocations": 3,
            "uniqueMethods": 2, "seedExecutions": 1, "verifierExecutions": 2,
        }
        return request

    def validate_base(self, directory):
        repo = self.t.root
        manifest = directory / "raw-manifest.json"
        require(self.manifest_descriptor(manifest) == dict(self.p.base_manifest), "EXACT_BASE86_MANIFEST")
        request = directory / "source-request.json"
        require(request.is_file() and not request.is_symlink() and
                request.read_bytes() == frozen_blob(repo, PREVIOUS_BUILD_REQUEST), "EXACT_BASE86_BUILD_REQUEST")
        tools_dir = "tools/lan-next-control/"
        old_paths = [tools_dir + name for name in FROZEN_MODULES]
        old_paths.extend(tools_dir + name for _, name in PREVIOUS_PROFILE.fixture_files)
        old_paths.append("tools/lan-preview-signing/assemble_public_apk.py")
        for name in old_paths:
            path = self.here.parents[1] / name
            require(path.is_file() and not path.is_symlink() and
                    self.c.fixture_bytes(path) == frozen_blob(repo, name), "FROZEN_V6_FILE_" + Path(name).name)
        old = self.load(request)
        V6Engine(PREVIOUS_PROFILE, self.t, self.here).validate_raw(directory, old, BASE_CONTROL)
        path = directory / self.p.base["member"]
        require(path.is_file() and not path.is_symlink() and
                path.stat().st_size == self.p.base["rawBytes"] and
                self.c.file_hash(path) == self.p.base["rawSha256"], "EXACT_BASE86_RAW_BYTES")
        return path

    def ui(self, directory):
        control = self.native_control()
        directory = directory.resolve(strict=True)
        proof = self.load(directory / "upgrade-proof.json")
        verification = self.verified_inputs(directory, control)
        require(proof["kind"] == self.p.kinds["upgrade"] and proof["passed"] is True and
                proof["verificationControlSha"] == proof["upgradeControlSha"] == control and
                proof["buildRequest"] == verification["buildRequest"], "COLD_REQUIRES_VERIFIED_UPGRADE")
        device = self.device(directory)
        device.select("cold-reopen")
        require(device.state("cold-reopen-package") == proof["installationAfter"], "COLD_INSTALL_CHANGED")
        device.command(["shell", "am", "force-stop", self.p.package], "cold-reopen-force-stop")
        stdout, stderr, status = device.command(["shell", "pidof", self.p.package], "cold-reopen-no-pid", (0, 1))
        require(status == 1 and not stdout.strip() and not stderr.strip(), "COLD_APP_PROCESS_CHECK_FAILED")
        super().ui(directory)


def prepare_build_request(args):
    parser = argparse.ArgumentParser(prog="candidate87_control.py request-build")
    parser.add_argument("--frozen-source-sha", required=True)
    parser.add_argument("--source-repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args(args)
    engine = Candidate87Engine(PROFILE, LegacyTools(HERE.parents[1]), HERE)
    source = engine.c.sha(options.frozen_source_sha, 40)
    exact = git_output(options.source_repo, "rev-parse", "--verify", source + "^{commit}").decode("ascii").strip()
    require(exact == source, "FROZEN_SOURCE_COMMIT_REQUIRED")
    config = git_output(options.source_repo, "show", f"{source}:{GRADLE_PATH}").decode("utf-8")
    versions = re.search(r'lanSyncPreviewSeries == "next" && raw in setOf\(([^)]+)\)', config)
    require(versions is not None and '"87"' in versions.group(1).split(", ") and
            'applicationId = "com.example.chatbar"' in config and
            'else ".lansyncnext"' in config and
            'output.versionName.set("1.4.1-lan-preview.2.$code")' in config, "ANDROID87_PACKAGE_CONFIG")
    require(
        not git_output(options.source_repo, "ls-tree", "-r", "--name-only", source, "--", PROFILE.fixture_target)
        and not git_output(options.source_repo, "ls-tree", "-r", "--name-only", BASE_SOURCE, "--", PROFILE.fixture_target),
        "TEST_OVERLAY_ALREADY_EXISTS",
    )
    engine.c.write_new(options.output, engine.build_request(source))


def main():
    if sys.argv[1:2] == ["request-build"]:
        prepare_build_request(sys.argv[2:])
        return
    # Reuse the existing dispatcher with only the new profile and engine bound.
    bound = dict(prior_cli.main.__globals__, GroupEngine=Candidate87Engine, PROFILE=PROFILE, __file__=__file__)
    FunctionType(prior_cli.main.__code__, bound, "candidate87_main")()


if __name__ == "__main__":
    main()
