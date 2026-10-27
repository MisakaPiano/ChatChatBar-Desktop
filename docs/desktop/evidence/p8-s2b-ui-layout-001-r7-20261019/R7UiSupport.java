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

public final class R7UiSupport {
    static final String FAKE="r7-synthetic-no-valid-account";
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
        if(!root.isAbsolute()||!root.toString().contains("p8-s2b-ui-layout-001-r7-20261019"))throw new IOException("Synthetic root required");
        if(Files.exists(root))throw new IOException("Fresh synthetic root required");
        Files.createDirectories(root);
        write(root.getParent().resolve("ChatChatBarDesktop.bootstrap.json"),"{\"formatVersion\":1,\"mode\":\"DEFAULT\"}");
        var resolution=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
        var result=DesktopDataRootOwnership.Companion.acquire(resolution);
        if(!(result instanceof DesktopDataRootOwnershipResult.Acquired))throw new IOException("Synthetic root not owned");
        try(var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership()){
            {Path file=root.resolve("entities/character_cards/r7-structured.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-structured\",\"name\":\"R7 结构化测试卡\",\"editMode\":\"STRUCTURED\",\"characters\":[{\"id\":\"r7-structured-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"r7-structured-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"R7 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/character_cards/r7-freeform.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-freeform\",\"name\":\"R7 自由文本测试卡\",\"editMode\":\"FREEFORM\",\"characters\":[{\"id\":\"r7-freeform-alice\",\"name\":\"Alice 合成测试\"},{\"id\":\"r7-freeform-bob\",\"name\":\"Bob 合成测试\"}],\"basicSetting\":\"Synthetic offline manual gate only\",\"freeformCharacterText\":\"Alice and Bob are synthetic test characters.\",\"greeting\":\"R7 synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/chat_sessions/r7-session-a.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-session-a\",\"characterCardId\":\"r7-structured\",\"title\":\"R7 会话 A\",\"longTermMemoryEnabled\":false,\"createdAt\":2,\"updatedAt\":2}");}
            {Path file=root.resolve("entities/chat_sessions/r7-session-b.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-session-b\",\"characterCardId\":\"r7-freeform\",\"title\":\"R7 会话 B\",\"longTermMemoryEnabled\":false,\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/model_configs/r7-chat.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-chat\",\"displayName\":\"R7 对话模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":true,\"createdAt\":1}");}
            {Path file=root.resolve("entities/model_configs/r7-aux.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"r7-aux\",\"displayName\":\"R7 辅助文本模型（不发请求）\",\"baseUrl\":\"https://model.invalid/v1\",\"apiKey\":\"\",\"modelName\":\"synthetic-never-call\",\"selectableForChat\":false,\"createdAt\":1}");}
            {Path file=root.resolve("entities/app_settings.json");Files.createDirectories(file.getParent());write(file,"{\"defaultModelId\":\"r7-chat\",\"voiceTagModelId\":\"r7-aux\",\"fishAudioTtsModelId\":\"s2.1-pro-free\",\"momentsEnabled\":false,\"automaticFormatCheckEnabled\":false,\"tutorialVersion\":999}");}
            WindowsSecretStore.Companion.create(root).save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);
        }
        System.out.println("R7_SYNTHETIC_FIXTURE_READY");
    }
    static String headers(InputStream in)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){bytes.write(b);if(bytes.size()>16384)throw new IOException("Header budget");byte[] v=bytes.toByteArray();int n=v.length;if(n>=4&&v[n-4]==13&&v[n-3]==10&&v[n-2]==13&&v[n-1]==10)break;}
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    static void event(Path folder,String record)throws Exception {
        synchronized(R7UiSupport.class){Files.writeString(folder.resolve("events.jsonl"),
            "{\"utc\":\""+java.time.Instant.now()+"\","+record+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    }
    static void mock(Path folder)throws Exception {
        if(!folder.toAbsolutePath().toString().contains("p8-s2b-ui-layout-001-r7-20261019"))throw new IOException("Synthetic service scope required");
        Files.createDirectory(folder);
        byte[] sample=Files.readAllBytes(folder.getParent().resolve("live-official-sample.mp3"));
        byte[] tts=Files.readAllBytes(folder.getParent().resolve("synthetic-tts-long.mp3"));
        if(sample.length>16*1024*1024||tts.length>16*1024*1024)throw new IOException("Fixture size budget");
        String primary="/task/11111111111111111111111111111111.mp3";
        String other="/task/22222222222222222222222222222222.mp3";
        String waiting="/task/33333333333333333333333333333333.mp3";
        Path p12=folder.resolve("ephemeral-private.p12"),trust=folder.resolve("public-trust.jks");
        char[] password=UUID.randomUUID().toString().toCharArray();
        ServerSocket server=null;Thread acceptor=null;
        Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        ExecutorService workers=Executors.newFixedThreadPool(4);
        Semaphore permits=new Semaphore(4);
        try {
            ProcessBuilder tool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),
                "-genkeypair","-alias","r7","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=api.fish.audio",
                "-ext","SAN=dns:api.fish.audio,dns:platform.r2.fish.audio,dns:localhost","-storetype","PKCS12","-keystore",p12.toString(),
                "-storepass:env","R7_CERT_PASSWORD","-keypass:env","R7_CERT_PASSWORD","-noprompt");
            tool.environment().put("R7_CERT_PASSWORD",new String(password));tool.redirectErrorStream(true);
            Process generated=tool.start();generated.getInputStream().readAllBytes();
            if(generated.waitFor()!=0)throw new IOException("Ephemeral TLS generation failed");
            KeyStore identity=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(p12)){identity.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,password);
            KeyStore publicOnly=KeyStore.getInstance("JKS");publicOnly.load(null,null);publicOnly.setCertificateEntry("r7",identity.getCertificate("r7"));
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
                                byte[] body=socket.getInputStream().readNBytes(length);
                                if(body.length!=length)throw new IOException("Incomplete synthetic request");
                                String bodyText=new String(body,StandardCharsets.UTF_8); // Synthetic only, never logged.
                                String reference=matchReference(bodyText);
                                boolean post=isApiHost&&first.startsWith("POST /v1/tts ");
                                boolean catalog=isApiHost&&first.startsWith("GET /model?");
                                boolean primarySample=isSampleHost&&first.startsWith("GET "+primary+" ");
                                boolean otherSample=isSampleHost&&first.startsWith("GET "+other+" ");
                                boolean waitSample=isSampleHost&&first.startsWith("GET "+waiting+" ");
                                Map<String,List<String>> query=catalog?query(first):Map.of();
                                int page=number(query,"page_number",1),pageSize=number(query,"page_size",20);
                                String catalogJson=catalog(query,page,pageSize,primary,other,waiting);
                                boolean sampleRoute=primarySample||otherSample||waitSample;
                                boolean hasSecretHeader=request.lines().anyMatch(l->{String low=l.toLowerCase(Locale.ROOT);return low.startsWith("authorization:")||low.startsWith("cookie:")||low.startsWith("proxy-authorization:");});
                                boolean validApi=request.contains("Authorization: Bearer "+FAKE+"\r\n");
                                boolean free=request.contains("model: s2.1-pro-free\r\n");
                                int code=(sampleRoute&&!hasSecretHeader)||(validApi&&(catalog||(post&&free)))?200:403;
                                boolean hang=code==200&&(waitSample||(post&&reference.equals("r7-004"))||
                                    (sampleRoute&&Files.deleteIfExists(folder.resolve("hang-next-sample.flag")))||
                                    (post&&Files.deleteIfExists(folder.resolve("hang-next-tts.flag"))));
                                byte[] payload=code!=200?"{\"error\":\"synthetic rejection\"}".getBytes(StandardCharsets.UTF_8):
                                    primarySample?sample:otherSample||waitSample||post?tts:catalogJson.getBytes(StandardCharsets.UTF_8);
                                String endpoint=sampleRoute?"GET sample":post?"POST /v1/tts":"GET /model";
                                String variant=primarySample?"sample-control-A":otherSample?"synthetic-control-B":waitSample?"waiting-sample":"not-sample";
                                socket.getOutputStream().write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: "+
                                    (sampleRoute||post?"audio/mpeg":"application/json")+"\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
                                event(folder,"\"interface\":\""+endpoint+"\",\"variant\":\""+variant+"\",\"status\":"+code+
                                    ",\"hanging\":"+hang+",\"sampleSecretHeader\":"+(isSampleHost&&hasSecretHeader)+",\"pageNumber\":"+page+",\"pageSize\":"+pageSize+",\"realRequests\":0");
                                if(hang){
                                    socket.getOutputStream().write(payload,0,Math.min(128,payload.length));socket.getOutputStream().flush();
                                    while(socket.getInputStream().read()!=-1){}
                                    event(folder,"\"event\":\"HANG_SOCKET_DRAINED\",\"interface\":\"preview body\"");
                                }else{socket.getOutputStream().write(payload);socket.getOutputStream().flush();}
                                sockets.remove(socket);
                            }
                        }catch(Exception error){
                            try{event(folder,"\"event\":\"CONNECTION_ENDED\",\"errorType\":\""+error.getClass().getSimpleName()+"\"");}catch(Exception ignored){}
                        }finally{sockets.remove(plain);try{plain.close();}catch(Exception ignored){}permits.release();}
                    });
                }catch(Exception ignored){if(!serving.isClosed())System.out.println("R7_SYNTHETIC_ACCEPT_ENDED");}
            },"R7-local-fake-fish");acceptor.start();
            System.out.println("R7_SYNTHETIC_TLS_READY");new BufferedReader(new InputStreamReader(System.in)).readLine();
        }finally{
            Arrays.fill(password,'\0');if(server!=null)server.close();for(Socket socket:sockets)socket.close();
            if(acceptor!=null){acceptor.join(30000);if(acceptor.isAlive())throw new IOException("Synthetic acceptor not drained");}
            workers.shutdown();if(!workers.awaitTermination(30,TimeUnit.SECONDS))throw new IOException("Synthetic handlers not drained");
            Files.deleteIfExists(p12);Files.deleteIfExists(trust);
            write(folder.resolve("cleanup.json"),"{\"privateKeyDeleted\":true,\"trustStoreDeleted\":true,\"serverDrained\":true}");
        }
    }

    static String matchReference(String body) {
        String key="\"reference_id\"";int start=body.indexOf(key);
        if(start<0)return "";int colon=body.indexOf(':',start+key.length());if(colon<0)return "";
        int first=body.indexOf('"',colon+1);if(first<0)return "";int last=body.indexOf('"',first+1);
        return last<0?"":body.substring(first+1,last);
    }
    static Map<String,List<String>> query(String first) {
        String target=first.split(" ")[1];String raw=URI.create("https://fixture.invalid"+target).getRawQuery();
        Map<String,List<String>> out=new HashMap<>();
        if(raw!=null)for(String item:raw.split("&")){
            String[] pair=item.split("=",2);
            String name=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            String value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
            out.computeIfAbsent(name,k->new ArrayList<>()).add(value);
        }
        return out;
    }
    static String value(Map<String,List<String>> q,String key,String fallback){return q.getOrDefault(key,List.of(fallback)).get(0);}
    static int number(Map<String,List<String>> q,String key,int fallback){
        try{return Integer.parseInt(value(q,key,""+fallback));}catch(NumberFormatException e){return fallback;}
    }
    static String catalog(Map<String,List<String>> q,int page,int size,String a,String b,String wait){
        if(size<1||size>100||page<1)return "{\"total\":0,\"items\":[],\"has_more\":false}";
        List<Integer> ids=new ArrayList<>();
        String title=value(q,"title","");boolean absent=q.getOrDefault("tag",List.of()).contains("absent")||q.getOrDefault("language",List.of()).contains("absent");
        for(int i=1;i<=60;i++)if(!absent&&voiceTitle(i).contains(title))ids.add(i);
        int start=(int)Math.min(ids.size(),((long)page-1)*size),end=Math.min(ids.size(),start+size);
        StringJoiner items=new StringJoiner(",");boolean mine=value(q,"self","false").equals("true");
        for(int index=start;index<end;index++){
            int i=ids.get(index);String path=i==3?wait:i%2==1?a:b;
            items.add("{\"_id\":\"r7-"+String.format(Locale.ROOT,"%03d",i)+"\",\"title\":\""+voiceTitle(i)+"\",\"visibility\":\""+(mine?"private":"public")+"\",\"author\":{\"nickname\":\"R7 Synthetic Fixture\"},\"tags\":[\"synthetic\"],\"languages\":[\"zh\"],\"samples\":[{\"text\":\"本机固定回放，不连接真实Fish\",\"audio\":\"https://platform.r2.fish.audio"+path+"\"}]}");
        }
        return "{\"total\":"+ids.size()+",\"has_more\":"+(end<ids.size())+",\"items\":["+items+"]}";
    }
    static String voiceTitle(int id){return switch(id){
        case 1 -> "R7 样本对照 A（本机回放）";
        case 2 -> "R7 样本对照 B（中文合成回放）";
        case 3 -> "R7 等待样本（用于停止下载）";
        case 4 -> "R7 等待生成（用于停止生成）";
        default -> "R7 合成音色 "+String.format(Locale.ROOT,"%02d",id);
    };}
}
