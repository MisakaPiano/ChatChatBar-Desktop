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

public final class P8S7Support {
    static final String FAKE="p8s7-synthetic-no-valid-account";
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
            case "selfcheck" -> {
                String input="目标语言：English\n待处理段落 JSON：\n[{\"id\":\"synthetic-target\",\"text\":\"hello\",\"character_name\":\"Alice\"}]\n";
                var content=kotlinx.serialization.json.JsonElementKt.JsonPrimitive(input);
                var message=new kotlinx.serialization.json.JsonObject(Map.of("role",kotlinx.serialization.json.JsonElementKt.JsonPrimitive("user"),"content",content));
                String body=new kotlinx.serialization.json.JsonObject(Map.of("messages",new kotlinx.serialization.json.JsonArray(List.of(message)))).toString();
                String result=new String(auxiliaryPayload(body),StandardCharsets.UTF_8);
                if(!result.contains("synthetic-target")||!result.contains("translatedText")||!result.contains("[DONE]"))throw new IOException("Synthetic auxiliary selfcheck failed");
                System.out.println("P8_S7_OFFLINE_HELPER_SELFCHECK_PASS_NO_NETWORK");
            }
            default -> throw new IllegalArgumentException("Unknown synthetic validation mode");
        }
    }
    static void seed(Path root)throws Exception{
        if(!root.isAbsolute()||!root.toString().contains("p8-remaining-20261019"))throw new IOException("Synthetic root required");
        if(Files.exists(root))throw new IOException("Fresh synthetic root required");
        Files.createDirectories(root);
        write(root.getParent().resolve("ChatChatBarDesktop.bootstrap.json"),"{\"formatVersion\":1,\"mode\":\"DEFAULT\"}");
        var resolution=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
        var result=DesktopDataRootOwnership.Companion.acquire(resolution);
        if(!(result instanceof DesktopDataRootOwnershipResult.Acquired))throw new IOException("Synthetic root not owned");
        try(var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership()){
            {Path file=root.resolve("entities/character_cards/p8s7-structured.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-structured\",\"name\":\"P8 S7 结构化测试卡\",\"editMode\":\"STRUCTURED\",\"characters\":[{\"id\":\"p8s7-structured-alice\",\"name\":\"Alice 合成测试\",\"fishAudioVoice\":{\"referenceId\":\"p8s7-001\",\"title\":\"P8 S7 合成声线 Alice\"}},{\"id\":\"p8s7-structured-bob\",\"name\":\"Bob 合成测试\",\"fishAudioVoice\":{\"referenceId\":\"p8s7-002\",\"title\":\"P8 S7 合成声线 Bob\"}}],\"basicSetting\":\"Synthetic offline only\",\"freeformCharacterText\":\"Alice and Bob synthetic.\",\"greeting\":\"Synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/character_cards/p8s7-freeform.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-freeform\",\"name\":\"P8 S7 自由文本测试卡\",\"editMode\":\"FREEFORM\",\"characters\":[{\"id\":\"p8s7-freeform-alice\",\"name\":\"Alice 合成测试\",\"fishAudioVoice\":{\"referenceId\":\"p8s7-001\",\"title\":\"P8 S7 合成声线 Alice\"}},{\"id\":\"p8s7-freeform-bob\",\"name\":\"Bob 合成测试\",\"fishAudioVoice\":{\"referenceId\":\"p8s7-002\",\"title\":\"P8 S7 合成声线 Bob\"}}],\"basicSetting\":\"Synthetic offline only\",\"freeformCharacterText\":\"Alice and Bob synthetic.\",\"greeting\":\"Synthetic greeting\",\"createdAt\":1,\"updatedAt\":1}");}
            {Path file=root.resolve("entities/chat_sessions/p8s7-session-a.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-session-a\",\"characterCardId\":\"p8s7-structured\",\"title\":\"P8 S7 合成会话 A\",\"modelId\":\"p8s7-chat\",\"longTermMemoryEnabled\":false,\"createdAt\":2,\"updatedAt\":2}");}
            {Path file=root.resolve("entities/chat_messages/p8s7-session-a_p8s7-a-user.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-a-user\",\"sessionId\":\"p8s7-session-a\",\"role\":\"USER\",\"content\":\"请两位合成角色各说一句测试。\",\"createdAt\":1000000,\"updatedAt\":1000000,\"orderKey\":1000000}");}
            {Path file=root.resolve("entities/chat_messages/p8s7-session-a_p8s7-a-assistant.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-a-assistant\",\"sessionId\":\"p8s7-session-a\",\"role\":\"ASSISTANT\",\"content\":\"柔和的灯光。\\n<n=\\\"Alice 合成测试\\\"/>[这是第一句合成语音测试。](平静)\\n<n=\\\"Bob 合成测试\\\"/>[这是第二句合成语音测试。](微笑)\",\"createdAt\":2000000,\"updatedAt\":2000000,\"orderKey\":2000000,\"alternatives\":[\"柔和的灯光。\\n<n=\\\"Alice 合成测试\\\"/>[这是第一句合成语音测试。](平静)\\n<n=\\\"Bob 合成测试\\\"/>[这是第二句合成语音测试。](微笑)\",\"<n=\\\"Alice 合成测试\\\"/>[这是替代回复的测试。]()\"],\"alternativeVersionIds\":[\"p8s7-version-1\",\"p8s7-version-2\"],\"currentAlternativeVersionId\":\"p8s7-version-1\"}");}
            {Path file=root.resolve("entities/chat_sessions/p8s7-session-b.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-session-b\",\"characterCardId\":\"p8s7-freeform\",\"title\":\"P8 S7 合成会话 B\",\"modelId\":\"p8s7-chat\",\"longTermMemoryEnabled\":false,\"createdAt\":1,\"updatedAt\":2}");}
            {Path file=root.resolve("entities/chat_messages/p8s7-session-b_p8s7-b-user.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-b-user\",\"sessionId\":\"p8s7-session-b\",\"role\":\"USER\",\"content\":\"请两位合成角色各说一句测试。\",\"createdAt\":1000000,\"updatedAt\":1000000,\"orderKey\":1000000}");}
            {Path file=root.resolve("entities/chat_messages/p8s7-session-b_p8s7-b-assistant.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-b-assistant\",\"sessionId\":\"p8s7-session-b\",\"role\":\"ASSISTANT\",\"content\":\"柔和的灯光。\\n<n=\\\"Alice 合成测试\\\"/>[这是第一句合成语音测试。](平静)\\n<n=\\\"Bob 合成测试\\\"/>[这是第二句合成语音测试。](微笑)\",\"createdAt\":2000000,\"updatedAt\":2000000,\"orderKey\":2000000,\"alternatives\":[\"柔和的灯光。\\n<n=\\\"Alice 合成测试\\\"/>[这是第一句合成语音测试。](平静)\\n<n=\\\"Bob 合成测试\\\"/>[这是第二句合成语音测试。](微笑)\",\"<n=\\\"Alice 合成测试\\\"/>[这是替代回复的测试。]()\"],\"alternativeVersionIds\":[\"p8s7-version-1\",\"p8s7-version-2\"],\"currentAlternativeVersionId\":\"p8s7-version-1\"}");}
            {Path file=root.resolve("entities/model_configs/p8s7-chat.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-chat\",\"displayName\":\"P8 S7 本机合成chat模型\",\"baseUrl\":\"https://api.fish.audio/mock-llm\",\"apiKey\":\"p8s7-synthetic-no-valid-account\",\"modelName\":\"p8s7-mock-text\",\"selectableForChat\":true,\"createdAt\":1}");}
            {Path file=root.resolve("entities/model_configs/p8s7-aux.json");Files.createDirectories(file.getParent());write(file,"{\"id\":\"p8s7-aux\",\"displayName\":\"P8 S7 本机合成aux模型\",\"baseUrl\":\"https://api.fish.audio/mock-llm\",\"apiKey\":\"p8s7-synthetic-no-valid-account\",\"modelName\":\"p8s7-mock-text\",\"selectableForChat\":false,\"createdAt\":1}");}
            {Path file=root.resolve("entities/app_settings.json");Files.createDirectories(file.getParent());write(file,"{\"defaultModelId\":\"p8s7-chat\",\"voiceTagModelId\":\"p8s7-aux\",\"fishAudioTtsModelId\":\"s2.1-pro-free\",\"audiobookModeEnabled\":true,\"momentsEnabled\":false,\"automaticFormatCheckEnabled\":false,\"tutorialVersion\":999}");}
            WindowsSecretStore.Companion.create(root).save(DesktopCredentialKey.FishAudioApiKey.INSTANCE,FAKE);
        }
        System.out.println("P8 S7_SYNTHETIC_FIXTURE_READY");
    }
    static String headers(InputStream in)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){bytes.write(b);if(bytes.size()>16384)throw new IOException("Header budget");byte[] v=bytes.toByteArray();int n=v.length;if(n>=4&&v[n-4]==13&&v[n-3]==10&&v[n-2]==13&&v[n-1]==10)break;}
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    static void event(Path folder,String record)throws Exception {
        synchronized(P8S7Support.class){Files.writeString(folder.resolve("events.jsonl"),
            "{\"utc\":\""+java.time.Instant.now()+"\","+record+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    }
    static void mock(Path folder)throws Exception {
        if(!folder.toAbsolutePath().toString().contains("p8-remaining-20261019"))throw new IOException("Synthetic service scope required");
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
                "-genkeypair","-alias","p8s7","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=api.fish.audio",
                "-ext","SAN=dns:api.fish.audio,dns:platform.r2.fish.audio,dns:localhost","-storetype","PKCS12","-keystore",p12.toString(),
                "-storepass:env","P8S7_CERT_PASSWORD","-keypass:env","P8S7_CERT_PASSWORD","-noprompt");
            tool.environment().put("P8S7_CERT_PASSWORD",new String(password));tool.redirectErrorStream(true);
            Process generated=tool.start();generated.getInputStream().readAllBytes();
            if(generated.waitFor()!=0)throw new IOException("Ephemeral TLS generation failed");
            KeyStore identity=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(p12)){identity.load(in,password);}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(identity,password);
            KeyStore publicOnly=KeyStore.getInstance("JKS");publicOnly.load(null,null);publicOnly.setCertificateEntry("p8s7",identity.getCertificate("p8s7"));
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
                                if(length<0||length>131072)throw new IOException("Synthetic body budget");
                                byte[] body=socket.getInputStream().readNBytes(length);
                                if(body.length!=length)throw new IOException("Incomplete synthetic request");
                                String bodyText=new String(body,StandardCharsets.UTF_8); // Synthetic only, never logged.
                                String reference=matchReference(bodyText);
                                boolean post=isApiHost&&first.startsWith("POST /v1/tts ");
                                boolean auxiliary=isApiHost&&first.startsWith("POST /mock-llm/chat/completions ")&&bodyText.contains("p8s7-mock-text");
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
                                int code=(sampleRoute&&!hasSecretHeader)||(validApi&&(catalog||auxiliary||(post&&free)))?200:403;
                                boolean hang=code==200&&(waitSample||(post&&reference.equals("p8s7-004"))||
                                    (sampleRoute&&Files.deleteIfExists(folder.resolve("hang-next-sample.flag")))||
                                    (post&&Files.deleteIfExists(folder.resolve("hang-next-tts.flag"))));
                                byte[] payload=code!=200?"{\"error\":\"synthetic rejection\"}".getBytes(StandardCharsets.UTF_8):
                                    auxiliary?auxiliaryPayload(bodyText):primarySample?sample:otherSample||waitSample?tts:post?(reference.equals("p8s7-002")?sample:tts):catalogJson.getBytes(StandardCharsets.UTF_8);
                                String endpoint=auxiliary?"POST synthetic auxiliary":sampleRoute?"GET sample":post?"POST /v1/tts":"GET /model";
                                String variant=primarySample?"sample-control-A":otherSample?"synthetic-control-B":waitSample?"waiting-sample":"not-sample";
                                socket.getOutputStream().write(("HTTP/1.1 "+code+" Synthetic\r\nContent-Type: "+
                                    (auxiliary?"text/event-stream":sampleRoute||post?"audio/mpeg":"application/json")+"\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
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
                }catch(Exception ignored){if(!serving.isClosed())System.out.println("P8 S7_SYNTHETIC_ACCEPT_ENDED");}
            },"P8 S7-local-fake-fish");acceptor.start();
            System.out.println("P8 S7_SYNTHETIC_TLS_READY");new BufferedReader(new InputStreamReader(System.in)).readLine();
        }finally{
            Arrays.fill(password,'\0');if(server!=null)server.close();for(Socket socket:sockets)socket.close();
            if(acceptor!=null){acceptor.join(30000);if(acceptor.isAlive())throw new IOException("Synthetic acceptor not drained");}
            workers.shutdown();if(!workers.awaitTermination(30,TimeUnit.SECONDS))throw new IOException("Synthetic handlers not drained");
            Files.deleteIfExists(p12);Files.deleteIfExists(trust);
            write(folder.resolve("cleanup.json"),"{\"privateKeyDeleted\":true,\"trustStoreDeleted\":true,\"serverDrained\":true}");
        }
    }

    static byte[] auxiliaryPayload(String body)throws Exception {
        var root=(kotlinx.serialization.json.JsonObject)kotlinx.serialization.json.Json.Default.parseToJsonElement(body);
        var messages=(kotlinx.serialization.json.JsonArray)root.get("messages");
        String input=null;
        for(var element:messages){var message=(kotlinx.serialization.json.JsonObject)element;var content=message.get("content");
            if(content instanceof kotlinx.serialization.json.JsonPrimitive primitive && primitive.getContent().contains("待处理段落 JSON："))input=primitive.getContent();}
        if(input==null)throw new IOException("Synthetic task segments required");
        String raw=input.substring(input.lastIndexOf("待处理段落 JSON：")+"待处理段落 JSON：".length()).trim();
        var segments=(kotlinx.serialization.json.JsonArray)kotlinx.serialization.json.Json.Default.parseToJsonElement(raw);
        boolean translate=input.contains("目标语言：");
        List<kotlinx.serialization.json.JsonElement> replies=new ArrayList<>();
        for(var element:segments){var item=(kotlinx.serialization.json.JsonObject)element;
            String text=((kotlinx.serialization.json.JsonPrimitive)item.get("text")).getContent();
            var reply=new LinkedHashMap<String,kotlinx.serialization.json.JsonElement>();reply.put("id",item.get("id"));
            reply.put(translate?"translatedText":"ttsText",kotlinx.serialization.json.JsonElementKt.JsonPrimitive(translate?"Synthetic translated voice text.":text));
            replies.add(new kotlinx.serialization.json.JsonObject(reply));}
        String result=new kotlinx.serialization.json.JsonObject(Map.of("segments",new kotlinx.serialization.json.JsonArray(replies))).toString();
        var delta=new kotlinx.serialization.json.JsonObject(Map.of("content",kotlinx.serialization.json.JsonElementKt.JsonPrimitive(result)));
        var chunk=new kotlinx.serialization.json.JsonObject(Map.of("choices",new kotlinx.serialization.json.JsonArray(List.of(new kotlinx.serialization.json.JsonObject(Map.of("delta",delta))))));
        return ("data: "+chunk+"\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
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
            items.add("{\"_id\":\"p8s7-"+String.format(Locale.ROOT,"%03d",i)+"\",\"title\":\""+voiceTitle(i)+"\",\"visibility\":\""+(mine?"private":"public")+"\",\"author\":{\"nickname\":\"P8 S7 Synthetic Fixture\"},\"tags\":[\"synthetic\"],\"languages\":[\"zh\"],\"samples\":[{\"text\":\"本机固定回放，不连接真实Fish\",\"audio\":\"https://platform.r2.fish.audio"+path+"\"}]}");
        }
        return "{\"total\":"+ids.size()+",\"has_more\":"+(end<ids.size())+",\"items\":["+items+"]}";
    }
    static String voiceTitle(int id){return switch(id){
        case 1 -> "P8 S7 样本对照 A（本机回放）";
        case 2 -> "P8 S7 样本对照 B（中文合成回放）";
        case 3 -> "P8 S7 等待样本（用于停止下载）";
        case 4 -> "P8 S7 等待生成（用于停止生成）";
        default -> "P8 S7 合成音色 "+String.format(Locale.ROOT,"%02d",id);
    };}
}
