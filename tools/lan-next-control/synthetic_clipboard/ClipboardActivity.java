package org.ccb.synthetic.clipboard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

/** Isolated test-only app. No network, logging, broadcasts, or candidate keys. */
public final class ClipboardActivity extends Activity {
    private boolean started = false;
    private final Handler handler = new Handler();
    private String operation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        operation = getIntent().getStringExtra("operation"); // Random non-secret operation ID only.
        TextView label = new TextView(this);
        label.setText("CCB synthetic clipboard helper");
        setContentView(label);
        handler.postDelayed(new Runnable() { public void run() {
            if (!started) { started = true; perform(false); }
        } }, 12000);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && !started) {
            started = true;
            handler.post(new Runnable() { public void run() { perform(hasWindowFocus()); } });
        }
    }

    private static void require(boolean yes, String code) throws Refused {
        if (!yes) throw new Refused(code);
    }
    private static final class Refused extends Exception {
        final String code;
        Refused(String code) { this.code = code; }
    }
    private static boolean keys(JSONObject value, String... expected) {
        if (value.length() != expected.length) return false;
        for (String key : expected) if (!value.has(key)) return false;
        return true;
    }
    private static boolean payload(String kind, String text) {
        if (text.length() < 1 || text.length() > 16384) return false;
        String prefix = "invitation".equals(kind) ? "CCB-GROUP-JOIN1:" :
                "recovery".equals(kind) ? "CCB-MGMT1." : null;
        return prefix != null && text.startsWith(prefix) &&
                text.substring(prefix.length()).matches("[A-Za-z0-9_-]+");
    }
    private static byte[] readBounded(File file) throws Exception {
        require(file.isFile() && file.length() > 0 && file.length() <= 32768, "INBOX_LIMIT");
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            try {
                for (;;) {
                    int count = in.read(buffer);
                    if (count < 0) break;
                    require(out.size() + count <= 32768, "INBOX_LIMIT");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            } finally { Arrays.fill(buffer, (byte) 0); }
        }
    }
    private void receipt(JSONObject result) throws Exception {
        File target = new File(getFilesDir(), "clipboard-result.json");
        File temporary = new File(getFilesDir(), "clipboard-result.tmp");
        require(!target.exists() && !temporary.exists(), "STALE_RESULT");
        byte[] raw = result.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(raw); out.getFD().sync();
        } finally { Arrays.fill(raw, (byte) 0); }
        require(temporary.renameTo(target), "RESULT_RENAME_FAILED");
    }
    private void perform(boolean focused) {
        File inbox = new File(getFilesDir(), "clipboard-inbox.bin");
        byte[] raw = null;
        String text = null;
        JSONObject result = new JSONObject();
        String action = "UNKNOWN";
        boolean removed = false, matched = false, changed = false;
        int characters = 0;
        String error = null;
        String clearMode = "NONE";
        try {
            require(focused && hasWindowFocus(), "FOREGROUND_FOCUS_REQUIRED");
            require(operation != null && operation.matches("[a-f0-9]{32}"), "OPERATION_ID_INVALID");
            raw = readBounded(inbox);
            JSONObject value = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            require(keys(value, "format", "operation", "action", "payloadKind", "text"), "INBOX_FIELDS");
            require(value.getInt("format") == 1 && operation.equals(value.getString("operation")), "OPERATION_MISMATCH");
            action = value.getString("action");
            text = value.getString("text");
            String kind = value.getString("payloadKind");
            require(("put".equals(action) && payload(kind, text)) ||
                    ("clear".equals(action) && "none".equals(kind) && text.isEmpty()), "PAYLOAD_SCOPE");
            // The private code file must be gone BEFORE the clipboard is changed.
            removed = inbox.delete() && !inbox.exists();
            require(removed, "INBOX_DELETE_FAILED");
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            require(manager != null && hasWindowFocus(), "FOREGROUND_CLIPBOARD_REQUIRED");
            if ("put".equals(action)) {
                ClipData data = ClipData.newPlainText("CCB synthetic test", text);
                PersistableBundle extra = new PersistableBundle();
                extra.putBoolean("android.content.extra.IS_SENSITIVE", true);
                data.getDescription().setExtras(extra);
                manager.setPrimaryClip(data);
                changed = true; characters = text.length();
                ClipData check = manager.getPrimaryClip();
                matched = check != null && check.getItemCount() == 1 &&
                        text.contentEquals(check.getItemAt(0).coerceToText(this));
            } else {
                if (Build.VERSION.SDK_INT >= 28) {
                    manager.clearPrimaryClip(); clearMode = "CLEAR_PRIMARY_API28PLUS";
                } else {
                    manager.setPrimaryClip(ClipData.newPlainText("CCB synthetic test", ""));
                    clearMode = "EMPTY_CLIP_API26";
                }
                changed = true;
                ClipData check = manager.getPrimaryClip();
                matched = check == null || (check.getItemCount() == 1 &&
                        check.getItemAt(0).coerceToText(this).length() == 0);
            }
            require(matched, "CLIPBOARD_ROUNDTRIP_MISMATCH");
        } catch (Refused problem) { error = problem.code;
        } catch (Throwable ignored) { error = "HELPER_OPERATION_FAILED";
        } finally {
            if (inbox.exists()) removed = inbox.delete() && !inbox.exists();
            else removed = true;
            if (raw != null) Arrays.fill(raw, (byte) 0);
            raw = null; text = null;
            try {
                result.put("kind", "CCB_SYNTHETIC_CLIPBOARD_RESULT_V1");
                result.put("format", 1); result.put("operation", operation == null ? "" : operation);
                result.put("action", action); result.put("focused", focused);
                result.put("changed", changed); result.put("roundTripMatched", matched);
                result.put("characters", characters); result.put("inputDeleted", removed);
                result.put("clearMode", clearMode); result.put("errorCode", error == null ? JSONObject.NULL : error);
                receipt(result);
            } catch (Throwable ignored) { /* No exception text or secret logging. Host times out. */ }
            finish(); // Resume the prior original-signed CCB Activity, never launch a private CCB entry.
        }
    }
}
