// Offline validation-only runner against frozen distribution classes. No UI/network runtime.
import com.example.chatbar.desktop.*;
import com.example.chatbar.desktop.security.*;
import com.example.chatbar.data.local.*;
import com.example.chatbar.data.local.entity.*;
import com.example.chatbar.data.repository.*;
import com.example.chatbar.domain.model.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import kotlin.Unit;
import kotlin.coroutines.*;
import kotlinx.coroutines.BuildersKt;

public final class P8R3Preflight {
    interface SuspendCall<T> { Object call(Continuation<? super T> c); }
    static <T> T await(SuspendCall<T> call)throws Exception {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,c)->call.call(c));
    }
    static void require(boolean condition,String code) {
        if(!condition)throw new IllegalStateException(code);
    }
    static void pass(String name) { System.out.println("P8_R3_CHECK "+name+" PASS"); }
    public static void main(String[] args)throws Exception {
        try { validate(Path.of(args[0])); }
        catch(Throwable failure) {
            System.err.println("P8_R3_OFFLINE_FAILED type="+failure.getClass().getName());
            System.exit(1); // Do not print models, secrets, exception messages or nested payloads.
        }
    }
    static void validate(Path root)throws Exception {
        require(root.isAbsolute()&&root.toString().contains("windows-final-gate-r3-20261020"),"WRONG_FIXTURE_SCOPE");
        var resolved=new DesktopDataRootResolution.Resolved(root,DesktopDataRootProvenance.CLI_OVERRIDE,root.getParent().resolve("synthetic-bootstrap.json"));
        var acquired=DesktopDataRootOwnership.Companion.acquire(resolved);
        require(acquired instanceof DesktopDataRootOwnershipResult.Acquired,"ROOT_NOT_OWNED");
        var gate=new DesktopDataOperationCoordinator();
        var ownership=((DesktopDataRootOwnershipResult.Acquired)acquired).getOwnership();
        try {
            DesktopSecretStore secrets=WindowsSecretStore.Companion.create(root);
            DesktopSecretStore reopened=WindowsSecretStore.Companion.create(root);
            Set<String> unique=new HashSet<>();
            String fish=secrets.load(DesktopCredentialKey.FishAudioApiKey.INSTANCE);
            require(fish!=null&&fish.startsWith("synthetic-invalid-"),"FISH_SCOPE_NOT_CONFIGURED");
            unique.add(fish);
            for(String id:List.of("p8s7-chat","p8s7-aux")) {
                String value=secrets.load(new DesktopCredentialKey.ModelApiKey(id));
                require(value!=null&&value.startsWith("synthetic-invalid-")&&unique.add(value),"MODEL_SCOPE_COLLISION");
                require(value.equals(reopened.load(new DesktopCredentialKey.ModelApiKey(id))),"PROTECTED_ROUNDTRIP_FAILED");
            }
            require(reopened.load(new DesktopCredentialKey.ModelApiKey("p8s7-nonexistent"))==null,"UNKNOWN_SCOPE_NOT_EMPTY");
            require(unique.size()==3,"TYPED_KEY_ISOLATION_FAILED");pass("protected_store_distinct_typed_scopes_and_reopen");
            Map<Path,byte[]> before=new HashMap<>();
            for(String id:List.of("p8s7-chat","p8s7-aux")) {
                Path file=root.resolve("entities/model_configs/"+WindowsSafeModelStorageKeyPolicy.INSTANCE.storageKey(id)+".json");
                before.put(file,Files.readAllBytes(file));
            }
            var storage=new JsonFileStorage(root,gate);
            var policy=new DesktopModelCredentialPersistencePolicy(secrets);
            var models=new ModelRepository(storage,WindowsSafeModelStorageKeyPolicy.INSTANCE,policy,new DesktopEmbeddingCredentialPersistencePolicy(secrets));
            P8R3Preflight.<Unit>await(models::initialize);
            List<ModelConfig> all=P8R3Preflight.<List<ModelConfig>>await(models::getAllModels);
            require(all.size()==2&&new HashSet<>(all.stream().map(ModelConfig::getId).toList()).equals(Set.of("p8s7-chat","p8s7-aux")),"MODEL_LIST_INCOMPLETE");
            for(ModelConfig model:all) {
                require(model.getApiKey().equals(secrets.load(new DesktopCredentialKey.ModelApiKey(model.getId()))),"HYDRATION_MISMATCH");
                ModelConfig exact=await(c->models.getModel(model.getId(),c));
                require(exact!=null&&exact.getApiKey().equals(model.getApiKey()),"EXACT_ID_READ_FAILED");
            }
            pass("real_policy_repository_initialize_list_and_exact_id_hydrate");
            boolean refused=false;
            try { P8R3Preflight.<ModelConfig>await(c->policy.hydrate(all.get(0),c)); }
            catch(UnsafeDesktopPlaintextCredentialException expected){refused=true;}
            require(refused,"PLAINTEXT_GUARD_WEAKENED");pass("nonblank_persisted_form_still_rejected");
            var modelsReopened=new ModelRepository(new JsonFileStorage(root,gate),WindowsSafeModelStorageKeyPolicy.INSTANCE,
                new DesktopModelCredentialPersistencePolicy(reopened),new DesktopEmbeddingCredentialPersistencePolicy(reopened));
            List<ModelConfig> rehydrated=P8R3Preflight.<List<ModelConfig>>await(modelsReopened::getAllModels);
            require(rehydrated.size()==2&&rehydrated.stream().allMatch(m->!m.getApiKey().isBlank()),"REOPEN_HYDRATION_FAILED");
            pass("new_repository_and_secret_store_rehydrate");
            var settings=new SettingsRepository(storage,new DesktopSettingsCredentialPersistencePolicy(secrets));
            PresetModelCatalogSource noFallback=new PresetModelCatalogSource(){
                public PresetModelCatalog getCatalog(){throw new AssertionError("UNEXPECTED_PRESET_FALLBACK");}
                public Integer getModelCatalogVersion(){return null;}
            };
            var resolver=new EffectiveModelResolver(models,settings,noFallback);
            List<ModelConfig> studio=await(resolver::availableChatModels);
            require(studio.size()==1&&studio.get(0).getId().equals("p8s7-chat")&&!studio.get(0).getApiKey().isBlank(),"STUDIO_MODEL_READ_FAILED");
            List<ModelConfig> auxiliary=await(resolver::availableAuxiliaryTextModels);
            require(auxiliary.size()==2,"AUXILIARY_MODELS_INCOMPLETE");
            pass("studio_availableChatModels_dependency_path_and_auxiliary_resolution_no_fallback");
            var characters=new CharacterRepository(storage);
            List<CharacterCard> cards=P8R3Preflight.<List<CharacterCard>>await(characters::getAll);
            require(cards.size()==2,"CHARACTER_FIXTURE_INCOMPLETE");
            require(new HashSet<>(cards.stream().map(c->c.getEditMode().name()).toList()).equals(Set.of("STRUCTURED","FREEFORM")),"CHARACTER_MODES_INCOMPLETE");
            for(CharacterCard card:cards) {
                require(card.getCharacters().size()==2,"CHARACTER_COUNT_FAILED");
                require(card.getCharacters().stream().allMatch(c->c.getFishAudioVoice()!=null&&!c.getFishAudioVoice().getReferenceId().isBlank()),"BINDING_MISSING");
            }
            pass("actual_character_repository_two_modes_and_four_bindings");
            var chats=new ChatRepository(storage);
            List<ChatSession> sessions=P8R3Preflight.<List<ChatSession>>await(chats::getAllSessions);
            require(sessions.size()==2,"SESSION_FIXTURE_INCOMPLETE");
            int messageCount=0;
            for(ChatSession session:sessions) {
                require(cards.stream().anyMatch(c->c.getId().equals(session.getCharacterCardId())),"SESSION_CARD_REFERENCE_FAILED");
                require(P8R3Preflight.<ModelConfig>await(c->models.getModel(session.getModelId(),c))!=null,"SESSION_MODEL_REFERENCE_FAILED");
                List<ChatMessage> messages=await(c->chats.getMessagesReadOnly(session.getId(),c));
                require(messages.size()==2&&messages.stream().anyMatch(m->m.getRole()==MessageRole.ASSISTANT),"MESSAGE_FIXTURE_INCOMPLETE");
                messageCount+=messages.size();
            }
            require(messageCount==4,"MESSAGE_COUNT_FAILED");pass("actual_chat_repository_sessions_messages_and_references");
            for(var item:before.entrySet())require(Arrays.equals(item.getValue(),Files.readAllBytes(item.getKey())),"MODEL_JSON_MUTATED");
            pass("model_json_bytes_unchanged_after_hydrate_and_negative_guard");
            System.out.println("P8_R3_OFFLINE_PREFLIGHT_PASS models=2 studioModels=1 auxiliaryModels=2 cards=2 bindings=4 sessions=2 messages=4 networkCalls=0 guiStarts=0");
        } finally {
            P8R3Preflight.<Unit>await(gate::closeAndDrain);
            ownership.close();
            System.out.println("P8_R3_FIXTURE_DRAINED_OWNER_RELEASED");
        }
    }
}
