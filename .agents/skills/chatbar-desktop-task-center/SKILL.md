---
name: chatbar-desktop-task-center
description: Maintain Desktop task-center projections, exact owner cancellation, task navigation and tray notification adapters.
---

# Desktop Task Center

- `DesktopTaskCenter` observes registered owner flows; it does not schedule feature work. Row IDs are namespaced by owner, stop/retry dispatches to the original ID, and stopRequested remains active until the real owner publishes a terminal state or retires its session activity. Observer shutdown joins its own collectors/commands, separate from business shutdown.
- `DesktopTaskCenterSources` projects TaskRuntime chat/image/repair and its existing document/card-design children. `ownedWork` uses the same UUID and Job already in TaskRuntime's shutdown drain, with completion observed after that child finishes. Format-card completion means candidate work, not applying/saving an entity. Unknown repair commit remains UNCONFIRMED and is not replayable retry.
- Memory rows observe actual coordinator.runningSessions, including manual actions/reads. They are session activity, not paid-call proof. Stop uses coordinator.stopAndJoin(rowSession), never DesktopMemoryController.stop()'s selected panel session or permanent deletion cancellation. Session disappearance is not synthesized success/history.
- AppContainer registers projections when each lazy owner is constructed; displaying the center must not initialize unrelated feature owners. It drains the observer after the existing business owners. Local context labels resolve only relevant session/card/document/draft names, are cached for retained rows, and remain separate from the fixed kind/status notice payload.
- Shell entry uses the existing modal/root/leave guards and native title-bar hit exclusions. A target carries actual session/card/document/draft IDs; opening a generic route does not prove target navigation. Preserve dirty editors and verify that the exact target still exists.
- Feature-specific verification starts with DesktopTaskCenterTest, actual owner cancellation/drain and matching target/Scene tests. Runtime, UI, tray capability, OS notification delivery and human acceptance are separate evidence; current scope is tracked in docs/desktop/251_TASK_CENTER_AND_TRAY.md.
