# CCB Desktop architecture

Keep upstream Android :app stable. Add :desktopApp first; add :sharedCore later only when real JVM-neutral code is extracted.

Shared candidates:
- entities and transfer models
- Prompt/WorldBook/Context logic
- model request policies/runtime
- RAG and memory algorithms
- SaveSlot protocol
- image/voice network-domain logic

Android platform code remains behind Android adapters. Desktop provides equivalents for filesystem, file dialogs, image/audio backends, credentials, background tasks, notifications, OAuth and updates.

Desktop UI is desktop-first:
- left: cards/sessions
- center: chat
- right: context/tools
- narrow layouts collapse responsively

Prompt Inspector is a Desktop-only read-only debugging convenience; it must not change normal CCB runtime behavior.

Avoid global formatting, package renames, directory moves and duplicate Desktop copies of upstream domain logic.
