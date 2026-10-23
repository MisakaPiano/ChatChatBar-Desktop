package com.example.chatbar.desktop.backup
import com.example.chatbar.desktop.DesktopArchiveFiles
import java.nio.file.*

/** Task-local evidence only, never app persistence. Caller supplies a NEW empty ignored directory. */
internal object PrivateSourceWitness {
    fun retain(directory:Path,before:String,after:String) {
        require(before.matches(Regex("[a-f0-9]{64}")) && before==after)
        DesktopArchiveFiles.safe(directory,true)
        require(Files.list(directory).use{it.count()}==0L)
        Files.writeString(directory.resolve("source-witness.private.txt"),"BEFORE_SHA256=$before\nAFTER_SHA256=$after\nSOURCE_BYTES=UNCHANGED\n",
            StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
    }
}
