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

public final class F2UiSupport {
    static final String FAKE="f2-synthetic-no-valid-account";
    static void write(Path path,String s)throws Exception{Files.writeString(path,s,StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        switch(args[0]){
            case "seed" -> seed(Path.of(args[1]));
            case "mock" -> mock(Path.of(args[1]));
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
        if(!root.isAbsolute()||!root.toString().contains("p8-s2b-ui-layout-001-r2-20261018"))throw new IOException("Synthetic root required");
        Files.createDirectories(root);
        var resolution=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
        var result=DesktopDataRootOwnership.Companion.acquire(resolution);
        if(!(result instanceof DesktopDataRootOwnershipResult.Acquired))throw new IOException("Synthetic root not owned");
        try(var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership()){
            {Path file=root.resolve("entities/character_cards/f2-structured.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-structured\",\"name\":\"F2 结构化测试卡\",\"editMode\":\"STRUCTURED\",\"characters\":[{\"id\":\"f2-structured-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"f2-structured-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"F2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/character_cards/f2-freeform.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-freeform\",\"name\":\"F2 自由文本测试卡\",\"editMode\":\"FREEFORM\",\"characters\":[{\"id\":\"f2-freeform-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"f2-freeform-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"F2 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/chat_sessions/f2-session-a.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-session-a\",\"characterCardId\":\"f2-structured\",\"title\":\"F2 会话 A\",\"longTermMemoryEnabled\":false,\"createdAt\":2,\"updatedAt\":2}");}
            {Path file=root.resolve("entities/chat_sessions/f2-session-b.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-session-b\",\"characterCardId\":\"f2-freeform\",\"title\":\"F2 会话 B\",\"longTermMemoryEnabled\":false,\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/model_configs/f2-chat.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-chat\",\"displayName\":\"F2 对话模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":true,\"createdAt\":1}");}
            {Path file=root.resolve("entities/model_configs/f2-aux.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"f2-aux\",\"displayName\":\"F2 辅助文本模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":false,\"createdAt\":1}");}
            {Path file=root.resolve("entities/app_settings.json");Files.createDirectories(file.getParent());write(file,"{\"defaultModelId\":\"f2-chat\",\"voiceTagModelId\":\"f2-missing-model\",\"momentsEnabled\":false,\"automaticFormatCheckEnabled\":false,\"tutorialVersion\":999}");}
            WindowsSecretStore.Companion.create(root).save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);
        }
        System.out.println("F2_SYNTHETIC_FIXTURE_READY");
    }
    static String headers(InputStream in)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){bytes.write(b);if(bytes.size()>16384)throw new IOException("Header budget");byte[] v=bytes.toByteArray();int n=v.length;if(n>=4&&v[n-4]==13&&v[n-3]==10&&v[n-2]==13&&v[n-1]==10)break;}
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    static void event(Path folder,String record)throws Exception {
        synchronized(F2UiSupport.class){Files.writeString(folder.resolve("events.jsonl"),
            "{\"utc\":\""+java.time.Instant.now()+"\","+record+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    }
    static void mock(Path folder)throws Exception {
        if(!folder.toAbsolutePath().toString().contains("p8-s2b-ui-layout-001-r2-20261018"))throw new IOException("Synthetic service scope required");
        Files.createDirectory(folder);
        byte[] sample=Files.readAllBytes(folder.getParent().resolve("live-official-sample.mp3"));
        byte[] tts=Files.readAllBytes(folder.getParent().resolve("synthetic-tts-fixture.mp3"));
        if(sample.length>16*1024*1024||tts.length>16*1024*1024)throw new IOException("Fixture size budget");
        String primary="/task/11111111111111111111111111111111.mp3";
        String other="/task/22222222222222222222222222222222.mp3";
        String catalogJson="""
            {"total":2,"has_more":false,"items":[
              {"_id":"f2-official","title":"F2 官方样本（本机回放）","visibility":"public","author":{"nickname":"F2 Fixture"},"samples":[{"text":"匿名下载的官方 MP3，本机隔离回放","audio":"https://platform.r2.fish.audio%s"}]},
              {"_id":"f2-control","title":"F2 合成对照音色（本机 fake）","visibility":"public","author":{"nickname":"F2 Fixture"},"samples":[{"text":"合成对照，不发送真实 TTS","audio":"https://platform.r2.fish.audio%s"}]}
            ]}
            """.formatted(primary,other);
        Path p12=folder.resolve("ephemeral-private.p12"),trust=folder.resolve("public-trust.jks");
        char[] password=UUID.randomUUID().toString().toCharArray();
        ServerSocket server=null;Thread acceptor=null;
        Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        ExecutorService workers=Executors.newFixedThreadPool(4);
        Semaphore permits=new Semaphore(4);
        try {
            ProcessBuilder tool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),
                "-genkeypair","-alias","f2","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=api.fish.audio",
                "-ext","SAN=dns:api.fish.audio,dns:platform.r2.fish.audio,dns:localhost","-storetype","PKCS12","-keystore",p12.toString(),
                "-storepass:env","F2_CERT_PASSWORD","-keypass:env","F2_CERT_PASSWORD","-noprompt");
            tool.environment().put("F2_CERT_PASSWORD",new String(password));tool.redirectErrorStream(true);
            Process generated=tool.start();generated.getInputStream().readAllBytes();
            if(generated.waitFor()!=0)throw new IOException("Ephemeral TLS generation failed");
            KeyStore identity=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(p12)){identity.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,password);
            KeyStore publicOnly=KeyStore.getInstance("JKS");publicOnly.load(null,null);publicOnly.setCertificateEntry("f2",identity.getCertificate("f2"));
            try(OutputStream out=Files.newOutputStream(trust)){publicOnly.store(out,new char[0]);}
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),null,null);Arrays.fill(password,'\0');
            server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());ServerSocket serving=server;
            write(folder.resolve("ready.json"),"{\"port\":"+server.getLocalPort()+",\"loopbackOnly\":true,\"realProviderRequests\":0}");
            acceptor=new Thread(()->{
                while(!serving.isClosed())try {
                    Socket plain=serving.accept();if(!permits.tryAcquire()){plain.close();continue;}sockets.add(plain);
                    workers.submit(()->{
                        try {
                            plain.setSoTimeout(120000);String connect=headers(plain.getInputStream());
                            boolean isSampleHost=connect.startsWith("CONNECT platform.r2.fish.audio:443 HTTP/1.1\r\n");
                            boolean isApiHost=connect.startsWith("CONNECT api.fish.audio:443 HTTP/1.1\r\n");
                            if(!isSampleHost&&!isApiHost)return;
                            plain.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));plain.getOutputStream().flush();
                            try(SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket(plain,isSampleHost?"platform.r2.fish.audio":"api.fish.audio",443,true)){
                                sockets.add(socket);socket.setUseClientMode(false);socket.setSoTimeout(120000);socket.startHandshake();
                                String request=headers(socket.getInputStream()),first=request.lines().findFirst().orElse("");
                                int length=request.lines().filter(l->l.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                                    .map(l->Integer.parseInt(l.substring(l.indexOf(':')+1).trim())).findFirst().orElse(0);
                                if(length<0||length>16384)throw new IOException("Synthetic body budget");
                                socket.getInputStream().readNBytes(length); // Body never logged; fake POST always returns canned audio.
                                boolean post=isApiHost&&first.startsWith("POST /v1/tts ");
                                boolean catalog=isApiHost&&first.startsWith("GET /model?");
                                boolean primarySample=isSampleHost&&first.startsWith("GET "+primary+" ");
                                boolean otherSample=isSampleHost&&first.startsWith("GET "+other+" ");
                                boolean hasSecretHeader=request.lines().anyMatch(l->{String low=l.toLowerCase(Locale.ROOT);return low.startsWith("authorization:")||low.startsWith("cookie:")||low.startsWith("proxy-authorization:");});
                                boolean validApi=request.contains("Authorization: Bearer "+FAKE+"\r\n");
                                boolean free=request.contains("model: s2.1-pro-free\r\n");
                                int code=((primarySample||otherSample)&&!hasSecretHeader)||(validApi&&(catalog||(post&&free)))?200:403;
                                boolean hang=code==200&&(primarySample||otherSample)&&Files.deleteIfExists(folder.resolve("hang-next-sample.flag"));
                                byte[] payload=code!=200?"{\"error\":\"synthetic rejection\"}".getBytes(StandardCharsets.UTF_8):
                                    primarySample?sample:otherSample||post?tts:catalogJson.getBytes(StandardCharsets.UTF_8);
                                String endpoint=primarySample||otherSample?"GET sample":post?"POST /v1/tts":"GET /model";
                                String variant=primarySample?"official-local-replay":otherSample?"synthetic-control":"not-sample";
                                socket.getOutputStream().write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: "+
                                    (primarySample||otherSample||post?"audio/mpeg":"application/json")+"\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                                event(folder,"\"interface\":\""+endpoint+"\",\"variant\":\""+variant+"\",\"status\":"+code+
                                    ",\"hanging\":"+hang+",\"sampleSecretHeader\":"+(isSampleHost&&hasSecretHeader)+",\"realRequests\":0");
                                if(hang){
                                    socket.getOutputStream().write(payload,0,Math.min(128,payload.length));socket.getOutputStream().flush();
                                    while(socket.getInputStream().read()!=-1){}
                                    event(folder,"\"event\":\"HANG_SOCKET_DRAINED\",\"interface\":\"GET sample\"");
                                }else{socket.getOutputStream().write(payload);socket.getOutputStream().flush();}
                                sockets.remove(socket);
                            }
                        }catch(Exception error){
                            try{event(folder,"\"event\":\"CONNECTION_ENDED\",\"errorType\":\""+error.getClass().getSimpleName()+"\"");}catch(Exception ignored){}
                        }finally{sockets.remove(plain);try{plain.close();}catch(Exception ignored){}permits.release();}
                    });
                }catch(Exception ignored){if(!serving.isClosed())System.out.println("F2_SYNTHETIC_ACCEPT_ENDED");}
            },"F2-local-fake-fish");acceptor.start();
            System.out.println("F2_SYNTHETIC_TLS_READY");new BufferedReader(new InputStreamReader(System.in)).readLine();
        }finally{
            Arrays.fill(password,'\0');if(server!=null)server.close();for(Socket socket:sockets)socket.close();
            if(acceptor!=null){acceptor.join(30000);if(acceptor.isAlive())throw new IOException("Synthetic acceptor not drained");}
            workers.shutdown();if(!workers.awaitTermination(30,TimeUnit.SECONDS))throw new IOException("Synthetic handlers not drained");
            Files.deleteIfExists(p12);Files.deleteIfExists(trust);
            write(folder.resolve("cleanup.json"),"{\"privateKeyDeleted\":true,\"trustStoreDeleted\":true,\"serverDrained\":true}");
        }
    }
}
