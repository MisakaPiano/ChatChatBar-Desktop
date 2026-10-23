package com.example.chatbar.desktop

import java.nio.file.*
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.*
import com.example.chatbar.data.local.entity.*

class SlotProcessProbeTest {
    private fun child(mode:String,source:Path,parent:Path):Pair<Int,String> {
        val output=Files.createTempFile("lr2-child-synthetic-",".log")
        val pb=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
            "-Xmx256m","-XX:+DisableAttachMechanism","-XX:-HeapDumpOnOutOfMemoryError","-Djava.awt.headless=true",
            "-cp",System.getProperty("ccb.readonly.classpath"),SlotProcessProbe::class.java.name,mode,source.toString(),parent.toString())
        listOf("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS").forEach{pb.environment().remove(it)}
        val process=pb.redirectErrorStream(true).redirectOutput(output.toFile()).start()
        if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();error("CHILD_TIMEOUT")}
        val text=Files.readString(output)
        val evidence=Files.createDirectories(Path.of("build/p11-lr2/child-evidence"))
        Files.copy(output,evidence.resolve("${process.pid()}-$mode.log"))
        Files.writeString(evidence.resolve("${process.pid()}-$mode.exit"),process.exitValue().toString())
        return process.exitValue() to text
    }
    private fun source():Path {
        val parent=Files.createTempDirectory("lr2-source-synthetic-")
        val chunk=VectorChunk("r",ChunkSourceType.CHAT_MEMORY,"s","m","Synthetic RAG",listOf(.1f,.2f),mapOf("sessionId" to "s"),1)
        val slot=SyntheticSaveSlotCorpus.slot().copy(playerName="Synthetic player",modelId="synthetic-model",
            packageRef=SaveSlotPackageRef("synthetic.cbsave",1,ragChunkCount=1),
            memorySnapshot=MemorySnapshot(state=MemorySessionSnapshot()))
        return SyntheticSaveSlotCorpus.archive(parent.resolve("synthetic.cbsave"),chunks=listOf(chunk),manifest=slot)
    }
    @Test fun `independent normal process reopens complete supported source fields twice`() {
        val source=source();val root=Files.createTempDirectory("lr2-process-synthetic-")
        val a=child("restore",source,root);assertEquals(0,a.first,a.second)
        val b=child("reopen",source,root);assertEquals(0,b.first,b.second)
        assertTrue(b.second.contains("RAG_ALL_FIELDS_EXCEPT_IDENTITIES=VERIFIED"))
        assertTrue(b.second.contains("CURRENT_SNAPSHOT_FIELDS_AND_GRAPH_VERIFIED"))
        assertNotEquals(a.second.substringAfter("LR2_PID=").trim(),b.second.substringAfter("LR2_PID=").trim())
        val c=child("reopen",source,root);assertEquals(0,c.first,c.second)
    }
    @Test fun `post commit hard process exit releases lock and permits new process verification`() {
        val source=source();val root=Files.createTempDirectory("lr2-process-halt-synthetic-")
        assertEquals(71,child("restore-halt",source,root).first)
        val b=child("reopen",source,root);assertEquals(0,b.first,b.second)
        assertTrue(b.second.contains("CROSS_PROCESS_BYTES=UNCHANGED"))
    }
    @Test fun `foreign target modification refuses before journal or repository reopening`() {
        val source=source();val root=Files.createTempDirectory("lr2-process-tamper-synthetic-")
        assertEquals(0,child("restore",source,root).first)
        val live=root.resolve(Files.readString(root.resolve("root-name.txt")))
        val message=Files.list(live.resolve("entities/chat_messages")).use{it.findFirst().get()}
        Files.writeString(message,"SYNTHETIC_EXTERNAL_CHANGE")
        assertEquals(2,child("reopen",source,root).first)
        assertEquals("SYNTHETIC_EXTERNAL_CHANGE",Files.readString(message))
    }
}
