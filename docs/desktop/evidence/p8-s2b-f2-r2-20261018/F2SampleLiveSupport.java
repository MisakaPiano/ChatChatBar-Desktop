// Validation-only helper. No SecretStore/credential access and no TTS implementation.
import com.example.chatbar.desktop.*;
import com.example.chatbar.domain.ProxyAwareClient;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

public final class F2SampleLiveSupport {
 public static void main(String[] args) throws Exception {
  Path gate=Path.of(args[0]).toAbsolutePath().normalize();
  if(!gate.toString().contains("p8-s2b-f2-r2-20261018"))throw new IllegalArgumentException("Synthetic gate required");
  String source=args[1];
  DesktopFishSamplePolicy.INSTANCE.approve(source);
  Path ledger=gate.resolve("live-sample-result.json");
  if(Files.exists(ledger))throw new IllegalStateException("Live attempt already reserved; no retry");
  Files.writeString(ledger,"{\"status\":\"ATTEMPT_RESERVED\",\"attempts\":1,\"budget\":2,\"TTS\":0}",StandardOpenOption.CREATE_NEW);
  AtomicInteger sends=new AtomicInteger(),status=new AtomicInteger(),secretHeaders=new AtomicInteger();
  OkHttpClient client=ProxyAwareClient.INSTANCE.builder().dns(DesktopFishSamplePolicy.INSTANCE.getDns())
   .eventListener(new EventListener(){
    @Override public void requestHeadersEnd(Call call,Request request){
     sends.incrementAndGet();
     if(request.header("Authorization")!=null||request.header("Cookie")!=null||request.header("Proxy-Authorization")!=null)secretHeaders.incrementAndGet();
    }
    @Override public void responseHeadersEnd(Call call,Response response){status.compareAndSet(0,response.code());}
   }).build();
  DesktopFishSampleTransport transport=new DesktopFishSampleTransport(client,url->url);
  Path folder=Files.createTempDirectory(gate,"live-owned-");
  DesktopFishPreviewStore store=new DesktopFishPreviewStore(folder,16L*1024*1024,64L*1024*1024);
  boolean success=false,drained=false,cleaned=false;String hash="",failure="";long size=0;
  try {
   Path clip=(Path)BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,k)->transport.download(source,store,k));
   Path saved=gate.resolve("live-official-sample.mp3");Files.copy(clip,saved);
   size=Files.size(saved);hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(saved)));success=true;
  } catch(Exception e){failure=e.getClass().getSimpleName();}
  finally {
   try{BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,k)->transport.closeAndDrain(k));drained=true;}
   catch(Exception e){failure="TransportDrainUnconfirmed";}
   if(drained)try{store.close();cleaned=!Files.exists(folder);}catch(Exception e){failure="StoreCleanupUnconfirmed";}
  }
  boolean valid=success&&drained&&cleaned&&sends.get()==1&&status.get()==200&&secretHeaders.get()==0;
  Files.writeString(ledger,"{\"status\":\""+(valid?"ANONYMOUS_SAMPLE_VALIDATED":"LIVE_GATE_STOPPED")+"\",\"attempts\":1,\"GET\":"+sends.get()+",\"TTS\":0,\"httpStatus\":"+status.get()+",\"secretHeaders\":"+secretHeaders.get()+",\"transportDrained\":"+drained+",\"storeCleaned\":"+cleaned+",\"bytes\":"+size+",\"sha256\":\""+hash+"\",\"errorClass\":\""+failure+"\",\"sourceUrlSha256\":\""+DesktopFishSamplePolicy.INSTANCE.identity(DesktopFishSamplePolicy.INSTANCE.approve(source))+"\",\"credentialAccess\":false,\"accountBillingAccess\":false,\"audioKeptLocalOnly\":true}");
  System.out.println(valid?"F2_ANONYMOUS_SAMPLE_VALIDATED":"F2_LIVE_GATE_STOPPED");
  if(!valid)System.exit(2);
 }
}
