// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

import java.util.UUID

/** Single-peer, single-offer session. All calls belong to the transport's serial executor. */
class DropSession(
    val role: Role,
    private val now: () -> Long,
    private val send: (ByteArray) -> Unit,
    private val closeTransport: () -> Unit,
    private val changed: (State) -> Unit,
    content: ByteArray? = null,
    val contentKind: DropWire.ContentKind = DropWire.ContentKind.PKCS12,
    private val policy: ContentPolicy = ContentPolicy.PKCS12_ONLY,
) {
    enum class Role { SENDER, RECEIVER }
    enum class State { CONNECTING, VERIFYING, CONNECTED, WAITING_ACCEPTANCE, REVIEW, WAITING_DATA, WAITING_RECEIPT, RECEIVED, DELIVERED, CANCELLED, FAILED }

    var state = State.CONNECTING
        private set
    var offeredBytes = 0
        private set
    var offeredKind: DropWire.ContentKind? = null
        private set
    private val deadline = now() + TIMEOUT_MS
    private var outgoing: ByteArray? = null
    private var incoming: ByteArray? = null
    private var offer: DropWire.Frame? = null
    private var verified = false
    private var id: UUID? = null

    init {
        require((role == Role.SENDER) == (content != null)) { "Invalid session content" }
        content?.let {
            policy.validate(contentKind, it)
            outgoing = it.copyOf()
        }
    }

    fun verifying() {
        requireState(State.CONNECTING)
        move(State.VERIFYING)
    }

    fun confirmPeer() {
        requireState(State.VERIFYING)
        verified = true
    }

    fun connected() {
        requireState(State.VERIFYING)
        require(verified) { "Peer not verified" }
        move(State.CONNECTED)
        if (role == Role.SENDER) {
            val transfer = UUID.randomUUID()
            id = transfer
            val frame = DropWire.offer(transfer, checkNotNull(outgoing), contentKind)
            offer = frame
            move(State.WAITING_ACCEPTANCE)
            emit(frame)
        }
    }

    fun acceptOffer() {
        requireState(State.REVIEW)
        move(State.WAITING_DATA)
        emit(DropWire.Frame(DropWire.Type.ACCEPT, checkNotNull(id), byteArrayOf()))
    }

    fun takeReceivedContent(): ByteArray {
        requireState(State.RECEIVED)
        val content = checkNotNull(incoming) { "Received content already consumed" }
        incoming = null
        return content.copyOf().also { content.fill(0) }
    }

    fun receive(bytes: ByteArray) {
        try {
            require(!expired() && verified && !terminal()) { "Inactive session" }
            val frame = DropWire.decode(bytes)
            try {
                when (frame.type) {
                    DropWire.Type.OFFER -> {
                        require(role == Role.RECEIVER && state == State.CONNECTED) { "Unsolicited offer" }
                        val kind = DropWire.offeredKind(frame)
                        require(policy.supports(kind)) { "Unsupported content kind" }
                        id = frame.id
                        offer = DropWire.Frame(frame.type, frame.id, frame.body.copyOf())
                        offeredBytes = DropWire.offeredSize(frame)
                        offeredKind = kind
                        move(State.REVIEW)
                    }
                    DropWire.Type.ACCEPT -> {
                        require(role == Role.SENDER && state == State.WAITING_ACCEPTANCE && frame.id == id) { "Unexpected acceptance" }
                        move(State.WAITING_RECEIPT)
                        emit(DropWire.Frame(DropWire.Type.DATA, frame.id, checkNotNull(outgoing)))
                    }
                    DropWire.Type.DATA -> {
                        require(role == Role.RECEIVER && state == State.WAITING_DATA && frame.id == id) { "Unsolicited data" }
                        require(DropWire.matches(checkNotNull(offer), frame.body)) { "Content mismatch" }
                        policy.validate(checkNotNull(offeredKind), frame.body)
                        incoming = frame.body.copyOf()
                        move(State.RECEIVED)
                        emit(DropWire.Frame(DropWire.Type.RECEIPT, frame.id, byteArrayOf()))
                        clearOffer()
                    }
                    DropWire.Type.RECEIPT -> {
                        require(role == Role.SENDER && state == State.WAITING_RECEIPT && frame.id == id) { "Unexpected receipt" }
                        clearContent()
                        move(State.DELIVERED)
                        closeTransport()
                    }
                }
            } finally {
                frame.clear()
            }
        } catch (_: IllegalArgumentException) {
            fail()
        }
    }

    fun tick() {
        if (!terminal() && expired()) fail()
    }

    fun disconnected() {
        if (state != State.RECEIVED && !terminal()) fail()
    }

    fun fail() {
        if (!terminal()) finish(State.FAILED)
    }

    fun cancel() = finish(State.CANCELLED)

    private fun expired() = now() >= deadline
    private fun terminal() = state in setOf(State.DELIVERED, State.CANCELLED, State.FAILED)
    private fun requireState(expected: State) {
        check(state == expected && !expired()) { "Invalid session action" }
    }
    private fun move(next: State) {
        state = next
        changed(next)
    }
    private fun emit(frame: DropWire.Frame) = send(DropWire.encode(frame))
    private fun clearOffer() {
        offer?.clear()
        offer = null
    }
    private fun clearContent() {
        outgoing?.fill(0)
        outgoing = null
        incoming?.fill(0)
        incoming = null
        clearOffer()
    }
    private fun finish(result: State) {
        clearContent()
        verified = false
        id = null
        move(result)
        closeTransport()
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
    }
}
