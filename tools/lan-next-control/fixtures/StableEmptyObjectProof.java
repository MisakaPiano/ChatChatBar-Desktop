package com.example.chatbar.desktop.lanSync;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Test-classpath-only, JDK-only same-object empty-content proof; never reads content. */
public final class StableEmptyObjectProof {
    private StableEmptyObjectProof() {}
    public enum Outcome { EMPTY_CONFIRMED, NOT_PROVEN }
    interface Identity { Boolean same(Path source, Path witness) throws IOException; }
    interface Hook { void run(Path source, Path witness) throws IOException; }
    interface Metadata { BasicFileAttributes read(Path path) throws IOException; }
    interface StoreSupport { Boolean supported(Path source, Path witnessDirectory) throws IOException; }
    private static final Hook NO_HOOK = (source, witness) -> {};
    private static final Metadata REAL_METADATA = path ->
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    private static final StoreSupport REAL_STORE_SUPPORT = (source, directory) -> {
        var sourceStore = Files.getFileStore(source);
        var witnessStore = Files.getFileStore(directory);
        return sourceStore.type().equalsIgnoreCase("NTFS") && witnessStore.type().equalsIgnoreCase("NTFS") &&
            sourceStore.equals(witnessStore);
    };

    public static Outcome confirmEmpty(Path source, Path privateWitnessDirectory) throws IOException {
        return confirmEmpty(source, privateWitnessDirectory, Files::isSameFile,
            REAL_METADATA, NO_HOOK, NO_HOOK, NO_HOOK);
    }

    // Fault injection belongs only to this helper's synthetic main probe, never the app fixture.
    static Outcome confirmEmpty(Path input, Path directory, Identity identity, Metadata metadata,
                                Hook afterWitness, Hook afterBefore, Hook afterHit) throws IOException {
        return confirmEmpty(input, directory, identity, metadata, REAL_STORE_SUPPORT,
            afterWitness, afterBefore, afterHit);
    }

    static Outcome confirmEmpty(Path input, Path directory, Identity identity, Metadata metadata,
                                StoreSupport support, Hook afterWitness, Hook afterBefore, Hook afterHit) throws IOException {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return Outcome.NOT_PROVEN;
        Path source = input.toAbsolutePath().normalize();
        Path parent = directory.toAbsolutePath().normalize();
        if (source.getFileSystem() != FileSystems.getDefault() || parent.getFileSystem() != FileSystems.getDefault() ||
            !source.getFileSystem().provider().getClass().getName().equals("sun.nio.fs.WindowsFileSystemProvider"))
            return Outcome.NOT_PROVEN;
        Path witness = parent.resolve("empty-object-" + UUID.randomUUID() + ".witness");
        FileChannel guard = null;
        boolean linked = false;
        boolean cleanupFailed = false;
        Outcome result = Outcome.NOT_PROVEN;
        try {
            BasicFileAttributes parentAttributes = REAL_METADATA.read(parent);
            if (parentAttributes.isSymbolicLink() || !parentAttributes.isDirectory()) return Outcome.NOT_PROVEN;
            if (source.equals(witness) || source.startsWith(parent)) return Outcome.NOT_PROVEN;
            BasicFileAttributes candidate = metadata.read(source);
            if (!regular(candidate) || candidate.size() != 0) return Outcome.NOT_PROVEN;
            // JDK17's 64-bit Windows isSameFile evidence is scoped to confirmed NTFS, not ReFS.
            if (!Boolean.TRUE.equals(support.supported(source, parent))) return Outcome.NOT_PROVEN;
            // The hard link pins an actual filesystem object, even when public fileKey() is null.
            Files.createLink(witness, source);
            linked = true;
            afterWitness.run(source, witness);
            guard = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS,
                ExtendedOpenOption.NOSHARE_DELETE);
            if (observe(source, witness, guard, identity, metadata, support)) {
                afterBefore.run(source, witness);
                if (observe(source, witness, guard, identity, metadata, support)) {
                    afterHit.run(source, witness);
                    if (observe(source, witness, guard, identity, metadata, support)) result = Outcome.EMPTY_CONFIRMED;
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            // No identity/metadata/API error is interpreted as empty; caller retains strict fallback.
            result = Outcome.NOT_PROVEN;
        } finally {
            // NOSHARE_DELETE also affects the private witness: close our guard first.
            if (guard != null) try { guard.close(); } catch (IOException failure) { cleanupFailed = true; }
            if (linked) try { Files.delete(witness); } catch (IOException | SecurityException failure) { cleanupFailed = true; }
            if (cleanupFailed) throw new IOException("EMPTY_OBJECT_PROOF_CLEANUP_FAILED");
        }
        return result;
    }

    private static boolean regular(BasicFileAttributes attributes) {
        return attributes != null && attributes.isRegularFile() && !attributes.isSymbolicLink();
    }

    private static boolean observe(Path source, Path witness, FileChannel guard,
                                   Identity identity, Metadata metadata, StoreSupport support) throws IOException {
        // Attribute size can be cached per hard link on Windows. Only held-handle size proves EOF.
        BasicFileAttributes left = metadata.read(source);
        BasicFileAttributes right = metadata.read(witness);
        return regular(left) && regular(right) && left.size() == 0 && right.size() == 0 &&
            Boolean.TRUE.equals(support.supported(source, witness.getParent())) &&
            !source.equals(witness) && Boolean.TRUE.equals(identity.same(source, witness)) && guard.size() == 0;
    }

    private static boolean strictReadFails(Path path) {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            input.read(new byte[64]);
            return false;
        } catch (IOException failure) { return true; }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new ProbeFailure(code);
    }
    private static final class ProbeFailure extends RuntimeException {
        final String code;
        ProbeFailure(String code) { super(); this.code = code; }
    }

    /** One standalone main invocation, with separately named logical checks; no app/UI claim. */
    public static void main(String[] args) {
        List<String> completed = new ArrayList<>();
        String failureCode = "NONE";
        String failureType = "NONE";
        boolean passed = false;
        Boolean publicFileKeyAvailable = null;
        Boolean emptyStrictReadFailed = null;
        Boolean unknownIdentityStrictReadFailed = null;
        Boolean ntfsAndSameStoreObserved = null;
        boolean realFileLockExercised = false;
        Path output = null;
        boolean outputAllowed = false;
        try {
            require(args.length == 4 && args[0].equals("--root") && args[2].equals("--output"), "ARGUMENTS");
            Path runner = Path.of(System.getenv("RUNNER_TEMP")).toRealPath();
            Path root = Path.of(args[1]).toAbsolutePath().normalize();
            output = Path.of(args[3]).toAbsolutePath().normalize();
            Path workspace = Path.of(System.getenv("GITHUB_WORKSPACE")).toRealPath();
            require(output.equals(workspace.resolve("desktop-group-retained-upgrade-evidence/empty-object-proof-native-result.json")), "OUTPUT_SCOPE");
            outputAllowed = true;
            require("true".equals(System.getenv("GITHUB_ACTIONS")) &&
                "Windows".equals(System.getenv("RUNNER_OS")) && Runtime.version().feature() == 17, "CLOUD_WINDOWS_JDK17");
            require(root.startsWith(runner) && !root.equals(runner) && !Files.exists(root), "ISOLATED_ROOT");
            Files.createDirectory(root);
            Path files = Files.createDirectory(root.resolve("files"));
            Path witnesses = Files.createDirectory(root.resolve("witnesses"));

            Path empty = Files.createFile(files.resolve("empty.bin"));
            publicFileKeyAvailable = REAL_METADATA.read(empty).fileKey() != null;
            ntfsAndSameStoreObserved = REAL_STORE_SUPPORT.supported(empty, witnesses);
            require(Boolean.TRUE.equals(ntfsAndSameStoreObserved), "CONFIRMED_NTFS_SAME_STORE_REQUIRED");
            try (FileChannel owner = FileChannel.open(empty, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lease = owner.lock()) {
                realFileLockExercised = true;
                emptyStrictReadFailed = strictReadFails(empty);
                require(confirmEmpty(empty, witnesses) == Outcome.EMPTY_CONFIRMED, "EMPTY_REAL_LOCK_CONFIRMED");
                require(lease.isValid() && owner.isOpen(), "ORIGINAL_OWNER_UNCHANGED");
            }
            completed.add("REAL_WINDOWS_LOCKED_EMPTY_CONFIRMED");

            Path nonempty = Files.write(files.resolve("nonempty.bin"), new byte[] { 7, 8, 9 });
            try (FileChannel owner = FileChannel.open(nonempty, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lease = owner.lock()) {
                require(confirmEmpty(nonempty, witnesses) == Outcome.NOT_PROVEN, "NONEMPTY_REJECTED");
                require(strictReadFails(nonempty), "NONEMPTY_STRICT_FALLBACK_FATAL");
            }
            completed.add("NONEMPTY_LOCKED_FALLBACK_REMAINS_FATAL");

            Path growth = Files.createFile(files.resolve("growth.bin"));
            try (FileChannel owner = FileChannel.open(growth, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lease = owner.lock()) {
                Outcome actual = confirmEmpty(growth, witnesses, Files::isSameFile, REAL_METADATA, NO_HOOK,
                    (source, witness) -> { owner.write(ByteBuffer.wrap(new byte[] { 5 }), 0); owner.force(true); }, NO_HOOK);
                require(actual == Outcome.NOT_PROVEN && owner.size() == 1, "GROWTH_REJECTED");
                require(strictReadFails(growth), "GROWTH_STRICT_FALLBACK_FATAL");
            }
            completed.add("GROWTH_BETWEEN_BEFORE_AND_HIT_REJECTED");

            Path laterGrowth = Files.createFile(files.resolve("later-growth.bin"));
            BasicFileAttributes cachedZero = REAL_METADATA.read(laterGrowth);
            try (FileChannel owner = FileChannel.open(laterGrowth, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lease = owner.lock()) {
                Outcome actual = confirmEmpty(laterGrowth, witnesses, Files::isSameFile, path -> cachedZero,
                    NO_HOOK, NO_HOOK, (source, witness) -> {
                        owner.write(ByteBuffer.wrap(new byte[] { 6 }), 0); owner.force(true);
                    });
                require(actual == Outcome.NOT_PROVEN && owner.size() == 1, "AFTER_GROWTH_REJECTED");
                require(strictReadFails(laterGrowth), "AFTER_GROWTH_FALLBACK_FATAL");
            }
            completed.add("AFTER_GROWTH_REJECTED_WITH_INJECTED_STALE_ZERO_ATTRIBUTES");

            Path replaced = Files.createFile(files.resolve("replaced.bin"));
            Outcome replacement = confirmEmpty(replaced, witnesses, Files::isSameFile, REAL_METADATA,
                (source, witness) -> { Files.delete(source); Files.createFile(source); }, NO_HOOK, NO_HOOK);
            require(replacement == Outcome.NOT_PROVEN, "REPLACEMENT_REJECTED");
            strictReadFails(replaced); // Actual fallback attempt; a new unlocked empty object may return EOF.
            completed.add("REAL_REPLACEMENT_AFTER_WITNESS_REJECTED");

            Path guarded = Files.createFile(files.resolve("guarded.bin"));
            boolean[] blocked = { false };
            Outcome guardedResult = confirmEmpty(guarded, witnesses, Files::isSameFile, REAL_METADATA, NO_HOOK,
                (source, witness) -> {
                    try { Files.delete(source); } catch (IOException failure) { blocked[0] = true; }
                }, NO_HOOK);
            require(blocked[0] && guardedResult == Outcome.EMPTY_CONFIRMED && Files.exists(guarded), "NOSHARE_DELETE_PREVENTS_REPLACEMENT");
            completed.add("REAL_REPLACEMENT_INSIDE_GUARD_BLOCKED");

            Path unknown = Files.createFile(files.resolve("unknown.bin"));
            try (FileChannel owner = FileChannel.open(unknown, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lease = owner.lock()) {
                require(confirmEmpty(unknown, witnesses, (source, witness) -> null, REAL_METADATA,
                    NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN, "UNKNOWN_IDENTITY_REJECTED");
                unknownIdentityStrictReadFailed = strictReadFails(unknown);
                require(confirmEmpty(unknown, witnesses, (source, witness) -> false, REAL_METADATA,
                    NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN, "WRONG_IDENTITY_REJECTED");
            }
            completed.add("INJECTED_UNKNOWN_OR_WRONG_IDENTITY_REJECTED");

            Path metadata = Files.createFile(files.resolve("metadata.bin"));
            require(confirmEmpty(metadata, witnesses, Files::isSameFile,
                path -> { throw new IOException("SYNTHETIC_METADATA_FAULT"); }, NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN,
                "METADATA_EXCEPTION_REJECTED");
            require(confirmEmpty(metadata, witnesses, Files::isSameFile, path -> null,
                NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN, "NULL_METADATA_REJECTED");
            require(confirmEmpty(metadata, witnesses, Files::isSameFile, REAL_METADATA,
                (source, directory) -> null, NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN, "UNKNOWN_FS_REJECTED");
            require(confirmEmpty(metadata, witnesses, Files::isSameFile, REAL_METADATA,
                (source, directory) -> false, NO_HOOK, NO_HOOK, NO_HOOK) == Outcome.NOT_PROVEN, "UNSUPPORTED_FS_REJECTED");
            strictReadFails(metadata); // Metadata failure never turns into the empty shortcut.
            completed.add("INJECTED_METADATA_OR_FILESYSTEM_ERROR_OR_UNKNOWN_REJECTED");

            Path wrongDirectory = Files.createDirectory(files.resolve("directory"));
            require(confirmEmpty(wrongDirectory, witnesses) == Outcome.NOT_PROVEN, "DIRECTORY_REJECTED");
            Path missing = files.resolve("missing.bin");
            require(confirmEmpty(missing, witnesses) == Outcome.NOT_PROVEN, "MISSING_REJECTED");
            completed.add("DIRECTORY_AND_MISSING_REJECTED");

            Path link = files.resolve("symbolic.bin");
            try { Files.createSymbolicLink(link, empty); }
            catch (IOException | UnsupportedOperationException | SecurityException failure) {
                throw new ProbeFailure("NATIVE_SYMBOLIC_LINK_CASE_UNAVAILABLE");
            }
            require(confirmEmpty(link, witnesses) == Outcome.NOT_PROVEN, "SYMBOLIC_LINK_REJECTED");
            require(strictReadFails(link), "SYMBOLIC_LINK_STRICT_FALLBACK_FATAL");
            completed.add("REAL_SYMBOLIC_LINK_REJECTED");

            try (var leftovers = Files.list(witnesses)) {
                require(leftovers.findAny().isEmpty(), "WITNESSES_CLEANED");
            }
            completed.add("PRIVATE_WITNESSES_CLEANED");
            passed = true;
        } catch (Throwable failure) {
            failureCode = failure instanceof ProbeFailure ? ((ProbeFailure) failure).code : "UNEXPECTED_FAILURE";
            String name = failure.getClass().getSimpleName();
            failureType = name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") ? name : "Exception";
        }
        StringBuilder checks = new StringBuilder();
        for (String value : completed) {
            if (checks.length() > 0) checks.append(',');
            checks.append('"').append(value).append('"');
        }
        String report = "{\"kind\":\"CCB_WINDOWS_STABLE_EMPTY_OBJECT_NATIVE_PROBE_V1\",\"passed\":" + passed +
            ",\"commandClass\":\"com.example.chatbar.desktop.lanSync.StableEmptyObjectProof\",\"commandMethod\":\"main\",\"invocations\":1" +
            ",\"logicalChecksCompleted\":" + completed.size() + ",\"checks\":[" + checks + "]" +
            ",\"publicFileKeyAvailable\":" + publicFileKeyAvailable + ",\"fileKeyNullUsedAsIdentity\":false" +
            ",\"ntfsAndSameStoreObserved\":" + ntfsAndSameStoreObserved +
            ",\"lockedEmptyStrictReadFailed\":" + emptyStrictReadFailed +
            ",\"unknownIdentityStrictReadAttempted\":" + (unknownIdentityStrictReadFailed != null) +
            ",\"unknownIdentityStrictReadFailed\":" + unknownIdentityStrictReadFailed +
            ",\"failureCode\":\"" + failureCode + "\",\"failureType\":\"" + failureType + "\"" +
            ",\"realWindowsFileLockExercised\":" + realFileLockExercised +
            ",\"appOrPackagedMainExecuted\":false,\"privateFilesUploaded\":false}\n";
        try {
            if (output == null || !outputAllowed) throw new IOException("OUTPUT_UNAVAILABLE");
            Files.writeString(output, report, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println(passed ? "STABLE_EMPTY_OBJECT_NATIVE_PROBE_PASS" : "STABLE_EMPTY_OBJECT_NATIVE_PROBE_FAIL");
        } catch (Throwable failure) {
            System.err.println("STABLE_EMPTY_OBJECT_NATIVE_PROBE_OUTPUT_FAILED");
            System.exit(2);
        }
        if (!passed) System.exit(2);
    }
}
