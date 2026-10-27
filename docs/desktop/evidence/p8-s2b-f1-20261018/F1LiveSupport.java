// Validation helper only; never packaged or invoked by application startup.
import com.example.chatbar.desktop.*;
import com.example.chatbar.desktop.security.*;
import com.example.chatbar.domain.ProxyAwareClient;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
public class F1LiveSupport {
 public static void main(String[] args) throws Exception {
  Path gate=Path.of(args[0]);
  Path ledger=gate.resolve("live-result.json");
  if(Files.exists(ledger))throw new IllegalStateException("Live round already recorded; no automatic retry");
  var credentials=new DesktopFishAudioCredentials(WindowsSecretStore.Companion.create(gate.resolve("isolated-no-business-data")));
  if(credentials.load()==null){Files.writeString(ledger,"{\"status\":\"NOT_TESTED_NO_CREDENTIAL\",\"GET\":0,\"TTS\":0}");return;}
  Files.writeString(ledger,"{\"status\":\"ATTEMPT_RESERVED\",\"budget\":6,\"attempts\":1,\"GET\":0,\"model\":\"s2.1-pro-free\"}",StandardOpenOption.CREATE_NEW);
  AtomicInteger sends=new AtomicInteger(),status=new AtomicInteger();
  OkHttpClient client=ProxyAwareClient.INSTANCE.builder().eventListener(new EventListener(){
   @Override public void requestHeadersEnd(Call c,Request r){if(!"s2.1-pro-free".equals(r.header("model")))throw new IllegalStateException("Wrong model");sends.incrementAndGet();}
   @Override public void responseHeadersEnd(Call c,Response r){status.set(r.code());}
  }).build();
  var transport=new DesktopFishPreviewTransport(credentials,client,"https://api.fish.audio");
  Path folder=Files.createTempDirectory(gate,"live-owned-");
  var store=new DesktopFishPreviewStore(folder,16L*1024*1024,64L*1024*1024);
  boolean success=false;String hash="";long size=0;String failure="";
  try {
   Path clip=(Path)BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,k)->transport.generate("7f92f8afb8ec43bf81429cc1c9199cb1","这是 ChatChatBar 免费预览测试。请确认声音清晰。现在测试停止播放。","s2.1-pro-free",store,k));
   Path saved=gate.resolve("live-synthetic-preview.mp3");Files.copy(clip,saved);
   size=Files.size(saved);hash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(saved)));success=true;
  } catch(Exception e){failure=e.getClass().getSimpleName();}
  finally {BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,k)->transport.closeAndDrain(k));store.close();}
  if(sends.get()>1)throw new IllegalStateException("Physical request budget violated");
  Files.writeString(ledger,"{\"status\":\""+(success?"GENERATED":"LIVE_GATE_STOPPED")+"\",\"attempts\":1,\"GET\":0,\"TTS\":"+sends.get()+",\"httpStatus\":"+status.get()+",\"model\":\"s2.1-pro-free\",\"bytes\":"+size+",\"sha256\":\""+hash+"\",\"errorClass\":\""+failure+"\",\"billing\":\"official free-model claim; account billing not independently read\",\"textSource\":\"synthetic public test sentence\"}");
  System.out.println("F1_LIVE_RECORDED_NO_CREDENTIAL_OUTPUT");
 }
}
