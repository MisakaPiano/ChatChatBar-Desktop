"""Thin Windows recovery UI profile; reuse existing overlay/XML and rclone child owners."""
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
import time
import urllib.error
import urllib.request
import zipfile

import real_webdav_process_owner as owner

SOURCE = "480fbc2e6f6c6c0ac8cf3af937ecccb18bc73507"
CLASS = "com.example.chatbar.desktop.lanSync.DesktopCloudRecoveryUiControlTest"
METHOD = "realRecoveryPopupPreservesPreparationAndBothConnectionChoicesAcrossColdAppRoots"
TARGET = "app/desktopApp/src/test/kotlin/com/example/chatbar/desktop/lanSync/DesktopCloudRecoveryUiControlTest.kt"
FIXTURE = Path(__file__).resolve().parent / "fixtures/DesktopCloudRecoveryUiControlTest.kt"
FIXTURE_SHA = "9b155408ef7cccf32d030f43b82af1a9f02b6a491eefed2f82b53c4d5596f60d"
ENGINE_SHA = "1b0c56542824dad1f7462c77aee66614eda4f037958239c4f7d2d0e201c75b66"
OWNER_SHA = "fb186f2aa53c211a5d6d0b203dfa93fa21e843b36dab8906d9ae6ab702d6e441"
RCLONE_URL = "https://github.com/rclone/rclone/releases/download/v1.76.0/rclone-v1.76.0-windows-amd64.zip"
RCLONE_BYTES = 32193987
RCLONE_SHA = "8b4fd2737ade4e70c057ef4a49deb7cffe94b0fb9d7587c189d149823f21a1dc"
JAVA_HELPER_SHA = "fe4398cbf625b212ea25f19ff6244419c9dfcdd5a8705bc2b7cafdbaa00440ab"
USER = "synthetic-recovery-ui"


def _load_private_engine():
    found = importlib.util.find_spec("desktop_group_ui_control")
    if found is None or found.origin is None:
        raise ValueError("RECOVERY_ENGINE_MISSING")
    path = Path(found.origin)
    if path.is_symlink() or not path.is_file():
        raise ValueError("RECOVERY_ENGINE_CHANGED")
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != ENGINE_SHA:
        raise ValueError("RECOVERY_ENGINE_CHANGED")
    spec = importlib.util.spec_from_file_location("_ccb_private_desktop_recovery_ui_engine", path)
    if spec is None or spec.loader is None:
        raise ValueError("RECOVERY_ENGINE_MISSING")
    module = importlib.util.module_from_spec(spec)
    # Execute only the checked original bytes, with private globals. The public
    # desktop module and its default fixture/callbacks are never mutated.
    exec(compile(raw, str(path), "exec"), module.__dict__)
    return module


base = _load_private_engine()
_BASE_READ_REQUEST = base.read_request


def require(value, code):
    base.require(value, code)


def profile():
    require(hashlib.sha256(Path(base.__file__).read_bytes()).hexdigest() == ENGINE_SHA, "RECOVERY_ENGINE_CHANGED")
    require(hashlib.sha256(Path(owner.__file__).read_bytes()).hexdigest() == OWNER_SHA, "RECOVERY_PROCESS_OWNER_CHANGED")
    require(not FIXTURE.is_symlink() and hashlib.sha256(FIXTURE.read_bytes()).hexdigest() == FIXTURE_SHA,
            "RECOVERY_FIXTURE_CHANGED")
    require(hashlib.sha256(Path(__file__).with_name("RecoveryUiTlsMaterial.java").read_bytes()).hexdigest() == JAVA_HELPER_SHA,
            "RECOVERY_TLS_HELPER_CHANGED")
    base.CLASS, base.METHOD, base.TARGET, base.FIXTURE = CLASS, METHOD, TARGET, FIXTURE
    base.read_request = read_fixed_request


def read_fixed_request(path):
    value = _BASE_READ_REQUEST(path)
    require(value["sourceSha"] == SOURCE, "RECOVERY_FIXED_SOURCE_REQUIRED")
    return value


def request(path):
    profile()
    return read_fixed_request(path)


def private_command(argv, environment, cwd):
    try:
        result = subprocess.run(argv, cwd=cwd, env=environment, capture_output=True, timeout=120)
        require(result.returncode == 0, "RECOVERY_TLS_TOOL_FAILED")
    except subprocess.SubprocessError:
        raise base.ControlError("RECOVERY_TLS_TOOL_FAILED") from None


def download_rclone(case):
    archive = case / "rclone.zip"
    digest = hashlib.sha256(); count = 0
    with urllib.request.urlopen(RCLONE_URL, timeout=30) as source, archive.open("xb") as output:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            count += len(block); require(count <= RCLONE_BYTES, "RCLONE_SIZE")
            output.write(block); digest.update(block)
    require(count == RCLONE_BYTES and digest.hexdigest() == RCLONE_SHA, "RCLONE_IDENTITY")
    with zipfile.ZipFile(archive) as zipped:
        require(zipped.testzip() is None, "RCLONE_ZIP_CRC")
        name = "rclone-v1.76.0-windows-amd64/rclone.exe"
        matches = [item for item in zipped.infolist() if item.filename == name]
        require(len(matches) == 1 and 0 < matches[0].file_size <= 128 * 1024 * 1024 and
                matches[0].external_attr >> 16 & 0o170000 != 0o120000, "RCLONE_ENTRY")
        binary = case / "rclone.exe"
        with zipped.open(matches[0]) as source, binary.open("xb") as output:
            shutil.copyfileobj(source, output, 64 * 1024)
    return binary


def keytool_argv(keytool, case):
    return [str(keytool), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
            "-ext", "KU=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth", "-storetype", "PKCS12",
            "-keystore", str(case / "server.p12"), "-storepass:env", "CCB_UI_TLS_STOREPASS",
            "-keypass:env", "CCB_UI_TLS_STOREPASS", "-noprompt"]


def tls_material(case, environment):
    java = Path(environment["JAVA_HOME"]) / "bin"
    helper = Path(__file__).with_name("RecoveryUiTlsMaterial.java")
    private_command(keytool_argv(java / "keytool.exe", case), environment, case)
    private_command([str(java / "javac.exe"), "-d", str(case), str(helper)], environment, case)
    private_command([str(java / "java.exe"), "-cp", str(case), "RecoveryUiTlsMaterial", str(case)], environment, case)
    for name in ("server-key.pem", "server-cert.pem", "test-trust.jks"):
        require((case / name).is_file() and not (case / name).is_symlink(), "RECOVERY_TLS_MATERIAL_MISSING")


def tls_readiness(process, endpoint, password, cert):
    context = ssl.create_default_context(cafile=str(cert)); context.minimum_version = ssl.TLSVersion.TLSv1_2
    token = base64.b64encode((USER + ":" + password).encode("ascii")).decode("ascii")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), owner.NoRedirect(),
                                       urllib.request.HTTPSHandler(context=context))
    for _ in range(60):
        require(process.poll() is None, "RECOVERY_DAV_EARLY_EXIT")
        check = urllib.request.Request(endpoint, method="PROPFIND", headers={"Depth": "0", "Authorization": "Basic " + token})
        try:
            with opener.open(check, timeout=2) as response:
                if response.status == 207:
                    require(process.poll() is None, "RECOVERY_DAV_EARLY_EXIT")
                    return
        except urllib.error.HTTPError as failure:
            failure.close()
        except (urllib.error.URLError, OSError, TimeoutError):
            pass
        time.sleep(.25)
    raise base.ControlError("RECOVERY_DAV_NOT_READY")


def server_argv(binary, case, port, password):
    return [str(binary), "serve", "webdav", str(case / "server"), "--addr", "127.0.0.1:" + str(port),
            "--user", USER, "--pass", password, "--cert", str(case / "server-cert.pem"),
            "--key", str(case / "server-key.pem"), "--min-tls-version", "tls1.2",
            "--config", str(case / "empty.conf"), "--cache-dir", str(case / "cache"), "--vfs-cache-mode", "off"]


def run(request_path, source, evidence):
    base.cloud_windows(os.environ); request(request_path)
    base.overlay_status(source, request(request_path))
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
    case = Path(tempfile.mkdtemp(prefix="ccb-recovery-ui-tls-", dir=temporary)).resolve(strict=True)
    require(case.parent == temporary and not case.is_symlink(), "RECOVERY_TLS_ROOT")
    original_environment, original_command = base.isolated_environment, base.native_command
    state = {}
    try:
        password = secrets.token_hex(24); storepass = secrets.token_hex(24)
        (case / "password.txt").write_text(password, encoding="ascii")
        (case / "empty.conf").write_bytes(b"")
        for name in ("server/restore", "server/save", "cache"):
            (case / name).mkdir(parents=True)
        binary = download_rclone(case)
        private_environment = dict(os.environ, CCB_UI_TLS_STOREPASS=storepass)
        tls_material(case, private_environment)
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0)); port = reserved.getsockname()[1]
        endpoint = "https://127.0.0.1:" + str(port) + "/"
        init = case / "test-tls.init.gradle"
        init.write_text("""allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        if (project.path == ':desktopApp') {
            systemProperty 'javax.net.ssl.trustStore', System.getenv('CCB_RECOVERY_UI_TRUST_STORE')
            systemProperty 'javax.net.ssl.trustStoreType', 'JKS'
            systemProperty 'javax.net.ssl.trustStorePassword', ''
        }
    }
}
""", encoding="utf-8")
        def child_environment(environment):
            result = original_environment(environment)
            result.update(CCB_RECOVERY_UI_PASSWORD_FILE=str(case / "password.txt"), CCB_RECOVERY_UI_USER=USER,
                          CCB_RECOVERY_UI_ENDPOINT=endpoint, CCB_RECOVERY_UI_TRUST_STORE=str(case / "test-trust.jks"))
            return result
        base.isolated_environment = child_environment
        base.native_command = lambda selected: original_command(selected) + ["--init-script", str(init)]
        def ready(process):
            state["process"] = process
            tls_readiness(process, endpoint, password, case / "server-cert.pem")
        def work():
            base.run(request_path, source, evidence)
            base.write_json(evidence / "tls-provider-summary.json", {
                "sourceSha": SOURCE, "fixtureSha256": FIXTURE_SHA, "rcloneVersion": "1.76.0", "releaseAssetId": 717775834,
                "zipBytes": RCLONE_BYTES, "zipSha256": RCLONE_SHA, "loopbackOnly": True, "minimumTls": "tls1.2",
                "normalHostnameValidation": True, "trustScope": "only desktop test JVM; no system store mutation",
                "certificateSha256": hashlib.sha256((case / "server-cert.pem").read_bytes()).hexdigest(),
                "scope": "native Compose Popup + real Windows AppContainer/DPAPI choices and cold roots; not packaged Main/physical device/provider account"})
        with owner.interruption_handlers() as begin_cleanup, (case / "server-private.log").open("xb") as server_log:
            owner.run_owned_server(server_argv(binary, case, port, password), server_log, ready, work, begin_cleanup)
    finally:
        base.isolated_environment, base.native_command = original_environment, original_command
        process = state.get("process")
        # Do not remove files a failed bounded server cleanup might still own. Never artifact this tree.
        if process is None or process.poll() is not None:
            require(case.parent == temporary and case != temporary and case.resolve() == case, "RECOVERY_TLS_CLEANUP_ROOT")
            shutil.rmtree(case)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["request", "prepare", "run"])
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.mode == "request":
            print("sourceSha=" + request(args.request)["sourceSha"])
        else:
            base.cloud_windows(os.environ); request(args.request)
            require(args.source is not None and args.evidence is not None, "RECOVERY_SOURCE_EVIDENCE_REQUIRED")
            if args.mode == "prepare": base.prepare(args.request, args.source, args.evidence)
            else: run(args.request, args.source, args.evidence)
        return 0
    except BaseException as failure:
        print("DESKTOP_RECOVERY_UI_CONTROL_REFUSED type=" + type(failure).__name__, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
