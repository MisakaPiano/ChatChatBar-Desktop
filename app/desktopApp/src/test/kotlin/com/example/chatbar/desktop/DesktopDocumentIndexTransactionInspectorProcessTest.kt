package com.example.chatbar.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

class DesktopDocumentIndexTransactionInspectorProcessTest {
    @Test fun `fresh owned inspection exits and next independent JVM reacquires root`() {
        val f=DesktopDocumentIndexTransactionSyntheticFixture()
        f.prepare(completed=true)
        val before=f.snapshot();f.close()
        runChild(f.root,"INSPECT","COMPLETED_STRUCTURE_ONLY")
        runChild(f.root,"LOCK","REACQUIRED")
        val after=f.snapshot();assertEquals(before,after);DesktopDocumentIndexTransactionSyntheticFixture.recordProcessInventory(before,after)
    }
    @Test fun `fresh process refuses corrupt payload without writes and exits`() {
        val f=DesktopDocumentIndexTransactionSyntheticFixture();val t=f.prepare(completed=true)
        Files.writeString(f.domain.resolve("history/${t.tx}/stage/card.json"),"{}")
        val before=f.snapshot();f.close()
        runChild(f.root,"INSPECT","CORRUPT");runChild(f.root,"LOCK","REACQUIRED")
        val after=f.snapshot();assertEquals(before,after);DesktopDocumentIndexTransactionSyntheticFixture.recordProcessInventory(before,after)
    }
    @Test fun `cancelled fresh process drains native read closes and independent JVM reacquires ownership`() {
        val f=DesktopDocumentIndexTransactionSyntheticFixture();f.prepare(completed=true)
        val before=f.snapshot();f.close()
        runChild(f.root,"CANCEL","CANCELLED");runChild(f.root,"LOCK","REACQUIRED")
        val after=f.snapshot();assertEquals(before,after);DesktopDocumentIndexTransactionSyntheticFixture.recordProcessInventory(before,after)
    }
    private fun runChild(root:Path,mode:String,expected:String) {
        val javaExe=Path.of(System.getProperty("java.home"),"bin/java.exe")
        val classpath=listOf(DesktopIndexInspectorSyntheticChild::class.java,DesktopDataRootOwnership::class.java,
            com.example.chatbar.data.local.entity.CharacterCard::class.java,Unit::class.java,kotlinx.coroutines.Job::class.java,
            Json::class.java,kotlinx.serialization.KSerializer::class.java,com.sun.jna.Native::class.java,
            com.sun.jna.platform.win32.Kernel32::class.java).map{Path.of(it.protectionDomain.codeSource.location.toURI()).toString()}.distinct().joinToString(File.pathSeparator)
        val outputFile=Files.createTempFile("ccb-g2-child-",".log")
        val process=ProcessBuilder(javaExe.toString(),"-Xmx128m","-XX:+UseSerialGC","-XX:ActiveProcessorCount=1","-cp",classpath,
            DesktopIndexInspectorSyntheticChild::class.java.name,root.toString(),mode).redirectErrorStream(true).redirectOutput(outputFile.toFile()).start()
        val identity=process.toHandle().info().startInstant().orElseThrow()
        val parent=process.toHandle().parent().orElseThrow().pid()
        assertEquals(ProcessHandle.current().pid(),parent)
        assertTrue(Files.isSameFile(javaExe,Path.of(process.info().command().orElseThrow())))
        // No forced termination or lock removal. A watchdog failure preserves the owned process/root.
        try {
            assertTrue(process.waitFor(120,TimeUnit.SECONDS),"Synthetic inspector child did not exit; root retained")
            assertTrue(Files.size(outputFile)<=65536,"Child diagnostic exceeded bounded capture; original retained")
            val output=Files.readString(outputFile)
            assertEquals(0,process.exitValue(),output);assertEquals("RESULT $expected",output.trim())
            System.getenv("CCB_G2_INSPECTOR_EVIDENCE")?.let{directory->
                val record=buildJsonObject{put("pid",process.pid());put("parentPid",parent);put("executable","JDK17_JAVA");put("executableSha256",DesktopDocumentIndexTransactionSyntheticFixture.hash(Files.readAllBytes(javaExe)));put("startedUtc",identity.toString());put("mode",mode);put("result",expected);put("exitCode",process.exitValue());put("aliveAfterWait",process.isAlive)}
                Files.writeString(Path.of(directory).resolve("process-events.jsonl"),record.toString()+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
                Files.copy(outputFile,Path.of(directory).resolve("child-${process.pid()}-${identity.epochSecond}.log"))
            }
        } finally {
            process.outputStream.close();process.inputStream.close();process.errorStream.close()
        }
    }
}

object DesktopIndexInspectorSyntheticChild {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        require(args.size==2)
        val root=Path.of(args[0]);val owner=DesktopDocumentIndexTransactionSyntheticFixture.acquire(root)
        try {
            val code=when(args[1]) {
                "LOCK"->"REACQUIRED"
                "CANCEL"->coroutineScope {
                    val opened=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                    val job=async{DesktopDocumentIndexTransactionInspector(onReadStep={if(it==DesktopDocumentIndexReadEvidence.Step.BEFORE_READ){opened.complete(Unit);release.await()}}).inspect(root)}
                    withTimeout(10000){opened.await()};job.cancel();release.complete(Unit);withTimeout(10000){job.join()}
                    check(job.isCancelled);"CANCELLED"
                }
                else->DesktopDocumentIndexTransactionInspector().inspect(root).let{result->check(result.businessAdmission==DesktopIndexBusinessAdmission.BLOCKED);result.code.name}
            }
            println("RESULT $code")
        } finally {owner.close()}
    }
}
