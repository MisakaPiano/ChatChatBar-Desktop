import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Cloud-only companion; never copied into an application image or installer. */
public final class CommunityAuthHelperProbe {
    private static final Map<String, Object> report = new LinkedHashMap<>();
    private static String stage = "INPUT";
    private static void require(boolean value, String code) {
        if (!value) throw new IllegalStateException(code);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!(method.getName().equals(name) || method.getName().startsWith(name + "$")) || method.getParameterCount() != args.length) continue;
            Class<?>[] types = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < types.length; i++) {
                if (args[i] != null && !types[i].isInstance(args[i]) && !(types[i] == boolean.class && args[i] instanceof Boolean)) matches = false;
            }
            if (matches) { method.setAccessible(true); return method.invoke(target, args); }
        }
        throw new NoSuchMethodException(name);
    }
    private static Object singleton(String name) throws Exception {
        Class<?> type = Class.forName(name);
        Field field = type.getDeclaredField("INSTANCE"); field.setAccessible(true); return field.get(null);
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "THREE_PROBE_ARGUMENTS_REQUIRED");
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Path local = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(!Files.exists(output), "NEW_REPORT_REQUIRED");
        report.put("kind", "ACTUAL_PACKAGED_COMMUNITY_AUTH_HELPER_PROBE");
        report.put("status", "FAIL");
        report.put("realProviderOrAccount", false);
        report.put("browserShellDispatchOrFocusVerified", false);
        report.put("actualPackagedMain", false);
        report.put("actualRegistryWriteAndRemoval", false);
        Object activation = null, own = null, registry = null, foreign = null;
        Throwable failure = null;
        try {
            stage = "LOAD_PRODUCTION_CLASSES";
            String prefix = "com.example.chatbar.desktop.";
            Class<?> activationType = Class.forName(prefix + "DesktopExternalActivation");
            Object companion = activationType.getField("Companion").get(null);
            Path directory = (Path) call(companion, "directory", home, local);
            registry = singleton(prefix + "WindowsCommunityProtocolRegistry");
            Object before = call(registry, "read");
            if (before != null) {
                report.put("status", "SKIPPED_PREEXISTING_HANDLER");
                report.put("preexistingHandlerUntouched", true);
            } else {
                stage = "OPEN_WARM_AUTH_ENDPOINT";
                activation = call(companion, "open", directory, Collections.emptyList());
                require(activation != null, "FRESH_PROBE_ENDPOINT_REQUIRED");
                Object queue = call(activation, "getQueue");
                Path journal = (Path) call(queue, "getRecordPath");
                require(journal != null && !Files.exists(journal), "EMPTY_FILE_QUEUE_REQUIRED");
                Class<?> function = Class.forName("kotlin.jvm.functions.Function1");
                String callback = "chatbar://auth/callback?code=synthetic-owned-" + UUID.randomUUID();
                AtomicInteger accepted = new AtomicInteger();
                Object receiver = Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[] { function }, (proxy, method, values) -> {
                    if (method.getName().equals("invoke")) {
                        boolean valid = values != null && values.length == 1 && callback.equals(values[0]);
                        if (valid) accepted.incrementAndGet();
                        return valid;
                    }
                    if (method.getName().equals("toString")) return "SyntheticAuthReceiver";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return values != null && proxy == values[0];
                    throw new IllegalStateException("UNEXPECTED_RECEIVER_METHOD");
                });
                call(activation, "setCommunityAuthReceiver", receiver);
                Class<?> recordType = Class.forName(prefix + "DesktopCommunityProtocolRecord");
                Constructor<?> recordConstructor = recordType.getDeclaredConstructor(String.class, String.class);
                recordConstructor.setAccessible(true);
                Class<?> registryType = Class.forName(prefix + "DesktopCommunityProtocolRegistry");
                Constructor<?> ownerConstructor = Class.forName(prefix + "DesktopCommunityProtocolReturn").getDeclaredConstructor(
                    Path.class, boolean.class, registryType, boolean.class, Path.class);
                ownerConstructor.setAccessible(true);

                stage = "FOREIGN_HANDLER_PRESERVATION";
                foreign = recordConstructor.newInstance("SyntheticForeignOwner-" + UUID.randomUUID(), "synthetic-foreign-handler");
                call(registry, "write", foreign, null);
                Object blocked = ownerConstructor.newInstance(home, true, registry, true, directory);
                require("OTHER_HANDLER".equals(call(blocked, "prepare").toString()), "FOREIGN_HANDLER_NOT_REFUSED");
                call(blocked, "release");
                require(foreign.equals(call(registry, "read")), "FOREIGN_HANDLER_CHANGED");
                report.put("foreignHandlerPreserved", true);
                call(registry, "removeIfEqual", foreign); foreign = null;
                require(call(registry, "read") == null, "SYNTHETIC_FOREIGN_CLEANUP_UNCONFIRMED");

                stage = "OWN_HANDLER_REGISTRATION";
                own = ownerConstructor.newInstance(home, true, registry, true, directory);
                require("READY".equals(call(own, "prepare").toString()), "OWN_HANDLER_NOT_READY");
                Object installed = call(registry, "read");
                Path root = home.getParent().getFileName().toString().equals("versions") ? home.getParent().getParent() : home;
                Object expected = recordConstructor.newInstance(root.toString(), "\"" + home.resolve("ChatChatBarDesktop.exe") +
                    "\" --community-auth \"" + directory + "\" \"%1\"");
                require(expected.equals(installed), "INSTALLED_HANDLER_COMMAND_MISMATCH");

                stage = "ACTUAL_JPACKAGE_MAIN_RETURN";
                ProcessBuilder childBuilder = new ProcessBuilder(home.resolve("ChatChatBarDesktop.exe").toString(), "--community-auth", directory.toString(), callback)
                    .directory(root.toFile())
                    .redirectOutput(output.resolveSibling("auth-helper-child.stdout.log").toFile())
                    .redirectError(output.resolveSibling("auth-helper-child-stderr.log").toFile());
                childBuilder.environment().put("LOCALAPPDATA", local.resolve("synthetic-browser-local").toString());
                childBuilder.environment().merge("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true", (old, extra) -> old + " " + extra);
                Process child = childBuilder.start();
                boolean exited = child.waitFor(30, TimeUnit.SECONDS);
                report.put("helperExited", exited);
                require(exited, "HELPER_EXIT_NOT_CONFIRMED");
                report.put("helperExitCode", child.exitValue());
                require(child.exitValue() == 0 && accepted.get() == 1, "HELPER_AUTH_DELIVERY_NOT_CONFIRMED");
                // After a parent exits, descendants may have been reparented. A late empty
                // ProcessHandle stream cannot prove their exit; the ordinary Job test is separate.
                report.put("helperDescendantExit", "NOT_PROVEN_PARENT_WAIT_ONLY");
                require(((Number) call(call(activation, "getCommunityActivation"), "getValue")).longValue() == 1L,
                    "MEMORY_WINDOW_SIGNAL_NOT_OBSERVED");
                require(!Files.exists(journal), "AUTH_ENTERED_FILE_JOURNAL");
                report.put("actualPackagedMain", true);
                report.put("acceptedAuthCallbacks", accepted.get());
                report.put("memoryWindowSignalObserved", true);
                report.put("explicitEndpointWithDifferentLocalAppData", true);
                report.put("fileQueueUntouched", true);

                stage = "OWN_HANDLER_REMOVAL";
                call(own, "release"); own = null;
                require(call(registry, "read") == null, "OWN_HANDLER_REMOVAL_UNCONFIRMED");
                report.put("actualRegistryWriteAndRemoval", true);
                report.put("status", "PASS");
            }
        } catch (Throwable error) {
            failure = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            report.put("failedStage", stage);
            report.put("errorType", failure.getClass().getName());
            if (failure instanceof IllegalStateException && failure.getMessage() != null && failure.getMessage().matches("[A-Z0-9_]+"))
                report.put("code", failure.getMessage());
        } finally {
            try {
                if (own != null) call(own, "release");
                if (foreign != null && foreign.equals(call(registry, "read"))) call(registry, "removeIfEqual", foreign);
                if (activation != null) call(activation, "close");
            } catch (Throwable cleanup) {
                report.put("cleanupUnconfirmed", true); report.put("cleanupErrorType", cleanup.getClass().getName());
                if (failure == null) failure = cleanup;
            }
            if (failure != null) report.put("status", "FAIL");
            StringJoiner rows = new StringJoiner(",\n", "{\n", "\n}\n");
            report.forEach((key, value) -> rows.add(json(key) + ": " + json(value)));
            Files.writeString(output, rows.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) System.exit(2);
    }
}
