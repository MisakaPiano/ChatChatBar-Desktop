# LAN6 Desktop candidate control

`desktop_lan6_control.py` selects the fixed interim-delivered V5 LAN5 baseline
and ordinary LAN6 identity0.1.0-lan.6/0.1.40006/build40006. It reuses the existing
LAN5 context and shared validator/native engines; both layers restore constants
in finally. Use a single selected profile per CLI process.

The new build/upgrade workflows trigger only their new explicit request paths.
No actual candidate source or request is supplied by adding these controls.
After the source owner freezes the mature source, create its build request with
the new entry point. Authenticate its original ordinary bundle before creating
the upgrade request. Reuse the original V5 artifact, never rebuild the baseline.

Exact API/ZIP/source/image checks, five real Windows launches, Program-only
replacement, retained UserData and the narrow source-bound capturedAt rule stay
in the existing engines. Native verification and human acceptance remain
separate. The profile's lightweight guards establish neither result.

`desktop_lan6_workflow_plan.py` with its fixed edit-plan JSON derives the two new
workflow texts from immutable-hash/count-checked LAN5 templates. Old LAN5
workflows, request files and defaults remain unchanged.

Light guards: `python -B -m unittest test_desktop_lan6_control test_desktop_lan5_control`
from tools/lan-next-control. Actual SDK/native work remains cloud-only.
