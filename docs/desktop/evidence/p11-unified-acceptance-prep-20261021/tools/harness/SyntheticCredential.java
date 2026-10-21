import java.nio.file.*;
import java.util.UUID;
import com.example.chatbar.desktop.security.*;

/** Only a newly created, explicitly isolated synthetic model credential. Never uses the real profile. */
public final class SyntheticCredential {
  public static void main(String[] args) throws Exception {
    Path base = Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/p11-unified-acceptance-prep-20261021").toAbsolutePath();
    Path local = Path.of(System.getenv("LOCALAPPDATA")).toAbsolutePath();
    if (!local.equals(base.resolve("profile/Local"))) throw new IllegalStateException("ISOLATION_REQUIRED");
    Path secretRoot = local.resolve("ChatChatBarDesktopCredentials");
    if (Files.exists(secretRoot)) throw new IllegalStateException("FRESH_SECRET_ROOT_REQUIRED");
    Path root = base.resolve("environments/manual-ready/app-data");
    if (!Files.isDirectory(root)) throw new IllegalStateException("SYNTHETIC_ROOT_REQUIRED");
    WindowsSecretStore store = WindowsSecretStore.Companion.create(root);
    DesktopCredentialKey.ModelApiKey key = new DesktopCredentialKey.ModelApiKey("unified-offline");
    String value = "synthetic-offline-only-" + UUID.randomUUID();
    store.save(key,value);
    if (!value.equals(store.load(key))) throw new IllegalStateException("SYNTHETIC_ROUNDTRIP_FAILED");
    System.out.println("SYNTHETIC_DPAPI_ROUNDTRIP_PASS; real credentials never accessed; no value logged");
  }
}
