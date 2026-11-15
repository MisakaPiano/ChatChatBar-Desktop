package com.example.chatbar.domain.lanSync

import com.example.chatbar.domain.syncStore.SyncStoreId
import kotlin.test.*

class LanSyncGroupDiscoveryTest {
    private val group = SyncStoreId.parse("1".repeat(32))
    private val device = "2".repeat(64)
    private val nonce = "3".repeat(64)

    @Test fun groupDiscoveryCarriesExactOpaqueGroupDeviceAndFreshRequestNonce() {
        val request = LanSyncGroupDiscoveryProtocol.request(group, device, nonce)
        val decoded = requireNotNull(LanSyncGroupDiscoveryProtocol.request(request, 0, request.size))
        assertEquals(group, decoded.group); assertEquals(device, decoded.device); assertEquals(nonce, decoded.nonce)
        val reply = LanSyncGroupDiscoveryProtocol.reply(group, device, 44321, nonce)
        val location = requireNotNull(LanSyncGroupDiscoveryProtocol.reply(reply, 0, reply.size))
        assertEquals(group, location.group); assertEquals(device, location.device)
        assertEquals(44321, location.port); assertEquals(nonce, location.nonce)
        assertNotEquals(39237, LanSyncGroupDiscovery.PORT)
        assertTrue(request.size < LanSyncGroupDiscoveryProtocol.MAX_BYTES)
        assertTrue(reply.size < LanSyncGroupDiscoveryProtocol.MAX_BYTES)
    }

    @Test fun legacyNamespaceAndAdditionalAddressOrCapabilityFieldsNeverParse() {
        val invalidRequests = listOf(
            "CCB-LAN-DISCOVER-7 $device $nonce",
            "CCB-GROUP-FIND-1 ${group.value} $device $nonce 192.168.1.2",
            "CCB-GROUP-FIND-1 ${group.value} $device $nonce capability",
            "CCB-GROUP-FIND-1 ${group.value}  $device $nonce",
            " CCB-GROUP-FIND-1 ${group.value} $device $nonce",
            "CCB-GROUP-FIND-1 ${group.value} $device $nonce\n",
            "CCB-GROUP-FIND-1 ${group.value} ${"A".repeat(64)} $nonce",
        )
        invalidRequests.forEach { text ->
            val bytes = text.toByteArray(Charsets.US_ASCII)
            assertNull(LanSyncGroupDiscoveryProtocol.request(bytes, 0, bytes.size))
        }
        val legacy = "CCB-LAN-HERE-7 $device 44321 $nonce".toByteArray(Charsets.US_ASCII)
        assertNull(LanSyncGroupDiscoveryProtocol.reply(legacy, 0, legacy.size))
    }

    @Test fun framesAreBoundedAsciiAndHonorOnlyTheirExactDatagramSlice() {
        val request = LanSyncGroupDiscoveryProtocol.request(group, device, nonce)
        val backing = byteArrayOf(0, 127) + request + byteArrayOf(0)
        assertNotNull(LanSyncGroupDiscoveryProtocol.request(backing, 2, request.size))
        assertNull(LanSyncGroupDiscoveryProtocol.request(backing, 0, backing.size))
        assertNull(LanSyncGroupDiscoveryProtocol.request(backing, -1, request.size))
        assertNull(LanSyncGroupDiscoveryProtocol.request(backing, 2, Int.MAX_VALUE))
        assertNull(LanSyncGroupDiscoveryProtocol.request(backing, Int.MAX_VALUE, request.size))
        assertNull(LanSyncGroupDiscoveryProtocol.request(request, 0, request.size - 1))
        val tooLarge = request + ByteArray(LanSyncGroupDiscoveryProtocol.MAX_BYTES)
        assertNull(LanSyncGroupDiscoveryProtocol.request(tooLarge, 0, tooLarge.size))
        val highByte = request.copyOf().also { it[it.lastIndex] = 0xff.toByte() }
        assertNull(LanSyncGroupDiscoveryProtocol.request(highByte, 0, highByte.size))
    }

    @Test fun replyRequiresCanonicalPortAndCompleteGroupAndNonce() {
        listOf("0", "1023", "65536", "044321", "+44321", "44321.0").forEach { port ->
            val bytes = "CCB-GROUP-HERE-1 ${group.value} $device $port $nonce".toByteArray(Charsets.US_ASCII)
            assertNull(LanSyncGroupDiscoveryProtocol.reply(bytes, 0, bytes.size))
        }
        listOf("short", "z".repeat(32)).forEach { id ->
            val bytes = "CCB-GROUP-HERE-1 $id $device 44321 $nonce".toByteArray(Charsets.US_ASCII)
            assertNull(LanSyncGroupDiscoveryProtocol.reply(bytes, 0, bytes.size))
        }
        val truncated = LanSyncGroupDiscoveryProtocol.reply(group, device, 44321, nonce).dropLast(1).toByteArray()
        assertNull(LanSyncGroupDiscoveryProtocol.reply(truncated, 0, truncated.size))
    }
}
