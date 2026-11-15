"""DRAFT ONLY: cloud-only Android TLS/UI/Keystore two-phase profile.

Reuses pinned private file profile, group runner, strict parser and direct rclone
process owner. No native work at import, request, or pure-test time.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

SOURCE = "db25f8c61fdf237449286b13812a622c9a9d2120"
PACKAGE = "com.example.chatbar.lansyncnext"
CLASS = "com.example.chatbar.lanSync.LanSyncGroupCloudRecoveryUiControlTest"
FIRST = "realCloudRecoveryUiRestoresOfflineLatestConversationAndSavesKeystoreConnection"
SECOND = "coldProcessReopensSavedRouteAndOrdinaryAndroidHooksUploadNewRevision"
TARGET = "app/app/src/androidTest/java/com/example/chatbar/lanSync/LanSyncGroupCloudRecoveryUiControlTest.kt"
ARGUMENT = "lanSyncCloudRecoveryUiFixture"
FIXTURE = "fixtures/LanSyncGroupCloudRecoveryUiControlTest.kt"
FIXTURE_SHA = "3334f685be71e95b60c6d62e4aaf1aadde229eb2057bbdd2a98129d3450dfa6b"
INPUT = "synthetic-cloud-recovery-input.properties"
USER = "synthetic-android-cloud-ui"
SCOPE = ("API35 actual Android Activity/CloudRecovery default HTTPS OkHttp, source offline latest "
         "four-message conversation and 131113-byte document, explicit UI approval/default-save, "
         "AndroidKeystore retained cold process and ordinary Android new-revision encrypted upload; "
         "not restore-only, another peer receipt, physical/commercial provider/GB/performance")
RCLONE_URL = "https://github.com/rclone/rclone/releases/download/v1.76.0/rclone-v1.76.0-linux-amd64.zip"
RCLONE_SHA = "b00f00b3266c3764413afe599d3ec316f6f43b3e0ea8a0bc1d186d2133e46df7"
PINS = {'group_ui_control.py': 'c2f4dc2ffb7f42933abb1286982e7e990d5f292fa25fa2066d66300958a0c77b', 'android_file_recovery_ui_control.py': '7a3785e243052e0a8f219c4e098db46ee85a3686a0b29a0929e33728a60e4cc9', 'instrumentation_report.py': 'af65a1a275fa5d4b80c7c46d1f09bb03bb9c19e5c822bad928974f6f67f22158', 'real_webdav_process_owner.py': 'fb186f2aa53c211a5d6d0b203dfa93fa21e843b36dab8906d9ae6ab702d6e441', 'RecoveryUiTlsMaterial.java': 'fe4398cbf625b212ea25f19ff6244419c9dfcdd5a8705bc2b7cafdbaa00440ab', 'real_webdav_interop_control.py': '96c87f845d014baf5ad3c5c5e388e29771cdcd83829cf29ad0e0ce91ad79b627', 'desktop_recovery_ui_control.py': '43c491f2c437bd86bf6fcb571fec52607e1390a85d7ebae0a666f3ef87384938'}


def require(value, code):
    if not value:
        raise ValueError(code)


def digest(path):
    require(path.is_file() and not path.is_symlink(), "PINNED_FILE_REQUIRED")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checked_module(path, pin, namespace):
    require(digest(path) == pin, "PINNED_HELPER_CHANGED")
    spec = importlib.util.spec_from_file_location(namespace, path)
    module = importlib.util.module_from_spec(spec)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


def load_profile(directory=None):
    here = Path(directory) if directory is not None else Path(__file__).resolve().parent
    require(digest(here / FIXTURE) == FIXTURE_SHA, "FIXTURE_CHANGED")
    # Only the private loaded module's constants change. Shared defaults stay byte-identical.
    profile = checked_module(here / "android_file_recovery_ui_control.py", PINS["android_file_recovery_ui_control.py"], "_ccb_cloud_private_file_profile")
    for name, value in dict(SOURCE=SOURCE, CLASS=CLASS, FIRST=FIRST, SECOND=SECOND, TARGET=TARGET,
                            ARGUMENT=ARGUMENT, FIXTURE_SHA=FIXTURE_SHA, SCOPE=SCOPE).items():
        setattr(profile, name, value)
    base = profile.load_engine(here)
    base.FIXTURE = here / FIXTURE
    owner = checked_module(here / "real_webdav_process_owner.py", PINS["real_webdav_process_owner.py"], "_ccb_cloud_private_rclone_owner")
    return profile, base, owner


def binding(here):
    return dict(kind="CCB_ANDROID_CLOUD_RECOVERY_UI_DRAFT_PROFILE", sourceSha=SOURCE, package=PACKAGE,
                api=35, previewCode=85, fixtureSha256=FIXTURE_SHA, profileSha256=digest(here / Path(__file__).name),
                phaseMethods=[FIRST, SECOND], helperSha256=PINS, overlay=TARGET, proofScope=SCOPE)


def cloud(base, owner):
    base.cloud_environment(os.environ); owner.cloud()
    require(os.environ.get("GITHUB_REPOSITORY") == "MisakaPiano/ChatChatBar-Desktop", "FIXED_REPOSITORY_REQUIRED")


def private_command(argv, environment, cwd):
    try:
        result = subprocess.run(argv, cwd=cwd, env=environment, capture_output=True, timeout=120)
        require(result.returncode == 0, "TLS_MATERIAL_FAILED")
    except subprocess.SubprocessError:
        raise ValueError("TLS_MATERIAL_FAILED") from None


def material(case, here, environment):
    require(digest(here / "RecoveryUiTlsMaterial.java") == PINS["RecoveryUiTlsMaterial.java"], "TLS_HELPER_CHANGED")
    java = Path(environment["JAVA_HOME"]) / "bin"
    argv = [str(java / "keytool"), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "2", "-dname", "CN=synthetic-android-cloud-ui", "-ext", "SAN=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost",
        "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth", "-storetype", "PKCS12",
        "-keystore", str(case / "server.p12"), "-storepass:env", "CCB_UI_TLS_STOREPASS",
        "-keypass:env", "CCB_UI_TLS_STOREPASS", "-noprompt"]
    private_command(argv, environment, case)
    private_command([str(java / "javac"), "-d", str(case), str(here / "RecoveryUiTlsMaterial.java")], environment, case)
    private_command([str(java / "java"), "-cp", str(case), "RecoveryUiTlsMaterial", str(case)], environment, case)
    cert = case / "server-cert.pem"
    require(cert.is_file() and not cert.is_symlink(), "TLS_CERT_MISSING")
    return ssl.PEM_cert_to_DER_cert(cert.read_text(encoding="ascii"))


def download(case):
    archive = case / "rclone.zip"
    hashed = hashlib.sha256(); count = 0
    with urllib.request.urlopen(RCLONE_URL, timeout=30) as source, archive.open("xb") as out:
        require(source.url.startswith("https://"), "HTTPS_ARCHIVE_REQUIRED")
        while True:
            block = source.read(64 * 1024)
            if not block:
                break
            count += len(block); require(count <= 128 * 1024 * 1024, "ARCHIVE_BUDGET")
            out.write(block); hashed.update(block)
    require(count > 0 and hashed.hexdigest() == RCLONE_SHA, "RCLONE_ARCHIVE_IDENTITY")
    name = "rclone-v1.76.0-linux-amd64/rclone"
    with zipfile.ZipFile(archive) as zipped:
        require(zipped.testzip() is None, "RCLONE_ZIP_CRC")
        entries = [item for item in zipped.infolist() if item.filename == name]
        require(len(entries) == 1 and 0 < entries[0].file_size <= 128 * 1024 * 1024 and
                entries[0].external_attr >> 16 & 0o170000 != 0o120000, "RCLONE_BINARY_ENTRY")
        binary = case / "rclone"
        with zipped.open(entries[0]) as src, binary.open("xb") as out:
            shutil.copyfileobj(src, out, 64 * 1024)
    binary.chmod(0o700)
    return binary


def input_bytes(port, password, certificate):
    require(type(port) is int and 1024 <= port <= 65535, "PORT_INVALID")
    require(len(password) == 48 and all(c in "0123456789abcdef" for c in password), "SYNTHETIC_PASSWORD_REQUIRED")
    return ("format=1\nport=" + str(port) + "\nusername=" + USER + "\npassword=" + password +
            "\ncertificate=" + base64.b64encode(certificate).decode("ascii") + "\n").encode("ascii")


class InjectThenTwoPhases:
    def __init__(self, phases, private_input, native_run=subprocess.run):
        self.phases, self.private_input, self.native_run = phases, private_input, native_run
        self.calls = 0
    def __call__(self, argv, **kwargs):
        self.calls += 1; require(self.calls == 1, "SINGLE_FIRST_PHASE_REQUIRED")
        require(argv[:2] == ["adb", "-s"] and argv[13] == CLASS + "#" + FIRST, "FIXED_FIRST_NATIVE_REQUIRED")
        adb = argv[:3]
        # Fixed remote command only; credentials travel on stdin, never argv or report.
        command = adb + ["shell", "-T", "run-as", PACKAGE, "sh", "-c",
                         "'umask 077; mkdir -p cache && set -C && cat > cache/" + INPUT + "'"]
        result = self.native_run(command, input=self.private_input, capture_output=True, timeout=30)
        require(result.returncode == 0 and not result.stdout and not result.stderr, "PRIVATE_INPUT_INSTALL_FAILED")
        self.private_input = b""
        return self.phases(argv, **kwargs)


def run(profile, base, owner, request_path, source, evidence, here):
    cloud(base, owner); base.read_request(request_path); base.overlay_status(source, base.read_request(request_path))
    expected = binding(here)
    require(json.loads((evidence / "two-phase-profile.json").read_bytes()) == expected, "PROFILE_CHANGED")
    for name in ["instrumentation.txt", "instrumentation-stderr.txt", "verified.json", "two-phase-verified.json"] + [
            "phase" + str(n) + suffix for n in (1, 2) for suffix in ("-instrumentation.txt", "-stderr.txt", "-verified.json")]:
        require(not (evidence / name).exists(), "NATIVE_EVIDENCE_EXISTS")
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    case = Path(tempfile.mkdtemp(prefix="ccb-android-cloud-ui-", dir=temporary)).resolve(strict=True)
    require(case.parent == temporary and not case.is_symlink(), "PRIVATE_CASE_ROOT")
    old_facade = base.subprocess
    try:
        password = secrets.token_hex(24)
        environment = dict(os.environ, CCB_UI_TLS_STOREPASS=secrets.token_hex(24))
        for name in ("server", "cache"):
            (case / name).mkdir()
        (case / "empty.conf").write_bytes(b"")
        cert = material(case, here, environment)
        binary = download(case)
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0)); port = reserved.getsockname()[1]
        argv = [str(binary), "serve", "webdav", str(case / "server"), "--addr", "127.0.0.1:" + str(port),
                "--user", USER, "--pass", password, "--cert", str(case / "server-cert.pem"),
                "--key", str(case / "server-key.pem"), "--min-tls-version", "tls1.2",
                "--config", str(case / "empty.conf"), "--cache-dir", str(case / "cache"), "--vfs-cache-mode", "off"]
        # Existing readiness loop is HTTP-specific. Use its unchanged TLS equivalent
        # from the desktop profile through a private function extraction, not its runner.
        desktop_path = here / "desktop_recovery_ui_control.py"
        require(digest(desktop_path) == PINS["desktop_recovery_ui_control.py"], "TLS_READINESS_HELPER_CHANGED")
        import ast
        tree = ast.parse(desktop_path.read_bytes())
        names = {"tls_readiness"}
        only = ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[])
        require(len(only.body) == 1, "TLS_READINESS_FUNCTION_MISSING")
        namespace = dict(ssl=ssl, base64=base64, urllib=__import__("urllib"), owner=owner, USER=USER,
                         require=require, base=base, time=__import__("time"))
        import urllib.error
        exec(compile(only, str(desktop_path), "exec"), namespace)
        phases = profile.TwoPhases(base, evidence, subprocess.run)
        injector = InjectThenTwoPhases(phases, input_bytes(port, password, cert))
        base.subprocess = profile.RunFacade(subprocess, injector)
        with owner.interruption_handlers() as begin_cleanup, (case / "private-server.log").open("xb") as server_log:
            owner.run_owned_server(argv, server_log,
                lambda process: namespace["tls_readiness"](process, "https://127.0.0.1:" + str(port) + "/", password, case / "server-cert.pem"),
                lambda: base.run(request_path, source, evidence), begin_cleanup)
        require(injector.calls == 1 and phases.calls == 1 and len(phases.results) == 2, "TWO_ORIGINAL_NATIVE_METHODS_REQUIRED")
        base.write_json(evidence / "two-phase-verified.json", dict(expected, phases=phases.results,
            nativeExecuted=True, forceStoppedPackageOnly=PACKAGE, clearedOrReinstalledBetweenPhases=False,
            hostnameVerification="default-positive-and-wrong-host-rejected", trustScope="single-public-cert-test-process-only",
            serverScope="runner127.0.0.1-emulator10.0.2.2-only", sourceOfflineBeforeReceiver=True,
            restoreOnlyExecuted=False, crossPeerDeliveryExecuted=False))
    finally:
        base.subprocess = old_facade
        # Only the one validated owned temporary tree; no system trust/network edit.
        require(case.parent == temporary and case.name.startswith("ccb-android-cloud-ui-") and
                case.resolve(strict=True) == case and not case.is_symlink(), "PRIVATE_CLEANUP_ROOT_CHANGED")
        shutil.rmtree(case)


def main(argv=None):
    phase = "profile"
    try:
        here = Path(__file__).resolve().parent
        profile, base, owner = load_profile(here)
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("mode", choices=["request", "prepare", "run"])
        parser.add_argument("--request", type=Path, required=True)
        parser.add_argument("--source", type=Path); parser.add_argument("--evidence", type=Path)
        args = parser.parse_args(argv)
        if args.mode == "request":
            print("sourceSha=" + base.read_request(args.request)["sourceSha"]); return 0
        require(args.source is not None and args.evidence is not None, "SOURCE_AND_EVIDENCE_REQUIRED")
        cloud(base, owner); phase = args.mode
        if args.mode == "prepare":
            base.prepare(args.request, args.source, args.evidence)
            base.write_json(args.evidence / "two-phase-profile.json", binding(here))
        else:
            run(profile, base, owner, args.request, args.source, args.evidence, here)
        return 0
    except BaseException as failure:
        print("ANDROID_CLOUD_RECOVERY_UI_REFUSED phase=" + phase + " type=" + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
