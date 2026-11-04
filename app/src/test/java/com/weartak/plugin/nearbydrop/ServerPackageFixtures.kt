// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.nearbydrop

import android.os.SystemClock
import com.weartak.plugin.sdk.nearbyimport.Pkcs12ImportState
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12CancelResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ImportStatusParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12ReviewResultParcel
import com.weartak.plugin.sdk.nearbyimport.model.Pkcs12StageResultParcel
import com.weartak.plugin.sdk.serverpackage.ServerPackage
import com.weartak.plugin.sdk.serverpackage.ServerPackageCodec
import com.weartak.plugin.sdk.serverpackage.ServerPackageDiscovery
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportCode
import com.weartak.plugin.sdk.serverpackage.ServerPackageExportState
import com.weartak.plugin.sdk.serverpackage.ServerPackageIdentityMode
import com.weartak.plugin.sdk.serverpackage.ServerPackageTakeCode
import com.weartak.plugin.sdk.serverpackage.ServerPackageTrustMode
import com.weartak.plugin.sdk.serverpackage.aidl.IWearTakServerPackageControlV1
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageExportPayloadParcel
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageExportResultParcel
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageExportStatusParcel
import com.weartak.plugin.sdk.serverpackage.model.ServerPackageStageRequestParcel

/** Fixture-only data: synthetic host names and secrets, never real credentials. */
internal object ServerPackageFixtures {
    const val HANDLE = "abcdefghijklmnopqrstuv"
    const val SECRET_USER = "fixture-event-user"
    const val SECRET_PASSWORD = "fixture-secret-pass"

    fun sharedCredentialsPackage(): ByteArray = ServerPackageCodec.encode(
        ServerPackage(
            "Event TAK", "tak.example", 8089, ServerPackageTrustMode.PUBLIC_CA,
            ServerPackageIdentityMode.RECEIVER_ENROLLMENT, emptyList(), null, null,
            SECRET_USER, SECRET_PASSWORD.toByteArray(),
        ),
    )

    fun sharedIdentityPackage(): ByteArray = ServerPackageCodec.encode(
        ServerPackage(
            "Event TAK", "tak.example", 8089, ServerPackageTrustMode.PUBLIC_CA,
            ServerPackageIdentityMode.SHARED_IDENTITY, emptyList(),
            byteArrayOf(0x30) + ByteArray(2_000) { 7 }, SECRET_PASSWORD.toByteArray(),
        ),
    )

    fun supported(control: IWearTakServerPackageControlV1, receive: Boolean = true, export: Boolean = true) =
        ServerPackageDiscovery.Supported(control, 32_700, 300_000, 300_000, receive, export)

    class FakeControl : IWearTakServerPackageControlV1.Stub() {
        var exportCode = ServerPackageExportCode.PENDING
        var exportState = ServerPackageExportState.PENDING
        var exportReason = 0
        var exportPayload: ByteArray? = null
        var takeCode = ServerPackageTakeCode.OK
        var stageCode = 1
        var reviewState = Pkcs12ImportState.REVIEW_PENDING
        var statusState = Pkcs12ImportState.STAGED
        var stageCalls = 0
        var reviewCalls = 0
        var takeCalls = 0
        @Volatile var cancelExportCalls = 0
        @Volatile var cancelCalls = 0
        @Volatile var requestExportCalls = 0
        var stagedBytes: ByteArray? = null

        override fun stageServerPackage(request: ServerPackageStageRequestParcel): Pkcs12StageResultParcel {
            stageCalls++
            stagedBytes = request.encodedPackage.copyOf()
            return if (stageCode == 1) {
                Pkcs12StageResultParcel(stageCode, HANDLE, SystemClock.elapsedRealtime() + 60_000)
            } else Pkcs12StageResultParcel(stageCode)
        }
        override fun requestReview(handle: String): Pkcs12ReviewResultParcel {
            reviewCalls++
            return Pkcs12ReviewResultParcel(reviewState)
        }
        override fun getStatus(handle: String) = Pkcs12ImportStatusParcel(statusState)
        override fun cancel(handle: String): Pkcs12CancelResultParcel {
            cancelCalls++
            return Pkcs12CancelResultParcel(Pkcs12ImportState.CANCELLED)
        }
        override fun requestExport(requestId: String) = if (exportCode == ServerPackageExportCode.PENDING) {
            requestExportCalls++
            ServerPackageExportResultParcel(exportCode, HANDLE, SystemClock.elapsedRealtime() + 60_000)
        } else ServerPackageExportResultParcel(exportCode)
        override fun getExportStatus(handle: String) =
            ServerPackageExportStatusParcel(exportState, SystemClock.elapsedRealtime() + 60_000, exportReason)
        override fun takeExport(handle: String): ServerPackageExportPayloadParcel {
            takeCalls++
            return if (takeCode == ServerPackageTakeCode.OK) {
                ServerPackageExportPayloadParcel(takeCode, exportPayload)
            } else ServerPackageExportPayloadParcel(takeCode, null)
        }
        override fun cancelExport(handle: String): Pkcs12CancelResultParcel {
            synchronized(this) { cancelExportCalls++ }
            return Pkcs12CancelResultParcel(Pkcs12ImportState.CANCELLED)
        }
    }
}
