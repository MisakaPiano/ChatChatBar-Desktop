import com.example.chatbar.desktop.*;
import java.nio.file.*;
public final class RootLockHold {
 public static void main(String[] args)throws Exception {
  Path scope=Path.of(args[0]);if(!scope.isAbsolute()||!scope.getFileName().toString().equals("post-p8-screenshot-windows-manual-20261021"))throw new IllegalArgumentException();
  Path root=scope.resolve("environments/post-p8-profile/ChatChatBarDesktop");
  var result=DesktopDataRootOwnership.Companion.acquire(new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,scope.resolve("synthetic-bootstrap.json")));
  if(!(result instanceof DesktopDataRootOwnershipResult.Acquired)){System.exit(76);return;}
  try(var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership()){
   Files.writeString(scope.resolve("preparation/lock-holder-ready.json"),"{\"ownerHeld\":true}",StandardOpenOption.CREATE_NEW);
   System.in.read();
  }
  System.out.println("SYNTHETIC_LOCK_HOLDER_RELEASED");
 }
}
