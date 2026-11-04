// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.weartak.plugin.nearbydrop.protocol.DropWire
import com.weartak.plugin.sdk.serverpackage.ServerPackageCodec

/**
 * Debug-only bound service accepting one synthetic server package from the initialized `.nearbyfixture`
 * host. Absent from release builds. Payload bytes are never logged.
 */
class FixtureReceiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == ACTION) binder else null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_DELIVER) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            val result = deliver(data, Binder.getCallingUid())
            reply?.writeInt(result)
            return true
        }
    }

    internal fun deliver(data: Parcel, caller: Int): Int {
        val host = NearbyDropService.InitializedHost.current
        if (host == null || host.uid != caller || !host.packageName.endsWith(FIXTURE_PACKAGE_SUFFIX) ||
            packageManager.getPackagesForUid(caller)?.contains(host.packageName) != true
        ) return RESULT_NOT_FIXTURE_HOST
        if (data.readString() != MARKER) return RESULT_INVALID
        val start = data.dataPosition()
        val length = data.readInt()
        if (length > DropWire.MAX_CONTENT_BYTES) return RESULT_TOO_LARGE
        if (length <= 0) return RESULT_INVALID
        data.setDataPosition(start)
        val bytes = data.createByteArray() ?: return RESULT_INVALID
        if (!ServerPackageBridge.canReceive()) {
            bytes.fill(0)
            return RESULT_RECEIVE_UNSUPPORTED
        }
        if (bytes.size != length || ServerPackageCodec.validate(bytes) != null) {
            bytes.fill(0)
            return RESULT_INVALID
        }
        return if (FixtureHook.offer(bytes, host)) RESULT_ACCEPTED else RESULT_BUSY
    }

    companion object {
        const val ACTION = "com.weartak.plugin.nearbydrop.debug.FIXTURE_RECEIVE"
        const val DESCRIPTOR = "com.weartak.plugin.nearbydrop.debug.IFixtureReceive"
        const val TRANSACTION_DELIVER = IBinder.FIRST_CALL_TRANSACTION
        const val MARKER = "WEARTAK-NEARBY-DROP-SYNTHETIC-FIXTURE-V1"
        const val FIXTURE_PACKAGE_SUFFIX = ".nearbyfixture"

        const val RESULT_ACCEPTED = 0
        const val RESULT_NOT_FIXTURE_HOST = 1
        const val RESULT_RECEIVE_UNSUPPORTED = 2
        const val RESULT_INVALID = 3
        const val RESULT_TOO_LARGE = 4
        const val RESULT_BUSY = 5
    }
}
