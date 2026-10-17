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

public final class R5T2Support {
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
            {Path file=root.resolve("entities/character_cards/t2-structured.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-structured\",\"name\":\"T2 结构化测试卡\",\"editMode\":\"STRUCTURED\",\"characters\":[{\"id\":\"t2-structured-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"t2-structured-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"T2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/character_cards/t2-freeform.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"t2-freeform\",\"name\":\"T2 自由文本测试卡\",\"editMode\":\"FREEFORM\",\"characters\":[{\"id\":\"t2-freeform-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"t2-freeform-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"T2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
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
        Files.createDirectory(folder);Path p12=folder.resolve("ephemeral-private.p12"),trust=folder.resolve("public-trust.jks");
        char[] password=UUID.randomUUID().toString().toCharArray();
        ServerSocket server=null;Thread worker=null;Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        try{
            ProcessBuilder tool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias","r5","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=api.fish.audio","-ext","SAN=dns:api.fish.audio,dns:localhost","-storetype","PKCS12","-keystore",p12.toString(),"-storepass:env","R5_CERT_PASSWORD","-keypass:env","R5_CERT_PASSWORD","-noprompt");
            tool.environment().put("R5_CERT_PASSWORD",new String(password));tool.redirectErrorStream(true);
            Process generated=tool.start();generated.getInputStream().readAllBytes();if(generated.waitFor()!=0)throw new IOException("Ephemeral TLS generation failed");
            KeyStore identity=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(p12)){identity.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,password);
            KeyStore publicOnly=KeyStore.getInstance("JKS");publicOnly.load(null,null);publicOnly.setCertificateEntry("r5",identity.getCertificate("r5"));try(OutputStream out=Files.newOutputStream(trust)){publicOnly.store(out,new char[0]);}
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);Arrays.fill(password,'\0');
            server=new ServerSocket(0,20,InetAddress.getLoopbackAddress());ServerSocket serving=server;
            write(folder.resolve("ready.json"),"{\"port\":"+server.getLocalPort()+",\"loopbackOnly\":true,\"realProviderRequests\":0}");
            worker=new Thread(()->{
                while(!serving.isClosed())try{
                    Socket plain=serving.accept();sockets.add(plain);plain.setSoTimeout(15000);
                    try{
                        String connect=headers(plain.getInputStream());
                        if(!connect.startsWith("CONNECT api.fish.audio:443 HTTP/1.1\r\n")){plain.close();continue;}
                        plain.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));plain.getOutputStream().flush();
                        try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,"api.fish.audio",443,true)){
                            sockets.add(socket);socket.setUseClientMode(false);socket.setSoTimeout(15000);socket.startHandshake();
                            String request=headers(socket.getInputStream());String first=request.lines().findFirst().orElse("");
                            boolean mine=first.contains("self=true"),second=first.contains("page_number=2"),error=first.contains("title=error");
                            boolean valid=first.startsWith("GET /model?")&&(request.contains("Authorization: Bearer "+FAKE+"\r\n")||request.contains("Authorization: Bearer r5-synthetic-replacement\r\n"));
                            int code=!valid||error?401:200;
                            String body=code==200?"{\"total\":21,\"has_more\":"+(!second)+",\"items\":[{\"_id\":\"r5-voice-"+(mine?"mine":"community")+(second?"2":"1")+"\",\"title\":\"T2 Synthetic Voice "+(second?"2":"1")+"\",\"visibility\":\""+(mine?"private":"public")+"\",\"author\":{\"nickname\":\"R5 Fixture\"},\"tags\":[\"synthetic\"],\"languages\":[\"zh\"],\"samples\":[{\"text\":\"Synthetic example only\",\"audio_url\":\"https://sample.invalid/not-fetched.mp3\"}]}]}":"{\"error\":\"synthetic\"}";
                            boolean empty=first.contains("title=empty")||first.contains("tag=absent")||first.contains("language=absent");
                            if(code==200&&empty)body="{\"total\":0,\"has_more\":false,\"items\":[]}";
                            byte[] payload=body.getBytes(StandardCharsets.UTF_8);
                            socket.getOutputStream().write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: application/json\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
                            synchronized(R5T2Support.class){Files.writeString(folder.resolve("events.jsonl"),"{\"interface\":\"GET /model\",\"status\":"+code+",\"mine\":"+mine+",\"page\":"+(second?2:1)+",\"tlsHandshake\":true,\"syntheticAuthorizationMatched\":"+valid+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
                            sockets.remove(socket);
                        }
                    }finally{sockets.remove(plain);plain.close();}
                }catch(Exception ignored){if(!serving.isClosed())System.out.println("SYNTHETIC_CONNECTION_ENDED");}
            },"R5-local-fake-fish");worker.start();
            System.out.println("SYNTHETIC_TLS_PROXY_READY");new BufferedReader(new InputStreamReader(System.in)).readLine();
        }finally{
            Arrays.fill(password,'\0');if(server!=null)server.close();for(Socket socket:sockets)socket.close();if(worker!=null){worker.join(30000);if(worker.isAlive())throw new IOException("Synthetic server not drained");}
            Files.deleteIfExists(p12);Files.deleteIfExists(trust);
            write(folder.resolve("cleanup.json"),"{\"privateKeyDeleted\":true,\"trustStoreDeleted\":true,\"serverDrained\":true}");
        }
    }
}
