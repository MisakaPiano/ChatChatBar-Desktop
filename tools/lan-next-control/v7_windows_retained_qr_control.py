"""Run only the retained synthetic Windows group needed for the V7 QR check."""
import os
from pathlib import Path
import uuid

import desktop_lan7_prepared_upgrade_control as control


ACTIONS = ("SEED", "RESUME", "COLD_INVITE")


def run():
    with control.borrowed() as (bridge, modules, profile), profile.profile():
        desktop = modules["desktop_control"]
        desktop.cloud()
        checkout = Path.cwd().resolve(strict=True)
        control.require(os.environ["RUNNER_OS"] == "Windows"
                        and checkout == Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True),
                        "EXACT_WINDOWS_CHECKOUT")
        control.require(bridge.command("git", "-C", str(checkout), "status", "--porcelain", "--untracked-files=all") == b"",
                        "CLEAN_CONTROL")
        head = bridge.command("git", "-C", str(checkout), "rev-parse", "HEAD").decode().strip()
        control.require(head == os.environ["GITHUB_SHA"], "EXACT_CONTROL_HEAD")
        control.require(control.digest(control.FIXTURE) == control.FIXTURE_SHA, "EXACT_FIXTURE")
        control.validate_request(desktop, control.small(checkout / control.REQUEST))

        runner = Path(os.environ["RUNNER_TEMP"]).resolve(strict=True)
        control.require(not runner.is_relative_to(checkout) and not checkout.is_relative_to(runner),
                        "SEPARATE_RUNNER_TEMP")
        private = runner / ("ccb-v7-qr-" + uuid.uuid4().hex)
        private.mkdir()
        output = checkout / "v7-retained-qr-evidence"
        output.mkdir()
        phase = "prepare"
        companion = None
        summary = {
            "kind": "CCB_V7_RETAINED_WINDOWS_PANEL_QR",
            "oldSource": control.OLD,
            "candidateSource": control.NEW,
            "controlSha": head,
            "fixtureSha256": control.FIXTURE_SHA,
            "packagedMainExercisedHere": False,
            "physicalCameraExercisedHere": False,
            "passed": False,
        }
        try:
            sources = {}
            for commit, leaf in ((control.OLD, "old-source"), (control.NEW, "new-source")):
                source = private / leaf
                bridge.command("git", "-C", str(checkout), "worktree", "add", "--detach", str(source), commit)
                sources[commit] = source
            companion = control.Companion(bridge, modules, sources, private, output)
            companion.prepare()
            root = control.make_archive_root(runner, private)
            modules["prove_desktop_lan3_lan4"].legacy().seed(root / "UserData/Local/ChatChatBarDesktop")
            for phase in ACTIONS:
                companion.run(root, phase)
            summary["passed"] = True
        except Exception as error:
            summary.update(failedAt=phase, errorType=control.safe_type(error),
                           componentFailureDiagnostic=companion.failure if companion else None)
        summary["components"] = companion.originals if companion else []
        control.write_new(output / "focused-result.json", summary)
        print("RETAINED_WINDOWS_QR_PASSED" if summary["passed"] else "RETAINED_WINDOWS_QR_FAILED:" + phase)
        return 0 if summary["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(run())
