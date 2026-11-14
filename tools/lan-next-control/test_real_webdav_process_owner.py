"""Pure mocks: never start a process, network server, shell, Gradle or SDK."""
from contextlib import contextmanager, redirect_stderr
import io
from pathlib import Path
import subprocess
import unittest
from unittest import mock

import real_webdav_process_owner as owner


class ProcessOwnerTest(unittest.TestCase):
    def process(self):
        child = mock.Mock()
        child.poll.return_value = None
        child.wait.return_value = 0
        return child

    def test_already_reaped_process_is_never_signalled_or_waited_again(self):
        child = self.process(); child.poll.return_value = 0
        owner.stop_owned_server(child)
        child.terminate.assert_not_called(); child.kill.assert_not_called(); child.wait.assert_not_called()

    def test_terminate_has_exact_five_second_wait(self):
        child = self.process()
        owner.stop_owned_server(child)
        child.terminate.assert_called_once_with(); child.kill.assert_not_called()
        child.wait.assert_called_once_with(timeout=5)

    def test_term_timeout_kills_only_still_unreaped_child_and_waits_five(self):
        child = self.process()
        child.wait.side_effect = [subprocess.TimeoutExpired(['private-server-argv'], 5), 0]
        owner.stop_owned_server(child)
        child.terminate.assert_called_once_with(); child.kill.assert_called_once_with()
        self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [5, 5])

    def test_child_exiting_after_term_timeout_is_not_killed(self):
        child = self.process(); child.poll.side_effect = [None, 0]
        child.wait.side_effect = subprocess.TimeoutExpired('private-server', 5)
        owner.stop_owned_server(child)
        child.kill.assert_not_called(); self.assertEqual(child.wait.call_count, 1)

    def test_kill_timeout_becomes_safe_error_without_server_argv(self):
        child = self.process(); secret = 'synthetic-password-must-not-print'
        child.wait.side_effect = subprocess.TimeoutExpired(['rclone', '--pass', secret], 5)
        with self.assertRaises(owner.OwnerFailure) as caught:
            owner.stop_owned_server(child)
        self.assertEqual(str(caught.exception), 'SERVER_KILL_WAIT_EXPIRED')
        self.assertNotIn(secret, str(caught.exception))
        self.assertEqual([call.kwargs['timeout'] for call in child.wait.call_args_list], [5, 5])

    def test_exit_race_after_poll_reaps_only_the_owned_child(self):
        child = self.process(); child.terminate.side_effect = ProcessLookupError()
        owner.stop_owned_server(child)
        child.wait.assert_called_once_with(timeout=5); child.kill.assert_not_called()

    def test_success_runs_cleanup_before_return_and_keeps_outer_group(self):
        child = self.process(); events = []
        child.terminate.side_effect = lambda: events.append('terminate')
        child.wait.side_effect = lambda **kw: events.append('wait') or 0
        with mock.patch.object(owner.subprocess, 'Popen', return_value=child) as spawn:
            result = owner.run_owned_server(['synthetic-not-executed'], None,
                lambda p: events.append('ready'), lambda: events.append('client') or 0,
                lambda: events.append('cleanup'))
        self.assertEqual(result, 0)
        self.assertEqual(events, ['ready', 'client', 'cleanup', 'terminate', 'wait'])
        self.assertIs(spawn.call_args.kwargs['start_new_session'], False)
        self.assertNotIn('preexec_fn', spawn.call_args.kwargs)

    def test_nonzero_gradle_exit_still_terminates_and_reaps_server(self):
        child = self.process()
        with mock.patch.object(owner.subprocess, 'Popen', return_value=child):
            self.assertEqual(owner.run_owned_server(['synthetic'], None, lambda p: None, lambda: 9), 9)
        child.terminate.assert_called_once_with(); child.wait.assert_called_once_with(timeout=5)

    def test_readiness_and_client_exceptions_preserve_identity_and_cleanup(self):
        for phase in ('ready', 'client'):
            child = self.process(); failure = RuntimeError('synthetic-primary')
            def ready(p):
                if phase == 'ready': raise failure
            def work():
                if phase == 'client': raise failure
                return 0
            with mock.patch.object(owner.subprocess, 'Popen', return_value=child):
                with self.assertRaises(RuntimeError) as caught:
                    owner.run_owned_server(['synthetic'], None, ready, work)
            self.assertIs(caught.exception, failure)
            child.terminate.assert_called_once_with(); child.wait.assert_called_once_with(timeout=5)

    def test_cleanup_failure_does_not_erase_existing_client_failure(self):
        child = self.process(); failure = ValueError('synthetic-client-failure')
        child.wait.side_effect = subprocess.TimeoutExpired(['rclone', '--pass', 'private-token'], 5)
        def work(): raise failure
        output = io.StringIO()
        with mock.patch.object(owner.subprocess, 'Popen', return_value=child), redirect_stderr(output):
            with self.assertRaises(ValueError) as caught:
                owner.run_owned_server(['synthetic'], None, lambda p: None, work)
        self.assertIs(caught.exception, failure)
        self.assertNotIn('private-token', output.getvalue())
        self.assertIn('OwnerFailure', output.getvalue())

    def test_keyboard_interruption_also_cleans_only_the_owned_server(self):
        child = self.process(); failure = KeyboardInterrupt()
        def work(): raise failure
        with mock.patch.object(owner.subprocess, 'Popen', return_value=child):
            with self.assertRaises(KeyboardInterrupt) as caught:
                owner.run_owned_server(['synthetic'], None, lambda p: None, work)
        self.assertIs(caught.exception, failure)
        child.terminate.assert_called_once_with(); child.wait.assert_called_once_with(timeout=5)

    def test_signal_handlers_restore_and_repeat_signals_cannot_restart_cleanup(self):
        previous = {owner.signal.SIGINT: object(), owner.signal.SIGTERM: object()}
        installed = {}
        def install(sig, handler): installed[sig] = handler
        with mock.patch.object(owner.signal, 'getsignal', side_effect=lambda sig: previous[sig]), \
             mock.patch.object(owner.signal, 'signal', side_effect=install):
            with owner.interruption_handlers() as begin_cleanup:
                handler = installed[owner.signal.SIGTERM]
                with self.assertRaisesRegex(owner.OwnerFailure, 'PROCESS_OWNER_INTERRUPTED'):
                    handler(owner.signal.SIGTERM, None)
                begin_cleanup()
                handler(owner.signal.SIGTERM, None)
                handler(owner.signal.SIGINT, None)
        self.assertEqual(installed, previous)

    def test_local_cli_refuses_before_any_process_or_network(self):
        output = io.StringIO()
        with mock.patch.dict(owner.os.environ, {}, clear=True), \
             mock.patch.object(owner.subprocess, 'Popen', side_effect=AssertionError('must not spawn')), \
             mock.patch.object(owner.urllib.request, 'build_opener', side_effect=AssertionError('must not network')), \
             redirect_stderr(output):
            self.assertEqual(owner.main(['--source-dir', 'unused', '--case-root', 'unused']), 70)
        self.assertIn('phase=validate', output.getvalue())

    def test_main_never_prints_timeout_command_or_password(self):
        secret = 'synthetic-password-must-not-escape'
        failure = subprocess.TimeoutExpired(['rclone', '--pass', secret], 5)
        @contextmanager
        def handlers(): yield lambda: None
        output = io.StringIO(); fake_log = mock.MagicMock()
        fake_log.open.return_value.__enter__.return_value = None
        with mock.patch.object(owner, 'cloud'), \
             mock.patch.object(owner, 'layout', return_value=(Path('source'), Path('case'), Path('rclone'), fake_log, secret)), \
             mock.patch.object(owner, 'interruption_handlers', handlers), \
             mock.patch.object(owner, 'run_owned_server', side_effect=failure), redirect_stderr(output):
            self.assertEqual(owner.main(['--source-dir', 'source', '--case-root', 'case']), 70)
        self.assertIn('TimeoutExpired', output.getvalue())
        self.assertNotIn(secret, output.getvalue()); self.assertNotIn('--pass', output.getvalue())

    def test_server_death_fails_before_a_readiness_request(self):
        child = self.process(); child.poll.return_value = 2
        with mock.patch.object(owner.urllib.request, 'build_opener') as opener:
            with self.assertRaisesRegex(owner.OwnerFailure, 'SERVER_EXITED_BEFORE_READY'):
                owner.readiness(child, '0' * 48)
            opener.return_value.open.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
