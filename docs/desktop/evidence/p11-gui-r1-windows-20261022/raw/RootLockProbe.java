import java.nio.file.*;
import java.nio.channels.*;
public class RootLockProbe {
 public static void main(String[] a) throws Exception {
  try(var c=FileChannel.open(Path.of(a[0]).resolve(".ccb-desktop.lock"),StandardOpenOption.READ,StandardOpenOption.WRITE);var lock=c.tryLock()) {
   System.out.println(lock==null?"ROOT_BUSY":"ROOT_ACQUIRED_RELEASED");
   if(lock==null)System.exit(3);
  }
 }
}
