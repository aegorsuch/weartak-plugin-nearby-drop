// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

/**
 * Restricts input to bounded PFX containers with a MAC and no visible plaintext key/secret bags.
 * Cryptographic validity, passwords, identities and CA approval remain host responsibilities.
 */
object Pkcs12Envelope {
    private val DATA = hex("2a864886f70d010701")
    private val ENCRYPTED_DATA = hex("2a864886f70d010706")
    private val SHROUDED_KEY = hex("2a864886f70d010c0a0102")
    private val CERTIFICATE = hex("2a864886f70d010c0a0103")
    private val X509 = hex("2a864886f70d01091601")

    fun validate(bytes: ByteArray) {
        require(bytes.size in 1..DropWire.MAX_CONTENT_BYTES) { "Invalid PKCS12 size" }
        val root = Reader(bytes).single(0x30).reader()
        require(root.next(0x02).equalsBytes(byteArrayOf(3))) { "Expected PKCS12 version 3" }
        val authSafe = contentInfo(root.next(0x30))
        require(authSafe.first.equalsBytes(DATA)) { "Unsupported authenticated safe" }
        val authenticated = authSafe.second.reader().single(0x04)
        val safes = authenticated.reader().single(0x30).reader()
        var count = 0
        while (safes.hasNext()) {
            require(++count <= 32) { "Too many safe contents" }
            val safe = contentInfo(safes.next(0x30))
            when {
                safe.first.equalsBytes(DATA) -> validateBags(safe.second.reader().single(0x04))
                safe.first.equalsBytes(ENCRYPTED_DATA) -> {
                    val encrypted = safe.second.reader().single(0x30).reader()
                    require(encrypted.next(0x02).equalsBytes(byteArrayOf(0))) { "Invalid encrypted safe" }
                    val info = encrypted.next(0x30).reader()
                    require(info.next(0x06).equalsBytes(DATA)) { "Unsupported encrypted content" }
                    validateAlgorithm(info.next(0x30))
                    require(info.next(0x80).size > 0) { "Empty encrypted content" }
                    info.end()
                    encrypted.end()
                }
                else -> throw IllegalArgumentException("Unsupported safe content")
            }
        }
        require(count > 0) { "Empty authenticated safe" }
        val mac = root.next(0x30).reader()
        val digestInfo = mac.next(0x30).reader()
        validateAlgorithm(digestInfo.next(0x30))
        require(digestInfo.next(0x04).size in 16..64) { "Invalid MAC" }
        digestInfo.end()
        require(mac.next(0x04).size in 1..64) { "Invalid MAC salt" }
        if (mac.hasNext()) require(mac.next(0x02).size in 1..4) { "Invalid MAC iteration count" }
        mac.end()
        root.end()
    }

    private fun validateBags(octets: Element) {
        val bags = octets.reader().single(0x30).reader()
        var count = 0
        while (bags.hasNext()) {
            require(++count <= 32) { "Too many bags" }
            val bag = bags.next(0x30).reader()
            val kind = bag.next(0x06)
            val value = bag.next(0xa0).reader().single(0x30).reader()
            when {
                kind.equalsBytes(SHROUDED_KEY) -> {
                    validateAlgorithm(value.next(0x30))
                    require(value.next(0x04).size > 0) { "Empty encrypted key" }
                }
                kind.equalsBytes(CERTIFICATE) -> {
                    require(value.next(0x06).equalsBytes(X509)) { "Unsupported certificate bag" }
                    require(value.next(0xa0).reader().single(0x04).size > 0) { "Empty certificate" }
                }
                else -> throw IllegalArgumentException("Plaintext keys and unsupported bags are forbidden")
            }
            value.end()
            // Optional attributes are not needed for transport and may conceal arbitrary metadata.
            if (bag.hasNext()) validateAttributes(bag.next(0x31))
            bag.end()
        }
        require(count > 0) { "Empty safe contents" }
    }

    private fun validateAttributes(attributes: Element) {
        val reader = attributes.reader()
        var count = 0
        val friendlyName = hex("2a864886f70d010914")
        val localKeyId = hex("2a864886f70d010915")
        while (reader.hasNext()) {
            require(++count <= 2) { "Too many bag attributes" }
            val attribute = reader.next(0x30).reader()
            val oid = attribute.next(0x06)
            val values = attribute.next(0x31).reader()
            when {
                oid.equalsBytes(friendlyName) -> require(values.next(0x1e).size <= 512) { "Name too long" }
                oid.equalsBytes(localKeyId) -> require(values.next(0x04).size <= 128) { "Key id too long" }
                else -> throw IllegalArgumentException("Unsupported bag attribute")
            }
            values.end()
            attribute.end()
        }
    }

    private fun contentInfo(element: Element): Pair<Element, Element> {
        val reader = element.reader()
        val oid = reader.next(0x06)
        val value = reader.next(0xa0)
        reader.end()
        return oid to value
    }

    private fun validateAlgorithm(element: Element) {
        val reader = element.reader()
        require(reader.next(0x06).size in 1..32) { "Invalid algorithm" }
        if (reader.hasNext()) reader.next()
        reader.end()
    }

    private class Element(val source: ByteArray, val start: Int, val size: Int) {
        fun reader() = Reader(source, start, start + size)
        fun equalsBytes(expected: ByteArray) =
            size == expected.size && expected.indices.all { source[start + it] == expected[it] }
    }

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int = 0,
        private val limit: Int = bytes.size,
    ) {
        fun hasNext() = position < limit
        fun end() = require(position == limit) { "Unexpected PKCS12 fields" }
        fun single(tag: Int): Element = next(tag).also { end() }
        fun next(expectedTag: Int? = null): Element {
            require(limit - position >= 2) { "Truncated PKCS12" }
            val tag = bytes[position++].toInt() and 0xff
            require(tag and 0x1f != 0x1f && (expectedTag == null || tag == expectedTag)) { "Unexpected PKCS12 field" }
            var size = bytes[position++].toInt() and 0xff
            if (size >= 128) {
                val count = size and 0x7f
                require(count in 1..2 && limit - position >= count) { "Unsupported PKCS12 length" }
                require(bytes[position].toInt() != 0) { "Noncanonical PKCS12 length" }
                size = 0
                repeat(count) { size = (size shl 8) or (bytes[position++].toInt() and 0xff) }
                require(size >= 128) { "Noncanonical PKCS12 length" }
            }
            require(size <= limit - position) { "Truncated PKCS12 field" }
            return Element(bytes, position, size).also { position += size }
        }
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
