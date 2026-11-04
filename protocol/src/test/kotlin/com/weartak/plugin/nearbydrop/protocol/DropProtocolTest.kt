// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import java.util.UUID

class DropProtocolTest {
    @Test fun `wire roundtrip includes no filename or password fields`() {
        val frame = DropWire.offer(UUID.randomUUID(), Fixtures.pfx())
        val decoded = DropWire.decode(DropWire.encode(frame))
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.id, decoded.id)
        assertArrayEquals(frame.body, decoded.body)
        assertEquals(Fixtures.pfx().size, DropWire.offeredSize(decoded))
        assertFalse(decoded.toString().contains(decoded.id.toString()))
    }

    @Test fun `frame rejects trailing truncated oversized wrong version and unknown type`() {
        val valid = DropWire.encode(DropWire.offer(UUID.randomUUID(), Fixtures.pfx()))
        rejects { DropWire.decode(valid + byteArrayOf(0)) }
        rejects { DropWire.decode(valid.copyOf(valid.size - 1)) }
        rejects { DropWire.decode(ByteArray(DropWire.MAX_FRAME_BYTES + 1)) }
        rejects { DropWire.decode(valid.copyOf().apply { this[4] = 1 }) }
        rejects { DropWire.decode(valid.copyOf().apply { this[5] = 99 }) }
        rejects { DropWire.decode(valid.copyOf().apply { ByteBuffer.wrap(this, 22, 4).putInt(-1) }) }
    }

    @Test fun `exact content and wire budgets enforced`() {
        val id = UUID.randomUUID()
        val bytes = ByteArray(DropWire.MAX_CONTENT_BYTES)
        assertTrue(DropWire.encode(DropWire.Frame(DropWire.Type.DATA, id, bytes)).size <= DropWire.MAX_FRAME_BYTES)
        rejects { DropWire.encode(DropWire.Frame(DropWire.Type.DATA, id, bytes + 0)) }
        rejects { DropWire.offer(id, byteArrayOf()) }
        rejects { DropWire.encode(DropWire.Frame(DropWire.Type.ACCEPT, id, byteArrayOf(1))) }
        rejects { DropWire.encode(DropWire.Frame(DropWire.Type.RECEIPT, id, byteArrayOf(1))) }
    }

    @Test fun `pfx structure allows encrypted keys but rejects plaintext and secret bags`() {
        Pkcs12Envelope.validate(Fixtures.pfx())
        rejects { Pkcs12Envelope.validate(Fixtures.pfx(bagSuffix = 1)) }
        rejects { Pkcs12Envelope.validate(Fixtures.pfx(bagSuffix = 5)) }
        rejects { Pkcs12Envelope.validate(Fixtures.pfx(bagSuffix = 6)) }
        rejects { Pkcs12Envelope.validate(Fixtures.pfx() + 0) }
        rejects { Pkcs12Envelope.validate(Fixtures.pfx().copyOf(20)) }
        rejects { Pkcs12Envelope.validate("tak://com.atakmap.app/enroll?token=forbidden".toByteArray()) }
        rejects { Pkcs12Envelope.validate("""{"password":"forbidden"}""".toByteArray()) }
        rejects { Pkcs12Envelope.validate(byteArrayOf(0x30, 0x80.toByte(), 0, 0)) }
        rejects { Pkcs12Envelope.validate(ByteArray(DropWire.MAX_CONTENT_BYTES + 1)) }
    }

    @Test fun `offer carries typed content kind and rejects unknown kinds`() {
        val pkcs12 = DropWire.decode(DropWire.encode(DropWire.offer(UUID.randomUUID(), Fixtures.pfx())))
        assertEquals(DropWire.ContentKind.PKCS12, DropWire.offeredKind(pkcs12))
        val pkg = DropWire.offer(UUID.randomUUID(), byteArrayOf(1, 2), DropWire.ContentKind.SERVER_PACKAGE)
        assertEquals(DropWire.ContentKind.SERVER_PACKAGE, DropWire.offeredKind(DropWire.decode(DropWire.encode(pkg))))
        val encoded = DropWire.encode(pkg)
        rejects { DropWire.decode(encoded.copyOf().apply { this[26] = 0 }) }
        rejects { DropWire.decode(encoded.copyOf().apply { this[26] = 9 }) }
    }

    @Test fun `receiver without package support fails closed before review or acceptance`() {
        val output = mutableListOf<ByteArray>()
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, output::add, {}, {})
        authenticate(receiver)
        receiver.receive(DropWire.encode(DropWire.offer(UUID.randomUUID(), byteArrayOf(1), DropWire.ContentKind.SERVER_PACKAGE)))
        assertEquals(DropSession.State.FAILED, receiver.state)
        assertTrue(output.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            DropSession(DropSession.Role.SENDER, { 0 }, {}, {}, {}, byteArrayOf(1), DropWire.ContentKind.SERVER_PACKAGE)
        }
    }

    @Test fun `package transfer uses injected validator on both ends and receipt stays receipt only`() {
        val validated = mutableListOf<Int>()
        val policy = ContentPolicy { require(it.isNotEmpty() && it[0] == 0x57.toByte()) { "bad package" }; validated += it.size }
        val pkg = byteArrayOf(0x57, 1, 2, 3)
        val senderOutput = mutableListOf<ByteArray>()
        val receiverOutput = mutableListOf<ByteArray>()
        val sender = DropSession(DropSession.Role.SENDER, { 0 }, senderOutput::add, {}, {}, pkg,
            DropWire.ContentKind.SERVER_PACKAGE, policy)
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, receiverOutput::add, {}, {}, policy = policy)
        authenticate(sender)
        authenticate(receiver)
        receiver.receive(senderOutput.removeAt(0))
        assertEquals(DropSession.State.REVIEW, receiver.state)
        assertEquals(DropWire.ContentKind.SERVER_PACKAGE, receiver.offeredKind)
        receiver.acceptOffer()
        sender.receive(receiverOutput.removeAt(0))
        receiver.receive(senderOutput.removeAt(0))
        assertEquals(DropSession.State.RECEIVED, receiver.state)
        sender.receive(receiverOutput.removeAt(0))
        assertEquals(DropSession.State.DELIVERED, sender.state)
        assertEquals(listOf(4, 4), validated)
        assertArrayEquals(pkg, receiver.takeReceivedContent())
    }

    @Test fun `no offer before local peer verification`() {
        val sent = mutableListOf<ByteArray>()
        val session = sender(sent)
        assertEquals(DropSession.State.CONNECTING, session.state)
        rejectsState { session.connected() }
        session.verifying()
        rejects { session.connected() }
        assertTrue(sent.isEmpty())
        session.confirmPeer()
        assertTrue(sent.isEmpty())
        session.connected()
        assertEquals(DropSession.State.WAITING_ACCEPTANCE, session.state)
        assertEquals(DropWire.Type.OFFER, DropWire.decode(sent.single()).type)
    }

    @Test fun `receiver acceptance gates data and receipt never means applied`() {
        val senderOutput = mutableListOf<ByteArray>()
        val receiverOutput = mutableListOf<ByteArray>()
        val sender = sender(senderOutput)
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, receiverOutput::add, {}, {})
        authenticate(sender)
        authenticate(receiver)
        receiver.receive(senderOutput.removeAt(0))
        assertEquals(DropSession.State.REVIEW, receiver.state)
        assertTrue(receiverOutput.isEmpty())
        receiver.acceptOffer()
        sender.receive(receiverOutput.removeAt(0))
        assertEquals(DropSession.State.WAITING_RECEIPT, sender.state)
        receiver.receive(senderOutput.removeAt(0))
        assertEquals(DropSession.State.RECEIVED, receiver.state)
        sender.receive(receiverOutput.removeAt(0))
        assertEquals(DropSession.State.DELIVERED, sender.state)
    }

    @Test fun `received content can be taken once for explicit host handoff`() {
        val senderOutput = mutableListOf<ByteArray>()
        val receiverOutput = mutableListOf<ByteArray>()
        val sender = sender(senderOutput)
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, receiverOutput::add, {}, {})
        authenticate(sender)
        authenticate(receiver)
        receiver.receive(senderOutput.removeAt(0))
        receiver.acceptOffer()
        sender.receive(receiverOutput.removeAt(0))
        val input = senderOutput.removeAt(0)
        receiver.receive(input)
        input.fill(0)

        val handedOff = receiver.takeReceivedContent()
        assertArrayEquals(Fixtures.pfx(), handedOff)
        assertEquals(DropSession.State.RECEIVED, receiver.state)
        assertThrows(IllegalStateException::class.java) { receiver.takeReceivedContent() }
        handedOff.fill(0)
    }

    @Test fun `unsolicited bytes before authentication or acceptance fail closed`() {
        val content = Fixtures.pfx()
        val data = DropWire.encode(DropWire.Frame(DropWire.Type.DATA, UUID.randomUUID(), content))
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, {}, {}, {})
        receiver.receive(data)
        assertEquals(DropSession.State.FAILED, receiver.state)
        val connected = DropSession(DropSession.Role.RECEIVER, { 0 }, {}, {}, {})
        authenticate(connected)
        connected.receive(data)
        assertEquals(DropSession.State.FAILED, connected.state)
    }

    @Test fun `wrong transfer id duplicate offer and corrupted content fail closed`() {
        val outputs = mutableListOf<ByteArray>()
        val sender = sender(outputs)
        authenticate(sender)
        sender.receive(DropWire.encode(DropWire.Frame(DropWire.Type.ACCEPT, UUID.randomUUID(), byteArrayOf())))
        assertEquals(DropSession.State.FAILED, sender.state)
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, {}, {}, {})
        authenticate(receiver)
        receiver.receive(outputs.single())
        receiver.receive(outputs.single())
        assertEquals(DropSession.State.FAILED, receiver.state)
        val receiver2 = DropSession(DropSession.Role.RECEIVER, { 0 }, {}, {}, {})
        authenticate(receiver2)
        receiver2.receive(outputs.single())
        receiver2.acceptOffer()
        val offer = DropWire.decode(outputs.single())
        receiver2.receive(DropWire.encode(DropWire.Frame(DropWire.Type.DATA, offer.id, Fixtures.pfx().apply { this[lastIndex] = 5 })))
        assertEquals(DropSession.State.FAILED, receiver2.state)
    }

    @Test fun `transport success without receiver receipt is not delivery`() {
        val outputs = mutableListOf<ByteArray>()
        val sender = sender(outputs)
        authenticate(sender)
        val id = DropWire.decode(outputs.single()).id
        sender.receive(DropWire.encode(DropWire.Frame(DropWire.Type.ACCEPT, id, byteArrayOf())))
        assertEquals(DropSession.State.WAITING_RECEIPT, sender.state)
        sender.disconnected()
        assertEquals(DropSession.State.FAILED, sender.state)
    }

    @Test fun `absolute timeout cancellation and disconnect close session`() {
        var time = 0L
        var closed = 0
        val sender = DropSession(DropSession.Role.SENDER, { time }, {}, { closed++ }, {}, Fixtures.pfx())
        authenticate(sender)
        time = DropSession.TIMEOUT_MS - 1
        sender.tick()
        assertEquals(DropSession.State.WAITING_ACCEPTANCE, sender.state)
        time++
        sender.tick()
        assertEquals(DropSession.State.FAILED, sender.state)
        assertEquals(1, closed)
        sender.cancel()
        assertEquals(DropSession.State.CANCELLED, sender.state)
        rejectsState { sender.confirmPeer() }
    }

    @Test fun `sender owns independent content and rejected payload has no receiver receipt`() {
        val input = Fixtures.pfx()
        val outputs = mutableListOf<ByteArray>()
        val sender = DropSession(DropSession.Role.SENDER, { 0 }, outputs::add, {}, {}, input)
        input.fill(0)
        authenticate(sender)
        assertEquals(Fixtures.pfx().size, DropWire.offeredSize(DropWire.decode(outputs.single())))
        val received = mutableListOf<ByteArray>()
        val receiver = DropSession(DropSession.Role.RECEIVER, { 0 }, received::add, {}, {})
        authenticate(receiver)
        receiver.receive(outputs.single())
        receiver.cancel()
        receiver.receive(DropWire.encode(DropWire.Frame(DropWire.Type.DATA, UUID.randomUUID(), Fixtures.pfx())))
        assertEquals(DropSession.State.CANCELLED, receiver.state)
        assertTrue(received.isEmpty())
    }

    @Test fun `bounded random envelope inputs never escape parser validation errors`() {
        val random = Random(0x4e445031)
        repeat(512) {
            val candidate = ByteArray(random.nextInt(DropWire.MAX_CONTENT_BYTES + 1))
            random.nextBytes(candidate)
            try {
                Pkcs12Envelope.validate(candidate)
            } catch (_: IllegalArgumentException) {
                // Malformed input is rejected through the validator's public contract.
            } catch (error: Exception) {
                fail("Unexpected parser exception: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun sender(output: MutableList<ByteArray>) =
        DropSession(DropSession.Role.SENDER, { 0 }, output::add, {}, {}, Fixtures.pfx())

    private fun authenticate(session: DropSession) {
        session.verifying()
        session.confirmPeer()
        session.connected()
    }

    private fun rejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }
    private fun rejectsState(block: () -> Unit) {
        assertThrows(IllegalStateException::class.java, block)
    }
}

internal object Fixtures {
    // Structural fixture only: deliberately not a cryptographically valid identity.
    fun pfx(bagSuffix: Int = 2): ByteArray {
        val algorithm = seq(oid("608648016503040201"), der(0x05, byteArrayOf()))
        val encryptedKey = seq(algorithm, der(0x04, byteArrayOf(1, 2, 3)))
        val bag = seq(oid("2a864886f70d010c0a01" + "%02x".format(bagSuffix)), der(0xa0, encryptedKey))
        val safe = content(seq(bag))
        val authSafe = content(seq(safe))
        val mac = seq(seq(algorithm, der(0x04, ByteArray(32))), der(0x04, byteArrayOf(1)))
        return seq(der(0x02, byteArrayOf(3)), authSafe, mac)
    }
    private fun content(bytes: ByteArray) = seq(oid("2a864886f70d010701"), der(0xa0, der(0x04, bytes)))
    private fun oid(hex: String) = der(0x06, hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    private fun seq(vararg values: ByteArray) = der(0x30, values.fold(byteArrayOf()) { a, b -> a + b })
    private fun der(tag: Int, bytes: ByteArray): ByteArray {
        val length = when {
            bytes.size < 128 -> byteArrayOf(bytes.size.toByte())
            bytes.size <= 255 -> byteArrayOf(0x81.toByte(), bytes.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + bytes
    }
}
