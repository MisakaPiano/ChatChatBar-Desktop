# Source ownership matrix

All references are repository-relative, pinned at06e5d863; exact source Git blobs and SHA-256 in raw/source-audit-identities.json. SOURCE_READ is static evidence, not runtime Windows proof.

| Owner / exact evidence | Observed authority and gap |
|---|---|
| security/WindowsSecretRootResolver.kt resolve19–57; WindowsSecretStore.kt create156–159 | LOCALAPPDATA/ChatChatBarDesktopCredentials independent of selected root; overlap refusal, no per-root namespace or consumer registry. No live environment/store accessed. |
| security/DesktopSecretStore.kt23–56 | load/save/delete only; global NAI/Fish/SiliconFlow, model-ID/legacy-embedding-ID/singleton keys. No endpoint/account/root in canonical key, no batch/CAS or Community key. |
| DesktopAppContainer.kt114–123,138–155,169–192,286–298 | lazy store shared by settings/model/embedding policies, NAI and Fish; full backup has no store injected. Snapshot scheduler is data-root owned. |
| DesktopNovelAiRuntime.kt; GenerationRuntime; Guidance; PostProcessor; SettingsController; ChatImageRegeneration; container179–182 | Verified current NAI consumers: runtime generation/design support, vibe guidance, postprocess, account/configuration/preflight. Provider clients can retain runtime values; quiescence/zeroization not proved. |
| DesktopFishAudioLibrary.kt24–32; container125–145 | global Fish credential settings/library/search/preview/chat TTS injected through DesktopFishAudioCredentials. Legacy/offline clients not enumerable. |
| security/DesktopRepositoryCredentialPolicies.kt30–145,152–184 | settings SiliconFlow; model and both embedding scopes hydrate runtime secrets. Key replacement precedes JSON persist; exception rollback is one-key, no crash journal. Configuration endpoint conflict needs independent target metadata. |
| sharedCore/domain/model/EffectiveModelResolver.kt; DesktopAppContainer.kt50–85 embedding gateway; sharedCore/data/repository/ModelRepository.kt | model and embedding consumers indirectly read hydrated policies. Full provider/runtime lifetime closure UNKNOWN. |
| Community search of desktop runtime + DesktopCredentialKey | community card provenance/read-only UI exists; no verified Desktop CommunitySession protected sink/auth lifecycle. Not absence of foreign session data. |
| DesktopDataDirectory.kt87–155 | explicit override > Portable marker > bootstrap > default. Recovery anchor must precede ALL selection routes. |
| DesktopDataRootOwnership.kt acquire121 onward | native root-local owner lock; does not lock shared secret root or exclude uncooperative old binaries. |
| DesktopBootstrapAuthorityTransaction.kt69–76,164–215 | independent cooperating bootstrap lock, fresh source check, save + readback, indeterminate result. Does not coordinate protected keys. |
| DesktopDataRootMigrationService.kt97–101,188–320 | pause/join -> exclusive preflight/snapshot/materialize -> authority commit/readback -> restart seal. Copies OLD, no cross-root credential transaction. |
| DesktopStartupGate.kt43–46,70–102; Main.kt | root selection/ownership/preflight then full-backup/index/session recovery before business/container. No profile credential recovery discovery. |
| DesktopDataOperationCoordinator.kt38–50 | process-only registered-operation drain/exclusion, separate cross-process root owner; no global consumer lock. |
| DesktopFullBackupService.kt49–77; DesktopFullBackupCommitJournal.kt20–74,98–145 | confirmed mapping/graph, scheduler/audio drain, root lock, schedule/restart; OLD/NEW data evidence and markers, CAS foreign bytes refusal; no SecretStore participant. |
| DesktopSessionCommitJournal / DesktopIndexCommitJournal | separate data journals in selected root; not a unified credential authority. No new invocation this task. |
| sharedCore/data/snapshot/AppDataSnapshotService.kt526 onward; DesktopCoordinatedSnapshotService / AutomaticBackupRuntime | snapshot walks selected data tree under coordination with reserved infrastructure rules. SecretStore outside root not a recoverable whole-data+key snapshot. Dormant domain needs approved validation/restore participation, not just inclusion by walk. |
| DesktopOwnedImageCleanup.kt16–52 | live entities string scan then exact-candidate owned image deletion. External sealed manifest is not an ownership adapter. |
| backup/DesktopFullBackupMapping.kt inventory; DesktopBackupPolicy | registered roots only; hidden/unknown bytes refuse, not partial preservation. Dormant namespace not registered. |
| DesktopPortableRoot / DataRootMigrationMaterializer | Portable/root copy does not transfer protector principal or fence clones/old writers; root identity binding/retained artifacts must survive switch explicitly. |

Current source owners are verified only within this tree. OLD installed binaries, offline/unregistered third roots, native protection/account identity and all-global-consumer closure remain UNKNOWN/NOT TESTED. No source search can promote them to ABSENT.
