// Validation-only helper. Not part of the app, production source or distribution.
import com.example.chatbar.desktop.*;
import com.example.chatbar.desktop.security.*;
import com.example.chatbar.domain.voice.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import okhttp3.*;

public final class F1UiSupport {
    static final String FAKE="r5-synthetic-no-valid-account";
    static void write(Path path,String s)throws Exception{Files.writeString(path,s,StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        switch(args[0]){
            case "seed" -> seed(Path.of(args[1]));
            case "mock" -> mock(Path.of(args[1]));
            case "validate" -> {
                Path root=Path.of(args[1]);
                DesktopFishAudioLibrary library=new DesktopFishAudioLibrary(new DesktopFishAudioCredentials(WindowsSecretStore.Companion.create(root)),com.example.chatbar.domain.ProxyAwareClient.INSTANCE.builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),"https://api.fish.audio");
                try{
                    for(int i=0;i<3;i++){
                        FishAudioModelQuery query=new FishAudioModelQuery(i==0?FishAudioLibrary.MINE:FishAudioLibrary.COMMUNITY,10,i==1?2:1,i==2?"error":"",List.of(),List.of(),FishAudioModelSort.RECOMMENDED);
                        boolean rejected=false;
                        try{
                            FishAudioModelPage page=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->library.listModels(query,continuation));
                            if(i==2||page.getItems().size()!=1)throw new IOException("Unexpected synthetic result");
                        }catch(FishAudioApiException error){if(i!=2||!Integer.valueOf(401).equals(error.getStatusCode()))throw error;rejected=true;}
                        if(i==2&&!rejected)throw new IOException("Missing synthetic auth rejection");
                    }
                    System.out.println("LOCAL_TLS_FIXTURE_PERSONAL_COMMUNITY_PAGE2_AUTH_ERROR_VALIDATED");
                }finally{BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->library.closeAndDrain(continuation));}
            }
            case "lock" -> {
                Path root=Path.of(args[1]);
                DesktopDataRootResolution.Resolved resolution=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
                DesktopDataRootOwnershipResult result=DesktopDataRootOwnership.Companion.acquire(resolution);
                if(result instanceof DesktopDataRootOwnershipResult.Acquired acquired){acquired.getOwnership().close();System.out.println("OWNERSHIP_REACQUIRED_AND_RELEASED");}
                else if(result instanceof DesktopDataRootOwnershipResult.AlreadyInUse){System.out.println("OWNERSHIP_HELD");System.exit(75);}
                else {System.out.println("OWNERSHIP_CHECK_FAILED");System.exit(76);}
            }
            default -> throw new IllegalArgumentException("Unknown synthetic validation mode");
        }
    }
    static void seed(Path root)throws Exception{
        if(!root.isAbsolute()||!root.toString().contains("p8-s2a-t2-ui-001-r1a-20261018"))throw new IOException("Synthetic root required");
        Files.createDirectories(root);
        var resolution=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
        var result=DesktopDataRootOwnership.Companion.acquire(resolution);
        if(!(result instanceof DesktopDataRootOwnershipResult.Acquired))throw new IOException("Synthetic root not owned");
        try(var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership()){
            {Path file=root.resolve("entities/character_cards/t2-structured.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-structured\",\"name\":\"F1 结构化测试卡\",\"editMode\":\"STRUCTURED\",\"characters\":[{\"id\":\"t2-structured-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"t2-structured-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"T2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/character_cards/t2-freeform.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-freeform\",\"name\":\"F1 自由文本测试卡\",\"editMode\":\"FREEFORM\",\"characters\":[{\"id\":\"t2-freeform-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"t2-freeform-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"T2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/chat_sessions/t2-session-a.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-session-a\",\"characterCardId\":\"t2-structured\",\"title\":\"T2 会话 A\",\"longTermMemoryEnabled\":false,\"createdAt\":2,\"updatedAt\":2}");}
            {Path file=root.resolve("entities/chat_sessions/t2-session-b.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-session-b\",\"characterCardId\":\"t2-freeform\",\"title\":\"T2 会话 B\",\"longTermMemoryEnabled\":false,\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/model_configs/t2-chat.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-chat\",\"displayName\":\"T2 对话模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":true,\"createdAt\":1}");}
            {Path file=root.resolve("entities/model_configs/t2-aux.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-aux\",\"displayName\":\"T2 辅助文本模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":false,\"createdAt\":1}");}
            {Path file=root.resolve("entities/app_settings.json");Files.createDirectories(file.getParent());write(file,"{\"defaultModelId\":\"t2-chat\",\"voiceTagModelId\":\"t2-missing-model\",\"momentsEnabled\":false,\"automaticFormatCheckEnabled\":false,\"tutorialVersion\":999}");}
            WindowsSecretStore.Companion.create(root).save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);
        }
        System.out.println("T2_SYNTHETIC_FIXTURE_READY");
    }
    static String headers(InputStream in)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){bytes.write(b);if(bytes.size()>16384)throw new IOException("Header budget");byte[] v=bytes.toByteArray();int n=v.length;if(n>=4&&v[n-4]==13&&v[n-3]==10&&v[n-2]==13&&v[n-1]==10)break;}
        return bytes.toString(StandardCharsets.US_ASCII);
    }
    static void mock(Path folder)throws Exception{
        Files.createDirectory(folder);
        byte[] clip=Files.readAllBytes(folder.getParent().resolve("live-synthetic-preview.mp3"));
        Path p12=folder.resolve("ephemeral-private.p12"),trust=folder.resolve("public-trust.jks");
        char[] password=UUID.randomUUID().toString().toCharArray();
        ServerSocket server=null;Thread acceptor=null;
        Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        ExecutorService workers=Executors.newFixedThreadPool(4);
        Semaphore permits=new Semaphore(4);
        try{
            ProcessBuilder tool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias","f1","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=api.fish.audio","-ext","SAN=dns:api.fish.audio,dns:localhost","-storetype","PKCS12","-keystore",p12.toString(),"-storepass:env","F1_CERT_PASSWORD","-keypass:env","F1_CERT_PASSWORD","-noprompt");
            tool.environment().put("F1_CERT_PASSWORD",new String(password));tool.redirectErrorStream(true);
            Process generated=tool.start();generated.getInputStream().readAllBytes();if(generated.waitFor()!=0)throw new IOException("Ephemeral TLS generation failed");
            KeyStore identity=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(p12)){identity.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,password);
            KeyStore publicOnly=KeyStore.getInstance("JKS");publicOnly.load(null,null);publicOnly.setCertificateEntry("f1",identity.getCertificate("f1"));try(OutputStream out=Files.newOutputStream(trust)){publicOnly.store(out,new char[0]);}
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);Arrays.fill(password,'\0');
            server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());ServerSocket serving=server;
            write(folder.resolve("ready.json"),"{\"port\":"+server.getLocalPort()+",\"loopbackOnly\":true,\"realProviderRequests\":0}");
            acceptor=new Thread(()->{
                while(!serving.isClosed())try{
                    Socket plain=serving.accept();
                    if(!permits.tryAcquire()){plain.close();continue;}
                    sockets.add(plain);
                    workers.submit(()->{
                      try{
                        plain.setSoTimeout(120000);
                        String connect=headers(plain.getInputStream());
                        if(!connect.startsWith("CONNECT api.fish.audio:443 HTTP/1.1\r\n"))return;
                        plain.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));plain.getOutputStream().flush();
                        try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,"api.fish.audio",443,true)){
                            sockets.add(socket);socket.setUseClientMode(false);socket.setSoTimeout(120000);socket.startHandshake();
                            String request=headers(socket.getInputStream()),first=request.lines().findFirst().orElse("");
                            int length=request.lines().filter(l->l.toLowerCase(Locale.ROOT).startsWith("content-length:")).map(l->Integer.parseInt(l.substring(l.indexOf(':')+1).trim())).findFirst().orElse(0);
                            if(length<0||length>16384)throw new IOException("Synthetic body budget");
                            String body=new String(socket.getInputStream().readNBytes(length),StandardCharsets.UTF_8);
                            boolean post=first.startsWith("POST /v1/tts "),catalog=first.startsWith("GET /model?");
                            boolean valid=request.contains("Authorization: Bearer "+FAKE+"\r\n");
                            boolean free=request.contains("model: s2.1-pro-free\r\n");
                            int code=valid&&(catalog||(post&&free))?200:403;
                            boolean hanging=code==200&&(first.contains("title=hang")||(post&&body.contains("hang")));
                            byte[] payload;
                            if(post&&code==200)payload=clip;
                            else payload=(code==200?"{\"total\":1,\"has_more\":false,\"items\":[{\"_id\":\"7f92f8afb8ec43bf81429cc1c9199cb1\",\"title\":\"F1 官方示例音色（本机测试）\",\"visibility\":\"public\",\"author\":{\"nickname\":\"F1 Fixture\"},\"samples\":[]}]}":"{\"error\":\"synthetic rejection\"}").getBytes(StandardCharsets.UTF_8);
                            socket.getOutputStream().write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: "+(post?"audio/mpeg":"application/json")+"\r\nContent-Length: "+(hanging?1048576:payload.length)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                            synchronized(F1UiSupport.class){Files.writeString(folder.resolve("events.jsonl"),"{\"interface\":\""+(post?"POST /v1/tts":"GET /model")+"\",\"status\":"+code+",\"hanging\":"+hanging+",\"syntheticAuthorizationMatched\":"+valid+",\"freeModelMatched\":"+free+",\"realRequests\":0}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
                            if(hanging){while(socket.getInputStream().read()!=-1){}; synchronized(F1UiSupport.class){Files.writeString(folder.resolve("events.jsonl"),"{\"event\":\"HANG_SOCKET_DRAINED\",\"interface\":\""+(post?"POST":"GET")+"\"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}}
                            else {socket.getOutputStream().write(payload);socket.getOutputStream().flush();}
                            sockets.remove(socket);
                        }
                      }catch(Exception ignored){System.out.println("F1_SYNTHETIC_CONNECTION_ENDED");}
                      finally{sockets.remove(plain);try{plain.close();}catch(Exception ignored){}permits.release();}
                    });
                }catch(Exception ignored){if(!serving.isClosed())System.out.println("F1_SYNTHETIC_ACCEPT_ENDED");}
            },"F1-local-fake-fish");acceptor.start();
            System.out.println("F1_SYNTHETIC_TLS_READY");new BufferedReader(new InputStreamReader(System.in)).readLine();
        }finally{
            Arrays.fill(password,'\0');if(server!=null)server.close();for(Socket socket:sockets)socket.close();
            if(acceptor!=null){acceptor.join(30000);if(acceptor.isAlive())throw new IOException("Synthetic acceptor not drained");}
            workers.shutdown();if(!workers.awaitTermination(30,TimeUnit.SECONDS))throw new IOException("Synthetic handlers not drained");
            Files.deleteIfExists(p12);Files.deleteIfExists(trust);
            write(folder.resolve("cleanup.json"),"{\"privateKeyDeleted\":true,\"trustStoreDeleted\":true,\"serverDrained\":true}");
        }
    }
}
