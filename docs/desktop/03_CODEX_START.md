# Codex first-pass instruction

This repository is the long-term Windows downstream port of SaltyFishOTL/ChatChatBar.

Git rules:
- origin = MisakaPiano/ChatChatBar-Desktop
- upstream = SaltyFishOTL/ChatChatBar
- master mirrors upstream only
- desktop is the integration branch
- feature/* for features
- sync/* for upstream sync

Read:
- root AGENTS.md
- .agents/skills/chatbar-feature-map/SKILL.md
- docs/desktop/00_PROJECT_SOURCE_MAP.md
- docs/desktop/10_UPSTREAM_BASELINE.json
- docs/desktop/11_DESKTOP_PORT_AUDIT.md
- docs/desktop/12_DESKTOP_ARCHITECTURE.md
- docs/desktop/13_FEATURE_PARITY.md
- docs/desktop/14_UPSTREAM_COMPAT.md
- docs/desktop/17_ROADMAP.md
- docs/desktop/21_CURRENT_STATE.md

For specific features, also read the matching upstream .agents/skills/*/SKILL.md.

FIRST PASS IS READ-ONLY:
1. report git status, HEAD and remotes;
2. verify the recorded baseline against upstream;
3. verify Gradle/module structure and version facts;
4. verify Package schemas, JsonFileStorage, Prompt pipeline, model runtime, image/NovelAI, Fish, RAG/memory, SaveSlot, Moments, Community, shared import, updater and QQ voice;
5. report stale/incorrect Desktop docs;
6. propose the smallest Phase 1 Desktop bootstrap task.

Do not modify source code in the first pass.
