package com.example.chatbar.desktop

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** ASCII-only launch arguments bind a bounded UTF-8 file to this exact installed invocation. */
internal object DesktopExternalLaunchRequest {
    private const val MAXIMUM_BYTES = 1048576
    fun paths(args: Array<String>, home: Path, environment: Map<String, String> = System.getenv()): List<Path> {
        if (args.firstOrNull() != "--open-request") return DesktopExternalActivation.paths(args)
        require(args.size == 3)
        val nonce = args[1]; val digest = args[2]
        require(nonce.matches(Regex("[a-f0-9]{32}")) && digest.matches(Regex("[a-f0-9]{64}")))
        val normalized = home.toAbsolutePath().normalize()
        require(normalized.parent?.fileName?.toString() == "versions")
        val request = normalized.parent.parent.resolve("receipts/$nonce.open-request")
        val activation = Path.of(requireNotNull(environment["CCB_DESKTOP_ACTIVATION_PATH"]))
        require(environment["CCB_DESKTOP_ACTIVATION_NONCE"] == nonce && activation.isAbsolute &&
            activation.toAbsolutePath().normalize() == request.resolveSibling("$nonce.activation"))
        DesktopArchiveFiles.safe(request, false)
        val bytes = Files.newInputStream(request).use { it.readNBytes(MAXIMUM_BYTES + 1) }
        require(bytes.size in 8..MAXIMUM_BYTES)
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(actual == digest)
        val raw = DataInputStream(bytes.inputStream()).use { input ->
            require(input.readInt() == 0x43434232)
            val count = input.readInt(); require(count in 1..64)
            val paths = (0 until count).map {
                val size = input.readInt(); require(size in 1..131072 && size <= input.available())
                val text = ByteArray(size); input.readFully(text)
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString()
            }
            require(input.read() == -1)
            paths
        }
        // The ordinary absolute-path/count/length validation remains authoritative.
        return DesktopExternalActivation.paths(arrayOf("--open-files", *raw.toTypedArray()))
    }
}
