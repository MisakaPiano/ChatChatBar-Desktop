import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.example.chatbar.desktop.security.*;
public class F1PrivacyCheck {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath().normalize();
  if(!root.toString().contains("docs\\desktop\\evidence\\p8-s2b-f1-20261018"))throw new IllegalArgumentException("Evidence scope required");
  String key=WindowsSecretStore.Companion.create(Path.of(args[1])).load(DesktopCredentialKey.FishAudioApiKey.INSTANCE);
  byte[] needle=key==null?new byte[0]:key.getBytes(StandardCharsets.UTF_8);
  int count=0;
  try(var paths=Files.walk(root)){
   for(Path file:paths.filter(Files::isRegularFile).toList()){
    if(Files.size(file)>4L*1024*1024)throw new IllegalStateException("Evidence size requires separate review");
    byte[] data=Files.readAllBytes(file);
    if(needle.length>0)for(int i=0;i<=data.length-needle.length;i++){
      boolean match=true;for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j]){match=false;break;}
      if(match)throw new IllegalStateException("Credential match detected; publication blocked");
    }
    count++;
   }
  }
  System.out.println("{\"checkedFiles\":"+count+",\"existingProtectedFishKeyLoaded\":"+(needle.length>0)+",\"credentialMatches\":0,\"credentialOutput\":false}");
 }
}