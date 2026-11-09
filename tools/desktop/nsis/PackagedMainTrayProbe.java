package ccb.synthetic;

import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** External CI companion only. Loads the unmodified packaged Main; never included in its image. */
public final class PackagedMainTrayProbe {
    private static final Map<String,Object> facts = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final AtomicReference<Throwable> failure = new AtomicReference<>();
    private static final AtomicBoolean mainReturned = new AtomicBoolean();
    private static Path report;

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }
    private static void awt(Runnable action) throws Exception {
        if (EventQueue.isDispatchThread()) action.run(); else EventQueue.invokeAndWait(action);
    }
    private static boolean onAwt(BooleanSupplier action) throws Exception {
        AtomicBoolean result = new AtomicBoolean(); awt(() -> result.set(action.getAsBoolean())); return result.get();
    }
    private static Frame mainWindow() {
        return Arrays.stream(Frame.getFrames()).filter(f -> "ChatChatBar".equals(f.getTitle()) && f.isDisplayable()).findFirst().orElse(null);
    }
    private static void await(BooleanSupplier condition, int seconds, String code) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            require(!mainReturned.get(), "MAIN_RETURNED_BEFORE_" + code);
            require(System.nanoTime() < deadline, code); Thread.sleep(40);
        }
    }
    private static void menu(String label) throws Exception {
        awt(() -> {
            TrayIcon[] icons = SystemTray.getSystemTray().getTrayIcons(); require(icons.length == 1, "EXACT_ONE_APP_TRAY_REQUIRED");
            PopupMenu popup = icons[0].getPopupMenu(); MenuItem selected = null;
            for (int i = 0; i < popup.getItemCount(); i++) if (label.equals(popup.getItem(i).getLabel())) selected = popup.getItem(i);
            require(selected != null && selected.isEnabled(), "ORIGINAL_TRAY_MENU_UNAVAILABLE");
            ActionEvent event = new ActionEvent(selected, ActionEvent.ACTION_PERFORMED, selected.getActionCommand());
            ActionListener[] listeners = selected.getActionListeners(); require(listeners.length == 1, "EXACT_ORIGINAL_MENU_LISTENER_REQUIRED");
            listeners[0].actionPerformed(event);
        });
    }
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
    private static synchronized void save() throws Exception {
        StringJoiner json = new StringJoiner(",", "{", "}\n");
        synchronized (facts) { facts.forEach((k,v) -> json.add(quoted(k) + ":" + (v instanceof Boolean || v instanceof Number ? v : quoted(String.valueOf(v))))); }
        Files.writeString(report, json.toString());
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3 && "true".equals(System.getenv("GITHUB_ACTIONS")) &&
            "MisakaPiano/ChatChatBar-Desktop".equals(System.getenv("GITHUB_REPOSITORY")), "SYNTHETIC_CI_ONLY");
        Path home = Path.of(args[0]).toRealPath(); report = Path.of(args[1]).toAbsolutePath().normalize();
        require(args[2].matches("[a-f0-9]{40}") && !Files.exists(report), "EXACT_NEW_REPORT_REQUIRED");
        Class<?> main = Class.forName("com.example.chatbar.desktop.MainKt");
        Path origin = Path.of(main.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(origin.startsWith(home.resolve("app")), "PACKAGED_MAIN_CLASS_ORIGIN_REQUIRED");
        Properties identity = new Properties();
        try (var input = main.getResourceAsStream("/desktop-build.properties")) { require(input != null, "BUILD_IDENTITY_REQUIRED"); identity.load(input); }
        require(args[2].equals(identity.getProperty("commit")), "EXACT_PACKAGED_MAIN_SOURCE_REQUIRED");
        facts.put("source", args[2]); facts.put("version", identity.getProperty("version")); facts.put("mainClassOrigin", origin.toString());
        facts.put("javaHome", System.getProperty("java.home")); facts.put("ciJdk17", Runtime.version().feature() == 17);
        facts.put("packagedMainClasses", true); facts.put("ordinaryLauncher", false); facts.put("bundledRuntime", false);
        facts.put("osMouseGesture", false); facts.put("agentInjected", false); facts.put("providerCalls", false);
        // Production deliberately calls exitProcess only after its original MAIN_RETURNED / SAFE_EXIT_HANDOFF.
        // Observe that shutdown without replacing terminate(), intercepting System.exit, or forcing exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                facts.put("jvmShutdownHookEntered", true);
                facts.put("reflectiveMainReturned", mainReturned.get());
                facts.put("mainWindowsDisposed", mainWindow() == null);
                facts.put("trayIconRemoved", !SystemTray.isSupported() || SystemTray.getSystemTray().getTrayIcons().length == 0);
                facts.put("passed", failure.get() == null && Boolean.TRUE.equals(facts.get("mainCloseHiddenAndStillDisplayable")) &&
                    Boolean.TRUE.equals(facts.get("originalTrayMenuRestoredMain")) && Boolean.TRUE.equals(facts.get("originalTrayExitRequested")) &&
                    Boolean.TRUE.equals(facts.get("mainWindowsDisposed")) && Boolean.TRUE.equals(facts.get("trayIconRemoved")));
                save();
            } catch (Throwable ignored) { /* Missing facts remain an external-driver failure. */ }
        }, "synthetic-original-jvm-shutdown-observer"));
        Thread script = new Thread(() -> {
            try {
                AtomicReference<Frame> frame = new AtomicReference<>();
                await(() -> { try { awt(() -> { Frame f = mainWindow(); if (f != null && f.isVisible()) frame.set(f); }); return frame.get() != null; }
                    catch (Exception e) { throw new IllegalStateException("WINDOW_OBSERVATION_FAILED"); } }, 180, "MAIN_WINDOW_READY_TIMEOUT");
                require(onAwt(SystemTray::isSupported), "NATIVE_TRAY_CAPABILITY_REQUIRED");
                await(() -> { try { return onAwt(() -> SystemTray.getSystemTray().getTrayIcons().length == 1); }
                    catch (Exception e) { throw new IllegalStateException("TRAY_OBSERVATION_FAILED"); } }, 20, "APP_TRAY_READY_TIMEOUT");
                facts.put("mainInitiallyVisible", true);
                awt(() -> frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING)));
                await(() -> { try { return onAwt(() -> frame.get().isDisplayable() && !frame.get().isVisible()); }
                    catch (Exception e) { throw new IllegalStateException("HIDE_OBSERVATION_FAILED"); } }, 20, "MAIN_CLOSE_DID_NOT_HIDE");
                facts.put("mainCloseHiddenAndStillDisplayable", true); require(!mainReturned.get(), "HIDE_EXITED_MAIN");
                menu("打开主窗口");
                await(() -> { try { return onAwt(() -> frame.get().isVisible()); }
                    catch (Exception e) { throw new IllegalStateException("RESTORE_OBSERVATION_FAILED"); } }, 20, "TRAY_DID_NOT_RESTORE_MAIN");
                facts.put("originalTrayMenuRestoredMain", true);
                facts.put("originalTrayExitRequested", true); save(); menu("退出");
            } catch (Throwable error) {
                failure.set(error); facts.put("failureClass", error.getClass().getName()); facts.put("passed", false);
                if (error.getMessage() != null && error.getMessage().matches("[A-Z0-9_]{1,100}")) facts.put("failureCode", error.getMessage());
                try { save(); menu("退出"); } catch (Exception ignored) { /* No forced process termination or false drain result. */ }
            }
        }, "synthetic-packaged-main-tray");
        script.setDaemon(true); script.start();
        try { main.getMethod("main", String[].class).invoke(null, (Object)new String[0]); }
        catch (InvocationTargetException error) { failure.compareAndSet(null, error.getCause()); }
        finally { mainReturned.set(true); }
        script.join(30000);
        require(!script.isAlive(), "ORIGINAL_MENU_SCRIPT_NOT_DRAINED");
        facts.put("mainReturned", true); facts.put("mainWindowsDisposed", onAwt(() -> mainWindow() == null));
        facts.put("trayIconRemoved", onAwt(() -> !SystemTray.isSupported() || SystemTray.getSystemTray().getTrayIcons().length == 0));
        facts.put("passed", failure.get() == null && Boolean.TRUE.equals(facts.get("originalTrayExitRequested")) &&
            Boolean.TRUE.equals(facts.get("mainWindowsDisposed")) && Boolean.TRUE.equals(facts.get("trayIconRemoved")));
        save(); require(Boolean.TRUE.equals(facts.get("passed")), "PACKAGED_MAIN_TRAY_CHECK_FAILED");
    }
}
