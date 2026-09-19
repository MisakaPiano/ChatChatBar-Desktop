# CCB Desktop

This directory is the control plane for the Windows downstream port of ChatChatBar.

Upstream: `SaltyFishOTL/ChatChatBar`
Desktop fork: `MisakaPiano/ChatChatBar-Desktop`

Rules:
- `master` mirrors upstream only.
- `desktop` is the Desktop integration branch.
- `feature/*` is for feature work.
- `sync/*` is for upstream sync.
- GitHub is the shared source of truth between ChatGPT Project and Codex.
- CCB semantics come from the selected upstream commit, not from old templates or Tavern/ST assumptions.
- Package, Entity and final Prompt/runtime request are separate layers.

Read in this order:
1. 00_PROJECT_SOURCE_MAP.md
2. 10_UPSTREAM_BASELINE.json
3. 21_CURRENT_STATE.md
4. 13_FEATURE_PARITY.md
5. relevant upstream .agents/skills/*/SKILL.md
6. 14_UPSTREAM_COMPAT.md
