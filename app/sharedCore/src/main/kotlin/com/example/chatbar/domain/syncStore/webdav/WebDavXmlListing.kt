package com.example.chatbar.domain.syncStore.webdav

import com.example.chatbar.domain.syncStore.CiphertextStoreException
import com.example.chatbar.domain.syncStore.CiphertextStoreFailure
import java.io.InputStream
import java.io.FilterInputStream
import java.util.TreeSet
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** Android API26/JVM shared SAX only. No DOM or entire-response string allocation. */
internal object WebDavXmlListing {
    const val DAV = "DAV:"
    val requestBytes = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        .toByteArray(Charsets.UTF_8)

    fun parse(input: InputStream, collection: HttpUrl, after: String?, limit: Int, maximumBytes: Long): List<String> {
        val handler = ListingHandler(collection, after, limit)
        val reader = try {
            SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader.apply {
                // Expat and JVM SAX both expose these flags. Refuse if protections cannot be installed.
                for (feature in listOf("http://xml.org/sax/features/external-general-entities",
                    "http://xml.org/sax/features/external-parameter-entities")) {
                    setFeature(feature, false)
                    if (getFeature(feature)) WebDavHttpTransport.fail(CiphertextStoreFailure.UNSUPPORTED)
                }
                setProperty("http://xml.org/sax/properties/lexical-handler", handler)
                entityResolver = handler; contentHandler = handler; errorHandler = handler
            }
        } catch (failure: CiphertextStoreException) { throw failure }
        catch (_: Exception) { WebDavHttpTransport.fail(CiphertextStoreFailure.UNSUPPORTED) }
        try {
            reader.parse(InputSource(object : FilterInputStream(input) {
                var remaining = maximumBytes
                override fun read(): Int {
                    val value = `in`.read()
                    if (value >= 0) {
                        if (remaining == 0L) WebDavHttpTransport.fail(CiphertextStoreFailure.UNSUPPORTED)
                        remaining--
                    }
                    return value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return if (read() < 0) -1 else 0
                    val count = `in`.read(bytes, offset, minOf(remaining, length.toLong()).toInt())
                    if (count > 0) remaining -= count.toLong()
                    return count
                }
            }))
        } catch (failure: CiphertextStoreException) { throw failure }
        catch (_: SAXException) { WebDavHttpTransport.fail(CiphertextStoreFailure.INTEGRITY) }
        if (!handler.rootSeen || !handler.selfSeen) WebDavHttpTransport.fail(CiphertextStoreFailure.INTEGRITY)
        return handler.ids.toList()
    }

    private class ListingHandler(val collection: HttpUrl, val after: String?, val limit: Int) : DefaultHandler2() {
        val ids = TreeSet<String>()
        var rootSeen = false
        var selfSeen = false
        private var depth = 0
        private var responseDepth = -1
        private var propstatDepth = -1
        private var propDepth = -1
        private var typeDepth = -1
        private var typeSeen = false
        private var collectionType = false
        private var goodType: Boolean? = null
        private var href: String? = null
        private var responseStatus: Int? = null
        private var propStatus: Int? = null
        private var capture: String? = null
        private var captureDepth = -1
        private val text = StringBuilder()

        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("SYNC_DAV_DTD") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw SAXException("SYNC_DAV_ENTITY") }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { throw SAXException("SYNC_DAV_ENTITY") }
        override fun error(error: org.xml.sax.SAXParseException) { throw error }
        override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            WebDavStoreRecord.interrupted()
            depth++
            if (depth > 32 || capture != null) bad()
            if (attributes.length > 64 || localName.length > 256 || uri.length > 8192) bad()
            if (depth == 1) {
                if (uri != DAV || localName != "multistatus") bad()
                rootSeen = true
            }
            if (uri != DAV) return
            if (depth == 2 && localName == "response") {
                responseDepth = depth; href = null; responseStatus = null; goodType = null
            } else if (responseDepth > 0) when {
                depth == 3 && localName == "href" -> startCapture("href")
                depth == 3 && localName == "status" -> startCapture("response-status")
                depth == 3 && localName == "propstat" -> { propstatDepth = depth; propStatus = null; propDepth = -1; typeDepth = -1; typeSeen = false; collectionType = false }
                depth == 4 && propstatDepth > 0 && localName == "status" -> startCapture("prop-status")
                depth == 4 && propstatDepth > 0 && localName == "prop" -> { if (propDepth != -1) bad(); propDepth = depth }
                depth == 5 && propDepth == 4 && localName == "resourcetype" -> { if (typeSeen) bad(); typeDepth = depth; typeSeen = true }
                depth == 6 && typeDepth == 5 && localName == "collection" -> collectionType = true
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            if (capture != null) {
                if (length > 8192 - text.length) bad()
                text.append(chars, start, length)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (capture != null && depth == captureDepth) {
                val value = text.toString().trim()
                when (capture) {
                    "href" -> { if (href != null || value.isEmpty()) bad(); href = value }
                    "response-status" -> { if (responseStatus != null) bad(); responseStatus = status(value) }
                    "prop-status" -> { if (propStatus != null) bad(); propStatus = status(value) }
                }
                capture = null
            }
            if (uri == DAV && depth == propstatDepth && localName == "propstat") {
                val code = propStatus ?: bad()
                if (code != 200) statusFailure(code)
                if (!typeSeen || goodType != null) bad()
                goodType = collectionType; propstatDepth = -1
            }
            if (uri == DAV && depth == typeDepth && localName == "resourcetype") typeDepth = -1
            if (uri == DAV && depth == propDepth && localName == "prop") propDepth = -1
            if (uri == DAV && depth == responseDepth && localName == "response") {
                responseStatus?.let { if (it != 200) statusFailure(it) }
                accept(href ?: bad(), goodType ?: bad()); responseDepth = -1
            }
            depth--
        }

        private fun startCapture(kind: String) { capture = kind; captureDepth = depth; text.setLength(0) }
        private fun status(value: String): Int = Regex("HTTP/[0-9]+(?:\\.[0-9]+)? ([1-5][0-9]{2})(?: .*)?")
            .matchEntire(value)?.groupValues?.get(1)?.toInt() ?: bad()
        private fun statusFailure(code: Int): Nothing = WebDavHttpTransport.fail(when (code) {
            401 -> CiphertextStoreFailure.AUTHENTICATION
            403 -> CiphertextStoreFailure.PERMISSION
            404, 423, 429, in 500..599 -> CiphertextStoreFailure.TRANSIENT
            else -> CiphertextStoreFailure.INTEGRITY
        })

        private fun accept(href: String, isCollection: Boolean) {
            val target = collection.resolve(href) ?: bad()
            if (target.scheme != collection.scheme || target.host != collection.host || target.port != collection.port ||
                target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null || target.fragment != null) unsafe()
            val prefix = collection.encodedPath
            if (target.encodedPath.trimEnd('/') == prefix.trimEnd('/')) {
                if (!isCollection) bad()
                selfSeen = true; return
            }
            if (!target.encodedPath.startsWith(prefix) || isCollection) unsafe()
            val name = target.encodedPath.removePrefix(prefix)
            if (!name.matches(Regex("[a-f0-9]{32}\\.bin"))) unsafe()
            val id = name.removeSuffix(".bin")
            if (after != null && id <= after) return
            ids.add(id)
            if (ids.size > limit) ids.pollLast()
        }
        private fun bad(): Nothing = WebDavHttpTransport.fail(CiphertextStoreFailure.INTEGRITY)
        private fun unsafe(): Nothing = WebDavHttpTransport.fail(CiphertextStoreFailure.UNSAFE_PATH)
    }
}
