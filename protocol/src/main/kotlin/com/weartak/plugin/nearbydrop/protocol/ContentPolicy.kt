// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop.protocol

/**
 * Content kinds this watch can send or receive. Server packages are refused unless a validator
 * (the SDK-owned package codec) is supplied, so unsupported kinds fail before acceptance.
 */
class ContentPolicy(private val serverPackageValidator: ((ByteArray) -> Unit)? = null) {
    fun supports(kind: DropWire.ContentKind) = when (kind) {
        DropWire.ContentKind.PKCS12 -> true
        DropWire.ContentKind.SERVER_PACKAGE -> serverPackageValidator != null
    }

    fun validate(kind: DropWire.ContentKind, content: ByteArray) {
        when (kind) {
            DropWire.ContentKind.PKCS12 -> Pkcs12Envelope.validate(content)
            DropWire.ContentKind.SERVER_PACKAGE -> {
                val validator = serverPackageValidator
                    ?: throw IllegalArgumentException("Server packages are unsupported")
                require(content.size in 1..DropWire.MAX_CONTENT_BYTES) { "Invalid package size" }
                validator(content)
            }
        }
    }

    companion object {
        val PKCS12_ONLY = ContentPolicy()
    }
}
