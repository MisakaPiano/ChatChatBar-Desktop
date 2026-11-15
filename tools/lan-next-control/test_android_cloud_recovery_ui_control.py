"""Pure guards and mocks. No JVM, Gradle, SDK, ADB, server or artifact download."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import types
import unittest
import android_cloud_recovery_ui_control as control

HERE = Path(__file__).resolve().parent
CONTROL_ROOT = r"I:\CCB-LanSigning-Proof-20261113"
FIXED_CONTROL = "1da064e91a7a94c4ff16da8934b8cec4bbfdb200"


def report(method, status=0):
    def block(code):
        return ("INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: stream=.\n"
                "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
                f"INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS: class={control.CLASS}\n"
                f"INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS_CODE: {code}\n")
    return (block(1) + block(status) + "INSTRUMENTATION_RESULT: stream=\n\nTime: 1.25\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n").encode()


def argv(method=None):
    return ["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "-r", "-e", control.ARGUMENT,
            "true", "-e", "class", control.CLASS + "#" + (method or control.FIRST),
            control.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]


class DraftPureGuards(unittest.TestCase):
    def setUp(self):
        parent = ('\\\\?\\' + str(HERE)) if sys.platform == 'win32' else str(HERE)
        self.temp = tempfile.TemporaryDirectory(prefix=".pure-", dir=parent)
        self.root = Path(self.temp.name)
        for name in control.PINS:
            local = HERE / name
            if local.is_file():
                raw = local.read_bytes()
            else:
                raw = subprocess.run(["git", "-C", CONTROL_ROOT, "show", FIXED_CONTROL + ":tools/lan-next-control/" + name],
                                     check=True, capture_output=True, timeout=30).stdout
            (self.root / name).write_bytes(raw)
        (self.root / "fixtures").mkdir()
        (self.root / control.FIXTURE).write_bytes((HERE / control.FIXTURE).read_bytes())
        self.profile, self.base, self.owner = control.load_profile(self.root)
    def tearDown(self):
        self.temp.cleanup()
    def test_private_engine_and_profile_defaults_and_stdlib_remain_unchanged(self):
        sentinel = types.ModuleType("instrumentation_report")
        previous = sys.modules.get("instrumentation_report")
        try:
            sys.modules["instrumentation_report"] = sentinel
            p2, b2, _ = control.load_profile(self.root)
            self.assertIs(sys.modules["instrumentation_report"], sentinel)
            self.assertIsNot(b2, self.base)
            old = control.checked_module(self.root / "android_file_recovery_ui_control.py", control.PINS["android_file_recovery_ui_control.py"], "_pure_old_profile")
            self.assertEqual("7ce8446b4da875aed0979c2940e5b5537b7f27a3", old.SOURCE)
            self.assertEqual(control.SOURCE, p2.SOURCE)
            self.assertEqual((35, control.CLASS, control.ARGUMENT), (b2.API, b2.CLASS, b2.INSTRUMENTATION_ARGUMENT))
            original_run = subprocess.run
            self.base.subprocess = self.profile.RunFacade(subprocess, lambda *a, **k: None)
            self.assertIs(subprocess.run, original_run)
        finally:
            if previous is None: sys.modules.pop("instrumentation_report", None)
            else: sys.modules["instrumentation_report"] = previous
    def test_all_used_helpers_and_fixture_fail_closed_on_mutation(self):
        for name in ("android_file_recovery_ui_control.py", "group_ui_control.py", "instrumentation_report.py", "real_webdav_process_owner.py", control.FIXTURE):
            path = self.root / name; raw = path.read_bytes(); path.write_bytes(raw + b"\n# changed\n")
            with self.subTest(name=name), self.assertRaises(ValueError): control.load_profile(self.root)
            path.write_bytes(raw)
    def test_fixed_request_three_fields_only_and_exact_source(self):
        expected = dict(format=1, sourceSha=control.SOURCE, fixtureSha256=control.FIXTURE_SHA)
        p = self.root / "request.json"; p.write_text(json.dumps(expected))
        self.assertEqual(expected, self.base.read_request(p))
        for value in (dict(expected, sourceSha="0" * 40), dict(expected, format=True), dict(expected, endpoint="https://example.com"), dict(expected, method=control.SECOND)):
            p.write_text(json.dumps(value))
            with self.assertRaises(ValueError): self.base.read_request(p)
        p.write_text(json.dumps(expected)[:-1] + ',"sourceSha":"' + control.SOURCE + '"}')
        with self.assertRaises(ValueError): self.base.read_request(p)
    def test_existing_cloud_emulator_and_package_guards_preserved(self):
        self.base.cloud_environment(dict(GITHUB_ACTIONS="true", RUNNER_OS="Linux"))
        for env in ({}, dict(GITHUB_ACTIONS="true", RUNNER_OS="Windows")):
            with self.assertRaises(ValueError): self.base.cloud_environment(env)
        self.assertEqual("emulator-5554", self.base.emulator_serial("List of devices attached\nemulator-5554 device\n"))
        for text in ("List of devices attached\nreal-phone device\n", "List of devices attached\nemulator-5554 device\nemulator-5556 device\n"):
            with self.assertRaises(ValueError): self.base.emulator_serial(text)
    def test_only_version85_and_truthful_preparation(self):
        apk = self.root / "synthetic.apk"; apk.write_bytes(b"MOCK ONLY")
        self.base.command = lambda *a: "package: name='" + control.PACKAGE + "' versionCode='85'\n"
        self.assertEqual("85", self.base.apk_manifest(apk, Path("unused"), control.PACKAGE, "83")["versionCode"])
        self.base.command = lambda *a: "package: name='" + control.PACKAGE + "' versionCode='83'\n"
        with self.assertRaises(ValueError): self.base.apk_manifest(apk, Path("unused"), control.PACKAGE, "83")
        p = self.root / "prepared.json"; self.base.write_json(p, dict(previewCode=83))
        self.assertEqual([control.FIRST, control.SECOND], json.loads(p.read_bytes())["phaseMethods"])
        self.assertEqual(85, json.loads(p.read_bytes())["previewCode"])
    def test_two_original_reports_and_package_only_force_stop(self):
        calls, commands = [], []
        def native(actual, **kwargs):
            calls.append(actual)
            return subprocess.CompletedProcess(actual, 0, report(control.FIRST if len(calls) == 1 else control.SECOND), b"")
        self.base.command = lambda value: commands.append(value) or ""
        phases = self.profile.TwoPhases(self.base, self.root, native)
        first = phases(argv(), capture_output=True, timeout=600)
        self.assertEqual([argv(), argv(control.SECOND)], calls)
        self.assertEqual([["adb", "-s", "emulator-5554", "shell", "am", "force-stop", control.PACKAGE]], commands)
        self.assertEqual(first.stdout, (self.root / "phase1-instrumentation.txt").read_bytes())
        self.assertEqual(report(control.SECOND), (self.root / "phase2-instrumentation.txt").read_bytes())
        self.assertEqual(2, len(phases.results))
        with self.assertRaises(ValueError): phases(argv())
    def test_failure_or_timeout_preserves_raw_and_cannot_claim_two_passes(self):
        for failed_phase in (1, 2):
            out = self.root / ("failed" + str(failed_phase)); out.mkdir(); count = 0
            def native(actual, **kwargs):
                nonlocal count
                count += 1
                method = control.FIRST if count == 1 else control.SECOND
                return subprocess.CompletedProcess(actual, 0, report(method, -2 if count == failed_phase else 0), b"")
            self.base.command = lambda value: ""
            phases = self.profile.TwoPhases(self.base, out, native)
            with self.assertRaises(ValueError): phases(argv())
            self.assertEqual(failed_phase - 1, len(phases.results))
            self.assertFalse((out / ("phase" + str(failed_phase) + "-verified.json")).exists())
        out = self.root / "timeout"; out.mkdir()
        def timeout(actual, **kwargs): raise subprocess.TimeoutExpired(actual, 600, output=b"partial MOCK", stderr=b"stderr MOCK")
        phases = self.profile.TwoPhases(self.base, out, timeout)
        with self.assertRaises(ValueError): phases(argv())
        self.assertEqual(b"partial MOCK", (out / "phase1-instrumentation.txt").read_bytes())
        self.assertFalse((out / "phase1-verified.json").exists())
    def test_no_selector_or_second_method_first(self):
        for bad in (argv(control.SECOND), argv() + ["extra"], argv()[:-1] + ["other/Runner"]):
            out = self.root / hashlib.sha256(repr(bad).encode()).hexdigest(); out.mkdir()
            phases = self.profile.TwoPhases(self.base, out, lambda *a, **k: self.fail("must refuse before native"))
            with self.assertRaises(ValueError): phases(bad)
    def test_private_stdin_only_once_and_no_credential_in_argv(self):
        password = "a" * 48; private = control.input_bytes(18765, password, b"public certificate MOCK")
        calls = []
        def native(actual, **kwargs):
            calls.append((actual, kwargs)); return subprocess.CompletedProcess(actual, 0, b"", b"")
        phase_calls = []
        injection = control.InjectThenTwoPhases(lambda actual, **k: phase_calls.append(actual) or "MOCK first native", private, native)
        self.assertEqual("MOCK first native", injection(argv(), timeout=600))
        self.assertEqual([argv()], phase_calls)
        self.assertEqual(private, calls[0][1]["input"])
        self.assertNotIn(password, repr(calls[0][0]))
        self.assertIn("run-as", calls[0][0]); self.assertEqual(b"", injection.private_input)
        with self.assertRaises(ValueError): injection(argv())
    def test_input_failure_never_starts_method(self):
        injection = control.InjectThenTwoPhases(lambda *a, **k: self.fail("must not execute method"), b"private MOCK",
            lambda actual, **kwargs: subprocess.CompletedProcess(actual, 1, b"", b"failure MOCK"))
        with self.assertRaises(ValueError): injection(argv())
    def test_fixture_exact_scope_no_recovery_bypass_and_no_secret_failure(self):
        text = (HERE / control.FIXTURE).read_text(encoding="utf8")
        self.assertEqual(2, text.count("@Test fun "))
        for denied in ("LanSyncGroupCloudRecoveryOwner(", ".prepareCloudRecovery(", ".openCloudRecovery(", ".confirm(", "trustAll", "setDefaultHostnameVerifier", "setDefaultSSLSocketFactory", "performSemanticsAction", "node.upload("):
            self.assertNotIn(denied, text)
        for expected in ("WebDavCloudClient.create()", "SSLPeerUnverifiedException", "SSLHandshakeException", "Security.removeProvider(NAME)",
                         "!panel.busy", "hasClickAction()", "isToggleable()", "consent.performClick()", "compose.waitForIdle()", "Process.myPid()", "sourceInventory", "history.admittedVersions()", "awaitExactCardRevision", "expectedCardHash"):
            self.assertIn(expected, text)
        self.assertNotIn("failure.message", text); self.assertNotIn("failure.cause", text)
        self.assertEqual(hashlib.sha256((HERE / control.FIXTURE).read_bytes()).hexdigest(), control.FIXTURE_SHA)
    def test_passive_observer_cannot_advance_or_create_normal_runtime_results(self):
        text = (HERE / control.FIXTURE).read_text(encoding="utf8")
        for denied in ("assertAuthorZero", "committedSequence", "SyncAuthorJournal(", "sourceOperations()", "reconcileForPrefetch()",
                       "targetPublication(", "inspectLocal(", ".node.inspect(", ".node.apply("):
            self.assertNotIn(denied, text)
        reader = text[text.index("    private fun existingCloudReceipt("):text.index("    private fun metadataBytes(")]
        for denied in ("createDirectory", "createDirectories", "CREATE_NEW", "prepare(", "recover", "newOutputStream", ".write("):
            self.assertNotIn(denied, reader)
        for expected in ("if (!Files.exists(directory, NOFOLLOW_LINKS)) return null", "LanSyncGroupCloudTargetReceipt.serializer()",
                         "LanSyncGroupTargetPlan.serializer()", "receipt.planHash == sha(planRaw)", "required in plan.versions",
                         "retainedSource in plan.versions", "witnesses.none", "selected.objectRequest.descriptor"):
            self.assertIn(expected, reader)
        self.assertLess(text.index('phase = "full-original-chat-replies-document-before-author-diagnostics"'),
                        text.index('val baseline = awaitPassiveBaseline('))
        diagnostic = text[text.index("    private enum class PassiveCloudRead"):text.index("    private fun existingCloudReceipt(")]
        for denied in ("createDirectory", "createDirectories", "newOutputStream", ".write(", "prepare(", "recover(",
                       "targetPublication(", "forceIdle(", "cloudWork.allowed", "cloudWork.failure", "nextRetry", "errorCategory"):
            self.assertNotIn(denied, diagnostic)
        for expected in ("progress?.pendingOperations ?: -1", "PassiveCloudRead.READ_UNAVAILABLE", "PassiveCloudRead.FILE_SET_CHANGED",
                         "diagnostic.read != PassiveCloudRead.STABLE_FILES", "diagnostic.fullReceiptsNewSlot = -1",
                         "diagnostic.partialReceiptsReportNewCardPackage = -1", "reasons.clear()",
                         "LanSyncGroupPartialTargetPlan.serializer()", "LanSyncGroupPartialCloudReceipt.serializer()",
                         "platformClassesObserved", "attemptPassiveCloudDiagnostic", "now - passiveCloudDiagnosticAt < 1_000_000_000L"):
            self.assertIn(expected, diagnostic)
        self.assertIn("catch (failure: Throwable)", text)
        self.assertIn("throw failure", text)
        self.assertIn('phase = "await-existing-exact-new-version-cloud-receipt-no-probe"', text)
        self.assertIn("PASSIVE_CLOUD_DIAGNOSTIC[$passiveCloudDiagnostic]", text)
        model = diagnostic[diagnostic.index("    private data class PassiveCloudDiagnostic("):diagnostic.index("    private var passiveCloudDiagnostic")]
        self.assertNotIn("String", model); self.assertNotIn("Path", model)
        route = text[text.index("    private enum class PassiveRouteClass"):text.index("    private enum class PassiveCloudRead")]
        for denied in (".open(", "settings.rows", "newOutputStream", "createDirectories", "prepare(", "recover(",
                       "targetPublication(", "forceIdle(", "node.upload(", "failure.message", "failure.cause"):
            self.assertNotIn(denied, route)
        for expected in ("progress[target.id]?.text", "null -> PassiveRouteClass.UNOBSERVED", "else PassiveRouteClass.UNKNOWN",
                         "passiveRouteClassesObserved.toList()", "readUnavailableObserved = true"):
            self.assertIn(expected, route)
        route_model = route[route.index("    private data class PassiveRouteDiagnostic("):route.index("    private val passiveRouteClassesObserved")]
        self.assertNotIn("String", route_model); self.assertNotIn("Path", route_model)
        self.assertIn("var before: PassiveRouteClass? = null", route_model)
        self.assertIn("var after: PassiveRouteClass? = null", route_model)
        for expected in ("PassiveRouteBoundary.BEFORE_WAIT", "PassiveRouteBoundary.AFTER_WAIT",
                         "observePassiveRouteClass(coordinator, target)", "PASSIVE_ROUTE_DIAGNOSTIC[$passiveRouteDiagnostic]"):
            self.assertIn(expected, text)
        route_enums = route[route.index("private enum class PassiveRouteClass"):route.index("private fun passiveRouteClass")]
        for name in ("UNOBSERVED", "AUTH", "PERMISSION", "LOCAL_CACHE", "FULL_OBSERVED", "UNKNOWN"):
            self.assertIn(name, route_enums)
        self.assertNotIn("NORMAL", route_enums)
        upload = text[text.index("    private enum class PassiveUploadPoint"):text.index("    private enum class PassiveCloudRead")]
        for denied in ("Files.", "prepare(", "recover(", "probe(", "println(", "stackTraceToString", ".key.name", "Thread.currentThread().name",
                       "failure.message", "failure.cause", "Dispatchers.", "launch(", "withContext("):
            self.assertNotIn(denied, upload)
        for expected in ("Thread.getAllStackTraces()", "selected.take(64)", "coerceAtMost(4096)", "frames.take(24)",
                         "NO_BLOCKING_UPLOAD_STACK", "UNLOCATED_UPLOAD", "readUnavailable = true",
                         "now - passiveUploadExecutionAt < 1_000_000_000L", "catch (_: Throwable)"):
            self.assertIn(expected, upload)
        upload_model = upload[upload.index("private data class PassiveUploadExecution("):upload.index("private class PassiveUploadExecutionSampler")]
        for denied in ("String", "StackTraceElement", "Thread>"):
            self.assertNotIn(denied, upload_model)
        self.assertIn("Set<Thread.State>", upload_model)
        self.assertIn("Map<PassiveUploadPoint, Int>", upload_model)
        self.assertEqual(2, text.count("passiveUploadExecutionSampler = PassiveUploadExecutionSampler()"))
        self.assertIn("passiveCloudDiagnosticAt = now\n        samplePassiveUploadExecution(coordinator)", text)
        self.assertIn("PASSIVE_UPLOAD_EXECUTION[$passiveUploadExecution]", text)
        worker = text[text.index("    private data class PassiveCloudWorkerExecution("):text.index("    private enum class PassiveCloudRead")]
        for denied in (".cancel(", ".start(", ".join(", "getCancellationException", "cancelCause", "failure.message", "failure.cause", "synchronized(", "withLock", ".set(coordinator"):
            self.assertNotIn(denied, worker)
        for expected in ('read("cloudWorker")', 'read("scope")', 'read("closed")', 'read("paused")',
                         "coroutineContext?.get(kotlinx.coroutines.Job)", "worker?.isActive", "worker?.isCompleted", "worker?.isCancelled",
                         "catch (_: Throwable)", "passiveCloudWorkerRecent.size > 8", "coerceAtMost(4096)"):
            self.assertIn(expected, worker)
        worker_model = worker[:worker.index("    private fun samplePassiveCloudWorkerExecution(")]
        for denied in ("String", "Job", "CoroutineScope", "Thread", "Any?", "Field"):
            self.assertNotIn(denied, worker_model)
        self.assertIn("val workerPresent: Boolean? = null", worker_model)
        self.assertEqual(2, text.count("passiveCloudWorkerRecent.clear()"))
        self.assertIn("recordPassiveCloudWorkerExecution(coordinator)", upload)
        self.assertIn("PASSIVE_CLOUD_WORKER_EXECUTION[$passiveCloudWorkerExecution]", text)
        self.assertIn("PASSIVE_CLOUD_WORKER_RECENT[$passiveCloudWorkerRecent]", text)
    def test_cold_allows_progress_but_locks_original_content_and_immutable_history(self):
        text = (HERE / control.FIXTURE).read_text(encoding="utf8")
        self.assertNotIn("nativeHeadHash", text)
        for expected in ("nativeOriginHash", "authorOriginHash", "identityHash", "retainAuthorHistory", "retainNativeHistory", "retainVersions",
                         "completedSequence >=", "version.authorSequence > priorMaximum", "priorCard.frontiers",
                         "before.copy(records = records).hashes()", "actual.characterRepository.save(edited)"):
            self.assertIn(expected, text)
        self.assertIn("cards.isEmpty() else cards.size == 1", text)
        self.assertIn("observedImportedChatChanges == 0", text)
        self.assertIn("observedCardMarkerCount == 1 && observedSessionMarkerCount == 1", text)
        self.assertIn("messageMarkerCounts.all { it == 1 }", text)
        self.assertIn('controlHistory(app, "author", setOf("intent", "signed"))', text)
        self.assertIn('controlHistory(app, "native", setOf("intent", "receipt"))', text)
        self.assertIn("observedUnaccountedIntent == 0 && observedDenseAuthorLineage", text)
        self.assertIn("val editedAuthorHistory = authorHistory(actual)", text)
        self.assertIn("retainAuthorHistory(actual, editedAuthorHistory)", text)
        self.assertIn("version.previousByAuthor == ordered.getOrNull(index - 1)?.reference()", text)
        self.assertIn("val actual = observedStableSigned && localVersionsMatchNative", text)
        self.assertIn("!observed.history.contains(required) || !observed.history.contains(retainedSource)", text)
    def test_original_ui_tls_and_native_tail_are_byte_preserved(self):
        original = subprocess.run(["git", "-C", CONTROL_ROOT, "show", FIXED_CONTROL + ":tools/lan-next-control/" + control.FIXTURE],
                                  check=True, capture_output=True, timeout=30).stdout.decode("utf8")
        text = (HERE / control.FIXTURE).read_text(encoding="utf8")
        def section(value, start, end):
            return value[value.index(start):value.index(end, value.index(start))]
        self.assertEqual(section(original, "    private fun tlsPreflight(", "    private suspend fun assertRestored("),
                         section(text, "    private fun tlsPreflight(", "    private suspend fun assertRestored("))
        self.assertEqual(original[original.index("/** One public certificate,"):], text[text.index("/** One public certificate,"):])
        self.assertEqual(section(original, '            phase = "real-activity-default-factory-cloud-ui-prepare"',
                                 "            val access = checkNotNull(owner.groupJoining.runtimeAccess())"),
                         section(text, '            phase = "real-activity-default-factory-cloud-ui-prepare"',
                                 "            val access = checkNotNull(owner.groupJoining.runtimeAccess())"))

    def test_provider_and_runner_network_are_finite_and_loopback_only(self):
        text = (HERE / "android_cloud_recovery_ui_control.py").read_text(encoding="utf8")
        self.assertIn('"127.0.0.1:" + str(port)', text)
        self.assertIn('owner.run_owned_server(', text)
        self.assertIn('owner.interruption_handlers()', text)
        self.assertIn('shutil.rmtree(case)', text)
        self.assertNotIn('subprocess.Popen(', text)
        self.assertNotIn('sys.modules["', text)
        self.assertIn('base.subprocess = old_facade', text)
        self.assertIn('restoreOnlyExecuted=False, crossPeerDeliveryExecuted=False', text)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(DraftPureGuards))
    value = dict(kind="CCB_ANDROID_CLOUD_UI_DRAFT_PURE_GUARDS", pureTests=result.testsRun,
                 failures=len(result.failures), errors=len(result.errors), successful=result.wasSuccessful(),
                 nativeCompiled=0, nativeExecuted=0, sdkExecuted=0, serverExecuted=0, downloads=0,
                 fixtureScope="synthetic mock/FS guards only; no native proof")
    (HERE / "pure-guard-results.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf8")
    raise SystemExit(0 if result.wasSuccessful() else 1)
