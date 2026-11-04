// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

object DropWire {
    const val SERVICE_ID = "com.weartak.plugin.nearbydrop.v2"
    const val ENDPOINT_NAME = "Nearby Drop v2"
    const val MAX_FRAME_BYTES = 32768
    const val MAX_CONTENT_BYTES = 32700
    private const val MAGIC = 0x574E4431
    private const val VERSION = 2
    private const val HEADER_BYTES = 26
    private const val OFFER_BYTES = 37

    enum class Type { OFFER, ACCEPT, DATA, RECEIPT }

    /** Typed payload kind, carried in the offer so the receiver can refuse unsupported content before acceptance. */
    enum class ContentKind(val code: Int) {
        PKCS12(1), SERVER_PACKAGE(2);
        companion object {
            fun of(code: Int) = entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unsupported content kind")
        }
    }

    class Frame(val type: Type, val id: UUID, val body: ByteArray) {
        fun clear() = body.fill(0)
        override fun toString() = "Frame(type=$type, body=[REDACTED])"
    }

    fun encode(frame: Frame): ByteArray {
        validate(frame)
        return ByteBuffer.allocate(HEADER_BYTES + frame.body.size)
            .putInt(MAGIC).put(VERSION.toByte()).put(frame.type.ordinal.toByte())
            .putLong(frame.id.mostSignificantBits).putLong(frame.id.leastSignificantBits)
            .putInt(frame.body.size).put(frame.body).array()
    }

    fun decode(bytes: ByteArray): Frame {
        require(bytes.size in HEADER_BYTES..MAX_FRAME_BYTES) { "Invalid frame size" }
        val input = ByteBuffer.wrap(bytes)
        require(input.int == MAGIC && input.get().toInt() == VERSION) { "Unsupported protocol" }
        val type = Type.entries.getOrNull(input.get().toInt())
            ?: throw IllegalArgumentException("Unsupported frame type")
        val id = UUID(input.long, input.long)
        val size = input.int
        require(size == input.remaining()) { "Invalid frame length" }
        return Frame(type, id, ByteArray(size).also(input::get)).also(::validate)
    }

    fun offer(id: UUID, content: ByteArray, kind: ContentKind = ContentKind.PKCS12): Frame {
        require(content.size in 1..MAX_CONTENT_BYTES) { "Invalid content size" }
        return Frame(Type.OFFER, id, ByteBuffer.allocate(OFFER_BYTES)
            .put(kind.code.toByte()).putInt(content.size).put(digest(content)).array())
    }

    fun offeredKind(frame: Frame): ContentKind {
        require(frame.type == Type.OFFER) { "Expected offer" }
        validate(frame)
        return ContentKind.of(frame.body[0].toInt())
    }

    fun offeredSize(frame: Frame): Int {
        require(frame.type == Type.OFFER) { "Expected offer" }
        validate(frame)
        return ByteBuffer.wrap(frame.body, 1, 4).int
    }

    fun matches(offer: Frame, content: ByteArray): Boolean =
        offeredSize(offer) == content.size &&
            MessageDigest.isEqual(offer.body.copyOfRange(5, OFFER_BYTES), digest(content))

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun validate(frame: Frame) {
        require(frame.body.size + HEADER_BYTES <= MAX_FRAME_BYTES) { "Frame exceeds budget" }
        when (frame.type) {
            Type.OFFER -> {
                require(frame.body.size == OFFER_BYTES &&
                    ByteBuffer.wrap(frame.body, 1, 4).int in 1..MAX_CONTENT_BYTES) { "Invalid offer" }
                ContentKind.of(frame.body[0].toInt())
            }
            Type.DATA -> require(frame.body.size in 1..MAX_CONTENT_BYTES) { "Invalid content size" }
            Type.ACCEPT, Type.RECEIPT -> require(frame.body.isEmpty()) { "Unexpected fields" }
        }
    }
}
