package com.example.chatbar.lanSync

import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.syncStore.CiphertextStoreException
import com.example.chatbar.domain.syncStore.CiphertextStoreFailure
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

/** An explicit API26 parser probe, not a WebDAV account/network or key-custody test. */
class LanSyncWebDavPlatformTest {
    @Test fun productionSaxRejectsDtdAndParsesSegmentedDavOnApi26() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("lanSyncWebDavPlatformProbe"))
        assertEquals(26, Build.VERSION.SDK_INT)
        assertEquals("com.example.chatbar.lansync", ApplicationProvider.getApplicationContext<android.content.Context>().packageName)

        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val descriptionReader = factory.newSAXParser().xmlReader
        FEATURES.forEach { feature -> descriptionReader.setFeature(feature, false); assertFalse(descriptionReader.getFeature(feature)) }
        val descriptionHandler = DefaultHandler2()
        descriptionReader.setProperty(LEXICAL, descriptionHandler)
        assertSame(descriptionHandler, descriptionReader.getProperty(LEXICAL))

        // These are the exact production class/method, not a copied implementation.
        // Reflection avoids a production test API or Kotlin internal visibility changes.
        val owner = Class.forName("com.example.chatbar.domain.syncStore.webdav.WebDavXmlListing")
        val instance = owner.getField("INSTANCE").get(null)
        val method = owner.getDeclaredMethod("parse", InputStream::class.java, HttpUrl::class.java,
            String::class.java, java.lang.Integer.TYPE, java.lang.Long.TYPE)
        // Observe the actual production guard on the native XMLReader, without replacing the
        // global SAX factory or adding a hook to production. No copied test-only reject logic.
        val handlerType = owner.declaredClasses.single { it.simpleName == "ListingHandler" }
        val constructor = handlerType.getDeclaredConstructor(HttpUrl::class.java, String::class.java,
            java.lang.Integer.TYPE).apply { isAccessible = true }
        val productionHandler = constructor.newInstance(COLLECTION, null, 16)
        val startDtd = handlerType.getDeclaredMethod("startDTD", String::class.java, String::class.java,
            String::class.java).apply { isAccessible = true }
        fun productionStartDtd(name: String?, publicId: String?, systemId: String?) {
            try { startDtd.invoke(productionHandler, name, publicId, systemId) }
            catch (failure: InvocationTargetException) {
                val actual = failure.targetException
                assertTrue(actual is SAXException)
                assertEquals("SYNC_DAV_DTD", actual.message)
                throw actual
            }
        }
        fun production(input: InputStream): List<String> {
            val value = try { method.invoke(instance, input, COLLECTION, null, 16, XML_BUDGET_BYTES) }
            catch (failure: InvocationTargetException) { throw failure.targetException }
            val result = value as? List<*> ?: error("PRODUCTION_PARSE_RESULT_TYPE")
            assertTrue(result.all { it is String })
            return result.map { it as String }
        }

        val valid = xml("")
        assertEquals(listOf(OBJECT_ID), production(ByteArrayInputStream(valid)))
        val segmented = OneByteInput(valid)
        assertEquals(listOf(OBJECT_ID), production(segmented))
        assertEquals(valid.size, segmented.consumed)
        assertTrue(segmented.bulkReads > 1)

        val cases = listOf(
            "internal-entity" to "<!DOCTYPE d:multistatus [<!ENTITY local 'unused synthetic entity'>]>",
            "external-general" to "<!DOCTYPE d:multistatus [<!ENTITY outside SYSTEM 'file:///ccb-webdav-api26-no-such-fixture/entity.txt'>]>",
            "external-parameter" to "<!DOCTYPE d:multistatus [<!ENTITY % outside SYSTEM 'https://webdav-api26.invalid/parameter.dtd'>%outside;]>",
            "external-dtd" to "<!DOCTYPE d:multistatus SYSTEM 'https://webdav-api26.invalid/external.dtd'>",
        )
        var nativeDtdCallbacks = 0
        var nativeResolverCallbacks = 0
        for ((name, declaration) in cases) {
            // First exercise the real native XMLReader with exactly the same feature/property setup.
            // It must enter startDTD and throw there, before either resolver callback. There is no
            // resolver fallback that opens a URL/file. Every document's DAV body is otherwise valid.
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            val guard = CountingDtd(::productionStartDtd)
            FEATURES.forEach { reader.setFeature(it, false); assertFalse(reader.getFeature(it)) }
            reader.setProperty(LEXICAL, guard)
            reader.entityResolver = guard; reader.contentHandler = guard; reader.errorHandler = guard
            try { reader.parse(InputSource(OneByteInput(xml(declaration)))); fail("Native SAX accepted $name") }
            catch (_: SAXException) { /* The exact production exception was checked in the callback. */ }
            assertEquals(name, 1, guard.dtdCalls)
            assertEquals(name, 0, guard.resolverCalls)
            nativeDtdCallbacks += guard.dtdCalls; nativeResolverCallbacks += guard.resolverCalls
        }

        // Warm exception classes before the guarded phase. The production parser itself is called
        // for each hostile document below. A network attempt on this thread terminates the probe,
        // rather than contacting the reserved .invalid names. No Android HTTP server is created.
        assertEquals(CiphertextStoreFailure.INTEGRITY,
            CiphertextStoreException(CiphertextStoreFailure.INTEGRITY).failure)
        val previousPolicy = StrictMode.getThreadPolicy()
        var productionRejections = 0
        try {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previousPolicy)
                .detectNetwork().penaltyDeathOnNetwork().build())
            for ((name, declaration) in cases) {
                try { production(OneByteInput(xml(declaration))); fail("Production SAX accepted $name") }
                catch (failure: CiphertextStoreException) {
                    assertEquals(name, CiphertextStoreFailure.INTEGRITY, failure.failure)
                    productionRejections++
                }
            }
        } finally { StrictMode.setThreadPolicy(previousPolicy) }
        assertEquals(4, productionRejections)
        assertEquals(4, nativeDtdCallbacks)
        assertEquals(0, nativeResolverCallbacks)

        val facts = JSONObject().put("api", Build.VERSION.SDK_INT)
            .put("factoryClass", factory.javaClass.name).put("readerClass", descriptionReader.javaClass.name)
            .put("externalGeneralEntities", false).put("externalParameterEntities", false)
            .put("lexicalHandlerInstalled", true).put("productionPositiveCalls", 2)
            .put("inputBudgetBytes", XML_BUDGET_BYTES)
            .put("productionSegmentedUtf8", true).put("productionDtdIntegrityRejections", productionRejections)
            .put("nativeStartDtdCallbacks", nativeDtdCallbacks).put("nativeResolverCallbacks", nativeResolverCallbacks)
            .put("nativeStartDtdInvokesProductionHandler", true)
            .put("productionNetworkDeathGuard", true).put("webDavHttpsTransportTested", false)
            .put("realAccountTested", false).put("largeTransferTested", false)
        InstrumentationRegistry.getInstrumentation().sendStatus(2,
            Bundle().apply { putString("stream", "WEBDAV_API26_SAX_CONTRACT_PASS $facts\n") })
    }

    private class CountingDtd(private val productionStartDtd: (String?, String?, String?) -> Unit) : DefaultHandler2() {
        var dtdCalls = 0
        var resolverCalls = 0
        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            dtdCalls++; productionStartDtd(name, publicId, systemId)
        }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
            resolverCalls++; throw AssertionError("EXTERNAL_ENTITY_RESOLUTION_MUST_NOT_RUN")
        }
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
            resolverCalls++; throw AssertionError("EXTERNAL_ENTITY_RESOLUTION_MUST_NOT_RUN")
        }
    }

    /** Actually splits UTF-8 byte sequences; it does not hand a pre-decoded Reader to SAX. */
    private class OneByteInput(private val bytes: ByteArray) : InputStream() {
        var consumed = 0
        var bulkReads = 0
        override fun read(): Int = if (consumed == bytes.size) -1 else bytes[consumed++].toInt() and 255
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= target.size - length)
            if (length == 0) return 0
            if (consumed == bytes.size) return -1
            target[offset] = bytes[consumed++]; bulkReads++; return 1
        }
    }

    companion object {
        private const val LEXICAL = "http://xml.org/sax/properties/lexical-handler"
        // A small synthetic XML-input safety budget, unrelated to ordinary resource/total sizes.
        private const val XML_BUDGET_BYTES = 64L * 1024
        private val FEATURES = listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities")
        private val COLLECTION = "https://webdav-api26.invalid/sync/commits/".toHttpUrl()
        private val OBJECT_ID = "a".repeat(32)
        private fun xml(doctype: String): ByteArray = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + doctype +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:x=\"urn:ccb:synthetic\">" +
            "<x:note>中文雪😀</x:note>" +
            "<d:response><d:href>/sync/commits/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
            "<d:status>HTTP/1.1 200 合成通过</d:status></d:propstat></d:response>" +
            "<d:response><d:href>/sync/commits/$OBJECT_ID.bin</d:href><d:propstat><d:prop><d:resourcetype/></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>").toByteArray(Charsets.UTF_8)
    }
}
