import java.nio.file.*;
import java.nio.channels.*;
public final class RootLockProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of("H:/ChatChatBar-Desktop/app/desktopApp/build/p11-unified-acceptance-prep-20261021/environments/manual-ready/app-data");
    try(FileChannel channel=FileChannel.open(root.resolve(".ccb-desktop.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE)) {
      try(FileLock lock=channel.tryLock()) {
        if(lock==null) throw new IllegalStateException("ROOT_BUSY");
        System.out.println("INDEPENDENT_JVM_ROOT_LOCK_ACQUIRED_AND_RELEASED");
      }
    }
  }
}
