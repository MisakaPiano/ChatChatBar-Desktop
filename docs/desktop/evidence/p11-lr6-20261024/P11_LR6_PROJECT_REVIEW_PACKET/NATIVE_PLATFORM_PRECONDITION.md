# LR6-B platform gate

**ENVIRONMENT_NOT_PROVEN / LR6-B NOT TESTED.** Authorization is valid; a usable
isolated environment is not proven. This is a precondition block, not a request
to repeat the approved D-045 directions or a claim that Windows lacks DPAPI.

`raw/platform-precondition.json` records no independently supplied disposable VM
or profile receipt. Command discovery did not find Get-VM/Get-VMSnapshot,
VBoxManage or vmrun in this runtime. This does **not** prove no VM exists anywhere.
Initial H: free space was below 1 GiB; no VM disk was allocated. Subsequent cleanup
does not establish identity isolation or authorize enabling host virtualization.

No native protector, SID/account/profile probe, CNG key creation, real SecretStore,
host reboot, VM power cycle, host service/account/registry/firewall modification,
old EXE, GUI, installer or Android execution occurred. No new private archive read.
The later ordinary file-handle/stream checks used for authorized storage cleanup
are separately scoped metadata/deletion operations, not LR6-B feasibility tests.

JDK17/guest OS build/NTFS/volume model/DPAPI account context are **NOT OBSERVED in
a proven disposable Windows guest**. No new JVM/JUnit/native durability result is
claimed. LR5's 29 tests / 144 child invocations remain historical accepted
synthetic process-halt evidence; none were rerun or converted to native proof.

Required before execution: independently identified disposable Windows guest and
throwaway principal, isolated writable disks and synthetic roots, known snapshot
owner/lifetime, no ordinary CCB profile or passthrough user disks, no credential
sync, network/provider access disabled, JDK17 identity and reviewed adapter source
hash, exact guest-only restart/power-off permission and retained rollback snapshot.
Provisioning host features/accounts/services is outside current authority.
Do not use the retired historical **Android** AVDs as Windows isolation evidence.

Use [VM_TEST_MATRIX](VM_TEST_MATRIX.md) as the bounded reproducible taskbook.
If guest proof remains missing, the correct final result is NOT TESTED, even when
the platform API documentation describes expected behavior.
