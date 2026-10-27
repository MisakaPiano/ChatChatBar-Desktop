// Offline fixture adapter only. Compiled separately against the unmodified frozen package.
import com.example.chatbar.desktop.*;
import com.example.chatbar.data.local.*;
import com.example.chatbar.data.local.entity.*;
import com.example.chatbar.data.repository.*;
import com.example.chatbar.domain.chat.*;
import com.example.chatbar.domain.voice.*;
import com.example.chatbar.domain.appearance.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import kotlin.Unit;

public final class ScreenshotFixtureSupport {
 static void check(boolean b,String code){if(!b)throw new IllegalStateException(code);}
 public static void main(String[] args)throws Exception {
  try { run(Path.of(args[0]), args[1]); }
  catch(Throwable t){for(Throwable e=t;e!=null;e=e.getCause()){System.err.println("SCREENSHOT_FIXTURE_FAILED type="+e.getClass().getName());for(var frame:e.getStackTrace())System.err.println("AT "+frame);if(e.getMessage()!=null&&e.getMessage().matches("[A-Z_]{1,60}"))System.err.println("CHECK "+e.getMessage());}System.exit(1);}
 }
 static void run(Path scope,String mode)throws Exception {
  check(scope.isAbsolute()&&scope.getFileName().toString().equals("post-p8-screenshot-windows-manual-20261021"),"SCOPE");
  check(!Files.exists(scope.resolve("manual-start-once.lock"))&&!Files.exists(scope.resolve("mock-service")),"NOT_PRELAUNCH");
  Path root=scope.resolve("environments/post-p8-profile/ChatChatBarDesktop");
  var result=DesktopDataRootOwnership.Companion.acquire(new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,scope.resolve("synthetic-bootstrap.json")));
  check(result instanceof DesktopDataRootOwnershipResult.Acquired,"OWNER");
  var owner=((DesktopDataRootOwnershipResult.Acquired)result).getOwnership();
  var gate=new DesktopDataOperationCoordinator();
  try {
   var storage=new JsonFileStorage(root,gate);var chats=new ChatRepository(storage);
   var repository=new VoiceMessageRepository(storage,true);var audio=new DesktopVoiceAudioStore(root);
   var characters=new CharacterRepository(storage);
   List<CharacterCard> cards=P8R3Preflight.<List<CharacterCard>>await(characters::getAll);
   var card=cards.stream().filter(c->c.getId().equals("p8s7-structured")).findFirst().orElseThrow();
   if(mode.equals("seed-audio")) {
    Files.writeString(scope.resolve("voice-fixture-seed-once.lock"),"Fresh offline recording fixtures; no TTS\n",StandardOpenOption.CREATE_NEW);
    for(String messageId:List.of("p8s7-a-assistant","postp8-a-assistant-2","shot-a-voice-only")) {
     ChatMessage message=P8R3Preflight.await(c->chats.getMessage(messageId,"p8s7-session-a",c));check(message!=null,"MESSAGE");
     List<VoiceSegmentAnchor> anchors=P8R3Preflight.await(c->repository.ensureAnchors(message,c));
     var selected=anchors.stream().filter(a->a.getSegment().getKind().name().equals("DIALOGUE")).toList();
     int count=messageId.equals("shot-a-voice-only")?1:selected.size();
     check(count==(messageId.equals("shot-a-voice-only")?1:2),"SEGMENTS");
     for(int i=0;i<count;i++) {
      var person=card.getCharacters().get(i);var segment=selected.isEmpty()?null:selected.get(i);
      String id="shot-"+messageId+"-"+i;
      Path source=scope.resolve(i==0?"live-official-sample.mp3":"synthetic-tts-long.mp3");
      FishAudioStoredAudio stored;
      long sourceBytes=Files.size(source);
      try(InputStream input=Files.newInputStream(source)) {
       stored=P8R3Preflight.await(c->audio.persist(message.getSessionId(),id,input,sourceBytes,n->Unit.INSTANCE,c));
      }
      String sourceText=segment==null?"":segment.getSegment().getSpokenText();
      var voice=new GeneratedVoiceMessage(id,message.getSessionId(),message.getId(),MessageAlternativeVersionPolicy.INSTANCE.activeVersionId(message),
       segment==null?null:segment.getAnchor().getId(),segment==null?0:segment.getAnchor().getSourceOrder(),
       segment==null?VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND:segment.getSegment().getKind().name(),person.getName(),sourceText,null,sourceText,
       person.getId(),person.getName(),person.getFishAudioVoice(),"s2.1-pro-free",stored.getPath(),stored.getDurationMs(),stored.getByteLength(),1,1);
      P8R3Preflight.<Unit>await(c->repository.save(voice,c));P8R3Preflight.await(c->audio.verify(voice.getAudioPath(),c));
     }
    }
   } else check(mode.equals("verify-render"),"MODE");
   P8R3Preflight.<Unit>await(repository::initialize);
   List<ChatSession> sessions=P8R3Preflight.<List<ChatSession>>await(chats::getAllSessions);check(sessions.size()==3,"SESSIONS");
   int messages=0,recordings=0;
   Map<String,List<VoiceMessagePlacement>> placements=new LinkedHashMap<>();
   for(ChatSession session:sessions) {
    List<ChatMessage> timeline=P8R3Preflight.await(c->chats.getMessagesReadOnly(session.getId(),c));messages+=timeline.size();
    for(ChatMessage m:timeline) {
     List<VoiceMessagePlacement> rows=P8R3Preflight.await(c->repository.placementsForMessage(m,c));placements.put(m.getId(),rows);recordings+=rows.size();
     for(var row:rows)P8R3Preflight.await(c->audio.verify(row.getVoice().getAudioPath(),c));
    }
   }
   check(messages==14&&recordings==5,"INVENTORY");
   ChatMessage only=P8R3Preflight.await(c->chats.getMessage("shot-a-voice-only","p8s7-session-a",c));
   check(only.getContent().isEmpty()&&only.getImages().isEmpty()&&placements.get(only.getId()).size()==1,"VOICE_ONLY");
   var ids=placements.get(only.getId()).stream().map(p->p.getVoice().getId()).toList();
   Set<String> selected=new LinkedHashSet<>(RoleplayContentSegmentsKt.roleplayScreenshotBlockIds(only,true,ids));
   check(selected.size()==1&&DesktopChatScreenshotSelectionKt.orderedChatScreenshotMessages(List.of(only),selected,true,Map.of(only.getId(),ids)).equals(List.of(only)),"VOICE_FILTER");
   System.out.println("SCREENSHOT_FIXTURE_REPOSITORIES_PASS sessions=3 messages=14 recordings=5 voiceOnly=1");
   if(mode.equals("verify-render")) {
    Path output=Files.createTempDirectory(scope.resolve("preparation"),"offline-proof-");
    var colors=DesktopThemeKt.desktopSemanticColors(ThemeMode.LIGHT,new ThemeColorHsv(),false,DesktopColorStyle.NEUTRAL);
    var request=new DesktopScreenshotRequest("Only recording fixture",new DesktopPrimaryChatState(),List.of(only),selected,placements,Map.of(),null);
    DesktopScreenshotPng png=P8R3Preflight.await(c->DesktopChatScreenshotRenderer.INSTANCE.render(request,colors,ref->{throw new IllegalStateException("NO_RESOURCE_EXPECTED");},640,c));
    DesktopChatScreenshotRenderer.INSTANCE.save(png,output.resolve("voice-only.png"));
    ChatMessage longMessage=P8R3Preflight.await(c->chats.getMessage("shot-long-assistant","shot-session-long",c));
    var longRequest=new DesktopScreenshotRequest("Long screenshot fixture",new DesktopPrimaryChatState(),List.of(longMessage),new LinkedHashSet<>(RoleplayContentSegmentsKt.roleplayScreenshotBlockIds(longMessage,true,List.of())),Map.of(),Map.of(),null);
    DesktopScreenshotPng longPng=P8R3Preflight.await(c->DesktopChatScreenshotRenderer.INSTANCE.render(longRequest,colors,ref->{throw new IllegalStateException("NO_RESOURCE_EXPECTED");},640,c));
    DesktopChatScreenshotRenderer.INSTANCE.save(longPng,output.resolve("long.png"));
    check(longPng.getHeight()>6000&&longPng.getHeight()<=12500,"LONG_HEIGHT");
    Files.writeString(output.resolve("dimensions.json"),"{\"voiceWidth\":"+png.getWidth()+",\"voiceHeight\":"+png.getHeight()+",\"longWidth\":"+longPng.getWidth()+",\"longHeight\":"+longPng.getHeight()+",\"offscreenOnly\":true}");
    System.out.println("SCREENSHOT_FIXTURE_OFFSCREEN_RENDER_PASS long="+longPng.getWidth()+"x"+longPng.getHeight()+" guiStarts=0 networkCalls=0");
   }
  } finally {P8R3Preflight.<Unit>await(gate::closeAndDrain);owner.close();System.out.println("SCREENSHOT_FIXTURE_OWNER_RELEASED");}
 }
}
