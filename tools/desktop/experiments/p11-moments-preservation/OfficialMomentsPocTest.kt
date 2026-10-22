package com.example.chatbar.domain.backup

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.moment.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Isolated, synthetic instrumentation only; compile against exact official 1198a13c. */
class OfficialMomentsPocTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val out get() = File(ctx.getExternalFilesDir(null), "p11-moments-poc").also { it.mkdirs() }
    private fun guard() {
        check(android.os.Build.MODEL.contains("sdk") &&
            android.os.Build.HARDWARE in setOf("ranchu","goldfish")) { "ISOLATED_EMULATOR_REQUIRED" }
    }
    private fun engine() = BackupArchive({}, { _, _, _ -> }, { error("EXTERNAL_FORBIDDEN") })
    private fun root(name: String) = File(ctx.cacheDir, "moments-poc-$name-${System.nanoTime()}").also { it.mkdirs() }
    private fun write(root: File, path: String, value: String) {
        File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(value) }
    }
    private fun view(root: File) = object : ContextWrapper(ctx) { override fun getFilesDir() = root }
    private fun export(root: File, name: String) {
        File(out, name).outputStream().use { engine().create(root, root("export"), it, null, 84, "1.4.5", BackupPreferences()) }
    }
    private fun copyTree(source: File, name: String) {
        val target = File(out, name)
        check(!target.exists()) { "OUTPUT_ALREADY_EXISTS" }
        check(source.copyRecursively(target))
    }

    @Test fun produceOfficialArchive() = runBlocking {
        guard()
        val source = root("source")
        fun png(name: String, color: Int): String {
            val file = File(source, "images/$name.png").also { it.parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            return file.absolutePath
        }
        val shared = png("shared", 0xff2299aa.toInt())
        val other = png("metadata", 0xffddbb33.toInt())
        val card = CharacterCard.create("Synthetic POC", greeting="").copy(id="card", avatar=shared,
            characters=listOf(CharacterInfo("sender", "Synthetic sender", appearanceImage=shared)))
        val session = ChatSession.create(card.id, "Synthetic POC").copy(id="session", longTermMemoryEnabled=false,
            automaticImageGenerationEnabled=false, lastMessageTime=1L)
        write(source,"entities/app_settings.json",backupJson.encodeToString(AppSettings(momentsEnabled=false,
            momentsImagesEnabled=false, momentsAutoStartConfirmed=true, allowCleartextModelApi=false)))
        write(source,"entities/character_cards/card.json",backupJson.encodeToString(card))
        write(source,"entities/chat_sessions/session.json",backupJson.encodeToString(session))
        val base = MomentPost("text","card","session","sender","Saved synthetic sender",shared,
            "Synthetic text",scheduledAt=1,generatedAt=2)
        val checkpoint = backupJson.encodeToString(MomentGenerationCheckpoint(
            decision=MomentPostDecision(true,"synthetic"), draft=MomentDraft(text="Synthetic checkpoint")))
        val posts=listOf(base, base.copy(id="image", imagePath=shared,
            generatedImageMetadata=GeneratedImageMetadata(other,"synthetic",negativePrompt="synthetic",sizePreset="SQUARE",width=4,height=4)),
            base.copy(id="private",isPrivate=true,imagePath=shared),
            base.copy(id="failed",isPlaceholder=true,text="",failureReason="synthetic failure",generationCheckpoint=checkpoint),
            base.copy(id="historical",characterCardId="deleted-card",sessionId="deleted-session",senderCharacterId="deleted-sender"))
        posts.forEach { write(source,"entities/moment_posts/${it.id}.json",backupJson.encodeToString(it)) }
        MomentTaskStatus.entries.forEach { status ->
            val task=MomentTask(status.name.lowercase(),"card","session",1,status,
                postId=when(status) { MomentTaskStatus.COMPLETED -> "image"; MomentTaskStatus.FAILED -> "failed"; else -> null }, createdAt=1,updatedAt=1)
            write(source,"entities/moment_tasks/${task.id}.json",backupJson.encodeToString(task))
        }
        BackupEntityValidator.validateTree(view(source),source) {}
        export(source,"official-source.cbbackup")
        val stage=root("verify")
        File(out,"official-source.cbbackup").inputStream().use { engine().extract(it,null,stage,source,84) }
        BackupEntityValidator.validateTree(view(source),File(stage,"files")) {}
        copyTree(source,"original-logical")
        File(out,"produce.json").writeText("{\"posts\":5,\"tasks\":5,\"images\":2,\"officialCreate\":true,\"officialValidate\":true}")
    }

    @Test fun consumeReexportThroughOfficialAndRepository() = runBlocking {
        guard()
        val target=root("return-target")
        val stage=root("return-stage")
        File(out,"host-reexport.cbbackup").inputStream().use { engine().extract(it,null,stage,target,84) }
        BackupEntityValidator.validateTree(view(target),File(stage,"files")) {}
        check(File(stage,"files").copyRecursively(target))
        val repository=MomentRepository(JsonFileStorage(view(target)))
        assertEquals(5,repository.getAllPosts().size)
        assertEquals(MomentTaskStatus.entries.toSet(),repository.getAllTasks().map { it.status }.toSet())
        assertEquals("image",repository.getAllTasks().single { it.id=="completed" }.postId)
        assertEquals("deleted-card",repository.getPost("historical")!!.characterCardId)
        copyTree(target,"official-return-logical")
        export(target,"official-second-export.cbbackup")
        File(out,"consume.json").writeText("{\"officialExtract\":true,\"officialValidator\":true,\"officialRepositoryRead\":true,\"posts\":5,\"tasks\":5}")
    }

    @Test fun restoredTasksAndActualSchedulerAdmission() = runBlocking {
        guard()
        val app=ChatBarApp.instance
        withTimeout(60_000) { while(!app.dependenciesReady.value) delay(100) }
        app.initializePersistentState().join()
        val target=root("scheduler-target")
        val workspace=root("scheduler-transaction")
        val prepared=File(workspace,"prepared")
        File(out,"host-reexport.cbbackup").inputStream().use { engine().extract(it,null,prepared,target,84) }
        BackupEntityValidator.validateTree(view(target),File(prepared,"files")) {}
        // Invoke the unchanged official import normalization, not a test-authored replacement.
        val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val service=AppBackupService(ctx,serviceScope,{})
        val reset=AppBackupService::class.java.getDeclaredMethod("resetDeviceConfirmations",File::class.java)
        reset.isAccessible=true
        reset.invoke(service,File(prepared,"files"))
        serviceScope.cancel()
        BackupPreparedSeal.write(prepared,{})
        val transaction=BackupRestoreTransaction(target,root("preferences"),workspace)
        transaction.schedule()
        assertTrue(transaction.bootstrap(restorePreferences={ file ->
            val p=backupJson.decodeFromString<BackupPreferences>(file.readText())
            check(p.novelAiToken==null && p.fishAudioKey==null && p.communitySession==null)
            // No credentials exist; this isolated test does not install Android preferences.
        },validatePrepared={ BackupPreparedSeal.validate(it) }))
        val storage=JsonFileStorage(view(target))
        val settings=SettingsRepository(storage)
        val moments=MomentRepository(storage)
        val chats=ChatRepository(storage)
        val cards=CharacterRepository(storage)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val scheduler=MomentScheduler(ctx,scope,settings,cards,chats,moments,app.effectiveModelResolver,app.momentGenerationService)
        assertFalse(settings.getAppSettings().momentsAutoStartConfirmed)
        val before=moments.getAllTasks().associateBy { it.id }
        scheduler.runOnce("poc-off",10_000L)
        assertTrue("DISABLED_TASKS_CHANGED",before==moments.getAllTasks().associateBy { it.id })
        transaction.commit()
        // Enabled but unconfigured generation: actual official processing can mutate due tasks.
        val enabled=settings.getAppSettings().copy(momentsEnabled=true,momentsImagesEnabled=false,
            allowCleartextModelApi=false,siliconFlowApiKey="")
        val model=app.effectiveModelResolver.resolveChatModel(null,enabled)
        check(model==null || !model.hasConfiguredAuthentication(enabled)) { "MODEL_ADMISSION_NOT_CLOSED" }
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { fake ->
            fake.soTimeout=250
            settings.saveAppSettings(enabled)
            scheduler.runOnce("poc-enabled-no-provider",10_000L)
            assertEquals(MomentTaskStatus.FAILED,moments.getAllTasks().single { it.id=="pending" }.status)
            assertEquals(MomentTaskStatus.RUNNING,moments.getAllTasks().single { it.id=="running" }.status)
            try { fake.accept().close(); error("UNEXPECTED_LOOPBACK_REQUEST") } catch (_: java.net.SocketTimeoutException) {}
        }
        scope.cancel()
        File(out,"scheduler.json").writeText("{\"officialTransactionApplied\":true,\"officialResetConfirmedFalse\":true,\"disabledTasksUnchanged\":true,\"enabledDuePending\":\"FAILED\",\"runningRetained\":true,\"providerAdmission\":\"CLOSED\",\"paidGeneration\":\"NOT_TESTED\",\"fullApplicationColdRestart\":\"NOT_TESTED\"}")
        copyTree(target,"scheduler-after")
    }
}

/** Writes actual JUnit callback outcomes, separately from the runner's console status. */
class PocXmlListener : org.junit.runner.notification.RunListener() {
    private val rows=mutableListOf<Pair<String,String?>>()
    private var current=""
    private var failure:String?=null
    override fun testStarted(description: org.junit.runner.Description) { current=description.methodName; failure=null }
    override fun testFailure(f: org.junit.runner.notification.Failure) { failure=f.exception.javaClass.name }
    override fun testFinished(description: org.junit.runner.Description) { rows+=current to failure }
    override fun testRunFinished(result: org.junit.runner.Result) {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(ctx.getExternalFilesDir(null),"p11-moments-poc").also { it.mkdirs() }
        val xml=buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"OfficialMomentsPoc\" tests=\"${result.runCount}\" failures=\"${result.failureCount}\" errors=\"0\" skipped=\"${result.ignoreCount}\">")
            rows.forEach { (name,error) ->
                append("<testcase name=\"$name\">")
                if(error!=null) append("<failure type=\"$error\"/>")
                append("</testcase>")
            }
            append("</testsuite>")
        }
        File(directory,"junit-${System.currentTimeMillis()}.xml").writeText(xml)
    }
}
