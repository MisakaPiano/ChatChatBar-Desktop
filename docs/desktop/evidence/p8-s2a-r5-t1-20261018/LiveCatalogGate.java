// Local validation only: fixed HTTPS GET /model, no TTS, no chat access, no response body logging.
import com.example.chatbar.desktop.*;
import com.example.chatbar.desktop.security.*;
import com.example.chatbar.domain.voice.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import okhttp3.*;
public final class LiveCatalogGate {
 static Path output; static int attempts; static AtomicInteger forwards=new AtomicInteger(); static String rows="";
 static void save(String state)throws Exception{Files.writeString(output,"{\"state\":\""+state+"\",\"logicalAttempts\":"+attempts+",\"httpForwards\":"+forwards.get()+",\"ttsCalls\":0,\"automaticRetriesAllowed\":false,\"responseBodyRecorded\":false,\"feeStatus\":\"GET_ONLY_NO_TTS_ACCOUNT_BILLING_NOT_INSPECTED\",\"results\":["+rows+"]}\n",StandardCharsets.UTF_8);}
 public static void main(String[] args)throws Exception{
  Path gate=Path.of(args[0]); output=gate.resolve("live-catalog-result.json");
  if(Files.exists(output))throw new IllegalStateException("One-shot gate already attempted");
  for(String stage:List.of("01-density-f4-focused","02-desktop-bounded")){
   String receipt=Files.readString(gate.resolve(stage+"/stage.json"));
   if(!java.util.regex.Pattern.compile("\"exitCode\"\\s*:\\s*0").matcher(receipt).find())throw new IllegalStateException("Offline gate not green");
  }
  save("CHECKING_EXISTING_OS_CREDENTIAL");
  DesktopFishAudioLibrary library=null;
  try{
   // Real LOCALAPPDATA is intentionally used only by the existing strict read-only SecretStore.load.
   // The selected synthetic data root is supplied solely for credential-root separation validation.
   var credentials=new DesktopFishAudioCredentials(WindowsSecretStore.Companion.create(gate.resolve("live-no-user-data")));
   if(credentials.load()==null){save("NOT_TESTED_NO_EXISTING_CREDENTIAL");return;}
   AtomicInteger status=new AtomicInteger(),perCall=new AtomicInteger();
   var client=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
    .connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS)
    .addNetworkInterceptor(chain->{
      var r=chain.request();
      if(!r.method().equals("GET")||!r.url().isHttps()||!r.url().host().equals("api.fish.audio")||!r.url().encodedPath().equals("/model")||perCall.incrementAndGet()!=1||forwards.get()>=3)throw new java.io.IOException("Live gate closed");
      forwards.incrementAndGet();try{save("REQUEST_IN_FLIGHT");}catch(Exception error){throw new java.io.IOException("Receipt unavailable");}
      Response response=chain.proceed(r);status.set(response.code());return response;
    }).build();
   library=new DesktopFishAudioLibrary(credentials,client,"https://api.fish.audio");
   long previousEnd=0;
   for(int i=0;i<3;i++){
    if(previousEnd!=0){long gap=TimeUnit.SECONDS.toNanos(30)-(System.nanoTime()-previousEnd);if(gap>0)TimeUnit.NANOSECONDS.sleep(gap);}
    attempts++;status.set(0);perCall.set(0);save("ATTEMPT_STARTED");
    var query=new FishAudioModelQuery(i==0?FishAudioLibrary.MINE:FishAudioLibrary.COMMUNITY,10,i==2?2:1,"",List.of(),List.of(),FishAudioModelSort.RECOMMENDED);
    try{
     DesktopFishAudioLibrary current=library;
     FishAudioModelPage page=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->current.listModels(query,continuation));
     if(!rows.isEmpty())rows+=",";
     rows+="{\"interface\":\"GET /model\",\"httpStatus\":"+status.get()+",\"library\":\""+(i==0?"MINE":"COMMUNITY")+"\",\"page\":"+(i==2?2:1)+",\"typedDecode\":true,\"itemCount\":"+page.getItems().size()+"}";
     save("PARTIAL_COMPLETE");
    }catch(Exception error){
     if(!rows.isEmpty())rows+=",";
     rows+="{\"interface\":\"GET /model\",\"httpStatus\":"+status.get()+",\"typedDecode\":false}";
     save("STOPPED_ON_FIRST_ERROR_NO_RETRY");return;
    }finally{previousEnd=System.nanoTime();}
   }
   save("COMPLETE_GET_ONLY");
  }catch(Exception unavailable){save("STOPPED_CREDENTIAL_OR_ENVIRONMENT_UNAVAILABLE");}
  finally{if(library!=null){DesktopFishAudioLibrary current=library;BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->current.closeAndDrain(continuation));}}
 }
}
