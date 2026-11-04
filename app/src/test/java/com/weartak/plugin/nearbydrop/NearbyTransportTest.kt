// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.Looper
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.ApiKey
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.weartak.plugin.nearbydrop.protocol.ContentPolicy
import com.weartak.plugin.nearbydrop.protocol.DropSession
import com.weartak.plugin.nearbydrop.protocol.DropWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION")
class NearbyTransportTest {
    @Test fun `constructing transport never starts radios`() {
        val fake = FakeClient()
        transport(fake)
        assertEquals(0, fake.discoveryStarts)
        assertEquals(0, fake.advertisingStarts)
    }

    @Test fun `unavailable Google Play services stops before discovery`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = NearbyTransport(RuntimeEnvironment.getApplication(), views::add, fake, available = { false })
        transport.startReceiving()
        assertEquals(0, fake.discoveryStarts)
        assertEquals(R.string.play_services_unavailable, views.last().message)
    }

    @Test fun `discovery is bounded and filters unknown endpoint protocol`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        transport.startReceiving()
        drain()
        fake.discovery!!.onEndpointFound("wrong", DiscoveredEndpointInfo("foreign.service", "foreign"))
        repeat(30) {
            fake.discovery!!.onEndpointFound("watch-$it", DiscoveredEndpointInfo(DropWire.SERVICE_ID, DropWire.ENDPOINT_NAME))
        }
        drain()
        assertEquals(20, views.last().peers.size)
        assertFalse("wrong" in views.last().peers)
        transport.cancel()
    }

    @Test fun `both connection code confirmation and receiver offer acceptance are explicit`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        connect(transport, fake)
        assertEquals(0, fake.accepts)
        assertEquals(ConnectionInfo(DropWire.ENDPOINT_NAME, "1234", false).authenticationDigits, views.last().code)
        transport.confirmPeer()
        drain()
        assertEquals(1, fake.accepts)
        fake.lifecycle!!.onConnectionResult("watch", ConnectionResolution(Status.RESULT_SUCCESS))
        drain()
        assertEquals(DropSession.State.CONNECTED, views.last().sessionState)
        val offer = DropWire.encode(DropWire.offer(UUID.randomUUID(), byteArrayOf(1)))
        fake.payloads!!.onPayloadReceived("watch", Payload.fromBytes(offer))
        drain()
        assertEquals(DropSession.State.REVIEW, views.last().sessionState)
        assertTrue(fake.sent.isEmpty())
        transport.acceptOffer()
        drain()
        assertEquals(DropWire.Type.ACCEPT, DropWire.decode(fake.sent.single()).type)
        assertFalse(fake.options!!.disruptiveUpgrade)
        transport.cancel()
    }

    @Test fun `blank verification code and unconfirmed connection fail closed`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        transport.startReceiving()
        drain()
        fake.discovery!!.onEndpointFound("watch", DiscoveredEndpointInfo(DropWire.SERVICE_ID, DropWire.ENDPOINT_NAME))
        drain()
        transport.connect("watch")
        drain()
        val info = ConnectionInfo(DropWire.ENDPOINT_NAME, "", false)
        assertEquals("0000", info.authenticationDigits)
        fake.lifecycle!!.onConnectionInitiated("watch", info)
        drain()
        assertEquals(0, fake.accepts)
        fake.lifecycle!!.onConnectionResult("watch", ConnectionResolution(Status.RESULT_SUCCESS))
        drain()
        assertEquals(R.string.nearby_failed, views.last().message)
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `sender waits for receiver receipt and clears in-flight bytes on cancel`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        transport.startSending(pfxFixture())
        drain()
        fake.lifecycle!!.onConnectionInitiated(
            "watch",
            ConnectionInfo(DropWire.ENDPOINT_NAME, "1234", false),
        )
        drain()
        transport.confirmPeer()
        drain()
        fake.lifecycle!!.onConnectionResult("watch", ConnectionResolution(Status.RESULT_SUCCESS))
        drain()

        assertEquals(DropWire.Type.OFFER, DropWire.decode(fake.sent.single()).type)
        assertEquals(DropSession.State.WAITING_ACCEPTANCE, views.last().sessionState)
        assertTrue(fake.payloadBuffers.single().any { it != 0.toByte() })

        transport.cancel()
        assertTrue(fake.payloadBuffers.single().all { it == 0.toByte() })
        assertEquals(R.string.cancelled, views.last().message)
    }

    @Test fun `receiver exposes accepted content once for explicit host handoff`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        connect(transport, fake)
        transport.confirmPeer()
        drain()
        fake.lifecycle!!.onConnectionResult("watch", ConnectionResolution(Status.RESULT_SUCCESS))
        drain()

        val id = UUID.randomUUID()
        val content = pfxFixture()
        fake.payloads!!.onPayloadReceived("watch", Payload.fromBytes(DropWire.encode(DropWire.offer(id, content))))
        drain()
        transport.acceptOffer()
        drain()
        assertEquals(DropWire.Type.ACCEPT, DropWire.decode(fake.sent.last()).type)

        fake.payloads!!.onPayloadReceived(
            "watch",
            Payload.fromBytes(DropWire.encode(DropWire.Frame(DropWire.Type.DATA, id, content))),
        )
        drain()
        assertEquals(DropSession.State.RECEIVED, views.last().sessionState)
        assertEquals(DropWire.Type.RECEIPT, DropWire.decode(fake.sent.last()).type)
        val handoff = transport.takeReceivedContent()
        assertArrayEquals(content, handoff)
        assertThrows(IllegalStateException::class.java) { transport.takeReceivedContent() }
        handoff.fill(0)
        content.fill(0)
        transport.cancel()
    }

    @Test fun `cancellation prevents delayed discovery completion from reviving old session`() {
        val pending = TaskCompletionSource<Void>()
        val fake = FakeClient().apply { discoveryTask = pending.task }
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        transport.startReceiving()
        val oldCallback = fake.discovery!!
        transport.cancel()
        transport.startReceiving()
        assertEquals(1, fake.discoveryStarts)
        pending.setResult(null)
        drain()
        oldCallback.onEndpointFound("stale", DiscoveredEndpointInfo(DropWire.SERVICE_ID, DropWire.ENDPOINT_NAME))
        drain()
        assertFalse(views.any { "stale" in it.peers })
        fake.discoveryTask = Tasks.forResult(null)
        transport.startReceiving()
        drain()
        assertEquals(2, fake.discoveryStarts)
        transport.cancel()
    }

    @Test fun `Nearby failure is visible and timeout closes discovery`() {
        val fake = FakeClient().apply { discoveryTask = Tasks.forException(SecurityException("test")) }
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        transport.startReceiving()
        drain()
        assertEquals(R.string.nearby_failed, views.last().message)
        fake.discoveryTask = Tasks.forResult(null)
        transport.startReceiving()
        drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DropSession.TIMEOUT_MS))
        assertEquals(R.string.nearby_failed, views.last().message)
        assertTrue(fake.stops > 0)
    }

    @Test fun `stale payloads after cancellation are cleared and never accepted`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        connect(transport, fake)
        transport.confirmPeer()
        drain()
        val callback = fake.payloads!!
        transport.cancel()
        val bytes = DropWire.encode(DropWire.offer(UUID.randomUUID(), byteArrayOf(1)))
        callback.onPayloadReceived("watch", Payload.fromBytes(bytes))
        drain()
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(R.string.cancelled, views.last().message)
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `expired confirmation cannot accept connection before next timer tick`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        connect(transport, fake)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(DropSession.TIMEOUT_MS))
        transport.confirmPeer()
        assertEquals(0, fake.accepts)
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `late connection initiation is explicitly rejected after cancellation`() {
        val fake = FakeClient()
        val transport = transport(fake)
        connect(transport, fake)
        val stale = fake.lifecycle!!
        transport.cancel()
        stale.onConnectionInitiated("watch", ConnectionInfo(DropWire.ENDPOINT_NAME, "1234", false))
        drain()
        assertEquals(1, fake.rejects)
        assertEquals(0, fake.accepts)
    }

    @Test fun `fixture loopback runs real offer acceptance and receipt without any radio`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val validator = ContentPolicy { require(it.isNotEmpty()) }
        val transport = NearbyTransport(RuntimeEnvironment.getApplication(), views::add, fake,
            policy = { validator }, available = { false })
        val content = byteArrayOf(9, 8, 7)
        assertTrue(transport.startLoopbackReceive(content, DropWire.ContentKind.SERVER_PACKAGE))
        drain()
        assertEquals(DropSession.State.REVIEW, views.last().sessionState)
        assertEquals(DropWire.ContentKind.SERVER_PACKAGE, views.last().offeredKind)
        transport.acceptOffer()
        drain()
        assertEquals(DropSession.State.RECEIVED, views.last().sessionState)
        assertArrayEquals(byteArrayOf(9, 8, 7), transport.takeReceivedContent())
        assertEquals(0, fake.discoveryStarts)
        assertEquals(0, fake.advertisingStarts)
        assertEquals(0, fake.accepts)
        assertTrue(fake.sent.isEmpty())
        transport.cancel()
    }

    @Test fun `fixture loopback refuses kinds the receiver policy rejects`() {
        val fake = FakeClient()
        val views = mutableListOf<NearbyTransport.ViewState>()
        val transport = transport(fake, views)
        assertFalse(transport.startLoopbackReceive(byteArrayOf(1), DropWire.ContentKind.SERVER_PACKAGE))
        assertEquals(R.string.invalid_bundle, views.last().message)
    }

    private fun transport(fake: FakeClient, views: MutableList<NearbyTransport.ViewState> = mutableListOf()) =
        NearbyTransport(RuntimeEnvironment.getApplication(), views::add, fake, available = { true })

    private fun connect(transport: NearbyTransport, fake: FakeClient) {
        transport.startReceiving()
        drain()
        fake.discovery!!.onEndpointFound("watch", DiscoveredEndpointInfo(DropWire.SERVICE_ID, DropWire.ENDPOINT_NAME))
        drain()
        transport.connect("watch")
        drain()
        fake.lifecycle!!.onConnectionInitiated("watch", ConnectionInfo(DropWire.ENDPOINT_NAME, "1234", false))
        drain()
    }
    private fun drain() = shadowOf(Looper.getMainLooper()).idle()

    private fun pfxFixture(): ByteArray {
        fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = when {
                bytes.size < 128 -> byteArrayOf(bytes.size.toByte())
                bytes.size <= 255 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
                else -> byteArrayOf(
                    0x82.toByte(),
                    (bytes.size shr 8).toByte(),
                    bytes.size.toByte(),
                )
            }
            return byteArrayOf(tag.toByte()) + length + bytes
        }
        fun oid(hex: String) = der(0x06, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        fun sequence(vararg values: ByteArray) =
            der(0x30, values.fold(byteArrayOf()) { aggregate, value -> aggregate + value })
        fun contentInfo(bytes: ByteArray) =
            sequence(oid("2a864886f70d010701"), der(0xa0, der(0x04, bytes)))

        val algorithm = sequence(oid("608648016503040201"), der(0x05, byteArrayOf()))
        val encryptedKey = sequence(algorithm, der(0x04, byteArrayOf(1, 2, 3)))
        val bag = sequence(
            oid("2a864886f70d010c0a0102"),
            der(0xa0, encryptedKey),
        )
        val authenticatedSafe = sequence(contentInfo(sequence(bag)))
        val authSafe = sequence(
            oid("2a864886f70d010701"),
            der(0xa0, der(0x04, authenticatedSafe)),
        )
        val mac = sequence(sequence(algorithm, der(0x04, ByteArray(32))), der(0x04, byteArrayOf(1)))
        return sequence(der(0x02, byteArrayOf(3)), authSafe, mac)
    }

    private class FakeClient : ConnectionsClient {
        var discovery: EndpointDiscoveryCallback? = null
        var lifecycle: ConnectionLifecycleCallback? = null
        var payloads: PayloadCallback? = null
        var options: ConnectionOptions? = null
        var discoveryStarts = 0
        var advertisingStarts = 0
        var accepts = 0
        var rejects = 0
        var stops = 0
        var discoveryTask: Task<Void> = Tasks.forResult(null)
        val sent = mutableListOf<ByteArray>()
        val payloadBuffers = mutableListOf<ByteArray>()
        private fun done(): Task<Void> = Tasks.forResult(null)
        override fun getApiKey(): ApiKey<ConnectionsOptions> = throw UnsupportedOperationException("Not used")
        override fun startDiscovery(service: String, callback: EndpointDiscoveryCallback, options: DiscoveryOptions): Task<Void> {
            discoveryStarts++
            discovery = callback
            return discoveryTask
        }
        override fun startAdvertising(name: String, service: String, callback: ConnectionLifecycleCallback, options: AdvertisingOptions): Task<Void> {
            advertisingStarts++
            lifecycle = callback
            assertFalse(options.disruptiveUpgrade)
            return done()
        }
        override fun startAdvertising(info: ByteArray, service: String, callback: ConnectionLifecycleCallback, options: AdvertisingOptions): Task<Void> =
            throw UnsupportedOperationException("No metadata advertising")
        override fun requestConnection(name: String, endpoint: String, callback: ConnectionLifecycleCallback, options: ConnectionOptions): Task<Void> {
            this.options = options
            lifecycle = callback
            return done()
        }
        override fun requestConnection(info: ByteArray, endpoint: String, callback: ConnectionLifecycleCallback, options: ConnectionOptions): Task<Void> =
            throw UnsupportedOperationException("No metadata requests")
        override fun requestConnection(name: String, endpoint: String, callback: ConnectionLifecycleCallback): Task<Void> =
            throw UnsupportedOperationException("Non-disruptive options required")
        override fun requestConnection(info: ByteArray, endpoint: String, callback: ConnectionLifecycleCallback): Task<Void> =
            throw UnsupportedOperationException("Non-disruptive options required")
        override fun acceptConnection(endpoint: String, callback: PayloadCallback): Task<Void> {
            accepts++
            payloads = callback
            return done()
        }
        override fun sendPayload(endpoint: String, payload: Payload): Task<Void> {
            val bytes = checkNotNull(payload.asBytes())
            sent.add(bytes.copyOf())
            payloadBuffers.add(bytes)
            return done()
        }
        override fun sendPayload(endpoints: List<String>, payload: Payload): Task<Void> =
            throw UnsupportedOperationException("Single peer required")
        override fun cancelPayload(id: Long) = done()
        override fun rejectConnection(endpoint: String): Task<Void> { rejects++; return done() }
        override fun disconnectFromEndpoint(endpoint: String) { stops++ }
        override fun stopAdvertising() { stops++ }
        override fun stopDiscovery() { stops++ }
        override fun stopAllEndpoints() { stops++ }
    }
}
